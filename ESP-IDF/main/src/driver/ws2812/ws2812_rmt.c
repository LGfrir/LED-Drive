/**
 * @file ws2812_rmt.c
 * @author LG_frir
 * @date 2026-11-11 17:30:14
 * @brief RMT 硬件发 WS2812。时序在编码器里，CPU 只改缓存再 show。
 *
 *   T0H = 0.40 us, T0L = 0.85 us
 *   T1H = 0.80 us, T1L = 0.45 us
 *   RESET 低电平 280 us
 *   数据 GRB，高位先发。
 */

#include "driver/ws2812/ws2812_rmt.h"

#include <stdbool.h>
#include <stddef.h>
#include <stdlib.h>
#include <string.h>

#include "freertos/FreeRTOS.h"
#include "driver/rmt_encoder.h"
#include "driver/rmt_tx.h"
#include "esp_err.h"
#include "esp_log.h"

#define TAG "ws2812_rmt"

/* 20 MHz -> 1 tick = 50 ns，下面的纳秒数都能整除 */
#define WS2812_RMT_RESOLUTION_HZ 20000000
#define WS2812_RESET_US          280

/*
 * RMT 的 DMA 单块上限。
 * rmt_tx_init_dma_link() 里检查 mem_block_symbols > 2047 就返回
 * ESP_ERR_INVALID_ARG，整条通道随后退回 48 符号中断补数。
 * 48 符号 = 60 us 一块：500 颗灯每帧要补 250 次，BLE 连接事件或
 * 一次 Flash 写（30~3000 us）就足够让 FIFO 断流、整条花屏。
 * 夹到 2046（偶数）：500 颗灯每帧只需补 6 次，截止期放宽到 2.56 ms。
 */
#define WS2812_DMA_MAX_SYMBOLS   2046u

#define NS_TO_TICKS(ns) ((uint32_t)(((uint64_t)(ns) * WS2812_RMT_RESOLUTION_HZ) / 1000000000ULL))

typedef struct {
    rmt_encoder_t base;
    rmt_encoder_t *bytes_encoder;
    rmt_encoder_t *copy_encoder;
    int state;
    rmt_symbol_word_t reset_code;
} ws2812_encoder_t;

static ws2812_rmt_handle_t *s_default;

struct ws2812_rmt_bus {
    gpio_num_t gpio;
    uint16_t led_count;
    uint8_t *frame;
    rmt_channel_handle_t channel;
    rmt_encoder_handle_t encoder;
    bool enabled;
    bool dma;
};

#define WS2812_CONTAINER_OF(ptr, type, member) \
    ((type *)((char *)(ptr) - offsetof(type, member)))

RMT_ENCODER_FUNC_ATTR
static size_t ws2812_encode(rmt_encoder_t *encoder, rmt_channel_handle_t channel,
                            const void *primary_data, size_t data_size,
                            rmt_encode_state_t *ret_state)
{
    ws2812_encoder_t *ws2812 = WS2812_CONTAINER_OF(encoder, ws2812_encoder_t, base);
    rmt_encode_state_t session_state = RMT_ENCODING_RESET;
    rmt_encode_state_t state = RMT_ENCODING_RESET;
    size_t encoded_symbols = 0;

    switch (ws2812->state) {
    case 0:
        encoded_symbols += ws2812->bytes_encoder->encode(ws2812->bytes_encoder, channel,
                                                         primary_data, data_size, &session_state);
        if (session_state & RMT_ENCODING_COMPLETE) {
            ws2812->state = 1;
        }
        if (session_state & RMT_ENCODING_MEM_FULL) {
            state |= RMT_ENCODING_MEM_FULL;
            goto out;
        }
        /* fall-through */
    case 1:
        encoded_symbols += ws2812->copy_encoder->encode(ws2812->copy_encoder, channel,
                                                        &ws2812->reset_code,
                                                        sizeof(ws2812->reset_code), &session_state);
        if (session_state & RMT_ENCODING_COMPLETE) {
            ws2812->state = RMT_ENCODING_RESET;
            state |= RMT_ENCODING_COMPLETE;
        }
        if (session_state & RMT_ENCODING_MEM_FULL) {
            state |= RMT_ENCODING_MEM_FULL;
            goto out;
        }
        break;
    default:
        break;
    }

out:
    *ret_state = state;
    return encoded_symbols;
}

