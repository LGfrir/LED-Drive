/**
 * @file gatt_svc.h
 * @author LG_frir
 * @date 2026-11-11 17:32:25
 * @brief 三个服务：IO 口、灯效修改、灯效详细设置。
 *
 * 手机先读拓扑，再按 IO 编号改亮度/刷新率，按灯段 ID 改灯效。
 * 多点渐变和追逐的包会超过默认 MTU，手机需要把 MTU 提到 247。
 */

#ifndef __GATT_SVC_H__
#define __GATT_SVC_H__

#include "driver/bt/NimBLE.h"
#include "driver/led_strip/light_mode.h"

int io_svc_callback(uint16_t conn_handle, uint16_t attr_handle,
                    struct ble_gatt_access_ctxt *ctxt, void *arg);
int light_svc_callback(uint16_t conn_handle, uint16_t attr_handle,
                       struct ble_gatt_access_ctxt *ctxt, void *arg);
int config_svc_callback(uint16_t conn_handle, uint16_t attr_handle,
                        struct ble_gatt_access_ctxt *ctxt, void *arg);

#define IO_SVC_UUID_REG BLE_UUID128_INIT \
(0x69,0x6f,0x5f,0x73,0x76,0x63,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00)
/* ASCII: io_svc */

#define IO_CHR_TOPOLOGY_UUID_REG BLE_UUID128_INIT \
(0x69,0x6f,0x5f,0x74,0x6f,0x70,0x6f,0x6c,0x6f,0x67,0x79,0x00,0x00,0x00,0x00,0x00)
/* ASCII: io_topology */

#define IO_CHR_SELECT_UUID_REG BLE_UUID128_INIT \
(0x69,0x6f,0x5f,0x73,0x65,0x6c,0x65,0x63,0x74,0x00,0x00,0x00,0x00,0x00,0x00,0x00)
/* ASCII: io_select */

#define IO_CHR_BRIGHTNESS_UUID_REG BLE_UUID128_INIT \
(0x69,0x6f,0x5f,0x62,0x72,0x69,0x67,0x68,0x74,0x6e,0x65,0x73,0x73,0x00,0x00,0x00)
/* ASCII: io_brightness */

#define IO_CHR_REFRESH_UUID_REG BLE_UUID128_INIT \
(0x69,0x6f,0x5f,0x72,0x65,0x66,0x72,0x65,0x73,0x68,0x00,0x00,0x00,0x00,0x00,0x00)
/* ASCII: io_refresh */

#define IO_CHR_PHASE_UUID_REG BLE_UUID128_INIT \
(0x70,0x68,0x61,0x73,0x65,0x5f,0x7a,0x65,0x72,0x6f,0x00,0x00,0x00,0x00,0x00,0x00)
/* ASCII: phase_zero */

static const ble_uuid128_t IO_SVC_UUID            = IO_SVC_UUID_REG;
static const ble_uuid128_t IO_CHR_TOPOLOGY_UUID   = IO_CHR_TOPOLOGY_UUID_REG;
static const ble_uuid128_t IO_CHR_SELECT_UUID     = IO_CHR_SELECT_UUID_REG;
static const ble_uuid128_t IO_CHR_BRIGHTNESS_UUID = IO_CHR_BRIGHTNESS_UUID_REG;
static const ble_uuid128_t IO_CHR_REFRESH_UUID    = IO_CHR_REFRESH_UUID_REG;
static const ble_uuid128_t IO_CHR_PHASE_UUID      = IO_CHR_PHASE_UUID_REG;

#define LIGHT_SVC_UUID_REG BLE_UUID128_INIT \
(0x6c,0x69,0x67,0x68,0x74,0x5f,0x73,0x76,0x63,0x00,0x00,0x00,0x00,0x00,0x00,0x00)
/* ASCII: light_svc */

#define LIGHT_CHR_SELECT_UUID_REG BLE_UUID128_INIT \
(0x73,0x65,0x67,0x5f,0x73,0x65,0x6c,0x65,0x63,0x74,0x00,0x00,0x00,0x00,0x00,0x00)
/* ASCII: seg_select */

#define LIGHT_CHR_MODE_UUID_REG BLE_UUID128_INIT \
(0x6c,0x69,0x67,0x68,0x74,0x5f,0x6d,0x6f,0x64,0x65,0x00,0x00,0x00,0x00,0x00,0x00)
/* ASCII: light_mode */

