package com.lgfrir.leddrivebt.ui

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.compose.runtime.withFrameMillis
import com.lgfrir.leddrivebt.ble.BleLogLine
import com.lgfrir.leddrivebt.ble.LinkPhase
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lgfrir.leddrivebt.protocol.BreathCfg
import com.lgfrir.leddrivebt.protocol.ChaseCfg
import com.lgfrir.leddrivebt.protocol.DIR_LEFT
import com.lgfrir.leddrivebt.protocol.DIR_RIGHT
import com.lgfrir.leddrivebt.protocol.FLASH_BLINK
import com.lgfrir.leddrivebt.protocol.FLASH_SWITCH
import com.lgfrir.leddrivebt.protocol.IoNode
import com.lgfrir.leddrivebt.protocol.IO_MODE_TIME
import com.lgfrir.leddrivebt.protocol.FlashCfg
import com.lgfrir.leddrivebt.protocol.MODE_LABELS
import com.lgfrir.leddrivebt.protocol.MODE_POINTS
import com.lgfrir.leddrivebt.protocol.PRESET_ID_NONE
import com.lgfrir.leddrivebt.protocol.PRESET_ROM_FIRST
import com.lgfrir.leddrivebt.protocol.PRESET_ROM_LAST
import com.lgfrir.leddrivebt.protocol.PresetRecord
import com.lgfrir.leddrivebt.protocol.ScheduleRecord
import com.lgfrir.leddrivebt.protocol.REFRESH_MAX
import com.lgfrir.leddrivebt.protocol.REFRESH_MIN
import com.lgfrir.leddrivebt.protocol.SPEED_MAX
import com.lgfrir.leddrivebt.protocol.PointCfg
import com.lgfrir.leddrivebt.protocol.PointsCfg
import com.lgfrir.leddrivebt.protocol.Rgb
import com.lgfrir.leddrivebt.protocol.SegBinding
import com.lgfrir.leddrivebt.protocol.SegmentDetail
import com.lgfrir.leddrivebt.protocol.SegmentHead
import com.lgfrir.leddrivebt.protocol.StaticCfg
import com.lgfrir.leddrivebt.protocol.WaveCfg
import com.lgfrir.leddrivebt.protocol.activeSlotIndex
import com.lgfrir.leddrivebt.protocol.encodeEffect
import com.lgfrir.leddrivebt.protocol.formatMinute
import com.lgfrir.leddrivebt.protocol.modeLabel
import com.lgfrir.leddrivebt.protocol.nameBytes
import com.lgfrir.leddrivebt.protocol.nextSlotSec
import com.lgfrir.leddrivebt.ui.theme.AppType
import com.lgfrir.leddrivebt.ui.theme.LocalTypeScale
import com.lgfrir.leddrivebt.ui.theme.typeScaleOf
import com.lgfrir.leddrivebt.ui.theme.MinTouchTarget
import com.lgfrir.leddrivebt.ui.theme.Motion
import com.lgfrir.leddrivebt.ui.theme.Space
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.onGloballyPositioned
import android.net.wifi.WifiManager
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.lgfrir.leddrivebt.ui.theme.Radius

/**
 * 页面。depth 决定转场方向：更深=进子页（从右滑入），更浅=返回（从左滑入），同深=只淡入。
 */
private enum class Desk(val depth: Int) {
    Work(0),
    Settings(1),
    Appearance(2),
    Log(2),
    Monitor(1),
    Presets(1),
    PresetEdit(2),
    Schedules(1),
    ScheduleEdit(2),
    ScheduleBoard(1),
    SlotEdit(2),
    Backup(1),
}

