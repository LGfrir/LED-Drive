/**
 * @file clock_scheduler.h
 * @author LG_frir
 * @date 2026-11-12
 * @brief 时间调度。1 秒一轮，比对"当前槽位该用哪个预设"，不同才切换。
 *
 * 调度预设（闹钟组）只描述时间点；每个灯段挂一组调度，
 * 再给每个时间点指定一个灯效预设 id。多个灯段可以共用一个调度组。
 */

#ifndef __CLOCK_SCHEDULER_H__
#define __CLOCK_SCHEDULER_H__

#include <stdbool.h>
#include <stdint.h>

#include "driver/preset/light_preset.h"
#include "esp_err.h"

#define CLOCK_SCHED_MAX   8
#define CLOCK_SLOT_MAX    8
#define CLOCK_IO_MAX      4
#define CLOCK_SEG_MAX     32

typedef enum {
    CLOCK_MODE_MANUAL = 0,   /* 手机直接操作，和以前一样 */
    CLOCK_MODE_TIME   = 1,   /* 跟时间调度走 */
} clock_io_mode_t;

typedef struct {
    uint8_t id;
    char name[LIGHT_PRESET_NAME_MAX];
    uint8_t slot_count;
    uint32_t at_sec[CLOCK_SLOT_MAX];   /* 一天内的秒，升序 */
} clock_sched_t;

/* 开机调用：读 NVS、起 1 Hz 任务、按当前模式决定要不要静默 */
esp_err_t clock_scheduler_init(void);

/* 对时成功后调用：立刻按当前时间重算一次 */
void clock_scheduler_on_time_synced(void);

/* 每个 IO 独立模式。只改内存里的模式，不写闪存、不换灯效。 */
esp_err_t clock_io_mode_set(uint8_t io_id, clock_io_mode_t mode);
clock_io_mode_t clock_io_mode_get(uint8_t io_id);

/* 通知调度任务去换灯、稍后写闪存。要在蓝牙应答发出去之后再调。 */
void clock_scheduler_kick(void);

/* 调度组。id 传 0 表示自动分配；out_id 返回实际 id */
esp_err_t clock_sched_set(uint8_t id, const clock_sched_t *in, uint8_t *out_id);
esp_err_t clock_sched_get(uint8_t id, clock_sched_t *out);
esp_err_t clock_sched_delete(uint8_t id);
uint8_t clock_sched_list(uint8_t *ids, uint8_t cap);

/* 灯段绑定：挂哪组调度，每个时间点用哪个预设（0xFF = 关灯） */
esp_err_t clock_seg_bind_set(uint8_t seg_id, uint8_t sched_id,
                             const uint8_t *preset_map, uint8_t slot_count);
esp_err_t clock_seg_bind_get(uint8_t seg_id, uint8_t *sched_id,
                             uint8_t *preset_map, uint8_t *slot_count);
esp_err_t clock_seg_unbind(uint8_t seg_id);

/*
 * 时间模式下手机改了某个灯段 -> 自动进入草稿态，调度器暂停接管这一段。
 * 改完由 App 发 commit：save=1 存成新预设并绑到当前槽，save=0 丢弃。
 */
void clock_seg_local_write(uint8_t seg_id);
esp_err_t clock_seg_commit(uint8_t seg_id, bool save_as_preset, uint8_t *out_preset);
void clock_seg_cancel(uint8_t seg_id);
void clock_seg_cancel_all(void);
bool clock_seg_is_editing(uint8_t seg_id);

/* 状态查询 */
uint8_t clock_seg_active_preset(uint8_t seg_id);
uint32_t clock_next_event_sec(void);
bool clock_time_valid(void);

/* 灯段被删除/重新绑定后清掉运行态 */
void clock_seg_forget(uint8_t seg_id);

/*
 * 某个预设被哪些灯段使用，写成多行文本：
 *   第一行是总数，后面每行 "IO号,灯段名"。
 * 返回写入的字节数（不含结尾 0）。
 */
uint16_t clock_preset_usage(uint8_t preset_id, char *buf, uint16_t cap);

/* 预设被删掉时，所有引用它的槽位改成 new_id */
void clock_preset_replace(uint8_t old_id, uint8_t new_id);

/*
 * 某套调度被哪些灯段使用，格式同 clock_preset_usage。
 */
uint16_t clock_sched_usage(uint8_t sched_id, char *buf, uint16_t cap);

#endif /* __CLOCK_SCHEDULER_H__ */
