/**
 * @file ws2812_spi.c
 * @author LG_frir
 * @date 2026-11-11 17:30:10
 * @brief SPI 硬件发 WS2812。SPI2 / SPI3 各能带一路。
 */

#include "driver/ws2812/ws2812_spi.h"

#include <stdlib.h>
#include <string.h>

#include "esp_log.h"
#include "esp_timer.h"

#define TAG "ws2812_spi"

/* 3.2 MHz 下 4 个 SPI 位 = 1.25 us，对齐 WS2812 的一个位时间 */
#define WS2812_SPI_CLOCK_HZ  3200000
/*
 * 复位低电平：24 字节只有 60 us。WS2812B 的最低要求是 50 us，余量太薄；
 * 老 WS2812 要 280 us。这里按 280 us 给足：280 us × 3.2 MHz / 8 = 112 字节。
 * 多出的 52 字节只多花 130 us，对 20 ms 的刷新周期没有影响。
 */
#define WS2812_SPI_RESET_BYTES 112

struct ws2812_spi_bus {
    spi_host_device_t host;
    spi_device_handle_t device;
    uint16_t led_count;
    uint8_t *frame;
    uint8_t *encoded;
    size_t encoded_bytes;
};

static void encode_byte(uint8_t data, uint8_t out[4])
{
    uint32_t bits = 0;
    for (int i = 0; i < 8; i++) {
        bits <<= 4;
        bits |= (data & 0x80) ? 0x0E : 0x08;
        data <<= 1;
    }
    out[0] = (uint8_t)(bits >> 24);
    out[1] = (uint8_t)(bits >> 16);
    out[2] = (uint8_t)(bits >> 8);
    out[3] = (uint8_t)bits;
}

esp_err_t ws2812_spi_init(const ws2812_spi_config_t *cfg, ws2812_spi_handle_t **out)
{
    if (cfg == NULL || out == NULL || cfg->led_count == 0 ||
        (cfg->host != SPI2_HOST && cfg->host != SPI3_HOST) ||
        !GPIO_IS_VALID_OUTPUT_GPIO(cfg->mosi) || !GPIO_IS_VALID_OUTPUT_GPIO(cfg->sclk)) {
        return ESP_ERR_INVALID_ARG;
    }

    ws2812_spi_handle_t *bus = calloc(1, sizeof(*bus));
    if (bus == NULL) {
        return ESP_ERR_NO_MEM;
    }
    bus->host = cfg->host;
    bus->led_count = cfg->led_count;
    bus->encoded_bytes = (size_t)cfg->led_count * 12 + WS2812_SPI_RESET_BYTES;
    bus->frame = calloc(cfg->led_count, 3);
    bus->encoded = calloc(1, bus->encoded_bytes);
    if (bus->frame == NULL || bus->encoded == NULL) {
        free(bus->frame);
        free(bus->encoded);
        free(bus);
        return ESP_ERR_NO_MEM;
    }

    spi_bus_config_t bus_cfg = {
        .mosi_io_num = cfg->mosi,
        .miso_io_num = -1,
        .sclk_io_num = cfg->sclk,
        .quadwp_io_num = -1,
        .quadhd_io_num = -1,
        .max_transfer_sz = (int)bus->encoded_bytes,
    };
    esp_err_t err = spi_bus_initialize(cfg->host, &bus_cfg, SPI_DMA_CH_AUTO);
    if (err != ESP_OK) {
        goto fail;
    }

    spi_device_interface_config_t dev_cfg = {
        .clock_speed_hz = WS2812_SPI_CLOCK_HZ,
        .mode = 0,
        .spics_io_num = -1,
        .queue_size = 1,
    };
    err = spi_bus_add_device(cfg->host, &dev_cfg, &bus->device);
    if (err != ESP_OK) {
        spi_bus_free(cfg->host);
        goto fail;
    }

    *out = bus;
    ESP_LOGI(TAG, "SPI 灯带就绪 host=%d mosi=%d sclk=%d leds=%u",
             cfg->host, cfg->mosi, cfg->sclk, cfg->led_count);
    return ESP_OK;

fail:
    free(bus->frame);
    free(bus->encoded);
    free(bus);
    return err;
}

esp_err_t ws2812_spi_del(ws2812_spi_handle_t *bus)
{
    if (bus == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    if (bus->device != NULL) {
        spi_bus_remove_device(bus->device);
    }
    spi_bus_free(bus->host);
    free(bus->frame);
    free(bus->encoded);
    free(bus);
    return ESP_OK;
}

void ws2812_spi_set_pixel(ws2812_spi_handle_t *bus, uint16_t index, uint8_t r, uint8_t g, uint8_t b)
{
    if (bus == NULL || index >= bus->led_count) {
        return;
    }
    uint8_t *px = &bus->frame[index * 3];
    px[0] = g;
    px[1] = r;
    px[2] = b;
}

void ws2812_spi_fill(ws2812_spi_handle_t *bus, uint8_t r, uint8_t g, uint8_t b)
{
    if (bus == NULL) {
        return;
    }
    for (uint16_t i = 0; i < bus->led_count; i++) {
        ws2812_spi_set_pixel(bus, i, r, g, b);
    }
}

void ws2812_spi_clear(ws2812_spi_handle_t *bus)
{
    if (bus == NULL) {
        return;
    }
    memset(bus->frame, 0, (size_t)bus->led_count * 3);
}

esp_err_t ws2812_spi_show(ws2812_spi_handle_t *bus)
{
    if (bus == NULL) {
        return ESP_ERR_INVALID_ARG;
    }

    uint8_t *dst = bus->encoded;
    for (uint16_t i = 0; i < bus->led_count; i++) {
        const uint8_t *px = &bus->frame[i * 3];
        encode_byte(px[0], dst);
        encode_byte(px[1], dst + 4);
        encode_byte(px[2], dst + 8);
        dst += 12;
    }
    memset(dst, 0, WS2812_SPI_RESET_BYTES);

    spi_transaction_t trans = {
        .length = bus->encoded_bytes * 8,
        .tx_buffer = bus->encoded,
    };

    int64_t start_us = esp_timer_get_time();
    esp_err_t err = spi_device_transmit(bus->device, &trans);
    int64_t cost_us = esp_timer_get_time() - start_us;

    /* 首帧打一条：确认 SPI 真的把整帧发出去了，顺带看硬件耗时。 */
    static bool s_first_frame_logged;
    if (!s_first_frame_logged) {
        s_first_frame_logged = true;
        if (err == ESP_OK) {
            ESP_LOGI(TAG, "首帧发送完成：%u 字节，%lld us",
                     (unsigned)bus->encoded_bytes, (long long)cost_us);
        } else {
            ESP_LOGE(TAG, "首帧发送失败：%s", esp_err_to_name(err));
        }
    } else if (err != ESP_OK) {
        static bool s_error_logged;
        if (!s_error_logged) {
            s_error_logged = true;
            ESP_LOGE(TAG, "SPI 发送失败：%s", esp_err_to_name(err));
        }
    }
    return err;
}

uint16_t ws2812_spi_led_count(const ws2812_spi_handle_t *bus)
{
    return bus == NULL ? 0 : bus->led_count;
}
