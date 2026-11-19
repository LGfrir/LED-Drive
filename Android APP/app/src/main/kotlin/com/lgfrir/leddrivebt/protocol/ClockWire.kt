package com.lgfrir.leddrivebt.protocol

import java.util.Calendar
import java.util.TimeZone

const val PRESET_ID_NONE = 0xFF
const val PRESET_ROM_FIRST = 0x80
const val PRESET_ROM_LAST = 0x86
const val PRESET_FLAG_ROM = 0x01
const val IO_MODE_MANUAL = 0
const val IO_MODE_TIME = 1
const val CHUNK = 230
const val EFFECT_WIRE = 270
const val NAME_MAX_BYTES = 63

const val RESP_PRESET = 1
const val RESP_DRAFT = 2
const val RESP_PRESET_DEL = 3
const val RESP_SCHED = 4
const val RESP_BIND = 5
const val RESP_TIME = 6
const val RESP_IO_MODE = 7
const val RESP_SCHED_DEL = 8

data class PresetHead(val id: Int, val rom: Boolean)

data class PresetRecord(val id: Int, val name: String, val detail: SegmentDetail) {
    fun effectBytes(): ByteArray = encodeEffect(detail)
}

data class ScheduleRecord(val id: Int, val name: String, val atSec: List<Int>)

data class SegBinding(val segId: Int, val schedId: Int, val presetIds: List<Int>)

data class DevInfo(
    val fw: String,
    val ioCount: Int,
    val segTotal: Int,
    val presetMax: Int,
    val schedMax: Int,
    val slotMax: Int,
)

data class SegClock(val segId: Int, val presetId: Int, val ioMode: Int)

data class ClockStat(val valid: Boolean, val nextEvent: Long, val segments: List<SegClock>)

data class OpResult(val type: Int, val a: Int, val b: Int, val status: Int, val name: String?)

data class UsageLine(val io: Int, val name: String)

data class UsageReport(val total: Int, val lines: List<UsageLine>)

fun statusLabel(status: Int): String = when (status) {
    0 -> "成功"
    2 -> "容量已满"
    3 -> "只读"
    4 -> "不存在"
    5 -> "参数无效"
    else -> "失败"
}

fun blankDetail(): SegmentDetail = SegmentDetail(
    mode = MODE_STATIC,
    brightness = 255,
    wave = WaveCfg(50, DIR_RIGHT),
    solid = StaticCfg(Rgb(255, 255, 255)),
    breath = BreathCfg(Rgb(255, 255, 255), 2000),
    chase = ChaseCfg(50, DIR_RIGHT, listOf(Rgb(255, 0, 0), Rgb(0, 255, 0), Rgb(0, 0, 255))),
    points = PointsCfg(false, listOf(PointCfg(0, 2000, listOf(Rgb(255, 255, 255))))),
    flash = FlashCfg(FLASH_BLINK, 400),
)

fun presetTitle(id: Int, name: String): String = when (id) {
    PRESET_ID_NONE -> "关灯"
    else -> name.ifEmpty { "预设 $id" }
}

fun encodeTimeSet(epochMs: Long = System.currentTimeMillis()): ByteArray {
    val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
    cal.timeInMillis = epochMs
    val year = cal.get(Calendar.YEAR)
    val wday = when (cal.get(Calendar.DAY_OF_WEEK)) {
        Calendar.SUNDAY -> 0
        Calendar.MONDAY -> 1
        Calendar.TUESDAY -> 2
        Calendar.WEDNESDAY -> 3
        Calendar.THURSDAY -> 4
        Calendar.FRIDAY -> 5
        else -> 6
    }
    return byteArrayOf(
        (year and 0xff).toByte(),
        ((year shr 8) and 0xff).toByte(),
        (cal.get(Calendar.MONTH) + 1).toByte(),
        cal.get(Calendar.DAY_OF_MONTH).toByte(),
        cal.get(Calendar.HOUR_OF_DAY).toByte(),
        cal.get(Calendar.MINUTE).toByte(),
        cal.get(Calendar.SECOND).toByte(),
        wday.toByte(),
    )
}

fun parseTimeState(raw: ByteArray): Pair<Boolean, Long> {
    if (raw.size < 9) throw PacketException("时间状态长度不对")
    val valid = raw.u8(0) != 0
    val now = raw.u32(5)
    return valid to now
}

fun parsePresetHeads(raw: ByteArray): List<PresetHead> {
    if (raw.isEmpty()) return emptyList()
    val count = raw.u8(0)
    if (raw.size < 1 + count * 2) throw PacketException("预设列表被截断")
    return List(count) { index ->
        val at = 1 + index * 2
        PresetHead(raw.u8(at), raw.u8(at + 1) and PRESET_FLAG_ROM != 0)
    }
}

