/**
 * @file local_clock.c
 * @author LG_frir
 * @date 2026-11-12
 */

#include "driver/timer/local_clock.h"

#include "freertos/FreeRTOS.h"
#include "esp_timer.h"

#include <sys/time.h>

static portMUX_TYPE s_lock = portMUX_INITIALIZER_UNLOCKED;
static int64_t s_epoch_us;   /* 对时那一刻的 UTC 微秒 */
static int64_t s_ref_us;     /* 对时那一刻的 esp_timer 微秒 */
static bool s_valid;

void local_clock_init(void)
{
    portENTER_CRITICAL(&s_lock);
    s_valid = false;
    s_epoch_us = 0;
    s_ref_us = 0;
    portEXIT_CRITICAL(&s_lock);
}

void local_clock_set(int64_t epoch_us)
{
    portENTER_CRITICAL(&s_lock);
    s_epoch_us = epoch_us;
    s_ref_us = esp_timer_get_time();
    s_valid = true;
    portEXIT_CRITICAL(&s_lock);

    /* 系统时间也同步一份，串口日志和 time() 看起来一致 */
    struct timeval tv = {
        .tv_sec = (time_t)(epoch_us / 1000000),
        .tv_usec = (suseconds_t)(epoch_us % 1000000),
    };
    settimeofday(&tv, NULL);
}

bool local_clock_valid(void)
{
    portENTER_CRITICAL(&s_lock);
    bool valid = s_valid;
    portEXIT_CRITICAL(&s_lock);
    return valid;
}

int64_t local_clock_now_us(void)
{
    portENTER_CRITICAL(&s_lock);
    int64_t base = s_epoch_us;
    int64_t ref = s_ref_us;
    bool valid = s_valid;
    portEXIT_CRITICAL(&s_lock);
    if (!valid) {
        return 0;
    }
    return base + (esp_timer_get_time() - ref);
}

time_t local_clock_now_sec(void)
{
    int64_t us = local_clock_now_us();
    return us > 0 ? (time_t)(us / 1000000) : 0;
}

void local_clock_local_tm(struct tm *out)
{
    if (out == NULL) {
        return;
    }
    time_t now = local_clock_now_sec();
    localtime_r(&now, out);
}
