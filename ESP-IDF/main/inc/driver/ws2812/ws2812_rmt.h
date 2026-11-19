/**
 * @file ws2812_rmt.h
 * @author LG_frir
 * @date 2026-11-11 17:26:33
 * @brief WS2812 的 RMT 后端。
 *
 * ESP32-S3 有 4 个 RMT 发送通道，但能同时挂 DMA 的更少。拿不到 DMA 时
 * 退回 48 符号中断补数：48 符号 = 60 us 一块，必须在这段时间内补完下一块，
 * 截止期极紧，BLE 连接事件或 Flash 写一抖就断流。
 * 另外 RMT 的 DMA 单块上限是 2047 个符号，超过会被驱动直接拒绝。
 */

#ifndef __WS2812_RMT_H__
#define __WS2812_RMT_H__

#include <stdbool.h>
#include <stdint.h>
#include "driver/gpio.h"
#include "esp_err.h"

/* 当前这条灯带：数据线 GPIO14，90 颗，三段串成一条链。 */
#define WS2812_RMT_DEFAULT_GPIO      GPIO_NUM_14
#define WS2812_RMT_DEFAULT_LED_COUNT 96

typedef struct ws2812_rmt_bus ws2812_rmt_handle_t;

typedef struct {
    gpio_num_t gpio;
    uint16_t led_count;
} ws2812_rmt_config_t;

/* 颜色参数是 RGB。缓存里按 WS2812 要的 GRB 存放，show 时才发出去。 */
esp_err_t ws2812_rmt_init(const ws2812_rmt_config_t *cfg, ws2812_rmt_handle_t **out);
esp_err_t ws2812_rmt_del(ws2812_rmt_handle_t *bus);

/* main 里把正在用的那一路记下来，灯效代码用这个取。 */
void ws2812_rmt_set_default(ws2812_rmt_handle_t *bus);
ws2812_rmt_handle_t *ws2812_rmt_default(void);

void ws2812_rmt_set_pixel(ws2812_rmt_handle_t *bus, uint16_t index, uint8_t r, uint8_t g, uint8_t b);
void ws2812_rmt_fill(ws2812_rmt_handle_t *bus, uint8_t r, uint8_t g, uint8_t b);
void ws2812_rmt_clear(ws2812_rmt_handle_t *bus);
esp_err_t ws2812_rmt_show(ws2812_rmt_handle_t *bus);

uint16_t ws2812_rmt_led_count(const ws2812_rmt_handle_t *bus);

/*
 * 这一路是否用上了 DMA。
 * 返回 false 说明退回 48 符号中断补数，刷新间隔下限要抬高，
 * 否则发送任务会在硬件发送能力之外空转（见 light_refresh_floor_for）。
 */
bool ws2812_rmt_has_dma(const ws2812_rmt_handle_t *bus);

#endif /* __WS2812_RMT_H__ */
