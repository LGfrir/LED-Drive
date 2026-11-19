/**
 * @file light_preset.c
 * @author LG_frir
 * @date 2026-11-12
 *
 * NVS 命名空间 "preset"，键 "p01".."p32"。
 * ROM 预设不进 NVS，开机按 id 生成。
 * 所有对外接口自己加锁，调度器和 BLE 任务可以并发调用。
 */

#include "driver/preset/light_preset.h"

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "esp_log.h"
#include "nvs.h"

#define TAG "preset"
#define NVS_NAMESPACE "preset"
#define PRESET_BLOB_VERSION 1

typedef struct {
    uint8_t version;
    uint8_t id;
    uint8_t flags;
    char name[LIGHT_PRESET_NAME_MAX];
    segment_effect_t effect;
} preset_blob_t;

static light_preset_t s_user[LIGHT_PRESET_USER_MAX];
static bool s_user_used[LIGHT_PRESET_USER_MAX];
static uint32_t s_version;
static SemaphoreHandle_t s_lock;
static bool s_inited;

static const char *const s_rom_names[] = {
    "关灯", "随波逐流", "常亮", "呼吸", "追逐", "多点", "爆闪",
};

/* 一份所有字段都合法的模板，再按 ROM 类型改 */
static segment_effect_t effect_template(void)
{
    segment_effect_t e;
    memset(&e, 0, sizeof(e));
    e.mode = LIGHT_MODE_WAVE;
    e.brightness = 255;
    e.wave.speed_pct = 50;
    e.wave.dir = LIGHT_DIR_RIGHT;
    e.solid.color = (color_t){ 255, 255, 255 };
    e.breath.color = (color_t){ 255, 255, 255 };
    e.breath.period_ms = 2000;
    e.chase.count = 3;
    e.chase.speed_pct = 50;
    e.chase.dir = LIGHT_DIR_RIGHT;
    e.chase.colors[0] = (color_t){ 255, 0, 0 };
    e.chase.colors[1] = (color_t){ 0, 255, 0 };
    e.chase.colors[2] = (color_t){ 0, 0, 255 };
    e.points.count = 1;
    e.points.wrap = false;
    e.points.points[0].position_pct = 0;
    e.points.points[0].count = 1;
    e.points.points[0].period_ms = 2000;
    e.points.points[0].colors[0] = (color_t){ 255, 255, 255 };
    e.flash.mode = FLASH_MODE_BLINK;
    e.flash.period_ms = 400;
    return e;
}

static void rom_effect(uint8_t id, segment_effect_t *out)
{
    segment_effect_t e = effect_template();
    switch (id) {
    case LIGHT_PRESET_ID_ROM_OFF:
        e.mode = LIGHT_MODE_OFF;
        break;
    case LIGHT_PRESET_ID_ROM_WAVE:
        e.mode = LIGHT_MODE_WAVE;
        break;
    case LIGHT_PRESET_ID_ROM_STATIC:
        e.mode = LIGHT_MODE_STATIC;
        break;
    case LIGHT_PRESET_ID_ROM_BREATH:
        e.mode = LIGHT_MODE_BREATH;
        break;
    case LIGHT_PRESET_ID_ROM_CHASE:
        e.mode = LIGHT_MODE_CHASE;
        break;
    case LIGHT_PRESET_ID_ROM_POINTS:
        e.mode = LIGHT_MODE_POINTS;
        break;
    case LIGHT_PRESET_ID_ROM_FLASH:
        e.mode = LIGHT_MODE_FLASH;
        break;
    default:
        e.mode = LIGHT_MODE_OFF;
        break;
    }
    *out = e;
}

static bool name_taken(const char *name, uint8_t except_id)
{
    for (uint8_t i = 0; i < LIGHT_PRESET_USER_MAX; i++) {
        if (s_user_used[i] && s_user[i].id != except_id &&
            strncmp(s_user[i].name, name, LIGHT_PRESET_NAME_MAX) == 0) {
            return true;
        }
    }
    for (uint8_t i = 0; i < 7; i++) {
        if (strncmp(s_rom_names[i], name, LIGHT_PRESET_NAME_MAX) == 0) {
            return true;
        }
    }
    return false;
}

static void copy_name(char *out, size_t cap, const char *src)
{
    if (cap == 0) {
        return;
    }
    size_t n = strlen(src);
    if (n >= cap) {
        n = cap - 1;
    }
    memcpy(out, src, n);
    out[n] = '\0';
}

/*
 * 重名自动顺延：优先把结尾数字加一，没有数字就追加 " 2"、" 3"。
 * 例："预设 7" -> "预设 8"；"白光" -> "白光 2"。
 */
