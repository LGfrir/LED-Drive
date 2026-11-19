/**
 * @file gatt_time_svc.c
 * @author LG_frir
 * @date 2026-11-12
 *
 * 对时 / 预设 / 调度三组 GATT 服务。
 * 通知通道（time_req、prst_dat、resp_dat）的 value handle 由 NimBLE 注册回调填进来。
 *
 * 包格式全部小端，详见 README 的“BLE 协议”一节。
 */

#include "task/gatt_svc.h"

#include "driver/led_strip/light_mode_driver.h"
#include "driver/preset/light_preset.h"
#include "driver/timer/local_clock.h"
#include "driver/timer/time_sync.h"
#include "task/clock_scheduler.h"

#include "host/ble_hs_mbuf.h"

#include <stdio.h>
#include <string.h>

#include "esp_log.h"
#include "esp_timer.h"

#define TAG "gatt_time"

static uint16_t s_h_time_req;
static uint16_t s_h_prst_data;
static uint16_t s_h_result;

static uint8_t s_time_seq;
static uint8_t s_prst_sel;
static uint8_t s_sched_sel;
static uint8_t s_bind_sel;

static char s_usage[640];
static char s_schd_usage[640];
static uint8_t s_wbuf[512];
static uint16_t s_wtotal;
static uint16_t s_wid;
static bool s_writing;

void gatt_svc_set_handles(uint16_t time_req, uint16_t preset_data, uint16_t result)
{
    s_h_time_req = time_req;
    s_h_prst_data = preset_data;
    s_h_result = result;
}

void gatt_svc_time_request(uint16_t conn_handle)
{
    if (s_h_time_req == 0) {
        return;
    }
    uint8_t seq = ++s_time_seq;
    struct os_mbuf *om = ble_hs_mbuf_from_flat(&seq, 1);
    if (om != NULL) {
        ble_gatts_notify_custom(conn_handle, s_h_time_req, om);
    }
}

static void notify_handle(uint16_t conn, uint16_t handle, const void *data, uint16_t len)
{
    if (handle == 0 || len == 0) {
        return;
    }
    struct os_mbuf *om = ble_hs_mbuf_from_flat(data, len);
    if (om != NULL) {
        ble_gatts_notify_custom(conn, handle, om);
    }
}

static void send_result(uint16_t conn, uint8_t type, uint8_t a, uint8_t b, uint8_t status)
{
    uint8_t pkt[4] = { type, a, b, status };
    notify_handle(conn, s_h_result, pkt, sizeof(pkt));
}

/* type=1 / type=4 额外把设备最终采用的名字带回，App 不用再读一遍 */
static void send_result_name(uint16_t conn, uint8_t type, uint8_t a, uint8_t b,
                             uint8_t status, const char *name)
{
    uint8_t pkt[4 + 1 + LIGHT_PRESET_NAME_MAX];
    uint8_t name_len = name != NULL ? (uint8_t)strnlen(name, LIGHT_PRESET_NAME_MAX - 1) : 0;
    pkt[0] = type;
    pkt[1] = a;
    pkt[2] = b;
    pkt[3] = status;
    pkt[4] = name_len;
    if (name_len > 0) {
        memcpy(&pkt[5], name, name_len);
    }
    notify_handle(conn, s_h_result, pkt, (uint16_t)(5 + name_len));
}

static int copy_in(struct ble_gatt_access_ctxt *ctxt, void *dst, uint16_t expect)
{
    if (OS_MBUF_PKTLEN(ctxt->om) != expect) {
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }
    if (os_mbuf_copydata(ctxt->om, 0, expect, dst) != 0) {
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }
    return 0;
}

static int put_out(struct ble_gatt_access_ctxt *ctxt, const void *src, uint16_t len)
{
    return os_mbuf_append(ctxt->om, src, len) == 0 ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
}

