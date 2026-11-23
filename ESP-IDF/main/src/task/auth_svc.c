/**
 * @file auth_svc.c
 * @brief 连接后先报手机 UUID 和名字。名单里有就直接放行，没有就等六位数字密码。
 *
 *   auth_id    写 16 字节 UUID + 名字长度 + 名字
 *   auth_pin   写 6 字节数字
 *   auth_stat  读 1 字节，1 表示这次连接已经放行
 *   auth_chg   写 旧密码 6 字节 + 新密码 6 字节
 *   auth_lst   先读个数；写序号后再读这一条（UUID 16 + 名字长度 + 名字）
 *   auth_del   写 16 字节 UUID
 *   auth_name  读 / 写蓝牙广播名
 */

#include "task/auth_svc.h"
#include "task/gatt_svc.h"

#include "host/ble_hs.h"
#include "services/gap/ble_svc_gap.h"

#include "nvs.h"

#include <string.h>

#define AUTH_NS "auth"
#define AUTH_BLOB_VER 1

typedef struct {
    uint8_t uuid[16];
    uint8_t name_len;
    char name[AUTH_NAME_MAX];
} auth_dev_t;

static char s_pin[AUTH_PIN_LEN + 1] = "123456";
static char s_bt_name[AUTH_NAME_MAX + 1] = DEVICE_NAME;
static auth_dev_t s_devs[AUTH_DEV_MAX];
static uint8_t s_dev_count;

static bool s_ok;
static bool s_have_pending;
static uint8_t s_pending_uuid[16];
static char s_pending_name[AUTH_NAME_MAX];
static uint8_t s_pending_name_len;
static uint8_t s_session_uuid[16];
static bool s_session_valid;
static uint8_t s_list_pick = 0xFF;

static bool six_digits(const uint8_t *raw, uint16_t len)
{
    if (len != AUTH_PIN_LEN) {
        return false;
    }
    for (uint16_t i = 0; i < AUTH_PIN_LEN; i++) {
        if (raw[i] < '0' || raw[i] > '9') {
            return false;
        }
    }
    return true;
}

static bool printable_name(const uint8_t *raw, uint16_t len)
{
    if (len == 0 || len > AUTH_NAME_MAX) {
        return false;
    }
    for (uint16_t i = 0; i < len; i++) {
        if (raw[i] < 0x20) {
            return false;
        }
    }
    return true;
}

static int find_dev(const uint8_t uuid[16])
{
    for (uint8_t i = 0; i < s_dev_count; i++) {
        if (memcmp(s_devs[i].uuid, uuid, 16) == 0) {
            return (int)i;
        }
    }
    return -1;
}

static void remember_session(const uint8_t uuid[16])
{
    memcpy(s_session_uuid, uuid, 16);
    s_session_valid = true;
    s_ok = true;
    s_have_pending = false;
}

static esp_err_t save_pin(void)
{
    nvs_handle_t handle;
    esp_err_t err = nvs_open(AUTH_NS, NVS_READWRITE, &handle);
    if (err != ESP_OK) {
        return err;
    }
    err = nvs_set_str(handle, "pin", s_pin);
    if (err == ESP_OK) {
        err = nvs_commit(handle);
    }
    nvs_close(handle);
    return err;
}

static esp_err_t save_name(void)
{
    nvs_handle_t handle;
    esp_err_t err = nvs_open(AUTH_NS, NVS_READWRITE, &handle);
    if (err != ESP_OK) {
        return err;
    }
    err = nvs_set_str(handle, "btname", s_bt_name);
    if (err == ESP_OK) {
        err = nvs_commit(handle);
    }
    nvs_close(handle);
    return err;
}

