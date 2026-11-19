package com.lgfrir.leddrivebt.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lgfrir.leddrivebt.protocol.PRESET_ID_NONE
import com.lgfrir.leddrivebt.protocol.formatMinute
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.lgfrir.leddrivebt.protocol.IO_MODE_MANUAL
import com.lgfrir.leddrivebt.protocol.IO_MODE_TIME
import com.lgfrir.leddrivebt.protocol.PRESET_ROM_FIRST
import com.lgfrir.leddrivebt.protocol.PRESET_ROM_LAST
import com.lgfrir.leddrivebt.protocol.PresetRecord
import com.lgfrir.leddrivebt.protocol.ScheduleRecord
import com.lgfrir.leddrivebt.protocol.SegBinding
import com.lgfrir.leddrivebt.protocol.UsageReport
import com.lgfrir.leddrivebt.protocol.clockOfDay
import com.lgfrir.leddrivebt.protocol.formatClock
import com.lgfrir.leddrivebt.protocol.formatEpoch
import com.lgfrir.leddrivebt.protocol.modeLabel
import com.lgfrir.leddrivebt.protocol.presetTitle
import com.lgfrir.leddrivebt.ui.theme.AppType
import com.lgfrir.leddrivebt.ui.theme.MinTouchTarget
import com.lgfrir.leddrivebt.ui.theme.Radius
import com.lgfrir.leddrivebt.ui.theme.Space

private val EFFECT_MODES = listOf("关灯", "随波逐流", "常亮", "呼吸", "追逐", "多点渐变", "爆闪")

@Composable
fun IoModeBlock(
    mode: Int,
    nextEvent: Long,
    onMode: (Int) -> Unit,
    onPreview: (() -> Unit)? = null,
    scheduleName: String = "",
    onPickSchedule: (() -> Unit)? = null,
    schedules: List<ScheduleRecord> = emptyList(),
    onChooseSchedule: (Int) -> Unit = {},
    onResetPhase: () -> Unit = {},
    backdrop: ImageBitmap? = null,
) {
    var scheduleOpen by remember { mutableStateOf(false) }
    AcrylicGroup {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
                FilterChip(selected = mode == IO_MODE_MANUAL, onClick = { onMode(IO_MODE_MANUAL) }, label = { Text("手动") })
                FilterChip(selected = mode == IO_MODE_TIME, onClick = { onMode(IO_MODE_TIME) }, label = { Text("定时") })
            }
            PhaseResetButton(onResetPhase)
        }
        if (onPickSchedule != null || onPreview != null) {
            ScheduleSplit(
                name = scheduleName,
                open = scheduleOpen,
                schedules = schedules,
                backdrop = backdrop,
                onToggle = {
                    if (!scheduleOpen) onPickSchedule?.invoke()
                    scheduleOpen = !scheduleOpen
                },
                onPreview = onPreview,
                showMenu = onPickSchedule != null,
                onChoose = { id ->
                    scheduleOpen = false
                    onChooseSchedule(id)
                },
            )
        }
    }
}

/**
 * 左边的调度名自己一个圆角框。预览灯效留在外面，和以前的文字按钮一样。
 * 展开只拉高左边这个框，浮在下面，不把页面往下顶。
 */
