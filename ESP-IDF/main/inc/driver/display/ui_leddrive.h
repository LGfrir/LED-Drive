/**
 * @file ui.h
 * @brief LEDDrive 160x128 LVGL 界面
 *
 * 所有 ui_* 调用都必须在持有 LVGL 锁时进行；ui_ota_set_* 例外，
 * 它只写内部状态，由界面定时器读取。
 */

#ifndef UI_LEDDRIVE_H
#define UI_LEDDRIVE_H

#include <stdint.h>

typedef enum {
    UI_OTA_IDLE = 0,
    UI_OTA_RUNNING,
    UI_OTA_SUCCESS,
    UI_OTA_FAILED,
} ui_ota_state_t;

/* 建好界面并挂上定时器（调用前必须持 LVGL 锁） */
void ui_start(void);

/* 兼容旧接口：满屏刷屏速度，显示在系统页 */
void ui_set_fill_fps(uint32_t fps);

/* OTA 后端调用：更新 OTA 页；可在任意任务里调用，内部只写状态 */
void ui_ota_set_state(ui_ota_state_t state, uint8_t progress, const char *message);

#endif /* UI_LEDDRIVE_H */
