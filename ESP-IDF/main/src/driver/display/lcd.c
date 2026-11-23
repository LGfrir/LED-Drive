/**
 * @file lcd.c
 * @author LG_frir
 * @date 2026-11-17
 * @brief ST7735 SPI 屏驱动（esp_lcd + waveshare/esp_lcd_st7735）
 */

#include "driver/display/lcd.h"

#include "driver/spi_master.h"
#include "esp_check.h"
#include "esp_heap_caps.h"
#include "esp_lcd_panel_io.h"
#include "esp_lcd_panel_ops.h"
#include "esp_lcd_panel_vendor.h"
#include "esp_lcd_st7735.h"
#include "esp_log.h"
#include "esp_timer.h"
#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include <string.h>

static const char *TAG = "LCD";

/* 主机在 lcd.h 里定义（LCD_SPI_HOST），默认 SPI2 */
/* ST7735S 写周期标称 15MHz。实测这块 1.8" 屏：
 *   请求 26~32MHz -> 实际 80/3 ≈ 26.7MHz，70 fps
 *   请求 40~60MHz -> 实际 80/2 = 40MHz，98 fps   <-- 实机验证干净的上限
 *   请求 65~80MHz -> 实际 80/1 = 80MHz，164 fps   <-- 花屏，别用
 * 所以默认取 40MHz（真正跑 40MHz）；想再快就调 panel_test 的扫描模式复测。
 */
#define LCD_PCLK_HZ   (40 * 1000 * 1000)

static const lcd_pins_t k_default_pins = LCD_PINS_TEST_BOARD;

static lcd_pins_t                s_pins     = LCD_PINS_TEST_BOARD;
static esp_lcd_panel_handle_t    s_panel    = NULL;
static esp_lcd_panel_io_handle_t s_io       = NULL;
static uint16_t                 *s_band     = NULL;   /* 刷屏用的行缓冲 */
static bool                      s_bus_inited = false;
static uint32_t                  s_pclk_hz  = LCD_PCLK_HZ;
static int                       s_gap_x    = LCD_GAP_COL;
static int                       s_gap_y    = LCD_GAP_ROW;

void lcd_set_pclk(uint32_t hz)
{
    if (hz > 0) {
        s_pclk_hz = hz;
    }
}

uint32_t lcd_get_pclk(void)
{
    return s_pclk_hz;
}

void lcd_set_gap(int gx, int gy)
{
    s_gap_x = gx;
    s_gap_y = gy;
    if (s_panel != NULL) {
        esp_lcd_panel_set_gap(s_panel, gx, gy);
    }
}

void lcd_get_gap(int *gx, int *gy)
{
    if (gx != NULL) { *gx = s_gap_x; }
    if (gy != NULL) { *gy = s_gap_y; }
}

esp_err_t lcd_deinit(void)
{
    if (s_panel != NULL) {
        esp_lcd_panel_del(s_panel);
        s_panel = NULL;
    }
    if (s_io != NULL) {
        esp_lcd_panel_io_del(s_io);
        s_io = NULL;
    }
    if (s_bus_inited) {
        spi_bus_free(LCD_SPI_HOST);
        s_bus_inited = false;
    }
    return ESP_OK;
}

esp_err_t lcd_init(void)
{
    return lcd_init_ex(NULL);
}

