package com.lgfrir.leddrivebt.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lgfrir.leddrivebt.protocol.DIR_LEFT
import com.lgfrir.leddrivebt.protocol.FLASH_BLINK
import com.lgfrir.leddrivebt.protocol.MODE_BREATH
import com.lgfrir.leddrivebt.protocol.MODE_CHASE
import com.lgfrir.leddrivebt.protocol.MODE_FLASH
import com.lgfrir.leddrivebt.protocol.MODE_POINTS
import com.lgfrir.leddrivebt.protocol.MODE_STATIC
import com.lgfrir.leddrivebt.protocol.MODE_WAVE
import com.lgfrir.leddrivebt.protocol.formatEpoch
import com.lgfrir.leddrivebt.protocol.formatMinute
import com.lgfrir.leddrivebt.protocol.nextSlotSec
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.delay
import com.lgfrir.leddrivebt.protocol.IoNode
import com.lgfrir.leddrivebt.protocol.PointsCfg
import com.lgfrir.leddrivebt.protocol.Rgb
import com.lgfrir.leddrivebt.protocol.SegmentDetail
import com.lgfrir.leddrivebt.protocol.modeLabel
import com.lgfrir.leddrivebt.ui.theme.AppType
import com.lgfrir.leddrivebt.ui.theme.Motion
import com.lgfrir.leddrivebt.ui.theme.Space
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.random.Random

class PreviewClockSource {
    val frame = mutableLongStateOf(0L)
}

val LocalPreviewClock = compositionLocalOf { PreviewClockSource() }

private class PreviewClock {
    var phase = 0f
    var breath = 0f
    val pointPhase = FloatArray(8)
    var flashMs = 0L
    var lit = true
    var flashFrame = 0
}

@Composable
fun EffectPreview(
    detail: SegmentDetail,
    length: Int,
    barHeight: Dp = 22.dp,
) {
    /*
     * 换模式时预览交叉淡化 300ms，不要硬跳。
     * 淡化期间新旧两个 Canvas 各跑各的帧，300ms 后旧的卸载。
     */
    Crossfade(
        targetState = detail.mode,
        animationSpec = tween(Motion.crossfade),
        label = "preview-mode",
    ) { mode ->
        PreviewCanvas(detail.copy(mode = mode), length, barHeight)
    }
}

@Composable
private fun PreviewCanvas(
    detail: SegmentDetail,
    length: Int,
    barHeight: Dp,
) {
    val count = length.coerceIn(1, 96)
    val clock = remember(detail.mode, count) { PreviewClock() }
    val frame = remember { mutableLongStateOf(0L) }
    val moving = detail.mode == MODE_WAVE ||
        detail.mode == MODE_BREATH ||
        detail.mode == MODE_CHASE ||
        detail.mode == MODE_POINTS ||
        detail.mode == MODE_FLASH
    val shared = LocalPreviewClock.current
    LaunchedEffect(detail, count, moving, shared) {
        if (!moving) {
            frame.longValue = frame.longValue + 1
            return@LaunchedEffect
        }
        /*
         * 相位按全应用共用的帧时钟来算，不从这一段读完的那一刻归零。
         * 后读到的灯段会直接落到同一时刻，相同灯效在监控里保持对齐。
         */
        snapshotFlow { shared.frame.longValue }.collect { now ->
            placePreview(clock, detail, now)
            frame.longValue = now
        }
    }
    val shape = RoundedCornerShape(barHeight / 2)
    val pixels = remember(count) { IntArray(count) }
    Canvas(
        Modifier
            .padding(top = Space.x2)
            .fillMaxWidth()
            .height(barHeight)
            .clip(shape),
    ) {
        frame.longValue
        renderInto(pixels, detail, count, clock)
        /*
         * 用水平渐变铺满整条，保持连续过渡——逐格 drawRect 会把灯带画成一格一格。
         * 计算仍然写进复用的 IntArray，只是最后转成 Brush 一次性画。
         */
        val colors = ArrayList<Color>(count)
        for (index in 0 until count) colors.add(Color(pixels[index]))
        val brush = if (colors.size <= 1) {
            Brush.horizontalGradient(listOf(colors.first(), colors.first()))
        } else {
            Brush.horizontalGradient(colors)
        }
        drawRect(brush)
    }
}

