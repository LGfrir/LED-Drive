/**
 * @file light_mode_driver.c
 * @author LG_frir
 * @date 2026-11-11 17:53:40
 */

#include "driver/led_strip/light_mode_driver.h"
#include "driver/nvs/nvs_flash_save.h"
#include "driver/ws2812/ws2812_spi.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"
#include "esp_log.h"

#define TAG "light_io"

typedef struct {
    uint8_t id;
    char name[LIGHT_NAME_MAX];
    uint16_t offset;
    uint16_t length;
    segment_effect_t effect;
    segment_effect_t held; /* 进入定时前的手动灯效，切回来时用，避免再读闪存 */
    segment_anim_t anim;
    bool held_valid;
} light_segment_t;

typedef struct {
    uint8_t id;
    char name[LIGHT_NAME_MAX];
    uint16_t led_count;
    uint8_t brightness;
    uint16_t refresh_ms;
    light_frame_show_fn show;
    void *show_ctx;
    light_segment_t segs[LIGHT_SEG_MAX];
    uint8_t seg_count;
    color_t *raw;
    color_t *tx;
    TaskHandle_t task;
    bool used;
    bool mute;
    bool dma; /* 这一路是不是 DMA。ESP32-S3 只有 1 个 RMT 通道支持 DMA，其余都是 0 */
    uint32_t last_mw; /* 最近一帧按实际 RGB 估算的 12V 功率（mW） */
} light_io_t;

static light_io_t s_ios[LIGHT_IO_MAX];
static portMUX_TYPE s_lock = portMUX_INITIALIZER_UNLOCKED;
/* 归零时加一。渲染写回相位前对一下，避免把刚清掉的相位又写回去。 */
static uint32_t s_phase_gen;

/* ---- 脏数据攒批 ----------------------------------------------------------
 * 原来 persist_io / persist_seg 当场 nvs_commit：拖一次滑条 = 一次 Flash 写，
 * 写一次 code cache 就关一次，RMT 的补数中断跟着停，灯带就断流。
 * 现在只置脏标记，由 light_persist 任务在静默 2 s 后整批写回、只 commit 一次。
 */
#define LIGHT_PERSIST_DELAY_MS 2000
static uint8_t s_dirty_io_mask;
static bool s_dirty_seg[256];
static TaskHandle_t s_persist_task;

static bool wave_ok(const wave_cfg_t *cfg);
static bool chase_ok(const chase_cfg_t *cfg);
static bool points_ok(const points_cfg_t *cfg);
static bool flash_ok(const flash_cfg_t *cfg);
static bool effect_sane(const segment_effect_t *cfg);
static void apply_stored(light_io_t *io);
static void persist_io(uint8_t io_id);
static void persist_seg(uint8_t seg_id);

/*
 * WS2812 一位 1.25 us，一颗灯 24 位 = 30 us。
 * 所以 N 颗灯一帧的纯发送时间就是 N*30 us，配置再快也发不完。
 * 加 1 ms 余量给渲染和 280 us 的 RESET 脉冲。
 * 500 颗 → 16 ms；96 颗 → 5 ms（不低于 LIGHT_REFRESH_MIN_MS）。
 */
uint16_t light_refresh_floor_for(uint16_t led_count, bool dma)
{
    /* 物理下限：24 位/颗 × 1.25 us = 30 us/颗，再留 1 ms 给渲染和 RESET */
    uint32_t ms = ((uint32_t)led_count * 30u + 999u) / 1000u + 1u;

    if (!dma) {
        /*
         * 非 DMA 走 48 符号一块，每块只有 60 us 的补数截止期，
         * 一帧要补 led_count*24/48 次中断。
         *   ISR 频率 ≈ (1000 / ms) × (led_count / 2)
         * 想把它压到 5000/s 以下，就要 ms >= led_count / 5。
         * 96 颗 → 20 ms(50 fps)，60 颗 → 13 ms，30 颗 → 7 ms。
         * 5 ms 那种 200 fps 对眼睛没意义，只是白烧中断。
         */
        uint32_t rate_floor = (uint32_t)led_count / 5u + 1u;
        if (ms < rate_floor) {
            ms = rate_floor;
        }
    }
    /* App 的滑条按 10 ms 一档，下限也对齐到 10 的整数倍，避免出现 33 ms 这种值 */
    ms = ((ms + 9u) / 10u) * 10u;
    if (ms < LIGHT_REFRESH_MIN_MS) {
        ms = LIGHT_REFRESH_MIN_MS;
    }
    if (ms > LIGHT_REFRESH_MAX_MS) {
        ms = LIGHT_REFRESH_MAX_MS;
    }
    return (uint16_t)ms;
}

static void name_copy(char *dst, const char *src)
{
    memset(dst, 0, LIGHT_NAME_MAX);
    if (src == NULL) {
        return;
    }
    strncpy(dst, src, LIGHT_NAME_MAX - 1);
}

static segment_effect_t effect_default(void)
{
    segment_effect_t effect = {
        .mode = LIGHT_MODE_WAVE,
        .brightness = 255,
        .wave = { .speed_pct = 50, .dir = LIGHT_DIR_RIGHT },
        .solid = { .color = { 255, 255, 255 } },
        .breath = { .color = { 255, 255, 255 }, .period_ms = 2000 },
        .chase = {
            .count = 3,
            .dir = LIGHT_DIR_RIGHT,
            .speed_pct = 50,
            .colors = {
                { 255, 0, 0 },
                { 0, 255, 0 },
                { 0, 0, 255 },
            },
        },
        .points = {
            .count = 1,
            .wrap = false,
            .points = {
                {
                    .position_pct = 0,
                    .count = 1,
                    .period_ms = 2000,
                    .colors = { { 255, 255, 255 } },
                },
            },
        },
        .flash = { .mode = FLASH_MODE_BLINK, .period_ms = 400 },
    };
    return effect;
}

