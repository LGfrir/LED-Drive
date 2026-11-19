/**
 * @file local_clock.h
 * @author LG_frir
 * @date 2026-11-12
 * @brief 本地走时。对时之后以 esp_timer 单调推进，不受 settimeofday 跳变影响。
 *
 * 和同步无关：time_sync 只负责把手机时间喂进来，之后走时靠这里。
 */

#ifndef __LOCAL_CLOCK_H__
#define __LOCAL_CLOCK_H__

#include <stdbool.h>
#include <stdint.h>
#include <time.h>

/* 清掉本地时间，回到未对时状态 */
void local_clock_init(void);

/* 对时：epoch_us 是 UTC 微秒。同时把系统时间也设成这个值 */
void local_clock_set(int64_t epoch_us);

/* 有没有对过时 */
bool local_clock_valid(void);

/* 当前 UTC 微秒；未对时返回 0 */
int64_t local_clock_now_us(void);

/* 当前 UTC 秒；未对时返回 0 */
time_t local_clock_now_sec(void);

/* 按 TZ（main 里设成 CST-8）转成本地时间 */
void local_clock_local_tm(struct tm *out);

#endif /* __LOCAL_CLOCK_H__ */
