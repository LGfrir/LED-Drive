/**
 * @file ws2812_spi.h
 * @author LG_frir
 * @date 2026-11-11 17:30:19
 * @brief WS2812 的 SPI 后端。ESP32-S3 上同时最多 2 路，SPI2 和 SPI3 各一根 MOSI。
 *
 * 时钟脚必须分给 SPI，但不要接到灯带。灯带只接 MOSI。
 * 一个 WS2812 位编成 4 个 SPI 位，时钟 3.2 MHz，一位正好 1.25 us：
 *   0 = 1000，1 = 1110。
 */

#ifndef __WS2812_SPI_H__
#define __WS2812_SPI_H__

#include <stdint.h>
#include "driver/gpio.h"
#include "driver/spi_master.h"
#include "esp_err.h"

#define WS2812_SPI_DEFAULT_HOST      SPI2_HOST
#define WS2812_SPI_DEFAULT_MOSI      GPIO_NUM_11
#define WS2812_SPI_DEFAULT_SCLK      GPIO_NUM_12
#define WS2812_SPI_DEFAULT_LED_COUNT 90

typedef struct ws2812_spi_bus ws2812_spi_handle_t;

typedef struct {
    spi_host_device_t host;
    gpio_num_t mosi;
    gpio_num_t sclk;
    uint16_t led_count;
} ws2812_spi_config_t;

esp_err_t ws2812_spi_init(const ws2812_spi_config_t *cfg, ws2812_spi_handle_t **out);
esp_err_t ws2812_spi_del(ws2812_spi_handle_t *bus);

void ws2812_spi_set_pixel(ws2812_spi_handle_t *bus, uint16_t index, uint8_t r, uint8_t g, uint8_t b);
void ws2812_spi_fill(ws2812_spi_handle_t *bus, uint8_t r, uint8_t g, uint8_t b);
void ws2812_spi_clear(ws2812_spi_handle_t *bus);
esp_err_t ws2812_spi_show(ws2812_spi_handle_t *bus);

uint16_t ws2812_spi_led_count(const ws2812_spi_handle_t *bus);

#endif /* __WS2812_SPI_H__ */
