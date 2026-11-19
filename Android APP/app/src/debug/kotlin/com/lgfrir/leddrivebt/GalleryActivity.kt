package com.lgfrir.leddrivebt

import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.lgfrir.leddrivebt.ble.BleLogLine
import com.lgfrir.leddrivebt.ble.ConnectStep
import com.lgfrir.leddrivebt.ble.LinkPhase
import com.lgfrir.leddrivebt.protocol.BreathCfg
import com.lgfrir.leddrivebt.protocol.ChaseCfg
import com.lgfrir.leddrivebt.protocol.DIR_RIGHT
import com.lgfrir.leddrivebt.protocol.IO_MODE_MANUAL
import com.lgfrir.leddrivebt.protocol.IO_MODE_TIME
import com.lgfrir.leddrivebt.protocol.IoNode
import com.lgfrir.leddrivebt.protocol.MODE_BREATH
import com.lgfrir.leddrivebt.protocol.MODE_CHASE
import com.lgfrir.leddrivebt.protocol.MODE_POINTS
import com.lgfrir.leddrivebt.protocol.MODE_STATIC
import com.lgfrir.leddrivebt.protocol.MODE_WAVE
import com.lgfrir.leddrivebt.protocol.PRESET_ID_NONE
import com.lgfrir.leddrivebt.protocol.PRESET_ROM_FIRST
import com.lgfrir.leddrivebt.protocol.PointCfg
import com.lgfrir.leddrivebt.protocol.PointsCfg
import com.lgfrir.leddrivebt.protocol.PresetRecord
import com.lgfrir.leddrivebt.protocol.Rgb
import com.lgfrir.leddrivebt.protocol.ScheduleRecord
import com.lgfrir.leddrivebt.protocol.SegBinding
import com.lgfrir.leddrivebt.protocol.SegmentDetail
import com.lgfrir.leddrivebt.protocol.SegmentHead
import com.lgfrir.leddrivebt.protocol.StaticCfg
import com.lgfrir.leddrivebt.protocol.UsageLine
import com.lgfrir.leddrivebt.protocol.UsageReport
import com.lgfrir.leddrivebt.protocol.WaveCfg
import com.lgfrir.leddrivebt.protocol.blankDetail
import com.lgfrir.leddrivebt.protocol.wireSelfCheck
import com.lgfrir.leddrivebt.ui.AcrylicGroup
import com.lgfrir.leddrivebt.ui.AppChrome
import com.lgfrir.leddrivebt.ui.BackupBlock
import com.lgfrir.leddrivebt.ui.BindingEditor
import com.lgfrir.leddrivebt.ui.BindingList
import com.lgfrir.leddrivebt.ui.ConnectLogOverlay
import com.lgfrir.leddrivebt.ui.DarkPalette
import com.lgfrir.leddrivebt.ui.DuplicateTimeDialog
import com.lgfrir.leddrivebt.ui.EffectPreview
import com.lgfrir.leddrivebt.ui.EmptyState
import com.lgfrir.leddrivebt.ui.GlobalLibraryRow
import com.lgfrir.leddrivebt.ui.Hint
import com.lgfrir.leddrivebt.ui.IoModeBlock
import com.lgfrir.leddrivebt.ui.LibraryState
import com.lgfrir.leddrivebt.ui.LightPalette
import com.lgfrir.leddrivebt.ui.LightUi
import com.lgfrir.leddrivebt.ui.LocalAcrylic
import com.lgfrir.leddrivebt.ui.LocalFilm
import com.lgfrir.leddrivebt.ui.LocalIoAcrylic
import com.lgfrir.leddrivebt.ui.LocalPalette
import com.lgfrir.leddrivebt.ui.ModeChips
import com.lgfrir.leddrivebt.ui.MonitorRoom
import com.lgfrir.leddrivebt.ui.Page
import com.lgfrir.leddrivebt.ui.PointRuler
import com.lgfrir.leddrivebt.ui.PointsEditor
import com.lgfrir.leddrivebt.ui.PresetBrowser
import com.lgfrir.leddrivebt.ui.ReleaseSlider
import com.lgfrir.leddrivebt.ui.SavePresetDialog
import com.lgfrir.leddrivebt.ui.ScheduleEditor
import com.lgfrir.leddrivebt.ui.ScheduleList
import com.lgfrir.leddrivebt.ui.StripActions
import com.lgfrir.leddrivebt.ui.StripPage
import com.lgfrir.leddrivebt.ui.UsageDialog
import com.lgfrir.leddrivebt.ui.WaveEditor
import com.lgfrir.leddrivebt.ui.scheme
import com.lgfrir.leddrivebt.ui.theme.AppType
import com.lgfrir.leddrivebt.ui.theme.Space
import kotlinx.coroutines.delay

