/**
 * @file clock_scheduler.c
 * @author LG_frir
 * @date 2026-11-12
 *
 * NVS 命名空间 "sched"：
 *   io1..io4  u8  每个 IO 的模式
 *   s01..s08  blob 调度组
 *   b001..    blob 灯段绑定
 */

#include "task/clock_scheduler.h"

#include <stdio.h>
#include <string.h>

#include "driver/led_strip/light_mode_driver.h"
#include "driver/timer/local_clock.h"

#include "freertos/FreeRTOS.h"
#include "freertos/semphr.h"
#include "freertos/task.h"
#include "esp_log.h"
#include "nvs.h"

#define TAG "clock"
#define NVS_NAMESPACE "sched"
#define BLOB_VERSION 1

/*
 * 调度节拍：灯效切换的响应上限。
 * 只做内存里的槽位计算，没有变化时 run_once() 会自己早退，代价是 us 级。
 * 100 ms 同时让"到点换灯"的精度从 1 s 提到 100 ms。
 */
#define CLOCK_TICK_MS 100

/*
 * 模式改动落盘前的静默期（独立于上面的节拍）。
 * nvs_commit 会关 code cache，期间 RMT 补数中断和 BLE 一起停摆，
 * 落一次就是一次可见的卡顿。攒够 5 s 没有新改动再写。
 */
#define NVS_SAVE_QUIET_MS 5000

typedef struct {
    uint8_t version;
    uint8_t id;
    char name[LIGHT_PRESET_NAME_MAX];
    uint8_t slot_count;
    uint32_t at_sec[CLOCK_SLOT_MAX];
} sched_blob_t;

typedef struct {
    uint8_t version;
    uint8_t seg_id;
    uint8_t sched_id;
    uint8_t slot_count;
    uint8_t map[CLOCK_SLOT_MAX];
} bind_blob_t;

typedef struct {
    uint8_t seg;
    uint8_t sched;
    uint8_t slot_count;
    uint8_t map[CLOCK_SLOT_MAX];
    uint8_t applied;
    uint32_t applied_ver;
    bool editing;
    bool used;
} seg_state_t;

static clock_sched_t s_sched[CLOCK_SCHED_MAX];
static bool s_sched_used[CLOCK_SCHED_MAX];
static seg_state_t s_seg[CLOCK_SEG_MAX];
static uint8_t s_io_mode[CLOCK_IO_MAX + 1];
static SemaphoreHandle_t s_lock;
static TaskHandle_t s_clock_task;
static uint8_t s_restore_mask;
static uint8_t s_mode_save_mask;
static uint32_t s_mode_gen; /* 每次改 IO 模式自增，落盘任务用它判断"有没有新改动" */
static bool s_inited;
static bool s_time_valid;

#define APPLIED_NONE 0xFE

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

/* ---------------- NVS ---------------- */

static void nvs_save_sched(uint8_t id)
{
    int slot = (int)id - 1;
    if (slot < 0 || slot >= CLOCK_SCHED_MAX || !s_sched_used[slot]) {
        return;
    }
    nvs_handle_t h;
    if (nvs_open(NVS_NAMESPACE, NVS_READWRITE, &h) != ESP_OK) {
        return;
    }
    sched_blob_t blob;
    memset(&blob, 0, sizeof(blob));
    blob.version = BLOB_VERSION;
    blob.id = id;
    memcpy(blob.name, s_sched[slot].name, sizeof(blob.name));
    blob.slot_count = s_sched[slot].slot_count;
    memcpy(blob.at_sec, s_sched[slot].at_sec, sizeof(blob.at_sec));

    char key[8];
    snprintf(key, sizeof(key), "s%02u", id);
    if (nvs_set_blob(h, key, &blob, sizeof(blob)) == ESP_OK) {
        nvs_commit(h);
    }
    nvs_close(h);
}

static void nvs_save_bind(const seg_state_t *b)
{
    if (b == NULL) {
        return;
    }
    nvs_handle_t h;
    if (nvs_open(NVS_NAMESPACE, NVS_READWRITE, &h) != ESP_OK) {
        return;
    }
    bind_blob_t blob;
    memset(&blob, 0, sizeof(blob));
    blob.version = BLOB_VERSION;
    blob.seg_id = b->seg;
    blob.sched_id = b->sched;
    blob.slot_count = b->slot_count;
    memcpy(blob.map, b->map, sizeof(blob.map));

    char key[8];
    snprintf(key, sizeof(key), "b%03u", b->seg);
    if (nvs_set_blob(h, key, &blob, sizeof(blob)) == ESP_OK) {
        nvs_commit(h);
    }
    nvs_close(h);
}

