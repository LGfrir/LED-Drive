package com.lgfrir.leddrivebt.protocol

data class Rgb(val r: Int, val g: Int, val b: Int) {
    fun clamp(): Rgb = Rgb(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))
}

data class WaveCfg(val speedPct: Int, val dir: Int)
data class StaticCfg(val color: Rgb)
data class BreathCfg(val color: Rgb, val periodMs: Int)
data class ChaseCfg(val speedPct: Int, val dir: Int, val colors: List<Rgb>)
data class PointCfg(val positionPct: Int, val periodMs: Int, val colors: List<Rgb>)
data class PointsCfg(val wrap: Boolean, val points: List<PointCfg>)
data class FlashCfg(val mode: Int, val periodMs: Int)

data class SegmentHead(
    val id: Int,
    val length: Int,
    val name: String,
    val mode: Int? = null,
    /** 这一段当前效果的代表色。流水、爆闪没有单一颜色时为空。 */
    val tint: Int? = null,
)

data class IoNode(
    val id: Int,
    val name: String,
    val brightness: Int,
    val refreshMs: Int,
    val ledCount: Int,
    val segments: List<SegmentHead>,
)

data class SegmentDetail(
    val mode: Int,
    val brightness: Int,
    val wave: WaveCfg,
    val solid: StaticCfg,
    val breath: BreathCfg,
    val chase: ChaseCfg,
    val points: PointsCfg,
    val flash: FlashCfg,
)

private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xff

private fun ByteArray.u16(index: Int): Int = u8(index) or (u8(index + 1) shl 8)

private fun MutableList<Byte>.u8(value: Int) {
    add((value and 0xff).toByte())
}

private fun MutableList<Byte>.u16(value: Int) {
    add((value and 0xff).toByte())
    add(((value shr 8) and 0xff).toByte())
}

class PacketException(message: String) : IllegalArgumentException(message)

fun parseTopology(raw: ByteArray): List<IoNode> {
    if (raw.isEmpty()) throw PacketException("拓扑是空的")
    var i = 0
    val ioCount = raw.u8(i++)
    val ios = ArrayList<IoNode>(ioCount)
    repeat(ioCount) {
        if (i + 2 > raw.size) throw PacketException("拓扑长度不够")
        val id = raw.u8(i++)
        val ioNameLen = raw.u8(i++)
        if (i + ioNameLen > raw.size) throw PacketException("IO 名字被截断")
        val ioName = raw.copyOfRange(i, i + ioNameLen).toString(Charsets.UTF_8)
        i += ioNameLen

        if (i + 6 > raw.size) throw PacketException("拓扑长度不够")
        val brightness = raw.u8(i++)
        val refresh = raw.u16(i)
        i += 2
        val ledCount = raw.u16(i)
        i += 2
        val segCount = raw.u8(i++)
        val segs = ArrayList<SegmentHead>(segCount)
        repeat(segCount) {
            if (i + 4 > raw.size) throw PacketException("灯段描述被截断")
            val segId = raw.u8(i++)
            val length = raw.u16(i)
            i += 2
            val segNameLen = raw.u8(i++)
            if (i + segNameLen > raw.size) throw PacketException("灯段名字被截断")
            val segName = raw.copyOfRange(i, i + segNameLen).toString(Charsets.UTF_8)
            i += segNameLen
            segs += SegmentHead(segId, length, segName.ifEmpty { "段 $segId" })
        }
        ios += IoNode(id, ioName.ifEmpty { "IO $id" }, brightness, refresh, ledCount, segs)
    }
    return ios
}

fun parseWave(raw: ByteArray): WaveCfg {
    if (raw.size != 3) throw PacketException("随波逐流数据长度不对")
    return WaveCfg(raw.u16(0), raw.u8(2))
}

fun encodeWave(cfg: WaveCfg): ByteArray {
    require(cfg.speedPct in 0..SPEED_MAX) { "速度要在 0 到 $SPEED_MAX 之间" }
    require(cfg.dir == DIR_RIGHT || cfg.dir == DIR_LEFT) { "方向无效" }
    return byteArrayOf(
        (cfg.speedPct and 0xff).toByte(),
        ((cfg.speedPct shr 8) and 0xff).toByte(),
        cfg.dir.toByte(),
    )
}

fun parseStatic(raw: ByteArray): StaticCfg {
    if (raw.size != 3) throw PacketException("常亮数据长度不对")
    return StaticCfg(Rgb(raw.u8(0), raw.u8(1), raw.u8(2)))
}

fun encodeStatic(cfg: StaticCfg): ByteArray {
    val color = cfg.color.clamp()
    return byteArrayOf(color.r.toByte(), color.g.toByte(), color.b.toByte())
}

fun parseBreath(raw: ByteArray): BreathCfg {
    if (raw.size != 5) throw PacketException("呼吸数据长度不对")
    return BreathCfg(Rgb(raw.u8(0), raw.u8(1), raw.u8(2)), raw.u16(3))
}

fun encodeBreath(cfg: BreathCfg): ByteArray {
    require(cfg.periodMs in 0..65535) { "呼吸周期要在 0 到 65535 毫秒之间" }
    val color = cfg.color.clamp()
    return byteArrayOf(
        color.r.toByte(),
        color.g.toByte(),
        color.b.toByte(),
        (cfg.periodMs and 0xff).toByte(),
        ((cfg.periodMs shr 8) and 0xff).toByte(),
    )
}