@Composable
private fun ScheduleSplit(
    name: String,
    open: Boolean,
    schedules: List<ScheduleRecord>,
    backdrop: ImageBitmap?,
    onToggle: () -> Unit,
    onPreview: (() -> Unit)?,
    showMenu: Boolean,
    onChoose: (Int) -> Unit,
) {
    var widthPx by remember { mutableIntStateOf(0) }
    var leftPx by remember { mutableIntStateOf(0) }
    var topPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val screen = LocalWindowInfo.current.containerSize
    Row(
        Modifier.padding(top = Space.x2).fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        if (showMenu) {
            Box(
                Modifier
                    .weight(1f)
                    .height(MinTouchTarget)
                    .onGloballyPositioned { coords ->
                        val origin = coords.positionInWindow()
                        widthPx = coords.size.width
                        leftPx = origin.x.toInt()
                        topPx = origin.y.toInt()
                    },
            ) {
                if (!open) {
                    ScheduleMenu(
                        name = name,
                        open = false,
                        schedules = schedules,
                        backdrop = backdrop,
                        screenOrigin = Offset(leftPx.toFloat(), topPx.toFloat()),
                        screenSize = screen,
                        onToggle = onToggle,
                        onChoose = onChoose,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (open && widthPx > 0) {
                    Popup(
                        popupPositionProvider = object : PopupPositionProvider {
                            override fun calculatePosition(
                                anchorBounds: IntRect,
                                windowSize: IntSize,
                                layoutDirection: LayoutDirection,
                                popupContentSize: IntSize,
                            ): IntOffset = IntOffset(anchorBounds.left, anchorBounds.top)
                        },
                        onDismissRequest = onToggle,
                        properties = PopupProperties(focusable = true, clippingEnabled = false),
                    ) {
                        ScheduleMenu(
                            name = name,
                            open = true,
                            schedules = schedules,
                            backdrop = backdrop,
                            screenOrigin = Offset(leftPx.toFloat(), topPx.toFloat()),
                            screenSize = screen,
                            onToggle = onToggle,
                            onChoose = onChoose,
                            modifier = Modifier.width(with(density) { widthPx.toDp() }),
                        )
                    }
                }
            }
        }
        if (onPreview != null) {
            TextButton(
                onClick = onPreview,
                modifier = Modifier.heightIn(min = MinTouchTarget),
            ) { Text("预览灯效") }
        }
    }
}

@Composable
private fun ScheduleMenu(
    name: String,
    open: Boolean,
    schedules: List<ScheduleRecord>,
    backdrop: ImageBitmap?,
    screenOrigin: Offset,
    screenSize: IntSize,
    onToggle: () -> Unit,
    onChoose: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val density = LocalDensity.current
    val rows = schedules.size.coerceAtLeast(1)
    val namePx = with(density) { MinTouchTarget.roundToPx() }
    val roomPx = (screenSize.height - screenOrigin.y.toInt() - namePx).coerceAtLeast(namePx)
    val optionPx = if (!open) 0 else (namePx * rows).coerceAtMost(roomPx)
    val total = with(density) { (namePx + optionPx).toDp() }
    Column(
        modifier
            .height(total)
            .piercedPlate(backdrop, screenOrigin, screenSize),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(MinTouchTarget)
                .clickable(onClick = onToggle)
                .padding(horizontal = Space.x3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                name,
                color = palette.text,
                style = AppType.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            ChevronDown(palette.text)
        }
        if (open) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (schedules.isEmpty()) {
                    Box(
                        Modifier.fillMaxWidth().height(MinTouchTarget).padding(horizontal = Space.x3),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text("还没有调度预设", color = palette.muted, style = AppType.body)
                    }
                }
                schedules.forEach { sched ->
                    val title = sched.name.ifEmpty { "调度 ${sched.id}" }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(MinTouchTarget)
                            .clickable { onChoose(sched.id) }
                            .padding(horizontal = Space.x3),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            title,
                            color = if (title == name) palette.accent else palette.text,
                            style = AppType.body,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun GlobalLibraryRow(onBackup: () -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        TextButton(onClick = onBackup, modifier = Modifier.heightIn(min = MinTouchTarget)) { Text("备份") }
    }
}

@Composable
fun PresetBrowser(
    presets: List<PresetRecord>,
    mode: Int,
    onMode: (Int) -> Unit,
    onOpen: (PresetRecord) -> Unit,
    onCreate: (() -> Unit)?,
    showPreview: Boolean,
    modifier: Modifier = Modifier,
    sharedPlate: Boolean = false,
    includeRecent: Boolean = false,
    recentPresets: List<PresetRecord> = emptyList(),
) {
    Column(modifier) {
        val shown = if (mode < 0) {
            recentPresets
        } else {
            presets.filter { it.detail.mode == mode }.sortedWith(compareBy({ it.id < PRESET_ROM_FIRST }, { it.id }))
        }
        val emptyText = if (mode < 0) "还没有选过。这次进来的顺序不会跟着变。" else "这一类还没有预设"
        if (sharedPlate) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .surfaceCard(CardLevel.Base)
                    .padding(horizontal = Space.x3, vertical = Space.x3),
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .surfaceCard(CardLevel.Raised)
                        .padding(horizontal = Space.x3, vertical = Space.x2),
                ) {
                    ModeStrip(mode, onMode, includeRecent)
                }
                PresetRows(shown, showPreview, onOpen, plated = true, Modifier.weight(1f).fillMaxWidth(), emptyText)
            }
            return@Column
        }
        AcrylicGroup { ModeStrip(mode, onMode, includeRecent) }
        if (onCreate != null) {
            TextButton(onClick = onCreate, modifier = Modifier.heightIn(min = MinTouchTarget)) { Text("新建") }
        }
        PresetRows(shown, showPreview, onOpen, plated = true, Modifier.weight(1f).fillMaxWidth(), emptyText)
    }
}

@Composable
private fun ModeStrip(mode: Int, onMode: (Int) -> Unit, includeRecent: Boolean = false) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (includeRecent) {
            FilterChip(selected = mode < 0, onClick = { onMode(-1) }, label = { Text("最近") })
        }
        EFFECT_MODES.forEachIndexed { index, label ->
            FilterChip(selected = mode == index, onClick = { onMode(index) }, label = { Text(label) })
        }
    }
}