@Composable
fun MonitorRoom(
    ios: List<IoNode>,
    previews: Map<Int, SegmentDetail>,
    modifier: Modifier = Modifier,
    nextTimes: Map<Int, List<Int>> = emptyMap(),
    epochSec: Long = 0L,
    onConnect: (() -> Unit)? = null,
) {
    val palette = LocalPalette.current
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            tick = SystemClock.elapsedRealtime()
        }
    }
    val clock = remember(epochSec, tick) {
        if (epochSec > 0L) {
            formatEpoch(epochSec)
        } else {
            val cal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Shanghai"))
            "%02d:%02d:%02d".format(
                cal.get(Calendar.HOUR_OF_DAY),
                cal.get(Calendar.MINUTE),
                cal.get(Calendar.SECOND),
            )
        }
    }
    if (ios.isEmpty()) {
        EmptyState(
            "未连接。连上灯带后，这里会显示每一路的实时颜色。",
            modifier = modifier,
            actionLabel = if (onConnect != null) "去连接" else null,
            onAction = onConnect,
        )
        return
    }
    Column(modifier) {
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = Space.x2)) {
        ios.forEach { io ->
            val next = nextSlotSec(nextTimes[io.id].orEmpty(), epochSec)
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = Space.x3)
                    .surfaceCard(CardLevel.Base)
                    .padding(Space.x3),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(io.name, color = palette.text, style = AppType.title)
                    if (next > 0) {
                        Text(formatMinute(next), color = palette.muted, style = AppType.body)
                    }
                }
                io.segments.forEach { seg ->
                    val detail = previews[seg.id]
                    Text(
                        "${seg.name} · ${seg.length} 颗 · ${detail?.mode?.let(::modeLabel) ?: seg.mode?.let(::modeLabel) ?: "读取中"}",
                        color = palette.muted,
                        style = AppType.caption,
                        modifier = Modifier.padding(top = Space.x2),
                    )
                    if (detail == null) {
                        Box(
                            Modifier
                                .padding(top = Space.x2)
                                .fillMaxWidth()
                                .height(16.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(palette.muted.copy(alpha = 0.25f)),
                        )
                    } else {
                        EffectPreview(detail, seg.length, barHeight = 16.dp)
                    }
                }
            }
        }
    }
        Text(
            clock,
            color = palette.text,
            style = AppType.title.copy(fontWeight = FontWeight.Bold),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = Space.x3),
        )
    }
}

@Composable
fun PointRuler(cfg: PointsCfg) {
    val palette = LocalPalette.current
    Text("端点位置", color = palette.muted, style = AppType.caption, modifier = Modifier.padding(top = Space.x2))
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(72.dp)
            .padding(top = 2.dp),
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth()
                .height(2.dp)
                .background(palette.muted.copy(alpha = 0.45f), RoundedCornerShape(1.dp)),
        )
        cfg.points.forEachIndexed { index, point ->
            val x = maxWidth * (point.positionPct / 100f)
            val left = (x - 20.dp).coerceIn(0.dp, (maxWidth - 40.dp).coerceAtLeast(0.dp))
            Column(
                Modifier
                    .offset(x = left, y = if (index % 2 == 0) 0.dp else 14.dp)
                    .width(40.dp)
                    .fillMaxHeight(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                    Text(
                        "${index + 1}",
                        color = palette.text,
                        style = AppType.caption,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = Space.x1),
                    )
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Text(
                        "${point.positionPct}%",
                        color = palette.muted,
                        style = AppType.caption,
                        maxLines = 1,
                        softWrap = false,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = Space.x1),
                    )
                }
            }
        }
    }
}