static esp_err_t save_devs(void)
{
    uint8_t blob[2 + AUTH_DEV_MAX * (16 + 1 + AUTH_NAME_MAX)];
    uint16_t used = 0;
    blob[used++] = AUTH_BLOB_VER;
    blob[used++] = s_dev_count;
    for (uint8_t i = 0; i < s_dev_count; i++) {
        memcpy(&blob[used], s_devs[i].uuid, 16);
        used = (uint16_t)(used + 16);
        blob[used++] = s_devs[i].name_len;
        if (s_devs[i].name_len > 0) {
            memcpy(&blob[used], s_devs[i].name, s_devs[i].name_len);
            used = (uint16_t)(used + s_devs[i].name_len);
        }
    }

    nvs_handle_t handle;
    esp_err_t err = nvs_open(AUTH_NS, NVS_READWRITE, &handle);
    if (err != ESP_OK) {
        return err;
    }
    err = nvs_set_blob(handle, "devs", blob, used);
    if (err == ESP_OK) {
        err = nvs_commit(handle);
    }
    nvs_close(handle);
    return err;
}

static void load_devs(const uint8_t *blob, size_t len)
{
    s_dev_count = 0;
    if (len < 2 || blob[0] != AUTH_BLOB_VER) {
        return;
    }
    uint8_t count = blob[1];
    if (count > AUTH_DEV_MAX) {
        count = AUTH_DEV_MAX;
    }
    size_t at = 2;
    for (uint8_t i = 0; i < count; i++) {
        if (at + 17 > len) {
            break;
        }
        auth_dev_t *dev = &s_devs[s_dev_count];
        memcpy(dev->uuid, &blob[at], 16);
        at += 16;
        uint8_t name_len = blob[at++];
        if (name_len > AUTH_NAME_MAX || at + name_len > len) {
            break;
        }
        memset(dev->name, 0, sizeof(dev->name));
        if (name_len > 0) {
            memcpy(dev->name, &blob[at], name_len);
        }
        dev->name_len = name_len;
        at += name_len;
        s_dev_count++;
    }
}

static void copy_name(auth_dev_t *dev, const char *name, uint8_t name_len)
{
    memset(dev->name, 0, sizeof(dev->name));
    dev->name_len = name_len;
    if (name_len > 0) {
        memcpy(dev->name, name, name_len);
    }
}

static int admit_device(const uint8_t uuid[16], const char *name, uint8_t name_len)
{
    int found = find_dev(uuid);
    if (found >= 0) {
        copy_name(&s_devs[found], name, name_len);
    } else {
        if (s_dev_count >= AUTH_DEV_MAX) {
            memmove(&s_devs[0], &s_devs[1], (AUTH_DEV_MAX - 1) * sizeof(s_devs[0]));
            s_dev_count = AUTH_DEV_MAX - 1;
        }
        auth_dev_t *dev = &s_devs[s_dev_count++];
        memcpy(dev->uuid, uuid, 16);
        copy_name(dev, name, name_len);
    }
    esp_err_t err = save_devs();
    if (err != ESP_OK) {
        return AUTH_ERR_STATE;
    }
    remember_session(uuid);
    return 0;
}

void auth_init(void)
{
    nvs_handle_t handle;
    if (nvs_open(AUTH_NS, NVS_READWRITE, &handle) != ESP_OK) {
        return;
    }

    size_t len = sizeof(s_pin);
    if (nvs_get_str(handle, "pin", s_pin, &len) != ESP_OK || !six_digits((const uint8_t *)s_pin, AUTH_PIN_LEN)) {
        memcpy(s_pin, "123456", AUTH_PIN_LEN + 1);
        nvs_set_str(handle, "pin", s_pin);
        nvs_commit(handle);
    }
    s_pin[AUTH_PIN_LEN] = '\0';

    len = sizeof(s_bt_name);
    if (nvs_get_str(handle, "btname", s_bt_name, &len) != ESP_OK || s_bt_name[0] == '\0') {
        memset(s_bt_name, 0, sizeof(s_bt_name));
        strncpy(s_bt_name, DEVICE_NAME, AUTH_NAME_MAX);
    }

    uint8_t blob[2 + AUTH_DEV_MAX * (16 + 1 + AUTH_NAME_MAX)];
    size_t blob_len = sizeof(blob);
    if (nvs_get_blob(handle, "devs", blob, &blob_len) == ESP_OK) {
        load_devs(blob, blob_len);
    }
    nvs_close(handle);
}

void auth_on_disconnect(void)
{
    s_ok = false;
    s_have_pending = false;
    s_pending_name_len = 0;
    s_session_valid = false;
    s_list_pick = 0xFF;
}