@Composable
private fun ColumnScope.PresetRows(
    shown: List<PresetRecord>,
    showPreview: Boolean,
    onOpen: (PresetRecord) -> Unit,
    plated: Boolean,
    modifier: Modifier,
    emptyText: String = "这一类还没有预设",
) {
        RubberColumn(modifier) {
            if (shown.isEmpty()) {
                Text(
                    emptyText,
                    color = LocalPalette.current.muted,
                    style = AppType.body,
                    modifier = Modifier.padding(top = Space.x3),
                )
            }
            shown.forEachIndexed { index, preset ->
                EnterOnce(index) {
                    val rom = preset.id in PRESET_ROM_FIRST..PRESET_ROM_LAST
                    val row = Modifier
                        .fillMaxWidth()
                        .padding(top = Space.x2)
                        .then(if (plated) Modifier.surfaceCard(CardLevel.Raised) else Modifier)
                        .tapTarget(description = preset.name.ifEmpty { presetTitle(preset.id, "") }) { onOpen(preset) }
                        .padding(horizontal = if (plated) Space.x3 else Space.x1, vertical = Space.x2)
                    Row(
                        row,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            preset.name.ifEmpty { presetTitle(preset.id, "") },
                            color = LocalPalette.current.text,
                            style = AppType.strong,
                            textDecoration = if (rom) TextDecoration.Underline else null,
                            modifier = Modifier.weight(1f),
                        )
                        if (showPreview) {
                            Box(Modifier.width(108.dp).height(22.dp)) {
                                EffectPreview(preset.detail, 24, barHeight = 16.dp)
                            }
                        }
                    }
                }
            }
        }
}

