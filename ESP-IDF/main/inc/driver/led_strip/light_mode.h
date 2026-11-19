/**
 * @file light_mode.h
 * @author LG_frir
 * @date 2026-11-11 17:52:55
 * @brief 灯段上的六种灯效。只往一段像素里写原始 RGB，不负责亮度和发送。
 */

#ifndef __LIGHT_MODE_H__
#define __LIGHT_MODE_H__

#include <stdbool.h>
#include <stdint.h>

#define LIGHT_COLOR_MAX     8
#define LIGHT_POINT_MAX     8
#define LIGHT_NAME_MAX      32
#define LIGHT_SPEED_MAX_PCT 1000   /* 100 = 一秒走完这段一次，允许更快 */

typedef struct color {
    uint8_t r;
    uint8_t g;
    uint8_t b;
} color_t;

typedef enum {
    LIGHT_DIR_RIGHT = 0,   /* 下标增大，远离 DIN */
    LIGHT_DIR_LEFT  = 1,
} light_dir_t;

typedef enum {
    LIGHT_MODE_OFF = 0,
    LIGHT_MODE_WAVE,          /* 随波逐流 */
    LIGHT_MODE_STATIC,        /* 常亮 */
    LIGHT_MODE_BREATH,        /* 呼吸 */
    LIGHT_MODE_CHASE,         /* 追逐 */
    LIGHT_MODE_POINTS,        /* 多点渐变 */
    LIGHT_MODE_FLASH,         /* 爆闪 */
} light_mode_t;

typedef enum {
    FLASH_MODE_BLINK = 0,     /* 周期对半：前一半亮，后一半灭 */
    FLASH_MODE_SWITCH = 1,    /* 不灭，每一帧换一屏新随机色 */
} flash_mode_t;

typedef struct {
    uint16_t speed_pct;
    light_dir_t dir;
} wave_cfg_t;

typedef struct {
    color_t color;
} static_cfg_t;

typedef struct {
    color_t color;
    uint16_t period_ms;       /* 亮到灭再回到亮的整圈时间 */
} breath_cfg_t;

typedef struct {
    uint8_t count;
    light_dir_t dir;
    uint16_t speed_pct;
    color_t colors[LIGHT_COLOR_MAX];
} chase_cfg_t;

typedef struct {
    uint8_t position_pct;     /* 0..100，相对这一段的长度 */
    uint8_t count;
    uint16_t period_ms;       /* 颜色数组转完一圈的时间 */
    color_t colors[LIGHT_COLOR_MAX];
} point_cfg_t;

typedef struct {
    uint8_t count;
    bool wrap;                /* 首尾相连时，最后一点和第一点之间也渐变 */
    point_cfg_t points[LIGHT_POINT_MAX];
} points_cfg_t;

typedef struct {
    flash_mode_t mode;
    uint16_t period_ms;       /* 只有会灭模式用 */
} flash_cfg_t;

typedef struct {
    light_mode_t mode;
    uint8_t brightness;       /* 灯段亮度，和 IO 亮度分开乘 */
    wave_cfg_t wave;
    static_cfg_t solid;
    breath_cfg_t breath;
    chase_cfg_t chase;
    points_cfg_t points;
    flash_cfg_t flash;
} segment_effect_t;

/* 动画相位。和配置分开，改速度不会把播放进度清掉。 */
typedef struct {
    float phase;
    float breath_phase;
    float point_phase[LIGHT_POINT_MAX];
    uint32_t flash_ms;
} segment_anim_t;

/*
 * 按 cfg 把这一段画进 pixels。count 是本段实际灯珠数。
 * 逻辑长度超出 count 的部分直接不画。delta_ms 是距离上一帧的毫秒数。
 */
void light_effect_render(const segment_effect_t *cfg, segment_anim_t *anim,
                         color_t *pixels, uint16_t count, uint32_t delta_ms);

#endif /* __LIGHT_MODE_H__ */