static light_io_t *io_find_locked(uint8_t io_id)
{
    for (uint8_t i = 0; i < LIGHT_IO_MAX; i++) {
        if (s_ios[i].used && s_ios[i].id == io_id) {
            return &s_ios[i];
        }
    }
    return NULL;
}

static light_segment_t *seg_find_locked(uint8_t seg_id, light_io_t **io_out)
{
    for (uint8_t i = 0; i < LIGHT_IO_MAX; i++) {
        if (!s_ios[i].used) {
            continue;
        }
        for (uint8_t s = 0; s < s_ios[i].seg_count; s++) {
            if (s_ios[i].segs[s].id == seg_id) {
                if (io_out != NULL) {
                    *io_out = &s_ios[i];
                }
                return &s_ios[i].segs[s];
            }
        }
    }
    return NULL;
}

static bool seg_id_used_locked(uint8_t seg_id)
{
    return seg_find_locked(seg_id, NULL) != NULL;
}

/*
 * 灯效写进 raw 的是满亮度。这里按灯段亮度再按 IO 亮度缩一次，放进 tx。
 * 不写回 raw，下一帧不会越乘越暗。灯段以外的灯珠保持 0。
 */
static void compose_frame(light_io_t *io)
{
    memset(io->tx, 0, (size_t)io->led_count * sizeof(color_t));
    for (uint8_t s = 0; s < io->seg_count; s++) {
        light_segment_t *seg = &io->segs[s];
        if (seg->length == 0 || seg->offset >= io->led_count) {
            continue;
        }
        uint16_t room = io->led_count - seg->offset;
        uint16_t n = seg->length < room ? seg->length : room;
        uint16_t gain = (uint16_t)seg->effect.brightness * io->brightness;
        for (uint16_t i = 0; i < n; i++) {
            color_t src = io->raw[seg->offset + i];
            color_t *dst = &io->tx[seg->offset + i];
            dst->r = (uint8_t)((uint16_t)src.r * gain / (255 * 255));
            dst->g = (uint8_t)((uint16_t)src.g * gain / (255 * 255));
            dst->b = (uint8_t)((uint16_t)src.b * gain / (255 * 255));
        }
    }

    /*
     * 按实际发送帧估算 12V 功率：
     * 全白 1A/160 颗 = 6.25mA/颗 = 75mW/颗；
     * 单颗按 (r+g+b)/765 缩放，整条累加。
     */
    uint32_t rgb_sum = 0;
    for (uint16_t i = 0; i < io->led_count; i++) {
        rgb_sum += (uint32_t)io->tx[i].r + io->tx[i].g + io->tx[i].b;
    }
    uint32_t mw = (rgb_sum * 75u) / 765u;
    portENTER_CRITICAL(&s_lock);
    io->last_mw = mw;
    portEXIT_CRITICAL(&s_lock);
}

static void render_io(light_io_t *io, uint32_t delta_ms)
{
    for (uint8_t s = 0; s < io->seg_count; s++) {
        light_segment_t *seg = &io->segs[s];
        segment_effect_t effect;
        segment_anim_t anim;
        light_mode_t mode;
        uint32_t phase_gen;

        portENTER_CRITICAL(&s_lock);
        effect = seg->effect;
        anim = seg->anim;
        mode = seg->effect.mode;
        phase_gen = s_phase_gen;
        portEXIT_CRITICAL(&s_lock);

        if (seg->offset >= io->led_count) {
            continue;
        }
        uint16_t room = io->led_count - seg->offset;
        uint16_t n = seg->length < room ? seg->length : room;
        light_effect_render(&effect, &anim, &io->raw[seg->offset], n, delta_ms);

        portENTER_CRITICAL(&s_lock);
        if (seg->effect.mode == mode && phase_gen == s_phase_gen) {
            seg->anim = anim;
        }
        portEXIT_CRITICAL(&s_lock);
    }
    compose_frame(io);
}

static void light_io_task(void *arg)
{
    light_io_t *io = arg;
    TickType_t last = xTaskGetTickCount();
    TickType_t prev = last; /* 上一帧真正开始渲染的时刻，只用来算 delta */
    const uint16_t floor_ms = light_refresh_floor_for(io->led_count, io->dma);

    while (1) {
        portENTER_CRITICAL(&s_lock);
        bool mute = io->mute;
        uint16_t period = io->refresh_ms;
        portEXIT_CRITICAL(&s_lock);

        /*
         * 两处钳制都不能省：
         *  1) 低于物理下限（N*30 us）的刷新发不完，只会空转；
         *  2) CONFIG_FREERTOS_HZ=100 时 pdMS_TO_TICKS(5..9) 都等于 0，
         *     而 vTaskDelay(0) 不是"睡 0 ms"，是立刻回来。
         *     没有这个下限，把刷新拖到 5 ms 就会让发送任务满速空转，
         *     同优先级的 NimBLE host 直接被饿死。
         */
        if (period < floor_ms) {
            period = floor_ms;
        }
        TickType_t wait = pdMS_TO_TICKS(period);
        if (wait < 1) {
            wait = 1;
        }

        if (mute) {
            /* 静默：只发全 0，不渲染动画 */
            memset(io->tx, 0, (size_t)io->led_count * sizeof(color_t));
            portENTER_CRITICAL(&s_lock);
            io->last_mw = 0;
            portEXIT_CRITICAL(&s_lock);
            if (io->show != NULL) {
                io->show(io->show_ctx, io->tx, io->led_count);
            }
            vTaskDelayUntil(&last, wait);
            prev = xTaskGetTickCount();
            continue;
        }

        TickType_t now = xTaskGetTickCount();
        uint32_t delta = (uint32_t)(now - prev) * portTICK_PERIOD_MS;
        prev = now;
        if (delta == 0) {
            delta = period;
        }

        render_io(io, delta);
        if (io->show != NULL) {
            io->show(io->show_ctx, io->tx, io->led_count);
        }

        /* 用绝对时间排下一帧：period 就是真实帧周期，
         * 而不是"发送时间 + period"（500 颗那样会白多等 15 ms）。 */
        vTaskDelayUntil(&last, wait);
    }
}

