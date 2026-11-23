/**
 * @file lcd.h
 * @author LG_frir
 * @date 2026-11-17
 * @brief ST7735 SPI 屏驱动（esp_lcd）
 *
 * 面板原生竖屏 128x160，代码里所有绘图坐标都按"横屏 160x128"给，
 * 旋转靠面板 MADCTL 硬件完成（顺时针 90°），不占 CPU。
 *
 * 引脚是可配置的：把 lcd_pins_t 传进来即可，方便搬进别的工程。
 * 本文件不含任何 IO 供电逻辑，屏幕 VCC/GND 若走 GPIO 由上层负责。
 */

#ifndef LCD_H
#define LCD_H

#include <stdbool.h>
#include <stdint.h>

#include "driver/gpio.h"
#include "driver/spi_master.h"
#include "esp_err.h"
#include "esp_lcd_types.h"

/* ===================== SPI 主机 =====================
 * 默认 SPI2。注意：LEDDrive 里 SPI2 被 SPI 灯带占用（ws2812_spi.c），
 * 那边必须在工程的编译选项里改成 SPI3_HOST，例如在 main/CMakeLists.txt 里：
 *   target_compile_definitions(${COMPONENT_LIB} PUBLIC LCD_SPI_HOST=SPI3_HOST)
 */
#ifndef LCD_SPI_HOST
#define LCD_SPI_HOST  SPI2_HOST
#endif

/* ===================== 面板参数 ===================== */
#define LCD_NATIVE_W 128        /* 竖屏宽（面板列数） */
#define LCD_NATIVE_H 160        /* 竖屏高 */

/* 横屏逻辑分辨率：整幅画面顺时针转 90° 之后使用 */
#define LCD_H_RES 160
#define LCD_V_RES 128

/* 顺时针 90° = 交换 X/Y（MV）+ 行镜像（MY）；画面翻转就改这两个 */
#define LCD_ROT_SWAP_XY  1
#define LCD_ROT_MIRROR_X 0
#define LCD_ROT_MIRROR_Y 1

/* ST7735 有的批次需要开反色。这块 1.8" 屏实测不需要（1 会让黑白颠倒） */
#define LCD_INVERT_COLOR 0

/* 这块屏实测走 RGB 顺序（BGR 会红蓝互换） */
#define LCD_USE_BGR_ORDER 0

/* 面板可视区偏移：这块 1.8" 屏实测是 GX=1 / GY=2
 * （用 main.c 的 APP_MODE 5 扫出来的：满幅边框刚好贴住玻璃四边）
 * 换别的批次如果四周露黑边或被切，就重跑 APP_MODE 5 标定 */
#define LCD_GAP_COL 1
#define LCD_GAP_ROW 2

/* 刷屏时按多少行一批发（越大越快，占内存越多） */
#define LCD_FILL_BAND_ROWS 8

/* ===================== 引脚 =====================
 *   TEST_BOARD   : 手上这块测试板（VCC/GND 也用 GPIO 供）
 *   LEDDRIVE_SAFE: 避开 LEDDrive 已占用的脚：
 *                    GPIO1  灯带 IO1（RMT）
 *                    GPIO5  灯带 IO2 的 SPI 时钟
 *                    GPIO7  灯带 IO2 的 SPI MOSI
 *                    GPIO11/12 是 ws2812_spi.h 里的默认 MOSI/SCLK，也别占
 *                    GPIO 3/45/46 是 strapping，GPIO19/20 是 USB，33-37 是 PSRAM
 *                  屏幕 VCC/GND 必须直接接 3V3/GND，不要用 GPIO 供电
 */
typedef struct {
    gpio_num_t sclk;
    gpio_num_t mosi;
    gpio_num_t cs;
    gpio_num_t dc;
    gpio_num_t rst;
    gpio_num_t bl;
} lcd_pins_t;

#define LCD_PINS_TEST_BOARD                          \
    {                                                \
        .sclk = GPIO_NUM_9,                          \
        .mosi = GPIO_NUM_10,                         \
        .cs   = GPIO_NUM_13,                         \
        .dc   = GPIO_NUM_12,                         \
        .rst  = GPIO_NUM_11,                         \
        .bl   = GPIO_NUM_14,                         \
    }

#define LCD_PINS_LEDDRIVE_SAFE                       \
    {                                                \
        .sclk = GPIO_NUM_9,                          \
        .mosi = GPIO_NUM_10,                         \
        .cs   = GPIO_NUM_14,                         \
        .dc   = GPIO_NUM_15,                         \
        .rst  = GPIO_NUM_16,                         \
        .bl   = GPIO_NUM_17,                         \
    }

