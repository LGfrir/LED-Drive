package com.lgfrir.leddrivebt.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lgfrir.leddrivebt.LedDriveApp
import com.lgfrir.leddrivebt.ble.BleException
import com.lgfrir.leddrivebt.ble.ConnectStep
import com.lgfrir.leddrivebt.ble.LightBleClient
import com.lgfrir.leddrivebt.ble.LightKeepService
import com.lgfrir.leddrivebt.ble.LinkPhase
import com.lgfrir.leddrivebt.ble.ScanHit
import com.lgfrir.leddrivebt.protocol.BreathCfg
import com.lgfrir.leddrivebt.protocol.ChaseCfg
import com.lgfrir.leddrivebt.protocol.FlashCfg
import com.lgfrir.leddrivebt.protocol.IoNode
import com.lgfrir.leddrivebt.protocol.PointCfg
import com.lgfrir.leddrivebt.protocol.PointsCfg
import com.lgfrir.leddrivebt.protocol.Rgb
import com.lgfrir.leddrivebt.protocol.SegmentDetail
import com.lgfrir.leddrivebt.protocol.StaticCfg
import com.lgfrir.leddrivebt.protocol.IO_MODE_TIME
import com.lgfrir.leddrivebt.protocol.PresetRecord
import com.lgfrir.leddrivebt.protocol.ScheduleRecord
import com.lgfrir.leddrivebt.protocol.PRESET_ID_NONE
import com.lgfrir.leddrivebt.protocol.SegBinding
import com.lgfrir.leddrivebt.protocol.WaveCfg
import com.lgfrir.leddrivebt.protocol.nameBytes
import com.lgfrir.leddrivebt.protocol.encodeBreath
import com.lgfrir.leddrivebt.protocol.encodeChase
import com.lgfrir.leddrivebt.protocol.encodeFlash
import com.lgfrir.leddrivebt.protocol.encodePoints
import com.lgfrir.leddrivebt.protocol.encodeStatic
import com.lgfrir.leddrivebt.protocol.encodeWave
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import java.io.File
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Page { Scan, Strip, Segment }

data class LightUi(
    val status: String = "未连接",
    val scanning: Boolean = false,
    val connected: Boolean = false,
    val devices: List<ScanHit> = emptyList(),
    val ios: List<IoNode> = emptyList(),
    val page: Page = Page.Scan,
    val ioId: Int? = null,
    val editor: SegmentDetail? = null,
    val segId: Int? = null,
    val segName: String = "",
    val segLength: Int = 0,
    val busy: Boolean = false,
    val message: String = "",
    val busyLabel: String = "",
    val holdBrightness: Map<Int, Int> = emptyMap(),
    val holdRefresh: Map<Int, Int> = emptyMap(),
    val outputOn: Map<Int, Boolean> = emptyMap(),
    val drafting: Boolean = false,
    val askSave: Boolean = false,
    val phase: LinkPhase = LinkPhase.Idle,
    val connectStep: ConnectStep? = null,
    val dropReason: String = "",
)

class LightViewModel(app: Application) : AndroidViewModel(app) {
    private val session = LedDriveApp.session(app)
    private val ble = session.ble
    val library = session.library
    val commLog = ble.commLog
    val connectLog = ble.connectLog
    private val presetCache = PresetCache(app)
    private val ioScheduleStore = IoScheduleStore(app)
    private val _presetSaved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val presetSaved: SharedFlow<Unit> = _presetSaved.asSharedFlow()
    private val _bindingSaved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val bindingSaved: SharedFlow<Unit> = _bindingSaved.asSharedFlow()
    private val _scheduleSaved = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val scheduleSaved: SharedFlow<Unit> = _scheduleSaved.asSharedFlow()
    private val _presetDeleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val presetDeleted: SharedFlow<Unit> = _presetDeleted.asSharedFlow()
    private val _scheduleDeleted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val scheduleDeleted: SharedFlow<Unit> = _scheduleDeleted.asSharedFlow()
    val clockReady = ble.clockReady
    val clockNote = ble.clockNote
    val devInfo = ble.devInfo
    val deviceName = ble.deviceName