@Composable
fun ScheduleList(schedules: List<ScheduleRecord>, onOpen: (ScheduleRecord) -> Unit, onCreate: () -> Unit) {
    if (schedules.isEmpty()) {
        EmptyState(
            "还没有调度。新建一套，给灯段排上时间点。",
            actionLabel = "新建调度",
            onAction = onCreate,
        )
        return
    }
    TextButton(onClick = onCreate, modifier = Modifier.heightIn(min = MinTouchTarget)) { Text("新建") }
    schedules.forEachIndexed { index, sched ->
        EnterOnce(index) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = Space.x2)
                    .surfaceCard(CardLevel.Raised)
                    .tapTarget(description = sched.name.ifEmpty { "调度 ${sched.id}" }) { onOpen(sched) }
                    .padding(horizontal = Space.x3, vertical = Space.x3),
            ) {
                Text(
                    sched.name.ifEmpty { "调度 ${sched.id}" },
                    color = LocalPalette.current.text,
                    style = AppType.strong,
                )
                Text(
                    sched.atSec.joinToString("  ") { formatMinute(it) },
                    color = LocalPalette.current.muted,
                    style = AppType.caption,
                )
            }
        }
    }
}

@Composable
fun ScheduleEditor(
    record: ScheduleRecord,
    slotMax: Int,
    onChange: (ScheduleRecord) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
) {
    var editing by remember { mutableStateOf<Int?>(null) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = Space.x3)
            .surfaceCard(CardLevel.Base)
            .padding(horizontal = Space.x3, vertical = Space.x3),
    ) {
        NameRow(record.name) { onChange(record.copy(name = it)) }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = Space.x3)
            .surfaceCard(CardLevel.Base)
            .padding(horizontal = Space.x3, vertical = Space.x3),
    ) {
        if (record.atSec.isEmpty()) {
            Text("还没有时间点。", color = LocalPalette.current.muted, style = AppType.body)
        }
        record.atSec.forEachIndexed { slot, sec ->
            key(slot) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = if (slot == 0) 0.dp else Space.x2)
                        .surfaceCard(CardLevel.Raised)
                        .tapTarget(description = "时间点 ${slot + 1}") {
                            editing = if (editing == slot) null else slot
                        }
                        .padding(horizontal = Space.x3, vertical = Space.x3),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("时间点 ${slot + 1}", color = LocalPalette.current.text, style = AppType.strong)
                    Text(formatMinute(sec), color = LocalPalette.current.text, style = AppType.body)
                }
                if (editing == slot) {
                    TimePointEditor(
                        second = sec,
                        canDelete = record.atSec.size > 1,
                        onConfirm = { hour, minute ->
                            val next = record.atSec.toMutableList()
                            next[slot] = clockOfDay(hour, minute, 0)
                            onChange(record.copy(atSec = next))
                            editing = null
                        },
                        onDelete = {
                            onChange(record.copy(atSec = record.atSec.filterIndexed { i, _ -> i != slot }))
                            editing = null
                        },
                    )
                }
            }
        }
    }
    if (record.atSec.size < slotMax) {
        TextButton(
            onClick = {
                val next = clockOfDay(12, 0, 0)
                onChange(record.copy(atSec = record.atSec + next))
            },
            modifier = Modifier.heightIn(min = MinTouchTarget),
        ) { Text("添加时间点") }
    }
    val nameTooLong = record.name.toByteArray(Charsets.UTF_8).size > 63
    Button(
        onClick = onSave,
        enabled = !nameTooLong,
        modifier = Modifier.fillMaxWidth().padding(top = Space.x4).heightIn(min = 48.dp),
    ) { Text("写入") }
    if (record.id != 0) {
        TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)) { Text("删除") }
    }
}

@Composable
private fun TimePointEditor(
    second: Int,
    canDelete: Boolean,
    onConfirm: (Int, Int) -> Unit,
    onDelete: () -> Unit,
) {
    var hour by remember(second) { mutableIntStateOf(second.coerceIn(0, 86399) / 3600) }
    var minute by remember(second) { mutableIntStateOf((second.coerceIn(0, 86399) / 60) % 60) }
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = Space.x2)
            .surfaceCard(CardLevel.Overlay)
            .padding(vertical = Space.x1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NumberWheel(hour, 0, 23, Modifier.weight(1f)) { hour = it }
        Text(":", color = palette.text, style = AppType.hero)
        NumberWheel(minute, 0, 59, Modifier.weight(1f)) { minute = it }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = Space.x2)
            .surfaceCard(CardLevel.Raised)
            .padding(horizontal = Space.x1, vertical = Space.x1),
        horizontalArrangement = if (canDelete) Arrangement.SpaceBetween else Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canDelete) {
            TextButton(onClick = onDelete) { Text("删除") }
        }
        TextButton(onClick = { onConfirm(hour, minute) }) { Text("确定") }
    }
}