fun parseDevInfo(raw: ByteArray): DevInfo {
    if (raw.size < 8) throw PacketException("设备信息长度不对")
    return DevInfo(
        fw = "${raw.u8(0)}.${raw.u8(1)}.${raw.u8(2)}",
        ioCount = raw.u8(3),
        segTotal = raw.u8(4),
        presetMax = raw.u8(5),
        schedMax = raw.u8(6),
        slotMax = raw.u8(7),
    )
}

fun parseIoModes(raw: ByteArray): Map<Int, Int> {
    if (raw.size < 8 || raw.size % 2 != 0) throw PacketException("IO 模式长度不对")
    val out = LinkedHashMap<Int, Int>()
    var i = 0
    while (i + 1 < raw.size) {
        val io = raw.u8(i)
        val mode = raw.u8(i + 1)
        if (io != 0) out[io] = mode
        i += 2
    }
    return out
}

fun encodeIoMode(io: Int, mode: Int): ByteArray = byteArrayOf(io.toByte(), mode.toByte())

fun parseClockStat(raw: ByteArray): ClockStat {
    if (raw.size < 6) throw PacketException("运行状态长度不对")
    val valid = raw.u8(0) != 0
    val next = raw.u32(1)
    val count = raw.u8(5)
    if (raw.size < 6 + count * 3) throw PacketException("运行状态被截断")
    val segs = List(count) { index ->
        val at = 6 + index * 3
        SegClock(raw.u8(at), raw.u8(at + 1), raw.u8(at + 2))
    }
    return ClockStat(valid, next, segs)
}

fun parseOpResult(raw: ByteArray): OpResult {
    if (raw.size < 4) throw PacketException("结果通知长度不对")
    val name = if (raw.size >= 5) {
        val len = raw.u8(4)
        if (5 + len > raw.size) throw PacketException("结果里的名字被截断")
        raw.copyOfRange(5, 5 + len).toString(Charsets.UTF_8)
    } else {
        null
    }
    return OpResult(raw.u8(0), raw.u8(1), raw.u8(2), raw.u8(3), name)
}

fun parseUsage(raw: ByteArray): UsageReport {
    val text = raw.toString(Charsets.UTF_8).trim()
    if (text.isEmpty()) return UsageReport(0, emptyList())
    val rows = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    val total = rows.firstOrNull()?.toIntOrNull() ?: 0
    val lines = rows.drop(1).map { row ->
        val comma = row.indexOf(',')
        if (comma < 0) UsageLine(0, row) else UsageLine(row.substring(0, comma).toIntOrNull() ?: 0, row.substring(comma + 1))
    }
    return UsageReport(total, lines)
}

fun encodeEffect(detail: SegmentDetail): ByteArray {
    val out = ByteArray(EFFECT_WIRE)
    var p = 0
    fun u8(value: Int) { out[p++] = (value and 0xff).toByte() }
    fun u16(value: Int) { u8(value); u8(value shr 8) }
    fun rgb(color: Rgb) {
        val c = color.clamp()
        u8(c.r); u8(c.g); u8(c.b)
    }
    u8(detail.mode)
    u8(detail.brightness)
    u16(detail.wave.speedPct.coerceIn(0, SPEED_MAX))
    u8(if (detail.wave.dir == DIR_LEFT) DIR_LEFT else DIR_RIGHT)
    rgb(detail.solid.color)
    rgb(detail.breath.color)
    u16(detail.breath.periodMs.coerceIn(0, 65535))
    val chaseColors = detail.chase.colors.take(COLOR_MAX).ifEmpty { listOf(Rgb(255, 255, 255)) }
    u8(chaseColors.size)
    u16(detail.chase.speedPct.coerceIn(0, SPEED_MAX))
    u8(if (detail.chase.dir == DIR_LEFT) DIR_LEFT else DIR_RIGHT)
    repeat(COLOR_MAX) { index -> rgb(chaseColors.getOrElse(index) { Rgb(0, 0, 0) }) }
    u8(if (detail.points.wrap) 1 else 0)
    val points = detail.points.points.take(POINT_MAX).ifEmpty { listOf(PointCfg(0, 2000, listOf(Rgb(255, 255, 255)))) }
    u8(points.size)
    repeat(POINT_MAX) { index ->
        val point = points.getOrNull(index)
        val colors = point?.colors?.take(COLOR_MAX).orEmpty().ifEmpty { listOf(Rgb(0, 0, 0)) }
        u8(point?.positionPct?.coerceIn(0, 100) ?: 0)
        u16(point?.periodMs?.coerceIn(0, 65535) ?: 0)
        u8(if (point == null) 0 else colors.size)
        repeat(COLOR_MAX) { colorIndex -> rgb(colors.getOrElse(colorIndex) { Rgb(0, 0, 0) }) }
    }
    u8(if (detail.flash.mode == FLASH_SWITCH) FLASH_SWITCH else FLASH_BLINK)
    u16(detail.flash.periodMs.coerceIn(0, 65535))
    check(p == EFFECT_WIRE)
    return out
}

