/**
 * @file ota_ble_svc.c
 * @brief BLE OTA control service.
 *
 * CMD write payload (binary, little-endian-ish fixed header):
 *   [0] command: 1=START, 2=ABORT
 *   START:
 *     [1] flags: bit0 local_http, bit1 auto_reboot
 *     [2] ssid_len
 *     [3] pass_len
 *     [4] url_len
 *     [5] token_len
 *     [6] sha_len (0 or 64)
 *     [7..] ssid, password, url, token, sha256 (hex)
 *
 * STATUS/INFO are JSON strings, so the App can parse them directly.
 */

#include "task/gatt_svc.h"
#include "task/auth_svc.h"
#include "task/ota_svc.h"

#include "esp_app_desc.h"
#include "esp_log.h"
#include "esp_ota_ops.h"

#include <stdio.h>
#include <string.h>

static const char *TAG = "OTA_BLE";

#define OTA_CMD_START 1
#define OTA_CMD_ABORT 2

static bool copy_field(char *dst, size_t cap, const uint8_t *src, uint8_t len)
{
    if (len >= cap) {
        return false;
    }
    memcpy(dst, src, len);
    dst[len] = '\0';
    return true;
}

static int append_json(struct ble_gatt_access_ctxt *ctxt, const char *json)
{
    return os_mbuf_append(ctxt->om, json, strlen(json)) == 0 ? 0
                                                             : BLE_ATT_ERR_INSUFFICIENT_RES;
}

static int read_info(struct ble_gatt_access_ctxt *ctxt)
{
    const esp_app_desc_t *desc = esp_app_get_description();
    const esp_partition_t *running = esp_ota_get_running_partition();
    char buf[160];

    snprintf(buf, sizeof(buf),
             "{\"version\":\"%s\",\"partition\":\"%s\",\"ota\":true}",
             desc != NULL ? desc->version : "?",
             running != NULL ? running->label : "?");
    return append_json(ctxt, buf);
}

static int read_status(struct ble_gatt_access_ctxt *ctxt)
{
    ota_status_t st;
    char buf[224];

    ota_get_status(&st);
    snprintf(buf, sizeof(buf),
             "{\"state\":%d,\"progress\":%u,\"read\":%lu,\"total\":%lu,"
             "\"version\":\"%s\",\"error\":\"%s\"}",
             (int)st.state, (unsigned)st.progress, (unsigned long)st.bytes_read,
             (unsigned long)st.bytes_total, st.version, st.error);
    return append_json(ctxt, buf);
}

static int handle_start(const uint8_t *data, uint16_t len)
{
    if (len < 7) {
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }

    uint8_t flags = data[1];
    uint8_t ssid_len = data[2];
    uint8_t pass_len = data[3];
    uint8_t url_len = data[4];
    uint8_t token_len = data[5];
    uint8_t sha_len = data[6];
    uint16_t need = (uint16_t)(7 + ssid_len + pass_len + url_len + token_len + sha_len);
    if (len < need) {
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }
    if (sha_len != 0 && sha_len != OTA_SHA256_LEN) {
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }

    ota_request_t req;
    memset(&req, 0, sizeof(req));
    req.local_http = (flags & 0x01) != 0;
    req.auto_reboot = (flags & 0x02) != 0;

    const uint8_t *p = data + 7;
    if (!copy_field(req.ssid, sizeof(req.ssid), p, ssid_len)) {
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }
    p += ssid_len;
    if (!copy_field(req.password, sizeof(req.password), p, pass_len)) {
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }
    p += pass_len;
    if (!copy_field(req.url, sizeof(req.url), p, url_len)) {
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }
    p += url_len;
    if (!copy_field(req.token, sizeof(req.token), p, token_len)) {
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }
    p += token_len;
    if (sha_len > 0) {
        if (!copy_field(req.sha256, sizeof(req.sha256), p, sha_len)) {
            return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        }
    }

    esp_err_t err = ota_start(&req);
    if (err == ESP_ERR_INVALID_STATE) {
        return BLE_ATT_ERR_INSUFFICIENT_RES;
    }
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "ota_start rejected: %s", esp_err_to_name(err));
        return BLE_ATT_ERR_UNLIKELY;
    }

    ESP_LOGI(TAG, "OTA start accepted (%s)", req.local_http ? "local http" : "remote https");
    return 0;
}

int ota_ble_svc_callback(uint16_t conn_handle, uint16_t attr_handle,
                         struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    (void)conn_handle;
    (void)attr_handle;
    intptr_t idx = (intptr_t)arg;

    if (ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR) {
        if (idx == 2) {
            return read_status(ctxt);
        }
        if (idx == 3) {
            return read_info(ctxt);
        }
        return BLE_ATT_ERR_UNLIKELY;
    }

    if (ctxt->op != BLE_GATT_ACCESS_OP_WRITE_CHR) {
        return BLE_ATT_ERR_UNLIKELY;
    }

    int auth = auth_guard();
    if (auth != 0) {
        return auth;
    }

    uint16_t len = OS_MBUF_PKTLEN(ctxt->om);
    if (len == 0 || len > 240) {
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }

    uint8_t data[240];
    if (os_mbuf_copydata(ctxt->om, 0, len, data) != 0) {
        return BLE_ATT_ERR_UNLIKELY;
    }

    if (data[0] == OTA_CMD_START) {
        return handle_start(data, len);
    }
    if (data[0] == OTA_CMD_ABORT) {
        esp_err_t err = ota_abort();
        return (err == ESP_OK) ? 0 : BLE_ATT_ERR_UNLIKELY;
    }
    return BLE_ATT_ERR_UNLIKELY;
}
