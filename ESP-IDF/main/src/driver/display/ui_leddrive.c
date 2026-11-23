/**
 * @file ui.c
 * @brief LEDDrive 160x128 LVGL 界面：状态 / 灯带 / OTA / 系统 / 设置
 */

#include "driver/display/ui_leddrive.h"

#include "driver/display/lcd.h"
#include "driver/led_strip/light_mode_driver.h"
#include "task/clock_scheduler.h"

#include "driver/temperature_sensor.h"
#include "esp_app_desc.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "esp_system.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "lvgl.h"

#include <stdio.h>
#include <string.h>
#include <time.h>

static const char *TAG = "UI";

/* ----------------------------- 版面/配色 ----------------------------- */
#define SCR_W 160
#define SCR_H 128
#define HEADER_H 20
#define FOOTER_H 8
#define CONTENT_H (SCR_H - HEADER_H - FOOTER_H)

#define PAGE_COUNT 5
#define PAGE_SWITCH_MS 6000

#define C_BG     0x0E1116
#define C_CARD   0x161D26
#define C_ACCENT 0x00E5A0
#define C_TEXT   0xE6EDF3
#define C_DIM    0x8B98A5
#define C_BLUE   0x4CC2FF
#define C_WARN   0xFFB020
#define C_ERR    0xFF5C5C

static const char *k_page_name[PAGE_COUNT] = { "STATUS", "LIGHT", "OTA", "SYSTEM", "SET" };

enum {
    PAGE_STATUS = 0,
    PAGE_LIGHT,
    PAGE_OTA,
    PAGE_SYSTEM,
    PAGE_SETTINGS,
};

static lv_obj_t *s_page[PAGE_COUNT];
static lv_obj_t *s_dot[PAGE_COUNT];
static lv_obj_t *s_page_name;
static uint8_t   s_page_idx;

/* STATUS */
static lv_obj_t *s_clock;
static lv_obj_t *s_date;
static lv_obj_t *s_io[2];
static lv_obj_t *s_next;
static lv_obj_t *s_mode_hint;

/* LIGHT */
static lv_obj_t *s_light_io[2];
static lv_obj_t *s_light_bar[2];
static lv_obj_t *s_light_meta[2];

/* OTA */
static lv_obj_t *s_ota_ver;
static lv_obj_t *s_ota_state;
static lv_obj_t *s_ota_bar;
static lv_obj_t *s_ota_pct;
static lv_obj_t *s_ota_msg;
static lv_obj_t *s_ota_hint;

/* SYSTEM */
static lv_obj_t *s_sys_val[7];

/* SETTINGS */
static lv_obj_t *s_set_val[4];

static uint32_t s_fill_fps;
static temperature_sensor_handle_t s_tsens;

/* OTA 状态由 OTA 任务写，界面定时器读 */
static portMUX_TYPE s_ota_lock = portMUX_INITIALIZER_UNLOCKED;
static ui_ota_state_t s_ota_st = UI_OTA_IDLE;
static uint8_t s_ota_percent;
static char s_ota_status[40] = "NOT STARTED";

/* ----------------------------- 小工具 ----------------------------- */
static lv_obj_t *make_box(lv_obj_t *parent, int x, int y, int w, int h, uint32_t color)
{
    lv_obj_t *o = lv_obj_create(parent);
    lv_obj_set_pos(o, x, y);
    lv_obj_set_size(o, w, h);
    lv_obj_set_style_bg_color(o, lv_color_hex(color), 0);
    lv_obj_set_style_bg_opa(o, LV_OPA_COVER, 0);
    lv_obj_set_style_border_width(o, 0, 0);
    lv_obj_set_style_radius(o, 4, 0);
    lv_obj_set_style_pad_all(o, 0, 0);
    lv_obj_remove_flag(o, LV_OBJ_FLAG_SCROLLABLE);
    return o;
}

static lv_obj_t *make_label(lv_obj_t *parent, const char *text, uint32_t color,
                            const lv_font_t *font)
{
    lv_obj_t *l = lv_label_create(parent);
    lv_label_set_text(l, text);
    lv_obj_set_style_text_color(l, lv_color_hex(color), 0);
    if (font != NULL) {
        lv_obj_set_style_text_font(l, font, 0);
    }
    return l;
}