const char *auth_device_name(void)
{
    return s_bt_name[0] != '\0' ? s_bt_name : DEVICE_NAME;
}

int auth_guard(void)
{
    return s_ok ? 0 : BLE_ATT_ERR_INSUFFICIENT_AUTHEN;
}

static int take(struct ble_gatt_access_ctxt *ctxt, uint8_t *dst, uint16_t cap, uint16_t *out_len)
{
    uint16_t len = OS_MBUF_PKTLEN(ctxt->om);
    if (len > cap) {
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }
    if (len > 0 && os_mbuf_copydata(ctxt->om, 0, len, dst) != 0) {
        return BLE_ATT_ERR_INVALID_ATTR_VALUE_LEN;
    }
    *out_len = len;
    return 0;
}

static int put(struct ble_gatt_access_ctxt *ctxt, const void *src, uint16_t len)
{
    return os_mbuf_append(ctxt->om, src, len) == 0 ? 0 : BLE_ATT_ERR_INSUFFICIENT_RES;
}

static int on_identify(struct ble_gatt_access_ctxt *ctxt)
{
    uint8_t raw[16 + 1 + AUTH_NAME_MAX];
    uint16_t len = 0;
    int err = take(ctxt, raw, sizeof(raw), &len);
    if (err != 0) {
        return err;
    }
    if (len < 17) {
        return AUTH_ERR_FORMAT;
    }
    uint8_t name_len = raw[16];
    if ((uint16_t)(17 + name_len) != len) {
        return AUTH_ERR_FORMAT;
    }
    if (name_len > 0 && !printable_name(&raw[17], name_len)) {
        return AUTH_ERR_FORMAT;
    }

    int found = find_dev(raw);
    if (found >= 0) {
        if (memcmp(s_devs[found].name, &raw[17], name_len) != 0 || s_devs[found].name_len != name_len) {
            copy_name(&s_devs[found], (const char *)&raw[17], name_len);
            save_devs();
        }
        remember_session(raw);
        return 0;
    }

    memcpy(s_pending_uuid, raw, 16);
    memset(s_pending_name, 0, sizeof(s_pending_name));
    s_pending_name_len = name_len;
    if (name_len > 0) {
        memcpy(s_pending_name, &raw[17], name_len);
    }
    s_have_pending = true;
    s_ok = false;
    s_session_valid = false;
    return 0;
}

static int on_pin(struct ble_gatt_access_ctxt *ctxt)
{
    uint8_t raw[AUTH_PIN_LEN];
    uint16_t len = 0;
    int err = take(ctxt, raw, sizeof(raw), &len);
    if (err != 0) {
        return err;
    }
    if (!six_digits(raw, len)) {
        return AUTH_ERR_FORMAT;
    }
    if (!s_have_pending) {
        return AUTH_ERR_STATE;
    }
    if (memcmp(raw, s_pin, AUTH_PIN_LEN) != 0) {
        return AUTH_ERR_PIN;
    }
    return admit_device(s_pending_uuid, s_pending_name, s_pending_name_len);
}

static int on_change(struct ble_gatt_access_ctxt *ctxt)
{
    if (!s_ok) {
        return AUTH_ERR_STATE;
    }
    uint8_t raw[AUTH_PIN_LEN * 2];
    uint16_t len = 0;
    int err = take(ctxt, raw, sizeof(raw), &len);
    if (err != 0) {
        return err;
    }
    if (len != AUTH_PIN_LEN * 2 || !six_digits(raw, AUTH_PIN_LEN) || !six_digits(raw + AUTH_PIN_LEN, AUTH_PIN_LEN)) {
        return AUTH_ERR_FORMAT;
    }
    if (memcmp(raw, s_pin, AUTH_PIN_LEN) != 0) {
        return AUTH_ERR_OLD;
    }
    memcpy(s_pin, raw + AUTH_PIN_LEN, AUTH_PIN_LEN);
    s_pin[AUTH_PIN_LEN] = '\0';
    return save_pin() == ESP_OK ? 0 : AUTH_ERR_STATE;
}

