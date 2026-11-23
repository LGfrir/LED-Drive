/**
 * @file gatt_svc.c
 * @author LG_frir
 * @date 2026-11-11 17:32:41
 *
 * 先写 io_select / seg_select，后面的读和写都作用在选中的那一个上。
 * 整数都是小端。
 *
 *   io_topology     见 light_topology_pack()
 *   io_select       [io_id u8]
 *   io_brightness   [u8]
 *   io_refresh      [refresh_ms u16]          5..50
 *   seg_select      [seg_id u8]
 *   light_mode      [u8]                      light_mode_t
 *   seg_brightness  [u8]
 *   cfg_lt_wave     [speed_pct u16][dir u8]   dir: 0 右, 1 左
 *   cfg_lt_static   [r][g][b]
 *   cfg_lt_breath   [r][g][b][period_ms u16]
 *   cfg_lt_chase    [count u8][speed u16][dir u8][r g b]xcount
 *   cfg_lt_points   [wrap u8][count u8]
 *                   重复 count 次：[pct u8][period u16][color_count u8][r g b]xN
 *   cfg_lt_flash    [mode u8][period_ms u16]  mode: 0 会灭, 1 直接切换
 */

#include "task/gatt_svc.h"
#include "task/auth_svc.h"

#include "driver/led_strip/light_mode_driver.h"
#include "task/clock_scheduler.h"

#include <string.h>

static uint8_t s_io_id;
static uint8_t s_seg_id;
static bool s_io_valid;
static bool s_seg_valid;

static int pkt_in(struct ble_gatt_access_ctxt *ctxt, void *dst, uint16_t expect)
{
    if (OS_MBUF_PKTLEN(ctxt->om) != expect) {
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }
    if (os_mbuf_copydata(ctxt->om, 0, expect, dst) != 0) {
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }
    return 0;
}

static int pkt_out(struct ble_gatt_access_ctxt *ctxt, const void *src, uint16_t len)
{
    int rc = os_mbuf_append(ctxt->om, src, len);
    return rc == 0 ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
}

static int map_err(esp_err_t err)
{
    if (err == ESP_OK) {
        return 0;
    }
    if (err == ESP_ERR_INVALID_ARG || err == ESP_ERR_INVALID_SIZE) {
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }
    return BLE_ATT_ERR_UNLIKELY;
}

/* 段级写入成功：时间模式下让调度器进入草稿态，暂停接管这一段 */
static int seg_write_done(esp_err_t err)
{
    if (err == ESP_OK && s_seg_valid) {
        clock_seg_local_write(s_seg_id);
    }
    return map_err(err);
}

int io_svc_callback(uint16_t conn_handle, uint16_t attr_handle,
                    struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    intptr_t which = (intptr_t)arg;
    (void)conn_handle;
    (void)attr_handle;
    int locked = auth_guard();
    if (locked != 0) {
        return locked;
    }

    if (ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR) {
        switch (which) {
        case 1: {
            uint8_t buf[512];
            uint16_t n = light_topology_pack(buf, sizeof(buf));
            if (n == 0) {
                return BLE_ATT_ERR_UNLIKELY;
            }
            return pkt_out(ctxt, buf, n);
        }
        case 2:
            if (!s_io_valid) {
                return BLE_ATT_ERR_UNLIKELY;
            }
            return pkt_out(ctxt, &s_io_id, sizeof(s_io_id));
        case 3: {
            uint8_t brightness = 0;
            if (!s_io_valid || light_io_get(s_io_id, &brightness, NULL, NULL) != ESP_OK) {
                return BLE_ATT_ERR_UNLIKELY;
            }
            return pkt_out(ctxt, &brightness, sizeof(brightness));
        }
        case 4: {
            uint16_t refresh = 0;
            if (!s_io_valid || light_io_get(s_io_id, NULL, &refresh, NULL) != ESP_OK) {
                return BLE_ATT_ERR_UNLIKELY;
            }
            return pkt_out(ctxt, &refresh, sizeof(refresh));
        }
        default:
            return BLE_ATT_ERR_UNLIKELY;
        }
    }

    if (ctxt->op != BLE_GATT_ACCESS_OP_WRITE_CHR) {
        return BLE_ATT_ERR_UNLIKELY;
    }

    switch (which) {
    case 2: {
        uint8_t id = 0;
        int err = pkt_in(ctxt, &id, sizeof(id));
        if (err != 0) {
            return err;
        }
        if (light_io_get(id, NULL, NULL, NULL) != ESP_OK) {
            return BLE_ATT_ERR_UNLIKELY;
        }
        s_io_id = id;
        s_io_valid = true;
        return 0;
    }
    case 3: {
        uint8_t brightness = 0;
        int err = pkt_in(ctxt, &brightness, sizeof(brightness));
        if (err != 0) {
            return err;
        }
        if (!s_io_valid) {
            return BLE_ATT_ERR_UNLIKELY;
        }
        return map_err(light_io_set_brightness(s_io_id, brightness));
    }
    case 4: {
        uint16_t refresh = 0;
        int err = pkt_in(ctxt, &refresh, sizeof(refresh));
        if (err != 0) {
            return err;
        }
        if (!s_io_valid) {
            return BLE_ATT_ERR_UNLIKELY;
        }
        return map_err(light_io_set_refresh(s_io_id, refresh));
    }
    case 5:
        /* 任意 1 字节。所有灯段的相位在这一刻一起归零。 */
        if (OS_MBUF_PKTLEN(ctxt->om) != 1) {
            return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        }
        light_reset_phase();
        return 0;
    default:
        return BLE_ATT_ERR_UNLIKELY;
    }
}