esp_err_t light_show_rmt(void *ctx, const color_t *pixels, uint16_t count)
{
    ws2812_rmt_handle_t *bus = ctx;
    if (bus == NULL || pixels == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    uint16_t n = ws2812_rmt_led_count(bus);
    if (count < n) {
        n = count;
    }
    for (uint16_t i = 0; i < n; i++) {
        ws2812_rmt_set_pixel(bus, i, pixels[i].r, pixels[i].g, pixels[i].b);
    }
    return ws2812_rmt_show(bus);
}

esp_err_t light_show_spi(void *ctx, const color_t *pixels, uint16_t count)
{
    ws2812_spi_handle_t *bus = ctx;
    if (bus == NULL || pixels == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    uint16_t n = ws2812_spi_led_count(bus);
    if (count < n) {
        n = count;
    }
    for (uint16_t i = 0; i < n; i++) {
        ws2812_spi_set_pixel(bus, i, pixels[i].r, pixels[i].g, pixels[i].b);
    }
    return ws2812_spi_show(bus);
}

esp_err_t light_io_create(uint8_t io_id, const char *name, uint16_t led_count,
                          uint8_t brightness, uint16_t refresh_ms,
                          light_frame_show_fn show, void *show_ctx,
                          const light_seg_desc_t *segs, uint8_t seg_count,
                          bool dma)
{
    if (show == NULL || led_count == 0 || seg_count == 0 || seg_count > LIGHT_SEG_MAX || segs == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    if (refresh_ms < LIGHT_REFRESH_MIN_MS || refresh_ms > LIGHT_REFRESH_MAX_MS) {
        return ESP_ERR_INVALID_ARG;
    }

    uint32_t sum = 0;
    for (uint8_t i = 0; i < seg_count; i++) {
        if (segs[i].length == 0) {
            return ESP_ERR_INVALID_ARG;
        }
        sum += segs[i].length;
    }
    if (sum > led_count) {
        return ESP_ERR_INVALID_SIZE;
    }

    portENTER_CRITICAL(&s_lock);
    if (io_find_locked(io_id) != NULL) {
        portEXIT_CRITICAL(&s_lock);
        return ESP_ERR_INVALID_STATE;
    }
    for (uint8_t i = 0; i < seg_count; i++) {
        if (seg_id_used_locked(segs[i].id)) {
            portEXIT_CRITICAL(&s_lock);
            return ESP_ERR_INVALID_STATE;
        }
        for (uint8_t j = 0; j < i; j++) {
            if (segs[j].id == segs[i].id) {
                portEXIT_CRITICAL(&s_lock);
                return ESP_ERR_INVALID_STATE;
            }
        }
    }
    light_io_t *io = NULL;
    for (uint8_t i = 0; i < LIGHT_IO_MAX; i++) {
        if (!s_ios[i].used) {
            io = &s_ios[i];
            break;
        }
    }
    if (io == NULL) {
        portEXIT_CRITICAL(&s_lock);
        return ESP_ERR_NO_MEM;
    }
    portEXIT_CRITICAL(&s_lock);

    color_t *raw = calloc(led_count, sizeof(color_t));
    color_t *tx = calloc(led_count, sizeof(color_t));
    if (raw == NULL || tx == NULL) {
        free(raw);
        free(tx);
        return ESP_ERR_NO_MEM;
    }

    memset(io, 0, sizeof(*io));
    /* 先静默，等调度器确认模式后再放开：避免时间模式未对时的时候
     * 上电瞬间按手动存档闪一下灯 */
    io->mute = true;
    io->id = io_id;
    name_copy(io->name, name);
    io->led_count = led_count;
    io->dma = dma;
    io->brightness = brightness;
    io->refresh_ms = refresh_ms;
    {
        uint16_t floor_ms = light_refresh_floor_for(led_count, dma);
        if (io->refresh_ms < floor_ms) {
            io->refresh_ms = floor_ms;
        }
    }
    io->show = show;
    io->show_ctx = show_ctx;
    io->raw = raw;
    io->tx = tx;
    io->seg_count = seg_count;

    uint16_t offset = 0;
    for (uint8_t i = 0; i < seg_count; i++) {
        light_segment_t *seg = &io->segs[i];
        seg->id = segs[i].id;
        name_copy(seg->name, segs[i].name);
        seg->offset = offset;
        seg->length = segs[i].length;
        seg->effect = effect_default();
        offset = (uint16_t)(offset + segs[i].length);
    }
    apply_stored(io);
    io->used = true;

    char task_name[16];
    snprintf(task_name, sizeof(task_name), "light_io%u", io_id);
    if (xTaskCreate(light_io_task, task_name, 4096, io, 5, &io->task) != pdPASS) {
        free(raw);
        free(tx);
        io->used = false;
        return ESP_ERR_NO_MEM;
    }

    ESP_LOGI(TAG, "IO %u 就绪，%u 颗，%u 段", io_id, led_count, seg_count);
    return ESP_OK;
}

esp_err_t light_io_set_brightness(uint8_t io_id, uint8_t brightness)
{
    portENTER_CRITICAL(&s_lock);
    light_io_t *io = io_find_locked(io_id);
    if (io == NULL) {
        portEXIT_CRITICAL(&s_lock);
        return ESP_ERR_NOT_FOUND;
    }
    io->brightness = brightness;
    portEXIT_CRITICAL(&s_lock);
    persist_io(io_id);
    return ESP_OK;
}

esp_err_t light_io_set_refresh(uint8_t io_id, uint16_t refresh_ms)
{
    if (refresh_ms < LIGHT_REFRESH_MIN_MS || refresh_ms > LIGHT_REFRESH_MAX_MS) {
        return ESP_ERR_INVALID_ARG;
    }
    portENTER_CRITICAL(&s_lock);
    light_io_t *io = io_find_locked(io_id);
    if (io == NULL) {
        portEXIT_CRITICAL(&s_lock);
        return ESP_ERR_NOT_FOUND;
    }
    uint16_t floor_ms = light_refresh_floor_for(io->led_count, io->dma);
    if (refresh_ms < floor_ms) {
        /* 物理上发不完，夹到下限。App 写完会回读，能看到实际生效的值。 */
        refresh_ms = floor_ms;
    }
    io->refresh_ms = refresh_ms;
    portEXIT_CRITICAL(&s_lock);
    persist_io(io_id);
    return ESP_OK;
}

esp_err_t light_io_get_power_mw(uint8_t io_id, uint32_t *mw)
{
    if (mw == NULL) {
        return ESP_ERR_INVALID_ARG;
    }

    portENTER_CRITICAL(&s_lock);
    light_io_t *io = io_find_locked(io_id);
    if (io == NULL) {
        portEXIT_CRITICAL(&s_lock);
        return ESP_ERR_NOT_FOUND;
    }
    *mw = io->last_mw;
    portEXIT_CRITICAL(&s_lock);
    return ESP_OK;
}

esp_err_t light_io_get(uint8_t io_id, uint8_t *brightness, uint16_t *refresh_ms, uint16_t *led_count)
{
    portENTER_CRITICAL(&s_lock);
    light_io_t *io = io_find_locked(io_id);
    if (io == NULL) {
        portEXIT_CRITICAL(&s_lock);
        return ESP_ERR_NOT_FOUND;
    }
    if (brightness != NULL) {
        *brightness = io->brightness;
    }
    if (refresh_ms != NULL) {
        *refresh_ms = io->refresh_ms;
    }
    if (led_count != NULL) {
        *led_count = io->led_count;
    }
    portEXIT_CRITICAL(&s_lock);
    return ESP_OK;
}

esp_err_t light_io_restore_manual(uint8_t io_id)
{
    portENTER_CRITICAL(&s_lock);
    light_io_t *io = io_find_locked(io_id);
    if (io == NULL) {
        portEXIT_CRITICAL(&s_lock);
        return ESP_ERR_NOT_FOUND;
    }
    uint8_t ids[LIGHT_SEG_MAX];
    uint8_t count = io->seg_count;
    for (uint8_t i = 0; i < count; i++) {
        ids[i] = io->segs[i].id;
    }
    portEXIT_CRITICAL(&s_lock);

    for (uint8_t i = 0; i < count; i++) {
        segment_effect_t effect;
        if (nvs_light_load_seg(ids[i], &effect) != ESP_OK || !effect_sane(&effect)) {
            continue;
        }
        portENTER_CRITICAL(&s_lock);
        light_segment_t *seg = seg_find_locked(ids[i], NULL);
        if (seg != NULL) {
            if (seg->effect.mode != effect.mode) {
                memset(&seg->anim, 0, sizeof(seg->anim));
            }
            seg->effect = effect;
        }
        portEXIT_CRITICAL(&s_lock);
    }
    return ESP_OK;
}

esp_err_t light_io_hold_manual(uint8_t io_id)
{
    portENTER_CRITICAL(&s_lock);
    light_io_t *io = io_find_locked(io_id);
    if (io == NULL) {
        portEXIT_CRITICAL(&s_lock);
        return ESP_ERR_NOT_FOUND;
    }
    uint8_t count = io->seg_count;
    portEXIT_CRITICAL(&s_lock);

    for (uint8_t i = 0; i < count; i++) {
        portENTER_CRITICAL(&s_lock);
        io = io_find_locked(io_id);
        if (io == NULL || i >= io->seg_count) {
            portEXIT_CRITICAL(&s_lock);
            return ESP_ERR_NOT_FOUND;
        }
        io->segs[i].held = io->segs[i].effect;
        io->segs[i].held_valid = true;
        portEXIT_CRITICAL(&s_lock);
    }
    return ESP_OK;
}

esp_err_t light_io_recall_manual(uint8_t io_id)
{
    portENTER_CRITICAL(&s_lock);
    light_io_t *io = io_find_locked(io_id);
    if (io == NULL) {
        portEXIT_CRITICAL(&s_lock);
        return ESP_ERR_NOT_FOUND;
    }
    uint8_t count = io->seg_count;
    portEXIT_CRITICAL(&s_lock);

    for (uint8_t i = 0; i < count; i++) {
        uint8_t id = 0;
        bool valid = false;
        segment_effect_t effect;
        portENTER_CRITICAL(&s_lock);
        io = io_find_locked(io_id);
        if (io == NULL || i >= io->seg_count) {
            portEXIT_CRITICAL(&s_lock);
            return ESP_ERR_NOT_FOUND;
        }
        id = io->segs[i].id;
        valid = io->segs[i].held_valid;
        if (valid) {
            effect = io->segs[i].held;
        }
        portEXIT_CRITICAL(&s_lock);

        if (!valid) {
            if (nvs_light_load_seg(id, &effect) != ESP_OK || !effect_sane(&effect)) {
                continue;
            }
        }

        portENTER_CRITICAL(&s_lock);
        light_segment_t *seg = seg_find_locked(id, NULL);
        if (seg != NULL) {
            if (seg->effect.mode != effect.mode) {
                memset(&seg->anim, 0, sizeof(seg->anim));
            }
            seg->effect = effect;
            if (!seg->held_valid) {
                seg->held = effect;
                seg->held_valid = true;
            }
        }
        portEXIT_CRITICAL(&s_lock);
    }
    return ESP_OK;
}

void light_reset_phase(void)
{
    portENTER_CRITICAL(&s_lock);
    s_phase_gen++;
    for (uint8_t i = 0; i < LIGHT_IO_MAX; i++) {
        light_io_t *io = &s_ios[i];
        if (!io->used) {
            continue;
        }
        for (uint8_t s = 0; s < io->seg_count; s++) {
            memset(&io->segs[s].anim, 0, sizeof(io->segs[s].anim));
        }
    }
    portEXIT_CRITICAL(&s_lock);
}

esp_err_t light_io_set_mute(uint8_t io_id, bool mute)
{
    portENTER_CRITICAL(&s_lock);
    light_io_t *io = io_find_locked(io_id);
    if (io == NULL) {
        portEXIT_CRITICAL(&s_lock);
        return ESP_ERR_NOT_FOUND;
    }
    io->mute = mute;
    portEXIT_CRITICAL(&s_lock);
    return ESP_OK;
}

bool light_io_is_muted(uint8_t io_id)
{
    bool mute = false;
    portENTER_CRITICAL(&s_lock);
    light_io_t *io = io_find_locked(io_id);
    if (io != NULL) {
        mute = io->mute;
    }
    portEXIT_CRITICAL(&s_lock);
    return mute;
}

esp_err_t light_io_list(uint8_t *ids, uint8_t cap, uint8_t *count)
{
    if (ids == NULL || count == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    uint8_t n = 0;
    portENTER_CRITICAL(&s_lock);
    for (uint8_t i = 0; i < LIGHT_IO_MAX && n < cap; i++) {
        if (s_ios[i].used) {
            ids[n++] = s_ios[i].id;
        }
    }
    portEXIT_CRITICAL(&s_lock);
    *count = n;
    return ESP_OK;
}

esp_err_t light_io_seg_list(uint8_t io_id, uint8_t *ids, uint8_t cap, uint8_t *count)
{
    if (ids == NULL || count == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    uint8_t n = 0;
    portENTER_CRITICAL(&s_lock);
    light_io_t *io = io_find_locked(io_id);
    if (io == NULL) {
        portEXIT_CRITICAL(&s_lock);
        return ESP_ERR_NOT_FOUND;
    }
    for (uint8_t s = 0; s < io->seg_count && n < cap; s++) {
        ids[n++] = io->segs[s].id;
    }
    portEXIT_CRITICAL(&s_lock);
    *count = n;
    return ESP_OK;
}

esp_err_t light_seg_get_io(uint8_t seg_id, uint8_t *io_id)
{
    if (io_id == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    portENTER_CRITICAL(&s_lock);
    light_io_t *io = NULL;
    light_segment_t *seg = seg_find_locked(seg_id, &io);
    if (seg == NULL || io == NULL) {
        portEXIT_CRITICAL(&s_lock);
        return ESP_ERR_NOT_FOUND;
    }
    *io_id = io->id;
    portEXIT_CRITICAL(&s_lock);
    return ESP_OK;
}

esp_err_t light_seg_get_name(uint8_t seg_id, char *buf, size_t len)
{
    if (buf == NULL || len == 0) {
        return ESP_ERR_INVALID_ARG;
    }
    portENTER_CRITICAL(&s_lock);
    light_segment_t *seg = seg_find_locked(seg_id, NULL);
    if (seg == NULL) {
        portEXIT_CRITICAL(&s_lock);
        return ESP_ERR_NOT_FOUND;
    }
    snprintf(buf, len, "%s", seg->name);
    portEXIT_CRITICAL(&s_lock);
    return ESP_OK;
}

esp_err_t light_seg_apply_effect(uint8_t seg_id, const segment_effect_t *effect, bool persist)
{
    if (effect == NULL || !effect_sane(effect)) {
        return ESP_ERR_INVALID_ARG;
    }
    portENTER_CRITICAL(&s_lock);
    light_segment_t *seg = seg_find_locked(seg_id, NULL);
    if (seg == NULL) {
        portEXIT_CRITICAL(&s_lock);
        return ESP_ERR_NOT_FOUND;
    }
    if (seg->effect.mode != effect->mode) {
        memset(&seg->anim, 0, sizeof(seg->anim));
    }
    seg->effect = *effect;
    portEXIT_CRITICAL(&s_lock);
    if (persist) {
        persist_seg(seg_id);
    }
    return ESP_OK;
}

esp_err_t light_seg_get_effect(uint8_t seg_id, segment_effect_t *out)
{
    if (out == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    portENTER_CRITICAL(&s_lock);
    light_segment_t *seg = seg_find_locked(seg_id, NULL);
    if (seg == NULL) {
        portEXIT_CRITICAL(&s_lock);
        return ESP_ERR_NOT_FOUND;
    }
    *out = seg->effect;
    portEXIT_CRITICAL(&s_lock);
    return ESP_OK;
}

esp_err_t light_seg_set_mode(uint8_t seg_id, light_mode_t mode)
{
    if (mode > LIGHT_MODE_FLASH) {
        return ESP_ERR_INVALID_ARG;
    }
    portENTER_CRITICAL(&s_lock);
    light_segment_t *seg = seg_find_locked(seg_id, NULL);
    if (seg == NULL) {
        portEXIT_CRITICAL(&s_lock);
        return ESP_ERR_NOT_FOUND;
    }
    if (seg->effect.mode != mode) {
        seg->effect.mode = mode;
        memset(&seg->anim, 0, sizeof(seg->anim));
    }
    portEXIT_CRITICAL(&s_lock);
    persist_seg(seg_id);
    return ESP_OK;
}

esp_err_t light_seg_set_brightness(uint8_t seg_id, uint8_t brightness)
{
    portENTER_CRITICAL(&s_lock);
    light_segment_t *seg = seg_find_locked(seg_id, NULL);
    if (seg == NULL) {
        portEXIT_CRITICAL(&s_lock);
        return ESP_ERR_NOT_FOUND;
    }
    seg->effect.brightness = brightness;
    portEXIT_CRITICAL(&s_lock);
    persist_seg(seg_id);
    return ESP_OK;
}

esp_err_t light_seg_get_common(uint8_t seg_id, light_mode_t *mode, uint8_t *brightness)
{
    portENTER_CRITICAL(&s_lock);
    light_segment_t *seg = seg_find_locked(seg_id, NULL);
    if (seg == NULL) {
        portEXIT_CRITICAL(&s_lock);
        return ESP_ERR_NOT_FOUND;
    }
    if (mode != NULL) {
        *mode = seg->effect.mode;
    }
    if (brightness != NULL) {
        *brightness = seg->effect.brightness;
    }
    portEXIT_CRITICAL(&s_lock);
    return ESP_OK;
}

static bool wave_ok(const wave_cfg_t *cfg)
{
    return cfg->speed_pct <= LIGHT_SPEED_MAX_PCT && cfg->dir <= LIGHT_DIR_LEFT;
}

static bool static_ok(const static_cfg_t *cfg)
{
    (void)cfg;
    return true;
}

static bool breath_ok(const breath_cfg_t *cfg)
{
    (void)cfg;
    return true;
}

static bool chase_ok(const chase_cfg_t *cfg)
{
    return cfg->count >= 1 && cfg->count <= LIGHT_COLOR_MAX &&
           cfg->speed_pct <= LIGHT_SPEED_MAX_PCT && cfg->dir <= LIGHT_DIR_LEFT;
}

static bool points_ok(const points_cfg_t *cfg)
{
    if (cfg->count < 1 || cfg->count > LIGHT_POINT_MAX) {
        return false;
    }
    for (uint8_t i = 0; i < cfg->count; i++) {
        const point_cfg_t *point = &cfg->points[i];
        if (point->position_pct > 100 || point->count < 1 || point->count > LIGHT_COLOR_MAX) {
            return false;
        }
    }
    return true;
}

static bool flash_ok(const flash_cfg_t *cfg)
{
    return cfg->mode <= FLASH_MODE_SWITCH;
}

static bool effect_sane(const segment_effect_t *cfg)
{
    return cfg->mode <= LIGHT_MODE_FLASH &&
           wave_ok(&cfg->wave) &&
           chase_ok(&cfg->chase) &&
           points_ok(&cfg->points) &&
           flash_ok(&cfg->flash);
}

/*
 * 创建时已经填好默认值。这里只在 NVS 里真有这份记录时覆盖。
 * 没有键、版本不对、内容不合法，都保持默认值。
 */
static void apply_stored(light_io_t *io)
{
    uint8_t brightness = 0;
    uint16_t refresh = 0;
    esp_err_t err = nvs_light_load_io(io->id, &brightness, &refresh);
    if (err == ESP_OK) {
        if (refresh < LIGHT_REFRESH_MIN_MS) {
            refresh = LIGHT_REFRESH_MIN_MS;
        } else if (refresh > LIGHT_REFRESH_MAX_MS) {
            refresh = LIGHT_REFRESH_MAX_MS;
        }
        /* 老固件可能把 5 ms 存进了 NVS；500 颗那条读到 5 会直接空转，必须夹。 */
        uint16_t floor_ms = light_refresh_floor_for(io->led_count, io->dma);
        if (refresh < floor_ms) {
            ESP_LOGW(TAG, "IO %u 存档刷新 %u ms 低于物理下限，改为 %u ms%s",
                     io->id, refresh, floor_ms, io->dma ? "" : "（非 DMA，48 符号补数）");
            refresh = floor_ms;
        }
        io->brightness = brightness;
        io->refresh_ms = refresh;
        ESP_LOGI(TAG, "IO %u 恢复亮度 %u、刷新 %u ms", io->id, brightness, refresh);
    }

    for (uint8_t i = 0; i < io->seg_count; i++) {
        segment_effect_t effect;
        err = nvs_light_load_seg(io->segs[i].id, &effect);
        if (err != ESP_OK) {
            continue;
        }
        if (!effect_sane(&effect)) {
            ESP_LOGW(TAG, "灯段 %u 存档无效，继续用默认值", io->segs[i].id);
            continue;
        }
        io->segs[i].effect = effect;
        ESP_LOGI(TAG, "灯段 %u 已从 NVS 恢复", io->segs[i].id);
    }
}

/* 最低优先级：Flash 写本来就会停 cache，别让它抢实时任务的 CPU */
static void light_persist_task(void *arg)
{
    (void)arg;
    while (1) {
        /*
         * 每来一次改动就重置等待，静默满 LIGHT_PERSIST_DELAY_MS 才真正写。
         * 拖一次滑条会产生几十次置脏，最后只落一次盘。
         */
        if (ulTaskNotifyTake(pdTRUE, pdMS_TO_TICKS(LIGHT_PERSIST_DELAY_MS)) == 0) {
            light_persist_flush();
        }
    }
}

esp_err_t light_persist_init(void)
{
    if (s_persist_task != NULL) {
        return ESP_OK;
    }
    /*
     * 必须钉在 core 0（和 RMT 中断同一个核）。
     * Flash 写期间 IDF 会关掉当前核的 code cache（IRAM 中断仍可运行），
     * 并把"另一个核"整体冻住。放到 core 1 反而会把 RMT 中断所在的
     * core 0 冻掉，48 符号的 60 us 补数立刻断流。
     */
    if (xTaskCreatePinnedToCore(light_persist_task, "light_persist", 4096, NULL, 1,
                                &s_persist_task, 0) != pdPASS) {
        s_persist_task = NULL;
        return ESP_ERR_NO_MEM;
    }
    return ESP_OK;
}

void light_persist_flush(void)
{
    uint8_t io_mask = s_dirty_io_mask;
    s_dirty_io_mask = 0;

    bool any_seg = false;
    for (int i = 0; i < 256; i++) {
        if (s_dirty_seg[i]) {
            any_seg = true;
            break;
        }
    }
    if (io_mask == 0 && !any_seg) {
        return;
    }

    nvs_handle_t handle;
    if (nvs_light_open(&handle) != ESP_OK) {
        /* 打不开就把脏标记放回去，下一轮再试 */
        s_dirty_io_mask |= io_mask;
        return;
    }

    /* 注意：NVS 调用不能放在 portENTER_CRITICAL 里，所以每次只在锁内拷贝一份 */
    for (uint8_t io = 1; io <= LIGHT_IO_MAX; io++) {
        if ((io_mask & (uint8_t)(1u << (io - 1))) == 0) {
            continue;
        }
        uint8_t brightness = 0;
        uint16_t refresh = 0;
        bool found = false;
        portENTER_CRITICAL(&s_lock);
        light_io_t *p = io_find_locked(io);
        if (p != NULL) {
            brightness = p->brightness;
            refresh = p->refresh_ms;
            found = true;
        }
        portEXIT_CRITICAL(&s_lock);
        if (found) {
            nvs_light_set_io(handle, io, brightness, refresh);
        }
    }

    for (int seg = 1; seg < 256; seg++) {
        if (!s_dirty_seg[seg]) {
            continue;
        }
        s_dirty_seg[seg] = false;
        segment_effect_t effect;
        bool found = false;
        portENTER_CRITICAL(&s_lock);
        light_segment_t *p = seg_find_locked((uint8_t)seg, NULL);
        if (p != NULL) {
            effect = p->effect;
            found = true;
        }
        portEXIT_CRITICAL(&s_lock);
        if (found) {
            nvs_light_set_seg(handle, (uint8_t)seg, &effect);
        }
    }

    /* 整批只落一次盘 */
    nvs_commit(handle);
    nvs_close(handle);
    ESP_LOGD(TAG, "批量落盘完成 io_mask=0x%02x", io_mask);
}

static void persist_io(uint8_t io_id)
{
    if (io_id < 1 || io_id > LIGHT_IO_MAX) {
        return;
    }
    s_dirty_io_mask |= (uint8_t)(1u << (io_id - 1));
    if (s_persist_task != NULL) {
        xTaskNotifyGive(s_persist_task);
    } else {
        light_persist_flush(); /* 没初始化就退回同步写，行为不变 */
    }
}

static void persist_seg(uint8_t seg_id)
{
    s_dirty_seg[seg_id] = true;
    if (s_persist_task != NULL) {
        xTaskNotifyGive(s_persist_task);
    } else {
        light_persist_flush();
    }
}

esp_err_t light_seg_get_wave(uint8_t seg_id, wave_cfg_t *out)
{
    if (out == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    portENTER_CRITICAL(&s_lock);
    light_segment_t *seg = seg_find_locked(seg_id, NULL);
    if (seg == NULL) {
        portEXIT_CRITICAL(&s_lock);
        return ESP_ERR_NOT_FOUND;
    }
    *out = seg->effect.wave;
    portEXIT_CRITICAL(&s_lock);
    return ESP_OK;
}

esp_err_t light_seg_set_wave(uint8_t seg_id, const wave_cfg_t *cfg)
{
    if (cfg == NULL || !wave_ok(cfg)) {
        return ESP_ERR_INVALID_ARG;
    }
    portENTER_CRITICAL(&s_lock);
    light_segment_t *seg = seg_find_locked(seg_id, NULL);
    if (seg == NULL) {
        portEXIT_CRITICAL(&s_lock);
        return ESP_ERR_NOT_FOUND;
    }
    seg->effect.wave = *cfg;
    portEXIT_CRITICAL(&s_lock);
    persist_seg(seg_id);
    return ESP_OK;
}

esp_err_t light_seg_get_static(uint8_t seg_id, static_cfg_t *out)
{
    if (out == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    portENTER_CRITICAL(&s_lock);
    light_segment_t *seg = seg_find_locked(seg_id, NULL);
    if (seg == NULL) {
        portEXIT_CRITICAL(&s_lock);
        return ESP_ERR_NOT_FOUND;
    }
    *out = seg->effect.solid;
    portEXIT_CRITICAL(&s_lock);
    return ESP_OK;
}

esp_err_t light_seg_set_static(uint8_t seg_id, const static_cfg_t *cfg)
{
    if (cfg == NULL || !static_ok(cfg)) {
        return ESP_ERR_INVALID_ARG;
    }
    portENTER_CRITICAL(&s_lock);
    light_segment_t *seg = seg_find_locked(seg_id, NULL);
    if (seg == NULL) {
        portEXIT_CRITICAL(&s_lock);
        return ESP_ERR_NOT_FOUND;
    }
    seg->effect.solid = *cfg;
    portEXIT_CRITICAL(&s_lock);
    persist_seg(seg_id);
    return ESP_OK;
}

esp_err_t light_seg_get_breath(uint8_t seg_id, breath_cfg_t *out)
{
    if (out == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    portENTER_CRITICAL(&s_lock);
    light_segment_t *seg = seg_find_locked(seg_id, NULL);
    if (seg == NULL) {
        portEXIT_CRITICAL(&s_lock);
        return ESP_ERR_NOT_FOUND;
    }
    *out = seg->effect.breath;
    portEXIT_CRITICAL(&s_lock);
    return ESP_OK;
}

esp_err_t light_seg_set_breath(uint8_t seg_id, const breath_cfg_t *cfg)
{
    if (cfg == NULL || !breath_ok(cfg)) {
        return ESP_ERR_INVALID_ARG;
    }
    portENTER_CRITICAL(&s_lock);
    light_segment_t *seg = seg_find_locked(seg_id, NULL);
    if (seg == NULL) {
        portEXIT_CRITICAL(&s_lock);
        return ESP_ERR_NOT_FOUND;
    }
    seg->effect.breath = *cfg;
    portEXIT_CRITICAL(&s_lock);
    persist_seg(seg_id);
    return ESP_OK;
}

esp_err_t light_seg_get_chase(uint8_t seg_id, chase_cfg_t *out)
{
    if (out == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    portENTER_CRITICAL(&s_lock);
    light_segment_t *seg = seg_find_locked(seg_id, NULL);
    if (seg == NULL) {
        portEXIT_CRITICAL(&s_lock);
        return ESP_ERR_NOT_FOUND;
    }
    *out = seg->effect.chase;
    portEXIT_CRITICAL(&s_lock);
    return ESP_OK;
}

esp_err_t light_seg_set_chase(uint8_t seg_id, const chase_cfg_t *cfg)
{
    if (cfg == NULL || !chase_ok(cfg)) {
        return ESP_ERR_INVALID_ARG;
    }
    portENTER_CRITICAL(&s_lock);
    light_segment_t *seg = seg_find_locked(seg_id, NULL);
    if (seg == NULL) {
        portEXIT_CRITICAL(&s_lock);
        return ESP_ERR_NOT_FOUND;
    }
    seg->effect.chase = *cfg;
    portEXIT_CRITICAL(&s_lock);
    persist_seg(seg_id);
    return ESP_OK;
}

esp_err_t light_seg_get_points(uint8_t seg_id, points_cfg_t *out)
{
    if (out == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    portENTER_CRITICAL(&s_lock);
    light_segment_t *seg = seg_find_locked(seg_id, NULL);
    if (seg == NULL) {
        portEXIT_CRITICAL(&s_lock);
        return ESP_ERR_NOT_FOUND;
    }
    *out = seg->effect.points;
    portEXIT_CRITICAL(&s_lock);
    return ESP_OK;
}

esp_err_t light_seg_set_points(uint8_t seg_id, const points_cfg_t *cfg)
{
    if (cfg == NULL || !points_ok(cfg)) {
        return ESP_ERR_INVALID_ARG;
    }
    portENTER_CRITICAL(&s_lock);
    light_segment_t *seg = seg_find_locked(seg_id, NULL);
    if (seg == NULL) {
        portEXIT_CRITICAL(&s_lock);
        return ESP_ERR_NOT_FOUND;
    }
    seg->effect.points = *cfg;
    portEXIT_CRITICAL(&s_lock);
    persist_seg(seg_id);
    return ESP_OK;
}

esp_err_t light_seg_get_flash(uint8_t seg_id, flash_cfg_t *out)
{
    if (out == NULL) {
        return ESP_ERR_INVALID_ARG;
    }
    portENTER_CRITICAL(&s_lock);
    light_segment_t *seg = seg_find_locked(seg_id, NULL);
    if (seg == NULL) {
        portEXIT_CRITICAL(&s_lock);
        return ESP_ERR_NOT_FOUND;
    }
    *out = seg->effect.flash;
    portEXIT_CRITICAL(&s_lock);
    return ESP_OK;
}

esp_err_t light_seg_set_flash(uint8_t seg_id, const flash_cfg_t *cfg)
{
    if (cfg == NULL || !flash_ok(cfg)) {
        return ESP_ERR_INVALID_ARG;
    }
    portENTER_CRITICAL(&s_lock);
    light_segment_t *seg = seg_find_locked(seg_id, NULL);
    if (seg == NULL) {
        portEXIT_CRITICAL(&s_lock);
        return ESP_ERR_NOT_FOUND;
    }
    seg->effect.flash = *cfg;
    portEXIT_CRITICAL(&s_lock);
    persist_seg(seg_id);
    return ESP_OK;
}

uint8_t light_io_count(void)
{
    uint8_t n = 0;
    portENTER_CRITICAL(&s_lock);
    for (uint8_t i = 0; i < LIGHT_IO_MAX; i++) {
        if (s_ios[i].used) {
            n++;
        }
    }
    portEXIT_CRITICAL(&s_lock);
    return n;
}

/*
 * 拓扑包，小端：
 *   u8  io_count
 *   重复 io_count 次：
 *     u8  io_id
 *     u8  name_len
 *     u8  name[name_len]     IO 名，UTF-8，不含结尾 0（v0.3 新增）
 *     u8  brightness
 *     u16 refresh_ms
 *     u16 led_count
 *     u8  seg_count
 *     重复 seg_count 次：
 *       u8  seg_id
 *       u16 length
 *       u8  name_len
 *       u8  name[name_len]   UTF-8，不含结尾 0
 */
uint16_t light_topology_pack(uint8_t *buf, uint16_t cap)
{
    if (buf == NULL || cap < 1) {
        return 0;
    }

    portENTER_CRITICAL(&s_lock);
    uint16_t used = 1;
    uint8_t io_count = 0;
    buf[0] = 0;
    for (uint8_t i = 0; i < LIGHT_IO_MAX; i++) {
        light_io_t *io = &s_ios[i];
        if (!io->used) {
            continue;
        }
        uint8_t io_name_len = (uint8_t)strnlen(io->name, LIGHT_NAME_MAX - 1);
        uint16_t need = 1 + 1 + io_name_len + 1 + 2 + 2 + 1;
        for (uint8_t s = 0; s < io->seg_count; s++) {
            size_t name_len = strnlen(io->segs[s].name, LIGHT_NAME_MAX - 1);
            need = (uint16_t)(need + 1 + 2 + 1 + name_len);
        }
        if ((uint32_t)used + need > cap) {
            portEXIT_CRITICAL(&s_lock);
            return 0;
        }
        buf[used++] = io->id;
        buf[used++] = io_name_len;
        memcpy(&buf[used], io->name, io_name_len);
        used = (uint16_t)(used + io_name_len);
        buf[used++] = io->brightness;
        memcpy(&buf[used], &io->refresh_ms, sizeof(io->refresh_ms));
        used = (uint16_t)(used + sizeof(io->refresh_ms));
        memcpy(&buf[used], &io->led_count, sizeof(io->led_count));
        used = (uint16_t)(used + sizeof(io->led_count));
        buf[used++] = io->seg_count;
        for (uint8_t s = 0; s < io->seg_count; s++) {
            light_segment_t *seg = &io->segs[s];
            uint8_t name_len = (uint8_t)strnlen(seg->name, LIGHT_NAME_MAX - 1);
            buf[used++] = seg->id;
            memcpy(&buf[used], &seg->length, sizeof(seg->length));
            used = (uint16_t)(used + sizeof(seg->length));
            buf[used++] = name_len;
            memcpy(&buf[used], seg->name, name_len);
            used = (uint16_t)(used + name_len);
        }
        io_count++;
    }
    buf[0] = io_count;
    portEXIT_CRITICAL(&s_lock);
    return used;
}
