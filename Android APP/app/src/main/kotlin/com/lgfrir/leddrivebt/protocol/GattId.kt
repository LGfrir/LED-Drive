package com.lgfrir.leddrivebt.protocol

import java.nio.ByteBuffer
import java.util.UUID

/**
 * 固件用 BLE_UUID128_INIT 把 ASCII 名字按小端放进 128 位 UUID。
 * 这里按同样的字节序还原成 Android 使用的 UUID。
 */
fun firmwareUuid(name: String): UUID {
    val le = ByteArray(16)
    val raw = name.toByteArray(Charsets.US_ASCII)
    require(raw.size <= 16) { name }
    raw.copyInto(le)
    val be = le.reversedArray()
    val buffer = ByteBuffer.wrap(be)
    return UUID(buffer.long, buffer.long)
}

fun firmwareUuidBytes(name: String): ByteArray {
    val le = ByteArray(16)
    name.toByteArray(Charsets.US_ASCII).copyInto(le)
    return le
}

object GattId {
    const val DEVICE_NAME = "LEDDrive"
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    val IO_SVC = firmwareUuid("io_svc")
    val IO_TOPOLOGY = firmwareUuid("io_topology")
    val IO_SELECT = firmwareUuid("io_select")
    val IO_BRIGHTNESS = firmwareUuid("io_brightness")
    val IO_REFRESH = firmwareUuid("io_refresh")
    val PHASE_ZERO = firmwareUuid("phase_zero")

    val LIGHT_SVC = firmwareUuid("light_svc")
    val SEG_SELECT = firmwareUuid("seg_select")
    val LIGHT_MODE = firmwareUuid("light_mode")
    val SEG_BRIGHTNESS = firmwareUuid("seg_brightness")

    val CFG_SVC = firmwareUuid("cfg_svc")
    val WAVE = firmwareUuid("cfg_lt_wave")
    val STATIC = firmwareUuid("cfg_lt_static")
    val BREATH = firmwareUuid("cfg_lt_breath")
    val CHASE = firmwareUuid("cfg_lt_chase")
    val POINTS = firmwareUuid("cfg_lt_points")
    val FLASH = firmwareUuid("cfg_lt_flash")

    val TIME_SVC = firmwareUuid("time_svc")
    val TIME_SET = firmwareUuid("time_set")
    val TIME_REQ = firmwareUuid("time_req")
    val TIME_STATE = firmwareUuid("time_state")

    val PRST_SVC = firmwareUuid("prst_svc")
    val PRST_LST = firmwareUuid("prst_lst")
    val PRST_RED = firmwareUuid("prst_red")
    val PRST_DAT = firmwareUuid("prst_dat")
    val PRST_WRT = firmwareUuid("prst_wrt")
    val PRST_DEL = firmwareUuid("prst_del")
    val PRST_DOK = firmwareUuid("prst_dok")
    val RESP_DAT = firmwareUuid("resp_dat")

    val SCHD_SVC = firmwareUuid("schd_svc")
    val SCHD_LST = firmwareUuid("schd_lst")
    val SCHD_GET = firmwareUuid("schd_get")
    val SCHD_SET = firmwareUuid("schd_set")
    val SCHD_DEL = firmwareUuid("schd_del")
    val SCHD_DOK = firmwareUuid("schd_dok")
    val BIND_GET = firmwareUuid("bind_get")
    val BIND_SET = firmwareUuid("bind_set")
    val IO_MODE = firmwareUuid("io_mode ")
    val CLK_STAT = firmwareUuid("clk_stat")
    val EDIT_CMT = firmwareUuid("edit_cmt")
    val DEVINFO = firmwareUuid("devinfo")

    val OTA_SVC = firmwareUuid("ota_svc")
    val OTA_CMD = firmwareUuid("ota_cmd")
    val OTA_STAT = firmwareUuid("ota_stat")
    val OTA_INFO = firmwareUuid("ota_info")

    val IO_SVC_LE: ByteArray = firmwareUuidBytes("io_svc")

    /* 设备鉴权：写 auth_id（16B UUID + 1B 名称长度 + 名称），再写 auth_pin */
    val AUTH_SVC = firmwareUuid("auth_svc")
    val AUTH_ID = firmwareUuid("auth_id")
    val AUTH_PIN = firmwareUuid("auth_pin")
    val AUTH_STAT = firmwareUuid("auth_stat")
    val AUTH_NAME = firmwareUuid("auth_name")
}

/** 设备端 ota_cmd 单包上限：固件里写的是 240 字节（MTU 247 减去 ATT 头后仍有余量）。 */
const val OTA_CMD_MAX = 240

const val MODE_OFF = 0
const val MODE_WAVE = 1
const val MODE_STATIC = 2
const val MODE_BREATH = 3
const val MODE_CHASE = 4
const val MODE_POINTS = 5
const val MODE_FLASH = 6

const val DIR_RIGHT = 0
const val DIR_LEFT = 1

const val FLASH_BLINK = 0
const val FLASH_SWITCH = 1

const val SPEED_MAX = 1000
const val COLOR_MAX = 8
const val POINT_MAX = 8
const val REFRESH_MIN = 10
const val REFRESH_MAX = 50

val MODE_LABELS = listOf("关闭", "随波逐流", "常亮", "呼吸", "追逐", "多点渐变", "爆闪")

fun modeLabel(mode: Int): String = MODE_LABELS.getOrElse(mode) { "未知" }
