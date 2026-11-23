/**
 * @file lvgl_app.c
 * @author LG_frir
 * @date 2026-11-17
 * @brief LVGL 9 + esp_lcd(ST7735) 对接
 */

#include "driver/display/lvgl_app.h"

#include "driver/display/lcd.h"

#include "esp_check.h"
#include "esp_lcd_panel_io.h"
#include "esp_log.h"
#include "esp_lvgl_port.h"
#include "freertos/FreeRTOS.h"

static const char *TAG = "LVGL";

/* 每个绘制缓冲占 1/4 屏，双缓冲共 1/2 屏：160*128*2/4 = 10KB 一块 */
#define LVGL_BUF_PIXELS (LCD_H_RES * LCD_V_RES / 4)

static lv_display_t *s_disp = NULL;

esp_err_t lvgl_app_init(void)
{
    if (s_disp != NULL) {
        return ESP_OK;
    }

    const lvgl_port_cfg_t port_cfg = ESP_LVGL_PORT_INIT_CONFIG();
    ESP_RETURN_ON_ERROR(lvgl_port_init(&port_cfg), TAG, "lvgl_port_init failed");

    const lvgl_port_display_cfg_t disp_cfg = {
        .io_handle     = lcd_io_handle(),
        .panel_handle  = lcd_panel_handle(),
        .buffer_size   = LVGL_BUF_PIXELS,
        .double_buffer = true,
        .hres          = LCD_H_RES,
        .vres          = LCD_V_RES,
        .monochrome    = false,
        .color_format  = LV_COLOR_FORMAT_RGB565,
        .rotation      = {
            .swap_xy  = LCD_ROT_SWAP_XY,
            .mirror_x = LCD_ROT_MIRROR_X,
            .mirror_y = LCD_ROT_MIRROR_Y,
        },
        .flags = {
            .buff_dma   = true,   /* 缓冲要能被 SPI DMA 直接取走 */
            .swap_bytes = true,   /* 面板要高字节先收，LVGL 给的是主机字节序 */
        },
    };

    s_disp = lvgl_port_add_disp(&disp_cfg);
    ESP_RETURN_ON_FALSE(s_disp != NULL, ESP_FAIL, TAG, "lvgl_port_add_disp failed");

    ESP_LOGI(TAG, "lvgl display ready: %dx%d, buffer %d px x2", LCD_H_RES, LCD_V_RES,
             LVGL_BUF_PIXELS);
    return ESP_OK;
}

bool lvgl_app_lock(uint32_t timeout_ms)
{
    return lvgl_port_lock(timeout_ms);
}

void lvgl_app_unlock(void)
{
    lvgl_port_unlock();
}

lv_display_t *lvgl_app_disp(void)
{
    return s_disp;
}
