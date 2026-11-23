/**
 * @file NimBLE.c
 * @author LG_frir
 * @date 2026-10-09 10:25:39
 */

#include "driver/bt/NimBLE.h"
#include "task/auth_svc.h"
#include "task/gatt_svc.h"
#include "driver/timer/time_sync.h"

#include <assert.h>
#include <string.h>

#include "esp_err.h"
#include "esp_timer.h"
#include "host/ble_att.h"

#define NOTIFY_MAX_CONN CONFIG_BT_NIMBLE_MAX_CONNECTIONS
typedef struct notify_sub_t_ {
    uint16_t conn_handle;
    uint16_t val_handle;
    bool on;
    bool used;
} notify_sub_t;


static uint8_t own_addr_type;
static notify_sub_t s_subs[NOTIFY_MAX_CONN];
static uint16_t s_topology_val_handle;
static uint16_t s_time_req_val_handle;
static uint16_t s_preset_data_val_handle;
static uint16_t s_result_val_handle;
static const char *TAG = "NIMBLE";

/* 仪表盘 BLE 状态（单连接） */
static volatile bool s_connected;
static uint16_t s_conn_handle;
static int64_t s_conn_start_us;

bool nimble_is_connected(void)
{
    return s_connected;
}

int8_t nimble_get_rssi(void)
{
    if (!s_connected) {
        return 0;
    }
    int8_t rssi = 0;
    if (ble_gap_conn_rssi(s_conn_handle, &rssi) != 0) {
        return 0;
    }
    return rssi;
}

uint16_t nimble_get_mtu(void)
{
    return s_connected ? ble_att_mtu(s_conn_handle) : 0;
}

uint32_t nimble_get_conn_seconds(void)
{
    if (!s_connected) {
        return 0;
    }
    return (uint32_t)((esp_timer_get_time() - s_conn_start_us) / 1000000);
}

void ble_store_config_init(void);

/* 前置声明:bleprph_advertise 定义在本文件后面,但 bleprph_gap_event 要调用它 */
static void bleprph_advertise(void);

static int bleprph_gap_event(struct ble_gap_event *event, void *arg) {
    switch(event->type) {
        case BLE_GAP_EVENT_CONNECT: {
            MODLOG_DFLT(INFO, "connection %s; status=%d ",
                        event->connect.status == 0 ? "established" : "failed",
                        event->connect.status);
            MODLOG_DFLT(INFO, "\n");

            if (event->connect.status != 0) {
                bleprph_advertise();
            } else {
                s_conn_handle = event->connect.conn_handle;
                s_conn_start_us = esp_timer_get_time();
                s_connected = true;
                time_sync_on_connect(event->connect.conn_handle);
            }
            return 0;
        }   
        case BLE_GAP_EVENT_DISCONNECT: {
            MODLOG_DFLT(INFO, "disconnect; reason=%d ", event->disconnect.reason);
            MODLOG_DFLT(INFO, "\n");
            bool found = false;
            for (int i = 0; i < NOTIFY_MAX_CONN; i++) {
                if (s_subs[i].used && s_subs[i].conn_handle == event->disconnect.conn.conn_handle) {
                    s_subs[i].used = false;
                    s_subs[i].on   = false;
                    found = true;
                    break;                      /* 一个连接只占一个槽，找到就停 */
                }
            }
            if (!found) {
                ESP_LOGW(TAG, "断开时没找到订阅记录 conn=%d", event->disconnect.conn.conn_handle);
            }

            s_connected = false;
            s_conn_handle = 0;
            time_sync_on_disconnect();
            auth_on_disconnect();
            bleprph_advertise();
            return 0;
        }
        case BLE_GAP_EVENT_ADV_COMPLETE: {
            ESP_LOGI(TAG, "advertise complete; reason=%d", event->adv_complete.reason);
            bleprph_advertise();
            return 0;
        }
        case BLE_GAP_EVENT_SUBSCRIBE:
            ESP_LOGI(TAG, "subscribe: conn=%d handle=%d %d -> %d",
                    event->subscribe.conn_handle,
                    event->subscribe.attr_handle,
                    event->subscribe.prev_notify,
                    event->subscribe.cur_notify);

            int idx      = -1;      /* 已有记录的位置 */
            int free_idx = -1;      /* 第一个空槽的位置 */
            


            for (int i = 0; i < NOTIFY_MAX_CONN; i++) {
                if (s_subs[i].used && s_subs[i].conn_handle == event->subscribe.conn_handle) {
                    idx = i;
                    break;
                }
                if (!s_subs[i].used && free_idx < 0) {
                    free_idx = i;
                }
            }

            if (idx < 0) {
                if (free_idx < 0) { ESP_LOGE(TAG, "订阅表满了"); return 0; }
                idx = free_idx;
                s_subs[idx].conn_handle = event->subscribe.conn_handle;
                s_subs[idx].used = true;
            }

            s_subs[idx].val_handle = event->subscribe.attr_handle;
            s_subs[idx].on         = event->subscribe.cur_notify;

            /* 订阅拓扑之后立刻推一包，手机用来生成 IO / 灯段两层界面 */
            if (event->subscribe.attr_handle == s_topology_val_handle &&
                event->subscribe.cur_notify) {
                ble_gatts_notify_custom(event->subscribe.conn_handle,
                                        s_topology_val_handle, NULL);
            }

            return 0;
        default: 
            return 0;
    }

    return 0;
}


