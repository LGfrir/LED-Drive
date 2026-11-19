/**
 * @file light_mode.c
 * @author LG_frir
 * @date 2026-11-11 17:52:59
 */

#include "driver/led_strip/light_mode.h"

#include <math.h>
#include <string.h>

#include "esp_random.h"

#define LIGHT_PI 3.14159265f

static float wrap01(float x)
{
    x = x - floorf(x);
    if (x < 0.f) {
        x += 1.f;
    }
    return x;
}

/* 三个通道的平方和。两个通道同时顶满时这个值会翻倍，灯就发白。 */
static float color_energy(color_t c)
{
    float r = (float)c.r;
    float g = (float)c.g;
    float b = (float)c.b;
    return r * r + g * g + b * b;
}

/*
 * 先按距离对两侧 RGB 取平均，得到中间灯的颜色方向。
 * 再把 R^2+G^2+B^2 拉回到两侧能量的插值，不让中间灯比两侧更亮。
 * 某一通道超过 255 时整色一起缩小，避免单独削顶把颜色削白。
 * 两端直接返回原色。
 */
static color_t color_lerp(color_t a, color_t b, float t)
{
    if (t <= 0.f) {
        return a;
    }
    if (t >= 1.f) {
        return b;
    }

    float r = (float)a.r + ((float)b.r - (float)a.r) * t;
    float g = (float)a.g + ((float)b.g - (float)a.g) * t;
    float bl = (float)a.b + ((float)b.b - (float)a.b) * t;
    float energy = color_energy(a) + (color_energy(b) - color_energy(a)) * t;
    float now = r * r + g * g + bl * bl;
    if (now <= 0.5f || energy <= 0.5f) {
        return (color_t){ 0, 0, 0 };
    }

    float scale = sqrtf(energy / now);
    r *= scale;
    g *= scale;
    bl *= scale;

    float peak = r;
    if (g > peak) {
        peak = g;
    }
    if (bl > peak) {
        peak = bl;
    }
    if (peak > 255.f) {
        float fit = 255.f / peak;
        r *= fit;
        g *= fit;
        bl *= fit;
    }

    color_t c = {
        .r = (uint8_t)(r + 0.5f),
        .g = (uint8_t)(g + 0.5f),
        .b = (uint8_t)(bl + 0.5f),
    };
    return c;
}

static color_t color_scale(color_t c, float gain)
{
    if (gain < 0.f) {
        gain = 0.f;
    } else if (gain > 1.f) {
        gain = 1.f;
    }
    c.r = (uint8_t)((float)c.r * gain + 0.5f);
    c.g = (uint8_t)((float)c.g * gain + 0.5f);
    c.b = (uint8_t)((float)c.b * gain + 0.5f);
    return c;
}

/* hue 走完 0~1 是一整圈彩虹。 */
static color_t color_from_hue(float hue)
{
    float h = wrap01(hue) * 6.f;
    int region = (int)h;
    float frac = h - (float)region;
    float r = 0.f;
    float g = 0.f;
    float b = 0.f;

    switch (region) {
    case 0: r = 1.f; g = frac; b = 0.f; break;
    case 1: r = 1.f - frac; g = 1.f; b = 0.f; break;
    case 2: r = 0.f; g = 1.f; b = frac; break;
    case 3: r = 0.f; g = 1.f - frac; b = 1.f; break;
    case 4: r = frac; g = 0.f; b = 1.f; break;
    default: r = 1.f; g = 0.f; b = 1.f - frac; break;
    }

    color_t c = {
        .r = (uint8_t)(r * 255.f + 0.5f),
        .g = (uint8_t)(g * 255.f + 0.5f),
        .b = (uint8_t)(b * 255.f + 0.5f),
    };
    return c;
}

static void clear_pixels(color_t *pixels, uint16_t count)
{
    memset(pixels, 0, (size_t)count * sizeof(color_t));
}

static float led_pos(uint16_t index, uint16_t count)
{
    if (count <= 1) {
        return 0.f;
    }
    return ((float)index + 0.5f) / (float)count;
}

static void advance_phase(float *phase, uint16_t speed_pct, light_dir_t dir, uint32_t delta_ms)
{
    float step = ((float)speed_pct / 100.f) * ((float)delta_ms / 1000.f);
    if (dir == LIGHT_DIR_LEFT) {
        *phase = wrap01(*phase - step);
    } else {
        *phase = wrap01(*phase + step);
    }
}