@Composable
private fun NumberWheel(
    value: Int,
    min: Int,
    max: Int,
    modifier: Modifier = Modifier,
    onChange: (Int) -> Unit,
) {
    val palette = LocalPalette.current
    val count = max - min + 1
    val itemHeight = 48.dp
    val itemPx = with(LocalDensity.current) { itemHeight.toPx() }
    var settled by remember { mutableIntStateOf(value) }
    var shift by remember { mutableFloatStateOf(0f) }
    val emit = rememberUpdatedState(onChange)
    val itemPxState = rememberUpdatedState(itemPx)
    val scope = rememberCoroutineScope()
    val snapJob = remember { mutableStateOf<Job?>(null) }
    fun wrap(raw: Int): Int {
        val mod = (raw - min) % count
        return min + if (mod < 0) mod + count else mod
    }
    Box(
        modifier
            .height(itemHeight * 3)
            .clipToBounds()
            .pointerInput(count) {
                fun launchSnap() {
                    snapJob.value?.cancel()
                    val px = itemPxState.value
                    if (px <= 0f) return
                    snapJob.value = scope.launch {
                        val k = (-shift / px).roundToInt()
                        if (k != 0) {
                            val next = wrap(settled + k)
                            val carried = shift + k * px
                            settled = next
                            shift = carried
                            emit.value(next)
                        }
                        val from = shift
                        if (from != 0f) {
                            animate(from, 0f, animationSpec = tween(160)) { v, _ -> shift = v }
                        }
                    }
                }
                awaitEachGesture {
                    val down = awaitFirstDown(pass = PointerEventPass.Initial, requireUnconsumed = false)
                    snapJob.value?.cancel()
                    val pointer = down.id
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == pointer }
                        if (change == null || !change.pressed) {
                            launchSnap()
                            break
                        }
                        val dy = change.position.y - change.previousPosition.y
                        if (dy != 0f) {
                            change.consume()
                            shift += dy
                        }
                    }
                }
            },
    ) {
        val centerSteps = if (itemPx == 0f) 0f else -shift / itemPx
        val first = floor(centerSteps).toInt() - 2
        val last = ceil(centerSteps).toInt() + 2
        val centerY = itemPx * 1.5f
        for (k in first..last) {
            key(k) {
                val delta = k * itemPx + shift
                val dist = if (itemPx == 0f) 0f else abs(delta) / itemPx
                val scale = (1f - dist * 0.36f).coerceIn(0.5f, 1f)
                val alpha = (1f - dist * 0.34f).coerceIn(0.4f, 1f)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(itemHeight)
                        .offset { IntOffset(0, (centerY + delta - itemPx / 2f).roundToInt()) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "%02d".format(wrap(settled + k)),
                        color = palette.text.copy(alpha = alpha),
                        style = AppType.hero,
                        modifier = Modifier.graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun BindingList(
    rows: List<Triple<Int, String, String>>,
    onOpen: (Int) -> Unit,
) {
    rows.forEachIndexed { index, (segId, segName, summary) ->
        EnterOnce(index) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = Space.x2)
                    .surfaceCard(CardLevel.Raised)
                    .tapTarget(description = "$segName $summary") { onOpen(segId) }
                    .padding(horizontal = Space.x3, vertical = Space.x3),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(segName, color = LocalPalette.current.text, style = AppType.strong)
                Text(summary, color = LocalPalette.current.muted, style = AppType.caption)
            }
        }
    }
}

@Composable
fun BindingEditor(
    binding: SegBinding,
    times: List<Int>,
    presets: List<PresetRecord>,
    onChange: (SegBinding) -> Unit,
    onSave: () -> Unit,
    onUnbind: () -> Unit,
    schedules: List<ScheduleRecord> = emptyList(),
) {
    AcrylicGroup {
        Text("调度", color = LocalPalette.current.text, fontWeight = FontWeight.Medium)
        if (schedules.isEmpty()) {
            Text(
                if (binding.schedId == 0) "未绑定" else "调度 ${binding.schedId}",
                color = LocalPalette.current.muted,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            Row(
                Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                schedules.forEach { sched ->
                    FilterChip(
                        selected = binding.schedId == sched.id,
                        onClick = {
                            val ids = List(sched.atSec.size) { index -> binding.presetIds.getOrElse(index) { PRESET_ID_NONE } }
                            onChange(binding.copy(schedId = sched.id, presetIds = ids))
                        },
                        label = { Text(sched.name.ifEmpty { "调度 ${sched.id}" }) },
                    )
                }
            }
        }
    }
    val count = maxOf(times.size, binding.presetIds.size)
    repeat(count) { index ->
        val presetId = binding.presetIds.getOrElse(index) { PRESET_ID_NONE }
        AcrylicGroup {
            Text(times.getOrNull(index)?.let(::formatClock) ?: "槽 ${index + 1}", color = LocalPalette.current.text, fontWeight = FontWeight.Medium)
            Row(
                Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = presetId == PRESET_ID_NONE,
                    onClick = { onChange(binding.withSlot(index, PRESET_ID_NONE, count)) },
                    label = { Text("关灯") },
                )
                presets.forEach { preset ->
                    FilterChip(
                        selected = presetId == preset.id,
                        onClick = { onChange(binding.withSlot(index, preset.id, count)) },
                        label = {
                            Text(
                                preset.name.ifEmpty { presetTitle(preset.id, "") },
                                textDecoration = if (preset.id in PRESET_ROM_FIRST..PRESET_ROM_LAST) TextDecoration.Underline else null,
                            )
                        },
                    )
                }
            }
        }
    }
    Button(onClick = onSave, modifier = Modifier.fillMaxWidth().padding(top = Space.x4).heightIn(min = 48.dp)) { Text("写入") }
    TextButton(onClick = onUnbind, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget)) { Text("解除绑定") }
}

