/**
 * @file nvs_flash_save.c
 * @author LG_frir
 * @date 2026-11-11 17:49:52
 *
 * 命名空间 "light"。
 *   键 i<IO号>  存这个口的亮度和刷新率
 *   键 s<灯段号> 存这一段的整份灯效
 * 版本或结构长度对不上就当没有存档，调用方保留默认值。
 */

#include "driver/nvs/nvs_flash_save.h"

#include <stdio.h>
#include <string.h>

#include "esp_log.h"
#include "nvs.h"

#define TAG "nvs_light"
#define NVS_NAMESPACE "light"
#define STORE_VERSION 1

typedef struct __attribute__((packed)) {
    uint8_t version;
    uint8_t brightness;
    uint16_t refresh_ms;
} io_blob_t;

typedef struct __attribute__((packed)) {
    uint8_t version;
    uint16_t effect_size;
    segment_effect_t effect;
} seg_blob_t;

static esp_err_t open_ns(nvs_open_mode_t mode, nvs_handle_t *handle)
{
    return nvs_open(NVS_NAMESPACE, mode, handle);
}

static void make_key(char *key, size_t key_len, char prefix, uint8_t id)
{
    snprintf(key, key_len, "%c%u", prefix, id);
}

esp_err_t nvs_light_open(nvs_handle_t *out)
{
    if (out == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    return open_ns(NVS_READWRITE, out);
}

/* 只往已打开的 handle 里塞，不 commit。批量写时由调用方最后统一 commit 一次。 */
esp_err_t nvs_light_set_io(nvs_handle_t handle, uint8_t io_id, uint8_t brightness, uint16_t refresh_ms)
{
    if (handle == 0) {
        return ESP_ERR_INVALID_ARG;
    }
    io_blob_t blob = {
        .version = STORE_VERSION,
        .brightness = brightness,
        .refresh_ms = refresh_ms,
    };
    char key[8];
    make_key(key, sizeof(key), 'i', io_id);
    return nvs_set_blob(handle, key, &blob, sizeof(blob));
}

esp_err_t nvs_light_save_io(uint8_t io_id, uint8_t brightness, uint16_t refresh_ms)
{
    nvs_handle_t handle;
    esp_err_t err = open_ns(NVS_READWRITE, &handle);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "打开 NVS 失败: %s", esp_err_to_name(err));
        return err;
    }

    err = nvs_light_set_io(handle, io_id, brightness, refresh_ms);
    if (err == ESP_OK) {
        err = nvs_commit(handle);
    }
    nvs_close(handle);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "保存 IO %u 失败: %s", io_id, esp_err_to_name(err));
    }
    return err;
}

esp_err_t nvs_light_load_io(uint8_t io_id, uint8_t *brightness, uint16_t *refresh_ms)
{
    if (brightness == NULL || refresh_ms == NULL) {
        return ESP_ERR_INVALID_ARG;
    }

    nvs_handle_t handle;
    esp_err_t err = open_ns(NVS_READONLY, &handle);
    if (err == ESP_ERR_NVS_NOT_FOUND) {
        return err;
    }
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "打开 NVS 失败: %s", esp_err_to_name(err));
        return err;
    }

    io_blob_t blob;
    size_t size = sizeof(blob);
    char key[8];
    make_key(key, sizeof(key), 'i', io_id);
    err = nvs_get_blob(handle, key, &blob, &size);
    nvs_close(handle);
    if (err != ESP_OK) {
        return err;
    }
    if (size != sizeof(blob) || blob.version != STORE_VERSION) {
        return ESP_ERR_INVALID_VERSION;
    }

    *brightness = blob.brightness;
    *refresh_ms = blob.refresh_ms;
    return ESP_OK;
}

esp_err_t nvs_light_set_seg(nvs_handle_t handle, uint8_t seg_id, const segment_effect_t *effect)
{
    if (handle == 0 || effect == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    seg_blob_t blob = {
        .version = STORE_VERSION,
        .effect_size = sizeof(segment_effect_t),
        .effect = *effect,
    };
    char key[8];
    make_key(key, sizeof(key), 's', seg_id);
    return nvs_set_blob(handle, key, &blob, sizeof(blob));
}

esp_err_t nvs_light_save_seg(uint8_t seg_id, const segment_effect_t *effect)
{
    if (effect == NULL) {
        return ESP_ERR_INVALID_ARG;
    }

    nvs_handle_t handle;
    esp_err_t err = open_ns(NVS_READWRITE, &handle);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "打开 NVS 失败: %s", esp_err_to_name(err));
        return err;
    }

    err = nvs_light_set_seg(handle, seg_id, effect);
    if (err == ESP_OK) {
        err = nvs_commit(handle);
    }
    nvs_close(handle);
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "保存灯段 %u 失败: %s", seg_id, esp_err_to_name(err));
    }
    return err;
}

esp_err_t nvs_light_load_seg(uint8_t seg_id, segment_effect_t *effect)
{
    if (effect == NULL) {
        return ESP_ERR_INVALID_ARG;
    }

    nvs_handle_t handle;
    esp_err_t err = open_ns(NVS_READONLY, &handle);
    if (err == ESP_ERR_NVS_NOT_FOUND) {
        return err;
    }
    if (err != ESP_OK) {
        ESP_LOGE(TAG, "打开 NVS 失败: %s", esp_err_to_name(err));
        return err;
    }

    seg_blob_t blob;
    size_t size = sizeof(blob);
    char key[8];
    make_key(key, sizeof(key), 's', seg_id);
    err = nvs_get_blob(handle, key, &blob, &size);
    nvs_close(handle);
    if (err != ESP_OK) {
        return err;
    }
    if (size != sizeof(blob) || blob.version != STORE_VERSION ||
        blob.effect_size != sizeof(segment_effect_t)) {
        return ESP_ERR_INVALID_VERSION;
    }

    *effect = blob.effect;
    return ESP_OK;
}