/* 0=成功 1=其他失败 2=满了 3=只读 4=不存在 5=参数不合法 */
static uint8_t status_of(esp_err_t err)
{
    switch (err) {
    case ESP_OK:
        return 0;
    case ESP_ERR_NO_MEM:
        return 2;
    case ESP_ERR_NOT_ALLOWED:
        return 3;
    case ESP_ERR_NOT_FOUND:
        return 4;
    case ESP_ERR_INVALID_ARG:
    case ESP_ERR_INVALID_SIZE:
        return 5;
    default:
        return 1;
    }
}

/* ---------------- 对时 ---------------- */

int time_svc_callback(uint16_t conn_handle, uint16_t attr_handle,
                      struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    (void)attr_handle;
    uintptr_t which = (uintptr_t)arg;

    if (which == 1 && ctxt->op == BLE_GATT_ACCESS_OP_WRITE_CHR) {
        uint8_t pkt[8];
        if (copy_in(ctxt, pkt, sizeof(pkt)) != 0) {
            return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        }
        esp_err_t err = time_sync_apply(pkt, sizeof(pkt));
        send_result(conn_handle, 6, status_of(err), 0, status_of(err));
        return err == ESP_OK ? 0 : BLE_ATT_ERR_UNLIKELY;
    }

    if (which == 3 && ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR) {
        uint8_t pkt[9];
        pkt[0] = time_sync_valid() ? 1 : 0;
        uint32_t next = clock_next_event_sec();
        memcpy(&pkt[1], &next, sizeof(next));
        uint32_t now = (uint32_t)local_clock_now_sec();
        memcpy(&pkt[5], &now, sizeof(now));
        return put_out(ctxt, pkt, sizeof(pkt));
    }

    return BLE_ATT_ERR_UNLIKELY;
}

/* ---------------- 预设 ---------------- */

static uint16_t preset_record(uint8_t id, uint8_t *buf, uint16_t cap)
{
    light_preset_t p;
    if (light_preset_copy(id, &p) != ESP_OK || cap < 64) {
        return 0;
    }
    uint8_t name_len = (uint8_t)strnlen(p.name, LIGHT_PRESET_NAME_MAX - 1);
    buf[0] = name_len;
    memcpy(&buf[1], p.name, name_len);
    uint16_t n = light_preset_encode(&p.effect, &buf[1 + name_len], cap - 1 - name_len);
    return n == 0 ? 0 : (uint16_t)(1 + name_len + n);
}

static void preset_read_chunk(uint16_t conn, uint8_t id, uint16_t off)
{
    uint8_t rec[LIGHT_PRESET_NAME_MAX + 270 + 1];
    uint16_t total = preset_record(id, rec, sizeof(rec));
    if (total == 0 || off >= total) {
        uint8_t err[6] = { id, 0, 0, 0, 0, 0 };
        notify_handle(conn, s_h_prst_data, err, sizeof(err));
        return;
    }
    uint16_t remain = total - off;
    uint16_t chunk = remain > 230 ? 230 : remain;
    uint8_t pkt[6 + 230];
    pkt[0] = id;
    memcpy(&pkt[1], &off, 2);
    memcpy(&pkt[3], &total, 2);
    pkt[5] = (uint8_t)chunk;
    memcpy(&pkt[6], rec + off, chunk);
    notify_handle(conn, s_h_prst_data, pkt, (uint16_t)(6 + chunk));
}

