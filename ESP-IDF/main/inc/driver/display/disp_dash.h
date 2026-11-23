/**
 * @file disp_dash.h
 * @author LG_frir
 * @date 2026-11-17
 * @brief 单页面仪表盘：不依赖 LVGL，直接驱动 ST7735。
 *
 * 一屏放全部信息（160x128 横屏），下面的“轮播面板”共 io_count+1 页：
 *   IO1 → IO2 → ... → BLE，每 12 秒切一页。
 *
 *   - 左上角：本机蓝牙名（取前 10 字符）
 *   - 右上角：已连接设备名（取前 10 字符），未连接显示 NO LINK
 *   - 时钟（全白）、日期、运行时长
 *   - 一行日志
 *   - 轮播面板：IO 页显示点数/刷新/亮度/DMA/CPU；BLE 页显示 RSSI/MTU/收发/连接时长
 *   - CPU 行按 IO 与 BLE 轮询，右侧跟帧率
 *   - 预设条数 / 下一个调度点
 *   - 四角括符平时隐藏，手机改过参数后亮 10 秒（dash_flash_activity）
 *
 * 抗闪：所有绘制先进 RAM 帧缓冲，只把变化的整行带推给面板。
 * 注意：推送必须按整行宽（见 fb_flush 注释），否则行数据会错位。
 */

#ifndef DISP_DASH_H
#define DISP_DASH_H

#include <stdbool.h>
#include <stdint.h>

#include "esp_err.h"

#define DASH_IO_MAX       4
#define DASH_NAME_MAX     11      /* 蓝牙名最多显示 10 个字符 + '\0' */
#define DASH_FLASH_SEC    10      /* 四角括符亮多久 */

/* 全局状态（1 Hz 更新） */
typedef struct {
    bool     time_valid;      /* 未对时显示 --:--:-- */
    uint8_t  hour;
    uint8_t  minute;
    uint8_t  second;
    uint8_t  preset_user;     /* 用户预设条数（预留，界面暂不显示） */
    uint8_t  preset_max;      /* 用户预设上限 */
    uint32_t next_event_sec;  /* 今天下一个调度点，0 = 没有 */
} dash_status_t;

/* 单路 IO 的性能与状态 */
typedef struct {
    uint8_t  id;
    const char *name;         /* ASCII */
    bool     time_mode;
    uint8_t  seg_count;
    uint16_t led_count;
    uint16_t refresh_ms;
    uint8_t  brightness;      /* 0..255 */
    uint32_t power_mw;        /* 最近一帧按实际 RGB 估算的 12V 功率 */
    uint8_t  cpu_pct;         /* 发送占用率 0..100 */
    uint32_t frame_us;
    uint16_t fps;
    bool     dma;
    uint8_t  r, g, b;
} dash_io_t;

/* 蓝牙状态与占用 */
typedef struct {
    const char *device_name;  /* 本机广播名，显示前 10 字符 */
    bool        connected;
    const char *peer_name;    /* 已连接设备名，显示前 10 字符 */
    int8_t      rssi;         /* dBm，未连接忽略 */
    uint16_t    mtu;
    uint32_t    tx_bytes;
    uint32_t    rx_bytes;
    uint8_t     cpu_pct;      /* BLE 任务占用 0..100 */
    uint32_t    conn_s;       /* 已连接时长（秒） */
} dash_ble_t;

esp_err_t dash_init(void);

/* 覆盖全局状态；只重画变化的区块 */
void dash_set_status(const dash_status_t *st);

/* 覆盖 IO 表（最多 DASH_IO_MAX 路）；轮播面板会按 IO 数 + 1 页（含 BLE）切换 */
void dash_set_ios(const dash_io_t *ios, uint8_t count);

/* 覆盖蓝牙状态 */
void dash_set_ble(const dash_ble_t *ble);

/* 推一条事件日志（一行），同时点亮四角括符 10 秒 */
void dash_log_push(const char *msg);

/* 手机改过参数时调一下：四角括符亮 10 秒后自动消失 */
void dash_flash_activity(void);

/* 1 Hz 调一次：推进轮播、CPU 轮询、括符倒计时 */
void dash_tick(void);

/* 当前轮播到第几页（0..io_count-1 是 IO，io_count 是 BLE 页） */
uint8_t dash_current_page(void);

/* 点阵字模自检：把 5x7 字库全部画出来（page 0/1） */
void dash_glyph_test(int page);

/* OTA 专用屏幕：进入/更新/退出。update 以 10Hz 调用，进度条和百分比直接刷新。 */
void dash_ota_begin(void);
void dash_ota_update(uint8_t progress, const char *phase, const char *detail);
void dash_ota_reboot(uint8_t seconds_left); /* 0 = REBOOTING，红色反相 */
void dash_ota_end(void);

#endif /* DISP_DASH_H */