int light_svc_callback(uint16_t conn_handle, uint16_t attr_handle,
                       struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    intptr_t which = (intptr_t)arg;
    (void)conn_handle;
    (void)attr_handle;
    int locked = auth_guard();
    if (locked != 0) {
        return locked;
    }

    if (ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR) {
        switch (which) {
        case 1:
            if (!s_seg_valid) {
                return BLE_ATT_ERR_UNLIKELY;
            }
            return pkt_out(ctxt, &s_seg_id, sizeof(s_seg_id));
        case 2: {
            light_mode_t mode = LIGHT_MODE_OFF;
            if (!s_seg_valid || light_seg_get_common(s_seg_id, &mode, NULL) != ESP_OK) {
                return BLE_ATT_ERR_UNLIKELY;
            }
            uint8_t value = (uint8_t)mode;
            return pkt_out(ctxt, &value, sizeof(value));
        }
        case 3: {
            uint8_t brightness = 0;
            if (!s_seg_valid || light_seg_get_common(s_seg_id, NULL, &brightness) != ESP_OK) {
                return BLE_ATT_ERR_UNLIKELY;
            }
            return pkt_out(ctxt, &brightness, sizeof(brightness));
        }
        default:
            return BLE_ATT_ERR_UNLIKELY;
        }
    }

    if (ctxt->op != BLE_GATT_ACCESS_OP_WRITE_CHR) {
        return BLE_ATT_ERR_UNLIKELY;
    }

    switch (which) {
    case 1: {
        uint8_t id = 0;
        int err = pkt_in(ctxt, &id, sizeof(id));
        if (err != 0) {
            return err;
        }
        if (light_seg_get_common(id, NULL, NULL) != ESP_OK) {
            return BLE_ATT_ERR_UNLIKELY;
        }
        s_seg_id = id;
        s_seg_valid = true;
        return 0;
    }
    case 2: {
        uint8_t mode = 0;
        int err = pkt_in(ctxt, &mode, sizeof(mode));
        if (err != 0) {
            return err;
        }
        if (!s_seg_valid) {
            return BLE_ATT_ERR_UNLIKELY;
        }
        return seg_write_done(light_seg_set_mode(s_seg_id, (light_mode_t)mode));
    }
    case 3: {
        uint8_t brightness = 0;
        int err = pkt_in(ctxt, &brightness, sizeof(brightness));
        if (err != 0) {
            return err;
        }
        if (!s_seg_valid) {
            return BLE_ATT_ERR_UNLIKELY;
        }
        return seg_write_done(light_seg_set_brightness(s_seg_id, brightness));
    }
    default:
        return BLE_ATT_ERR_UNLIKELY;
    }
}