static void preset_write_done(uint16_t conn)
{
    if (s_wtotal < 2 || s_wtotal > sizeof(s_wbuf)) {
        send_result(conn, 1, (uint8_t)s_wid, 0, 1);
        s_writing = false;
        return;
    }
    uint8_t name_len = s_wbuf[0];
    if (name_len > LIGHT_PRESET_NAME_MAX - 1 || (uint16_t)(1 + name_len + 270) != s_wtotal) {
        send_result(conn, 1, (uint8_t)s_wid, 0, 1);
        s_writing = false;
        return;
    }
    char name[LIGHT_PRESET_NAME_MAX];
    memset(name, 0, sizeof(name));
    memcpy(name, &s_wbuf[1], name_len);

    segment_effect_t eff;
    if (light_preset_decode(&eff, &s_wbuf[1 + name_len], (uint16_t)(s_wtotal - 1 - name_len), NULL) != ESP_OK) {
        send_result(conn, 1, (uint8_t)s_wid, 0, 1);
        s_writing = false;
        return;
    }

    esp_err_t err;
    uint8_t id = (uint8_t)s_wid;
    if (id == 0) {
        err = light_preset_create(name, &eff, &id);
    } else {
        err = light_preset_update(id, name, &eff);
    }
    light_preset_t saved;
    const char *final_name = (err == ESP_OK && light_preset_copy(id, &saved) == ESP_OK) ? saved.name : name;
    send_result_name(conn, 1, id, 0, status_of(err), final_name);
    s_writing = false;
}

static void preset_delete_now(uint16_t conn, uint8_t id)
{
    light_preset_t p;
    if (light_preset_copy(id, &p) != ESP_OK) {
        send_result(conn, 3, id, 0, 1);
        return;
    }
    if ((p.flags & LIGHT_PRESET_FLAG_ROM) != 0) {
        send_result(conn, 3, id, 0, 1);
        return;
    }
    uint8_t rom = (uint8_t)(LIGHT_PRESET_ID_ROM_FIRST + (uint8_t)p.effect.mode);
    clock_preset_replace(id, rom);
    esp_err_t err = light_preset_delete(id);
    send_result(conn, 3, id, rom, status_of(err));
}

int preset_svc_callback(uint16_t conn_handle, uint16_t attr_handle,
                        struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    (void)attr_handle;
    uintptr_t which = (uintptr_t)arg;

    if (which == 1 && ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR) {
        uint8_t ids[LIGHT_PRESET_USER_MAX + 8];
        uint8_t n = light_preset_list(ids, sizeof(ids));
        uint8_t pkt[1 + (LIGHT_PRESET_USER_MAX + 8) * 2];
        uint8_t used = 0;
        pkt[used++] = n;
        for (uint8_t i = 0; i < n; i++) {
            const light_preset_t *p = light_preset_get(ids[i]);
            if (p == NULL) {
                continue;
            }
            pkt[used++] = p->id;
            pkt[used++] = p->flags;
        }
        pkt[0] = (uint8_t)(used - 1) / 2;   /* 实际写进去的条数 */
        return put_out(ctxt, pkt, used);
    }

    if (which == 2 && ctxt->op == BLE_GATT_ACCESS_OP_WRITE_CHR) {
        uint8_t req[3];
        if (copy_in(ctxt, req, sizeof(req)) != 0) {
            return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        }
        s_prst_sel = req[0];
        uint16_t off = (uint16_t)(req[1] | (req[2] << 8));
        preset_read_chunk(conn_handle, s_prst_sel, off);
        return 0;
    }

    if (which == 4 && ctxt->op == BLE_GATT_ACCESS_OP_WRITE_CHR) {
        uint16_t len = OS_MBUF_PKTLEN(ctxt->om);
        if (len < 6 || len > 5 + 235) {
            return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        }
        uint8_t hdr[5];
        if (os_mbuf_copydata(ctxt->om, 0, 5, hdr) != 0) {
            return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        }
        uint16_t total = (uint16_t)(hdr[1] | (hdr[2] << 8));
        uint16_t off = (uint16_t)(hdr[3] | (hdr[4] << 8));
        if (total < 2 || total > sizeof(s_wbuf) || off + (len - 5) > total) {
            return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        }
        if (off == 0) {
            s_wid = hdr[0];
            s_wtotal = total;
            s_writing = true;
        }
        if (!s_writing || off + (len - 5) > sizeof(s_wbuf)) {
            return BLE_ATT_ERR_UNLIKELY;
        }
        if (os_mbuf_copydata(ctxt->om, 5, len - 5, &s_wbuf[off]) != 0) {
            return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        }
        if ((uint16_t)(off + (len - 5)) == total) {
            preset_write_done(conn_handle);
        }
        return 0;
    }

    if (which == 5) {
        if (ctxt->op == BLE_GATT_ACCESS_OP_WRITE_CHR) {
            uint8_t id = 0;
            if (copy_in(ctxt, &id, 1) != 0) {
                return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
            }
            s_prst_sel = id;
            uint16_t n = clock_preset_usage(id, s_usage, sizeof(s_usage));
            s_usage[n < sizeof(s_usage) ? n : sizeof(s_usage) - 1] = '\0';
            return 0;
        }
        return put_out(ctxt, s_usage, (uint16_t)strlen(s_usage));
    }

    if (which == 6 && ctxt->op == BLE_GATT_ACCESS_OP_WRITE_CHR) {
        uint8_t id = 0;
        if (copy_in(ctxt, &id, 1) != 0) {
            return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        }
        preset_delete_now(conn_handle, id);
        return 0;
    }

    return BLE_ATT_ERR_UNLIKELY;
}