@Composable
fun LightApp(model: LightViewModel) {
    val ui by model.ui.collectAsStateWithLifecycle()
    val appearance by model.appearance.collectAsStateWithLifecycle()
    val backdrop by model.backdrop.collectAsStateWithLifecycle()
    val backdropFailed by model.backdropFailed.collectAsStateWithLifecycle()
    val library by model.library.state.collectAsStateWithLifecycle()
    val clockReady by model.clockReady.collectAsStateWithLifecycle()
    val ioModes by model.ioModes.collectAsStateWithLifecycle()
    val clockNote by model.clockNote.collectAsStateWithLifecycle()
    val synced by model.synced.collectAsStateWithLifecycle()
    val deviceEpoch by model.deviceEpoch.collectAsStateWithLifecycle()
    val peerName by model.peerName.collectAsStateWithLifecycle()
    val epochMark by model.epochMark.collectAsStateWithLifecycle()
    val connecting = ui.phase == LinkPhase.Connecting
    // 连接一结束就收起，不额外停留。
    val connectLog = if (connecting) {
        model.connectLog.collectAsStateWithLifecycle().value
    } else {
        emptyList()
    }
    var deskName by rememberSaveable { mutableStateOf(Desk.Work.name) }
    var desk by remember {
        mutableStateOf(runCatching { Desk.valueOf(deskName) }.getOrDefault(Desk.Work))
    }
    LaunchedEffect(desk) { deskName = desk.name }
    var boardIo by rememberSaveable { mutableIntStateOf(1) }
    var slotSeg by rememberSaveable { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val host = LocalContext.current as? Activity
    DisposableEffect(lifecycleOwner, appearance.keepLink) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_STOP || appearance.keepLink) return@LifecycleEventObserver
            if (host?.isChangingConfigurations == true) return@LifecycleEventObserver
            val phase = model.ui.value.phase
            if (phase == LinkPhase.Ready || phase == LinkPhase.Connecting) model.disconnect()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    var pickIo by remember { mutableStateOf<Int?>(null) }
    var boardPreview by remember { mutableStateOf(false) }
    var presetMode by remember { mutableIntStateOf(0) }
    var leaveAsk by remember { mutableStateOf(false) }
    var presetBase by remember { mutableStateOf<PresetRecord?>(null) }
    var scheduleBase by remember { mutableStateOf<ScheduleRecord?>(null) }
    var confirmUnbind by remember { mutableStateOf(false) }
    val importTrim by model.importTrim.collectAsStateWithLifecycle()
    val previewClock = remember { PreviewClockSource() }
    /*
     * 只有"监控"页和灯段详情页需要逐帧动画。
     * 灯带页原来每段下面都挂一条 60fps 预览，一屏十几条 Canvas 一起刷新，
     * 主线程被占满，GATT 回调跟着排队——现在改成静态，不再起帧循环。
     */
    val animatePreview = desk == Desk.Monitor ||
        (desk == Desk.Work && ui.page == Page.Segment)
    LaunchedEffect(animatePreview) {
        if (!animatePreview) return@LaunchedEffect
        while (true) {
            previewClock.frame.longValue = withFrameMillis { it }
        }
    }
    val palette = remember(appearance.dark, appearance.ink) {
        (if (appearance.dark) DarkPalette else LightPalette).withInk(appearance.ink)
    }
    val scheme = remember(palette) { palette.scheme() }
    val typeScale = remember(
        appearance.fontCaption,
        appearance.fontBody,
        appearance.fontStrong,
        appearance.fontTitle,
        appearance.fontHero,
    ) { typeScaleOf(appearance.typeFonts()) }
    var glowAlpha by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(ui.phase, ui.dropReason) {
        when {
            ui.phase == LinkPhase.Connecting -> glowAlpha = 1f
            ui.phase == LinkPhase.Dropped && ui.dropReason.isNotEmpty() && ui.dropReason != "已断开" -> {
                glowAlpha = 1f
                delay(3_000)
                val blinkUntil = SystemClock.elapsedRealtime() + 3_000
                while (SystemClock.elapsedRealtime() < blinkUntil) {
                    glowAlpha = if (glowAlpha > 0.5f) 0.12f else 1f
                    delay(250)
                }
                glowAlpha = 0f
            }
            else -> glowAlpha = 0f
        }
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !palette.dark
            controller.isAppearanceLightNavigationBars = !palette.dark
        }
    }
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(
            LocalPalette provides palette,
            LocalAcrylic provides appearance.acrylic,
            LocalIoAcrylic provides appearance.ioAcrylic,
            LocalFilm provides appearance.film,
            LocalPreviewClock provides previewClock,
            LocalTypeScale provides typeScale,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(palette.page)
                    .drawWithContent {
                        drawContent()
                        if (glowAlpha > 0f) {
                            val reach = 22.dp.toPx()
                            val hot = Color(0xFFE23B3B).copy(alpha = 0.42f * glowAlpha)
                            val mid = Color(0xFFE23B3B).copy(alpha = 0.16f * glowAlpha)
                            val clear = Color.Transparent
                            drawRect(
                                brush = Brush.verticalGradient(listOf(hot, mid, clear), startY = 0f, endY = reach),
                                size = Size(size.width, reach),
                            )
                            drawRect(
                                brush = Brush.verticalGradient(listOf(clear, mid, hot), startY = size.height - reach, endY = size.height),
                                topLeft = Offset(0f, size.height - reach),
                                size = Size(size.width, reach),
                            )
                            drawRect(
                                brush = Brush.horizontalGradient(listOf(hot, mid, clear), startX = 0f, endX = reach),
                                size = Size(reach, size.height),
                            )
                            drawRect(
                                brush = Brush.horizontalGradient(listOf(clear, mid, hot), startX = size.width - reach, endX = size.width),
                                topLeft = Offset(size.width - reach, 0f),
                                size = Size(reach, size.height),
                            )
                        }
                    },
            ) {
                if (backdrop != null) {
                    Image(
                        bitmap = backdrop!!,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        /*
                         * 壁纸在解码时已经做过 box blur，这里用双线性就够了。
                         * FilterQuality.High 每帧对全屏做一次高质量重采样，转场时叠着两个页面会明显掉帧。
                         */
                        filterQuality = FilterQuality.Low,
                    )
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(palette.page.copy(alpha = appearance.scrim / 100f)),
                    )
                }
                Scaffold(
                    containerColor = if (backdrop == null) palette.page else Color.Transparent,
                    contentColor = palette.text,
                ) { padding ->
                    BackHandler(enabled = desk == Desk.Work && ui.page == Page.Segment) { model.closeSegment() }
                    val focusManager = LocalFocusManager.current
                    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
                    LaunchedEffect(imeBottom) {
                        if (imeBottom == 0) focusManager.clearFocus()
                    }
                    LaunchedEffect(Unit) {
                        // 存成功就回列表，同时把基准值刷新成刚存下的那条，
                        // 否则返回时 closePreset/closeSchedule 会认为"还有没保存的修改"。
                        model.presetSaved.collect {
                            presetBase = model.library.state.value.editingPreset
                            desk = Desk.Presets
                        }
                    }
                    LaunchedEffect(Unit) {
                        model.scheduleSaved.collect {
                            scheduleBase = model.library.state.value.editingSchedule
                            desk = Desk.Schedules
                        }
                    }
                    LaunchedEffect(Unit) {
                        model.presetDeleted.collect {
                            presetBase = null
                            desk = Desk.Presets
                        }
                    }
                    LaunchedEffect(Unit) {
                        model.scheduleDeleted.collect {
                            scheduleBase = null
                            desk = Desk.Schedules
                        }
                    }
                    LaunchedEffect(Unit) {
                        model.bindingSaved.collect { desk = Desk.Work }
                    }
                    LaunchedEffect(ui.phase) {
                        val devicePage = desk != Desk.Work && desk != Desk.Settings && desk != Desk.Appearance && desk != Desk.Log
                        if (ui.phase == LinkPhase.Dropped || (ui.phase == LinkPhase.Idle && devicePage)) {
                            desk = Desk.Work
                            boardPreview = false
                            pickIo = null
                        }
                    }
                    val closePreset = {
                        if (library.editingPreset != presetBase) leaveAsk = true else desk = Desk.Presets
                    }
                    val closeSchedule = {
                        if (library.editingSchedule != scheduleBase) leaveAsk = true else desk = Desk.Schedules
                    }
                    BackHandler(enabled = desk != Desk.Work || boardPreview || ui.phase == LinkPhase.Connecting) {
                        when {
                            ui.phase == LinkPhase.Connecting -> model.disconnect()
                            boardPreview -> {
                                boardPreview = false
                                desk = Desk.Work
                            }
                            desk == Desk.PresetEdit -> closePreset()
                            desk == Desk.ScheduleEdit -> closeSchedule()
                            desk == Desk.Log -> desk = Desk.Settings
                            desk == Desk.Appearance -> desk = Desk.Settings
                            desk == Desk.Settings -> desk = Desk.Work
                            desk == Desk.SlotEdit -> desk = Desk.Work
                            else -> desk = Desk.Work
                        }
                    }
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(horizontal = 16.dp)
                            .imePadding(),
                    ) {
                        AnimatedContent(
                            targetState = desk,
                            transitionSpec = {
                                val forward = targetState.depth > initialState.depth
                                if (targetState.depth == initialState.depth) {
                                    fadeIn(tween(Motion.sameLevel)) togetherWith
                                        fadeOut(tween(Motion.sameLevel))
                                } else {
                                    val dir = if (forward) 1 else -1
                                    (
                                        slideInHorizontally(tween(Motion.enter)) { full -> dir * full / 8 } +
                                            fadeIn(tween(Motion.enter))
                                        ) togetherWith (
                                        slideOutHorizontally(tween(Motion.enter)) { full -> -dir * full / 8 } +
                                            fadeOut(tween(Motion.enter))
                                        ) using SizeTransform(clip = false)
                                }
                            },
                            modifier = Modifier.fillMaxSize(),
                            label = "desk",
                        ) { current ->
                            Box(Modifier.fillMaxSize()) {
                                when (current) {
                            Desk.Settings -> AppChrome("设置", onBack = { desk = Desk.Work }) {
                                AcrylicGroup {
                                    if (ui.connected || ui.phase == LinkPhase.Connecting) {
                                        MenuRow("监控", enabled = ui.phase != LinkPhase.Connecting) {
                                            model.reloadMonitor()
                                            desk = Desk.Monitor
                                        }
                                    }
                                    MenuRow("外观") { desk = Desk.Appearance }
                                    MenuRow("日志") { desk = Desk.Log }
                                    MenuRow("备份") { desk = Desk.Backup }
                                }
                                AcrylicGroup {
                                    Row(
                                        Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text("离开应用后保持连接", color = LocalPalette.current.text, style = AppType.body)
                                        Switch(checked = appearance.keepLink, onCheckedChange = model::setKeepLink)
                                    }
                                    Hint("默认开启。关闭时，切到别的应用会断开灯带。")
                                }
                                Spacer(Modifier.weight(1f))
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = Space.x4)
                                        .heightIn(min = MinTouchTarget)
                                        .clip(RoundedCornerShape(Radius.medium))
                                        .background(Color(0xFFB42318))
                                        .tapTarget(description = "断开") {
                                            model.disconnect()
                                            desk = Desk.Work
                                        },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text("断开", color = Color.White, style = AppType.body)
                                }
                            }
                            Desk.Log -> AppChrome("日志", onBack = { desk = Desk.Settings }) {
                                val commLog by model.commLog.collectAsStateWithLifecycle()
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .surfaceCard(CardLevel.Base)
                                        .verticalScroll(rememberScrollState())
                                        .padding(horizontal = Space.x3, vertical = Space.x2),
                                ) {
                                    if (commLog.isEmpty()) {
                                        EmptyState(
                                            "还没有通信记录。",
                                            actionLabel = "去连接",
                                            onAction = { desk = Desk.Work },
                                        )
                                    }
                                    commLog.asReversed().forEach { line ->
                                        Text(
                                            "${line.time}  ${line.direction}  ${line.step}  ${if (line.ok) "成功" else "失败"}${if (line.detail.isBlank()) "" else "  ${line.detail}"}",
                                            color = if (line.ok) palette.text else palette.warn,
                                            style = AppType.body,
                                            modifier = Modifier.padding(top = Space.x2),
                                        )
                                    }
                                }
                            }
                            Desk.Appearance -> AppChrome("外观", onBack = { desk = Desk.Settings }) {
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .verticalScroll(rememberScrollState()),
                                ) {
                                    AppearancePanel(
                                        appearance = appearance,
                                        imageReady = backdrop != null,
                                        imageFailed = backdropFailed,
                                        onDark = model::setDark,
                                        onInk = model::setInk,
                                        onFont = model::setFont,
                                        onBlur = model::setBlur,
                                        onBlurPreview = model::setBlurPreview,
                                        onScrim = model::setScrim,
                                        onAcrylic = model::setAcrylic,
                                        onIoAcrylic = model::setIoAcrylic,
                                        onFilm = model::setFilm,
                                        onPick = model::setBackground,
                                        onClear = model::clearBackground,
                                    )
                                }
                            }
                            Desk.Monitor -> AppChrome("实时监控", onBack = { desk = Desk.Work }) {
                                val previews by model.previews.collectAsStateWithLifecycle()
                                var monitorTick by remember { mutableLongStateOf(0L) }
                                LaunchedEffect(Unit) {
                                    while (true) {
                                        delay(1_000)
                                        monitorTick = SystemClock.elapsedRealtime()
                                    }
                                }
                                val monitorNow = remember(deviceEpoch, epochMark, monitorTick) {
                                    shownEpoch(deviceEpoch, epochMark)
                                }
                                MonitorRoom(
                                    ios = ui.ios,
                                    previews = previews,
                                    nextTimes = ui.ios.associate { io ->
                                        val schedule = library.schedules.firstOrNull { it.id == library.scheduleIdOf(io.id) }
                                        io.id to schedule?.atSec.orEmpty()
                                    },
                                    epochSec = monitorNow,
                                    onConnect = { desk = Desk.Work },
                                    modifier = Modifier
                                        .padding(top = Space.x2)
                                        .weight(1f)
                                        .fillMaxWidth(),
                                )
                            }
                            Desk.Presets -> AppChrome("灯效", onBack = { desk = Desk.Work }) {
                                PresetBrowser(
                                    presets = library.presets,
                                    mode = presetMode,
                                    onMode = { presetMode = it },
                                    onOpen = {
                                        model.beginPreset(it)
                                        presetBase = model.library.state.value.editingPreset
                                        desk = Desk.PresetEdit
                                    },
                                    onCreate = {
                                        model.beginPreset(null)
                                        presetBase = model.library.state.value.editingPreset
                                        desk = Desk.PresetEdit
                                    },
                                    showPreview = true,
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                )
                            }
                            Desk.PresetEdit -> LibraryPage("灯效", closePreset, ui.message) {
                                PresetEditPage(model, library.editingPreset)
                            }
                            Desk.Schedules -> LibraryPage("调度", { desk = Desk.Work }, ui.message) {
                                ScheduleList(library.schedules, onOpen = {
                                    model.beginSchedule(it)
                                    scheduleBase = model.library.state.value.editingSchedule
                                    desk = Desk.ScheduleEdit
                                }, onCreate = {
                                    model.beginSchedule(null)
                                    scheduleBase = model.library.state.value.editingSchedule
                                    desk = Desk.ScheduleEdit
                                })
                            }
                            Desk.ScheduleEdit -> LibraryPage("调度", closeSchedule, ui.message) {
                                library.editingSchedule?.let { record ->
                                ScheduleEditor(
                                    record = record,
                                    slotMax = model.devInfo.collectAsStateWithLifecycle().value?.slotMax ?: 8,
                                    onChange = model::editSchedule,
                                    onSave = { model.saveSchedule() },
                                    onDelete = { model.requestDeleteSchedule(record.id) },
                                )
                                }
                            }
                            Desk.ScheduleBoard -> {
                                AppChrome("预览灯效", onBack = { boardPreview = false; desk = Desk.Work }) {
                                    SchedulePreview(
                                        io = ui.ios.firstOrNull { it.id == boardIo },
                                        library = library,
                                        scheduleId = library.scheduleIdOf(boardIo),
                                        modifier = Modifier.weight(1f).fillMaxWidth(),
                                    )
                                }
                            }
                            Desk.SlotEdit -> AppChrome(
                                title = ui.ios.flatMap { it.segments }.firstOrNull { it.id == slotSeg }?.name ?: "灯段",
                                onBack = { desk = Desk.Work },
                            ) {
                                val schedId = library.scheduleIdOf(boardIo)
                                val times = library.schedules.firstOrNull { it.id == schedId }?.atSec.orEmpty()
                                val binding = library.editingBinding?.takeIf { it.segId == slotSeg }
                                    ?: SegBinding(slotSeg, schedId, List(times.size) { PRESET_ID_NONE })
                                SlotAssign(
                                    binding = binding,
                                    times = times,
                                    presets = library.presets,
                                    onChange = model::editBinding,
                                    modifier = Modifier.weight(1f).fillMaxWidth(),
                                )
                                Button(
                                    onClick = model::saveBinding,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = Space.x2).heightIn(min = 48.dp),
                                ) {
                                    Text("写入")
                                }
                                TextButton(onClick = { confirmUnbind = true }, modifier = Modifier.fillMaxWidth()) {
                                    Text("解除绑定")
                                }
                                StatusLine(ui.message)
                            }
                            Desk.Backup -> LibraryPage("备份", { desk = Desk.Work }) {
                                val context = LocalContext.current
                                BackupActions(ui.message, { model.exportBackup(context) }, model::importBackup)
                            }
                            Desk.Work -> {
                                if (ui.page == Page.Segment) {
                                    AppChrome(
                                        title = ui.segName.ifEmpty { "灯段" },
                                        onBack = model::closeSegment,
                                    ) {
                                        ui.editor?.let { detail ->
                                            EffectPreview(detail, ui.segLength)
                                            if (detail.mode == MODE_POINTS) PointRuler(detail.points)
                                        }
                                        ScrollColumn(Modifier.weight(1f)) {
                                            SegmentPage(ui, model)
                                        }
                                    }
                                } else {
                                    val locked = ui.phase == LinkPhase.Connecting
                                    val stripActions = remember(model) {
                                        StripActions(
                                            onPresets = { model.loadPresets(); desk = Desk.Presets },
                                            onSchedules = { model.loadSchedules(); desk = Desk.Schedules },
                                            onBackup = { desk = Desk.Backup },
                                            onDisconnect = { model.disconnect() },
                                            onMode = { ioId, mode -> model.setIoMode(ioId, mode) },
                                            onBrightness = { ioId, value -> model.setIoBrightness(ioId, value) },
                                            onRefresh = { ioId, value -> model.setIoRefresh(ioId, value) },
                                            onOpenSegment = { segId, name, length -> model.openSegment(segId, name, length) },
                                            onOpenSlot = { ioId, segId ->
                                                boardIo = ioId
                                                slotSeg = segId
                                                val schedId = model.library.state.value.scheduleIdOf(ioId)
                                                model.loadPresets()
                                                model.loadSchedules()
                                                model.loadBindings()
                                                if (schedId == 0) {
                                                    pickIo = ioId
                                                } else {
                                                    model.openSlot(segId, schedId)
                                                    desk = Desk.SlotEdit
                                                }
                                            },
                                            onPreview = { ioId ->
                                                boardIo = ioId
                                                boardPreview = true
                                                model.loadPresets()
                                                model.loadSchedules()
                                                model.loadBindings()
                                                desk = Desk.ScheduleBoard
                                            },
                                            onPickSchedule = { _ -> model.loadSchedules() },
                                            onChooseSchedule = { ioId, schedId ->
                                                model.chooseIoSchedule(ioId, schedId)
                                            },
                                            onResetPhase = model::resetPhases,
                                            onOutput = model::setOutput,
                                        )
                                    }
                                    AppChrome(
                                        title = if (ui.page == Page.Scan) "LEDDrive" else peerName.ifBlank { "灯带" },
                                        onMenu = { desk = Desk.Settings },
                                        actions = {
                                            if (ui.page == Page.Strip) {
                                                val ready = !locked && ui.connected
                                                BarPlate("灯效", ready) {
                                                    model.loadPresets()
                                                    desk = Desk.Presets
                                                }
                                                BarPlate("调度", ready) {
                                                    model.loadSchedules()
                                                    desk = Desk.Schedules
                                                }
                                            }
                                        },
                                    ) {
                                        Box(Modifier.weight(1f).fillMaxWidth()) {
                                            when (ui.page) {
                                                Page.Scan -> Column(
                                                    Modifier
                                                        .fillMaxSize()
                                                        .verticalScroll(rememberScrollState()),
                                                ) { ScanPage(ui, model) }
                                                Page.Strip -> StripPage(
                                                    modifier = Modifier.fillMaxSize(),
                                                    ui = ui,
                                                    actions = stripActions,
                                                    library = library,
                                                    ioModes = ioModes,
                                                    clockReady = clockReady,
                                                    clockNote = clockNote,
                                                    epoch = deviceEpoch,
                                                    mark = epochMark,
                                                    synced = synced,
                                                    backdrop = backdrop,
                                                )
                                                Page.Segment -> Unit
                                            }
                                        }
                                    }
                                }
                            }
                        }
                            }
                        }
                    }
                }
                if (connectLog.isNotEmpty()) {
                    ConnectLogOverlay(
                        lines = connectLog,
                        backdrop = backdrop,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            // 顶栏 56dp + 进度条那一行，浮窗接在它下面
                            .padding(top = 104.dp, start = Space.x4, end = Space.x4),
                    )
                }
                if (ui.askSave) SavePresetDialog(model::acceptDraft, model::discardDraft)
                if (leaveAsk) {
                    AlertDialog(
                        onDismissRequest = { leaveAsk = false },
                        title = { Text("还有修改") },
                        text = { Text("可以保存，放弃这次修改，或留在这一页。") },
                        confirmButton = {
                            TextButton(onClick = {
                                val preset = desk == Desk.PresetEdit
                                val name = if (preset) library.editingPreset?.name.orEmpty() else library.editingSchedule?.name.orEmpty()
                                if (nameBytes(name) > 63) {
                                    if (preset) model.savePreset() else model.saveSchedule()
                                    leaveAsk = false
                                } else {
                                    if (preset) model.savePreset() else model.saveSchedule()
                                    desk = if (preset) Desk.Presets else Desk.Schedules
                                    leaveAsk = false
                                }
                            }) { Text("保存") }
                            TextButton(onClick = {
                                desk = if (desk == Desk.PresetEdit) Desk.Presets else Desk.Schedules
                                leaveAsk = false
                            }) { Text("放弃") }
                        },
                        dismissButton = { TextButton(onClick = { leaveAsk = false }) { Text("留下") } },
                    )
                }
                if (confirmUnbind) {
                    AlertDialog(
                        onDismissRequest = { confirmUnbind = false },
                        title = { Text("解除绑定") },
                        text = { Text("解除后，时间模式下这一段会关灯。") },
                        confirmButton = {
                            TextButton(onClick = {
                                model.unbindSegment(slotSeg)
                                confirmUnbind = false
                            }) { Text("解除") }
                        },
                        dismissButton = { TextButton(onClick = { confirmUnbind = false }) { Text("取消") } },
                    )
                }
                importTrim?.let { trim ->
                    ImportTrimDialog(
                        trim = trim,
                        onContinue = { presets, schedules -> model.continueImport(presets, schedules) },
                        onDismiss = model::dismissImport,
                    )
                }
                library.usage?.let { report ->
                    UsageDialog(
                        title = if (library.usageKind == "schedule") "删除调度" else "删除预设",
                        report = report,
                        onConfirm = model::confirmUsage,
                        onDismiss = model::dismissUsage,
                    )
                }
                pickIo?.let { ioId ->
                    AlertDialog(
                        onDismissRequest = { pickIo = null },
                        title = { Text("选择调度") },
                        text = {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                Text(
                                    "对得上的时间点保留原来的效果，对不上的时间点会关灯。",
                                    color = LocalPalette.current.muted,
                                )
                                if (library.schedules.isEmpty()) {
                                    Text("还没有调度预设", color = LocalPalette.current.muted)
                                }
                                library.schedules.forEach { sched ->
                                    TextButton(onClick = {
                                        model.chooseIoSchedule(ioId, sched.id)
                                        pickIo = null
                                    }) {
                                        Text(sched.name.ifEmpty { "调度 ${sched.id}" })
                                    }
                                }
                            }
                        },
                        confirmButton = {},
                        dismissButton = { TextButton(onClick = { pickIo = null }) { Text("取消") } },
                    )
                }
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .enabledDim(enabled)
            .tapTarget(enabled = enabled, description = label, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = LocalPalette.current.text, style = AppType.body)
    }
}

