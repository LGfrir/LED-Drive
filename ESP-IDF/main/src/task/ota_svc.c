/**
 * @file ota_svc.c
 * @brief OTA downloader.
 *
 * Two allowed transports:
 *   - https://...                 remote server, certificate bundle
 *   - http://... + local_http     phone hotspot local server; requires token
 *
 * The firmware is written to the inactive OTA partition, SHA256 is verified
 * when provided, then the boot partition is switched. The new image must call
 * ota_boot_confirm() after a successful boot or the bootloader rolls back.
 */

#include "task/ota_svc.h"

#include "driver/wifi/wifi_mgr.h"

#include "esp_app_desc.h"
#include "esp_check.h"
#include "esp_crt_bundle.h"
#include "esp_http_client.h"
#include "esp_log.h"
#include "esp_ota_ops.h"
#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "psa/crypto.h"

#include <stdio.h>
#include <string.h>
#include <strings.h>
#include <time.h>

static const char *TAG = "OTA";

static SemaphoreHandle_t s_lock;
static TaskHandle_t s_task;
static ota_status_t s_status;
static ota_request_t s_req;
static bool s_pending;
static volatile bool s_running;
static volatile bool s_abort;

static void status_lock(void)
{
    if (s_lock != NULL) {
        xSemaphoreTake(s_lock, portMAX_DELAY);
    }
}

static void status_unlock(void)
{
    if (s_lock != NULL) {
        xSemaphoreGive(s_lock);
    }
}

static void set_state(ota_state_t state, uint8_t progress, const char *msg)
{
    status_lock();
    s_status.state = state;
    s_status.progress = progress;
    if (msg != NULL) {
        strncpy(s_status.error, msg, sizeof(s_status.error) - 1);
        s_status.error[sizeof(s_status.error) - 1] = '\0';
    }
    status_unlock();
}

static void fail(const char *msg)
{
    status_lock();
    s_status.state = OTA_FAILED;
    strncpy(s_status.error, msg != NULL ? msg : "failed", sizeof(s_status.error) - 1);
    s_status.error[sizeof(s_status.error) - 1] = '\0';
    status_unlock();
    ESP_LOGE(TAG, "%s", msg != NULL ? msg : "failed");
}

static void update_progress(uint32_t read, uint32_t total)
{
    status_lock();
    s_status.bytes_read = read;
    s_status.bytes_total = total;
    if (total > 0) {
        uint32_t pct = (read * 100u) / total;
        s_status.progress = (uint8_t)((pct > 100u) ? 100u : pct);
    }
    status_unlock();
}

static void hex_encode(const uint8_t *data, size_t len, char *out)
{
    static const char hex[] = "0123456789abcdef";
    for (size_t i = 0; i < len; i++) {
        out[i * 2] = hex[data[i] >> 4];
        out[i * 2 + 1] = hex[data[i] & 0x0F];
    }
    out[len * 2] = '\0';
}

