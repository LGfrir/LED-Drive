/**
 * @file dash_data.c
 * @brief LEDDrive -> disp_dash 的数据适配层
 *
 * 原作者只提供 dash_set_status/ios/ble + dash_tick 接口；
 * 这个文件每秒从 light_* / clock_* / NimBLE 读一次真实数据，
 * 不改仪表盘布局，也不依赖 LVGL。
 */

#include "driver/display/dash_data.h"

#include "driver/display/disp_dash.h"
#include "driver/bt/NimBLE.h"
#include "driver/led_strip/light_mode_driver.h"
#include "driver/preset/light_preset.h"
#include "task/clock_scheduler.h"

#include "esp_log.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include <string.h>
#include <time.h>

static const char *TAG = "DASH_DATA";

static const char *io_name(uint8_t id)
{
    if (id == 1) {
        return "LAPTOP";
    }
    if (id == 2) {
        return "FIGURE";
    }
    return "IO";
}

static uint8_t frame_cpu_pct(uint16_t leds, uint16_t refresh_ms)
{
    if (leds == 0 || refresh_ms == 0) {
        return 0;
    }
    /* WS2812: 24 bit * 1.25 us = 30 us / 颗 */
    uint32_t frame_us = (uint32_t)leds * 30u;
    uint32_t period_us = (uint32_t)refresh_ms * 1000u;
    uint32_t pct = (frame_us * 100u) / period_us;
    return (uint8_t)((pct > 100u) ? 100u : pct);
}

static void dash_task(void *arg)
{
    (void)arg;

    bool first = true;
    bool last_connected = false;

    while (1) {
        dash_status_t st;
        memset(&st, 0, sizeof(st));

        bool valid = clock_time_valid();
        st.time_valid = valid;
        if (valid) {
            time_t now = time(NULL);
            struct tm tm_local;
            localtime_r(&now, &tm_local);
            st.hour = (uint8_t)tm_local.tm_hour;
            st.minute = (uint8_t)tm_local.tm_min;
            st.second = (uint8_t)tm_local.tm_sec;
        }
        st.preset_user = light_preset_count();
        st.preset_max = 32;
        st.next_event_sec = clock_next_event_sec();

        dash_io_t ios[DASH_IO_MAX];
        memset(ios, 0, sizeof(ios));

        uint8_t ids[LIGHT_IO_MAX];
        uint8_t n = 0;
        light_io_list(ids, LIGHT_IO_MAX, &n);

        uint8_t count = 0;
        for (uint8_t i = 0; i < n && count < DASH_IO_MAX; i++) {
            uint8_t id = ids[i];
            uint8_t bri = 0;
            uint16_t refresh = 0;
            uint16_t leds = 0;
            if (light_io_get(id, &bri, &refresh, &leds) != ESP_OK) {
                continue;
            }

            uint8_t seg_ids[LIGHT_SEG_MAX];
            uint8_t seg_count = 0;
            if (light_io_seg_list(id, seg_ids, LIGHT_SEG_MAX, &seg_count) != ESP_OK) {
                seg_count = 0;
            }

            uint32_t power_mw = 0;
            light_io_get_power_mw(id, &power_mw);

            dash_io_t *d = &ios[count];
            d->id = id;
            d->name = io_name(id);
            d->time_mode = (clock_io_mode_get(id) == CLOCK_MODE_TIME);
            d->seg_count = seg_count;
            d->led_count = leds;
            d->refresh_ms = refresh;
            d->brightness = bri;
            d->power_mw = power_mw;
            d->frame_us = (uint32_t)leds * 30u;
            d->fps = (refresh > 0) ? (uint16_t)(1000u / refresh) : 0;
            d->cpu_pct = frame_cpu_pct(leds, refresh);
            d->dma = true;
            if (id == 1) {
                d->r = 255; d->g = 200; d->b = 120;
            } else {
                d->r = 0; d->g = 255; d->b = 160;
            }
            count++;
        }

        bool connected = nimble_is_connected();
        dash_ble_t ble;
        memset(&ble, 0, sizeof(ble));
        ble.device_name = DEVICE_NAME;
        ble.connected = connected;
        ble.peer_name = connected ? "PHONE" : "NO LINK";
        ble.rssi = nimble_get_rssi();
        ble.mtu = nimble_get_mtu();
        ble.conn_s = nimble_get_conn_seconds();

        dash_set_status(&st);
        dash_set_ios(ios, count);
        dash_set_ble(&ble);
        dash_tick();

        if (first) {
            dash_log_push("SYSTEM READY");
            first = false;
        }
        if (connected != last_connected) {
            dash_log_push(connected ? "PHONE CONNECTED" : "PHONE LOST");
            dash_flash_activity();
            last_connected = connected;
            ESP_LOGI(TAG, "BLE %s", connected ? "connected" : "disconnected");
        }

        vTaskDelay(pdMS_TO_TICKS(1000));
    }
}

void dash_data_start(void)
{
    if (xTaskCreate(dash_task, "dash_data", 4096, NULL, 2, NULL) != pdPASS) {
        ESP_LOGE(TAG, "dash_data task create failed");
    }
}