@Composable
private fun BarPlate(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = MinTouchTarget)
            .enabledDim(enabled)
            .acrylic(LocalAcrylic.current)
            .tapTarget(enabled = enabled, description = label, onClick = onClick)
            .padding(horizontal = Space.x3),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = LocalPalette.current.text, style = AppType.body, maxLines = 1)
    }
}

@Composable
private fun ImportTrimDialog(
    trim: ImportResult.Overflow,
    onContinue: (Set<String>, Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var presets by remember(trim) { mutableStateOf(trim.presets) }
    var schedules by remember(trim) { mutableStateOf(trim.schedules) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("这次备份放不下") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("只会从这一次备份里去掉条目。灯带上已经有的效果不会删除。")
                Text(
                    "灯效还能放 ${trim.presetRoom} 个，调度还能放 ${trim.scheduleRoom} 套。点一下就从这次导入里去掉。",
                    color = LocalPalette.current.muted,
                    modifier = Modifier.padding(top = 8.dp),
                )
                presets.forEach { name ->
                    TextButton(onClick = { presets = presets.filterNot { it == name } }) { Text("去掉灯效 $name") }
                }
                schedules.forEach { name ->
                    TextButton(onClick = { schedules = schedules.filterNot { it == name } }) { Text("去掉调度 $name") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onContinue(
                    trim.presets.filter { it !in presets }.toSet(),
                    trim.schedules.filter { it !in schedules }.toSet(),
                )
            }) { Text("继续导入") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ScanPage(
    ui: LightUi,
    model: LightViewModel,
) {
    val permissions = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val context = LocalContext.current
    var askedPermission by remember { mutableStateOf(false) }
    var granted by remember {
        mutableStateOf(permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED })
    }
    val enableBluetooth = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        if (model.bluetoothReady()) model.startScan()
    }
    val notifyPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }
    if (Build.VERSION.SDK_INT >= 33) {
        LaunchedEffect(Unit) {
            val post = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(context, post) != PackageManager.PERMISSION_GRANTED) {
                notifyPermission.launch(post)
            }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        granted = result.values.all { it }
        val activity = context as? Activity
        val permanent = askedPermission && activity != null && permissions.any { perm ->
            result[perm] != true && !ActivityCompat.shouldShowRequestPermissionRationale(activity, perm)
        }
        askedPermission = true
        if (!granted) {
            if (permanent) {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                )
            }
            return@rememberLauncherForActivityResult
        }
        if (model.bluetoothReady()) model.startScan() else enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
    }

    val palette = LocalPalette.current
    val toggleScan: () -> Unit = {
        when {
            !granted -> permissionLauncher.launch(permissions)
            !model.bluetoothReady() -> enableBluetooth.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            ui.scanning -> model.stopScan()
            else -> model.startScan()
        }
    }
    Note(ui.dropReason, palette.warn)
    if (ui.status.isNotEmpty() && ui.status != ui.dropReason) Hint(ui.status)
    val messageOk = ui.message.startsWith("已") || ui.message.startsWith("导入完成") || ui.message.startsWith("绑定已")
    Note(ui.message, if (messageOk) palette.ok else palette.warn)
    Button(
        onClick = toggleScan,
        modifier = Modifier.fillMaxWidth().padding(top = Space.x4),
        enabled = !ui.busy,
    ) {
        Text(if (ui.scanning) "停止扫描" else "开始扫描")
    }
    if (ui.busy) {
        Row(Modifier.padding(top = Space.x4), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            Text("正在读取", Modifier.padding(start = Space.x3), color = palette.muted)
        }
    }
    when {
        ui.devices.isNotEmpty() -> ui.devices.forEach { device ->
            ScanRow(device.name, device.address, !ui.busy) { model.connect(device.address) }
        }
        ui.scanning -> EmptyState("正在扫描。灯带通电后会出现在这里。")
        else -> EmptyState("还没有找到设备。确认灯带已通电，再点上面的开始扫描。")
    }
}

