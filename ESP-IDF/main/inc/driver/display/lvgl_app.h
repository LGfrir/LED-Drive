/**
 * @file lvgl_app.h
 * @author LG_frir
 * @date 2026-11-17
 * @brief LVGL 9 移植层（espressif/esp_lvgl_port）
 *
 * 屏幕仍然是横屏 160x128、画面顺时针转 90°：旋转标志直接从 lcd.h 取,
 * 保证裸驱动和 LVGL 两条路径看到的方向完全一致。
 */

#ifndef LVGL_APP_H
#define LVGL_APP_H

#include <stdbool.h>
#include <stdint.h>

#include "esp_err.h"
#include "lvgl.h"

/* 初始化 LVGL 与显示设备（会创建 LVGL 自己的任务和 tick 定时器） */
esp_err_t lvgl_app_init(void);

/* LVGL 不是线程安全的：任何任务里操作 UI 都要先加锁 */
bool lvgl_app_lock(uint32_t timeout_ms);
void lvgl_app_unlock(void);

/* 拿到 LVGL 显示对象（可能为 NULL，未初始化时） */
lv_display_t *lvgl_app_disp(void);

#endif /* LVGL_APP_H */