static int read_selected(light_mode_t which, struct ble_gatt_access_ctxt *ctxt)
{
    if (!s_seg_valid) {
        return BLE_ATT_ERR_UNLIKELY;
    }
    switch (which) {
    case LIGHT_MODE_WAVE: {
        wave_cfg_t cfg;
        if (light_seg_get_wave(s_seg_id, &cfg) != ESP_OK) {
            return BLE_ATT_ERR_UNLIKELY;
        }
        uint8_t pkt[3];
        memcpy(pkt, &cfg.speed_pct, sizeof(cfg.speed_pct));
        pkt[2] = (uint8_t)cfg.dir;
        return pkt_out(ctxt, pkt, sizeof(pkt));
    }
    case LIGHT_MODE_STATIC: {
        static_cfg_t cfg;
        if (light_seg_get_static(s_seg_id, &cfg) != ESP_OK) {
            return BLE_ATT_ERR_UNLIKELY;
        }
        return pkt_out(ctxt, &cfg.color, sizeof(cfg.color));
    }
    case LIGHT_MODE_BREATH: {
        breath_cfg_t cfg;
        if (light_seg_get_breath(s_seg_id, &cfg) != ESP_OK) {
            return BLE_ATT_ERR_UNLIKELY;
        }
        uint8_t pkt[5];
        memcpy(pkt, &cfg.color, sizeof(cfg.color));
        memcpy(pkt + 3, &cfg.period_ms, sizeof(cfg.period_ms));
        return pkt_out(ctxt, pkt, sizeof(pkt));
    }
    case LIGHT_MODE_CHASE: {
        chase_cfg_t cfg;
        if (light_seg_get_chase(s_seg_id, &cfg) != ESP_OK) {
            return BLE_ATT_ERR_UNLIKELY;
        }
        uint8_t pkt[4 + LIGHT_COLOR_MAX * 3];
        pkt[0] = cfg.count;
        memcpy(pkt + 1, &cfg.speed_pct, sizeof(cfg.speed_pct));
        pkt[3] = (uint8_t)cfg.dir;
        memcpy(pkt + 4, cfg.colors, sizeof(color_t) * cfg.count);
        return pkt_out(ctxt, pkt, (uint16_t)(4 + sizeof(color_t) * cfg.count));
    }
    case LIGHT_MODE_POINTS: {
        points_cfg_t cfg;
        if (light_seg_get_points(s_seg_id, &cfg) != ESP_OK) {
            return BLE_ATT_ERR_UNLIKELY;
        }
        uint8_t pkt[2 + LIGHT_POINT_MAX * (4 + LIGHT_COLOR_MAX * 3)];
        uint16_t used = 0;
        pkt[used++] = cfg.wrap ? 1 : 0;
        pkt[used++] = cfg.count;
        for (uint8_t i = 0; i < cfg.count; i++) {
            point_cfg_t *point = &cfg.points[i];
            pkt[used++] = point->position_pct;
            memcpy(pkt + used, &point->period_ms, sizeof(point->period_ms));
            used = (uint16_t)(used + sizeof(point->period_ms));
            pkt[used++] = point->count;
            memcpy(pkt + used, point->colors, sizeof(color_t) * point->count);
            used = (uint16_t)(used + sizeof(color_t) * point->count);
        }
        return pkt_out(ctxt, pkt, used);
    }
    case LIGHT_MODE_FLASH: {
        flash_cfg_t cfg;
        if (light_seg_get_flash(s_seg_id, &cfg) != ESP_OK) {
            return BLE_ATT_ERR_UNLIKELY;
        }
        uint8_t pkt[3];
        pkt[0] = (uint8_t)cfg.mode;
        memcpy(pkt + 1, &cfg.period_ms, sizeof(cfg.period_ms));
        return pkt_out(ctxt, pkt, sizeof(pkt));
    }
    default:
        return BLE_ATT_ERR_UNLIKELY;
    }
}

/*
 * 多点渐变的包长度不固定：每个点自己带颜色个数。
 * 先看 wrap 和点数，再逐点核对剩余字节，对不上就整包拒绝。
 */