@Composable
private fun ScanRow(name: String, address: String, enabled: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = Space.x3)
            .surfaceCard(CardLevel.Raised, enabled = enabled)
            .tapTarget(enabled = enabled, description = "$name $address", onClick = onClick)
            .padding(horizontal = Space.x3, vertical = Space.x3),
    ) {
        Text(name, color = LocalPalette.current.text, style = AppType.strong)
        Text(address, color = LocalPalette.current.muted, style = AppType.caption)
    }
}

/**
 * 灯带页的回调集合。把 LightViewModel 挡在展示层之外，debug 画廊才能用假数据渲染同一屏。
 */
class StripActions(
    val onPresets: () -> Unit = {},
    val onSchedules: () -> Unit = {},
    val onBackup: () -> Unit = {},
    val onDisconnect: () -> Unit = {},
    val onMode: (Int, Int) -> Unit = { _, _ -> },
    val onBrightness: (Int, Int) -> Unit = { _, _ -> },
    val onRefresh: (Int, Int) -> Unit = { _, _ -> },
    val onOpenSegment: (Int, String, Int) -> Unit = { _, _, _ -> },
    val onOpenSlot: (Int, Int) -> Unit = { _, _ -> },
    val onPreview: (Int) -> Unit = {},
    val onPickSchedule: (Int) -> Unit = {},
    val onChooseSchedule: (Int, Int) -> Unit = { _, _ -> },
    val onResetPhase: () -> Unit = {},
    val onOutput: (Int, Boolean) -> Unit = { _, _ -> },
)

private fun readWifiName(context: android.content.Context): String? {
    val manager = context.applicationContext.getSystemService(WifiManager::class.java) ?: return null
    val raw = runCatching { manager.connectionInfo?.ssid }.getOrNull() ?: return null
    val name = raw.trim().trim('"')
    if (name.isBlank() || name == "<unknown ssid>" || name.equals("0x", ignoreCase = true)) return null
    return name
}