static esp_err_t ws2812_encoder_reset(rmt_encoder_t *encoder)
{
    ws2812_encoder_t *ws2812 = WS2812_CONTAINER_OF(encoder, ws2812_encoder_t, base);
    rmt_encoder_reset(ws2812->bytes_encoder);
    rmt_encoder_reset(ws2812->copy_encoder);
    ws2812->state = RMT_ENCODING_RESET;
    return ESP_OK;
}

static esp_err_t ws2812_encoder_del(rmt_encoder_t *encoder)
{
    ws2812_encoder_t *ws2812 = WS2812_CONTAINER_OF(encoder, ws2812_encoder_t, base);
    rmt_del_encoder(ws2812->bytes_encoder);
    rmt_del_encoder(ws2812->copy_encoder);
    free(ws2812);
    return ESP_OK;
}

static esp_err_t ws2812_encoder_new(rmt_encoder_handle_t *ret_encoder)
{
    ws2812_encoder_t *ws2812 = rmt_alloc_encoder_mem(sizeof(ws2812_encoder_t));
    if (ws2812 == NULL) {
        return ESP_ERR_NO_MEM;
    }

    ws2812->base.encode = ws2812_encode;
    ws2812->base.reset = ws2812_encoder_reset;
    ws2812->base.del = ws2812_encoder_del;

    rmt_bytes_encoder_config_t bytes_cfg = {
        .bit0 = {
            .level0 = 1,
            .duration0 = NS_TO_TICKS(400),
            .level1 = 0,
            .duration1 = NS_TO_TICKS(850),
        },
        .bit1 = {
            .level0 = 1,
            .duration0 = NS_TO_TICKS(800),
            .level1 = 0,
            .duration1 = NS_TO_TICKS(450),
        },
        .flags.msb_first = 1,
    };
    esp_err_t err = rmt_new_bytes_encoder(&bytes_cfg, &ws2812->bytes_encoder);
    if (err != ESP_OK) {
        free(ws2812);
        return err;
    }

    rmt_copy_encoder_config_t copy_cfg = {};
    err = rmt_new_copy_encoder(&copy_cfg, &ws2812->copy_encoder);
    if (err != ESP_OK) {
        rmt_del_encoder(ws2812->bytes_encoder);
        free(ws2812);
        return err;
    }

    uint32_t reset_ticks = WS2812_RESET_US * (WS2812_RMT_RESOLUTION_HZ / 1000000) / 2;
    ws2812->reset_code = (rmt_symbol_word_t) {
        .level0 = 0,
        .duration0 = reset_ticks,
        .level1 = 0,
        .duration1 = reset_ticks,
    };

    *ret_encoder = &ws2812->base;
    return ESP_OK;
}

esp_err_t ws2812_rmt_init(const ws2812_rmt_config_t *cfg, ws2812_rmt_handle_t **out)
{
    if (cfg == NULL || out == NULL || cfg->led_count == 0 || !GPIO_IS_VALID_OUTPUT_GPIO(cfg->gpio)) {
        return ESP_ERR_INVALID_ARG;
    }

    ws2812_rmt_handle_t *bus = calloc(1, sizeof(*bus));
    if (bus == NULL) {
        return ESP_ERR_NO_MEM;
    }
    bus->gpio = cfg->gpio;
    bus->led_count = cfg->led_count;
    bus->frame = calloc(cfg->led_count, 3);
    if (bus->frame == NULL) {
        free(bus);
        return ESP_ERR_NO_MEM;
    }

    /*
     * 把一整帧塞进一块 DMA 缓冲，编码器中途不再把内存写满。
     * 但块大小必须夹在硬件上限内：原来直接按 led_count*24+64 申请，
     * 96 颗(2368)、500 颗(12064) 都超过 2047，被驱动判为非法参数，
     * 于是整条通道退回 48 符号——比不申请 DMA 还糟。
     */
    size_t want = (size_t)cfg->led_count * 24u + 64u;
    if (want > WS2812_DMA_MAX_SYMBOLS) {
        want = WS2812_DMA_MAX_SYMBOLS;
    }
    if (want < 48u) {
        want = 48u;
    }
    if (want & 1u) {
        want--; /* 块大小取偶数 */
    }
    rmt_tx_channel_config_t tx_cfg = {
        .clk_src = RMT_CLK_SRC_DEFAULT,
        .gpio_num = cfg->gpio,
        .mem_block_symbols = want,
        .resolution_hz = WS2812_RMT_RESOLUTION_HZ,
        .trans_queue_depth = 4,
        .flags.with_dma = true,
        .flags.init_level = 0,
    };
    /*
     * 探测 DMA 时 RMT 驱动会打 E 级日志（no free tx channels 等），
     * 但 ESP32-S3 只有 1 个 RMT 通道支持 DMA，其余 3 路拿不到是必然的，
     * 不是故障。这里临时压掉驱动日志，免得每次启动都像报错。
     */
    esp_log_level_set("rmt", ESP_LOG_NONE);
    esp_err_t err = rmt_new_tx_channel(&tx_cfg, &bus->channel);
    esp_log_level_set("rmt", ESP_LOG_INFO);
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "gpio%d: 无非 DMA 通道可用(%s)；ESP32-S3 只有 1 个 RMT 通道支持 DMA，"
                      "%u 颗灯改走 48 符号中断补数",
                 (int)cfg->gpio, esp_err_to_name(err), cfg->led_count);
        tx_cfg.mem_block_symbols = 48;
        tx_cfg.flags.with_dma = false;
        esp_log_level_set("rmt", ESP_LOG_NONE);
        err = rmt_new_tx_channel(&tx_cfg, &bus->channel);
        esp_log_level_set("rmt", ESP_LOG_INFO);
        bus->dma = false;
    } else {
        bus->dma = true;
    }
    if (err != ESP_OK) {
        goto fail;
    }
    err = ws2812_encoder_new(&bus->encoder);
    if (err != ESP_OK) {
        goto fail;
    }
    err = rmt_enable(bus->channel);
    if (err != ESP_OK) {
        goto fail;
    }
    bus->enabled = true;

    *out = bus;
    ESP_LOGI(TAG, "RMT 灯带就绪 gpio=%d leds=%u", cfg->gpio, cfg->led_count);
    return ESP_OK;

