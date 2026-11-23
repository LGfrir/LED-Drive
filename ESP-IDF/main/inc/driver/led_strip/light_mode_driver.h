/**
 * @file light_mode_driver.h
 * @author LG_frir
 * @date 2026-11-11 17:53:55
 * @brief 一个 IO 一条灯珠数组。上面切成若干灯段，每段一份灯效配置。
 *
 * 刷新率和亮度挂在 IO 上。灯段还有自己的亮度。
 * 灯效先把原始颜色写入本段区间，整帧画完后统一乘
 * 灯段亮度 * IO 亮度 / 255 / 255，再发送。原始缓存不原地缩放。
 */

#ifndef __LIGHT_MODE_DRIVER_H__
#define __LIGHT_MODE_DRIVER_H__

#include "driver/led_strip/light_mode.h"
#include "driver/ws2812/ws2812_rmt.h"

#include <stddef.h>

#include "esp_err.h"

#define LIGHT_IO_MAX          4
#define LIGHT_SEG_MAX         16
#define LIGHT_REFRESH_MIN_MS  10
#define LIGHT_REFRESH_MAX_MS  50

typedef esp_err_t (*light_frame_show_fn)(void *ctx, const color_t *pixels, uint16_t count);

typedef struct {
    uint8_t id;
    uint16_t length;
    const char *name;
} light_seg_desc_t;

/*
 * 新建一个 IO。segs 按数据线方向从前到后排，长度之和不能超过 led_count。
 * 多出来的灯珠保持灭。灯段 ID 全机唯一。成功后自己起刷新任务。
 *
 * dma：这一路是否有硬件 DMA（RMT DMA 或 SPI DMA）。
 *      true 时刷新下限只按物理发送时间算；false 才按 48 符号补数的中断率抬高。
 */
esp_err_t light_io_create(uint8_t io_id, const char *name, uint16_t led_count,
                          uint8_t brightness, uint16_t refresh_ms,
                          light_frame_show_fn show, void *show_ctx,
                          const light_seg_desc_t *segs, uint8_t seg_count,
                          bool dma);

/* 把一段 RGB 交给 RMT 灯带发出去。show_ctx 是 ws2812_rmt_handle_t *。 */
esp_err_t light_show_rmt(void *ctx, const color_t *pixels, uint16_t count);

/* 把一段 RGB 交给 SPI 灯带发出去。show_ctx 是 ws2812_spi_handle_t *。 */
esp_err_t light_show_spi(void *ctx, const color_t *pixels, uint16_t count);

esp_err_t light_io_set_brightness(uint8_t io_id, uint8_t brightness);
esp_err_t light_io_set_refresh(uint8_t io_id, uint16_t refresh_ms);
esp_err_t light_io_get(uint8_t io_id, uint8_t *brightness, uint16_t *refresh_ms, uint16_t *led_count);

/* 最近一帧按实际 RGB 估算的 12V 功率（mW，全白 160 颗 = 12000） */
esp_err_t light_io_get_power_mw(uint8_t io_id, uint32_t *mw);

/* 时间模式未对时的时候整条 IO 静默：只发全 0，不跑灯效 */
esp_err_t light_io_set_mute(uint8_t io_id, bool mute);

/* 所有灯段的动画相位同时归零，流水灯才能前后接上。 */
void light_reset_phase(void);

/* 切回手动时，把各灯段恢复成 NVS 里的手动灯效。时间调度写灯效时不落盘。 */
esp_err_t light_io_restore_manual(uint8_t io_id);

/* 进入定时前记下当前灯效。切回手动时优先用这份，不再逐段读闪存。 */
esp_err_t light_io_hold_manual(uint8_t io_id);
esp_err_t light_io_recall_manual(uint8_t io_id);
bool light_io_is_muted(uint8_t io_id);

/* 列出已存在的 IO / 某个 IO 下的灯段 id */
esp_err_t light_io_list(uint8_t *ids, uint8_t cap, uint8_t *count);
esp_err_t light_io_seg_list(uint8_t io_id, uint8_t *ids, uint8_t cap, uint8_t *count);
esp_err_t light_seg_get_io(uint8_t seg_id, uint8_t *io_id);
esp_err_t light_seg_get_name(uint8_t seg_id, char *buf, size_t len);

/* 整份覆盖一个灯段的 effect（预设切换用）。persist=false 时不写 NVS */
esp_err_t light_seg_apply_effect(uint8_t seg_id, const segment_effect_t *effect, bool persist);
esp_err_t light_seg_get_effect(uint8_t seg_id, segment_effect_t *out);

esp_err_t light_seg_set_mode(uint8_t seg_id, light_mode_t mode);
esp_err_t light_seg_set_brightness(uint8_t seg_id, uint8_t brightness);
esp_err_t light_seg_get_common(uint8_t seg_id, light_mode_t *mode, uint8_t *brightness);

esp_err_t light_seg_get_wave(uint8_t seg_id, wave_cfg_t *out);
esp_err_t light_seg_set_wave(uint8_t seg_id, const wave_cfg_t *cfg);
esp_err_t light_seg_get_static(uint8_t seg_id, static_cfg_t *out);
esp_err_t light_seg_set_static(uint8_t seg_id, const static_cfg_t *cfg);
esp_err_t light_seg_get_breath(uint8_t seg_id, breath_cfg_t *out);
esp_err_t light_seg_set_breath(uint8_t seg_id, const breath_cfg_t *cfg);
esp_err_t light_seg_get_chase(uint8_t seg_id, chase_cfg_t *out);
esp_err_t light_seg_set_chase(uint8_t seg_id, const chase_cfg_t *cfg);
esp_err_t light_seg_get_points(uint8_t seg_id, points_cfg_t *out);
esp_err_t light_seg_set_points(uint8_t seg_id, const points_cfg_t *cfg);
esp_err_t light_seg_get_flash(uint8_t seg_id, flash_cfg_t *out);
esp_err_t light_seg_set_flash(uint8_t seg_id, const flash_cfg_t *cfg);

uint8_t light_io_count(void);

/*
 * 这一路物理上可行的最小刷新间隔（ms）。
 * WS2812 一位 1.25 us，一颗灯 24 位 = 30 us，所以 N 颗灯光发送就要 N*30 us。
 * dma=false 时还要额外抬高：48 符号一块只有 60 us 的补数截止期，
 * 每帧要补 N*24/48 次，帧率太高就会把中断率顶上去。详见实现里的注释。
 */
uint16_t light_refresh_floor_for(uint16_t led_count, bool dma);

/*
 * 掉电保存改成"攒批落盘"。
 * 原来每改一项就 nvs_open + set + commit + close 一次，
 * 拖一次亮度滑条就是一次 Flash 写（期间 code cache 关闭，RMT 断流）。
 * 现在所有改动只置脏标记，由最低优先级的 light_persist 任务在静默
 * LIGHT_PERSIST_DELAY_MS 之后一次性写回，整批只 commit 一次。
 * 必须在灯带创建之后调用一次。
 */
esp_err_t light_persist_init(void);

/* 立刻把脏数据落盘（断开连接 / 关机前可以主动调一次） */
void light_persist_flush(void);

/*
 * 把当前正在用的 IO 和灯段打成一包，给手机生成两层界面。
 * 格式见 light_mode_driver.c 里的说明。装不下返回 0。
 */
uint16_t light_topology_pack(uint8_t *buf, uint16_t cap);

#endif /* __LIGHT_MODE_DRIVER_H__ */