@Composable
internal fun StripPage(
    modifier: Modifier = Modifier,
    ui: LightUi,
    actions: StripActions,
    library: LibraryState,
    ioModes: Map<Int, Int>,
    clockReady: Boolean,
    clockNote: String,
    epoch: Long,
    mark: Long,
    synced: Boolean,
    backdrop: ImageBitmap? = null,
) {
    val context = LocalContext.current
    var wifiName by remember { mutableStateOf(readWifiName(context)) }
    val wifiPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        wifiName = readWifiName(context)
    }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED
        ) {
            wifiPermission.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }
    val locked = ui.phase == LinkPhase.Connecting
    val palette = LocalPalette.current
    val timing = ioModes.values.any { it == IO_MODE_TIME }
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(timing) {
        if (!timing) return@LaunchedEffect
        while (true) {
            delay(1_000)
            tick = SystemClock.elapsedRealtime()
        }
    }
    val epochSec = remember(epoch, mark, tick) { shownEpoch(epoch, mark) }
    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(bottom = Space.x6),
    ) {
        item(key = "head") {
            if (locked) {
                val step = ui.connectStep
                val fraction = ((step?.index ?: 0) + 1f) / (step?.total ?: 6).toFloat()
                Crossfade(
                    targetState = step?.label ?: "正在连接",
                    animationSpec = tween(180),
                    label = "connect-step",
                ) { label ->
                    Text(label, color = palette.text, style = AppType.body, modifier = Modifier.padding(top = Space.x2))
                }
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().padding(top = Space.x2),
                )
            }
            if (!clockReady && clockNote.isNotEmpty() && !locked) Hint(clockNote)
        }
        if (ui.ios.isEmpty()) {
            item(key = "empty") {
                if (!locked) {
                    EmptyState(
                        "没有读到灯段。断开后重连一次。",
                        actionLabel = "断开重连",
                        onAction = actions.onDisconnect,
                    )
                }
            }
        } else {
            items(ui.ios, key = { it.id }) { io ->
                val mode = ioModes[io.id] ?: 0
                val title = if (io.id == ui.ios.firstOrNull()?.id && !wifiName.isNullOrBlank()) wifiName!! else io.name
                IoPlate {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(title, color = palette.text, style = AppType.title)
                            Text(
                                "IO ${io.id} · ${io.ledCount} 颗",
                                color = palette.muted,
                                style = AppType.caption,
                                modifier = Modifier.padding(top = Space.x1),
                            )
                        }
                        Switch(
                            checked = ui.outputOn[io.id] != false,
                            onCheckedChange = { actions.onOutput(io.id, it) },
                            enabled = !locked,
                        )
                    }
                    if (clockReady) {
                        val schedule = library.schedules.firstOrNull { it.id == library.scheduleIdOf(io.id) }
                        IoModeBlock(
                            mode = mode,
                            nextEvent = nextSlotSec(schedule?.atSec.orEmpty(), epochSec).toLong(),
                            onMode = { actions.onMode(io.id, it) },
                            onPreview = if (mode == IO_MODE_TIME) ({ actions.onPreview(io.id) }) else null,
                            scheduleName = library.scheduleLabel(io.id),
                            onPickSchedule = if (mode == IO_MODE_TIME) ({ actions.onPickSchedule(io.id) }) else null,
                            schedules = library.schedules,
                            onChooseSchedule = { actions.onChooseSchedule(io.id, it) },
                            onResetPhase = actions.onResetPhase,
                            backdrop = backdrop,
                        )
                    }
                    AcrylicGroup {
                        ReleaseSlider(
                            label = "亮度",
                            value = ui.holdBrightness[io.id] ?: io.brightness,
                            range = 0f..255f,
                            readout = { "$it" },
                            onFinish = { actions.onBrightness(io.id, it) },
                        )
                    }
                    AcrylicGroup {
                        /*
                         * 刷新间隔只保留 10/20/30/40/50 五档，滑条走 1..5 的整数刻度。
                         * 1 ms 级的差别肉眼看不出来，反而更容易把 RMT 补数顶到极限。
                         */
                        val step = (((ui.holdRefresh[io.id] ?: io.refreshMs) / 10).coerceIn(1, 5))
                        ReleaseSlider(
                            label = "刷新间隔",
                            value = step,
                            range = 1f..5f,
                            readout = { "${it * 10} ms" },
                            onFinish = { actions.onRefresh(io.id, it * 10) },
                        )
                    }
                    io.segments.forEach { seg ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = Space.x2)
                                .surfaceCard(CardLevel.Overlay)
                                .tapTarget(
                                    enabled = !ui.busy && ui.phase != LinkPhase.Connecting,
                                    description = "${seg.name} ${seg.length} 颗",
                                ) {
                                    if (mode == IO_MODE_TIME) actions.onOpenSlot(io.id, seg.id)
                                    else actions.onOpenSegment(seg.id, seg.name, seg.length)
                                }
                                .padding(horizontal = Space.x3, vertical = Space.x3),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
                                seg.tint?.let { tint ->
                                    Box(
                                        Modifier
                                            .size(14.dp)
                                            .clip(CircleShape)
                                            .background(Color(tint)),
                                    )
                                }
                                Text(seg.name, color = palette.text, style = AppType.strong)
                            }
                            Text(
                                "${seg.length} 颗 · ${segmentStatus(mode, io.id, seg, library, epochSec)}",
                                color = palette.muted,
                                style = AppType.caption,
                            )
                        }
                    }
                }
            }
        }
        item(key = "tail") {
            if (ui.busy && ui.busyLabel.isNotEmpty()) Hint(ui.busyLabel)
            StatusLine(ui.message)
        }
    }
}
@Composable
private fun ScrollColumn(
    modifier: Modifier = Modifier,
    maxHeight: Dp = Dp.Unspecified,
    isolate: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val state = rememberScrollState()
    val palette = LocalPalette.current
    val density = LocalDensity.current
    var viewport by remember { mutableIntStateOf(0) }
    val eat = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset = available
        }
    }
    val cap = if (maxHeight != Dp.Unspecified) Modifier.heightIn(max = maxHeight) else Modifier
    Box(modifier.then(cap)) {
        Column(
            Modifier
                .fillMaxWidth()
                .then(cap)
                .then(if (isolate) Modifier.nestedScroll(eat) else Modifier)
                .onSizeChanged { viewport = it.height }
                .verticalScroll(state)
                .padding(end = Space.x2),
            content = content,
        )
        ScrollThumb(state, viewport, palette.muted, density)
    }
}

@Composable
private fun BoxScope.ScrollThumb(state: ScrollState, viewport: Int, color: Color, density: androidx.compose.ui.unit.Density) {
    val max = state.maxValue
    if (max <= 0 || viewport <= 0) return
    val thumb = (viewport.toFloat() * viewport / (viewport + max)).coerceIn(24f, viewport.toFloat())
    val travel = (viewport - thumb).coerceAtLeast(0f)
    val y = state.value / max.toFloat() * travel
    Box(
        Modifier
            .align(Alignment.TopEnd)
            .offset(y = with(density) { y.toDp() })
            .width(3.dp)
            .height(with(density) { thumb.toDp() })
            .background(color.copy(alpha = 0.72f), RoundedCornerShape(2.dp)),
    )
}

@Composable
private fun SegmentPage(ui: LightUi, model: LightViewModel) {
    val detail = ui.editor
    val library by model.library.state.collectAsStateWithLifecycle()
    var picked by remember(ui.segId) { mutableStateOf<Int?>(null) }
    var touched by remember(ui.segId) { mutableStateOf(false) }
    var naming by remember(ui.segId) { mutableStateOf(false) }
    var presetName by remember(ui.segId) { mutableStateOf("") }
    LaunchedEffect(ui.segId, detail, library.presets) {
        if (touched || detail == null || library.presets.isEmpty()) return@LaunchedEffect
        val bytes = encodeEffect(detail)
        picked = library.presets.firstOrNull { encodeEffect(it.detail).contentEquals(bytes) }?.id
        touched = true
    }
    Hint("${ui.segLength} 颗。选一个预设直接用，或在自定义里自己调。")
    if (detail == null) {
        if (ui.busy) CircularProgressIndicator(Modifier.padding(top = 16.dp))
        StatusLine(ui.message)
        return
    }
    AcrylicGroup {
        Text("选择预设", color = LocalPalette.current.text, style = AppType.strong)
        if (library.presets.isEmpty()) {
            Hint("还没有预设。可以先用下面的自定义。")
        }
        ScrollColumn(
            Modifier.fillMaxWidth().padding(top = Space.x1),
            maxHeight = 220.dp,
            isolate = true,
        ) {
            library.presets.forEach { preset ->
                val title = preset.name.ifEmpty { "预设 ${preset.id}" }
                val selected = picked == preset.id
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = Space.x2)
                        .surfaceCard(if (selected) CardLevel.Raised else CardLevel.Overlay)
                        .tapTarget(enabled = !ui.busy, description = title) {
                            touched = true
                            picked = preset.id
                            model.applyPresetToSegment(preset)
                        }
                        .padding(horizontal = Space.x3, vertical = Space.x3),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(title, color = LocalPalette.current.text, style = AppType.strong)
                    Text(modeLabel(preset.detail.mode), color = LocalPalette.current.muted, style = AppType.caption)
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = Space.x2)
                    .surfaceCard(if (picked == null) CardLevel.Raised else CardLevel.Overlay)
                    .tapTarget(enabled = !ui.busy, description = "自定义") {
                        touched = true
                        picked = null
                    }
                    .padding(horizontal = Space.x3, vertical = Space.x3),
            ) {
                Text("自定义", color = LocalPalette.current.text, style = AppType.strong)
            }
        }
    }
    if (picked == null) {
        AcrylicGroup {
            ReleaseSlider(
                label = "亮度",
                value = detail.brightness,
                range = 0f..255f,
                readout = { "$it" },
                onFinish = model::setSegBrightness,
            )
            Hint("松手后立即生效。")
        }
        AcrylicGroup {
            Text("模式", color = LocalPalette.current.text, fontWeight = FontWeight.Medium)
            ModeChips(detail.mode, enabled = !ui.busy) { model.setMode(it) }
        }
        when (detail.mode) {
            0 -> Hint("输出关闭。")
            1 -> WaveEditor(detail.wave, model::updateWave)
            2 -> AcrylicGroup { RgbEditor(detail.solid.color, model::updateSolid) }
            3 -> BreathEditor(detail.breath, model::updateBreath)
            4 -> ChaseEditor(detail.chase, model::updateChase)
            5 -> PointsEditor(detail.points, model::updatePoints, model::updatePoint)
            6 -> FlashEditor(detail.flash, model::updateFlash)
        }
        if (detail.mode != 0) {
            Button(
                onClick = model::commitDetail,
                modifier = Modifier.fillMaxWidth().padding(top = Space.x4),
                enabled = !ui.busy,
            ) { Text("应用到灯带") }
            Hint("先在预览里变化，点应用到灯带后才发送。")
        }
        Button(
            onClick = { presetName = ""; naming = true },
            enabled = !ui.busy,
            modifier = Modifier.fillMaxWidth().padding(top = Space.x2).heightIn(min = MinTouchTarget),
        ) { Text("保存到预设") }
    } else {
        val using = library.presets.firstOrNull { it.id == picked }
        Hint("正在使用${using?.name?.takeIf { it.isNotEmpty() }?.let { "「$it」" } ?: "这个预设"}。要自己调参数，点自定义。")
    }
    StatusLine(ui.message)
    if (naming) {
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("保存到预设") },
            text = {
                Column {
                    BasicTextField(
                        value = presetName,
                        onValueChange = { presetName = it },
                        singleLine = true,
                        textStyle = AppType.strong.copy(color = LocalPalette.current.text),
                        cursorBrush = SolidColor(LocalPalette.current.text),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "不保存的话，这次调整会直接写在这一段上。下次再改颜色，原来的效果就找不回来。",
                        color = LocalPalette.current.muted,
                        style = AppType.caption,
                        modifier = Modifier.padding(top = Space.x3),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    naming = false
                    model.saveSegmentPreset(presetName)
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = {
                    naming = false
                    model.commitDetail()
                }) { Text("不保存") }
            },
        )
    }
}