#define LIGHT_CHR_BRIGHTNESS_UUID_REG BLE_UUID128_INIT \
(0x73,0x65,0x67,0x5f,0x62,0x72,0x69,0x67,0x68,0x74,0x6e,0x65,0x73,0x73,0x00,0x00)
/* ASCII: seg_brightness */

static const ble_uuid128_t LIGHT_SVC_UUID            = LIGHT_SVC_UUID_REG;
static const ble_uuid128_t LIGHT_CHR_SELECT_UUID     = LIGHT_CHR_SELECT_UUID_REG;
static const ble_uuid128_t LIGHT_CHR_MODE_UUID       = LIGHT_CHR_MODE_UUID_REG;
static const ble_uuid128_t LIGHT_CHR_BRIGHTNESS_UUID = LIGHT_CHR_BRIGHTNESS_UUID_REG;

#define CONFIG_SVC_UUID_REG BLE_UUID128_INIT \
(0x63,0x66,0x67,0x5f,0x73,0x76,0x63,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00)
/* ASCII: cfg_svc */

#define CONFIG_CHR_LIGHT_WAVE_UUID_REG BLE_UUID128_INIT \
(0x63,0x66,0x67,0x5f,0x6c,0x74,0x5f,0x77,0x61,0x76,0x65,0x00,0x00,0x00,0x00,0x00)
/* ASCII: cfg_lt_wave */

#define CONFIG_CHR_LIGHT_STATIC_UUID_REG BLE_UUID128_INIT \
(0x63,0x66,0x67,0x5f,0x6c,0x74,0x5f,0x73,0x74,0x61,0x74,0x69,0x63,0x00,0x00,0x00)
/* ASCII: cfg_lt_static */

#define CONFIG_CHR_LIGHT_BREATH_UUID_REG BLE_UUID128_INIT \
(0x63,0x66,0x67,0x5f,0x6c,0x74,0x5f,0x62,0x72,0x65,0x61,0x74,0x68,0x00,0x00,0x00)
/* ASCII: cfg_lt_breath */

#define CONFIG_CHR_LIGHT_CHASE_UUID_REG BLE_UUID128_INIT \
(0x63,0x66,0x67,0x5f,0x6c,0x74,0x5f,0x63,0x68,0x61,0x73,0x65,0x00,0x00,0x00,0x00)
/* ASCII: cfg_lt_chase */

#define CONFIG_CHR_LIGHT_POINTS_UUID_REG BLE_UUID128_INIT \
(0x63,0x66,0x67,0x5f,0x6c,0x74,0x5f,0x70,0x6f,0x69,0x6e,0x74,0x73,0x00,0x00,0x00)
/* ASCII: cfg_lt_points */

#define CONFIG_CHR_LIGHT_FLASH_UUID_REG BLE_UUID128_INIT \
(0x63,0x66,0x67,0x5f,0x6c,0x74,0x5f,0x66,0x6c,0x61,0x73,0x68,0x00,0x00,0x00,0x00)
/* ASCII: cfg_lt_flash */

static const ble_uuid128_t CONFIG_SVC_UUID              = CONFIG_SVC_UUID_REG;
static const ble_uuid128_t CONFIG_CHR_LIGHT_WAVE_UUID   = CONFIG_CHR_LIGHT_WAVE_UUID_REG;
static const ble_uuid128_t CONFIG_CHR_LIGHT_STATIC_UUID = CONFIG_CHR_LIGHT_STATIC_UUID_REG;
static const ble_uuid128_t CONFIG_CHR_LIGHT_BREATH_UUID = CONFIG_CHR_LIGHT_BREATH_UUID_REG;
static const ble_uuid128_t CONFIG_CHR_LIGHT_CHASE_UUID  = CONFIG_CHR_LIGHT_CHASE_UUID_REG;
static const ble_uuid128_t CONFIG_CHR_LIGHT_POINTS_UUID = CONFIG_CHR_LIGHT_POINTS_UUID_REG;
static const ble_uuid128_t CONFIG_CHR_LIGHT_FLASH_UUID  = CONFIG_CHR_LIGHT_FLASH_UUID_REG;

/* ---------------- 对时服务 ---------------- */

#define TIME_SVC_UUID_REG BLE_UUID128_INIT \
(0x74,0x69,0x6d,0x65,0x5f,0x73,0x76,0x63,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00)
/* ASCII: time_svc */