static void make_row(lv_obj_t *parent, int y, const char *key, lv_obj_t **val)
{
    lv_obj_t *k = make_label(parent, key, C_DIM, NULL);
    lv_obj_set_pos(k, 4, y);
    lv_obj_t *v = make_label(parent, "-", C_TEXT, NULL);
    lv_obj_set_pos(v, 64, y);
    *val = v;
}

/* ----------------------------- 页面创建 ----------------------------- */
static void page_status_create(lv_obj_t *p)
{
    s_clock = make_label(p, "--:--", C_ACCENT, &lv_font_montserrat_24);
    lv_obj_align(s_clock, LV_ALIGN_TOP_MID, 0, 2);

    s_date = make_label(p, "NO TIME", C_DIM, NULL);
    lv_obj_align(s_date, LV_ALIGN_TOP_MID, 0, 30);

    s_io[0] = make_label(p, "IO1  ---", C_TEXT, NULL);
    lv_obj_set_pos(s_io[0], 4, 48);
    s_io[1] = make_label(p, "IO2  ---", C_TEXT, NULL);
    lv_obj_set_pos(s_io[1], 4, 64);

    s_next = make_label(p, "NEXT --:--", C_DIM, NULL);
    lv_obj_set_pos(s_next, 4, 82);

    s_mode_hint = make_label(p, "BLE CONTROL", C_BLUE, NULL);
    lv_obj_set_pos(s_mode_hint, 4, 96);
}

static void page_light_create(lv_obj_t *p)
{
    for (int i = 0; i < 2; i++) {
        int y = 4 + i * 42;
        s_light_io[i] = make_label(p, i == 0 ? "IO1" : "IO2", C_ACCENT, NULL);
        lv_obj_set_pos(s_light_io[i], 4, y);

        s_light_bar[i] = lv_bar_create(p);
        lv_obj_set_pos(s_light_bar[i], 40, y + 2);
        lv_obj_set_size(s_light_bar[i], 114, 9);
        lv_bar_set_range(s_light_bar[i], 0, 255);
        lv_bar_set_value(s_light_bar[i], 0, LV_ANIM_OFF);
        lv_obj_set_style_bg_color(s_light_bar[i], lv_color_hex(C_CARD), 0);
        lv_obj_set_style_bg_opa(s_light_bar[i], LV_OPA_COVER, 0);
        lv_obj_set_style_radius(s_light_bar[i], 4, 0);
        lv_obj_set_style_bg_color(s_light_bar[i], lv_color_hex(C_ACCENT), LV_PART_INDICATOR);
        lv_obj_set_style_bg_opa(s_light_bar[i], LV_OPA_COVER, LV_PART_INDICATOR);
        lv_obj_set_style_radius(s_light_bar[i], 4, LV_PART_INDICATOR);

        s_light_meta[i] = make_label(p, "B--- R--- L---", C_DIM, NULL);
        lv_obj_set_pos(s_light_meta[i], 40, y + 14);
    }

    lv_obj_t *hint = make_label(p, "BRIGHTNESS / REFRESH", C_DIM, NULL);
    lv_obj_set_pos(hint, 4, 88);
}

static void page_ota_create(lv_obj_t *p)
{
    s_ota_ver = make_label(p, "VER ---", C_TEXT, NULL);
    lv_obj_set_pos(s_ota_ver, 4, 2);

    s_ota_state = make_label(p, "IDLE", C_ACCENT, NULL);
    lv_obj_set_pos(s_ota_state, 4, 18);

    s_ota_bar = lv_bar_create(p);
    lv_obj_set_pos(s_ota_bar, 4, 36);
    lv_obj_set_size(s_ota_bar, 150, 10);
    lv_bar_set_range(s_ota_bar, 0, 100);
    lv_bar_set_value(s_ota_bar, 0, LV_ANIM_OFF);
    lv_obj_set_style_bg_color(s_ota_bar, lv_color_hex(C_CARD), 0);
    lv_obj_set_style_bg_opa(s_ota_bar, LV_OPA_COVER, 0);
    lv_obj_set_style_radius(s_ota_bar, 5, 0);
    lv_obj_set_style_bg_color(s_ota_bar, lv_color_hex(C_BLUE), LV_PART_INDICATOR);
    lv_obj_set_style_bg_opa(s_ota_bar, LV_OPA_COVER, LV_PART_INDICATOR);
    lv_obj_set_style_radius(s_ota_bar, 5, LV_PART_INDICATOR);

    s_ota_pct = make_label(p, "0%", C_TEXT, NULL);
    lv_obj_set_pos(s_ota_pct, 4, 50);

    s_ota_msg = make_label(p, "WIFI OFF", C_DIM, NULL);
    lv_obj_set_pos(s_ota_msg, 4, 68);

    s_ota_hint = make_label(p, "START FROM APP", C_DIM, NULL);
    lv_obj_set_pos(s_ota_hint, 4, 86);
}

