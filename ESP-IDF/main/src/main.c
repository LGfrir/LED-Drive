/**
 * @file main.c
 * @author LG_frir
 * @date 2026-11-11
 * @brief 两路灯带，每路 172 颗、三段 36 + 100 + 36。
 *
 * IO1：GPIO14，段 id 1-3，RMT + DMA
 * IO2：GPIO4， 段 id 4-6，SPI2 MOSI + DMA（SCLK GPIO5，只给 SPI 时钟，不接灯带）
 *
 * ESP32-S3 只有一个 RMT 发送通道能挂 DMA，IO1 独占；IO2 改走 SPI，
 * 不再和 RMT 抢通道，也就没有 48 符号中断补数。
 */

#include "driver/bt/NimBLE.h"
#include "driver/display/dash_data.h"
#include "driver/display/disp_dash.h"
#include "driver/display/lcd.h"
#include "driver/led_strip/light_mode_driver.h"
#include "driver/preset/light_preset.h"
#include "driver/timer/local_clock.h"
#include "driver/timer/time_sync.h"
#include "driver/ws2812/ws2812_rmt.h"
#include "driver/ws2812/ws2812_spi.h"
#include "task/auth_svc.h"
#include "task/clock_scheduler.h"
#include "task/gatt_svc.h"
#include "task/ota_svc.h"

#include "esp_log.h"
#include "nvs_flash.h"

#include <stdlib.h>
#include <string.h>
#include <time.h>

static const char *TAG = "main";

/*
 * 屏幕板（spiscreentest 的 COM4 测试板）和 LEDDrive 原板引脚不同：
 *   TEST_BOARD: 屏 9/10/13/12/11/14，灯带临时挪到 GPIO1/7，避开背光 14
 *   原板:       屏 9/10/15/16/17/18，灯带 GPIO14/4
 * 默认编译原板；测试板用 -DTEST_BOARD=1。
 */
#ifdef TEST_BOARD
#define IO1_GPIO GPIO_NUM_1
#define IO2_GPIO GPIO_NUM_7
static const lcd_pins_t s_lcd_pins = LCD_PINS_TEST_BOARD;
#else
#define IO1_GPIO GPIO_NUM_14
#define IO2_GPIO GPIO_NUM_4
static const lcd_pins_t s_lcd_pins = LCD_PINS_LEDDRIVE_ORIG;
#endif

/*
 * 两路，每路 172 颗，三段 36 + 100 + 36。
 *
 * IO1 走 RMT：ESP32-S3 的 4 个 RMT 发送通道里只有【最后一个】能挂 DMA
 * （见 esp_driver_rmt/src/rmt_tx.c 的 rmt_tx_register_to_group）。
 * IO2 走 SPI2：SPI 自带 DMA，不占 RMT 通道，也不再有 48 符号补数。
 *
 * 仍然按灯珠数从大到小建，保证以后加回多路 RMT 时，唯一的 DMA 落在
 * 最大的那条灯带上；led_count 相同时按 io_id 升序，启动分配结果稳定。
 */
static const light_seg_desc_t s_segs_io1[] = {
    { .id = 1, .length = 36, .name = "右" },
    { .id = 2, .length = 100, .name = "中" },
    { .id = 3, .length = 36, .name = "左" },
};

static const light_seg_desc_t s_segs_io2[] = {
    { .id = 4, .length = 36, .name = "右" },
    { .id = 5, .length = 100, .name = "中" },
    { .id = 6, .length = 36, .name = "左" },
};

typedef enum {
    IO_BUS_RMT = 0,
    IO_BUS_SPI,
} io_bus_t;

typedef struct {
    uint8_t io_id;
    const char *name; /* 显示名，随拓扑包一起发给 App */
    gpio_num_t gpio;  /* RMT: 数据线；SPI: MOSI */
    gpio_num_t sclk;  /* 仅 SPI 用；只给 SPI 时钟，不接灯带 */
    io_bus_t bus;
    uint16_t led_count;
    const light_seg_desc_t *segs;
    uint8_t seg_count;
} io_desc_t;

static const io_desc_t s_ios[] = {
    { .io_id = 1, .name = "笔记本", .gpio = IO1_GPIO, .bus = IO_BUS_RMT,
      .led_count = 172, .segs = s_segs_io1, .seg_count = 3 },
    { .io_id = 2, .name = "手办",   .gpio = IO2_GPIO, .sclk = GPIO_NUM_5,
      .bus = IO_BUS_SPI, .led_count = 172, .segs = s_segs_io2, .seg_count = 3 },
};

/*
 * 按灯珠数从大到小建，保证以后再加 RMT 路数时，唯一的 DMA 落在最大的
 * 灯带上，而不是"谁排在第 4 个谁倒霉"。
 * 现在 IO2 走 SPI，这条排序对 IO1 没有影响，留着是为了不把规则丢掉。
 */