private fun placePreview(clock: PreviewClock, detail: SegmentDetail, elapsedMs: Long) {
    when (detail.mode) {
        MODE_WAVE -> clock.phase = phaseAt(elapsedMs, detail.wave.speedPct, detail.wave.dir)
        MODE_BREATH -> {
            val period = detail.breath.periodMs
            clock.breath = if (period <= 0) 0f else fraction(elapsedMs.toDouble() / period)
        }
        MODE_CHASE -> clock.phase = phaseAt(elapsedMs, detail.chase.speedPct, detail.chase.dir)
        MODE_POINTS -> detail.points.points.forEachIndexed { index, point ->
            if (index >= clock.pointPhase.size) return@forEachIndexed
            clock.pointPhase[index] = if (point.periodMs <= 0) 0f else fraction(elapsedMs.toDouble() / point.periodMs)
        }
        MODE_FLASH -> {
            clock.flashMs = elapsedMs
            val period = detail.flash.periodMs
            clock.lit = detail.flash.mode != FLASH_BLINK || period <= 0 ||
                (elapsedMs % period) < period / 2
            clock.flashFrame = (elapsedMs / 16L).toInt()
        }
    }
}

private fun phaseAt(elapsedMs: Long, speedPct: Int, dir: Int): Float {
    val turns = speedPct / 100.0 * (elapsedMs / 1000.0)
    val phase = fraction(if (dir == DIR_LEFT) -turns else turns)
    return phase
}

private fun fraction(turns: Double): Float {
    val whole = kotlin.math.floor(turns)
    var part = (turns - whole).toFloat()
    if (part < 0f) part += 1f
    return part
}

private fun argb(c: Rgb): Int =
    (0xFF shl 24) or (c.r.coerceIn(0, 255) shl 16) or (c.g.coerceIn(0, 255) shl 8) or c.b.coerceIn(0, 255)

private fun renderInto(out: IntArray, detail: SegmentDetail, count: Int, clock: PreviewClock) {
    when (detail.mode) {
        MODE_WAVE -> for (index in 0 until count) {
            out[index] = argb(hueColor(ledCenter(index, count) - clock.phase))
        }
        MODE_STATIC -> {
            val color = argb(detail.solid.color)
            for (index in 0 until count) out[index] = color
        }
        MODE_BREATH -> {
            val gain = if (detail.breath.periodMs == 0) 1f else 0.5f * (1f + cos(clock.breath * 2f * PI.toFloat()))
            val color = argb(scale(detail.breath.color, gain))
            for (index in 0 until count) out[index] = color
        }
        MODE_CHASE -> for (index in 0 until count) {
            out[index] = chaseColorAt(detail, count, clock.phase, index)
        }
        MODE_POINTS -> pointColorsInto(out, detail, count, clock)
        MODE_FLASH -> for (index in 0 until count) {
            out[index] = if (!clock.lit) {
                0xFF000000.toInt()
            } else {
                val sample = Random(clock.flashFrame * 131L + index * 17L).nextInt()
                argb(Rgb(sample and 0xff, (sample shr 8) and 0xff, (sample shr 16) and 0xff))
            }
        }
        else -> out.fill(0xFF000000.toInt(), 0, count)
    }
}

private fun chaseColorAt(detail: SegmentDetail, count: Int, phase: Float, index: Int): Int {
    val colors = detail.chase.colors
    if (colors.isEmpty()) return 0xFF000000.toInt()
    val n = colors.size
    val u = ledCenter(index, count)
    var prev = 0
    var prevD = 2f
    for (k in 0 until n) {
        var d = u - wrap01(k / n.toFloat() + phase)
        if (d < 0f) d += 1f
        if (d < prevD) {
            prevD = d
            prev = k
        }
    }
    val span = 1f / n
    return lerpArgb(colors[prev], colors[(prev + 1) % n], if (span <= 0f) 0f else prevD / span)
}