static int write_points(struct ble_gatt_access_ctxt *ctxt)
{
    uint16_t len = OS_MBUF_PKTLEN(ctxt->om);
    uint8_t raw[2 + LIGHT_POINT_MAX * (4 + LIGHT_COLOR_MAX * 3)];
    if (len < 2 || len > sizeof(raw)) {
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }
    if (os_mbuf_copydata(ctxt->om, 0, len, raw) != 0) {
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }

    points_cfg_t cfg = { 0 };
    uint16_t used = 0;
    cfg.wrap = raw[used++] != 0;
    cfg.count = raw[used++];
    if (cfg.count < 1 || cfg.count > LIGHT_POINT_MAX) {
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }
    for (uint8_t i = 0; i < cfg.count; i++) {
        if ((uint16_t)(used + 4) > len) {
            return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        }
        point_cfg_t *point = &cfg.points[i];
        point->position_pct = raw[used++];
        memcpy(&point->period_ms, raw + used, sizeof(point->period_ms));
        used = (uint16_t)(used + sizeof(point->period_ms));
        point->count = raw[used++];
        if (point->count < 1 || point->count > LIGHT_COLOR_MAX) {
            return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        }
        uint16_t bytes = (uint16_t)(sizeof(color_t) * point->count);
        if ((uint16_t)(used + bytes) > len) {
            return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        }
        memcpy(point->colors, raw + used, bytes);
        used = (uint16_t)(used + bytes);
    }
    if (used != len) {
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }
    return seg_write_done(light_seg_set_points(s_seg_id, &cfg));
}

int config_svc_callback(uint16_t conn_handle, uint16_t attr_handle,
                        struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    light_mode_t which = (light_mode_t)(intptr_t)arg;
    (void)conn_handle;
    (void)attr_handle;
    int locked = auth_guard();
    if (locked != 0) {
        return locked;
    }

    if (ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR) {
        return read_selected(which, ctxt);
    }
    if (ctxt->op != BLE_GATT_ACCESS_OP_WRITE_CHR) {
        return BLE_ATT_ERR_UNLIKELY;
    }
    if (!s_seg_valid) {
        return BLE_ATT_ERR_UNLIKELY;
    }

    switch (which) {
    case LIGHT_MODE_WAVE: {
        uint8_t raw[3];
        int err = pkt_in(ctxt, raw, sizeof(raw));
        if (err != 0) {
            return err;
        }
        wave_cfg_t cfg = {
            .dir = (light_dir_t)raw[2],
        };
        memcpy(&cfg.speed_pct, raw, sizeof(cfg.speed_pct));
        return seg_write_done(light_seg_set_wave(s_seg_id, &cfg));
    }
    case LIGHT_MODE_STATIC: {
        static_cfg_t cfg;
        int err = pkt_in(ctxt, &cfg.color, sizeof(cfg.color));
        if (err != 0) {
            return err;
        }
        return seg_write_done(light_seg_set_static(s_seg_id, &cfg));
    }
    case LIGHT_MODE_BREATH: {
        uint8_t raw[5];
        int err = pkt_in(ctxt, raw, sizeof(raw));
        if (err != 0) {
            return err;
        }
        breath_cfg_t cfg;
        memcpy(&cfg.color, raw, sizeof(cfg.color));
        memcpy(&cfg.period_ms, raw + 3, sizeof(cfg.period_ms));
        return seg_write_done(light_seg_set_breath(s_seg_id, &cfg));
    }
    case LIGHT_MODE_CHASE: {
        uint8_t head[4];
        if (OS_MBUF_PKTLEN(ctxt->om) < sizeof(head)) {
            return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        }
        if (os_mbuf_copydata(ctxt->om, 0, sizeof(head), head) != 0) {
            return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        }
        chase_cfg_t cfg = {
            .count = head[0],
            .dir = (light_dir_t)head[3],
        };
        memcpy(&cfg.speed_pct, head + 1, sizeof(cfg.speed_pct));
        uint16_t color_bytes = (uint16_t)(sizeof(color_t) * cfg.count);
        if (cfg.count < 1 || cfg.count > LIGHT_COLOR_MAX ||
            OS_MBUF_PKTLEN(ctxt->om) != sizeof(head) + color_bytes) {
            return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        }
        if (os_mbuf_copydata(ctxt->om, sizeof(head), color_bytes, cfg.colors) != 0) {
            return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
        }
        return seg_write_done(light_seg_set_chase(s_seg_id, &cfg));
    }
    case LIGHT_MODE_POINTS:
        return write_points(ctxt);
    case LIGHT_MODE_FLASH: {
        uint8_t raw[3];
        int err = pkt_in(ctxt, raw, sizeof(raw));
        if (err != 0) {
            return err;
        }
        flash_cfg_t cfg = {
            .mode = (flash_mode_t)raw[0],
        };
        memcpy(&cfg.period_ms, raw + 1, sizeof(cfg.period_ms));
        return seg_write_done(light_seg_set_flash(s_seg_id, &cfg));
    }
    default:
        return BLE_ATT_ERR_UNLIKELY;
    }
}
