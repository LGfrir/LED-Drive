/**
 * @file wifi_mgr.h
 * @brief On-demand WiFi STA for OTA. Keeps WiFi off until an OTA starts.
 */

#ifndef WIFI_MGR_H
#define WIFI_MGR_H

#include <stdbool.h>
#include <stdint.h>

#include "esp_err.h"

esp_err_t wifi_mgr_init(void);
esp_err_t wifi_mgr_connect(const char *ssid, const char *password, uint32_t timeout_ms);
esp_err_t wifi_mgr_disconnect(void);
bool wifi_mgr_is_connected(void);

/* Start SNTP and wait for a sane wall clock (needed by HTTPS). */
esp_err_t wifi_mgr_sync_time(uint32_t timeout_ms);

#endif /* WIFI_MGR_H */