static void nvs_erase_bind(uint8_t seg)
{
    nvs_handle_t h;
    if (nvs_open(NVS_NAMESPACE, NVS_READWRITE, &h) != ESP_OK) {
        return;
    }
    char key[8];
    snprintf(key, sizeof(key), "b%03u", seg);
    nvs_erase_key(h, key);
    nvs_commit(h);
    nvs_close(h);
}

static void nvs_save_io_mode(uint8_t io_id, uint8_t mode)
{
    nvs_handle_t h;
    if (nvs_open(NVS_NAMESPACE, NVS_READWRITE, &h) != ESP_OK) {
        return;
    }
    char key[8];
    snprintf(key, sizeof(key), "io%u", io_id);
    if (nvs_set_u8(h, key, mode) == ESP_OK) {
        nvs_commit(h);
    }
    nvs_close(h);
}

static void load_all(void)
{
    memset(s_sched, 0, sizeof(s_sched));
    memset(s_sched_used, 0, sizeof(s_sched_used));
    memset(s_seg, 0, sizeof(s_seg));
    for (int i = 0; i <= CLOCK_IO_MAX; i++) {
        s_io_mode[i] = CLOCK_MODE_MANUAL;
    }

    nvs_handle_t h;
    if (nvs_open(NVS_NAMESPACE, NVS_READONLY, &h) != ESP_OK) {
        return;
    }

    for (uint8_t io = 1; io <= CLOCK_IO_MAX; io++) {
        char key[8];
        snprintf(key, sizeof(key), "io%u", io);
        uint8_t mode = CLOCK_MODE_MANUAL;
        if (nvs_get_u8(h, key, &mode) == ESP_OK && mode <= CLOCK_MODE_TIME) {
            s_io_mode[io] = mode;
        }
    }

    for (uint8_t id = 1; id <= CLOCK_SCHED_MAX; id++) {
        char key[8];
        snprintf(key, sizeof(key), "s%02u", id);
        sched_blob_t blob;
        size_t size = sizeof(blob);
        if (nvs_get_blob(h, key, &blob, &size) != ESP_OK || size != sizeof(blob) ||
            blob.version != BLOB_VERSION || blob.id != id || blob.slot_count > CLOCK_SLOT_MAX) {
            continue;
        }
        int slot = id - 1;
        s_sched[slot].id = id;
        memcpy(s_sched[slot].name, blob.name, sizeof(s_sched[slot].name));
        s_sched[slot].name[LIGHT_PRESET_NAME_MAX - 1] = '\0';
        s_sched[slot].slot_count = blob.slot_count;
        memcpy(s_sched[slot].at_sec, blob.at_sec, sizeof(s_sched[slot].at_sec));
        s_sched_used[slot] = true;
    }

    for (uint16_t seg = 1; seg <= 255; seg++) {
        char key[8];
        snprintf(key, sizeof(key), "b%03u", seg);
        bind_blob_t blob;
        size_t size = sizeof(blob);
        if (nvs_get_blob(h, key, &blob, &size) != ESP_OK || size != sizeof(blob) ||
            blob.version != BLOB_VERSION || blob.seg_id != seg ||
            blob.slot_count > CLOCK_SLOT_MAX) {
            continue;
        }
        for (int i = 0; i < CLOCK_SEG_MAX; i++) {
            if (!s_seg[i].used) {
                s_seg[i].used = true;
                s_seg[i].seg = seg;
                s_seg[i].sched = blob.sched_id;
                s_seg[i].slot_count = blob.slot_count;
                memcpy(s_seg[i].map, blob.map, sizeof(s_seg[i].map));
                s_seg[i].applied = APPLIED_NONE;
                break;
            }
        }
    }
    nvs_close(h);
}

/* ---------------- 时间计算 ---------------- */

static uint32_t now_sec_of_day(void)
{
    struct tm tm;
    local_clock_local_tm(&tm);
    return (uint32_t)tm.tm_hour * 3600u + (uint32_t)tm.tm_min * 60u + (uint32_t)tm.tm_sec;
}

