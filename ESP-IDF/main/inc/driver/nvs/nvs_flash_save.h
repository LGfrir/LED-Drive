/**
 * @file nvs_flash_save.h
 * @author LG_frir
 * @date 2026-11-11 17:49:59
 * @brief 灯带配置掉电保存。没有存档时调用方继续用默认值。
 */

#ifndef __NVS_FLASH_SAVE_H__
#define __NVS_FLASH_SAVE_H__

#include "driver/led_strip/light_mode.h"

#include "esp_err.h"
#include "nvs.h"

/*
 * 批量写接口。
 * NVS 是追加日志：每调用一次 nvs_commit 就要写一个页，
 * 页写满后还要 GC + 擦除（一次 4 KB / 20~40 ms，期间 code cache 关闭）。
 * 所以"改一项就 commit 一次"是最坏的用法。
 * 正确做法：nvs_light_open 打开一次 → 逐项 nvs_light_set_* → 最后 commit 一次。
 */
esp_err_t nvs_light_open(nvs_handle_t *out);
esp_err_t nvs_light_set_io(nvs_handle_t handle, uint8_t io_id, uint8_t brightness, uint16_t refresh_ms);
esp_err_t nvs_light_set_seg(nvs_handle_t handle, uint8_t seg_id, const segment_effect_t *effect);

esp_err_t nvs_light_save_io(uint8_t io_id, uint8_t brightness, uint16_t refresh_ms);
esp_err_t nvs_light_load_io(uint8_t io_id, uint8_t *brightness, uint16_t *refresh_ms);

esp_err_t nvs_light_save_seg(uint8_t seg_id, const segment_effect_t *effect);
esp_err_t nvs_light_load_seg(uint8_t seg_id, segment_effect_t *effect);

#endif /* __NVS_FLASH_SAVE_H__ */
