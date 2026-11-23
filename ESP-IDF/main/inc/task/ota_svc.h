/**
 * @file ota_svc.h
 * @brief OTA downloader: WiFi + HTTP(S) + inactive OTA partition + rollback.
 */

#ifndef OTA_SVC_H
#define OTA_SVC_H

#include <stdbool.h>
#include <stdint.h>

#include "esp_err.h"

#define OTA_URL_MAX     256
#define OTA_SSID_MAX    33
#define OTA_PASS_MAX    65
#define OTA_TOKEN_MAX   64
#define OTA_SHA256_LEN  64
#define OTA_VER_MAX     32
#define OTA_ERR_MAX     64

typedef enum {
    OTA_IDLE = 0,
    OTA_CONNECTING_WIFI,
    OTA_DOWNLOADING,
    OTA_VERIFYING,
    OTA_READY_REBOOT,
    OTA_SUCCESS,
    OTA_FAILED,
    OTA_ABORTED,
} ota_state_t;

typedef struct {
    char url[OTA_URL_MAX];
    char ssid[OTA_SSID_MAX];
    char password[OTA_PASS_MAX];
    char token[OTA_TOKEN_MAX];
    char sha256[OTA_SHA256_LEN + 1]; /* empty = skip SHA check */
    bool local_http;                 /* allow http:// only for phone hotspot */
    bool auto_reboot;
} ota_request_t;

typedef struct {
    ota_state_t state;
    uint8_t progress;
    uint32_t bytes_read;
    uint32_t bytes_total;
    char version[OTA_VER_MAX];
    char error[OTA_ERR_MAX];
} ota_status_t;

esp_err_t ota_init(void);

/* Call at the end of a successful app boot: cancels pending rollback. */
esp_err_t ota_boot_confirm(void);

esp_err_t ota_start(const ota_request_t *req);
esp_err_t ota_abort(void);
void ota_get_status(ota_status_t *out);
bool ota_is_running(void);

#endif /* OTA_SVC_H */