static void page_system_create(lv_obj_t *p)
{
    static const char *keys[7] = { "IDF", "LVGL", "HEAP", "PSRAM", "TEMP", "UPTIME", "FPS" };
    for (int i = 0; i < 7; i++) {
        make_row(p, 2 + i * 13, keys[i], &s_sys_val[i]);
    }
}

static void page_settings_create(lv_obj_t *p)
{
    static const char *keys[4] = { "WIFI", "OTA URL", "BACKLIGHT", "TOUCH" };
    for (int i = 0; i < 4; i++) {
        make_row(p, 2 + i * 22, keys[i], &s_set_val[i]);
    }
    lv_label_set_text(s_set_val[0], "SET IN APP");
    lv_label_set_text(s_set_val[1], "SET IN APP");
    lv_label_set_text(s_set_val[2], "ON");
    lv_label_set_text(s_set_val[3], "N/A");
}

/* ----------------------------- 刷新 ----------------------------- */
static void refresh_status(void)
{
    bool valid = clock_time_valid();
    time_t now = time(NULL);
    struct tm tm_local;
    localtime_r(&now, &tm_local);

    if (valid) {
        lv_label_set_text_fmt(s_clock, "%02d:%02d", tm_local.tm_hour, tm_local.tm_min);
        lv_label_set_text_fmt(s_date, "%04d-%02d-%02d", tm_local.tm_year + 1900,
                              tm_local.tm_mon + 1, tm_local.tm_mday);
    } else {
        lv_label_set_text(s_clock, "--:--");
        lv_label_set_text(s_date, "TIME NOT SYNCED");
    }

    uint8_t ids[LIGHT_IO_MAX];
    uint8_t n = 0;
    light_io_list(ids, LIGHT_IO_MAX, &n);

    for (int i = 0; i < 2; i++) {
        if (i >= n) {
            lv_label_set_text_fmt(s_io[i], "IO%d  ---", i + 1);
            lv_label_set_text(s_light_io[i], i == 0 ? "IO1" : "IO2");
            lv_bar_set_value(s_light_bar[i], 0, LV_ANIM_OFF);
            lv_label_set_text(s_light_meta[i], "NO IO");
            continue;
        }

        uint8_t id = ids[i];
        uint8_t bri = 0;
        uint16_t refr = 0, leds = 0;
        light_io_get(id, &bri, &refr, &leds);

        const char *mode = (clock_io_mode_get(id) == CLOCK_MODE_TIME) ? "TIM" : "MAN";
        if (light_io_is_muted(id)) {
            mode = "MUTE";
        }

        lv_label_set_text_fmt(s_io[i], "IO%u %3u%% %2ums %s", (unsigned)id,
                              (unsigned)((bri * 100u + 127u) / 255u), (unsigned)refr, mode);
        lv_label_set_text_fmt(s_light_io[i], "IO%u", (unsigned)id);
        lv_bar_set_value(s_light_bar[i], bri, LV_ANIM_OFF);
        lv_label_set_text_fmt(s_light_meta[i], "B%u%% R%ums L%u",
                              (unsigned)((bri * 100u + 127u) / 255u), (unsigned)refr,
                              (unsigned)leds);
    }

    uint32_t next = clock_next_event_sec();
    if (next == 0) {
        lv_label_set_text(s_next, "NEXT --:--");
    } else {
        lv_label_set_text_fmt(s_next, "NEXT %02u:%02u", (unsigned)(next / 3600u),
                              (unsigned)((next % 3600u) / 60u));
    }

    lv_label_set_text(s_mode_hint, "BLE CONTROL");
}