#define TIME_CHR_SET_UUID_REG BLE_UUID128_INIT \
(0x74,0x69,0x6d,0x65,0x5f,0x73,0x65,0x74,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00)
/* ASCII: time_set */

#define TIME_CHR_REQ_UUID_REG BLE_UUID128_INIT \
(0x74,0x69,0x6d,0x65,0x5f,0x72,0x65,0x71,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00)
/* ASCII: time_req */

#define TIME_CHR_STATE_UUID_REG BLE_UUID128_INIT \
(0x74,0x69,0x6d,0x65,0x5f,0x73,0x74,0x61,0x74,0x65,0x00,0x00,0x00,0x00,0x00,0x00)
/* ASCII: time_state */

/* ---------------- 预设服务 ---------------- */

#define PRESET_SVC_UUID_REG BLE_UUID128_INIT \
(0x70,0x72,0x73,0x74,0x5f,0x73,0x76,0x63,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00)
/* ASCII: prst_svc */

#define PRESET_CHR_LIST_UUID_REG BLE_UUID128_INIT \
(0x70,0x72,0x73,0x74,0x5f,0x6c,0x73,0x74,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00)
/* ASCII: prst_lst */

#define PRESET_CHR_READ_UUID_REG BLE_UUID128_INIT \
(0x70,0x72,0x73,0x74,0x5f,0x72,0x65,0x64,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00)
/* ASCII: prst_red */

#define PRESET_CHR_DATA_UUID_REG BLE_UUID128_INIT \
(0x70,0x72,0x73,0x74,0x5f,0x64,0x61,0x74,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00)
/* ASCII: prst_dat */

#define PRESET_CHR_WRITE_UUID_REG BLE_UUID128_INIT \
(0x70,0x72,0x73,0x74,0x5f,0x77,0x72,0x74,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00)
/* ASCII: prst_wrt */

#define PRESET_CHR_DEL_UUID_REG BLE_UUID128_INIT \
(0x70,0x72,0x73,0x74,0x5f,0x64,0x65,0x6c,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00)
/* ASCII: prst_del */

#define PRESET_CHR_DELOK_UUID_REG BLE_UUID128_INIT \
(0x70,0x72,0x73,0x74,0x5f,0x64,0x6f,0x6b,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00)
/* ASCII: prst_dok */

#define RESULT_CHR_UUID_REG BLE_UUID128_INIT \
(0x72,0x65,0x73,0x70,0x5f,0x64,0x61,0x74,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00)
/* ASCII: resp_dat */

/* ---------------- 调度服务 ---------------- */

#define SCHED_SVC_UUID_REG BLE_UUID128_INIT \
(0x73,0x63,0x68,0x64,0x5f,0x73,0x76,0x63,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00)
/* ASCII: schd_svc */

#define SCHED_CHR_LIST_UUID_REG BLE_UUID128_INIT \
(0x73,0x63,0x68,0x64,0x5f,0x6c,0x73,0x74,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00)
/* ASCII: schd_lst */

#define SCHED_CHR_GET_UUID_REG BLE_UUID128_INIT \
(0x73,0x63,0x68,0x64,0x5f,0x67,0x65,0x74,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00)
/* ASCII: schd_get */

#define SCHED_CHR_SET_UUID_REG BLE_UUID128_INIT \
(0x73,0x63,0x68,0x64,0x5f,0x73,0x65,0x74,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00)
/* ASCII: schd_set */

#define BIND_CHR_GET_UUID_REG BLE_UUID128_INIT \
(0x62,0x69,0x6e,0x64,0x5f,0x67,0x65,0x74,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00)
/* ASCII: bind_get */

#define BIND_CHR_SET_UUID_REG BLE_UUID128_INIT \
(0x62,0x69,0x6e,0x64,0x5f,0x73,0x65,0x74,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00)
/* ASCII: bind_set */

#define IO_MODE_CHR_UUID_REG BLE_UUID128_INIT \
(0x69,0x6f,0x5f,0x6d,0x6f,0x64,0x65,0x20,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00)
/* ASCII: io_mode */

#define CLOCK_STATE_CHR_UUID_REG BLE_UUID128_INIT \
(0x63,0x6c,0x6b,0x5f,0x73,0x74,0x61,0x74,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00)
/* ASCII: clk_stat */

#define EDIT_COMMIT_CHR_UUID_REG BLE_UUID128_INIT \
(0x65,0x64,0x69,0x74,0x5f,0x63,0x6d,0x74,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00)
/* ASCII: edit_cmt */