/* 返回当前应该用的槽位下标；没有槽位返回 -1 */
static int current_slot(const clock_sched_t *s, uint32_t sod)
{
    if (s == NULL || s->slot_count == 0) {
        return -1;
    }
    int idx = -1;
    for (uint8_t i = 0; i < s->slot_count; i++) {
        if (s->at_sec[i] <= sod) {
            idx = i;
        } else {
            break;
        }
    }
    if (idx < 0) {
        idx = s->slot_count - 1;   /* 还没到今天第一个点：沿用前一天最后一个 */
    }
    return idx;
}

static const clock_sched_t *sched_get(uint8_t id)
{
    int slot = (int)id - 1;
    if (slot < 0 || slot >= CLOCK_SCHED_MAX || !s_sched_used[slot]) {
        return NULL;
    }
    return &s_sched[slot];
}

static seg_state_t *seg_find(uint8_t seg_id)
{
    for (int i = 0; i < CLOCK_SEG_MAX; i++) {
        if (s_seg[i].used && s_seg[i].seg == seg_id) {
            return &s_seg[i];
        }
    }
    return NULL;
}

static seg_state_t *seg_get_or_create(uint8_t seg_id)
{
    seg_state_t *b = seg_find(seg_id);
    if (b != NULL) {
        return b;
    }
    for (int i = 0; i < CLOCK_SEG_MAX; i++) {
        if (!s_seg[i].used) {
            memset(&s_seg[i], 0, sizeof(s_seg[i]));
            s_seg[i].used = true;
            s_seg[i].seg = seg_id;
            s_seg[i].applied = APPLIED_NONE;
            return &s_seg[i];
        }
    }
    return NULL;
}

static void apply_preset(uint8_t seg_id, uint8_t preset_id)
{
    light_preset_t p;
    if (light_preset_copy(preset_id, &p) != ESP_OK) {
        if (light_preset_copy(LIGHT_PRESET_ID_ROM_OFF, &p) != ESP_OK) {
            return;
        }
    }
    light_seg_apply_effect(seg_id, &p.effect, false);
}

static void run_once_locked(void)
{
    bool valid = local_clock_valid();
    s_time_valid = valid;
    uint32_t sod = valid ? now_sec_of_day() : 0;
    uint32_t ver = light_preset_version();

    for (uint8_t io = 1; io <= CLOCK_IO_MAX; io++) {
        if (light_io_get(io, NULL, NULL, NULL) != ESP_OK) {
            continue;
        }
        if (s_io_mode[io] != CLOCK_MODE_TIME) {
            light_io_set_mute(io, false);
            continue;
        }
        if (!valid) {
            light_io_set_mute(io, true);
            continue;
        }
        light_io_set_mute(io, false);
    }

    for (int i = 0; i < CLOCK_SEG_MAX; i++) {
        seg_state_t *b = &s_seg[i];
        if (!b->used || b->editing) {
            continue;
        }
        uint8_t io = 0;
        if (light_seg_get_io(b->seg, &io) != ESP_OK) {
            continue;
        }
        if (s_io_mode[io] != CLOCK_MODE_TIME || !valid) {
            continue;
        }

        uint8_t want = LIGHT_PRESET_ID_NONE;
        const clock_sched_t *s = sched_get(b->sched);
        int slot = current_slot(s, sod);
        if (slot >= 0 && (uint8_t)slot < b->slot_count) {
            want = b->map[slot];
        }
        light_preset_t probe;
        if (want == LIGHT_PRESET_ID_NONE || light_preset_copy(want, &probe) != ESP_OK) {
            want = LIGHT_PRESET_ID_ROM_OFF;
        }
        if (b->applied == want && b->applied_ver == ver) {
            continue;
        }
        apply_preset(b->seg, want);
        b->applied = want;
        b->applied_ver = ver;
    }
}

static void run_once(void)
{
    lock();
    run_once_locked();
    unlock();
}

static void clock_kick(void)
{
    if (s_clock_task != NULL) {
        xTaskNotifyGive(s_clock_task);
    }
}

void clock_scheduler_kick(void)
{
    clock_kick();
}