private fun segmentStatus(
    mode: Int,
    ioId: Int,
    seg: SegmentHead,
    library: LibraryState,
    epochSec: Long,
): String {
    if (mode != IO_MODE_TIME) return seg.mode?.let(::modeLabel) ?: "读取中"
    val schedId = library.scheduleIdOf(ioId)
    val schedule = library.schedules.firstOrNull { it.id == schedId } ?: return "未选调度"
    if (schedule.atSec.isEmpty()) return "未选调度"
    val binding = library.bindings[seg.id] ?: return "同步中"
    val slot = activeSlotIndex(schedule.atSec, epochSec)
    val presetId = if (binding.schedId == schedId && slot >= 0) {
        binding.presetIds.getOrElse(slot) { PRESET_ID_NONE }
    } else {
        PRESET_ID_NONE
    }
    val preset = library.presets.firstOrNull { it.id == presetId }
    return when {
        preset != null -> preset.name.ifEmpty { modeLabel(preset.detail.mode) }
        presetId == PRESET_ID_NONE -> "关灯"
        else -> "预设 $presetId"
    }
}

@Composable
internal fun WaveEditor(cfg: WaveCfg, onChange: (WaveCfg) -> Unit) {
    AcrylicGroup {
        PreciseSlider("速度", cfg.speedPct, 200, SPEED_MAX, "%") { onChange(cfg.copy(speedPct = it)) }
        Hint("100% 为每秒移动一个段长。")
    }
    AcrylicGroup {
        DirectionChips(cfg.dir) { onChange(cfg.copy(dir = it)) }
    }
}

@Composable
internal fun BreathEditor(cfg: BreathCfg, onChange: (BreathCfg) -> Unit) {
    AcrylicGroup {
        RgbEditor(cfg.color) { onChange(cfg.copy(color = it)) }
    }
    AcrylicGroup {
        PreciseSlider("周期", cfg.periodMs, 2000, 60000, "ms") { onChange(cfg.copy(periodMs = it)) }
        Hint("一个周期为亮—灭—亮。0 为常亮。")
    }
}

@Composable
internal fun ChaseEditor(cfg: ChaseCfg, onChange: (ChaseCfg) -> Unit) {
    AcrylicGroup {
        PreciseSlider("速度", cfg.speedPct, 200, SPEED_MAX, "%") { onChange(cfg.copy(speedPct = it)) }
        Hint("100% 为每秒移动一个段长。")
    }
    AcrylicGroup {
        DirectionChips(cfg.dir) { onChange(cfg.copy(dir = it)) }
    }
    AcrylicGroup {
        Stepper("颜色数", cfg.colors.size, 1, 8) { count ->
            onChange(cfg.copy(colors = resizeColors(cfg.colors, count)))
        }
        Hint("沿段长均分的颜色个数。")
        ColorList(cfg.colors) { colors -> onChange(cfg.copy(colors = colors)) }
    }
}

@Composable
internal fun PointsEditor(
    cfg: PointsCfg,
    onChange: (PointsCfg) -> Unit,
    onPoint: (Int, PointCfg) -> Unit,
) {
    AcrylicGroup {
        Text("端点", color = LocalPalette.current.text, fontWeight = FontWeight.Medium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            FilterChip(selected = !cfg.wrap, onClick = { onChange(cfg.copy(wrap = false)) }, label = { Text("保持") })
            FilterChip(selected = cfg.wrap, onClick = { onChange(cfg.copy(wrap = true)) }, label = { Text("循环") })
        }
    }
    var selected by remember(cfg.points.size) { mutableIntStateOf(0) }
    val index = selected.coerceIn(0, cfg.points.lastIndex.coerceAtLeast(0))
    AcrylicGroup {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            cfg.points.forEachIndexed { pointIndex, point ->
                FilterChip(
                    selected = pointIndex == index,
                    onClick = { selected = pointIndex },
                    label = { Text("点${pointIndex + 1} ${point.positionPct}%") },
                )
            }
        }
        Row {
            TextButton(onClick = {
                if (cfg.points.size >= 8) return@TextButton
                onChange(cfg.copy(points = cfg.points + PointCfg(50, 2000, listOf(Rgb(255, 255, 255)))))
                selected = cfg.points.size
            }) { Text("添加") }
            TextButton(onClick = {
                if (cfg.points.size <= 1) return@TextButton
                onChange(cfg.copy(points = cfg.points.filterIndexed { i, _ -> i != index }))
                selected = (index - 1).coerceAtLeast(0)
            }) { Text("删除") }
        }
    }
    val point = cfg.points.getOrNull(index) ?: return
    AcrylicGroup {
        DraftSlider("位置", point.positionPct, 0f..100f, { "$it%" }) { onPoint(index, point.copy(positionPct = it)) }
    }
    AcrylicGroup {
        PreciseSlider("周期", point.periodMs, 2000, 60000, "ms") { onPoint(index, point.copy(periodMs = it)) }
        Hint("该点颜色循环一圈的时间。")
    }
    AcrylicGroup {
        Stepper("颜色数", point.colors.size, 1, 8) { count ->
            onPoint(index, point.copy(colors = resizeColors(point.colors, count)))
        }
        Hint("当前点按顺序循环的颜色个数。")
        ColorList(point.colors) { colors -> onPoint(index, point.copy(colors = colors)) }
    }
}

@Composable
internal fun FlashEditor(cfg: FlashCfg, onChange: (FlashCfg) -> Unit) {
    AcrylicGroup {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = cfg.mode == FLASH_BLINK,
                onClick = { onChange(cfg.copy(mode = FLASH_BLINK)) },
                label = { Text("闪烁") },
            )
            FilterChip(
                selected = cfg.mode == FLASH_SWITCH,
                onClick = { onChange(cfg.copy(mode = FLASH_SWITCH)) },
                label = { Text("换色") },
            )
        }
        if (cfg.mode != FLASH_BLINK) {
            Hint("常亮。每帧随机取色，不使用周期。")
        }
    }
    if (cfg.mode == FLASH_BLINK) {
        AcrylicGroup {
            PreciseSlider("周期", cfg.periodMs, 2000, 60000, "ms") { onChange(cfg.copy(periodMs = it)) }
            Hint("前半周期亮，后半周期灭。亮时每帧随机取色。")
        }
    }
}

@Composable
internal fun ModeChips(selected: Int, enabled: Boolean, onSelect: (Int) -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
    ) {
        MODE_LABELS.forEachIndexed { index, label ->
            FilterChip(
                selected = selected == index,
                onClick = { if (enabled) onSelect(index) },
                label = { Text(label) },
                enabled = enabled,
            )
        }
    }
}

@Composable
internal fun DirectionChips(dir: Int, onSelect: (Int) -> Unit) {
    Text("方向", color = LocalPalette.current.text, fontWeight = FontWeight.Medium)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        FilterChip(selected = dir == DIR_RIGHT, onClick = { onSelect(DIR_RIGHT) }, label = { Text("正向") })
        FilterChip(selected = dir == DIR_LEFT, onClick = { onSelect(DIR_LEFT) }, label = { Text("反向") })
    }
    Hint("正向是灯珠序号变大的方向。")
}

@Composable
private fun ColorList(colors: List<Rgb>, onChange: (List<Rgb>) -> Unit) {
    var selected by remember(colors.size) { mutableIntStateOf(0) }
    val index = selected.coerceIn(0, colors.lastIndex.coerceAtLeast(0))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        colors.forEachIndexed { colorIndex, color ->
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(color.r, color.g, color.b))
                    .border(
                        width = if (colorIndex == index) 2.dp else 1.dp,
                        color = if (colorIndex == index) LocalPalette.current.text else LocalPalette.current.muted,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .clickable { selected = colorIndex },
            )
        }
    }
    val color = colors.getOrNull(index) ?: return
    RgbEditor(color) { updated ->
        onChange(colors.mapIndexed { i, old -> if (i == index) updated else old })
    }
}