#define SCHED_DEL_CHR_UUID_REG BLE_UUID128_INIT \
(0x73,0x63,0x68,0x64,0x5f,0x64,0x65,0x6c,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00)
/* ASCII: schd_del */

#define SCHED_DOK_CHR_UUID_REG BLE_UUID128_INIT \
(0x73,0x63,0x68,0x64,0x5f,0x64,0x6f,0x6b,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00)
/* ASCII: schd_dok */

#define DEVINFO_CHR_UUID_REG BLE_UUID128_INIT \
(0x64,0x65,0x76,0x69,0x6e,0x66,0x6f,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00,0x00)
/* ASCII: devinfo */

static const ble_uuid128_t TIME_SVC_UUID      = TIME_SVC_UUID_REG;
static const ble_uuid128_t TIME_CHR_SET_UUID  = TIME_CHR_SET_UUID_REG;
static const ble_uuid128_t TIME_CHR_REQ_UUID  = TIME_CHR_REQ_UUID_REG;
static const ble_uuid128_t TIME_CHR_STATE_UUID = TIME_CHR_STATE_UUID_REG;

static const ble_uuid128_t PRESET_SVC_UUID      = PRESET_SVC_UUID_REG;
static const ble_uuid128_t PRESET_CHR_LIST_UUID = PRESET_CHR_LIST_UUID_REG;
static const ble_uuid128_t PRESET_CHR_READ_UUID = PRESET_CHR_READ_UUID_REG;
static const ble_uuid128_t PRESET_CHR_DATA_UUID = PRESET_CHR_DATA_UUID_REG;
static const ble_uuid128_t PRESET_CHR_WRITE_UUID = PRESET_CHR_WRITE_UUID_REG;
static const ble_uuid128_t PRESET_CHR_DEL_UUID  = PRESET_CHR_DEL_UUID_REG;
static const ble_uuid128_t PRESET_CHR_DELOK_UUID = PRESET_CHR_DELOK_UUID_REG;
static const ble_uuid128_t RESULT_CHR_UUID      = RESULT_CHR_UUID_REG;

static const ble_uuid128_t SCHED_SVC_UUID      = SCHED_SVC_UUID_REG;
static const ble_uuid128_t SCHED_CHR_LIST_UUID = SCHED_CHR_LIST_UUID_REG;
static const ble_uuid128_t SCHED_CHR_GET_UUID  = SCHED_CHR_GET_UUID_REG;
static const ble_uuid128_t SCHED_CHR_SET_UUID  = SCHED_CHR_SET_UUID_REG;
static const ble_uuid128_t BIND_CHR_GET_UUID   = BIND_CHR_GET_UUID_REG;
static const ble_uuid128_t BIND_CHR_SET_UUID   = BIND_CHR_SET_UUID_REG;
static const ble_uuid128_t IO_MODE_CHR_UUID    = IO_MODE_CHR_UUID_REG;
static const ble_uuid128_t CLOCK_STATE_CHR_UUID = CLOCK_STATE_CHR_UUID_REG;
static const ble_uuid128_t EDIT_COMMIT_CHR_UUID = EDIT_COMMIT_CHR_UUID_REG;
static const ble_uuid128_t SCHED_DEL_CHR_UUID   = SCHED_DEL_CHR_UUID_REG;
static const ble_uuid128_t SCHED_DOK_CHR_UUID   = SCHED_DOK_CHR_UUID_REG;
static const ble_uuid128_t DEVINFO_CHR_UUID     = DEVINFO_CHR_UUID_REG;

int time_svc_callback(uint16_t conn_handle, uint16_t attr_handle,
                      struct ble_gatt_access_ctxt *ctxt, void *arg);
int preset_svc_callback(uint16_t conn_handle, uint16_t attr_handle,
                        struct ble_gatt_access_ctxt *ctxt, void *arg);
int sched_svc_callback(uint16_t conn_handle, uint16_t attr_handle,
                       struct ble_gatt_access_ctxt *ctxt, void *arg);

/* NimBLE 注册时把通知用的 value handle 交给 gatt_svc */
void gatt_svc_set_handles(uint16_t time_req, uint16_t preset_data, uint16_t result);

/* 设备向手机请求时间（time_req 通知） */
void gatt_svc_time_request(uint16_t conn_handle);