/* ---------------- 调度 ---------------- */

int sched_svc_callback(uint16_t conn_handle, uint16_t attr_handle,
                       struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    (void)attr_handle;
    uintptr_t which = (uintptr_t)arg;

    if (which == 1 && ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR) {
        uint8_t ids[CLOCK_SCHED_MAX];
        uint8_t n = clock_sched_list(ids, sizeof(ids));
        uint8_t pkt[1 + CLOCK_SCHED_MAX];
        pkt[0] = n;
        memcpy(&pkt[1], ids, n);
        return put_out(ctxt, pkt, (uint16_t)(1 + n));
    }

    if (which == 2) {
        if (ctxt->op == BLE_GATT_ACCESS_OP_WRITE_CHR) {
            uint8_t id = 0;
            if (copy_in(ctxt, &id, 1) != 0) {
                return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
            }
            s_sched_sel = id;
            return 0;
        }
        clock_sched_t s;
        if (clock_sched_get(s_sched_sel, &s) != ESP_OK) {
            return BLE_ATT_ERR_UNLIKELY;
        }
        uint8_t name_len = (uint8_t)strnlen(s.name, LIGHT_PRESET_NAME_MAX - 1);
        uint8_t pkt[1 + 1 + LIGHT_PRESET_NAME_MAX + 1 + CLOCK_SLOT_MAX * 4];
        uint16_t used = 0;
        pkt[used++] = s.id;
        pkt[used++] = name_len;
        memcpy(&pkt[used], s.name, name_len);
        used = (uint16_t)(used + name_len);
        pkt[used++] = s.slot_count;
        memcpy(&pkt[used], s.at_sec, sizeof(uint32_t) * s.slot_count);
        used = (uint16_t)(used + sizeof(uint32_t) * s.slot_count);
        return put_out(ctxt, pkt, used);
    }

    if (which == 3 && ctxt->op == BLE_GATT_ACCESS_OP_WRITE_CHR) {
        uint16_t len = OS_MBUF_PKTLEN(ctxt->om);
        if (len < 3 || len > 1 + 1 + LIGHT_PRESET_NAME_MAX + 1 + CLOCK_SLOT_MAX * 4) {
            return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        }
        uint8_t raw[1 + 1 + LIGHT_PRESET_NAME_MAX + 1 + CLOCK_SLOT_MAX * 4];
        if (os_mbuf_copydata(ctxt->om, 0, len, raw) != 0) {
            return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        }
        clock_sched_t s;
        memset(&s, 0, sizeof(s));
        uint16_t p = 0;
        s.id = raw[p++];
        uint8_t name_len = raw[p++];
        if (name_len > LIGHT_PRESET_NAME_MAX - 1 || (uint16_t)(p + name_len + 1) > len) {
            return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        }
        memcpy(s.name, &raw[p], name_len);
        p = (uint16_t)(p + name_len);
        s.slot_count = raw[p++];
        if (s.slot_count == 0 || s.slot_count > CLOCK_SLOT_MAX ||
            (uint16_t)(p + s.slot_count * 4) != len) {
            return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        }
        memcpy(s.at_sec, &raw[p], sizeof(uint32_t) * s.slot_count);
        uint8_t id = 0;
        esp_err_t err = clock_sched_set(s.id, &s, &id);
        clock_sched_t saved;
        const char *final_name = (err == ESP_OK && clock_sched_get(id, &saved) == ESP_OK) ? saved.name : s.name;
        send_result_name(conn_handle, 4, id, 0, status_of(err), final_name);
        return err == ESP_OK ? 0 : BLE_ATT_ERR_UNLIKELY;
    }

    if (which == 4) {
        if (ctxt->op == BLE_GATT_ACCESS_OP_WRITE_CHR) {
            uint8_t seg = 0;
            if (copy_in(ctxt, &seg, 1) != 0) {
                return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
            }
            s_bind_sel = seg;
            return 0;
        }
        uint8_t sched = 0;
        uint8_t count = 0;
        uint8_t map[CLOCK_SLOT_MAX];
        memset(map, LIGHT_PRESET_ID_NONE, sizeof(map));
        if (clock_seg_bind_get(s_bind_sel, &sched, map, &count) != ESP_OK) {
            return BLE_ATT_ERR_UNLIKELY;
        }
        uint8_t pkt[3 + CLOCK_SLOT_MAX];
        pkt[0] = s_bind_sel;
        pkt[1] = sched;
        pkt[2] = count;
        memcpy(&pkt[3], map, count);
        return put_out(ctxt, pkt, (uint16_t)(3 + count));
    }

    if (which == 5 && ctxt->op == BLE_GATT_ACCESS_OP_WRITE_CHR) {
        uint16_t len = OS_MBUF_PKTLEN(ctxt->om);
        if (len < 3 || len > 3 + CLOCK_SLOT_MAX) {
            return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        }
        uint8_t raw[3 + CLOCK_SLOT_MAX];
        if (os_mbuf_copydata(ctxt->om, 0, len, raw) != 0) {
            return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        }
        uint8_t seg = raw[0];
        uint8_t sched = raw[1];
        uint8_t count = raw[2];
        if (count > CLOCK_SLOT_MAX || (uint16_t)(3 + count) != len) {
            return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        }
        esp_err_t err = clock_seg_bind_set(seg, sched, &raw[3], count);
        send_result(conn_handle, 5, seg, 0, status_of(err));
        return err == ESP_OK ? 0 : BLE_ATT_ERR_UNLIKELY;
    }

    if (which == 6) {
        if (ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR) {
            uint8_t pkt[CLOCK_IO_MAX * 2];
            for (uint8_t io = 1; io <= CLOCK_IO_MAX; io++) {
                pkt[(io - 1) * 2] = io;
                pkt[(io - 1) * 2 + 1] = (uint8_t)clock_io_mode_get(io);
            }
            return put_out(ctxt, pkt, sizeof(pkt));
        }
        uint8_t req[2];
        if (copy_in(ctxt, req, sizeof(req)) != 0) {
            return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        }
        /*
         * 打一条带时间戳的日志：从这条日志到灯真正变化之间的间隔，
         * 就是固件侧的延迟。如果这条日志本身就在点击后 5 s 才出现，
         * 那问题在 App（命令排队）或链路，不在调度器。
         */
        ESP_LOGI(TAG, "io_mode 写入 io=%u mode=%u t=%lldms",
                 req[0], req[1], (long long)(esp_timer_get_time() / 1000));
        esp_err_t err = clock_io_mode_set(req[0], (clock_io_mode_t)req[1]);
        send_result(conn_handle, 7, req[0], req[1], status_of(err));
        if (err == ESP_OK) {
            clock_scheduler_kick();
        }
        return err == ESP_OK ? 0 : BLE_ATT_ERR_UNLIKELY;
    }

    if (which == 7 && ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR) {
        uint8_t pkt[8 + CLOCK_SEG_MAX * 3];
        uint16_t used = 0;
        pkt[used++] = clock_time_valid() ? 1 : 0;
        uint32_t next = clock_next_event_sec();
        memcpy(&pkt[used], &next, sizeof(next));
        used = (uint16_t)(used + sizeof(next));
        uint16_t count_pos = used++;
        uint8_t count = 0;
        uint8_t ios[LIGHT_IO_MAX];
        uint8_t io_count = 0;
        light_io_list(ios, sizeof(ios), &io_count);
        for (uint8_t i = 0; i < io_count && used + 3 <= sizeof(pkt); i++) {
            uint8_t segs[LIGHT_SEG_MAX];
            uint8_t seg_count = 0;
            light_io_seg_list(ios[i], segs, sizeof(segs), &seg_count);
            for (uint8_t k = 0; k < seg_count && used + 3 <= sizeof(pkt); k++) {
                pkt[used++] = segs[k];
                pkt[used++] = clock_seg_active_preset(segs[k]);
                pkt[used++] = (uint8_t)clock_io_mode_get(ios[i]);
                count++;
            }
        }
        pkt[count_pos] = count;
        return put_out(ctxt, pkt, used);
    }

    if (which == 8 && ctxt->op == BLE_GATT_ACCESS_OP_WRITE_CHR) {
        uint8_t req[2];
        if (copy_in(ctxt, req, sizeof(req)) != 0) {
            return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        }
        uint8_t preset = LIGHT_PRESET_ID_NONE;
        esp_err_t err = clock_seg_commit(req[0], req[1] != 0, &preset);
        send_result(conn_handle, 2, req[0], preset, status_of(err));
        return err == ESP_OK ? 0 : BLE_ATT_ERR_UNLIKELY;
    }

    if (which == 9) {
        if (ctxt->op == BLE_GATT_ACCESS_OP_WRITE_CHR) {
            uint8_t id = 0;
            if (copy_in(ctxt, &id, 1) != 0) {
                return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
            }
            uint16_t n = clock_sched_usage(id, s_schd_usage, sizeof(s_schd_usage));
            s_schd_usage[n < sizeof(s_schd_usage) ? n : sizeof(s_schd_usage) - 1] = '\0';
            return 0;
        }
        return put_out(ctxt, s_schd_usage, (uint16_t)strlen(s_schd_usage));
    }

    if (which == 10 && ctxt->op == BLE_GATT_ACCESS_OP_WRITE_CHR) {
        uint8_t id = 0;
        if (copy_in(ctxt, &id, 1) != 0) {
            return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        }
        esp_err_t err = clock_sched_delete(id);
        send_result(conn_handle, 8, id, 0, status_of(err));
        return err == ESP_OK ? 0 : BLE_ATT_ERR_UNLIKELY;
    }

    if (which == 11 && ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR) {
        uint8_t pkt[8];
        uint8_t ios[LIGHT_IO_MAX];
        uint8_t io_count = 0;
        uint8_t seg_total = 0;
        light_io_list(ios, sizeof(ios), &io_count);
        for (uint8_t i = 0; i < io_count; i++) {
            uint8_t segs[LIGHT_SEG_MAX];
            uint8_t seg_count = 0;
            light_io_seg_list(ios[i], segs, sizeof(segs), &seg_count);
            seg_total = (uint8_t)(seg_total + seg_count);
        }
        pkt[0] = 0;   /* fw major */
        pkt[1] = 2;   /* fw minor */
        pkt[2] = 0;   /* fw patch */
        pkt[3] = io_count;
        pkt[4] = seg_total;
        pkt[5] = LIGHT_PRESET_USER_MAX;
        pkt[6] = CLOCK_SCHED_MAX;
        pkt[7] = CLOCK_SLOT_MAX;
        return put_out(ctxt, pkt, sizeof(pkt));
    }

    return BLE_ATT_ERR_UNLIKELY;
}