private fun colorsAt(raw: ByteArray, offset: Int, count: Int): List<Rgb> {
    if (offset + count * 3 > raw.size) throw PacketException("颜色数据被截断")
    return List(count) { index ->
        val at = offset + index * 3
        Rgb(raw.u8(at), raw.u8(at + 1), raw.u8(at + 2))
    }
}

fun parseChase(raw: ByteArray): ChaseCfg {
    if (raw.size < 4) throw PacketException("追逐数据长度不对")
    val count = raw.u8(0)
    if (count !in 1..COLOR_MAX || raw.size != 4 + count * 3) {
        throw PacketException("追逐颜色个数不对")
    }
    return ChaseCfg(raw.u16(1), raw.u8(3), colorsAt(raw, 4, count))
}

fun encodeChase(cfg: ChaseCfg): ByteArray {
    require(cfg.colors.size in 1..COLOR_MAX) { "追逐颜色要有 1 到 $COLOR_MAX 个" }
    require(cfg.speedPct in 0..SPEED_MAX) { "速度要在 0 到 $SPEED_MAX 之间" }
    require(cfg.dir == DIR_RIGHT || cfg.dir == DIR_LEFT) { "方向无效" }
    val out = ArrayList<Byte>(4 + cfg.colors.size * 3)
    out.u8(cfg.colors.size)
    out.u16(cfg.speedPct)
    out.u8(cfg.dir)
    cfg.colors.forEach { color ->
        val clamped = color.clamp()
        out.u8(clamped.r)
        out.u8(clamped.g)
        out.u8(clamped.b)
    }
    return out.toByteArray()
}

fun parsePoints(raw: ByteArray): PointsCfg {
    if (raw.size < 2) throw PacketException("多点渐变数据长度不对")
    var i = 0
    val wrap = raw.u8(i++) != 0
    val count = raw.u8(i++)
    if (count !in 1..POINT_MAX) throw PacketException("渐变点个数不对")
    val points = ArrayList<PointCfg>(count)
    repeat(count) {
        if (i + 4 > raw.size) throw PacketException("渐变点被截断")
        val position = raw.u8(i++)
        val period = raw.u16(i)
        i += 2
        val colorCount = raw.u8(i++)
        if (position > 100 || colorCount !in 1..COLOR_MAX) {
            throw PacketException("渐变点参数不对")
        }
        val colors = colorsAt(raw, i, colorCount)
        i += colorCount * 3
        points += PointCfg(position, period, colors)
    }
    if (i != raw.size) throw PacketException("多点渐变末尾还有多余字节")
    return PointsCfg(wrap, points)
}

fun encodePoints(cfg: PointsCfg): ByteArray {
    require(cfg.points.size in 1..POINT_MAX) { "渐变点要有 1 到 $POINT_MAX 个" }
    val out = ArrayList<Byte>()
    out.u8(if (cfg.wrap) 1 else 0)
    out.u8(cfg.points.size)
    cfg.points.forEach { point ->
        require(point.positionPct in 0..100) { "位置要在 0% 到 100% 之间" }
        require(point.periodMs in 0..65535) { "点的周期要在 0 到 65535 毫秒之间" }
        require(point.colors.size in 1..COLOR_MAX) { "每个点的颜色要有 1 到 $COLOR_MAX 个" }
        out.u8(point.positionPct)
        out.u16(point.periodMs)
        out.u8(point.colors.size)
        point.colors.forEach { color ->
            val clamped = color.clamp()
            out.u8(clamped.r)
            out.u8(clamped.g)
            out.u8(clamped.b)
        }
    }
    return out.toByteArray()
}

fun parseFlash(raw: ByteArray): FlashCfg {
    if (raw.size != 3) throw PacketException("爆闪数据长度不对")
    return FlashCfg(raw.u8(0), raw.u16(1))
}

fun encodeFlash(cfg: FlashCfg): ByteArray {
    require(cfg.mode == FLASH_BLINK || cfg.mode == FLASH_SWITCH) { "爆闪方式无效" }
    require(cfg.periodMs in 0..65535) { "爆闪周期要在 0 到 65535 毫秒之间" }
    return byteArrayOf(
        cfg.mode.toByte(),
        (cfg.periodMs and 0xff).toByte(),
        ((cfg.periodMs shr 8) and 0xff).toByte(),
    )
}

fun parseDetail(
    mode: Int,
    brightness: Int,
    wave: ByteArray,
    solid: ByteArray,
    breath: ByteArray,
    chase: ByteArray,
    points: ByteArray,
    flash: ByteArray,
): SegmentDetail {
    if (mode !in MODE_OFF..MODE_FLASH) throw PacketException("模式编号无效")
    return SegmentDetail(
        mode = mode,
        brightness = brightness.coerceIn(0, 255),
        wave = parseWave(wave),
        solid = parseStatic(solid),
        breath = parseBreath(breath),
        chase = parseChase(chase),
        points = parsePoints(points),
        flash = parseFlash(flash),
    )
}
