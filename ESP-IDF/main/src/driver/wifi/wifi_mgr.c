/**
 * @file wifi_mgr.c
 * @brief Minimal WiFi STA manager for OTA.
 */

#include "driver/wifi/wifi_mgr.h"

#include "esp_event.h"
#include "esp_log.h"
#include "esp_netif.h"
#include "esp_sntp.h"
#include "esp_wifi.h"
#include "freertos/FreeRTOS.h"
#include "freertos/event_groups.h"
#include "freertos/task.h"

#include <string.h>
#include <time.h>

static const char *TAG = "WIFI";

#define WIFI_CONNECTED_BIT BIT0
#define WIFI_FAIL_BIT      BIT1

static EventGroupHandle_t s_events;
static bool s_inited;
static bool s_connecting;
static bool s_manual_disconnect;
static int s_retry;

static void wifi_event_handler(void *arg, esp_event_base_t base, int32_t id, void *data)
{
    (void)arg;

    if (base == WIFI_EVENT && id == WIFI_EVENT_STA_START) {
        if (s_connecting) {
            esp_wifi_connect();
        }
    } else if (base == WIFI_EVENT && id == WIFI_EVENT_STA_DISCONNECTED) {
        if (s_events != NULL) {
            xEventGroupClearBits(s_events, WIFI_CONNECTED_BIT);
        }
        if (s_manual_disconnect || !s_connecting) {
            return;
        }
        if (s_retry < 3) {
            s_retry++;
            ESP_LOGW(TAG, "disconnected, retry %d", s_retry);
            esp_wifi_connect();
        } else {
            s_connecting = false;
            if (s_events != NULL) {
                xEventGroupSetBits(s_events, WIFI_FAIL_BIT);
            }
        }
    } else if (base == IP_EVENT && id == IP_EVENT_STA_GOT_IP) {
        ip_event_got_ip_t *event = (ip_event_got_ip_t *)data;
        ESP_LOGI(TAG, "got ip " IPSTR, IP2STR(&event->ip_info.ip));
        s_retry = 0;
        s_connecting = false;
        if (s_events != NULL) {
            xEventGroupClearBits(s_events, WIFI_FAIL_BIT);
            xEventGroupSetBits(s_events, WIFI_CONNECTED_BIT);
        }
    }
}

esp_err_t wifi_mgr_init(void)
{
    if (s_inited) {
        return ESP_OK;
    }

    s_events = xEventGroupCreate();
    if (s_events == NULL) {
        return ESP_ERR_NO_MEM;
    }

    esp_err_t err = esp_netif_init();
    if (err != ESP_OK && err != ESP_ERR_INVALID_STATE) {
        return err;
    }

    err = esp_event_loop_create_default();
    if (err != ESP_OK && err != ESP_ERR_INVALID_STATE) {
        return err;
    }

    if (esp_netif_create_default_wifi_sta() == NULL) {
        ESP_LOGW(TAG, "default STA netif already exists");
    }

    wifi_init_config_t cfg = WIFI_INIT_CONFIG_DEFAULT();
    err = esp_wifi_init(&cfg);
    if (err != ESP_OK && err != ESP_ERR_INVALID_STATE) {
        return err;
    }

    ESP_ERROR_CHECK(esp_event_handler_register(WIFI_EVENT, ESP_EVENT_ANY_ID,
                                               wifi_event_handler, NULL));
    ESP_ERROR_CHECK(esp_event_handler_register(IP_EVENT, IP_EVENT_STA_GOT_IP,
                                               wifi_event_handler, NULL));

    err = esp_wifi_set_mode(WIFI_MODE_STA);
    if (err != ESP_OK) {
        return err;
    }
    err = esp_wifi_start();
    if (err != ESP_OK) {
        return err;
    }

    s_inited = true;
    ESP_LOGI(TAG, "wifi manager ready (off until OTA)");
    return ESP_OK;
}

esp_err_t wifi_mgr_connect(const char *ssid, const char *password, uint32_t timeout_ms)
{
    if (ssid == NULL || ssid[0] == '\0') {
        return ESP_ERR_INVALID_ARG;
    }

    esp_err_t err = wifi_mgr_init();
    if (err != ESP_OK) {
        return err;
    }

    wifi_config_t wc;
    memset(&wc, 0, sizeof(wc));
    strncpy((char *)wc.sta.ssid, ssid, sizeof(wc.sta.ssid) - 1);
    if (password != NULL) {
        strncpy((char *)wc.sta.password, password, sizeof(wc.sta.password) - 1);
    }
    wc.sta.threshold.authmode = (password != NULL && password[0] != '\0')
                                    ? WIFI_AUTH_WPA2_PSK
                                    : WIFI_AUTH_OPEN;
    wc.sta.pmf_cfg.capable = true;
    wc.sta.pmf_cfg.required = false;

    xEventGroupClearBits(s_events, WIFI_CONNECTED_BIT | WIFI_FAIL_BIT);
    s_manual_disconnect = false;
    s_retry = 0;
    s_connecting = true;

    err = esp_wifi_set_config(WIFI_IF_STA, &wc);
    if (err != ESP_OK) {
        s_connecting = false;
        return err;
    }
    esp_wifi_set_ps(WIFI_PS_NONE);
    err = esp_wifi_connect();
    if (err != ESP_OK) {
        s_connecting = false;
        return err;
    }

    EventBits_t bits = xEventGroupWaitBits(s_events, WIFI_CONNECTED_BIT | WIFI_FAIL_BIT,
                                           pdFALSE, pdFALSE, pdMS_TO_TICKS(timeout_ms));
    if (bits & WIFI_CONNECTED_BIT) {
        return ESP_OK;
    }
    s_connecting = false;
    return (bits & WIFI_FAIL_BIT) ? ESP_FAIL : ESP_ERR_TIMEOUT;
}

esp_err_t wifi_mgr_disconnect(void)
{
    if (!s_inited) {
        return ESP_OK;
    }
    s_manual_disconnect = true;
    s_connecting = false;
    if (s_events != NULL) {
        xEventGroupClearBits(s_events, WIFI_CONNECTED_BIT | WIFI_FAIL_BIT);
    }
    return esp_wifi_disconnect();
}

bool wifi_mgr_is_connected(void)
{
    if (!s_inited || s_events == NULL) {
        return false;
    }
    return (xEventGroupGetBits(s_events) & WIFI_CONNECTED_BIT) != 0;
}

esp_err_t wifi_mgr_sync_time(uint32_t timeout_ms)
{
    time_t now = time(NULL);
    if (now > 1700000000) {
        return ESP_OK;
    }

    esp_sntp_setoperatingmode(SNTP_OPMODE_POLL);
    esp_sntp_setservername(0, "pool.ntp.org");
    esp_sntp_init();

    uint32_t waited = 0;
    while (waited < timeout_ms) {
        vTaskDelay(pdMS_TO_TICKS(200));
        waited += 200;
        now = time(NULL);
        if (now > 1700000000) {
            ESP_LOGI(TAG, "SNTP time synced");
            esp_sntp_stop();
            return ESP_OK;
        }
    }

    esp_sntp_stop();
    ESP_LOGW(TAG, "SNTP timeout");
    return ESP_ERR_TIMEOUT;
}