static void ota_run(void)
{
    ota_request_t req;
    status_lock();
    req = s_req;
    status_unlock();

    esp_http_client_handle_t client = NULL;
    esp_ota_handle_t ota = 0;
    bool ota_open = false;
    bool http_open = false;
    const esp_partition_t *update = NULL;
    uint8_t *buf = NULL;
    psa_hash_operation_t sha = PSA_HASH_OPERATION_INIT;
    bool sha_active = false;
    int64_t content_len = 0;
    uint32_t total_read = 0;
    bool https = (strncmp(req.url, "https://", 8) == 0);
    bool http = (strncmp(req.url, "http://", 7) == 0);

    if (!https && !(http && req.local_http)) {
        fail("URL must be https, or http only in local mode");
        s_running = false;
        return;
    }

    set_state(OTA_CONNECTING_WIFI, 0, "connecting wifi");
    esp_err_t err = wifi_mgr_connect(req.ssid, req.password, 20000);
    if (err != ESP_OK) {
        char msg[OTA_ERR_MAX];
        snprintf(msg, sizeof(msg), "wifi: %s", esp_err_to_name(err));
        fail(msg);
        goto cleanup;
    }

    if (https) {
        set_state(OTA_CONNECTING_WIFI, 0, "syncing time");
        err = wifi_mgr_sync_time(12000);
        if (err != ESP_OK) {
            fail("SNTP failed (TLS needs time)");
            goto cleanup;
        }
    }

    esp_http_client_config_t cfg = {
        .url = req.url,
        .timeout_ms = 30000,
        .buffer_size = 4096,
        .keep_alive_enable = true,
        .crt_bundle_attach = https ? esp_crt_bundle_attach : NULL,
    };
    client = esp_http_client_init(&cfg);
    if (client == NULL) {
        fail("http client init failed");
        goto cleanup;
    }
    if (req.token[0] != '\0') {
        esp_http_client_set_header(client, "X-OTA-Token", req.token);
    }

    err = esp_http_client_open(client, 0);
    if (err != ESP_OK) {
        char msg[OTA_ERR_MAX];
        snprintf(msg, sizeof(msg), "http open: %s", esp_err_to_name(err));
        fail(msg);
        goto cleanup;
    }
    http_open = true;

    content_len = esp_http_client_fetch_headers(client);
    if (content_len < 0) {
        fail("http headers failed");
        goto cleanup;
    }
    int status = esp_http_client_get_status_code(client);
    if (status != 200) {
        char msg[OTA_ERR_MAX];
        snprintf(msg, sizeof(msg), "http status %d", status);
        fail(msg);
        goto cleanup;
    }

    update = esp_ota_get_next_update_partition(NULL);
    if (update == NULL) {
        fail("no OTA partition");
        goto cleanup;
    }
    ESP_LOGI(TAG, "downloading %s to %s (%lld bytes)", req.url, update->label,
             (long long)content_len);

    err = esp_ota_begin(update, OTA_SIZE_UNKNOWN, &ota);
    if (err != ESP_OK) {
        char msg[OTA_ERR_MAX];
        snprintf(msg, sizeof(msg), "ota begin: %s", esp_err_to_name(err));
        fail(msg);
        goto cleanup;
    }
    ota_open = true;

    buf = malloc(4096);
    if (buf == NULL) {
        fail("no download buffer");
        goto cleanup;
    }

    if (psa_crypto_init() != PSA_SUCCESS ||
        psa_hash_setup(&sha, PSA_ALG_SHA_256) != PSA_SUCCESS) {
        fail("sha256 init failed");
        goto cleanup;
    }
    sha_active = true;

    set_state(OTA_DOWNLOADING, 0, "downloading");
    while (!s_abort) {
        int r = esp_http_client_read(client, (char *)buf, 4096);
        if (r < 0) {
            fail("http read failed");
            goto cleanup;
        }
        if (r == 0) {
            break;
        }
        err = esp_ota_write(ota, buf, (size_t)r);
        if (err != ESP_OK) {
            char msg[OTA_ERR_MAX];
            snprintf(msg, sizeof(msg), "ota write: %s", esp_err_to_name(err));
            fail(msg);
            goto cleanup;
        }
        psa_hash_update(&sha, buf, (size_t)r);
        total_read += (uint32_t)r;
        update_progress(total_read, (content_len > 0) ? (uint32_t)content_len : 0);
    }

    if (s_abort) {
        status_lock();
        s_status.state = OTA_ABORTED;
        strncpy(s_status.error, "aborted", sizeof(s_status.error) - 1);
        status_unlock();
        goto cleanup;
    }

    if (content_len > 0 && total_read != (uint32_t)content_len) {
        fail("short download");
        goto cleanup;
    }
    if (!esp_http_client_is_complete_data_received(client)) {
        fail("incomplete http data");
        goto cleanup;
    }

    set_state(OTA_VERIFYING, 100, "verifying");
    err = esp_ota_end(ota);
    ota_open = false;
    if (err != ESP_OK) {
        char msg[OTA_ERR_MAX];
        snprintf(msg, sizeof(msg), "ota end: %s", esp_err_to_name(err));
        fail(msg);
        goto cleanup;
    }

    if (req.sha256[0] != '\0') {
        uint8_t digest[32];
        size_t digest_len = 0;
        char hex[65];
        if (psa_hash_finish(&sha, digest, sizeof(digest), &digest_len) != PSA_SUCCESS ||
            digest_len != sizeof(digest)) {
            fail("sha256 finish failed");
            goto cleanup;
        }
        sha_active = false;
        hex_encode(digest, sizeof(digest), hex);
        if (strcasecmp(hex, req.sha256) != 0) {
            fail("sha256 mismatch");
            goto cleanup;
        }
        ESP_LOGI(TAG, "sha256 ok");
    }

    err = esp_ota_set_boot_partition(update);
    if (err != ESP_OK) {
        char msg[OTA_ERR_MAX];
        snprintf(msg, sizeof(msg), "set boot: %s", esp_err_to_name(err));
        fail(msg);
        goto cleanup;
    }

    status_lock();
    s_status.state = OTA_READY_REBOOT;
    s_status.progress = 100;
    strncpy(s_status.error, "ready to reboot", sizeof(s_status.error) - 1);
    status_unlock();
    ESP_LOGI(TAG, "update ready on %s", update->label);

    if (req.auto_reboot) {
        /* 给屏幕留出 REBOOT IN 3 -> 2 -> 1 -> REBOOTING 的显示时间。 */
        vTaskDelay(pdMS_TO_TICKS(3600));
        esp_restart();
    }
    goto cleanup;

cleanup:
    if (sha_active) {
        psa_hash_abort(&sha);
        sha_active = false;
    }
    free(buf);
    if (ota_open) {
        esp_ota_abort(ota);
    }
    if (http_open) {
        esp_http_client_close(client);
    }
    if (client != NULL) {
        esp_http_client_cleanup(client);
    }
    wifi_mgr_disconnect();

    status_lock();
    if (s_status.state != OTA_FAILED && s_status.state != OTA_ABORTED &&
        s_status.state != OTA_READY_REBOOT) {
        s_status.state = OTA_FAILED;
        if (s_status.error[0] == '\0') {
            strncpy(s_status.error, "unknown error", sizeof(s_status.error) - 1);
        }
    }
    status_unlock();
    s_running = false;
}