fun decodeEffect(raw: ByteArray): SegmentDetail {
    if (raw.size < EFFECT_WIRE) throw PacketException("灯效长度不对")
    var p = 0
    fun u8(): Int = raw[p++].toInt() and 0xff
    fun u16(): Int = u8() or (u8() shl 8)
    fun rgb(): Rgb = Rgb(u8(), u8(), u8())
    val mode = u8()
    val brightness = u8()
    val wave = WaveCfg(u16(), u8())
    val solid = StaticCfg(rgb())
    val breath = BreathCfg(rgb(), u16())
    val chaseCount = u8().coerceIn(0, COLOR_MAX)
    val chaseSpeed = u16()
    val chaseDir = u8()
    val chaseColors = List(COLOR_MAX) { rgb() }.take(chaseCount.coerceAtLeast(1))
    val wrap = u8() != 0
    val pointCount = u8().coerceIn(0, POINT_MAX)
    val points = List(POINT_MAX) {
        val pos = u8()
        val period = u16()
        val count = u8().coerceIn(0, COLOR_MAX)
        val colors = List(COLOR_MAX) { rgb() }.take(count.coerceAtLeast(1))
        PointCfg(pos, period, colors)
    }.take(pointCount.coerceAtLeast(1))
    val flash = FlashCfg(u8(), u16())
    if (mode !in MODE_OFF..MODE_FLASH) throw PacketException("模式编号无效")
    return SegmentDetail(mode, brightness, wave, solid, breath, ChaseCfg(chaseSpeed, chaseDir, chaseColors), PointsCfg(wrap, points), flash)
}

fun encodeRecord(name: String, detail: SegmentDetail): ByteArray {
    val bytes = utf8Trim(name)
    val effect = encodeEffect(detail)
    val out = ByteArray(1 + bytes.size + effect.size)
    out[0] = bytes.size.toByte()
    bytes.copyInto(out, 1)
    effect.copyInto(out, 1 + bytes.size)
    return out
}

fun decodeRecord(id: Int, raw: ByteArray): PresetRecord {
    if (raw.isEmpty()) throw PacketException("预设记录是空的")
    val nameLen = raw.u8(0)
    if (raw.size < 1 + nameLen + EFFECT_WIRE) throw PacketException("预设记录被截断")
    val name = raw.copyOfRange(1, 1 + nameLen).toString(Charsets.UTF_8)
    val detail = decodeEffect(raw.copyOfRange(1 + nameLen, 1 + nameLen + EFFECT_WIRE))
    return PresetRecord(id, name, detail)
}

/** 按分钟对齐。同一分钟保留旧预设，新时间点为关灯。固件 clock_sched_set 用同一规则。 */
fun alignPresetIds(oldTimes: List<Int>, oldIds: List<Int>, newTimes: List<Int>): List<Int> {
    val byMinute = HashMap<Int, Int>()
    oldTimes.forEachIndexed { index, time ->
        val minute = time / 60
        if (minute !in byMinute && index < oldIds.size) byMinute[minute] = oldIds[index]
    }
    return newTimes.map { time -> byMinute[time / 60] ?: PRESET_ID_NONE }
}

fun encodeSchedule(id: Int, name: String, atSec: List<Int>): ByteArray {
    val unique = atSec.distinct().sorted()
    if (unique.size != atSec.size) throw PacketException("时间点重复")
    if (unique.any { it !in 0..86399 }) throw PacketException("时间点超出当天")
    val bytes = utf8Trim(name)
    val out = ArrayList<Byte>(2 + bytes.size + 1 + unique.size * 4)
    out.add(id.toByte())
    out.add(bytes.size.toByte())
    bytes.forEach { out.add(it) }
    out.add(unique.size.toByte())
    unique.forEach { sec ->
        out.add((sec and 0xff).toByte())
        out.add(((sec shr 8) and 0xff).toByte())
        out.add(((sec shr 16) and 0xff).toByte())
        out.add(((sec shr 24) and 0xff).toByte())
    }
    return out.toByteArray()
}

fun decodeSchedule(raw: ByteArray): ScheduleRecord {
    if (raw.size < 3) throw PacketException("调度详情长度不对")
    var i = 0
    val id = raw.u8(i++)
    val nameLen = raw.u8(i++)
    if (raw.size < 2 + nameLen + 1) throw PacketException("调度名字被截断")
    val name = raw.copyOfRange(i, i + nameLen).toString(Charsets.UTF_8)
    i += nameLen
    val count = raw.u8(i++)
    if (raw.size != i + count * 4) throw PacketException("调度时间点被截断")
    val times = List(count) {
        val sec = raw.u32(i)
        i += 4
        sec.toInt()
    }
    return ScheduleRecord(id, name, times)
}