@Composable
internal fun RgbEditor(color: Rgb, onChange: (Rgb) -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(color.r, color.g, color.b))
            .semantics { contentDescription = "当前颜色" },
    )
    Row(
        Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        COLOR_SWATCHES.forEach { (name, swatch) ->
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(swatch.r, swatch.g, swatch.b))
                    .clickable { onChange(swatch) }
                    .semantics { contentDescription = name },
            )
        }
    }
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(color.r, color.g, color.b, hsv)
    Text("色相", color = palette.text, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 12.dp))
    Slider(
        value = hsv[0],
        onValueChange = { hue ->
            val packed = android.graphics.Color.HSVToColor(
                floatArrayOf(hue, hsv[1].coerceAtLeast(0.85f), hsv[2].coerceAtLeast(0.85f)),
            )
            onChange(
                Rgb(
                    android.graphics.Color.red(packed),
                    android.graphics.Color.green(packed),
                    android.graphics.Color.blue(packed),
                ),
            )
        },
        valueRange = 0f..360f,
        modifier = Modifier.fillMaxWidth(),
    )
    Text("精确", color = palette.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    DraftSlider("红", color.r, 0f..255f, { "$it" }) { onChange(color.copy(r = it)) }
    DraftSlider("绿", color.g, 0f..255f, { "$it" }) { onChange(color.copy(g = it)) }
    DraftSlider("蓝", color.b, 0f..255f, { "$it" }) { onChange(color.copy(b = it)) }
}

private val COLOR_SWATCHES = listOf(
    "白" to Rgb(255, 255, 255),
    "红" to Rgb(255, 0, 0),
    "绿" to Rgb(0, 255, 0),
    "蓝" to Rgb(0, 0, 255),
    "黄" to Rgb(255, 220, 0),
    "青" to Rgb(0, 255, 255),
    "紫" to Rgb(180, 0, 255),
)

@Composable
internal fun ReleaseSlider(
    label: String,
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    readout: (Int) -> String,
    step: Int = 0,
    onFinish: (Int) -> Unit,
) {
    val palette = LocalPalette.current
    val haptic = LocalHapticFeedback.current
    val lo = range.start.toInt()
    val hi = range.endInclusive.toInt()
    var draft by remember { mutableStateOf<Int?>(null) }
    var dragging by remember { mutableStateOf(false) }
    val shown = draft ?: value
    /*
     * 松手才下发。拖动过程中只改本地 draft，滑块和数字照样每帧跟手；
     * 加减按钮是连续量，按住连步时同样只改 draft，停手 320ms 后统一下发一次，
     * 免得长按把 BLE 写队列刷爆。
     */
    LaunchedEffect(draft, dragging) {
        val pending = draft ?: return@LaunchedEffect
        if (dragging) return@LaunchedEffect
        delay(320)
        if (draft == pending) {
            draft = null
            onFinish(pending)
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(top = Space.x3),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = palette.text, style = AppType.strong)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.x2),
        ) {
            if (step > 0) {
                StepButton("−", enabled = shown > lo, description = "减少$label") {
                    draft = ((draft ?: value) - step).coerceIn(lo, hi)
                }
            }
            Text(readout(shown), color = palette.muted, style = AppType.body)
            if (step > 0) {
                StepButton("+", enabled = shown < hi, description = "增加$label") {
                    draft = ((draft ?: value) + step).coerceIn(lo, hi)
                }
            }
        }
    }
    Slider(
        value = shown.toFloat().coerceIn(range),
        onValueChange = {
            dragging = true
            draft = it.roundToInt().coerceIn(lo, hi)
        },
        onValueChangeFinished = {
            val committed = draft ?: return@Slider
            draft = null
            dragging = false
            onFinish(committed)
            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        },
        valueRange = range,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun DraftSlider(
    label: String,
    value: Int,
    range: ClosedFloatingPointRange<Float>,
    readout: (Int) -> String,
    onChange: (Int) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = Space.x3),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = LocalPalette.current.text, style = AppType.strong)
        Text(readout(value), color = LocalPalette.current.muted, style = AppType.body)
    }
    Slider(
        value = value.toFloat().coerceIn(range),
        onValueChange = { onChange(it.roundToInt()) },
        valueRange = range,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun Stepper(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = Space.x3),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$label  $value", color = LocalPalette.current.text, style = AppType.strong)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.x2)) {
            StepButton("−", enabled = value > min, description = "减少$label") {
                onChange((value - 1).coerceAtLeast(min))
            }
            StepButton("+", enabled = value < max, description = "增加$label") {
                onChange((value + 1).coerceAtMost(max))
            }
        }
    }
}

@Composable
internal fun Hint(text: String) {
    Text(text, color = LocalPalette.current.muted, style = AppType.body, modifier = Modifier.padding(top = Space.x2))
}

/**
 * 调度框：先铺屏幕原位的壁纸，再按从外到内铺底板和它高一级的卡片。
 * 壁纸透出来，这两层亚克力都还在。
 */
@Composable
internal fun Modifier.piercedPlate(
    backdrop: ImageBitmap?,
    screenOrigin: Offset,
    screenSize: IntSize,
): Modifier {
    val palette = LocalPalette.current
    val base = acrylicAlpha(LocalIoAcrylic.current)
    val card = acrylicAlpha(LocalAcrylic.current)
    val shape = RoundedCornerShape(Radius.medium)
    return this
        .clip(shape)
        .drawBehind {
            val bitmap = backdrop
            if (bitmap != null && screenSize.width > 0 && screenSize.height > 0) {
                val scale = maxOf(
                    screenSize.width.toFloat() / bitmap.width,
                    screenSize.height.toFloat() / bitmap.height,
                )
                val dstWidth = (bitmap.width * scale).roundToInt()
                val dstHeight = (bitmap.height * scale).roundToInt()
                val left = (screenSize.width - dstWidth) / 2f - screenOrigin.x
                val top = (screenSize.height - dstHeight) / 2f - screenOrigin.y
                drawImage(
                    image = bitmap,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(bitmap.width, bitmap.height),
                    dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                    dstSize = IntSize(dstWidth, dstHeight),
                    filterQuality = FilterQuality.Low,
                )
            } else {
                drawRect(palette.page)
            }
            drawRect(palette.surface.copy(alpha = base))
            drawRect(palette.surface.copy(alpha = card))
        }
        .border(1.dp, palette.hairline, shape)
}

/**
 * 浮层背板：画屏幕原位的壁纸，再盖一层半透明底板。字只叠在壁纸上。
 */
@Composable
internal fun Modifier.wallpaperPlate(backdrop: ImageBitmap?): Modifier {
    val palette = LocalPalette.current
    val window = LocalWindowInfo.current.containerSize
    val shape = RoundedCornerShape(Radius.large)
    var origin by remember { mutableStateOf(Offset.Zero) }
    return this
        .clip(shape)
        .onGloballyPositioned { origin = it.positionInWindow() }
        .drawBehind {
            val bitmap = backdrop
            if (bitmap != null && window.width > 0 && window.height > 0) {
                val scale = maxOf(
                    window.width.toFloat() / bitmap.width,
                    window.height.toFloat() / bitmap.height,
                )
                val dstWidth = (bitmap.width * scale).roundToInt()
                val dstHeight = (bitmap.height * scale).roundToInt()
                val left = (window.width - dstWidth) / 2f - origin.x
                val top = (window.height - dstHeight) / 2f - origin.y
                drawImage(
                    image = bitmap,
                    srcOffset = IntOffset.Zero,
                    srcSize = IntSize(bitmap.width, bitmap.height),
                    dstOffset = IntOffset(left.roundToInt(), top.roundToInt()),
                    dstSize = IntSize(dstWidth, dstHeight),
                    filterQuality = FilterQuality.Low,
                )
            }
            drawRect(palette.surface.copy(alpha = if (bitmap == null) 0.96f else 0.55f))
        }
        .border(1.dp, palette.hairline, shape)
}

@Composable
internal fun ChevronDown(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(14.dp)) {
        val stroke = 1.6.dp.toPx()
        drawLine(
            color,
            Offset(size.width * 0.18f, size.height * 0.34f),
            Offset(size.width * 0.5f, size.height * 0.68f),
            stroke,
            StrokeCap.Round,
        )
        drawLine(
            color,
            Offset(size.width * 0.5f, size.height * 0.68f),
            Offset(size.width * 0.82f, size.height * 0.34f),
            stroke,
            StrokeCap.Round,
        )
    }
}

/**
 * 穿透下拉。不超过 3 行时高度跟行数走；超过 3 行才展开。
 * 展开后底边超出屏幕时，把这一块滚到屏幕最下面。
 */
@Composable
internal fun PenetratingMenu(
    count: Int,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val window = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val screen = with(density) { window.height.toDp() }
    val rows = count.coerceAtLeast(1)
    val full = MinTouchTarget * rows
    val limit = (screen - Space.x8).coerceAtLeast(MinTouchTarget * 3)
    val requester = remember { BringIntoViewRequester() }
    val plate = LocalPlateStep.current.inward()
    LaunchedEffect(rows) { requester.bringIntoView() }
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = Space.x1)
            .then(
                if (rows <= 3) {
                    Modifier.height(full)
                } else {
                    Modifier.heightIn(max = minOf(full, limit))
                },
            )
            .bringIntoViewRequester(requester)
            .nextPlate(plate)
            .verticalScroll(rememberScrollState()),
        content = content,
    )
}