esp_err_t lcd_init_ex(const lcd_pins_t *pins)
{
    if (s_panel != NULL) {
        return ESP_OK;
    }
    s_pins = (pins != NULL) ? *pins : k_default_pins;

    /* 1. SPI 总线 */
    spi_bus_config_t bus_cfg = {
        .sclk_io_num     = s_pins.sclk,
        .mosi_io_num     = s_pins.mosi,
        .miso_io_num     = -1,
        .quadwp_io_num   = -1,
        .quadhd_io_num   = -1,
        .max_transfer_sz = LCD_H_RES * LCD_FILL_BAND_ROWS * sizeof(uint16_t),
    };
    ESP_RETURN_ON_ERROR(spi_bus_initialize(LCD_SPI_HOST, &bus_cfg, SPI_DMA_CH_AUTO), TAG,
                        "spi bus init failed");
    s_bus_inited = true;

    /* 2. panel IO（SPI 协议适配） */
    esp_lcd_panel_io_spi_config_t io_cfg = {
        .cs_gpio_num       = s_pins.cs,
        .dc_gpio_num       = s_pins.dc,
        .spi_mode          = 0,
        .pclk_hz           = s_pclk_hz,
        .trans_queue_depth = 10,
        .lcd_cmd_bits      = 8,
        .lcd_param_bits    = 8,
    };
    ESP_RETURN_ON_ERROR(
        esp_lcd_new_panel_io_spi((esp_lcd_spi_bus_handle_t)LCD_SPI_HOST, &io_cfg, &s_io), TAG,
        "panel io init failed");

    /* 3. ST7735 驱动 */
    esp_lcd_panel_dev_config_t panel_cfg = {
        .reset_gpio_num = s_pins.rst,
        .rgb_ele_order  = LCD_USE_BGR_ORDER ? LCD_RGB_ELEMENT_ORDER_BGR
                                            : LCD_RGB_ELEMENT_ORDER_RGB,
        .bits_per_pixel = 16,
    };
    ESP_RETURN_ON_ERROR(esp_lcd_new_panel_st7735(s_io, &panel_cfg, &s_panel), TAG,
                        "st7735 panel init failed");

    ESP_RETURN_ON_ERROR(esp_lcd_panel_reset(s_panel), TAG, "panel reset failed");
    ESP_RETURN_ON_ERROR(esp_lcd_panel_init(s_panel), TAG, "panel init failed");
    ESP_RETURN_ON_ERROR(esp_lcd_panel_set_gap(s_panel, s_gap_x, s_gap_y), TAG,
                        "panel set gap failed");
    ESP_RETURN_ON_ERROR(esp_lcd_panel_invert_color(s_panel, LCD_INVERT_COLOR), TAG,
                        "panel invert failed");

    /* 顺时针 90°：先交换 X/Y，再做镜像 */
#if LCD_ROT_SWAP_XY
    ESP_RETURN_ON_ERROR(esp_lcd_panel_swap_xy(s_panel, true), TAG, "panel swap_xy failed");
#endif
    ESP_RETURN_ON_ERROR(esp_lcd_panel_mirror(s_panel, LCD_ROT_MIRROR_X, LCD_ROT_MIRROR_Y), TAG,
                        "panel mirror failed");
    ESP_RETURN_ON_ERROR(esp_lcd_panel_disp_on_off(s_panel, true), TAG, "panel disp on failed");

    /* 背光脚配成输出并先关掉（占空比/电平由 lcd_backlight 控制） */
    if (s_pins.bl != GPIO_NUM_NC) {
        gpio_config_t bl_cfg = {
            .pin_bit_mask = (1ULL << s_pins.bl),
            .mode         = GPIO_MODE_OUTPUT,
        };
        ESP_RETURN_ON_ERROR(gpio_config(&bl_cfg), TAG, "backlight gpio config failed");
        gpio_set_level(s_pins.bl, !LCD_BL_ACTIVE_LEVEL);
        gpio_set_drive_capability(s_pins.bl, GPIO_DRIVE_CAP_2);
    }

    /* 4. 刷屏行缓冲（DMA 可访问的内部 RAM）；反复 reconfigure 时只分配一次 */
    if (s_band == NULL) {
        s_band = heap_caps_malloc(LCD_H_RES * LCD_FILL_BAND_ROWS * sizeof(uint16_t),
                                  MALLOC_CAP_DMA | MALLOC_CAP_INTERNAL);
        if (s_band == NULL) {
            ESP_LOGE(TAG, "no memory for fill buffer");
            return ESP_ERR_NO_MEM;
        }
    }

    ESP_LOGI(TAG, "st7735 ready: native %dx%d, landscape %dx%d, pclk %u MHz, "
                  "pins sclk=%d mosi=%d cs=%d dc=%d rst=%d bl=%d",
             LCD_NATIVE_W, LCD_NATIVE_H, LCD_H_RES, LCD_V_RES, (unsigned)(s_pclk_hz / 1000000),
             (int)s_pins.sclk, (int)s_pins.mosi, (int)s_pins.cs, (int)s_pins.dc, (int)s_pins.rst,
             (int)s_pins.bl);
    return ESP_OK;
}

void lcd_backlight(bool on)
{
    if (s_pins.bl == GPIO_NUM_NC) {
        return;
    }
    gpio_set_level(s_pins.bl, on ? LCD_BL_ACTIVE_LEVEL : !LCD_BL_ACTIVE_LEVEL);
}

/* 给 LVGL 移植层用的底层句柄 */
esp_lcd_panel_io_handle_t lcd_io_handle(void)
{
    return s_io;
}

esp_lcd_panel_handle_t lcd_panel_handle(void)
{
    return s_panel;
}

esp_err_t lcd_draw_bitmap(int x0, int y0, int x1, int y1, const void *data)
{
    ESP_RETURN_ON_FALSE(s_panel != NULL, ESP_ERR_INVALID_STATE, TAG, "lcd not initialized");

    /* 坐标直接给横屏逻辑坐标：MADCTL 的 MV 位会让面板内部完成转置,
     * 软件再换一次就转了两遍（画面会劈成两半）。 */
    return esp_lcd_panel_draw_bitmap(s_panel, x0, y0, x1, y1, data);
}

