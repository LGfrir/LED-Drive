/**
 * @file light_preset.h
 * @author LG_frir
 * @date 2026-11-12
 * @brief 灯效预设表。ROM 里 7 条只读，用户区 32 条存 NVS。
 *
 * 灯段进入时间模式后不再各自存整份 effect，只记预设 id。
 */

#ifndef __LIGHT_PRESET_H__
#define __LIGHT_PRESET_H__

#include <stdbool.h>
#include <stdint.h>

#include "driver/led_strip/light_mode.h"
#include "esp_err.h"

#define LIGHT_PRESET_USER_MAX   32
#define LIGHT_PRESET_NAME_MAX   64
#define LIGHT_PRESET_ID_NONE    0xFF

/* ROM 预设 id，和 light_mode_t 一一对应，只读 */
#define LIGHT_PRESET_ID_ROM_OFF     0x80
#define LIGHT_PRESET_ID_ROM_WAVE    0x81
#define LIGHT_PRESET_ID_ROM_STATIC  0x82
#define LIGHT_PRESET_ID_ROM_BREATH  0x83
#define LIGHT_PRESET_ID_ROM_CHASE   0x84
#define LIGHT_PRESET_ID_ROM_POINTS  0x85
#define LIGHT_PRESET_ID_ROM_FLASH   0x86
#define LIGHT_PRESET_ID_ROM_FIRST   LIGHT_PRESET_ID_ROM_OFF
#define LIGHT_PRESET_ID_ROM_LAST    LIGHT_PRESET_ID_ROM_FLASH

typedef struct {
    uint8_t id;
    uint8_t flags;          /* bit0 = ROM，只读 */
    char name[LIGHT_PRESET_NAME_MAX];
    segment_effect_t effect;
} light_preset_t;

#define LIGHT_PRESET_FLAG_ROM  0x01

esp_err_t light_preset_init(void);

/* 版本号，任何增删改都会加一，调度器用它判断要不要重刷 */
uint32_t light_preset_version(void);

uint8_t light_preset_count(void);

/* 不存在的 id 返回 NULL。只读，调用方不要长期持有 */
const light_preset_t *light_preset_get(uint8_t id);

/* 拷贝一份，线程安全，调度器用这个 */
esp_err_t light_preset_copy(uint8_t id, light_preset_t *out);

/*
 * 新建。name 为 NULL 或空串时用默认名 "预设 N"；
 * 重名会自动改成下一个空闲名字。out_id 返回分配到的 id。
 */
esp_err_t light_preset_create(const char *name, const segment_effect_t *effect, uint8_t *out_id);

/* 改用户预设。ROM 预设返回 ESP_ERR_NOT_ALLOWED */
esp_err_t light_preset_update(uint8_t id, const char *name, const segment_effect_t *effect);

/* 删用户预设。ROM 预设返回 ESP_ERR_NOT_ALLOWED */
esp_err_t light_preset_delete(uint8_t id);

/* 找 effect 完全一样（模式、亮度、该模式的参数）的预设，返回 id 或 0xFF */
uint8_t light_preset_find_equal(const segment_effect_t *effect);

/* 填 id 列表，返回写入条数 */
uint8_t light_preset_list(uint8_t *ids, uint8_t cap);

/* 线上格式编解码，供 BLE 分包用。返回字节数，装不下返回 0 */
uint16_t light_preset_encode(const segment_effect_t *effect, uint8_t *buf, uint16_t cap);
esp_err_t light_preset_decode(segment_effect_t *effect, const uint8_t *buf, uint16_t len, uint16_t *used);

#endif /* __LIGHT_PRESET_H__ */