@Composable
fun BackupBlock(note: String, onExport: () -> Unit, onImport: () -> Unit) {
    AcrylicGroup {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
            OutlinedButton(onClick = onExport, modifier = Modifier.heightIn(min = MinTouchTarget)) { Text("导出") }
            OutlinedButton(onClick = onImport, modifier = Modifier.heightIn(min = MinTouchTarget)) { Text("导入") }
        }
        if (note.isNotEmpty()) {
            Text(note, color = LocalPalette.current.muted, style = AppType.body, modifier = Modifier.padding(top = Space.x2))
        }
    }
}

@Composable
fun UsageDialog(title: String, report: UsageReport, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text("共 ${report.total} 段使用")
                report.lines.forEach { line ->
                    Text("第 ${line.io} 路  ${line.name}", color = LocalPalette.current.muted)
                }
                if (title.contains("调度")) {
                    Text("这些灯段会解除绑定，时间模式下会关灯。", modifier = Modifier.padding(top = 8.dp))
                }
                if (title.contains("预设")) {
                    Text("删除后，正在使用它的灯段会改绑成同一种模式的内置效果。", modifier = Modifier.padding(top = 8.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("删除") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun SavePresetDialog(onSave: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("这次修改") },
        text = { Text("保存为新预设，或放弃并恢复灯带上的效果。") },
        confirmButton = { TextButton(onClick = onSave) { Text("保存为新预设") } },
        dismissButton = { TextButton(onClick = onDiscard) { Text("放弃并恢复") } },
    )
}

@Composable
fun DuplicateTimeDialog(onReplace: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("时间点重复") },
        text = { Text("保留后一次，去掉前一次？") },
        confirmButton = { TextButton(onClick = onReplace) { Text("覆盖") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
fun SlotAssign(
    binding: SegBinding,
    times: List<Int>,
    presets: List<PresetRecord>,
    onChange: (SegBinding) -> Unit,
    modifier: Modifier = Modifier,
) {
    var mode by remember { mutableIntStateOf(-1) }
    var cursor by remember(times, binding.segId) {
        mutableStateOf(firstOpenSlot(binding, times))
    }
    val context = LocalContext.current
    val recentStore = remember { RecentModeStore(context) }
    var recentOrder by remember { mutableStateOf(recentStore.ranked()) }
    val recentPresets = recentOrder.mapNotNull { id -> presets.firstOrNull { preset -> preset.id == id } }
    val palette = LocalPalette.current
    Column(modifier) {
        PresetBrowser(
            presets = presets,
            mode = mode,
            onMode = { next ->
                if (next < 0 && mode >= 0) recentOrder = recentStore.ranked()
                mode = next
            },
            onOpen = { preset ->
                val index = cursor ?: return@PresetBrowser
                if (index !in times.indices) return@PresetBrowser
                recentStore.bump(preset.id)
                val next = binding.withSlot(index, preset.id, times.size)
                onChange(next)
                cursor = (index + 1 until times.size).firstOrNull { slot ->
                    next.presetIds.getOrElse(slot) { PRESET_ID_NONE } == PRESET_ID_NONE
                }
            },
            onCreate = null,
            showPreview = false,
            sharedPlate = true,
            includeRecent = true,
            recentPresets = recentPresets,
            modifier = Modifier.weight(2f).fillMaxWidth(),
        )
        Box(
            Modifier
                .padding(vertical = 10.dp)
                .fillMaxWidth()
                .height(1.dp)
                .background(Color.Black.copy(alpha = 0.2f)),
        )
        Box(
            Modifier
                .weight(3f)
                .fillMaxWidth()
                .surfaceCard(CardLevel.Base),
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.x2, vertical = Space.x2),
            ) {
                times.forEachIndexed { index, sec ->
                    val presetId = binding.presetIds.getOrElse(index) { PRESET_ID_NONE }
                    val preset = presets.firstOrNull { it.id == presetId }
                    val caption = when {
                        preset != null -> {
                            val modeName = EFFECT_MODES.getOrElse(preset.detail.mode) { "关灯" }
                            "$modeName·${preset.name.ifEmpty { presetTitle(preset.id, "") }}"
                        }
                        presetId != PRESET_ID_NONE -> presetTitle(presetId, "")
                        else -> ""
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = if (index == 0) 0.dp else Space.x2)
                            .heightIn(min = MinTouchTarget, max = MinTouchTarget)
                            .surfaceCard(CardLevel.Overlay)
                            .tapTarget(description = "${formatMinute(sec)} $caption") { cursor = index }
                            .padding(start = Space.x3, end = Space.x1),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            formatMinute(sec),
                            color = if (cursor == index) palette.ok else palette.text,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            caption,
                            color = palette.text,
                            modifier = Modifier.weight(1f).padding(start = Space.x4),
                            maxLines = 1,
                        )
                        Box(
                            Modifier
                                .size(MinTouchTarget)
                                .tapTarget(
                                    enabled = presetId != PRESET_ID_NONE,
                                    description = "去掉 ${formatMinute(sec)}",
                                ) {
                                    onChange(binding.withSlot(index, PRESET_ID_NONE, times.size))
                                    if (cursor == index) cursor = null
                                },
                            contentAlignment = Alignment.CenterEnd,
                        ) {
                            if (presetId != PRESET_ID_NONE) {
                                Text("×", color = palette.text, style = AppType.title)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ColumnScope.RubberColumn(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    val pull = remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val connection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val current = pull.floatValue
                if (current == 0f || source != NestedScrollSource.UserInput) return Offset.Zero
                val next = current + available.y
                val consumeY = when {
                    current > 0f && available.y < 0f -> if (next < 0f) -current else available.y
                    current < 0f && available.y > 0f -> if (next > 0f) -current else available.y
                    else -> 0f
                }
                if (consumeY != 0f) pull.floatValue = current + consumeY
                return Offset(0f, consumeY)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y == 0f || source != NestedScrollSource.UserInput) return Offset.Zero
                val scale = 1f + kotlin.math.abs(pull.floatValue) / 48f
                pull.floatValue = (pull.floatValue + available.y / scale).coerceIn(-140f, 140f)
                return available
            }
        }
    }
    Column(
        modifier
            .clipToBounds()
            .nestedScroll(connection)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                    } while (event.changes.any { it.pressed })
                    val from = pull.floatValue
                    scope.launch {
                        animate(from, 0f, animationSpec = spring()) { value, _ -> pull.floatValue = value }
                    }
                }
            }
            .graphicsLayer { translationY = pull.floatValue }
            .verticalScroll(scroll),
        content = content,
    )
}

@Composable
private fun NameRow(name: String, onChange: (String) -> Unit) {
    val palette = LocalPalette.current
    Text("名称", color = palette.muted, style = AppType.caption)
    BasicTextField(
        value = name,
        onValueChange = onChange,
        singleLine = true,
        textStyle = AppType.strong.copy(color = palette.text),
        cursorBrush = SolidColor(palette.text),
        modifier = Modifier.fillMaxWidth().padding(top = Space.x1),
    )
    val used = name.toByteArray(Charsets.UTF_8).size
    Text(
        "已用 $used/63 字节",
        color = if (used > 63) palette.warn else palette.muted,
        style = AppType.caption,
        modifier = Modifier.padding(top = Space.x1),
    )
}

private fun firstOpenSlot(binding: SegBinding, times: List<Int>): Int? {
    val open = times.indices.firstOrNull { slot ->
        binding.presetIds.getOrElse(slot) { PRESET_ID_NONE } == PRESET_ID_NONE
    }
    return open ?: times.indices.firstOrNull()
}

private fun SegBinding.withSlot(index: Int, presetId: Int, count: Int): SegBinding {
    val size = maxOf(count, index + 1, presetIds.size)
    val next = MutableList(size) { presetIds.getOrElse(it) { PRESET_ID_NONE } }
    next[index] = presetId
    return copy(presetIds = next)
}

@Composable
private fun PhaseResetButton(onClick: () -> Unit) {
    val color = LocalPalette.current.accent
    Box(
        Modifier.size(MinTouchTarget).tapTarget(description = "相位归零", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(11.dp)) {
            val width = 1.5.dp.toPx()
            drawCircle(
                color = color,
                radius = size.minDimension / 2f - width / 2f,
                style = Stroke(width = width),
            )
        }
    }
}

/** 灯段调度里「最近」的次数。只在进入这一档时读一次，选择过程中不重排。 */
class RecentModeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("leddrive_recent_modes", Context.MODE_PRIVATE)

    fun ranked(): List<Int> = prefs.all.mapNotNull { (key, value) ->
        val id = key.toIntOrNull() ?: return@mapNotNull null
        val count = value as? Int ?: return@mapNotNull null
        if (count <= 0) null else id to count
    }.sortedWith(compareByDescending<Pair<Int, Int>> { it.second }.thenBy { it.first }).map { it.first }

    fun bump(id: Int) {
        prefs.edit().putInt(id.toString(), prefs.getInt(id.toString(), 0) + 1).apply()
    }
}