static int by_leds_desc(const void *a, const void *b)
{
    const io_desc_t *x = a;
    const io_desc_t *y = b;
    if (x->led_count != y->led_count) {
        return x->led_count < y->led_count ? 1 : -1;
    }
    return (int)x->io_id - (int)y->io_id;
}

void app_main(void) 
{
    esp_err_t err = nvs_flash_init();
    if (err == ESP_ERR_NVS_NO_FREE_PAGES || err == ESP_ERR_NVS_NEW_VERSION_FOUND) {
        ESP_ERROR_CHECK(nvs_flash_erase());
        err = nvs_flash_init();
    }
    ESP_ERROR_CHECK(err);

    /* 本地时间按中国标准时间解释，调度表里的时间点也是本地时间 */
    setenv("TZ", "CST-8", 1);
    tzset();

    local_clock_init();
    ESP_ERROR_CHECK(light_preset_init());

    /* 按灯珠数从大到小建，大的先拿 DMA（见 by_leds_desc 的说明） */
    const size_t io_total = sizeof(s_ios) / sizeof(s_ios[0]);
    io_desc_t order[sizeof(s_ios) / sizeof(s_ios[0])];
    memcpy(order, s_ios, sizeof(order));
    qsort(order, io_total, sizeof(order[0]), by_leds_desc);

    for (size_t i = 0; i < io_total; i++) {
        const io_desc_t *d = &order[i];

        if (d->bus == IO_BUS_SPI) {
            ws2812_spi_handle_t *strip = NULL;
            const ws2812_spi_config_t strip_cfg = {
                .host = SPI2_HOST,
                .mosi = d->gpio,
                .sclk = d->sclk,
                .led_count = d->led_count,
            };
            ESP_ERROR_CHECK(ws2812_spi_init(&strip_cfg, &strip));
            ESP_ERROR_CHECK(light_io_create(d->io_id, d->name, d->led_count,
                                            255, 20,
                                            light_show_spi, strip,
                                            d->segs, d->seg_count,
                                            true));
            ESP_LOGI(TAG, "IO %u「%s」就绪：SPI2 MOSI=GPIO%d，SCLK=GPIO%d（不接灯带），"
                          "%u 颗，%u 段，DMA，刷新下限 %u ms",
                     d->io_id, d->name, (int)d->gpio, (int)d->sclk, d->led_count,
                     d->seg_count, light_refresh_floor_for(d->led_count, true));
            continue;
        }

        ws2812_rmt_handle_t *strip = NULL;
        const ws2812_rmt_config_t strip_cfg = {
            .gpio = d->gpio,
            .led_count = d->led_count,
        };
        ESP_ERROR_CHECK(ws2812_rmt_init(&strip_cfg, &strip));
        ESP_ERROR_CHECK(light_io_create(d->io_id, d->name, d->led_count,
                                        255, 20,
                                        light_show_rmt, strip,
                                        d->segs, d->seg_count,
                                        ws2812_rmt_has_dma(strip)));
        ESP_LOGI(TAG, "IO %u「%s」就绪：RMT GPIO%d，%u 颗，%u 段，%s，刷新下限 %u ms",
                 d->io_id, d->name, (int)d->gpio, d->led_count, d->seg_count,
                 ws2812_rmt_has_dma(strip) ? "DMA" : "48符号补数",
                 light_refresh_floor_for(d->led_count, ws2812_rmt_has_dma(strip)));
    }
    ESP_LOGI(TAG, "共 2 路、6 个灯段，每路 172 颗（36+100+36）");
    ESP_LOGW(TAG, "IO1 走 RMT DMA；IO2 走 SPI2 DMA（SCLK GPIO5 不接灯带），"
                  "不再抢 RMT 的 48 符号补数");

    /* 灯带都建好之后再起落盘任务：它要能遍历到全部 IO / 灯段 */
    ESP_ERROR_CHECK(light_persist_init());

    /* 调度器要在灯段建好之后启动：时间模式 + 未对时会立刻静默 */
    ESP_ERROR_CHECK(clock_scheduler_init());

    /*
     * 屏幕是可选的：ST7735 初始化失败只打日志，不影响灯带和 BLE。
     * 原板接线：SCLK=9 / MOSI=10 / CS=15 / DC=16 / RST=17 / BL=18。
     */
    esp_err_t disp_err = lcd_init_ex(&s_lcd_pins);
    if (disp_err == ESP_OK) {
        disp_err = dash_init();
    }
    if (disp_err == ESP_OK) {
        dash_data_start();
        lcd_backlight(true);
        ESP_LOGI(TAG, "display ready (ST7735 dashboard, SPI3)");
    } else {
        ESP_LOGW(TAG, "display disabled: %s", esp_err_to_name(disp_err));
    }

    time_sync_init(gatt_svc_time_request);
    auth_init();

    ESP_ERROR_CHECK(ota_init());

    nimble_init_as_prph();

    /* OTA image is only considered good after the app reached this point. */
    ota_boot_confirm();
}