static int on_delete(struct ble_gatt_access_ctxt *ctxt)
{
    if (!s_ok) {
        return AUTH_ERR_STATE;
    }
    uint8_t uuid[16];
    uint16_t len = 0;
    int err = take(ctxt, uuid, sizeof(uuid), &len);
    if (err != 0) {
        return err;
    }
    if (len != 16) {
        return AUTH_ERR_FORMAT;
    }
    int found = find_dev(uuid);
    if (found >= 0) {
        if (found < s_dev_count - 1) {
            memmove(&s_devs[found], &s_devs[found + 1], (s_dev_count - found - 1) * sizeof(s_devs[0]));
        }
        s_dev_count--;
        err = save_devs();
        if (err != ESP_OK) {
            return AUTH_ERR_STATE;
        }
    }
    if (s_session_valid && memcmp(uuid, s_session_uuid, 16) == 0) {
        s_ok = false;
        s_session_valid = false;
    }
    return 0;
}

static int on_name_write(struct ble_gatt_access_ctxt *ctxt)
{
    if (!s_ok) {
        return AUTH_ERR_STATE;
    }
    uint8_t raw[AUTH_NAME_MAX];
    uint16_t len = 0;
    int err = take(ctxt, raw, sizeof(raw), &len);
    if (err != 0) {
        return err;
    }
    if (!printable_name(raw, len)) {
        return AUTH_ERR_FORMAT;
    }
    memset(s_bt_name, 0, sizeof(s_bt_name));
    memcpy(s_bt_name, raw, len);
    if (ble_svc_gap_device_name_set(s_bt_name) != 0) {
        return AUTH_ERR_FORMAT;
    }
    return save_name() == ESP_OK ? 0 : AUTH_ERR_STATE;
}

static int on_list_read(struct ble_gatt_access_ctxt *ctxt)
{
    if (!s_ok) {
        return AUTH_ERR_STATE;
    }
    if (s_list_pick != 0xFF) {
        uint8_t index = s_list_pick;
        s_list_pick = 0xFF;
        if (index >= s_dev_count) {
            return AUTH_ERR_STATE;
        }
        const auth_dev_t *dev = &s_devs[index];
        uint8_t pkt[16 + 1 + AUTH_NAME_MAX];
        memcpy(pkt, dev->uuid, 16);
        pkt[16] = dev->name_len;
        if (dev->name_len > 0) {
            memcpy(&pkt[17], dev->name, dev->name_len);
        }
        return put(ctxt, pkt, (uint16_t)(17 + dev->name_len));
    }
    return put(ctxt, &s_dev_count, 1);
}

int auth_svc_callback(uint16_t conn_handle, uint16_t attr_handle,
                      struct ble_gatt_access_ctxt *ctxt, void *arg)
{
    (void)conn_handle;
    (void)attr_handle;
    intptr_t which = (intptr_t)arg;

    if (which == 3 && ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR) {
        uint8_t flag = s_ok ? 1 : 0;
        return put(ctxt, &flag, 1);
    }
    if (which == 5 && ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR) {
        return on_list_read(ctxt);
    }
    if (which == 7 && ctxt->op == BLE_GATT_ACCESS_OP_READ_CHR) {
        if (!s_ok) {
            return AUTH_ERR_STATE;
        }
        uint16_t len = (uint16_t)strlen(auth_device_name());
        return put(ctxt, auth_device_name(), len);
    }

    if (ctxt->op != BLE_GATT_ACCESS_OP_WRITE_CHR) {
        return BLE_ATT_ERR_UNLIKELY;
    }
    switch (which) {
    case 1:
        return on_identify(ctxt);
    case 2:
        return on_pin(ctxt);
    case 4:
        return on_change(ctxt);
    case 5: {
        if (!s_ok) {
            return AUTH_ERR_STATE;
        }
        uint8_t index = 0;
        uint16_t len = 0;
        int err = take(ctxt, &index, 1, &len);
        if (err != 0) {
            return err;
        }
        if (len != 1 || index >= s_dev_count) {
            return AUTH_ERR_FORMAT;
        }
        s_list_pick = index;
        return 0;
    }
    case 6:
        return on_delete(ctxt);
    case 7:
        return on_name_write(ctxt);
    default:
        return BLE_ATT_ERR_UNLIKELY;
    }
}