/*
 * 随波逐流：一整圈色相铺在这一段上，再按速度平移。
 * 右移时，某一颗灯下一帧会拿到它左边那颗原来的颜色。
 * 用灯珠中心 (i+0.5)/count，色相可以落在两颗灯的中间。
 */
static void render_wave(const wave_cfg_t *cfg, segment_anim_t *anim,
                        color_t *pixels, uint16_t count, uint32_t delta_ms)
{
    advance_phase(&anim->phase, cfg->speed_pct, cfg->dir, delta_ms);
    for (uint16_t i = 0; i < count; i++) {
        float u = led_pos(i, count);
        pixels[i] = color_from_hue(u - anim->phase);
    }
}

static void render_static(const static_cfg_t *cfg, color_t *pixels, uint16_t count)
{
    for (uint16_t i = 0; i < count; i++) {
        pixels[i] = cfg->color;
    }
}

/* 一个周期是亮 -> 灭 -> 亮。相位 0 为满色，0.5 为黑。 */
static void render_breath(const breath_cfg_t *cfg, segment_anim_t *anim,
                          color_t *pixels, uint16_t count, uint32_t delta_ms)
{
    if (cfg->period_ms == 0) {
        render_static(&(static_cfg_t){ .color = cfg->color }, pixels, count);
        return;
    }
    anim->breath_phase = wrap01(anim->breath_phase + (float)delta_ms / (float)cfg->period_ms);
    float gain = 0.5f * (1.f + cosf(anim->breath_phase * 2.f * LIGHT_PI));
    color_t c = color_scale(cfg->color, gain);
    for (uint16_t i = 0; i < count; i++) {
        pixels[i] = c;
    }
}

/*
 * 追逐：n 个色标均匀钉在圆环上，一起平移。
 * 色标坐标是小数。每颗灯找到圆环上紧挨着它的前后两个色标，
 * 按到这两个色标的距离做渐变。相邻色标的间隔始终是 1/n。
 */
static void render_chase(const chase_cfg_t *cfg, segment_anim_t *anim,
                         color_t *pixels, uint16_t count, uint32_t delta_ms)
{
    if (cfg->count == 0 || cfg->count > LIGHT_COLOR_MAX) {
        clear_pixels(pixels, count);
        return;
    }
    advance_phase(&anim->phase, cfg->speed_pct, cfg->dir, delta_ms);

    const uint8_t n = cfg->count;
    /* 方向已经写进 phase，这里只沿相位平移色标 */
    const float shift = anim->phase;
    for (uint16_t i = 0; i < count; i++) {
        float u = led_pos(i, count);
        int prev = 0;
        float prev_d = 2.f;
        for (uint8_t k = 0; k < n; k++) {
            float pos = wrap01((float)k / (float)n + shift);
            float d = u - pos;
            if (d < 0.f) {
                d += 1.f;
            }
            if (d < prev_d) {
                prev_d = d;
                prev = (int)k;
            }
        }
        int next = (prev + 1) % n;
        float span = 1.f / (float)n;
        float t = (span <= 0.f) ? 0.f : prev_d / span;
        pixels[i] = color_lerp(cfg->colors[prev], cfg->colors[next], t);
    }
}

/* 一个色标此刻的颜色：数组在 period_ms 里转完一圈，相邻颜色之间交叉渐变。 */
static color_t point_color_now(const point_cfg_t *point, float phase)
{
    if (point->count == 0) {
        return (color_t){ 0, 0, 0 };
    }
    if (point->count == 1) {
        return point->colors[0];
    }
    float x = wrap01(phase) * (float)point->count;
    int idx = (int)x;
    if (idx >= point->count) {
        idx = 0;
    }
    float t = x - (float)idx;
    return color_lerp(point->colors[idx], point->colors[(idx + 1) % point->count], t);
}

/*
 * 多点渐变。只有给出的那些百分比位置自带颜色数组，其余灯珠没有。
 * 每颗灯用自己的位置（0~1）去找左右两个色标，按小数距离取色。
 * wrap 为假时，第一个色标之前、最后一个色标之后保持端点颜色，不再绕回。
 */