private fun pointColorsInto(out: IntArray, detail: SegmentDetail, count: Int, clock: PreviewClock) {
    val cfg = detail.points
    val n = cfg.points.size.coerceAtMost(8)
    if (n == 0) {
        out.fill(0xFF000000.toInt(), 0, count)
        return
    }
    val order = IntArray(n) { it }
    for (i in 1 until n) {
        val key = order[i]
        val keyPos = cfg.points[key].positionPct
        var j = i - 1
        while (j >= 0 && cfg.points[order[j]].positionPct > keyPos) {
            order[j + 1] = order[j]
            j--
        }
        order[j + 1] = key
    }
    val now = Array(n) { energyAt(cfg.points[order[it]], clock.pointPhase[order[it]]) }
    val pos = FloatArray(n) { cfg.points[order[it]].positionPct.coerceIn(0, 100) / 100f }
    for (index in 0 until count) {
        val u = if (count <= 1) 0f else index / (count - 1f)
        out[index] = if (!cfg.wrap) {
            when {
                u <= pos[0] -> argb(now[0])
                u >= pos[n - 1] -> argb(now[n - 1])
                else -> {
                    var k = 0
                    while (k + 1 < n && pos[k + 1] < u) k++
                    val span = pos[k + 1] - pos[k]
                    lerpArgb(now[k], now[k + 1], if (span <= 0.0001f) 0f else (u - pos[k]) / span)
                }
            }
        } else {
            var prev = 0
            var prevD = 2f
            for (k in 0 until n) {
                var d = u - pos[k]
                if (d < 0f) d += 1f
                if (d < prevD) {
                    prevD = d
                    prev = k
                }
            }
            val next = (prev + 1) % n
            var span = pos[next] - pos[prev]
            if (span < 0f) span += 1f
            lerpArgb(now[prev], now[next], if (span <= 0.0001f) 0f else prevD / span)
        }
    }
}

private fun lerpArgb(a: Rgb, b: Rgb, t: Float): Int {
    if (t <= 0f) return argb(a)
    if (t >= 1f) return argb(b)
    val r = (a.r + (b.r - a.r) * t).roundToInt().coerceIn(0, 255)
    val g = (a.g + (b.g - a.g) * t).roundToInt().coerceIn(0, 255)
    val bl = (a.b + (b.b - a.b) * t).roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
}

private fun energyAt(point: com.lgfrir.leddrivebt.protocol.PointCfg, phase: Float): Rgb {
    if (point.colors.isEmpty()) return Rgb(0, 0, 0)
    if (point.colors.size == 1) return point.colors[0]
    val x = wrap01(phase) * point.colors.size
    var index = x.toInt()
    if (index >= point.colors.size) index = 0
    return rgbLerp(point.colors[index], point.colors[(index + 1) % point.colors.size], x - index)
}

private fun rgbLerp(a: Rgb, b: Rgb, t: Float): Rgb {
    if (t <= 0f) return a
    if (t >= 1f) return b
    return Rgb(
        (a.r + (b.r - a.r) * t).roundToInt().coerceIn(0, 255),
        (a.g + (b.g - a.g) * t).roundToInt().coerceIn(0, 255),
        (a.b + (b.b - a.b) * t).roundToInt().coerceIn(0, 255),
    )
}

private fun scale(c: Rgb, gain: Float): Rgb {
    val g = gain.coerceIn(0f, 1f)
    return Rgb((c.r * g).roundToInt(), (c.g * g).roundToInt(), (c.b * g).roundToInt())
}

private fun hueColor(hue: Float): Rgb {
    val h = wrap01(hue) * 6f
    val region = h.toInt()
    val frac = h - region
    val r: Float
    val g: Float
    val b: Float
    when (region) {
        0 -> { r = 1f; g = frac; b = 0f }
        1 -> { r = 1f - frac; g = 1f; b = 0f }
        2 -> { r = 0f; g = 1f; b = frac }
        3 -> { r = 0f; g = 1f - frac; b = 1f }
        4 -> { r = frac; g = 0f; b = 1f }
        else -> { r = 1f; g = 0f; b = 1f - frac }
    }
    return Rgb((r * 255f).roundToInt(), (g * 255f).roundToInt(), (b * 255f).roundToInt())
}

private fun ledCenter(index: Int, count: Int): Float {
    if (count <= 1) return 0f
    return (index + 0.5f) / count
}

private fun wrap01(x: Float): Float {
    var v = x - floor(x)
    if (v < 0f) v += 1f
    return v
}