esp_err_t lcd_fill_rect(int x, int y, int w, int h, uint16_t color)
{
    ESP_RETURN_ON_FALSE(s_panel != NULL, ESP_ERR_INVALID_STATE, TAG, "lcd not initialized");

    /* 裁剪到屏幕内 */
    if (x < 0) { w += x; x = 0; }
    if (y < 0) { h += y; y = 0; }
    if (x + w > LCD_H_RES) { w = LCD_H_RES - x; }
    if (y + h > LCD_V_RES) { h = LCD_V_RES - y; }
    if (w <= 0 || h <= 0) {
        return ESP_OK;
    }

    /* 一次最多铺满整行，按 band 分批发 */
    int band_w = (w > LCD_H_RES) ? LCD_H_RES : w;
    for (int i = 0; i < band_w; i++) {
        s_band[i] = color;
    }

    for (int row = 0; row < h; row += LCD_FILL_BAND_ROWS) {
        int rows = (h - row > LCD_FILL_BAND_ROWS) ? LCD_FILL_BAND_ROWS : (h - row);
        /* 每行内容一致，直接重复写满 band 区 */
        for (int r = 1; r < rows; r++) {
            memcpy(&s_band[r * band_w], s_band, band_w * sizeof(uint16_t));
        }
        ESP_RETURN_ON_ERROR(
            lcd_draw_bitmap(x, y + row, x + band_w, y + row + rows, s_band), TAG,
            "fill rect failed");
    }

    /* 宽度超过一块 band 时，剩余列再刷一遍（正常用不到，屏幕只有 160 宽） */
    if (w > band_w) {
        ESP_RETURN_ON_ERROR(lcd_fill_rect(x + band_w, y, w - band_w, h, color), TAG,
                            "fill rect tail failed");
    }
    return ESP_OK;
}

esp_err_t lcd_fill(uint16_t color)
{
    return lcd_fill_rect(0, 0, LCD_H_RES, LCD_V_RES, color);
}

esp_err_t lcd_draw_hline(int x, int y, int w, uint16_t color)
{
    return lcd_fill_rect(x, y, w, 1, color);
}

esp_err_t lcd_draw_vline(int x, int y, int h, uint16_t color)
{
    return lcd_fill_rect(x, y, 1, h, color);
}

esp_err_t lcd_draw_frame(int x, int y, int w, int h, int thickness, uint16_t color)
{
    esp_err_t ret = ESP_OK;
    ret |= lcd_fill_rect(x, y, w, thickness, color);
    ret |= lcd_fill_rect(x, y + h - thickness, w, thickness, color);
    ret |= lcd_fill_rect(x, y, thickness, h, color);
    ret |= lcd_fill_rect(x + w - thickness, y, thickness, h, color);
    return ret;
}

/* ========================= 自检图案 ========================= */
void lcd_test_pattern(void)
{
    const int w = LCD_H_RES;
    const int h = LCD_V_RES;

    /* 底色 + 白框 */
    lcd_fill(LCD_COLOR_BLACK);
    lcd_draw_frame(0, 0, w, h, 2, LCD_COLOR_WHITE);

    /* 四角标记：左上红、右上绿、左下蓝、右下黄 */
    lcd_fill_rect(4, 4, 20, 20, LCD_COLOR_RED);
    lcd_fill_rect(w - 24, 4, 20, 20, LCD_COLOR_GREEN);
    lcd_fill_rect(4, h - 24, 20, 20, LCD_COLOR_BLUE);
    lcd_fill_rect(w - 24, h - 24, 20, 20, LCD_COLOR_YELLOW);

    /* 顶部中间向上的三角（看方向用） */
    for (int i = 0; i < 12; i++) {
        lcd_fill_rect(w / 2 - i, 6 + i, i * 2 + 1, 1, LCD_COLOR_WHITE);
    }

    /* 中间彩条（看 R/G/B 顺序） */
    static const uint16_t bars[8] = {
        LCD_COLOR_WHITE, LCD_COLOR_YELLOW, LCD_COLOR_CYAN,  LCD_COLOR_GREEN,
        LCD_COLOR_MAGENTA, LCD_COLOR_RED,  LCD_COLOR_BLUE,  LCD_COLOR_BLACK,
    };
    const int bar_w = w / 8;
    for (int i = 0; i < 8; i++) {
        lcd_fill_rect(i * bar_w, h / 2 - 16, bar_w, 32, bars[i]);
    }

    ESP_LOGI(TAG, "test pattern drawn");
}

uint32_t lcd_bench_fill_fps(void)
{
    const int rounds = 10;

    if (s_panel == NULL) {
        return 0;
    }

    int64_t t0 = esp_timer_get_time();
    for (int i = 0; i < rounds; i++) {
        lcd_fill((i & 1) ? LCD_COLOR_BLACK : LCD_COLOR_WHITE);
    }
    int64_t dt_us = esp_timer_get_time() - t0;

    if (dt_us <= 0) {
        return 0;
    }

    /* fps = rounds / (dt_us / 1e6) */
    uint32_t fps = (uint32_t)(((int64_t)rounds * 1000000) / dt_us);
    ESP_LOGI(TAG, "full screen fill: %u fps (%d ms/frame)", (unsigned)fps,
             (int)(dt_us / 1000 / rounds));
    return fps;
}