static void clock_task(void *arg)
{
    (void)arg;
    TickType_t quiet_since = 0;
    uint32_t seen_gen = 0;

    while (1) {
        /*
         * 固定 100 ms 节拍。
         * 不能把节拍设成"落盘静默期"那么长——那样一旦 clock_scheduler_kick()
         * 这一脚没送到（或送晚了），灯效就要等到下一个周期才生效，
         * 用户看到的就是"点了没反应，5 秒后才切"。
         * 现在踢醒是加速路径，踢丢了最坏也只晚 100 ms。
         */
        ulTaskNotifyTake(pdTRUE, pdMS_TO_TICKS(CLOCK_TICK_MS));

        uint32_t gen;
        uint8_t restore;
        uint8_t modes[CLOCK_IO_MAX + 1];
        lock();
        gen = s_mode_gen;
        restore = s_restore_mask;
        s_restore_mask = 0;
        memcpy(modes, s_io_mode, sizeof(modes));
        unlock();

        for (uint8_t io = 1; io <= CLOCK_IO_MAX; io++) {
            if ((restore & (uint8_t)(1u << io)) != 0) {
                light_io_set_mute(io, false);
                light_io_recall_manual(io);
            }
        }

        /* 幂等：没有变化时 run_once_locked() 会自己早退 */
        run_once();

        /* ---- 模式落盘：从"最后一次改动"起算静默 5 s 才写 ---- */
        TickType_t now = xTaskGetTickCount();
        if (gen != seen_gen) {
            seen_gen = gen;
            quiet_since = (now == 0) ? 1 : now;
        } else if (quiet_since != 0 &&
                   (TickType_t)(now - quiet_since) >= pdMS_TO_TICKS(NVS_SAVE_QUIET_MS)) {
            uint8_t save;
            lock();
            save = s_mode_save_mask;
            s_mode_save_mask = 0;
            unlock();
            for (uint8_t io = 1; io <= CLOCK_IO_MAX; io++) {
                if ((save & (uint8_t)(1u << io)) != 0) {
                    nvs_save_io_mode(io, modes[io]);
                }
            }
            quiet_since = 0;
        }
    }
}

/* ---------------- 对外接口 ---------------- */

esp_err_t clock_scheduler_init(void)
{
    if (s_inited) {
        return ESP_OK;
    }
    s_lock = xSemaphoreCreateMutex();
    if (s_lock == NULL) {
        return ESP_ERR_NO_MEM;
    }
    load_all();
    s_inited = true;

    /* 开机若已是定时，先记下 NVS 恢复出来的手动灯效，再让调度覆盖内存。 */
    for (uint8_t io = 1; io <= CLOCK_IO_MAX; io++) {
        if (s_io_mode[io] == CLOCK_MODE_TIME) {
            light_io_hold_manual(io);
        }
    }
    run_once();

    /*
     * 调度任务的优先级必须高于 light_io_task(5)。
     * 它只做内存里的灯效切换（us 级），如果排在一次 500 颗灯的 RMT 传输
     * （15 ms）后面，用户就会看到"点了模式，灯过一会儿才变"。
     */
    if (xTaskCreate(clock_task, "clock_sched", 4096, NULL, 6, &s_clock_task) != pdPASS) {
        return ESP_ERR_NO_MEM;
    }
    ESP_LOGI(TAG, "调度器就绪，1 Hz 轮询");
    return ESP_OK;
}

void clock_scheduler_on_time_synced(void)
{
    lock();
    for (int i = 0; i < CLOCK_SEG_MAX; i++) {
        s_seg[i].applied = APPLIED_NONE;
    }
    run_once_locked();
    unlock();
}

esp_err_t clock_io_mode_set(uint8_t io_id, clock_io_mode_t mode)
{
    if (io_id < 1 || io_id > CLOCK_IO_MAX || mode > CLOCK_MODE_TIME) {
        return ESP_ERR_INVALID_ARG;
    }
    if (light_io_get(io_id, NULL, NULL, NULL) != ESP_OK) {
        return ESP_ERR_NOT_FOUND;
    }
    lock();
    clock_io_mode_t prev = (clock_io_mode_t)s_io_mode[io_id];
    unlock();
    /*
     * 幂等：模式没变就直接返回。
     * 不能省这一步——否则重复点同一个 chip 也会置 s_mode_save_mask
     * （5 s 后触发一次 Flash 提交），并且把所有灯段的 applied 重置一遍
     * （整组灯段白白重刷、动画相位清零，肉眼就是"灯跳一下"）。
     */
    if (prev == mode) {
        return ESP_OK;
    }
    /* 只拷贝内存。放在改模式之前，调度任务随后覆盖灯效时，手动那份还在。 */
    if (mode == CLOCK_MODE_TIME && prev != CLOCK_MODE_TIME) {
        light_io_hold_manual(io_id);
    }
    lock();
    s_io_mode[io_id] = mode;
    s_mode_save_mask |= (uint8_t)(1u << io_id);
    s_mode_gen++;
    if (mode == CLOCK_MODE_MANUAL) {
        s_restore_mask |= (uint8_t)(1u << io_id);
    } else {
        s_restore_mask &= (uint8_t)~(1u << io_id);
    }
    for (int i = 0; i < CLOCK_SEG_MAX; i++) {
        uint8_t seg_io = 0;
        if (s_seg[i].used && light_seg_get_io(s_seg[i].seg, &seg_io) == ESP_OK && seg_io == io_id) {
            s_seg[i].editing = false;
            s_seg[i].applied = APPLIED_NONE;
        }
    }
    unlock();
    /* 不在这里踢任务。调用方先把蓝牙结果发出去，再 clock_scheduler_kick()。 */
    return ESP_OK;
}

