/**
 * @file ui.c
 * @author LG_frir
 * @date 2026-11-17
 * @brief 横屏 160x128 界面：4 个页面自动轮播
 *        1 DASH  运行时间 + 进度条
 *        2 CHART 双通道实时曲线
 *        3 INFO  芯片/内存/温度/刷屏速度
 *        4 ARC   圆弧仪表 + 转圈动画
 */

#include "driver/display/ui.h"

#include "driver/display/lcd.h"

#include "driver/temperature_sensor.h"
#include "esp_heap_caps.h"
#include "esp_log.h"
#include "esp_system.h"
#include "lvgl.h"

#include <math.h>
#include <stdio.h>

static const char *TAG = "UI";

/* ========================= 配色 ========================= */
#define UI_BG     0x0E1116
#define UI_CARD   0x161D26
#define UI_ACCENT 0x00E5A0
#define UI_TEXT   0xE6EDF3
#define UI_DIM    0x8B98A5
#define UI_BLUE   0x4CC2FF

#define UI_SCR_W LCD_H_RES
#define UI_SCR_H LCD_V_RES

#define UI_HEADER_H 22
#define UI_FOOTER_H 10

/* ========================= 页面 ========================= */
#define PAGE_COUNT     4
#define PAGE_SWITCH_MS 6000

enum { PAGE_DASH = 0, PAGE_CHART, PAGE_INFO, PAGE_ARC };

static const char *k_page_name[PAGE_COUNT] = { "DASH", "CHART", "INFO", "ARC" };

static lv_obj_t *s_page[PAGE_COUNT];
static lv_obj_t *s_dot[PAGE_COUNT];
static lv_obj_t *s_page_name;
static uint8_t   s_page_idx;

/* DASH */
static lv_obj_t *s_uptime;
static lv_obj_t *s_bar;

/* CHART */
static lv_obj_t *s_chart;
static lv_chart_series_t *s_ser_a;
static lv_chart_series_t *s_ser_b;
static uint32_t s_chart_phase;

/* INFO */
static lv_obj_t *s_val_idf;
static lv_obj_t *s_val_lvgl;
static lv_obj_t *s_val_heap;
static lv_obj_t *s_val_temp;
static lv_obj_t *s_val_fill;

/* ARC */
static lv_obj_t *s_arc;
static lv_obj_t *s_arc_label;

static uint32_t  s_fill_fps;
static uint32_t  s_seconds;
static temperature_sensor_handle_t s_tsens = NULL;