/**
 * 仅 debug 包。UI 沙盒：用假数据渲染真实页面，可选注入一张假壁纸，
 * 这样在没有灯带、没有 BLE 的模拟器上也能验收配色、卡片透明度与壁纸链路。
 *
 * adb shell am start -n com.lgfrir.leddrivebt/.GalleryActivity --es screen strip --ez wallpaper true
 */
class GalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val screen = intent.getStringExtra("screen") ?: "strip"
        val wallpaper = intent.getBooleanExtra("wallpaper", false)
        val dark = intent.getBooleanExtra("dark", true)
        val palette = if (dark) DarkPalette else LightPalette
        setContent {
            MaterialTheme(colorScheme = palette.scheme()) {
                CompositionLocalProvider(
                    LocalPalette provides palette,
                    LocalAcrylic provides 28,
                    LocalIoAcrylic provides 40,
                    LocalFilm provides 36,
                ) {
                    val backdrop = remember(wallpaper) { if (wallpaper) fakeWallpaper() else null }
                    Box(Modifier.fillMaxSize().background(palette.page)) {
                        if (backdrop != null) {
                            Image(
                                bitmap = backdrop,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                                filterQuality = FilterQuality.High,
                            )
                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .background(palette.page.copy(alpha = 0.48f)),
                            )
                        }
                        Scaffold(
                            containerColor = if (backdrop == null) palette.page else Color.Transparent,
                            contentColor = palette.text,
                        ) { padding ->
                            Column(
                                Modifier
                                    .fillMaxSize()
                                    .padding(padding)
                                    .padding(horizontal = Space.x4)
                                    .imePadding(),
                            ) {
                                GalleryScreen(screen)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryScreen(screen: String) {
    when (screen) {
        "scan" -> AppChrome("LEDDrive", onMenu = {}) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Hint("未连接")
                Button(
                    onClick = {},
                    modifier = Modifier.fillMaxWidth().padding(top = Space.x4),
                ) { Text("开始扫描") }
                EmptyState("还没有找到设备。确认灯带已通电，再点上面的开始扫描。")
            }
        }
        "strip" -> {
            var brightness by remember { mutableIntStateOf(160) }
            var refresh by remember { mutableIntStateOf(20) }
            val base = remember { fakeUi() }
            val ui = base.copy(
                holdBrightness = mapOf(1 to brightness, 2 to 200),
                holdRefresh = mapOf(1 to refresh, 2 to 30),
            )
            AppChrome(
                title = "灯带",
                onMenu = {},
                actions = {
                    TextButton(onClick = {}) { Text("监控") }
                    TextButton(onClick = {}) { Text("断开") }
                },
            ) {
                StripPage(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    ui = ui,
                    actions = StripActions(
                        onBrightness = { _, value -> brightness = value },
                        onRefresh = { _, value -> refresh = value },
                    ),
                    library = fakeLibrary(),
                    ioModes = mapOf(1 to IO_MODE_MANUAL, 2 to IO_MODE_TIME),
                    clockReady = true,
                    clockNote = "",
                    epoch = 21 * 3600L,
                    mark = 0L,
                    synced = true,
                )
            }
        }
        "segment", "segment-wave" -> SegmentScreen(MODE_WAVE)
        "segment-points" -> SegmentScreen(MODE_POINTS)
        "connecting" -> {
            val paper = remember { fakeWallpaper() }
            Box(Modifier.fillMaxSize()) {
                AppChrome("灯带", onMenu = {}) {
                    StripPage(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        ui = fakeUi().copy(
                            phase = LinkPhase.Connecting,
                            connectStep = ConnectStep(4, 7, "读取灯段"),
                            connected = false,
                        ),
                        actions = StripActions(),
                        library = fakeLibrary(),
                        ioModes = emptyMap(),
                        clockReady = false,
                        clockNote = "",
                        epoch = 0L,
                        mark = 0L,
                        synced = false,
                    )
                }
                ConnectLogOverlay(
                    lines = fakeConnectLog(),
                    backdrop = paper,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 100.dp),
                )
            }
        }
        "monitor" -> AppChrome("监控", onBack = {}) {
            MonitorRoom(
                ios = fakeIos(),
                previews = fakePreviews(),
                modifier = Modifier.padding(top = Space.x2).weight(1f).fillMaxWidth(),
            )
        }
        "presets" -> AppChrome("灯效预设", onBack = {}) {
            PresetBrowser(
                presets = fakePresets(),
                mode = MODE_WAVE,
                onMode = {},
                onOpen = {},
                onCreate = {},
                showPreview = true,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
        "schedules" -> AppChrome("调度预设", onBack = {}) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                ScheduleList(fakeSchedules(), onOpen = {}, onCreate = {})
            }
        }
        "schedule-edit" -> AppChrome("调度预设", onBack = {}) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                ScheduleEditor(fakeSchedules().first(), 8, onChange = {}, onSave = {}, onDelete = {})
            }
        }
        "backup" -> AppChrome("备份", onBack = {}) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                BackupBlock("目前 30 个，总容量 32，待导入 4 个：夜灯、阅读", onExport = {}, onImport = {})
            }
        }
        "bindings" -> AppChrome("灯效调度", onBack = {}) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                BindingList(
                    listOf(Triple(1, "左灯带", "工作日"), Triple(2, "前灯带", "未绑定")),
                    onOpen = {},
                )
                BindingEditor(
                    binding = SegBinding(1, 1, listOf(1, PRESET_ID_NONE)),
                    times = fakeSchedules().first().atSec,
                    presets = fakePresets(),
                    onChange = {},
                    onSave = {},
                    onUnbind = {},
                    schedules = fakeSchedules(),
                )
            }
        }
        "dialogs" -> Column(Modifier.padding(top = Space.x4)) {
            UsageDialog(
                "删除调度",
                UsageReport(2, listOf(UsageLine(1, "左灯带"), UsageLine(1, "前灯带"))),
                onConfirm = {},
                onDismiss = {},
            )
        }
        "protocol" -> Column(Modifier.padding(top = Space.x4)) {
            Text(wireSelfCheck(), color = LocalPalette.current.ok, style = AppType.body)
            DuplicateTimeDialog(onReplace = {}, onDismiss = {})
            SavePresetDialog(onSave = {}, onDiscard = {})
        }
        /*
         * 转场演示：与 LightApp 同一套 AnimatedContent 参数，只是把时长放慢到 900ms，
         * 方便用 screencap 抓中间帧。壁纸在 Gallery 外层，不参与转场。
         */
        "transition" -> TransitionDemo()
        else -> AppChrome("预览", onBack = {}) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                IoModeBlock(IO_MODE_TIME, 18 * 3600L, onMode = {}, onPreview = {})
                GlobalLibraryRow(onBackup = {})
                Hint("未知屏：$screen")
            }
        }
    }
}