    /** 修改设备 BLE 广播名；回调在主线程，给设置页显示结果。 */
    fun renameDevice(name: String, onDone: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                val saved = ble.writeDeviceName(name)
                onDone(true, "蓝牙名称已改为「$saved」；下次广播/重连生效。")
            } catch (error: Exception) {
                onDone(false, error.message ?: "修改蓝牙名称失败。")
            }
        }
    }
    /**
     * OTA 页面状态。link 在 init 里装成 [OtaBleLink]，复用现有 BLE 连接。
     * 手机 WiFi IPv4 的读取器在这里注入，controller 本身不持有 Context。
     */
    val ota = OtaController(
        wifiIpv4 = { phoneWifiIpv4(app) },
        hotspotIpv4 = { phoneHotspotIpv4() },
    )
    val ioModes = ble.ioModes
    val clockStat = ble.clockStat
    val synced = ble.synced
    val deviceEpoch = ble.deviceEpoch
    val peerName = ble.peerName
    val epochMark = ble.epochMark
    private val appearanceStore = AppearanceStore(app)
    private val _ui = MutableStateFlow(LightUi())
    val ui: StateFlow<LightUi> = _ui.asStateFlow()
    private val _appearance = MutableStateFlow(appearanceStore.load())
    val appearance: StateFlow<Appearance> = _appearance.asStateFlow()
    private val _backdrop = MutableStateFlow<ImageBitmap?>(null)
    val backdrop: StateFlow<ImageBitmap?> = _backdrop.asStateFlow()
    private val _backdropFailed = MutableStateFlow(false)
    val backdropFailed: StateFlow<Boolean> = _backdropFailed.asStateFlow()
    private val _previews = MutableStateFlow<Map<Int, SegmentDetail>>(emptyMap())
    val previews: StateFlow<Map<Int, SegmentDetail>> = _previews.asStateFlow()
    private var monitorJob: Job? = null
    private var backdropJob: Job? = null
    private var baseBackdrop: Bitmap? = null
    private var baseBackdropUri: String? = null
    private val brightnessToken = mutableMapOf<Int, Int>()
    private val parkedBrightness = mutableMapOf<Int, Int>()
    private val refreshToken = mutableMapOf<Int, Int>()
    private var segBrightnessToken = 0
    private var libraryJob: Job? = null

    init {
        ota.link = OtaBleLink(ble, ota, viewModelScope)
        reloadBackdrop()
        if (ble.connected.value) holdConnection()
        viewModelScope.launch {
            combine(ble.status, ble.scanning, ble.connected, ble.devices, ble.ios) {
                    status, scanning, connected, devices, ios ->
                Link(status, scanning, connected, devices, ios)
            }.collect { link ->
                update { current ->
                    val stillThere = current.ioId?.let { id -> link.ios.any { it.id == id } } == true
                    current.copy(
                        status = link.status,
                        scanning = link.scanning,
                        connected = link.connected,
                        devices = link.devices,
                        ios = link.ios,
                        ioId = if (stillThere) current.ioId else link.ios.firstOrNull()?.id,
                    )
                }
            }
        }
        viewModelScope.launch {
            combine(ble.phase, ble.connectStep, ble.dropReason) { phase, step, reason ->
                Triple(phase, step, reason)
            }.collect { (phase, step, reason) ->
                update { current ->
                    current.copy(
                        phase = phase,
                        connectStep = step,
                        dropReason = reason,
                        page = when (phase) {
                            LinkPhase.Connecting -> Page.Strip
                            LinkPhase.Ready -> if (current.page == Page.Scan) Page.Strip else current.page
                            else -> Page.Scan
                        },
                        message = if (phase == LinkPhase.Connecting || phase == LinkPhase.Dropped) "" else current.message,
                        editor = if (phase == LinkPhase.Ready) current.editor else null,
                        holdBrightness = if (phase == LinkPhase.Ready) current.holdBrightness else emptyMap(),
                        holdRefresh = if (phase == LinkPhase.Ready) current.holdRefresh else emptyMap(),
                        drafting = if (phase == LinkPhase.Ready) current.drafting else false,
                        askSave = if (phase == LinkPhase.Ready) current.askSave else false,
                    )
                }
                if (phase != LinkPhase.Ready && phase != LinkPhase.Connecting) {
                    _previews.value = emptyMap()
                    library.clear()
                    releaseConnection()
                }
            }
        }
    }

    fun reloadMonitor() {
        monitorJob?.cancel()
        monitorJob = null
        val pending = _ui.value.ios.flatMap { it.segments }
        _previews.value = emptyMap()
        if (pending.isEmpty()) return
        monitorJob = viewModelScope.launch {
            for (seg in pending) {
                /*
                 * 不能只写 runCatching：它会把 CancellationException 一起吞掉，
                 * 于是 stopPreviewLoad() 取消之后，循环还会把剩下的段空跑一遍。
                 */
                val detail = try {
                    ble.loadSegment(seg.id)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    continue
                }
                _previews.value = _previews.value + (seg.id to detail)
            }
        }
    }

    fun setDark(dark: Boolean) = updateAppearance { it.copy(dark = dark) }

    fun setInk(ink: String) = updateAppearance { it.copy(ink = ink) }

    fun setFont(role: String, id: String) = updateAppearance {
        when (role) {
            "caption" -> it.copy(fontCaption = id)
            "body" -> it.copy(fontBody = id)
            "strong" -> it.copy(fontStrong = id)
            "title" -> it.copy(fontTitle = id)
            "hero" -> it.copy(fontHero = id)
            else -> it
        }
    }

    fun resetPhases() {
        if (linkLocked()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ble.resetPhases()
                update { it.copy(message = "已对齐") }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                update { it.copy(message = failureText(error, "没有对齐")) }
            }
        }
    }

    fun setKeepLink(keep: Boolean) = updateAppearance { it.copy(keepLink = keep) }

    fun setBlur(blur: Int) {
        updateAppearance { it.copy(blur = blur.coerceIn(0, BLUR_MAX)) }
        // 拖动时已经改过 _appearance（预览），这里强制再出一张全分辨率终图。
        reloadBackdrop(preview = false)
    }

    /** 拖动模糊滑块时用：只更新显示和低分辨率预览，不落盘。 */
    fun setBlurPreview(blur: Int) {
        val value = blur.coerceIn(0, BLUR_MAX)
        if (_appearance.value.blur == value) return
        _appearance.value = _appearance.value.copy(blur = value)
        reloadBackdrop(preview = true)
    }

    fun setScrim(scrim: Int) = updateAppearance { it.copy(scrim = scrim.coerceIn(0, SCRIM_MAX)) }

    fun setAcrylic(acrylic: Int) = updateAppearance { it.copy(acrylic = acrylic.coerceIn(0, ACRYLIC_MAX)) }

    fun setIoAcrylic(acrylic: Int) = updateAppearance { it.copy(ioAcrylic = acrylic.coerceIn(0, ACRYLIC_MAX)) }

    fun setFilm(acrylic: Int) = updateAppearance { it.copy(film = acrylic.coerceIn(0, ACRYLIC_MAX)) }

    fun setBackground(uri: Uri) {
        val previous = _appearance.value.backgroundUri
        runCatching { holdBackground(getApplication(), uri) }
        if (previous != null && previous != uri.toString()) {
            releaseBackground(getApplication(), previous)
        }
        updateAppearance { it.copy(backgroundUri = uri.toString()) }
    }

    fun clearBackground() {
        _appearance.value.backgroundUri?.let { releaseBackground(getApplication(), it) }
        updateAppearance { it.copy(backgroundUri = null) }
    }

    fun bluetoothReady(): Boolean = ble.bluetoothReady()

    fun startScan() {
        update { it.copy(message = "") }
        ble.startScan()
    }

    fun stopScan() = ble.stopScan()

    fun connect(address: String) {
        if (ble.phase.value == LinkPhase.Connecting) return
        ble.prepareConnect()
        viewModelScope.launch {
            update {
                it.copy(
                    page = Page.Strip,
                    message = "",
                    editor = null,
                    askSave = false,
                    drafting = false,
                )
            }
            library.restoreIoSchedules(ioScheduleStore.load(address))
            presetCache.load(address)?.let { library.showPresets(it) }
            try {
                ble.stopScan()
                ble.connect(address)
                if (ble.phase.value == LinkPhase.Connecting && ble.connected.value) {
                    holdConnection()
                    try {
                        adoptDeviceSchedules()
                    } finally {
                        if (ble.phase.value == LinkPhase.Connecting) ble.markReady()
                    }
                    /*
                     * 这里原来有一个 ensureMonitor()：连上就把 21 个灯段的完整参数
                     * 全读一遍（每段 8 个特征 ≈ 9 次往返，合计 190 次），
                     * 按 30~50 ms 一次算就是 6~9 秒的 BLE 队列。
                     * 用户此刻只要点一下"手动/定时"，命令就排在这 190 次后面，
                     * 表现就是"第一次点卡住，再点就好了"。
                     * 预览页改成按需加载：只有真的进"监控"才读。
                     */
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                update { it.copy(page = Page.Scan, message = "") }
            }
        }
    }

    fun disconnect() {
        releaseConnection()
        ble.disconnect()
    }

    private fun holdConnection() {
        val app = getApplication<Application>()
        runCatching {
            ContextCompat.startForegroundService(app, Intent(app, LightKeepService::class.java))
        }
    }

    private fun releaseConnection() {
        val app = getApplication<Application>()
        app.stopService(Intent(app, LightKeepService::class.java))
    }

    fun selectIo(id: Int) = update { it.copy(ioId = id) }

    fun setIoBrightness(io: Int, value: Int) {
        if (linkLocked()) return
        if (_ui.value.outputOn[io] == false) {
            parkedBrightness[io] = value
            update { it.copy(holdBrightness = it.holdBrightness + (io to value)) }
            return
        }
        val token = (brightnessToken[io] ?: 0) + 1
        brightnessToken[io] = token
        val previous = _ui.value.ios.firstOrNull { it.id == io }?.brightness
        update { it.copy(holdBrightness = it.holdBrightness + (io to value), message = "") }
        viewModelScope.launch {
            val settled = confirm({ brightnessToken[io] ?: 0 }, token, previous, { ble.setIoBrightness(io, value) }) {
                ble.readIoBrightness(io)
            }
            if (settled.superseded) return@launch
            update { state ->
                if ((brightnessToken[io] ?: 0) != token) state
                else state.copy(
                    holdBrightness = state.holdBrightness - io,
                    message = if (settled.value != null && settled.value != value) {
                        "亮度未写入，设备当前值 ${settled.value}"
                    } else {
                        state.message
                    },
                )
            }
        }
    }

    fun setOutput(io: Int, on: Boolean) {
        if (linkLocked()) return
        val shown = _ui.value.holdBrightness[io]
            ?: _ui.value.ios.firstOrNull { it.id == io }?.brightness
            ?: 0
        if (on) {
            val restore = parkedBrightness[io] ?: shown
            update {
                it.copy(
                    outputOn = it.outputOn + (io to true),
                    holdBrightness = it.holdBrightness + (io to restore),
                )
            }
            setIoBrightness(io, restore)
            return
        }
        brightnessToken[io] = (brightnessToken[io] ?: 0) + 1
        parkedBrightness[io] = shown
        update {
            it.copy(
                outputOn = it.outputOn + (io to false),
                holdBrightness = it.holdBrightness + (io to shown),
            )
        }
        viewModelScope.launch {
            try {
                ble.writeIoBrightness(io, 0)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                update { it.copy(message = error.message ?: "亮度写入失败") }
            }
        }
    }

    fun setIoRefresh(io: Int, value: Int) {
        if (linkLocked()) return
        val token = (refreshToken[io] ?: 0) + 1
        refreshToken[io] = token
        val previous = _ui.value.ios.firstOrNull { it.id == io }?.refreshMs
        update { it.copy(holdRefresh = it.holdRefresh + (io to value), message = "") }
        viewModelScope.launch {
            val settled = confirm({ refreshToken[io] ?: 0 }, token, previous, { ble.setIoRefresh(io, value) }) {
                ble.readIoRefresh(io)
            }
            if (settled.superseded) return@launch
            update { state ->
                if ((refreshToken[io] ?: 0) != token) state
                else state.copy(
                    holdRefresh = state.holdRefresh - io,
                    message = if (settled.value != null && settled.value != value) {
                        "刷新间隔未写入，设备当前值 ${settled.value} ms"
                    } else {
                        state.message
                    },
                )
            }
        }
    }

    fun openSegment(id: Int, name: String, length: Int) {
        if (_ui.value.busy || linkLocked()) return
        /*
         * 先把页面切过去，再等 BLE 读完灯段参数。
         * 原来是读完才切页，固件回包慢的时候看起来像"点了没反应、卡一下才进去"。
         */
        update {
            it.copy(
                busy = true,
                message = "",
                segId = id,
                segName = name,
                segLength = length,
                page = Page.Segment,
                editor = null,
            )
        }
        viewModelScope.launch {
            try {
                val detail = ble.loadSegment(id)
                _previews.value = _previews.value + (id to detail)
                update { it.copy(editor = detail) }
                if (library.state.value.presets.isEmpty()) {
                    runCatching { library.loadPresets() }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                update { it.copy(message = failureText(error, "读取灯段失败")) }
            } finally {
                update { it.copy(busy = false) }
            }
        }
    }

    fun closeSegment() {
        val state = _ui.value
        val id = state.segId
        viewModelScope.launch {
            try {
                if (state.drafting && id != null) {
                    ble.commitDraft(id, false)
                    val detail = ble.loadSegment(id)
                    _previews.value = _previews.value + (id to detail)
                }
                update { it.copy(page = Page.Strip, editor = null, drafting = false, askSave = false, message = "") }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                update { it.copy(message = failureText(error, "设备没有返回结果"), askSave = true) }
            }
        }
    }

    fun setMode(mode: Int) {
        if (linkLocked()) return
        val id = _ui.value.segId ?: return
        val current = _ui.value.editor ?: return
        launchEdit {
            ble.setMode(id, mode)
            val next = current.copy(mode = mode)
            _previews.value = _previews.value + (id to next)
            update { it.copy(editor = next) }
            markDraft()
            "模式已写入。"
        }
    }

    fun setIoMode(io: Int, mode: Int) {
        if (linkLocked()) {
            /* 原来这里是静默 return：连接/同步期间的点击完全没反应，看着像"卡住" */
            update { it.copy(message = "正在同步设备，稍等一下再切") }
            return
        }
        if (mode == IO_MODE_TIME && !ble.clockReady.value) {
            update { it.copy(message = "定时暂时不可用") }
            return
        }
        stopPreviewLoad()
        val previous = ble.ioModes.value
        ble.stageIoMode(io, mode)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ble.setClockIoMode(io, mode)
                if (mode == IO_MODE_TIME) refreshTimedLabels(io)
                update { it.copy(message = "") }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ble.replaceIoModes(previous)
                update { it.copy(message = error.message ?: "模式写入失败") }
            }
        }
    }

    private suspend fun refreshTimedLabels(io: Int) {
        if (library.state.value.presets.isEmpty()) runCatching { library.loadPresets() }
        if (library.state.value.schedules.isEmpty()) runCatching { library.loadSchedules() }
        val segs = _ui.value.ios.firstOrNull { it.id == io }?.segments?.map { it.id }.orEmpty()
        if (segs.isNotEmpty()) runCatching { library.loadBindings(segs) }
    }

    private fun stopPreviewLoad() {
        monitorJob?.cancel()
        monitorJob = null
    }

    fun loadPresets() {
        val mac = ble.address.value
        libraryLaunch {
            presetCache.load(mac)?.let { library.showPresets(it) }
            library.loadPresets()
            presetCache.save(mac, library.state.value.presets)
        }
    }
    fun loadSchedules() {
        stopPreviewLoad()
        libraryLaunch { library.loadSchedules() }
    }

    fun loadBindings() {
        stopPreviewLoad()
        libraryLaunch { adoptDeviceSchedules() }
    }

    fun chooseIoSchedule(ioId: Int, scheduleId: Int) {
        if (linkLocked() || !ble.clockReady.value) {
            update { it.copy(message = "定时暂时不可用") }
            return
        }
        stopPreviewLoad()
        val schedule = library.state.value.schedules.firstOrNull { it.id == scheduleId } ?: return
        val segs = _ui.value.ios.firstOrNull { it.id == ioId }?.segments?.map { it.id }.orEmpty()
        val previousSched = library.state.value.scheduleIdOf(ioId)
        val mac = ble.address.value
        val label = schedule.name.ifEmpty { "调度 ${schedule.id}" }
        update { it.copy(message = "已使用 $label") }
        libraryLaunch("正在保存") {
            /* 切走之前，先把当前调度这一套槽位分配存进本地缓存 */
            if (previousSched != 0 && previousSched != scheduleId) {
                persistScheduleMaps(mac, ioId, previousSched)
            }
            /*
             * 切过去：目标调度以前配过就按缓存原样恢复；
             * 没配过才退回"按时间点对齐"的老逻辑。
             */
            val saved = if (previousSched != scheduleId) {
                ioScheduleStore.loadMaps(mac, ioId, scheduleId)
            } else {
                emptyMap()
            }
            val pending = library.pinIoSchedule(ioId, schedule, segs, saved)
            persistIoSchedules()
            library.pushBindings(pending)
            /* 目标调度这一套也立刻存一份，后面单段修改会继续更新它 */
            persistScheduleMaps(mac, ioId, scheduleId)
            update { it.copy(message = "已同步 $label") }
        }
    }

    /*
     * 把「IO + 调度」下每个灯段的槽位分配落到本地文件。
     * 固件每个灯段只存一条绑定（只记当前调度），切走就会被覆盖，
     * 所以这份缓存是"切走再切回来还能恢复"的唯一依据。
     */
    private suspend fun persistScheduleMaps(mac: String, ioId: Int, schedId: Int) {
        if (mac.isEmpty() || schedId == 0) return
        val segIds = _ui.value.ios.firstOrNull { it.id == ioId }?.segments?.map { it.id }.orEmpty()
        if (segIds.isEmpty()) return
        val maps = segIds.mapNotNull { segId ->
            library.state.value.bindings[segId]
                ?.takeIf { it.schedId == schedId }
                ?.let { segId to it.presetIds }
        }.toMap()
        if (maps.isNotEmpty()) {
            ioScheduleStore.saveMaps(mac, ioId, schedId, maps)
        }
    }

    private suspend fun adoptDeviceSchedules() {
        val groups = _ui.value.ios.map { io -> io.id to io.segments.map { it.id } }
        val segIds = groups.flatMap { it.second }
        runCatching { library.loadSchedules() }
        runCatching { library.loadPresets() }
            .onSuccess { presetCache.save(ble.address.value, library.state.value.presets) }
        if (segIds.isNotEmpty()) {
            try {
                library.loadBindings(segIds)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                update {
                    it.copy(message = "同步没有完成，画面先按手机记录显示，灯带上的调度还没被改写")
                }
                return
            }
        }
        library.adoptFromDevice(groups)
        persistIoSchedules()
    }

    private suspend fun persistIoSchedules() {
        val state = library.state.value
        ioScheduleStore.save(
            ble.address.value,
            state.ioSchedules.mapValues { (ioId, id) -> IoPin(id, state.ioScheduleNames[ioId].orEmpty()) },
        )
    }

    fun openSlot(segId: Int, schedId: Int) {
        val schedule = library.state.value.schedules.firstOrNull { it.id == schedId }
        val existing = library.state.value.bindings[segId]
        val ids = if (existing != null && existing.schedId == schedId) {
            existing.presetIds
        } else {
            List(schedule?.atSec?.size ?: 0) { PRESET_ID_NONE }
        }
        library.editBinding(SegBinding(segId, schedId, ids))
        libraryLaunch {
            if (library.state.value.presets.isEmpty()) {
                runCatching {
                    presetCache.load(ble.address.value)?.let { library.showPresets(it) }
                    library.loadPresets()
                    presetCache.save(ble.address.value, library.state.value.presets)
                }
            }
            runCatching { library.openBinding(segId) }
            val read = library.state.value.editingBinding ?: return@libraryLaunch
            if (schedId != 0 && read.schedId != schedId) {
                library.editBinding(SegBinding(segId, schedId, ids))
            }
        }
    }
    fun beginPreset(record: PresetRecord?) = library.beginPreset(record)
    fun editPreset(block: (PresetRecord) -> PresetRecord) = library.editPreset(block)
    fun beginSchedule(record: ScheduleRecord?) = library.beginSchedule(record)
    fun editSchedule(record: ScheduleRecord) = library.editSchedule(record)
    fun openBinding(segId: Int) {
        library.stageBinding(segId)
        libraryLaunch { library.openBinding(segId) }
    }
    fun editBinding(binding: SegBinding) = library.editBinding(binding)

    fun savePreset() = libraryLaunch("正在保存") {
        val note = library.savePreset()
        update { it.copy(message = note) }
        if (note.startsWith("已保存")) {
            presetCache.save(ble.address.value, library.state.value.presets)
            _presetSaved.tryEmit(Unit)
        }
    }
    fun saveSchedule() = libraryLaunch("正在保存") {
        val note = library.saveSchedule()
        if (note.isNotEmpty()) update { it.copy(message = note) }
        // 存成功就回到调度列表；否则页面会停在编辑态、返回时又被问一遍"要不要保存"。
        if (note.startsWith("已保存")) _scheduleSaved.tryEmit(Unit)
    }
    fun unbindSegment(segId: Int) {
        library.editBinding(SegBinding(segId, 0, emptyList()))
        libraryLaunch("正在保存") {
            val note = library.saveBinding()
            update { it.copy(message = if (note.isEmpty()) note else "已解除绑定") }
        }
    }

    fun saveBinding() = libraryLaunch("正在保存") {
        val note = library.saveBinding()
        update { it.copy(message = note) }
        if (note.isNotEmpty()) {
            /* 单段改完立刻更新「IO + 调度」缓存，切走再切回来才不会丢 */
            val edited = library.state.value.editingBinding
            if (edited != null && edited.schedId != 0) {
                val io = _ui.value.ios.firstOrNull { io -> io.segments.any { it.id == edited.segId } }
                if (io != null) persistScheduleMaps(ble.address.value, io.id, edited.schedId)
            }
            _bindingSaved.tryEmit(Unit)
        }
    }
    fun requestDeletePreset(id: Int) = libraryLaunch { library.requestDeletePreset(id) }
    fun requestDeleteSchedule(id: Int) = libraryLaunch { library.requestDeleteSchedule(id) }
    fun confirmUsage() {
        // kind 在删除后会被清空，先记下来，删完按类型导航回对应列表。
        val kind = library.state.value.usageKind
        libraryLaunch {
            val note = library.confirmUsage()
            if (note.isNotEmpty()) update { it.copy(message = note) }
            if (note.isNotEmpty()) {
                if (kind == "schedule") _scheduleDeleted.tryEmit(Unit) else _presetDeleted.tryEmit(Unit)
            }
        }
    }
    fun dismissUsage() = library.dismissUsage()

    fun exportBackup(context: Context) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            update { it.copy(busy = true, message = "") }
            try {
                val bundle = library.exportBundle(_ui.value.ios.size, segmentIds())
                val text = encodeBackupText(bundle)
                val dir = app.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: error("无法创建文件")
                val file = File(dir, "leddrive-backup.json")
                withContext(Dispatchers.IO) { file.writeText(text) }
                val uri = FileProvider.getUriForFile(app, "${app.packageName}.files", file)
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, "导出备份"))
                update { it.copy(message = "已生成备份") }
            } catch (error: Exception) {
                update { it.copy(message = error.message ?: "导出失败") }
            } finally {
                update { it.copy(busy = false) }
            }
        }
    }

    private val _importTrim = MutableStateFlow<ImportResult.Overflow?>(null)
    private var importText: String = ""
    val importTrim: StateFlow<ImportResult.Overflow?> = _importTrim.asStateFlow()

    fun importBackup(uri: Uri) {
        viewModelScope.launch {
            update { it.copy(busy = true, busyLabel = "正在导入", message = "") }
            try {
                val bytes = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("无法读取文件")
                }
                if (bytes.size > 512 * 1024) error("文件超过 512KB")
                importText = bytes.toString(Charsets.UTF_8)
                applyImport(emptySet(), emptySet())
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                update { it.copy(message = failureText(error, "导入失败")) }
            } finally {
                update { it.copy(busy = false, busyLabel = "") }
            }
        }
    }

    fun continueImport(dropPresets: Set<String>, dropSchedules: Set<String>) {
        val text = importText
        if (text.isEmpty()) return
        viewModelScope.launch {
            update { it.copy(busy = true, busyLabel = "正在导入", message = "") }
            try {
                applyImport(dropPresets, dropSchedules)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                update { it.copy(message = failureText(error, "导入失败")) }
            } finally {
                update { it.copy(busy = false, busyLabel = "") }
            }
        }
    }

    fun dismissImport() {
        _importTrim.value = null
    }

    private suspend fun applyImport(dropPresets: Set<String>, dropSchedules: Set<String>) {
        val result = library.importBundle(
            importText,
            segmentIds().toSet(),
            _ui.value.ios.map { it.id }.toSet(),
            onNote = { progress -> update { it.copy(message = progress) } },
            dropPresets = dropPresets,
            dropSchedules = dropSchedules,
        )
        when (result) {
            is ImportResult.Finished -> {
                _importTrim.value = null
                update { it.copy(message = result.message) }
            }
            is ImportResult.Overflow -> _importTrim.value = result
        }
    }

    fun acceptDraft() {
        val id = _ui.value.segId ?: return
        viewModelScope.launch {
            update { it.copy(busy = true, askSave = false) }
            try {
                val result = ble.commitDraft(id, true)
                val detail = runCatching { ble.loadSegment(id) }.getOrNull()
                if (detail != null) _previews.value = _previews.value + (id to detail)
                update {
                    it.copy(
                        drafting = false,
                        editor = null,
                        page = Page.Strip,
                        message = "已保存预设 ${result.b}",
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                update { it.copy(message = failureText(error, "设备没有返回结果"), drafting = true, askSave = true) }
            } finally {
                update { it.copy(busy = false) }
            }
        }
    }

    fun discardDraft() {
        val id = _ui.value.segId ?: return
        viewModelScope.launch {
            update { it.copy(busy = true, askSave = false) }
            try {
                if (_ui.value.drafting) ble.commitDraft(id, false)
                val detail = ble.loadSegment(id)
                _previews.value = _previews.value + (id to detail)
                update { it.copy(drafting = false, editor = detail, message = "已恢复") }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                update { it.copy(message = failureText(error, "设备没有返回结果"), askSave = true) }
            } finally {
                update { it.copy(busy = false) }
            }
        }
    }

    fun setSegBrightness(value: Int) {
        if (linkLocked()) return
        val id = _ui.value.segId ?: return
        val token = ++segBrightnessToken
        val previous = _ui.value.editor?.brightness
        update { state ->
            val editor = state.editor?.copy(brightness = value)
            if (editor != null) _previews.value = _previews.value + (id to editor)
            state.copy(editor = editor, message = "")
        }
        viewModelScope.launch {
            if (inTimeMode()) update { it.copy(drafting = true) }
            val settled = confirm({ segBrightnessToken }, token, previous, { ble.setSegBrightness(id, value) }) {
                ble.readSegBrightness(id)
            }
            if (settled.superseded || token != segBrightnessToken) return@launch
            val seen = settled.value
            update { state ->
                if (token != segBrightnessToken) state
                else {
                    val editor = if (seen == null) state.editor else state.editor?.copy(brightness = seen)
                    if (editor != null) _previews.value = _previews.value + (id to editor)
                    state.copy(
                        editor = editor,
                        message = if (seen != null && seen != value) "亮度未写入，设备当前值 $seen" else state.message,
                    )
                }
            }
        }
    }

    fun updateWave(cfg: WaveCfg) = edit { it.copy(wave = cfg) }
    fun updateSolid(color: Rgb) = edit { it.copy(solid = StaticCfg(color)) }
    fun updateBreath(cfg: BreathCfg) = edit { it.copy(breath = cfg) }
    fun updateChase(cfg: ChaseCfg) = edit { it.copy(chase = cfg) }
    fun updatePoints(cfg: PointsCfg) = edit { it.copy(points = cfg) }
    fun updatePoint(index: Int, point: PointCfg) = edit { detail ->
        val points = detail.points.points.toMutableList()
        if (index in points.indices) points[index] = point
        detail.copy(points = detail.points.copy(points = points))
    }
    fun updateFlash(cfg: FlashCfg) = edit { it.copy(flash = cfg) }

    fun applyPresetToSegment(preset: PresetRecord) {
        if (linkLocked()) return
        val id = _ui.value.segId ?: return
        val detail = preset.detail
        _previews.value = _previews.value + (id to detail)
        update { it.copy(editor = detail, message = "") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                ble.applySegmentDetail(id, detail)
                val label = preset.name.ifEmpty { "预设 ${preset.id}" }
                update { it.copy(message = "已使用 $label") }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                update { it.copy(message = failureText(error, "预设没有写上")) }
            }
        }
    }

    fun saveSegmentPreset(name: String) {
        if (linkLocked()) return
        val detail = _ui.value.editor ?: return
        val id = _ui.value.segId ?: return
        if (nameBytes(name) > 63) {
            update { it.copy(message = "名称超过 63 字节") }
            return
        }
        library.beginPreset(PresetRecord(0, name.trim(), detail))
        libraryLaunch("正在保存") {
            val note = library.savePreset()
            ble.applySegmentDetail(id, detail)
            update { it.copy(message = note.ifEmpty { "已保存" }) }
        }
    }

    fun commitDetail() {
        if (linkLocked()) return
        val state = _ui.value
        val id = state.segId ?: return
        val detail = state.editor ?: return
        launchEdit {
            when (detail.mode) {
                1 -> ble.writeWave(id, encodeWave(detail.wave))
                2 -> ble.writeStatic(id, encodeStatic(detail.solid))
                3 -> ble.writeBreath(id, encodeBreath(detail.breath))
                4 -> ble.writeChase(id, encodeChase(detail.chase))
                5 -> ble.writePoints(id, encodePoints(detail.points))
                6 -> ble.writeFlash(id, encodeFlash(detail.flash))
                else -> return@launchEdit "关闭模式没有额外参数。"
            }
            markDraft()
            if (inTimeMode()) {
                update { it.copy(askSave = true) }
                ""
            } else {
                "已写入。"
            }
        }
    }

    private fun inTimeMode(): Boolean = ble.ioModes.value[_ui.value.ioId] == IO_MODE_TIME

    private fun markDraft() {
        if (inTimeMode()) update { it.copy(drafting = true) }
    }

    private fun segmentIds(): List<Int> = _ui.value.ios.flatMap { io -> io.segments.map { it.id } }

    private fun linkLocked(): Boolean = ble.phase.value == LinkPhase.Connecting

    private fun failureText(error: Exception, fallback: String): String {
        val raw = error.message?.takeIf { it.isNotBlank() } ?: fallback
        return if (raw.contains("Timeout", ignoreCase = true) || raw.contains("timed out", ignoreCase = true)) {
            "设备没有返回结果"
        } else {
            raw
        }
    }

    private fun libraryLaunch(label: String = "正在读取", block: suspend () -> Unit) {
        if (libraryJob?.isActive == true || linkLocked()) return
        libraryJob = viewModelScope.launch {
            update { it.copy(busy = true, busyLabel = label) }
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                update { it.copy(message = failureText(error, "操作失败")) }
            } finally {
                update { it.copy(busy = false, busyLabel = "") }
            }
        }
    }

    private fun edit(block: (SegmentDetail) -> SegmentDetail) {
        update { state ->
            val detail = state.editor ?: return@update state
            val next = block(detail)
            val id = state.segId
            if (id != null) _previews.value = _previews.value + (id to next)
            state.copy(editor = next, message = "")
        }
    }

    private suspend fun confirm(
        tokenNow: () -> Int,
        token: Int,
        previous: Int?,
        write: suspend () -> Unit,
        read: suspend () -> Int,
    ): Settled {
        val wrote = try {
            write()
            true
        } catch (_: Exception) {
            false
        }
        if (tokenNow() != token) return Settled(superseded = true, value = null)
        val actual = try {
            read()
        } catch (_: Exception) {
            null
        }
        return Settled(superseded = false, value = actual ?: if (wrote) null else previous)
    }

    private data class Settled(val superseded: Boolean, val value: Int?)

    private fun launchEdit(block: suspend () -> String) {
        viewModelScope.launch {
            update { it.copy(busy = true) }
            try {
                val text = block()
                update { it.copy(message = text) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                update { it.copy(message = failureText(error, "写入失败")) }
            } finally {
                update { it.copy(busy = false) }
            }
        }
    }

    private fun update(block: (LightUi) -> LightUi) {
        _ui.value = block(_ui.value)
    }

    private var appearanceSave: Job? = null

    private fun updateAppearance(block: (Appearance) -> Appearance) {
        val previous = _appearance.value
        val next = block(previous)
        if (next == previous) return
        _appearance.value = next
        /*
         * 滑条每个刻度都会进这里。立刻改界面，落盘延后一拍，
         * 避免拖动时主线程反复写配置。
         */
        appearanceSave?.cancel()
        appearanceSave = viewModelScope.launch {
            delay(250)
            appearanceStore.save(_appearance.value)
        }
        if (previous.backgroundUri != next.backgroundUri || previous.blur != next.blur) {
            reloadBackdrop()
        }
    }

    private fun reloadBackdrop(preview: Boolean = false) {
        val snap = _appearance.value
        val uri = snap.backgroundUri
        backdropJob?.cancel()
        if (uri == null) {
            baseBackdrop = null
            baseBackdropUri = null
            _backdrop.value = null
            _backdropFailed.value = false
            return
        }
        _backdropFailed.value = false
        backdropJob = viewModelScope.launch {
            val image = withContext(Dispatchers.IO) {
                runCatching {
                    /*
                     * 解码 + fit 的结果按 uri 缓存。改模糊、拖动预览都复用这一张，
                     * 不再每次重新读文件、重新解码 9MB 的图。
                     */
                    val base = if (baseBackdropUri == uri && baseBackdrop != null) {
                        baseBackdrop!!
                    } else {
                        decodeBaseBackdrop(getApplication(), uri)?.also {
                            baseBackdrop = it
                            baseBackdropUri = uri
                        }
                    } ?: return@runCatching null
                    // 拖动时先出 1/4 分辨率的预览，松手后再出终图。
                    blurBackdrop(base, snap.blur, scale = if (preview) 4 else 1)
                }.getOrNull()
            }
            if (_appearance.value.backgroundUri != uri || _appearance.value.blur != snap.blur) return@launch
            _backdrop.value = image
            _backdropFailed.value = image == null
        }
    }

    private data class Link(
        val status: String,
        val scanning: Boolean,
        val connected: Boolean,
        val devices: List<ScanHit>,
        val ios: List<IoNode>,
    )
}