/* 原 LEDDrive 板：IO1=GPIO14、IO2=GPIO4 / SCLK=GPIO5。
 * CS 不能再用 14，往上挪一格：CS=15 / DC=16 / RST=17 / BL=18。
 * 屏幕 VCC/GND 仍必须直接接 3V3/GND；BL 建议经三极管/MOS，不要 GPIO 直灌背光。 */
#define LCD_PINS_LEDDRIVE_ORIG                       \
    {                                                \
        .sclk = GPIO_NUM_9,                          \
        .mosi = GPIO_NUM_10,                         \
        .cs   = GPIO_NUM_15,                         \
        .dc   = GPIO_NUM_16,                         \
        .rst  = GPIO_NUM_17,                         \
        .bl   = GPIO_NUM_18,                         \
    }

/* 背光有效电平：1 = 高电平点亮 */
#define LCD_BL_ACTIVE_LEVEL 1

/* ===================== 颜色 =====================
 * 面板按高字节先收，主机 uint16_t 是小端，所以统一在宏里换字节，
 * 上层代码直接写 LCD_RGB565(r, g, b) 即可。
 */
#define LCD_SWAP16(c) ((uint16_t)(((uint16_t)(c) >> 8) | ((uint16_t)(c) << 8)))

#define LCD_RGB565(r, g, b)                            \
    LCD_SWAP16((uint16_t)((((uint16_t)(r) & 0xF8) << 8) | \
                           (((uint16_t)(g) & 0xFC) << 3) | \
                           (((uint16_t)(b) & 0xF8) >> 3)))

#define LCD_COLOR_BLACK   LCD_RGB565(0, 0, 0)
#define LCD_COLOR_WHITE   LCD_RGB565(255, 255, 255)
#define LCD_COLOR_RED     LCD_RGB565(255, 0, 0)
#define LCD_COLOR_GREEN   LCD_RGB565(0, 255, 0)
#define LCD_COLOR_BLUE    LCD_RGB565(0, 0, 255)
#define LCD_COLOR_YELLOW  LCD_RGB565(255, 255, 0)
#define LCD_COLOR_CYAN    LCD_RGB565(0, 255, 255)
#define LCD_COLOR_MAGENTA LCD_RGB565(255, 0, 255)
#define LCD_COLOR_GRAY    LCD_RGB565(128, 128, 128)

/* ===================== 接口 ===================== */

/* 用默认（测试板）接法初始化 */
esp_err_t lcd_init(void);

/* 指定引脚初始化；pins 为 NULL 时等同 lcd_init() */
esp_err_t lcd_init_ex(const lcd_pins_t *pins);

/* 反初始化（释放 panel/io/总线），之后可以重新 init */
esp_err_t lcd_deinit(void);

/* 运行时改显存偏移（标定面板可视区用）。gx/gy 是写进 CASET/RASET 的偏移，
 * 上电默认用 LCD_GAP_COL/LCD_GAP_ROW。 */
void lcd_set_gap(int gx, int gy);
void lcd_get_gap(int *gx, int *gy);

/* 改 SPI 像素时钟，必须在 lcd_deinit() 之后、lcd_init() 之前调用 */
void lcd_set_pclk(uint32_t hz);
uint32_t lcd_get_pclk(void);

/* 背光开关 */
void lcd_backlight(bool on);

/* 给 LVGL 移植层用的底层句柄 */
esp_lcd_panel_io_handle_t lcd_io_handle(void);
esp_lcd_panel_handle_t    lcd_panel_handle(void);

/* 推一块图像：坐标是横屏逻辑坐标，[x0,x1) [y0,y1)，data 为 RGB565 小端 */
esp_err_t lcd_draw_bitmap(int x0, int y0, int x1, int y1, const void *data);

/* 基础绘图（颜色都用 LCD_RGB565 系列） */
esp_err_t lcd_fill(uint16_t color);
esp_err_t lcd_fill_rect(int x, int y, int w, int h, uint16_t color);
esp_err_t lcd_draw_hline(int x, int y, int w, uint16_t color);
esp_err_t lcd_draw_vline(int x, int y, int h, uint16_t color);
esp_err_t lcd_draw_frame(int x, int y, int w, int h, int thickness, uint16_t color);

/* 上电自检图案：四角+三角+彩条，用来看旋转方向和颜色顺序对不对 */
void lcd_test_pattern(void);

/* 满屏刷屏速度（帧/秒），开机时测一次给界面显示；会闪几下屏 */
uint32_t lcd_bench_fill_fps(void);

#endif /* LCD_H */