/** 转场演示：两个假页面自动来回切，壁纸在外层不参与。 */
@Composable
private fun TransitionDemo() {
    var page by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1600)
            page = if (page == 0) 1 else 0
        }
    }
    AnimatedContent(
        targetState = page,
        transitionSpec = {
            val dir = if (targetState > initialState) 1 else -1
            (
                slideInHorizontally(tween(900)) { full -> dir * full / 8 } +
                    fadeIn(tween(900))
                ) togetherWith (
                slideOutHorizontally(tween(900)) { full -> -dir * full / 8 } +
                    fadeOut(tween(900))
                )
        },
        modifier = Modifier.fillMaxSize(),
        label = "demo",
    ) { target ->
        AppChrome(
            title = if (target == 0) "第一页" else "第二页",
            onBack = {},
        ) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                repeat(4) { index ->
                    AcrylicGroup {
                        Text(
                            "${if (target == 0) "第一页" else "第二页"} · 第 ${index + 1} 条",
                            color = LocalPalette.current.text,
                            style = AppType.strong,
                        )
                        Hint("这一层在转场里滑动，壁纸不动。")
                    }
                }
            }
        }
    }
}

@Composable
private fun SegmentScreen(mode: Int) {
    val detail = if (mode == MODE_POINTS) {
        blankDetail().copy(
            mode = mode,
            brightness = 180,
            points = PointsCfg(
                wrap = false,
                points = listOf(
                    PointCfg(0, 4000, listOf(Rgb(255, 72, 72))),
                    PointCfg(50, 4000, listOf(Rgb(120, 255, 180))),
                    PointCfg(100, 4000, listOf(Rgb(72, 120, 255))),
                ),
            ),
        )
    } else {
        blankDetail().copy(mode = mode, brightness = 180, wave = WaveCfg(60, DIR_RIGHT))
    }
    AppChrome(title = "左灯带", onBack = {}) {
        EffectPreview(detail, 300)
        if (mode == MODE_POINTS) PointRuler(detail.points)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Hint("300 颗。预览只显示颜色。未写入的参数也会参与预览。")
            AcrylicGroup {
                ReleaseSlider(
                    label = "亮度",
                    value = 180,
                    range = 0f..255f,
                    step = 1,
                    readout = { "$it" },
                    onFinish = {},
                )
                Hint("松手后立即生效。按住加号可以连续调。")
            }
            AcrylicGroup {
                Text("模式", color = LocalPalette.current.text, style = AppType.strong)
                ModeChips(mode, enabled = true) {}
            }
            when (mode) {
                MODE_WAVE -> WaveEditor(detail.wave) {}
                MODE_POINTS -> PointsEditor(detail.points, {}, { _, _ -> })
                else -> Hint("输出关闭。")
            }
            Button(
                onClick = {},
                modifier = Modifier.fillMaxWidth().padding(top = Space.x4),
            ) { Text("应用到灯带") }
            Hint("先在预览里变化，点应用到灯带后才发送。")
        }
    }
}