static void bleprph_advertise(void) {
    struct ble_hs_adv_fields fields;
    memset(&fields, 0, sizeof fields);

    fields.flags = BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP;
    fields.tx_pwr_lvl_is_present = 1;
    fields.tx_pwr_lvl = BLE_HS_ADV_TX_PWR_LVL_AUTO;

    fields.uuids128            = &IO_SVC_UUID;
    fields.num_uuids128        = 1;
    fields.uuids128_is_complete = 1;

    int rc = ble_gap_adv_set_fields(&fields); 
    if(rc != 0) {
        ESP_LOGE(TAG, "ble_gap_adv_set_fields failed 1");
        return;
    }
    struct ble_hs_adv_fields rsp_fields;
    memset(&rsp_fields, 0, sizeof rsp_fields);

    const char* dev_name = ble_svc_gap_device_name();
    rsp_fields.name = (const uint8_t *)dev_name;
    rsp_fields.name_len = strlen(dev_name);
    rsp_fields.name_is_complete = 1;

    rc = ble_gap_adv_rsp_set_fields(&rsp_fields);
    if(rc != 0) {
        ESP_LOGE(TAG, "ble_gap_adv_set_fields failed 2");
        return;
    }
    
    struct ble_gap_adv_params adv_params;
    memset(&adv_params, 0, sizeof adv_params);
    adv_params.conn_mode = BLE_GAP_CONN_MODE_UND;
    adv_params.disc_mode = BLE_GAP_DISC_MODE_GEN;   

    rc = ble_gap_adv_start(own_addr_type, NULL, BLE_HS_FOREVER, &adv_params, bleprph_gap_event, NULL);

    if(rc != 0 && rc != BLE_HS_EALREADY) {
        ESP_LOGE(TAG,"ble_gap_adv_start failed");
        return;
    }
    
}



static void bleprph_on_reset(int reason) {
    ESP_LOGE(TAG, "Resetting state; reason=%d", reason);
}

static void bleprph_on_sync(void) {
    int rc = ble_hs_util_ensure_addr(0);
    assert(rc == 0);

    // 设置MAC地址
    rc = ble_hs_id_infer_auto(0, &own_addr_type);
    if (rc != 0) {
        MODLOG_DFLT(ERROR, "error determining address type; rc=%d\n", rc);
        return;
    }

    uint8_t addr_val[6] = {0};
    rc = ble_hs_id_copy_addr(own_addr_type, addr_val, NULL);

    ESP_LOGI(TAG, "Device Address: %02x:%02x:%02x:%02x:%02x:%02x",
         addr_val[5], addr_val[4], addr_val[3], addr_val[2], addr_val[1], addr_val[0]);

    // 广播
    bleprph_advertise();

}