static const struct ble_gatt_svc_def gatt_svr_svcs[] = {
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &IO_SVC_UUID.u,
        .characteristics = (struct ble_gatt_chr_def[]) {
            {
                .uuid = &IO_CHR_TOPOLOGY_UUID.u,
                .access_cb = io_svc_callback,
                .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_NOTIFY,
                .arg = (void *)(intptr_t)1
            },
            {
                .uuid = &IO_CHR_SELECT_UUID.u,
                .access_cb = io_svc_callback,
                .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_WRITE,
                .arg = (void *)(intptr_t)2
            },
            {
                .uuid = &IO_CHR_BRIGHTNESS_UUID.u,
                .access_cb = io_svc_callback,
                .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_WRITE,
                .arg = (void *)(intptr_t)3
            },
            {
                .uuid = &IO_CHR_REFRESH_UUID.u,
                .access_cb = io_svc_callback,
                .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_WRITE,
                .arg = (void *)(intptr_t)4
            },
            {
                .uuid = &IO_CHR_PHASE_UUID.u,
                .access_cb = io_svc_callback,
                .flags = BLE_GATT_CHR_F_WRITE,
                .arg = (void *)(intptr_t)5
            },
            { 0 }
        }
    },
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &LIGHT_SVC_UUID.u,
        .characteristics = (struct ble_gatt_chr_def[]) {
            {
                .uuid = &LIGHT_CHR_SELECT_UUID.u,
                .access_cb = light_svc_callback,
                .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_WRITE,
                .arg = (void *)(intptr_t)1
            },
            {
                .uuid = &LIGHT_CHR_MODE_UUID.u,
                .access_cb = light_svc_callback,
                .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_WRITE,
                .arg = (void *)(intptr_t)2
            },
            {
                .uuid = &LIGHT_CHR_BRIGHTNESS_UUID.u,
                .access_cb = light_svc_callback,
                .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_WRITE,
                .arg = (void *)(intptr_t)3
            },
            { 0 }
        }
    },
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &CONFIG_SVC_UUID.u,
        .characteristics = (struct ble_gatt_chr_def[]) {
            {
                .uuid = &CONFIG_CHR_LIGHT_WAVE_UUID.u,
                .access_cb = config_svc_callback,
                .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_WRITE,
                .arg = (void *)(intptr_t)LIGHT_MODE_WAVE
            },
            {
                .uuid = &CONFIG_CHR_LIGHT_STATIC_UUID.u,
                .access_cb = config_svc_callback,
                .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_WRITE,
                .arg = (void *)(intptr_t)LIGHT_MODE_STATIC
            },
            {
                .uuid = &CONFIG_CHR_LIGHT_BREATH_UUID.u,
                .access_cb = config_svc_callback,
                .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_WRITE,
                .arg = (void *)(intptr_t)LIGHT_MODE_BREATH
            },
            {
                .uuid = &CONFIG_CHR_LIGHT_CHASE_UUID.u,
                .access_cb = config_svc_callback,
                .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_WRITE,
                .arg = (void *)(intptr_t)LIGHT_MODE_CHASE
            },
            {
                .uuid = &CONFIG_CHR_LIGHT_POINTS_UUID.u,
                .access_cb = config_svc_callback,
                .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_WRITE,
                .arg = (void *)(intptr_t)LIGHT_MODE_POINTS
            },
            {
                .uuid = &CONFIG_CHR_LIGHT_FLASH_UUID.u,
                .access_cb = config_svc_callback,
                .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_WRITE,
                .arg = (void *)(intptr_t)LIGHT_MODE_FLASH
            },
            { 0 }
        }
    },
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &TIME_SVC_UUID.u,
        .characteristics = (struct ble_gatt_chr_def[]) {
            {
                .uuid = &TIME_CHR_SET_UUID.u,
                .access_cb = time_svc_callback,
                .flags = BLE_GATT_CHR_F_WRITE,
                .arg = (void *)(intptr_t)1
            },
            {
                .uuid = &TIME_CHR_REQ_UUID.u,
                .access_cb = time_svc_callback,
                .flags = BLE_GATT_CHR_F_NOTIFY,
                .arg = (void *)(intptr_t)2
            },
            {
                .uuid = &TIME_CHR_STATE_UUID.u,
                .access_cb = time_svc_callback,
                .flags = BLE_GATT_CHR_F_READ,
                .arg = (void *)(intptr_t)3
            },
            { 0 }
        }
    },
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &PRESET_SVC_UUID.u,
        .characteristics = (struct ble_gatt_chr_def[]) {
            {
                .uuid = &PRESET_CHR_LIST_UUID.u,
                .access_cb = preset_svc_callback,
                .flags = BLE_GATT_CHR_F_READ,
                .arg = (void *)(intptr_t)1
            },
            {
                .uuid = &PRESET_CHR_READ_UUID.u,
                .access_cb = preset_svc_callback,
                .flags = BLE_GATT_CHR_F_WRITE,
                .arg = (void *)(intptr_t)2
            },
            {
                .uuid = &PRESET_CHR_DATA_UUID.u,
                .access_cb = preset_svc_callback,
                .flags = BLE_GATT_CHR_F_NOTIFY,
                .arg = (void *)(intptr_t)3
            },
            {
                .uuid = &PRESET_CHR_WRITE_UUID.u,
                .access_cb = preset_svc_callback,
                .flags = BLE_GATT_CHR_F_WRITE,
                .arg = (void *)(intptr_t)4
            },
            {
                .uuid = &PRESET_CHR_DEL_UUID.u,
                .access_cb = preset_svc_callback,
                .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_READ,
                .arg = (void *)(intptr_t)5
            },
            {
                .uuid = &PRESET_CHR_DELOK_UUID.u,
                .access_cb = preset_svc_callback,
                .flags = BLE_GATT_CHR_F_WRITE,
                .arg = (void *)(intptr_t)6
            },
            {
                .uuid = &RESULT_CHR_UUID.u,
                .access_cb = preset_svc_callback,
                .flags = BLE_GATT_CHR_F_NOTIFY,
                .arg = (void *)(intptr_t)7
            },
            { 0 }
        }
    },
    {
        .type = BLE_GATT_SVC_TYPE_PRIMARY,
        .uuid = &SCHED_SVC_UUID.u,
        .characteristics = (struct ble_gatt_chr_def[]) {
            {
                .uuid = &SCHED_CHR_LIST_UUID.u,
                .access_cb = sched_svc_callback,
                .flags = BLE_GATT_CHR_F_READ,
                .arg = (void *)(intptr_t)1
            },
            {
                .uuid = &SCHED_CHR_GET_UUID.u,
                .access_cb = sched_svc_callback,
                .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_READ,
                .arg = (void *)(intptr_t)2
            },
            {
                .uuid = &SCHED_CHR_SET_UUID.u,
                .access_cb = sched_svc_callback,
                .flags = BLE_GATT_CHR_F_WRITE,
                .arg = (void *)(intptr_t)3
            },
            {
                .uuid = &BIND_CHR_GET_UUID.u,
                .access_cb = sched_svc_callback,
                .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_READ,
                .arg = (void *)(intptr_t)4
            },
            {
                .uuid = &BIND_CHR_SET_UUID.u,
                .access_cb = sched_svc_callback,
                .flags = BLE_GATT_CHR_F_WRITE,
                .arg = (void *)(intptr_t)5
            },
            {
                .uuid = &IO_MODE_CHR_UUID.u,
                .access_cb = sched_svc_callback,
                .flags = BLE_GATT_CHR_F_READ | BLE_GATT_CHR_F_WRITE,
                .arg = (void *)(intptr_t)6
            },
            {
                .uuid = &CLOCK_STATE_CHR_UUID.u,
                .access_cb = sched_svc_callback,
                .flags = BLE_GATT_CHR_F_READ,
                .arg = (void *)(intptr_t)7
            },
            {
                .uuid = &EDIT_COMMIT_CHR_UUID.u,
                .access_cb = sched_svc_callback,
                .flags = BLE_GATT_CHR_F_WRITE,
                .arg = (void *)(intptr_t)8
            },
            {
                .uuid = &SCHED_DEL_CHR_UUID.u,
                .access_cb = sched_svc_callback,
                .flags = BLE_GATT_CHR_F_WRITE | BLE_GATT_CHR_F_READ,
                .arg = (void *)(intptr_t)9
            },
            {
                .uuid = &SCHED_DOK_CHR_UUID.u,
                .access_cb = sched_svc_callback,
                .flags = BLE_GATT_CHR_F_WRITE,
                .arg = (void *)(intptr_t)10
            },
            {
                .uuid = &DEVINFO_CHR_UUID.u,
                .access_cb = sched_svc_callback,
                .flags = BLE_GATT_CHR_F_READ,
                .arg = (void *)(intptr_t)11
            },
            { 0 }
        }
    },
    { 0 }
};

#endif /* __GATT_SVC_H__ */