private fun fakeConnectLog(): List<BleLogLine> = listOf(
    BleLogLine("11:57:03", "灯带→手机", "正在连接", true, "链路已建立"),
    BleLogLine("11:57:03", "手机→灯带", "协商 MTU", true, "247 字节"),
    BleLogLine("11:57:04", "灯带→手机", "发现服务", true, "GATT 就绪"),
    BleLogLine("11:57:04", "灯带→手机", "订阅通知", true, "io_topology"),
    BleLogLine("11:57:04", "灯带→手机", "读取灯段", true, "拓扑 · 2 路 / 5 段"),
    BleLogLine("11:57:05", "灯带→手机", "读取灯段", true, "第 1 路 · 3 段 · 300 颗"),
    BleLogLine("11:57:05", "灯带→手机", "读取灯段", true, "第 2 路 · 2 段 · 180 颗"),
)

private fun fakeIos(): List<IoNode> = listOf(
    IoNode(
        id = 1,
        name = "笔记本",
        brightness = 160,
        refreshMs = 20,
        ledCount = 300,
        segments = listOf(
            SegmentHead(1, 120, "左灯带", MODE_WAVE),
            SegmentHead(2, 90, "前灯带", MODE_CHASE),
            SegmentHead(3, 90, "尾灯带", MODE_BREATH),
        ),
    ),
    IoNode(
        id = 2,
        name = "手办",
        brightness = 200,
        refreshMs = 30,
        ledCount = 180,
        segments = listOf(
            SegmentHead(4, 90, "右侧", MODE_STATIC),
            SegmentHead(5, 90, "顶部", MODE_POINTS),
        ),
    ),
)

private fun fakeUi(): LightUi = LightUi(
    status = "已连接 LEDDrive",
    connected = true,
    ios = fakeIos(),
    page = Page.Strip,
    phase = LinkPhase.Ready,
)

private fun fakePresets(): List<PresetRecord> = listOf(
    PresetRecord(1, "暖白", blankDetail().copy(mode = MODE_WAVE)),
    PresetRecord(2, "呼吸蓝", blankDetail().copy(mode = MODE_BREATH)),
    PresetRecord(PRESET_ROM_FIRST, "关灯", blankDetail().copy(mode = 0)),
)

private fun fakeSchedules(): List<ScheduleRecord> = listOf(
    ScheduleRecord(1, "工作日", listOf(7 * 3600, 18 * 3600)),
    ScheduleRecord(2, "周末", listOf(9 * 3600)),
)

private fun fakeLibrary(): LibraryState = LibraryState(
    presets = fakePresets(),
    schedules = fakeSchedules(),
    ioSchedules = mapOf(2 to 1),
)

private fun fakePreviews(): Map<Int, SegmentDetail> = mapOf(
    1 to blankDetail().copy(mode = MODE_WAVE, brightness = 200),
    2 to blankDetail().copy(
        mode = MODE_CHASE,
        brightness = 200,
        chase = ChaseCfg(60, DIR_RIGHT, listOf(Rgb(255, 64, 64), Rgb(48, 200, 255))),
    ),
    3 to blankDetail().copy(
        mode = MODE_BREATH,
        brightness = 200,
        breath = BreathCfg(Rgb(48, 120, 255), 3000),
    ),
    4 to blankDetail().copy(
        mode = MODE_STATIC,
        brightness = 200,
        solid = StaticCfg(Rgb(255, 176, 64)),
    ),
    5 to blankDetail().copy(
        mode = MODE_POINTS,
        brightness = 200,
        points = PointsCfg(
            wrap = false,
            points = listOf(
                PointCfg(0, 4000, listOf(Rgb(255, 72, 72))),
                PointCfg(50, 4000, listOf(Rgb(120, 255, 180))),
                PointCfg(100, 4000, listOf(Rgb(72, 120, 255))),
            ),
        ),
    ),
)

/** 代码生成的假壁纸。够看出半透明层次和模糊，不需要外部资源。 */
private fun fakeWallpaper(): ImageBitmap {
    val width = 720
    val height = 1280
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint = Paint().apply {
        shader = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            intArrayOf(0xFF16233F.toInt(), 0xFF3E2A5E.toInt(), 0xFFB5653E.toInt()),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
    }
    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    return bitmap.asImageBitmap()
}