static void refresh_system(void)
{
    const esp_app_desc_t *desc = esp_app_get_description();
    size_t heap = heap_caps_get_free_size(MALLOC_CAP_INTERNAL);
    size_t psram = heap_caps_get_free_size(MALLOC_CAP_SPIRAM);
    uint32_t up = (uint32_t)(esp_timer_get_time() / 1000000);

    lv_label_set_text_fmt(s_sys_val[0], "%s", esp_get_idf_version());
    lv_label_set_text_fmt(s_sys_val[1], "%d.%d.%d", LVGL_VERSION_MAJOR, LVGL_VERSION_MINOR,
                          LVGL_VERSION_PATCH);
    lv_label_set_text_fmt(s_sys_val[2], "%u KB", (unsigned)(heap / 1024));
    if (psram == 0) {
        lv_label_set_text(s_sys_val[3], "NONE");
    } else {
        lv_label_set_text_fmt(s_sys_val[3], "%u KB", (unsigned)(psram / 1024));
    }

    if (s_tsens != NULL) {
        float celsius = 0.0f;
        if (temperature_sensor_get_celsius(s_tsens, &celsius) == ESP_OK) {
            lv_label_set_text_fmt(s_sys_val[4], "%.1f C", celsius);
        } else {
            lv_label_set_text(s_sys_val[4], "N/A");
        }
    } else {
        lv_label_set_text(s_sys_val[4], "N/A");
    }

    lv_label_set_text_fmt(s_sys_val[5], "%02u:%02u", (unsigned)(up / 60u),
                          (unsigned)(up % 60u));
    if (s_fill_fps == 0) {
        lv_label_set_text(s_sys_val[6], "N/A");
    } else {
        lv_label_set_text_fmt(s_sys_val[6], "%u", (unsigned)s_fill_fps);
    }

    if (desc != NULL) {
        lv_label_set_text_fmt(s_ota_ver, "VER %s", desc->version);
    }
}

static void refresh_ota(void)
{
    ui_ota_state_t state;
    uint8_t pct;
    char msg[sizeof(s_ota_status)];

    portENTER_CRITICAL(&s_ota_lock);
    state = s_ota_st;
    pct = s_ota_percent;
    strncpy(msg, s_ota_status, sizeof(msg) - 1);
    msg[sizeof(msg) - 1] = '\0';
    portEXIT_CRITICAL(&s_ota_lock);

    lv_bar_set_value(s_ota_bar, pct, LV_ANIM_OFF);
    lv_label_set_text_fmt(s_ota_pct, "%u%%", (unsigned)pct);
    lv_label_set_text(s_ota_msg, msg);

    switch (state) {
    case UI_OTA_RUNNING:
        lv_label_set_text(s_ota_state, "RUNNING");
        lv_obj_set_style_text_color(s_ota_state, lv_color_hex(C_BLUE), 0);
        break;
    case UI_OTA_SUCCESS:
        lv_label_set_text(s_ota_state, "SUCCESS");
        lv_obj_set_style_text_color(s_ota_state, lv_color_hex(C_ACCENT), 0);
        break;
    case UI_OTA_FAILED:
        lv_label_set_text(s_ota_state, "FAILED");
        lv_obj_set_style_text_color(s_ota_state, lv_color_hex(C_ERR), 0);
        break;
    default:
        lv_label_set_text(s_ota_state, "IDLE");
        lv_obj_set_style_text_color(s_ota_state, lv_color_hex(C_ACCENT), 0);
        break;
    }
}

static void ui_tick_cb(lv_timer_t *timer)
{
    (void)timer;
    refresh_status();
    refresh_system();
    refresh_ota();
}