static void gatt_svr_register_cb(struct ble_gatt_register_ctxt *ctxt, void *arg) {
    char buf[BLE_UUID_STR_LEN];
    switch (ctxt->op) {
    case BLE_GATT_REGISTER_OP_SVC:
        ESP_LOGD(TAG, "registered service %s with handle=%d",
                 ble_uuid_to_str(ctxt->svc.svc_def->uuid, buf),
                 ctxt->svc.handle);
        break;
    case BLE_GATT_REGISTER_OP_CHR:
        ESP_LOGD(TAG, "registered characteristic %s with def_handle=%d val_handle=%d",
                 ble_uuid_to_str(ctxt->chr.chr_def->uuid, buf),
                 ctxt->chr.def_handle, ctxt->chr.val_handle);
        if (ble_uuid_cmp(ctxt->chr.chr_def->uuid, &IO_CHR_TOPOLOGY_UUID.u) == 0) {
            s_topology_val_handle = ctxt->chr.val_handle;
        } else if (ble_uuid_cmp(ctxt->chr.chr_def->uuid, &TIME_CHR_REQ_UUID.u) == 0) {
            s_time_req_val_handle = ctxt->chr.val_handle;
        } else if (ble_uuid_cmp(ctxt->chr.chr_def->uuid, &PRESET_CHR_DATA_UUID.u) == 0) {
            s_preset_data_val_handle = ctxt->chr.val_handle;
        } else if (ble_uuid_cmp(ctxt->chr.chr_def->uuid, &RESULT_CHR_UUID.u) == 0) {
            s_result_val_handle = ctxt->chr.val_handle;
        }
        gatt_svc_set_handles(s_time_req_val_handle, s_preset_data_val_handle, s_result_val_handle);
        break;
    case BLE_GATT_REGISTER_OP_DSC:
        ESP_LOGD(TAG, "registered descriptor %s with handle=%d",
                 ble_uuid_to_str(ctxt->dsc.dsc_def->uuid, buf),
                 ctxt->dsc.handle);
        break;
    default:
        break;
    }
}




// GATT 注册
int gatt_svt_init() {
    int rc;
    ble_svc_gap_init();
    ble_svc_gatt_init();

    rc = ble_gatts_count_cfg(gatt_svr_svcs);
    if (rc != 0) return rc;
    rc = ble_gatts_add_svcs(gatt_svr_svcs);
    if (rc != 0) return rc;
    return 0;
}

void bleprph_host_task(void *param) {
    ESP_LOGI(TAG, "BLE Host Task Started");
    /* This function will return only when nimble_port_stop() is executed */
    nimble_port_run();

    nimble_port_freertos_deinit();
}
void nimble_init_as_prph() {
    esp_err_t ret = nimble_port_init();
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "Failed to init nimble %d ", ret);
        return;
    }
    /* 追逐和多点渐变的包比默认 23 字节 MTU 大，优先提到 247 */
    ble_att_set_preferred_mtu(247);

    ble_hs_cfg.reset_cb = bleprph_on_reset;
    ble_hs_cfg.sync_cb = bleprph_on_sync;
    ble_hs_cfg.gatts_register_cb = gatt_svr_register_cb;
    ble_hs_cfg.store_status_cb = ble_store_util_status_rr;

    ble_hs_cfg.sm_io_cap = BLE_HS_IO_NO_INPUT_OUTPUT;
    ble_hs_cfg.sm_bonding = 1;
    ble_hs_cfg.sm_our_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
    ble_hs_cfg.sm_their_key_dist = BLE_SM_PAIR_KEY_DIST_ENC | BLE_SM_PAIR_KEY_DIST_ID;
    ble_hs_cfg.sm_mitm = 0;
    ble_hs_cfg.sm_sc = 1;

    // gatt 注册
    int rc = gatt_svt_init();
    assert(rc == 0);
    // 设备名注册

    rc = ble_svc_gap_device_name_set(auth_device_name());
    assert(rc == 0);

    //不懂
    ble_store_config_init();
    nimble_port_freertos_init(bleprph_host_task);
}