/* ========================= 小工具 ========================= */
static lv_obj_t *make_box(lv_obj_t *parent, int x, int y, int w, int h, uint32_t color)
{
    lv_obj_t *o = lv_obj_create(parent);
    lv_obj_set_pos(o, x, y);
    lv_obj_set_size(o, w, h);
    lv_obj_set_style_bg_color(o, lv_color_hex(color), 0);
    lv_obj_set_style_bg_opa(o, LV_OPA_COVER, 0);
    lv_obj_set_style_border_width(o, 0, 0);
    lv_obj_set_style_pad_all(o, 0, 0);
    lv_obj_set_scrollable(o, false);
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

/* ========================= 页面 1：DASH ========================= */
static void dash_create(lv_obj_t *p)
{
    lv_obj_t *cap = make_label(p, "UPTIME", UI_DIM, NULL);
    lv_obj_align(cap, LV_ALIGN_TOP_MID, 0, 2);

    s_uptime = make_label(p, "00:00", UI_ACCENT, &lv_font_montserrat_24);
    lv_obj_align(s_uptime, LV_ALIGN_TOP_MID, 0, 16);

    s_bar = lv_bar_create(p);
    lv_obj_set_pos(s_bar, 6, 54);
    lv_obj_set_size(s_bar, UI_SCR_W - 12, 10);
    lv_obj_set_style_bg_color(s_bar, lv_color_hex(UI_CARD), 0);
    lv_obj_set_style_bg_opa(s_bar, LV_OPA_COVER, 0);
    lv_obj_set_style_radius(s_bar, 5, 0);
    lv_obj_set_style_bg_color(s_bar, lv_color_hex(UI_ACCENT), LV_PART_INDICATOR);
    lv_obj_set_style_bg_opa(s_bar, LV_OPA_COVER, LV_PART_INDICATOR);
    lv_obj_set_style_radius(s_bar, 5, LV_PART_INDICATOR);
    lv_bar_set_range(s_bar, 0, 100);
    lv_bar_set_value(s_bar, 0, LV_ANIM_OFF);

    /* 总线参数直接取驱动的实际设定，改时钟不用改这里 */
    char hint_txt[32];
    snprintf(hint_txt, sizeof(hint_txt), "ST7735  SPI2  %uMHz",
             (unsigned)(lcd_get_pclk() / 1000000));
    lv_obj_t *hint = make_label(p, hint_txt, UI_DIM, NULL);
    lv_obj_align(hint, LV_ALIGN_TOP_MID, 0, 72);
}

/* ========================= 页面 2：CHART ========================= */
static void chart_create(lv_obj_t *p)
{
    s_chart = lv_chart_create(p);
    lv_obj_set_pos(s_chart, 4, 2);
    lv_obj_set_size(s_chart, UI_SCR_W - 8, 88);
    lv_obj_set_style_bg_color(s_chart, lv_color_hex(UI_CARD), 0);
    lv_obj_set_style_bg_opa(s_chart, LV_OPA_COVER, 0);
    lv_obj_set_style_border_width(s_chart, 0, 0);
    lv_obj_set_style_radius(s_chart, 4, 0);
    lv_obj_set_style_pad_all(s_chart, 2, 0);
    lv_obj_set_style_line_width(s_chart, 1, LV_PART_MAIN);
    lv_obj_set_style_line_color(s_chart, lv_color_hex(UI_CARD), LV_PART_MAIN);

    lv_chart_set_type(s_chart, LV_CHART_TYPE_LINE);
    lv_chart_set_point_count(s_chart, 24);
    lv_chart_set_range(s_chart, LV_CHART_AXIS_PRIMARY_Y, 0, 100);
    lv_chart_set_div_line_count(s_chart, 3, 4);

    s_ser_a = lv_chart_add_series(s_chart, lv_color_hex(UI_ACCENT), LV_CHART_AXIS_PRIMARY_Y);
    s_ser_b = lv_chart_add_series(s_chart, lv_color_hex(UI_BLUE), LV_CHART_AXIS_PRIMARY_Y);
}

/* ========================= 页面 3：INFO ========================= */
static void info_row(lv_obj_t *p, int y, const char *key, lv_obj_t **val)
{
    lv_obj_t *k = make_label(p, key, UI_DIM, NULL);
    lv_obj_set_pos(k, 6, y);

    lv_obj_t *v = make_label(p, "-", UI_ACCENT, NULL);
    lv_obj_set_pos(v, 62, y);
    *val = v;
}

static void info_create(lv_obj_t *p)
{
    info_row(p, 4,  "IDF",  &s_val_idf);
    info_row(p, 20, "LVGL", &s_val_lvgl);
    info_row(p, 36, "HEAP", &s_val_heap);
    info_row(p, 52, "DIE",  &s_val_temp);
    info_row(p, 68, "FILL", &s_val_fill);

    lv_label_set_text(s_val_idf, esp_get_idf_version());
    lv_label_set_text_fmt(s_val_lvgl, "%d.%d.%d", LVGL_VERSION_MAJOR, LVGL_VERSION_MINOR,
                          LVGL_VERSION_PATCH);
}

/* ========================= 页面 4：ARC ========================= */
static void arc_create(lv_obj_t *p)
{
    s_arc = lv_arc_create(p);
    lv_obj_set_pos(s_arc, 8, 0);
    lv_obj_set_size(s_arc, 92, 92);
    lv_arc_set_rotation(s_arc, 0);
    lv_arc_set_bg_angles(s_arc, 135, 405);
    lv_arc_set_range(s_arc, 0, 100);
    lv_arc_set_value(s_arc, 0);

    lv_obj_set_style_arc_width(s_arc, 8, LV_PART_MAIN);
    lv_obj_set_style_arc_color(s_arc, lv_color_hex(UI_CARD), LV_PART_MAIN);
    lv_obj_set_style_arc_width(s_arc, 8, LV_PART_INDICATOR);
    lv_obj_set_style_arc_color(s_arc, lv_color_hex(UI_ACCENT), LV_PART_INDICATOR);
    lv_obj_set_style_bg_opa(s_arc, 0, LV_PART_KNOB);
    lv_obj_set_style_bg_opa(s_arc, 0, LV_PART_MAIN);
    lv_obj_set_clickable(s_arc, false);

    s_arc_label = make_label(p, "0%", UI_TEXT, &lv_font_montserrat_24);
    lv_obj_align_to(s_arc_label, s_arc, LV_ALIGN_CENTER, 0, 0);

    /* 右侧转圈，验证动画流畅度 */
    lv_obj_t *sp = lv_spinner_create(p);
    lv_obj_set_size(sp, 34, 34);
    lv_obj_set_pos(sp, 116, 6);
    lv_spinner_set_anim_params(sp, 900, 70);
    lv_obj_set_style_arc_width(sp, 4, LV_PART_MAIN);
    lv_obj_set_style_arc_color(sp, lv_color_hex(UI_CARD), LV_PART_MAIN);
    lv_obj_set_style_arc_width(sp, 4, LV_PART_INDICATOR);
    lv_obj_set_style_arc_color(sp, lv_color_hex(UI_BLUE), LV_PART_INDICATOR);
    lv_obj_set_style_bg_opa(sp, 0, LV_PART_MAIN);

    lv_obj_t *cap = make_label(p, "SPIN", UI_DIM, NULL);
    lv_obj_set_pos(cap, 122, 48);

    lv_obj_t *hint = make_label(p, "ANIM", UI_DIM, NULL);
    lv_obj_set_pos(hint, 120, 68);
}

/* ========================= 定时刷新 ========================= */
static void info_refresh(void)
{
    size_t heap = heap_caps_get_free_size(MALLOC_CAP_INTERNAL);
    lv_label_set_text_fmt(s_val_heap, "%u KB", (unsigned)(heap / 1024));

    if (s_tsens != NULL) {
        float celsius = 0.0f;
        if (temperature_sensor_get_celsius(s_tsens, &celsius) == ESP_OK) {
            lv_label_set_text_fmt(s_val_temp, "%.1f C", celsius);
        } else {
            lv_label_set_text(s_val_temp, "n/a");
        }
    } else {
        lv_label_set_text(s_val_temp, "n/a");
    }

    lv_label_set_text_fmt(s_val_fill, "%u fps", (unsigned)s_fill_fps);
}

static void uptime_cb(lv_timer_t *timer)
{
    (void)timer;

    s_seconds++;
    lv_label_set_text_fmt(s_uptime, "%02u:%02u", (unsigned)(s_seconds / 60),
                          (unsigned)(s_seconds % 60));

    int32_t v = lv_bar_get_value(s_bar) + 4;
    if (v > 100) {
        v = 0;
    }
    lv_bar_set_value(s_bar, v, LV_ANIM_ON);

    if (s_page_idx == PAGE_INFO) {
        info_refresh();
    }
}

static void chart_cb(lv_timer_t *timer)
{
    (void)timer;

    if (s_page_idx != PAGE_CHART) {
        return;
    }

    /* 采样点少，直接算正弦，S3 有单精度 FPU */
    float t = (float)s_chart_phase * 0.35f;
    int32_t a = (int32_t)(50.0f + 40.0f * sinf(t));
    int32_t b = (int32_t)(50.0f + 40.0f * sinf(t * 1.7f + 1.2f));

    lv_chart_set_next_value(s_chart, s_ser_a, a);
    lv_chart_set_next_value(s_chart, s_ser_b, b);
    s_chart_phase++;
}

static void arc_anim_cb(void *var, int32_t v)
{
    lv_arc_set_value((lv_obj_t *)var, v);
    lv_label_set_text_fmt(s_arc_label, "%d%%", (int)v);
}

static void page_switch_cb(lv_timer_t *timer)
{
    (void)timer;

    lv_obj_set_hidden(s_page[s_page_idx], true);
    lv_obj_set_style_bg_color(s_dot[s_page_idx], lv_color_hex(UI_CARD), 0);

    s_page_idx = (uint8_t)((s_page_idx + 1) % PAGE_COUNT);

    lv_obj_set_hidden(s_page[s_page_idx], false);
    lv_obj_set_style_bg_color(s_dot[s_page_idx], lv_color_hex(UI_ACCENT), 0);
    lv_label_set_text(s_page_name, k_page_name[s_page_idx]);

    if (s_page_idx == PAGE_INFO) {
        info_refresh();
    }
}

/* ========================= 入口 ========================= */
void ui_set_fill_fps(uint32_t fps)
{
    s_fill_fps = fps;
}

void ui_start(void)
{
    lv_obj_t *scr = lv_screen_active();
    lv_obj_set_style_bg_color(scr, lv_color_hex(UI_BG), 0);
    lv_obj_set_style_bg_opa(scr, LV_OPA_COVER, 0);
    lv_obj_set_scrollable(scr, false);

    /* ---------- 顶栏 ---------- */
    make_box(scr, 0, 0, UI_SCR_W, UI_HEADER_H, UI_CARD);
    lv_obj_t *hdr = lv_obj_get_child(scr, -1);

    lv_obj_t *title = make_label(hdr, "SPI SCREEN", UI_ACCENT, NULL);
    lv_obj_align(title, LV_ALIGN_LEFT_MID, 6, 0);

    s_page_name = make_label(hdr, k_page_name[PAGE_DASH], UI_DIM, NULL);
    lv_obj_align(s_page_name, LV_ALIGN_RIGHT_MID, -6, 0);

    /* ---------- 四个页面 ---------- */
    const int content_h = UI_SCR_H - UI_HEADER_H - UI_FOOTER_H;
    for (int i = 0; i < PAGE_COUNT; i++) {
        lv_obj_t *p = lv_obj_create(scr);
        lv_obj_set_pos(p, 0, UI_HEADER_H);
        lv_obj_set_size(p, UI_SCR_W, content_h);
        lv_obj_set_style_bg_opa(p, 0, 0);
        lv_obj_set_style_border_width(p, 0, 0);
        lv_obj_set_style_pad_all(p, 0, 0);
        lv_obj_set_scrollable(p, false);
        if (i != PAGE_DASH) {
            lv_obj_set_hidden(p, true);
        }
        s_page[i] = p;
    }

    dash_create(s_page[PAGE_DASH]);
    chart_create(s_page[PAGE_CHART]);
    info_create(s_page[PAGE_INFO]);
    arc_create(s_page[PAGE_ARC]);

    /* ---------- 底部页码点 ---------- */
    for (int i = 0; i < PAGE_COUNT; i++) {
        s_dot[i] = make_box(scr, UI_SCR_W / 2 - 18 + i * 10, UI_SCR_H - 7, 7, 5,
                            (i == PAGE_DASH) ? UI_ACCENT : UI_CARD);
    }

    /* ---------- 动画：圆弧来回摆 ---------- */
    lv_anim_t a;
    lv_anim_init(&a);
    lv_anim_set_var(&a, s_arc);
    lv_anim_set_exec_cb(&a, arc_anim_cb);
    lv_anim_set_values(&a, 0, 100);
    lv_anim_set_duration(&a, 1800);
    lv_anim_set_playback_duration(&a, 1800);
    lv_anim_set_repeat_count(&a, LV_ANIM_REPEAT_INFINITE);
    lv_anim_start(&a);

    /* ---------- 定时器 ---------- */
    lv_timer_create(uptime_cb, 1000, NULL);
    lv_timer_create(chart_cb, 200, NULL);
    lv_timer_create(page_switch_cb, PAGE_SWITCH_MS, NULL);

    /* ---------- 片上温度传感器（失败不影响界面）---------- */
    temperature_sensor_config_t ts_cfg = TEMPERATURE_SENSOR_CONFIG_DEFAULT(-10, 80);
    if (temperature_sensor_install(&ts_cfg, &s_tsens) == ESP_OK) {
        temperature_sensor_enable(s_tsens);
    } else {
        s_tsens = NULL;
        ESP_LOGW(TAG, "no temperature sensor");
    }

    info_refresh();
    ESP_LOGI(TAG, "ui started, %d pages", PAGE_COUNT);
}