clock_io_mode_t clock_io_mode_get(uint8_t io_id)
{
    if (io_id < 1 || io_id > CLOCK_IO_MAX) {
        return CLOCK_MODE_MANUAL;
    }
    return (clock_io_mode_t)s_io_mode[io_id];
}

static bool sched_name_taken(const char *name, uint8_t except_id)
{
    for (int i = 0; i < CLOCK_SCHED_MAX; i++) {
        if (s_sched_used[i] && s_sched[i].id != except_id &&
            strncmp(s_sched[i].name, name, LIGHT_PRESET_NAME_MAX) == 0) {
            return true;
        }
    }
    return false;
}

esp_err_t clock_sched_set(uint8_t id, const clock_sched_t *in, uint8_t *out_id)
{
    if (in == NULL || in->slot_count == 0 || in->slot_count > CLOCK_SLOT_MAX) {
        return ESP_ERR_INVALID_ARG;
    }
    clock_sched_t tmp = *in;
    /* 校验并排序去重 */
    for (uint8_t i = 0; i < tmp.slot_count; i++) {
        if (tmp.at_sec[i] >= 86400) {
            return ESP_ERR_INVALID_ARG;
        }
    }
    for (uint8_t i = 1; i < tmp.slot_count; i++) {
        uint32_t key = tmp.at_sec[i];
        int j = (int)i - 1;
        while (j >= 0 && tmp.at_sec[j] > key) {
            tmp.at_sec[j + 1] = tmp.at_sec[j];
            j--;
        }
        tmp.at_sec[j + 1] = key;
    }
    uint8_t uniq = 1;
    for (uint8_t i = 1; i < tmp.slot_count; i++) {
        if (tmp.at_sec[i] == tmp.at_sec[i - 1]) {
            /* 同一套调度里不允许重复时间点，App 先“覆盖”再提交 */
            return ESP_ERR_INVALID_ARG;
        }
        tmp.at_sec[uniq++] = tmp.at_sec[i];
    }
    tmp.slot_count = uniq;

    uint32_t old_at[CLOCK_SLOT_MAX];
    uint8_t old_count = 0;
    bool updating = false;
    memset(old_at, 0, sizeof(old_at));

    lock();
    int slot = (int)id - 1;
    if (id == 0 || slot >= CLOCK_SCHED_MAX || !s_sched_used[slot]) {
        slot = -1;
        for (int i = 0; i < CLOCK_SCHED_MAX; i++) {
            if (!s_sched_used[i]) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            unlock();
            return ESP_ERR_NO_MEM;
        }
        id = (uint8_t)(slot + 1);
    } else {
        updating = true;
        old_count = s_sched[slot].slot_count;
        if (old_count > CLOCK_SLOT_MAX) {
            old_count = CLOCK_SLOT_MAX;
        }
        memcpy(old_at, s_sched[slot].at_sec, sizeof(old_at));
    }

    char name[LIGHT_PRESET_NAME_MAX];
    if (tmp.name[0] == '\0') {
        snprintf(name, sizeof(name), "调度 %u", id);
    } else {
        snprintf(name, sizeof(name), "%s", tmp.name);
    }
    if (sched_name_taken(name, id)) {
        for (unsigned n = 2; n < 1000; n++) {
            char try_name[LIGHT_PRESET_NAME_MAX + 16];
            snprintf(try_name, sizeof(try_name), "%s %u", name, n);
            if (!sched_name_taken(try_name, id)) {
                copy_name(name, sizeof(name), try_name);
                break;
            }
        }
    }

    s_sched[slot].id = id;
    memcpy(s_sched[slot].name, name, sizeof(s_sched[slot].name));
    s_sched[slot].name[LIGHT_PRESET_NAME_MAX - 1] = '\0';
    s_sched[slot].slot_count = tmp.slot_count;
    memset(s_sched[slot].at_sec, 0, sizeof(s_sched[slot].at_sec));
    memcpy(s_sched[slot].at_sec, tmp.at_sec, sizeof(uint32_t) * tmp.slot_count);
    s_sched_used[slot] = true;
    nvs_save_sched(id);

    for (int i = 0; i < CLOCK_SEG_MAX; i++) {
        if (!s_seg[i].used || s_seg[i].sched != id) {
            continue;
        }
        s_seg[i].applied = APPLIED_NONE;
        if (!updating) {
            continue;
        }
        uint8_t new_map[CLOCK_SLOT_MAX];
        memset(new_map, LIGHT_PRESET_ID_NONE, sizeof(new_map));
        uint8_t bound = s_seg[i].slot_count;
        if (bound > CLOCK_SLOT_MAX) {
            bound = CLOCK_SLOT_MAX;
        }
        uint8_t limit = old_count < bound ? old_count : bound;
        for (uint8_t n = 0; n < tmp.slot_count; n++) {
            uint32_t minute = tmp.at_sec[n] / 60U;
            for (uint8_t o = 0; o < limit; o++) {
                if (old_at[o] / 60U == minute) {
                    new_map[n] = s_seg[i].map[o];
                    break;
                }
            }
        }
        memcpy(s_seg[i].map, new_map, sizeof(s_seg[i].map));
        s_seg[i].slot_count = tmp.slot_count;
        nvs_save_bind(&s_seg[i]);
    }
    run_once_locked();
    unlock();

    if (out_id != NULL) {
        *out_id = id;
    }
    return ESP_OK;
}