fail:
    if (bus->enabled) {
        rmt_disable(bus->channel);
    }
    if (bus->channel != NULL) {
        rmt_del_channel(bus->channel);
    }
    if (bus->encoder != NULL) {
        rmt_del_encoder(bus->encoder);
    }
    free(bus->frame);
    free(bus);
    return err;
}

void ws2812_rmt_set_default(ws2812_rmt_handle_t *bus)
{
    s_default = bus;
}

ws2812_rmt_handle_t *ws2812_rmt_default(void)
{
    return s_default;
}

esp_err_t ws2812_rmt_del(ws2812_rmt_handle_t *bus)
{
    if (bus == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    if (s_default == bus) {
        s_default = NULL;
    }
    if (bus->enabled) {
        rmt_disable(bus->channel);
    }
    if (bus->channel != NULL) {
        rmt_del_channel(bus->channel);
    }
    if (bus->encoder != NULL) {
        rmt_del_encoder(bus->encoder);
    }
    free(bus->frame);
    free(bus);
    return ESP_OK;
}

void ws2812_rmt_set_pixel(ws2812_rmt_handle_t *bus, uint16_t index, uint8_t r, uint8_t g, uint8_t b)
{
    if (bus == NULL || index >= bus->led_count) {
        return;
    }
    uint8_t *px = &bus->frame[index * 3];
    px[0] = g;
    px[1] = r;
    px[2] = b;
}

void ws2812_rmt_fill(ws2812_rmt_handle_t *bus, uint8_t r, uint8_t g, uint8_t b)
{
    if (bus == NULL) {
        return;
    }
    for (uint16_t i = 0; i < bus->led_count; i++) {
        ws2812_rmt_set_pixel(bus, i, r, g, b);
    }
}

void ws2812_rmt_clear(ws2812_rmt_handle_t *bus)
{
    if (bus == NULL) {
        return;
    }
    memset(bus->frame, 0, (size_t)bus->led_count * 3);
}

esp_err_t ws2812_rmt_show(ws2812_rmt_handle_t *bus)
{
    if (bus == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    rmt_transmit_config_t tx_cfg = {
        .loop_count = 0,
        .flags.eot_level = 0,
    };
    esp_err_t err = rmt_transmit(bus->channel, bus->encoder, bus->frame,
                                 (size_t)bus->led_count * 3, &tx_cfg);
    if (err != ESP_OK) {
        return err;
    }
    return rmt_tx_wait_all_done(bus->channel, portMAX_DELAY);
}

uint16_t ws2812_rmt_led_count(const ws2812_rmt_handle_t *bus)
{
    return bus == NULL ? 0 : bus->led_count;
}

bool ws2812_rmt_has_dma(const ws2812_rmt_handle_t *bus)
{
    return bus != NULL && bus->dma;
}