static void render_points(const points_cfg_t *cfg, segment_anim_t *anim,
                          color_t *pixels, uint16_t count, uint32_t delta_ms)
{
    if (cfg->count == 0 || cfg->count > LIGHT_POINT_MAX) {
        clear_pixels(pixels, count);
        return;
    }

    uint8_t order[LIGHT_POINT_MAX];
    uint8_t n = cfg->count;
    for (uint8_t i = 0; i < n; i++) {
        order[i] = i;
        const point_cfg_t *point = &cfg->points[i];
        float step = (point->period_ms == 0) ? 0.f : (float)delta_ms / (float)point->period_ms;
        anim->point_phase[i] = wrap01(anim->point_phase[i] + step);
    }
    for (uint8_t i = 1; i < n; i++) {
        uint8_t key = order[i];
        float key_pos = (float)cfg->points[key].position_pct;
        int j = (int)i - 1;
        while (j >= 0 && (float)cfg->points[order[j]].position_pct > key_pos) {
            order[j + 1] = order[j];
            j--;
        }
        order[j + 1] = key;
    }

    color_t now[LIGHT_POINT_MAX];
    float pos[LIGHT_POINT_MAX];
    for (uint8_t i = 0; i < n; i++) {
        uint8_t src = order[i];
        now[i] = point_color_now(&cfg->points[src], anim->point_phase[src]);
        pos[i] = (float)cfg->points[src].position_pct / 100.f;
        if (pos[i] < 0.f) {
            pos[i] = 0.f;
        } else if (pos[i] > 1.f) {
            pos[i] = 1.f;
        }
    }

    for (uint16_t led = 0; led < count; led++) {
        float u = (count <= 1) ? 0.f : (float)led / (float)(count - 1);
        if (!cfg->wrap) {
            if (u <= pos[0]) {
                pixels[led] = now[0];
                continue;
            }
            if (u >= pos[n - 1]) {
                pixels[led] = now[n - 1];
                continue;
            }
            uint8_t k = 0;
            while (k + 1 < n && pos[k + 1] < u) {
                k++;
            }
            float span = pos[k + 1] - pos[k];
            float t = (span <= 0.0001f) ? 0.f : (u - pos[k]) / span;
            pixels[led] = color_lerp(now[k], now[k + 1], t);
            continue;
        }

        int prev = 0;
        float prev_d = 2.f;
        for (uint8_t k = 0; k < n; k++) {
            float d = u - pos[k];
            if (d < 0.f) {
                d += 1.f;
            }
            if (d < prev_d) {
                prev_d = d;
                prev = (int)k;
            }
        }
        int next = (prev + 1) % n;
        float span = pos[next] - pos[prev];
        if (span < 0.f) {
            span += 1.f;
        }
        float t = (span <= 0.0001f) ? 0.f : prev_d / span;
        pixels[led] = color_lerp(now[prev], now[next], t);
    }
}

/*
 * 爆闪。会灭模式用周期对半：前一半每一帧都重新随机，后一半全灭。
 * 直接切换没有灭，每一帧换一屏，周期不参与。随机色按灯珠各自生成。
 */
static void render_flash(const flash_cfg_t *cfg, segment_anim_t *anim,
                         color_t *pixels, uint16_t count, uint32_t delta_ms)
{
    anim->flash_ms += delta_ms;
    bool on = true;
    if (cfg->mode == FLASH_MODE_BLINK && cfg->period_ms > 0) {
        uint32_t pos = anim->flash_ms % cfg->period_ms;
        on = pos < (cfg->period_ms / 2);
    }
    if (!on) {
        clear_pixels(pixels, count);
        return;
    }
    for (uint16_t i = 0; i < count; i++) {
        uint32_t rnd = esp_random();
        pixels[i].r = (uint8_t)rnd;
        pixels[i].g = (uint8_t)(rnd >> 8);
        pixels[i].b = (uint8_t)(rnd >> 16);
    }
}

void light_effect_render(const segment_effect_t *cfg, segment_anim_t *anim,
                         color_t *pixels, uint16_t count, uint32_t delta_ms)
{
    if (pixels == NULL || count == 0) {
        return;
    }
    switch (cfg->mode) {
    case LIGHT_MODE_WAVE:
        render_wave(&cfg->wave, anim, pixels, count, delta_ms);
        break;
    case LIGHT_MODE_STATIC:
        render_static(&cfg->solid, pixels, count);
        break;
    case LIGHT_MODE_BREATH:
        render_breath(&cfg->breath, anim, pixels, count, delta_ms);
        break;
    case LIGHT_MODE_CHASE:
        render_chase(&cfg->chase, anim, pixels, count, delta_ms);
        break;
    case LIGHT_MODE_POINTS:
        render_points(&cfg->points, anim, pixels, count, delta_ms);
        break;
    case LIGHT_MODE_FLASH:
        render_flash(&cfg->flash, anim, pixels, count, delta_ms);
        break;
    default:
        clear_pixels(pixels, count);
        break;
    }
}
