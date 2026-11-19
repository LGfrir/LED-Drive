/**
 * @file time_sync.c
 * @author LG_frir
 * @date 2026-11-12
 */

#include "driver/timer/time_sync.h"

#include <string.h>
#include <time.h>

#include "driver/timer/local_clock.h"
#include "task/clock_scheduler.h"

#include "esp_log.h"
#include "esp_timer.h"

#define TAG "time_sync"
#define SYNC_TIMEOUT_US (60 * 1000 * 1000)   /* 连上 1 分钟还没同步就请求 */

static esp_timer_handle_t s_timer;
static time_sync_request_cb_t s_request_cb;
static uint16_t s_conn;
static bool s_connected;

static void timer_cb(void *arg)
{
    (void)arg;
    if (!s_connected || local_clock_valid()) {
        return;
    }
    ESP_LOGI(TAG, "连接满 1 分钟仍未对时，向手机请求时间");
    if (s_request_cb != NULL) {
        s_request_cb(s_conn);
    }
}

void time_sync_init(time_sync_request_cb_t request_cb)
{
    s_request_cb = request_cb;
    s_connected = false;
    s_conn = 0;

    if (s_timer == NULL) {
        const esp_timer_create_args_t args = {
            .callback = timer_cb,
            .name = "time_sync",
        };
        if (esp_timer_create(&args, &s_timer) == ESP_OK) {
            ESP_LOGI(TAG, "对时模块就绪");
        }
    }
}

void time_sync_on_connect(uint16_t conn_handle)
{
    s_conn = conn_handle;
    s_connected = true;
    if (s_timer != NULL) {
        esp_timer_stop(s_timer);
        esp_timer_start_once(s_timer, SYNC_TIMEOUT_US);
    }
}

void time_sync_on_disconnect(void)
{
    s_connected = false;
    if (s_timer != NULL) {
        esp_timer_stop(s_timer);
    }
    /* 编辑中的草稿没人确认，直接丢掉 */
    clock_seg_cancel_all();
}

esp_err_t time_sync_apply(const uint8_t *pkt, uint16_t len)
{
    if (pkt == NULL || len < 8) {
        return ESP_ERR_INVALID_SIZE;
    }
    uint16_t year = (uint16_t)(pkt[0] | (pkt[1] << 8));
    uint8_t mon = pkt[2];
    uint8_t day = pkt[3];
    uint8_t hour = pkt[4];
    uint8_t min = pkt[5];
    uint8_t sec = pkt[6];
    uint8_t wday = pkt[7];

    if (year < 2020 || year > 2099 || mon < 1 || mon > 12 || day < 1 || day > 31 ||
        hour > 23 || min > 59 || sec > 59 || wday > 6) {
        ESP_LOGW(TAG, "时间包非法: %u-%u-%u %u:%u:%u", year, mon, day, hour, min, sec);
        return ESP_ERR_INVALID_ARG;
    }

    struct tm t;
    memset(&t, 0, sizeof(t));
    t.tm_year = (int)year - 1900;
    t.tm_mon = (int)mon - 1;
    t.tm_mday = (int)day;
    t.tm_hour = (int)hour;
    t.tm_min = (int)min;
    t.tm_sec = (int)sec;
    t.tm_isdst = -1;

    time_t epoch = mktime(&t);   /* 按本地时区（CST-8）解释 */
    if (epoch <= 0) {
        return ESP_ERR_INVALID_ARG;
    }

    local_clock_set((int64_t)epoch * 1000000);
    if (s_timer != NULL) {
        esp_timer_stop(s_timer);
    }
    clock_scheduler_on_time_synced();

    ESP_LOGI(TAG, "对时成功: %04u-%02u-%02u %02u:%02u:%02u (wday=%u)",
             year, mon, day, hour, min, sec, wday);
    return ESP_OK;
}

bool time_sync_valid(void)
{
    return local_clock_valid();
}