esp_err_t clock_sched_get(uint8_t id, clock_sched_t *out)
{
    if (out == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    const clock_sched_t *s = sched_get(id);
    if (s == NULL) {
        return ESP_ERR_NOT_FOUND;
    }
    *out = *s;
    return ESP_OK;
}

esp_err_t clock_sched_delete(uint8_t id)
{
    int slot = (int)id - 1;
    if (slot < 0 || slot >= CLOCK_SCHED_MAX || !s_sched_used[slot]) {
        return ESP_ERR_NOT_FOUND;
    }
    lock();
    memset(&s_sched[slot], 0, sizeof(s_sched[slot]));
    s_sched_used[slot] = false;

    nvs_handle_t h;
    if (nvs_open(NVS_NAMESPACE, NVS_READWRITE, &h) == ESP_OK) {
        char key[8];
        snprintf(key, sizeof(key), "s%02u", id);
        nvs_erase_key(h, key);
        nvs_commit(h);
        nvs_close(h);
    }

    for (int i = 0; i < CLOCK_SEG_MAX; i++) {
        if (s_seg[i].used && s_seg[i].sched == id) {
            s_seg[i].sched = 0;
            s_seg[i].slot_count = 0;
            memset(s_seg[i].map, LIGHT_PRESET_ID_NONE, sizeof(s_seg[i].map));
            s_seg[i].applied = APPLIED_NONE;
            nvs_save_bind(&s_seg[i]);
        }
    }
    run_once_locked();
    unlock();
    return ESP_OK;
}

uint8_t clock_sched_list(uint8_t *ids, uint8_t cap)
{
    if (ids == NULL) {
        return 0;
    }
    uint8_t n = 0;
    lock();
    for (int i = 0; i < CLOCK_SCHED_MAX && n < cap; i++) {
        if (s_sched_used[i]) {
            ids[n++] = s_sched[i].id;
        }
    }
    unlock();
    return n;
}

esp_err_t clock_seg_bind_set(uint8_t seg_id, uint8_t sched_id,
                             const uint8_t *preset_map, uint8_t slot_count)
{
    if (slot_count > CLOCK_SLOT_MAX || (slot_count > 0 && preset_map == NULL)) {
        return ESP_ERR_INVALID_ARG;
    }
    uint8_t io_check = 0;
    if (light_seg_get_io(seg_id, &io_check) != ESP_OK) {
        return ESP_ERR_NOT_FOUND;
    }
    if (sched_id != 0 && sched_get(sched_id) == NULL) {
        return ESP_ERR_NOT_FOUND;
    }

    lock();
    seg_state_t *b = seg_get_or_create(seg_id);
    if (b == NULL) {
        unlock();
        return ESP_ERR_NO_MEM;
    }
    b->sched = sched_id;
    b->slot_count = slot_count;
    memset(b->map, LIGHT_PRESET_ID_NONE, sizeof(b->map));
    if (slot_count > 0) {
        memcpy(b->map, preset_map, slot_count);
    }
    b->editing = false;
    b->applied = APPLIED_NONE;
    nvs_save_bind(b);
    run_once_locked();
    unlock();
    return ESP_OK;
}

esp_err_t clock_seg_bind_get(uint8_t seg_id, uint8_t *sched_id,
                             uint8_t *preset_map, uint8_t *slot_count)
{
    lock();
    seg_state_t *b = seg_find(seg_id);
    if (b == NULL) {
        if (sched_id != NULL) {
            *sched_id = 0;
        }
        if (slot_count != NULL) {
            *slot_count = 0;
        }
        if (preset_map != NULL) {
            memset(preset_map, LIGHT_PRESET_ID_NONE, CLOCK_SLOT_MAX);
        }
        unlock();
        return ESP_OK;
    }
    if (sched_id != NULL) {
        *sched_id = b->sched;
    }
    if (slot_count != NULL) {
        *slot_count = b->slot_count;
    }
    if (preset_map != NULL) {
        memcpy(preset_map, b->map, CLOCK_SLOT_MAX);
    }
    unlock();
    return ESP_OK;
}

esp_err_t clock_seg_unbind(uint8_t seg_id)
{
    lock();
    seg_state_t *b = seg_find(seg_id);
    if (b == NULL) {
        unlock();
        return ESP_ERR_NOT_FOUND;
    }
    b->sched = 0;
    b->slot_count = 0;
    memset(b->map, LIGHT_PRESET_ID_NONE, sizeof(b->map));
    b->applied = APPLIED_NONE;
    nvs_erase_bind(seg_id);
    run_once_locked();
    unlock();
    return ESP_OK;
}

void clock_seg_local_write(uint8_t seg_id)
{
    uint8_t io = 0;
    if (light_seg_get_io(seg_id, &io) != ESP_OK) {
        return;
    }
    if (clock_io_mode_get(io) != CLOCK_MODE_TIME) {
        return;
    }
    lock();
    seg_state_t *b = seg_get_or_create(seg_id);
    if (b != NULL) {
        b->editing = true;
    }
    unlock();
}

esp_err_t clock_seg_commit(uint8_t seg_id, bool save_as_preset, uint8_t *out_preset)
{
    segment_effect_t eff;
    if (light_seg_get_effect(seg_id, &eff) != ESP_OK) {
        return ESP_ERR_NOT_FOUND;
    }

    uint8_t new_id = LIGHT_PRESET_ID_NONE;
    if (save_as_preset) {
        esp_err_t err = light_preset_create(NULL, &eff, &new_id);
        if (err != ESP_OK) {
            return err;
        }
    }

    lock();
    seg_state_t *b = seg_get_or_create(seg_id);
    if (b == NULL) {
        unlock();
        return ESP_ERR_NO_MEM;
    }
    if (save_as_preset && b->sched != 0 && b->slot_count > 0) {
        const clock_sched_t *s = sched_get(b->sched);
        int slot = current_slot(s, now_sec_of_day());
        if (slot >= 0 && (uint8_t)slot < b->slot_count) {
            b->map[slot] = new_id;
            nvs_save_bind(b);
        }
    }
    b->editing = false;
    b->applied = APPLIED_NONE;
    run_once_locked();
    unlock();

    if (out_preset != NULL) {
        *out_preset = new_id;
    }
    return ESP_OK;
}

void clock_seg_cancel(uint8_t seg_id)
{
    lock();
    seg_state_t *b = seg_find(seg_id);
    if (b != NULL) {
        b->editing = false;
        b->applied = APPLIED_NONE;
        run_once_locked();
    }
    unlock();
}

void clock_seg_cancel_all(void)
{
    lock();
    for (int i = 0; i < CLOCK_SEG_MAX; i++) {
        s_seg[i].editing = false;
        s_seg[i].applied = APPLIED_NONE;
    }
    run_once_locked();
    unlock();
}

bool clock_seg_is_editing(uint8_t seg_id)
{
    lock();
    seg_state_t *b = seg_find(seg_id);
    bool editing = b != NULL && b->editing;
    unlock();
    return editing;
}

uint8_t clock_seg_active_preset(uint8_t seg_id)
{
    lock();
    seg_state_t *b = seg_find(seg_id);
    uint8_t id = b != NULL ? b->applied : LIGHT_PRESET_ID_NONE;
    unlock();
    return id;
}

uint32_t clock_next_event_sec(void)
{
    if (!s_time_valid) {
        return 0;
    }
    uint32_t sod = now_sec_of_day();
    uint32_t best = 86400;
    lock();
    for (int i = 0; i < CLOCK_SCHED_MAX; i++) {
        if (!s_sched_used[i]) {
            continue;
        }
        for (uint8_t k = 0; k < s_sched[i].slot_count; k++) {
            uint32_t at = s_sched[i].at_sec[k];
            if (at > sod && at < best) {
                best = at;
            }
        }
    }
    unlock();
    return best == 86400 ? 0 : best;
}

bool clock_time_valid(void)
{
    return s_time_valid;
}

void clock_seg_forget(uint8_t seg_id)
{
    lock();
    seg_state_t *b = seg_find(seg_id);
    if (b != NULL) {
        b->used = false;
    }
    unlock();
}

uint16_t clock_preset_usage(uint8_t preset_id, char *buf, uint16_t cap)
{
    if (buf == NULL || cap < 8) {
        return 0;
    }
    uint16_t used = 0;
    uint8_t count = 0;

    lock();
    for (int i = 0; i < CLOCK_SEG_MAX; i++) {
        if (!s_seg[i].used || s_seg[i].sched == 0) {
            continue;
        }
        for (uint8_t k = 0; k < s_seg[i].slot_count; k++) {
            if (s_seg[i].map[k] == preset_id) {
                count++;
                break;
            }
        }
    }
    used += (uint16_t)snprintf(buf + used, cap - used, "%u\n", count);

    for (int i = 0; i < CLOCK_SEG_MAX && used + 32 < cap; i++) {
        seg_state_t *b = &s_seg[i];
        if (!b->used || b->sched == 0) {
            continue;
        }
        bool hit = false;
        for (uint8_t k = 0; k < b->slot_count; k++) {
            if (b->map[k] == preset_id) {
                hit = true;
                break;
            }
        }
        if (!hit) {
            continue;
        }
        uint8_t io = 0;
        char name[LIGHT_NAME_MAX] = { 0 };
        light_seg_get_io(b->seg, &io);
        light_seg_get_name(b->seg, name, sizeof(name));
        used += (uint16_t)snprintf(buf + used, cap - used, "%u,%s\n", io, name);
    }
    unlock();
    return used;
}

void clock_preset_replace(uint8_t old_id, uint8_t new_id)
{
    lock();
    for (int i = 0; i < CLOCK_SEG_MAX; i++) {
        if (!s_seg[i].used) {
            continue;
        }
        bool changed = false;
        for (uint8_t k = 0; k < s_seg[i].slot_count; k++) {
            if (s_seg[i].map[k] == old_id) {
                s_seg[i].map[k] = new_id;
                changed = true;
            }
        }
        if (changed) {
            s_seg[i].applied = APPLIED_NONE;
            nvs_save_bind(&s_seg[i]);
        }
    }
    run_once_locked();
    unlock();
}

uint16_t clock_sched_usage(uint8_t sched_id, char *buf, uint16_t cap)
{
    if (buf == NULL || cap < 8) {
        return 0;
    }
    uint16_t used = 0;
    uint8_t count = 0;

    lock();
    for (int i = 0; i < CLOCK_SEG_MAX; i++) {
        if (s_seg[i].used && s_seg[i].sched == sched_id) {
            count++;
        }
    }
    used += (uint16_t)snprintf(buf + used, cap - used, "%u\n", count);

    for (int i = 0; i < CLOCK_SEG_MAX && used + 32 < cap; i++) {
        seg_state_t *b = &s_seg[i];
        if (!b->used || b->sched != sched_id) {
            continue;
        }
        uint8_t io = 0;
        char name[LIGHT_NAME_MAX] = { 0 };
        light_seg_get_io(b->seg, &io);
        light_seg_get_name(b->seg, name, sizeof(name));
        used += (uint16_t)snprintf(buf + used, cap - used, "%u,%s\n", io, name);
    }
    unlock();
    return used;
}