static void make_unique_name(const char *src, uint8_t except_id, char *out)
{
    if (src == NULL || src[0] == '\0') {
        snprintf(out, LIGHT_PRESET_NAME_MAX, "预设");
        return;
    }
    snprintf(out, LIGHT_PRESET_NAME_MAX, "%s", src);
    if (!name_taken(out, except_id)) {
        return;
    }

    size_t len = strlen(out);
    size_t pos = len;
    while (pos > 0 && isdigit((unsigned char)out[pos - 1])) {
        pos--;
    }
    if (pos < len && pos > 0) {
        char prefix[LIGHT_PRESET_NAME_MAX];
        size_t plen = pos;
        if (plen >= sizeof(prefix)) {
            plen = sizeof(prefix) - 1;
        }
        memcpy(prefix, out, plen);
        prefix[plen] = '\0';
        unsigned base = (unsigned)strtoul(out + pos, NULL, 10);
        for (unsigned n = base + 1; n < base + 1000; n++) {
            char tmp[LIGHT_PRESET_NAME_MAX + 16];
            snprintf(tmp, sizeof(tmp), "%s%u", prefix, n);
            copy_name(out, LIGHT_PRESET_NAME_MAX, tmp);
            if (!name_taken(out, except_id)) {
                return;
            }
        }
    }
    for (unsigned n = 2; n < 1000; n++) {
        char tmp[LIGHT_PRESET_NAME_MAX + 16];
        snprintf(tmp, sizeof(tmp), "%s %u", src, n);
        copy_name(out, LIGHT_PRESET_NAME_MAX, tmp);
        if (!name_taken(out, except_id)) {
            return;
        }
    }
}

static bool effect_equal(const segment_effect_t *a, const segment_effect_t *b)
{
    if (a->mode != b->mode || a->brightness != b->brightness) {
        return false;
    }
    switch (a->mode) {
    case LIGHT_MODE_WAVE:
        return a->wave.speed_pct == b->wave.speed_pct && a->wave.dir == b->wave.dir;
    case LIGHT_MODE_STATIC:
        return memcmp(&a->solid.color, &b->solid.color, sizeof(color_t)) == 0;
    case LIGHT_MODE_BREATH:
        return memcmp(&a->breath.color, &b->breath.color, sizeof(color_t)) == 0 &&
               a->breath.period_ms == b->breath.period_ms;
    case LIGHT_MODE_CHASE:
        return a->chase.count == b->chase.count &&
               a->chase.speed_pct == b->chase.speed_pct &&
               a->chase.dir == b->chase.dir &&
               memcmp(a->chase.colors, b->chase.colors, sizeof(color_t) * a->chase.count) == 0;
    case LIGHT_MODE_POINTS: {
        if (a->points.count != b->points.count || a->points.wrap != b->points.wrap) {
            return false;
        }
        for (uint8_t i = 0; i < a->points.count; i++) {
            const point_cfg_t *pa = &a->points.points[i];
            const point_cfg_t *pb = &b->points.points[i];
            if (pa->position_pct != pb->position_pct || pa->count != pb->count ||
                pa->period_ms != pb->period_ms ||
                memcmp(pa->colors, pb->colors, sizeof(color_t) * pa->count) != 0) {
                return false;
            }
        }
        return true;
    }
    case LIGHT_MODE_FLASH:
        return a->flash.mode == b->flash.mode && a->flash.period_ms == b->flash.period_ms;
    default:
        return true;   /* OFF */
    }
}

static bool effect_ok(const segment_effect_t *e)
{
    if (e->mode > LIGHT_MODE_FLASH) {
        return false;
    }
    if (e->wave.speed_pct > LIGHT_SPEED_MAX_PCT || e->wave.dir > LIGHT_DIR_LEFT) {
        return false;
    }
    if (e->chase.count < 1 || e->chase.count > LIGHT_COLOR_MAX ||
        e->chase.speed_pct > LIGHT_SPEED_MAX_PCT || e->chase.dir > LIGHT_DIR_LEFT) {
        return false;
    }
    if (e->points.count < 1 || e->points.count > LIGHT_POINT_MAX) {
        return false;
    }
    for (uint8_t i = 0; i < e->points.count; i++) {
        const point_cfg_t *p = &e->points.points[i];
        if (p->position_pct > 100 || p->count < 1 || p->count > LIGHT_COLOR_MAX) {
            return false;
        }
    }
    if (e->flash.mode > FLASH_MODE_SWITCH) {
        return false;
    }
    return true;
}