/** 展开层不重画壁纸。盖住的最深一层再往里一级，用那一级的颜色和厚度。 */
@Composable
internal fun Modifier.nextPlate(step: PlateStep): Modifier {
    val palette = LocalPalette.current
    val strength = when (step) {
        PlateStep.None, PlateStep.Base -> LocalIoAcrylic.current
        PlateStep.Raised -> LocalAcrylic.current
        PlateStep.Overlay -> LocalFilm.current
    }
    val fill = if (step == PlateStep.Overlay) palette.surfaceHigh else palette.surface
    return background(fill.copy(alpha = acrylicAlpha(strength)))
}

@Composable
internal fun MenuRowButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = MinTouchTarget)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = if (selected) palette.accent else palette.text,
            style = AppType.body,
            maxLines = 1,
        )
    }
}

/**
 * 连接日志面板：进度条下面逐条打出手机解析出来的结果。
 * 行内容全部由 App 侧解析固件返回的数据得到，不依赖固件发任何日志文本。
 */
@Composable
internal fun ConnectLogOverlay(
    lines: List<BleLogLine>,
    backdrop: ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val scroll = rememberScrollState()
    LaunchedEffect(lines.size, scroll.maxValue) {
        if (lines.isNotEmpty()) scroll.animateScrollTo(scroll.maxValue)
    }
    Box(
        modifier
            .fillMaxWidth()
            .wallpaperPlate(backdrop)
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        Column(Modifier.fillMaxWidth().padding(Space.x3)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "解析日志",
                    color = palette.muted,
                    style = AppType.caption,
                    modifier = Modifier.weight(1f),
                )
                Text("${lines.size} 条", color = palette.muted, style = AppType.caption)
            }
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 240.dp)
                    .verticalScroll(scroll),
            ) {
                lines.forEach { line -> LogLineRow(line) }
            }
        }
    }
}
@Composable
private fun LogLineRow(line: BleLogLine) {
    val palette = LocalPalette.current
    val visible = remember { MutableTransitionState(false) }
    LaunchedEffect(Unit) { visible.targetState = true }
    AnimatedVisibility(visibleState = visible, enter = fadeIn(tween(140))) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 2.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                line.time,
                color = palette.muted,
                style = AppType.caption,
                modifier = Modifier.width(62.dp),
            )
            Text(
                buildString {
                    append(line.step)
                    if (line.detail.isNotEmpty()) append(" · ").append(line.detail)
                },
                color = if (line.ok) palette.text else palette.warn,
                style = AppType.caption,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 状态提示的出现/消失：展开 + 淡入，不要硬顶下面内容。 */
@Composable
private fun StatusEnter(visible: Boolean, content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(tween(160)) + fadeIn(tween(120)),
        exit = shrinkVertically(tween(140)) + fadeOut(tween(90)),
    ) {
        content()
    }
}

@Composable
private fun Note(text: String, color: Color) {
    StatusEnter(visible = text.isNotEmpty()) {
        Text(text, color = color, style = AppType.body, modifier = Modifier.padding(top = Space.x2))
    }
}

@Composable
private fun StatusLine(text: String) {
    val palette = LocalPalette.current
    val ok = text.startsWith("已") || text.startsWith("导入完成") || text.startsWith("绑定已")
    StatusEnter(visible = text.isNotEmpty()) {
        Text(
            text,
            color = if (ok) palette.ok else palette.warn,
            style = AppType.body,
            modifier = Modifier.padding(top = Space.x3, bottom = Space.x6),
        )
    }
}

@Composable
private fun LibraryPage(
    title: String,
    onBack: () -> Unit,
    message: String = "",
    content: @Composable ColumnScope.() -> Unit,
) {
    AppChrome(title, onBack = onBack) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            content()
            StatusLine(message)
        }
    }
}

@Composable
private fun PresetEditPage(model: LightViewModel, record: PresetRecord?) {
    val preset = record ?: return
    val rom = preset.id in PRESET_ROM_FIRST..PRESET_ROM_LAST
    val palette = LocalPalette.current
    AcrylicGroup {
        Text("名称", color = palette.muted, fontSize = 12.sp)
        BasicTextField(
            value = preset.name,
            onValueChange = { value -> if (!rom) model.editPreset { it.copy(name = value) } },
            singleLine = true,
            readOnly = rom,
            textStyle = TextStyle(color = palette.text, fontSize = 16.sp),
            cursorBrush = SolidColor(palette.text),
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
        val used = nameBytes(preset.name)
        Text(
            "已用 $used/63 字节",
            color = if (used > 63) palette.warn else palette.muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    AcrylicGroup {
        Text("模式", color = palette.text, fontWeight = FontWeight.Medium)
        ModeChips(preset.detail.mode, enabled = !rom) { mode ->
            model.editPreset { it.copy(detail = it.detail.copy(mode = mode)) }
        }
    }
    when (preset.detail.mode) {
        1 -> WaveEditor(preset.detail.wave) { cfg -> model.editPreset { it.copy(detail = it.detail.copy(wave = cfg)) } }
        2 -> AcrylicGroup { RgbEditor(preset.detail.solid.color) { color -> model.editPreset { it.copy(detail = it.detail.copy(solid = StaticCfg(color))) } } }
        3 -> BreathEditor(preset.detail.breath) { cfg -> model.editPreset { it.copy(detail = it.detail.copy(breath = cfg)) } }
        4 -> ChaseEditor(preset.detail.chase) { cfg -> model.editPreset { it.copy(detail = it.detail.copy(chase = cfg)) } }
        5 -> PointsEditor(preset.detail.points, { cfg -> model.editPreset { it.copy(detail = it.detail.copy(points = cfg)) } }) { index, point ->
            model.editPreset { recordNow ->
                val points = recordNow.detail.points.points.toMutableList()
                if (index in points.indices) points[index] = point
                recordNow.copy(detail = recordNow.detail.copy(points = recordNow.detail.points.copy(points = points)))
            }
        }
        6 -> FlashEditor(preset.detail.flash) { cfg -> model.editPreset { it.copy(detail = it.detail.copy(flash = cfg)) } }
        else -> Hint("输出关闭。")
    }
    if (!rom) {
        Button(
            onClick = model::savePreset,
            enabled = nameBytes(preset.name) <= 63,
            modifier = Modifier.fillMaxWidth().padding(top = Space.x4).heightIn(min = 48.dp),
        ) { Text("写入") }
        if (preset.id != 0) {
            TextButton(onClick = { model.requestDeletePreset(preset.id) }, modifier = Modifier.fillMaxWidth()) { Text("删除") }
        }
    }
}

@Composable
private fun BackupActions(note: String, onExport: () -> Unit, onImport: (Uri) -> Unit) {
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImport(uri)
    }
    BackupBlock(note, onExport = onExport, onImport = { import.launch(arrayOf("application/json", "text/*", "*/*")) })
}

@Composable
private fun SchedulePreview(
    io: IoNode?,
    library: LibraryState,
    scheduleId: Int,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    if (io == null) {
        Hint("没有这个 IO。")
        return
    }
    val schedule = library.schedules.firstOrNull { it.id == scheduleId }
    Column(modifier.verticalScroll(rememberScrollState())) {
        if (schedule == null) {
            Hint("先给这一路选一套调度。")
            return@Column
        }
        schedule.atSec.forEach { sec ->
            val slot = schedule.atSec.indexOf(sec)
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .acrylic(LocalIoAcrylic.current)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            ) {
                Text(formatMinute(sec), color = palette.text, fontWeight = FontWeight.Medium)
                io.segments.forEach { seg ->
                    val binding = library.bindings[seg.id]
                    val presetId = if (binding != null && binding.schedId == schedule.id) {
                        binding.presetIds.getOrElse(slot) { PRESET_ID_NONE }
                    } else {
                        PRESET_ID_NONE
                    }
                    val preset = library.presets.firstOrNull { it.id == presetId }
                    val presetName = when {
                        preset != null -> preset.name.ifEmpty { "预设 ${preset.id}" }
                        presetId == PRESET_ID_NONE -> "关灯"
                        else -> "预设 $presetId"
                    }
                    val caption = if (preset != null) {
                        val modeName = MODE_LABELS.getOrElse(preset.detail.mode) { "关灯" }.let { if (it == "关闭") "关灯" else it }
                        "$modeName · $presetName"
                    } else {
                        presetName
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .acrylic(LocalFilm.current)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(seg.name, color = palette.text, fontWeight = FontWeight.SemiBold)
                        if (preset != null) {
                            EffectPreview(preset.detail, seg.length.coerceAtLeast(1), barHeight = 18.dp)
                        } else {
                            Box(
                                Modifier
                                    .padding(top = 6.dp)
                                    .fillMaxWidth()
                                    .height(18.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(Color.Black.copy(alpha = 0.35f)),
                            )
                        }
                        Text(
                            caption,
                            color = palette.muted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun shownEpoch(epoch: Long, mark: Long): Long {
    if (epoch <= 0L || mark <= 0L) return epoch
    return epoch + (SystemClock.elapsedRealtime() - mark) / 1000L
}

private fun resizeColors(colors: List<Rgb>, count: Int): List<Rgb> {
    val next = colors.take(count).toMutableList()
    while (next.size < count) next += Rgb(255, 255, 255)
    return next
}
