/**
 * @file NimBLE.h
 * @author LG_frir
 * @date 2026-10-09 10:25:03
 */

#ifndef __NIMBLE_H__
#define __NIMBLE_H__

#include <stdbool.h>
#include <stdint.h>

#include "esp_log.h"
#include "nvs_flash.h"
/* BLE */
#include "nimble/nimble_port.h"
#include "nimble/nimble_port_freertos.h"
#include "host/ble_hs.h"
#include "host/util/util.h"
#include "services/gap/ble_svc_gap.h"
#include "services/gatt/ble_svc_gatt.h"
#include "store/config/ble_store_config.h"

#define DEVICE_NAME "LEDDrive"

void nimble_init_as_prph(void);

/* 仪表盘用的 BLE 状态 */
bool nimble_is_connected(void);
int8_t nimble_get_rssi(void);
uint16_t nimble_get_mtu(void);
uint32_t nimble_get_conn_seconds(void);

#endif /* __NIMBLE_H__ */