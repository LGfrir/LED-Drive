/**
 * @file time_sync.h
 * @author LG_frir
 * @date 2026-11-12
 * @brief 和手机对时。连接后 1 分钟没同步就主动请求，App 打开时也会主动写。
 */

#ifndef __TIME_SYNC_H__
#define __TIME_SYNC_H__

#include <stdbool.h>
#include <stdint.h>

#include "esp_err.h"

/* 请求回调：设备 -> App 发 time_req 通知 */
typedef void (*time_sync_request_cb_t)(uint16_t conn_handle);

void time_sync_init(time_sync_request_cb_t request_cb);

/* 连接/断开时由 NimBLE 的 GAP 事件调用 */
void time_sync_on_connect(uint16_t conn_handle);
void time_sync_on_disconnect(void);

/* 解析 8 字节时间包并写进本地时钟：年 u16, 月, 日, 时, 分, 秒, 星期 */
esp_err_t time_sync_apply(const uint8_t *pkt, uint16_t len);

/* 已经对过时 */
bool time_sync_valid(void);

#endif /* __TIME_SYNC_H__ */
