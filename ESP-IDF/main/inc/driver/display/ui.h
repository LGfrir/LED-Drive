/**
 * @file ui.h
 * @author LG_frir
 * @date 2026-11-17
 * @brief 屏幕上的界面（全英文），调用前必须持有 LVGL 锁
 */

#ifndef UI_H
#define UI_H

#include <stdint.h>

/* 建好主界面并挂上刷新定时器（调用前必须持锁） */
void ui_start(void);

/* 满屏刷屏速度，由 lcd_bench_fill_fps() 测出来后传给界面显示 */
void ui_set_fill_fps(uint32_t fps);

#endif /* UI_H */