fun encodeBinding(binding: SegBinding): ByteArray {
    val out = ByteArray(3 + binding.presetIds.size)
    out[0] = binding.segId.toByte()
    out[1] = binding.schedId.toByte()
    out[2] = binding.presetIds.size.toByte()
    binding.presetIds.forEachIndexed { index, id -> out[3 + index] = id.toByte() }
    return out
}

fun decodeBinding(raw: ByteArray): SegBinding {
    if (raw.size < 3) throw PacketException("绑定长度不对")
    val count = raw.u8(2)
    if (raw.size != 3 + count) throw PacketException("绑定被截断")
    return SegBinding(raw.u8(0), raw.u8(1), List(count) { raw.u8(3 + it) })
}

fun clockOfDay(hour: Int, minute: Int, second: Int): Int = hour * 3600 + minute * 60 + second

fun formatClock(sec: Int): String {
    val s = sec.coerceIn(0, 86399)
    return "%02d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
}

fun formatMinute(sec: Int): String {
    val s = sec.coerceIn(0, 86399)
    return "%02d:%02d".format(s / 3600, (s / 60) % 60)
}

fun wireSelfCheck(): String {
    val detail = blankDetail().copy(mode = MODE_POINTS)
    val encoded = encodeEffect(detail)
    val decoded = decodeEffect(encoded)
    if (encodeEffect(decoded).contentEquals(encoded).not()) return "灯效往返失败"
    val record = encodeRecord("白光", detail)
    val parsed = decodeRecord(3, record)
    if (parsed.name != "白光" || parsed.detail.mode != MODE_POINTS) return "预设记录往返失败"
    val sched = encodeSchedule(0, "工作日", listOf(8 * 3600, 18 * 3600 + 30))
    val schedBack = decodeSchedule(sched)
    if (schedBack.atSec != listOf(8 * 3600, 18 * 3600 + 30)) return "调度往返失败"
    val binding = SegBinding(2, 1, listOf(0x80, PRESET_ID_NONE))
    if (decodeBinding(encodeBinding(binding)) != binding) return "绑定往返失败"
    val time = encodeTimeSet(0L)
    if (time.size != 8) return "对时包长度不对"
    return "协议往返通过"
}

fun nameBytes(name: String): Int = name.toByteArray(Charsets.UTF_8).size

/**
 * 当前该生效的时间点下标。还没到今天第一个点时，沿用前一天最后一个，和固件 current_slot 一致。
 */
fun activeSlotIndex(times: List<Int>, epochSec: Long): Int {
    if (epochSec <= 0L || times.isEmpty()) return -1
    val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
    cal.timeInMillis = epochSec * 1000L
    val secondOfDay = cal.get(Calendar.HOUR_OF_DAY) * 3600 +
        cal.get(Calendar.MINUTE) * 60 +
        cal.get(Calendar.SECOND)
    var index = -1
    times.forEachIndexed { i, sec -> if (sec <= secondOfDay) index = i }
    return if (index < 0) times.lastIndex else index
}

/** 这一路今天还没到的下一个时间点，单位是当天秒。没有则返回 0。 */
fun nextSlotSec(times: List<Int>, epochSec: Long): Int {
    if (epochSec <= 0L || times.isEmpty()) return 0
    val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
    cal.timeInMillis = epochSec * 1000L
    val secondOfDay = cal.get(Calendar.HOUR_OF_DAY) * 3600 +
        cal.get(Calendar.MINUTE) * 60 +
        cal.get(Calendar.SECOND)
    return times.filter { it > secondOfDay }.minOrNull() ?: 0
}

fun formatEpoch(epochSec: Long): String {
    if (epochSec <= 0L) return "--:--:--"
    val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
    cal.timeInMillis = epochSec * 1000L
    return "%02d:%02d:%02d".format(
        cal.get(Calendar.HOUR_OF_DAY),
        cal.get(Calendar.MINUTE),
        cal.get(Calendar.SECOND),
    )
}

private fun utf8Trim(name: String): ByteArray {
    val raw = name.toByteArray(Charsets.UTF_8)
    if (raw.size <= NAME_MAX_BYTES) return raw
    var n = NAME_MAX_BYTES
    while (n > 0 && (raw[n].toInt() and 0xC0) == 0x80) n--
    return raw.copyOf(n)
}

private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xff

private fun ByteArray.u32(index: Int): Long =
    (u8(index).toLong()) or
        (u8(index + 1).toLong() shl 8) or
        (u8(index + 2).toLong() shl 16) or
        (u8(index + 3).toLong() shl 24)