static int slot_of(uint8_t id)
{
    return (int)id - 1;
}

static esp_err_t nvs_save(uint8_t id)
{
    int slot = slot_of(id);
    if (slot < 0 || slot >= LIGHT_PRESET_USER_MAX || !s_user_used[slot]) {
        return ESP_ERR_INVALID_ARG;
    }
    nvs_handle_t h;
    esp_err_t err = nvs_open(NVS_NAMESPACE, NVS_READWRITE, &h);
    if (err != ESP_OK) {
        return err;
    }
    preset_blob_t blob;
    memset(&blob, 0, sizeof(blob));
    blob.version = PRESET_BLOB_VERSION;
    blob.id = id;
    blob.flags = s_user[slot].flags;
    memcpy(blob.name, s_user[slot].name, sizeof(blob.name));
    blob.effect = s_user[slot].effect;

    char key[8];
    snprintf(key, sizeof(key), "p%02u", id);
    err = nvs_set_blob(h, key, &blob, sizeof(blob));
    if (err == ESP_OK) {
        err = nvs_commit(h);
    }
    nvs_close(h);
    return err;
}

static void nvs_erase(uint8_t id)
{
    nvs_handle_t h;
    if (nvs_open(NVS_NAMESPACE, NVS_READWRITE, &h) != ESP_OK) {
        return;
    }
    char key[8];
    snprintf(key, sizeof(key), "p%02u", id);
    nvs_erase_key(h, key);
    nvs_commit(h);
    nvs_close(h);
}

static void lock(void)
{
    if (s_lock != NULL) {
        xSemaphoreTake(s_lock, portMAX_DELAY);
    }
}

static void unlock(void)
{
    if (s_lock != NULL) {
        xSemaphoreGive(s_lock);
    }
}

esp_err_t light_preset_init(void)
{
    if (s_inited) {
        return ESP_OK;
    }
    s_lock = xSemaphoreCreateMutex();
    if (s_lock == NULL) {
        return ESP_ERR_NO_MEM;
    }
    memset(s_user, 0, sizeof(s_user));
    memset(s_user_used, 0, sizeof(s_user_used));
    s_version = 1;

    nvs_handle_t h;
    if (nvs_open(NVS_NAMESPACE, NVS_READONLY, &h) == ESP_OK) {
        for (uint8_t id = 1; id <= LIGHT_PRESET_USER_MAX; id++) {
            char key[8];
            snprintf(key, sizeof(key), "p%02u", id);
            preset_blob_t blob;
            size_t size = sizeof(blob);
            if (nvs_get_blob(h, key, &blob, &size) != ESP_OK ||
                size != sizeof(blob) || blob.version != PRESET_BLOB_VERSION ||
                blob.id != id || !effect_ok(&blob.effect)) {
                continue;
            }
            int slot = slot_of(id);
            s_user[slot].id = id;
            s_user[slot].flags = blob.flags & ~LIGHT_PRESET_FLAG_ROM;
            memcpy(s_user[slot].name, blob.name, sizeof(s_user[slot].name));
            s_user[slot].name[LIGHT_PRESET_NAME_MAX - 1] = '\0';
            s_user[slot].effect = blob.effect;
            s_user_used[slot] = true;
        }
        nvs_close(h);
    }

    s_inited = true;
    ESP_LOGI(TAG, "预设表就绪，用户 %u 条", light_preset_count() - 7);
    return ESP_OK;
}

uint32_t light_preset_version(void)
{
    return s_version;
}

uint8_t light_preset_count(void)
{
    uint8_t n = 7;   /* ROM */
    lock();
    for (uint8_t i = 0; i < LIGHT_PRESET_USER_MAX; i++) {
        if (s_user_used[i]) {
            n++;
        }
    }
    unlock();
    return n;
}

const light_preset_t *light_preset_get(uint8_t id)
{
    static light_preset_t s_tmp;
    if (id >= LIGHT_PRESET_ID_ROM_FIRST && id <= LIGHT_PRESET_ID_ROM_LAST) {
        s_tmp.id = id;
        s_tmp.flags = LIGHT_PRESET_FLAG_ROM;
        snprintf(s_tmp.name, sizeof(s_tmp.name), "%s", s_rom_names[id - LIGHT_PRESET_ID_ROM_FIRST]);
        rom_effect(id, &s_tmp.effect);
        return &s_tmp;
    }
    int slot = slot_of(id);
    if (slot < 0 || slot >= LIGHT_PRESET_USER_MAX || !s_user_used[slot]) {
        return NULL;
    }
    return &s_user[slot];
}