static void ota_task(void *arg)
{
    (void)arg;
    for (;;) {
        ulTaskNotifyTake(pdTRUE, portMAX_DELAY);
        if (!s_pending) {
            continue;
        }
        s_pending = false;
        ota_run();
    }
}

esp_err_t ota_init(void)
{
    if (s_task != NULL) {
        return ESP_OK;
    }

    s_lock = xSemaphoreCreateMutex();
    if (s_lock == NULL) {
        return ESP_ERR_NO_MEM;
    }

    memset(&s_status, 0, sizeof(s_status));
    s_status.state = OTA_IDLE;
    const esp_app_desc_t *desc = esp_app_get_description();
    if (desc != NULL) {
        strncpy(s_status.version, desc->version, sizeof(s_status.version) - 1);
    }
    const esp_partition_t *running = esp_ota_get_running_partition();
    if (running != NULL) {
        ESP_LOGI(TAG, "running from %s, version %s", running->label, s_status.version);
    }

    if (xTaskCreate(ota_task, "ota_svc", 8192, NULL, 5, &s_task) != pdPASS) {
        s_task = NULL;
        return ESP_ERR_NO_MEM;
    }
    ESP_LOGI(TAG, "ota service ready");
    return ESP_OK;
}

esp_err_t ota_start(const ota_request_t *req)
{
    if (req == NULL || req->url[0] == '\0' || req->ssid[0] == '\0') {
        return ESP_ERR_INVALID_ARG;
    }
    if (s_running) {
        return ESP_ERR_INVALID_STATE;
    }

    bool https = (strncmp(req->url, "https://", 8) == 0);
    bool http = (strncmp(req->url, "http://", 7) == 0);
    if (!https && !(http && req->local_http)) {
        return ESP_ERR_INVALID_ARG;
    }
    if (http && req->token[0] == '\0') {
        return ESP_ERR_INVALID_ARG;
    }
    if (req->sha256[0] != '\0' && strlen(req->sha256) != OTA_SHA256_LEN) {
        return ESP_ERR_INVALID_ARG;
    }

    status_lock();
    s_req = *req;
    s_pending = true;
    s_abort = false;
    s_running = true;
    s_status.state = OTA_CONNECTING_WIFI;
    s_status.progress = 0;
    s_status.bytes_read = 0;
    s_status.bytes_total = 0;
    s_status.error[0] = '\0';
    status_unlock();

    if (s_task != NULL) {
        xTaskNotifyGive(s_task);
    }
    return ESP_OK;
}

esp_err_t ota_abort(void)
{
    if (!s_running) {
        return ESP_ERR_INVALID_STATE;
    }
    s_abort = true;
    return ESP_OK;
}

void ota_get_status(ota_status_t *out)
{
    if (out == NULL) {
        return;
    }
    status_lock();
    *out = s_status;
    status_unlock();
}

bool ota_is_running(void)
{
    return s_running;
}

esp_err_t ota_boot_confirm(void)
{
    const esp_partition_t *running = esp_ota_get_running_partition();
    if (running != NULL && running->subtype == ESP_PARTITION_SUBTYPE_APP_FACTORY) {
        ESP_LOGI(TAG, "factory image; no pending rollback");
        return ESP_OK;
    }

    esp_err_t err = esp_ota_mark_app_valid_cancel_rollback();
    if (err == ESP_OK) {
        ESP_LOGI(TAG, "running image marked valid");
    } else {
        ESP_LOGW(TAG, "mark valid: %s", esp_err_to_name(err));
    }
    return err;
}