static void ui_page_switch_cb(lv_timer_t *timer)
{
    (void)timer;

    lv_obj_add_flag(s_page[s_page_idx], LV_OBJ_FLAG_HIDDEN);
    lv_obj_set_style_bg_color(s_dot[s_page_idx], lv_color_hex(C_CARD), 0);

    s_page_idx = (uint8_t)((s_page_idx + 1) % PAGE_COUNT);

    lv_obj_remove_flag(s_page[s_page_idx], LV_OBJ_FLAG_HIDDEN);
    lv_obj_set_style_bg_color(s_dot[s_page_idx], lv_color_hex(C_ACCENT), 0);
    lv_label_set_text(s_page_name, k_page_name[s_page_idx]);

    if (s_page_idx == PAGE_SYSTEM) {
        refresh_system();
    } else if (s_page_idx == PAGE_OTA) {
        refresh_ota();
    }
}

/* ----------------------------- 对外接口 ----------------------------- */
void ui_set_fill_fps(uint32_t fps)
{
    s_fill_fps = fps;
}

void ui_ota_set_state(ui_ota_state_t state, uint8_t progress, const char *message)
{
    if (progress > 100) {
        progress = 100;
    }
    portENTER_CRITICAL(&s_ota_lock);
    s_ota_st = state;
    s_ota_percent = progress;
    if (message != NULL) {
        strncpy(s_ota_status, message, sizeof(s_ota_status) - 1);
        s_ota_status[sizeof(s_ota_status) - 1] = '\0';
    }
    portEXIT_CRITICAL(&s_ota_lock);
}

void ui_start(void)
{
    lv_obj_t *scr = lv_screen_active();
    lv_obj_set_style_bg_color(scr, lv_color_hex(C_BG), 0);
    lv_obj_set_style_bg_opa(scr, LV_OPA_COVER, 0);
    lv_obj_remove_flag(scr, LV_OBJ_FLAG_SCROLLABLE);

    /* 顶栏 */
    make_box(scr, 0, 0, SCR_W, HEADER_H, C_CARD);
    lv_obj_t *hdr = lv_obj_get_child(scr, -1);
    lv_obj_t *title = make_label(hdr, "LEDDrive", C_ACCENT, NULL);
    lv_obj_align(title, LV_ALIGN_LEFT_MID, 6, 0);
    s_page_name = make_label(hdr, k_page_name[PAGE_STATUS], C_DIM, NULL);
    lv_obj_align(s_page_name, LV_ALIGN_RIGHT_MID, -6, 0);

    /* 5 个页面 */
    for (int i = 0; i < PAGE_COUNT; i++) {
        lv_obj_t *p = lv_obj_create(scr);
        lv_obj_set_pos(p, 0, HEADER_H);
        lv_obj_set_size(p, SCR_W, CONTENT_H);
        lv_obj_set_style_bg_opa(p, 0, 0);
        lv_obj_set_style_border_width(p, 0, 0);
        lv_obj_set_style_pad_all(p, 0, 0);
        lv_obj_remove_flag(p, LV_OBJ_FLAG_SCROLLABLE);
        if (i != PAGE_STATUS) {
            lv_obj_add_flag(p, LV_OBJ_FLAG_HIDDEN);
        }
        s_page[i] = p;
    }

    page_status_create(s_page[PAGE_STATUS]);
    page_light_create(s_page[PAGE_LIGHT]);
    page_ota_create(s_page[PAGE_OTA]);
    page_system_create(s_page[PAGE_SYSTEM]);
    page_settings_create(s_page[PAGE_SETTINGS]);

    /* 底部页码点 */
    for (int i = 0; i < PAGE_COUNT; i++) {
        s_dot[i] = make_box(scr, SCR_W / 2 - 20 + i * 10, SCR_H - 6, 6, 4,
                            (i == PAGE_STATUS) ? C_ACCENT : C_CARD);
    }

    /* 片上温度传感器（失败不影响界面） */
    temperature_sensor_config_t ts_cfg = TEMPERATURE_SENSOR_CONFIG_DEFAULT(-10, 80);
    if (temperature_sensor_install(&ts_cfg, &s_tsens) == ESP_OK) {
        temperature_sensor_enable(s_tsens);
    } else {
        s_tsens = NULL;
    }

    lv_timer_create(ui_tick_cb, 500, NULL);
    lv_timer_create(ui_page_switch_cb, PAGE_SWITCH_MS, NULL);

    ui_tick_cb(NULL);
    s_page_idx = PAGE_STATUS;
    ESP_LOGI(TAG, "ui started, %d pages", PAGE_COUNT);
}