esp_err_t light_preset_copy(uint8_t id, light_preset_t *out)
{
    if (out == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    lock();
    const light_preset_t *p = light_preset_get(id);
    if (p == NULL) {
        unlock();
        return ESP_ERR_NOT_FOUND;
    }
    *out = *p;
    unlock();
    return ESP_OK;
}

esp_err_t light_preset_create(const char *name, const segment_effect_t *effect, uint8_t *out_id)
{
    if (effect == NULL || !effect_ok(effect)) {
        return ESP_ERR_INVALID_ARG;
    }
    lock();
    int slot = -1;
    for (int i = 0; i < LIGHT_PRESET_USER_MAX; i++) {
        if (!s_user_used[i]) {
            slot = i;
            break;
        }
    }
    if (slot < 0) {
        unlock();
        return ESP_ERR_NO_MEM;
    }

    uint8_t id = (uint8_t)(slot + 1);
    char final_name[LIGHT_PRESET_NAME_MAX];
    char seed[LIGHT_PRESET_NAME_MAX];
    if (name == NULL || name[0] == '\0') {
        snprintf(seed, sizeof(seed), "预设 %u", id);
    } else {
        snprintf(seed, sizeof(seed), "%s", name);
    }
    make_unique_name(seed, 0, final_name);
    final_name[LIGHT_PRESET_NAME_MAX - 1] = '\0';

    memset(&s_user[slot], 0, sizeof(s_user[slot]));
    s_user[slot].id = id;
    s_user[slot].flags = 0;
    memcpy(s_user[slot].name, final_name, sizeof(s_user[slot].name));
    s_user[slot].effect = *effect;
    s_user_used[slot] = true;
    s_version++;
    nvs_save(id);
    unlock();

    if (out_id != NULL) {
        *out_id = id;
    }
    return ESP_OK;
}

esp_err_t light_preset_update(uint8_t id, const char *name, const segment_effect_t *effect)
{
    if (effect == NULL || !effect_ok(effect)) {
        return ESP_ERR_INVALID_ARG;
    }
    if (id >= LIGHT_PRESET_ID_ROM_FIRST && id <= LIGHT_PRESET_ID_ROM_LAST) {
        return ESP_ERR_NOT_ALLOWED;
    }
    int slot = slot_of(id);
    if (slot < 0 || slot >= LIGHT_PRESET_USER_MAX || !s_user_used[slot]) {
        return ESP_ERR_NOT_FOUND;
    }
    lock();
    char final_name[LIGHT_PRESET_NAME_MAX];
    make_unique_name(name != NULL ? name : s_user[slot].name, id, final_name);
    memcpy(s_user[slot].name, final_name, sizeof(s_user[slot].name));
    s_user[slot].effect = *effect;
    s_version++;
    nvs_save(id);
    unlock();
    return ESP_OK;
}

esp_err_t light_preset_delete(uint8_t id)
{
    if (id >= LIGHT_PRESET_ID_ROM_FIRST && id <= LIGHT_PRESET_ID_ROM_LAST) {
        return ESP_ERR_NOT_ALLOWED;
    }
    int slot = slot_of(id);
    if (slot < 0 || slot >= LIGHT_PRESET_USER_MAX || !s_user_used[slot]) {
        return ESP_ERR_NOT_FOUND;
    }
    lock();
    memset(&s_user[slot], 0, sizeof(s_user[slot]));
    s_user_used[slot] = false;
    s_version++;
    nvs_erase(id);
    unlock();
    return ESP_OK;
}

uint8_t light_preset_find_equal(const segment_effect_t *effect)
{
    if (effect == NULL) {
        return LIGHT_PRESET_ID_NONE;
    }
    for (uint8_t i = 0; i < LIGHT_PRESET_USER_MAX; i++) {
        if (s_user_used[i] && effect_equal(&s_user[i].effect, effect)) {
            return s_user[i].id;
        }
    }
    for (uint8_t id = LIGHT_PRESET_ID_ROM_FIRST; id <= LIGHT_PRESET_ID_ROM_LAST; id++) {
        segment_effect_t e;
        rom_effect(id, &e);
        if (effect_equal(&e, effect)) {
            return id;
        }
    }
    return LIGHT_PRESET_ID_NONE;
}

uint8_t light_preset_list(uint8_t *ids, uint8_t cap)
{
    if (ids == NULL) {
        return 0;
    }
    uint8_t n = 0;
    for (uint8_t i = 0; i < LIGHT_PRESET_USER_MAX && n < cap; i++) {
        if (s_user_used[i]) {
            ids[n++] = s_user[i].id;
        }
    }
    for (uint8_t id = LIGHT_PRESET_ID_ROM_FIRST; id <= LIGHT_PRESET_ID_ROM_LAST && n < cap; id++) {
        ids[n++] = id;
    }
    return n;
}

/* ---------------- 线上格式 ---------------- */

#define WIRE_SIZE 270

uint16_t light_preset_encode(const segment_effect_t *e, uint8_t *buf, uint16_t cap)
{
    if (e == NULL || buf == NULL || cap < WIRE_SIZE) {
        return 0;
    }
    uint16_t p = 0;
    buf[p++] = (uint8_t)e->mode;
    buf[p++] = e->brightness;

    memcpy(&buf[p], &e->wave.speed_pct, 2); p += 2;
    buf[p++] = (uint8_t)e->wave.dir;

    buf[p++] = e->solid.color.r;
    buf[p++] = e->solid.color.g;
    buf[p++] = e->solid.color.b;

    buf[p++] = e->breath.color.r;
    buf[p++] = e->breath.color.g;
    buf[p++] = e->breath.color.b;
    memcpy(&buf[p], &e->breath.period_ms, 2); p += 2;

    buf[p++] = e->chase.count;
    memcpy(&buf[p], &e->chase.speed_pct, 2); p += 2;
    buf[p++] = (uint8_t)e->chase.dir;
    memcpy(&buf[p], e->chase.colors, sizeof(color_t) * LIGHT_COLOR_MAX);
    p += sizeof(color_t) * LIGHT_COLOR_MAX;

    buf[p++] = e->points.wrap ? 1 : 0;
    buf[p++] = e->points.count;
    for (uint8_t i = 0; i < LIGHT_POINT_MAX; i++) {
        const point_cfg_t *pt = &e->points.points[i];
        buf[p++] = pt->position_pct;
        memcpy(&buf[p], &pt->period_ms, 2); p += 2;
        buf[p++] = pt->count;
        memcpy(&buf[p], pt->colors, sizeof(color_t) * LIGHT_COLOR_MAX);
        p += sizeof(color_t) * LIGHT_COLOR_MAX;
    }

    buf[p++] = (uint8_t)e->flash.mode;
    memcpy(&buf[p], &e->flash.period_ms, 2); p += 2;

    return p;
}

esp_err_t light_preset_decode(segment_effect_t *e, const uint8_t *buf, uint16_t len, uint16_t *used)
{
    if (e == NULL || buf == NULL || len < WIRE_SIZE) {
        return ESP_ERR_INVALID_SIZE;
    }
    segment_effect_t out = effect_template();
    uint16_t p = 0;

    memset(&out, 0, sizeof(out));
    out.mode = (light_mode_t)buf[p++];
    out.brightness = buf[p++];

    memcpy(&out.wave.speed_pct, &buf[p], 2); p += 2;
    out.wave.dir = (light_dir_t)buf[p++];

    out.solid.color.r = buf[p++];
    out.solid.color.g = buf[p++];
    out.solid.color.b = buf[p++];

    out.breath.color.r = buf[p++];
    out.breath.color.g = buf[p++];
    out.breath.color.b = buf[p++];
    memcpy(&out.breath.period_ms, &buf[p], 2); p += 2;

    out.chase.count = buf[p++];
    memcpy(&out.chase.speed_pct, &buf[p], 2); p += 2;
    out.chase.dir = (light_dir_t)buf[p++];
    memcpy(out.chase.colors, &buf[p], sizeof(color_t) * LIGHT_COLOR_MAX);
    p += sizeof(color_t) * LIGHT_COLOR_MAX;

    out.points.wrap = buf[p++] != 0;
    out.points.count = buf[p++];
    for (uint8_t i = 0; i < LIGHT_POINT_MAX; i++) {
        point_cfg_t *pt = &out.points.points[i];
        pt->position_pct = buf[p++];
        memcpy(&pt->period_ms, &buf[p], 2); p += 2;
        pt->count = buf[p++];
        memcpy(pt->colors, &buf[p], sizeof(color_t) * LIGHT_COLOR_MAX);
        p += sizeof(color_t) * LIGHT_COLOR_MAX;
    }

    out.flash.mode = (flash_mode_t)buf[p++];
    memcpy(&out.flash.period_ms, &buf[p], 2); p += 2;

    if (!effect_ok(&out)) {
        return ESP_ERR_INVALID_ARG;
    }
    *e = out;
    if (used != NULL) {
        *used = p;
    }
    return ESP_OK;
}
