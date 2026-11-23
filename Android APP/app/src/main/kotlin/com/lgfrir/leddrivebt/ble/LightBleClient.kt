package com.lgfrir.leddrivebt.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import com.lgfrir.leddrivebt.protocol.CHUNK
import com.lgfrir.leddrivebt.protocol.ClockStat
import com.lgfrir.leddrivebt.protocol.DevInfo
import com.lgfrir.leddrivebt.protocol.GattId
import com.lgfrir.leddrivebt.protocol.IoNode
import com.lgfrir.leddrivebt.protocol.MODE_FLASH
import com.lgfrir.leddrivebt.protocol.MODE_OFF
import com.lgfrir.leddrivebt.protocol.OpResult
import com.lgfrir.leddrivebt.protocol.OTA_CMD_MAX
import com.lgfrir.leddrivebt.protocol.PacketException
import com.lgfrir.leddrivebt.protocol.PresetHead
import com.lgfrir.leddrivebt.protocol.PresetRecord
import com.lgfrir.leddrivebt.protocol.REFRESH_MAX
import com.lgfrir.leddrivebt.protocol.REFRESH_MIN
import com.lgfrir.leddrivebt.protocol.RESP_BIND
import com.lgfrir.leddrivebt.protocol.RESP_DRAFT
import com.lgfrir.leddrivebt.protocol.RESP_IO_MODE
import com.lgfrir.leddrivebt.protocol.RESP_PRESET
import com.lgfrir.leddrivebt.protocol.RESP_PRESET_DEL
import com.lgfrir.leddrivebt.protocol.RESP_SCHED
import com.lgfrir.leddrivebt.protocol.RESP_SCHED_DEL
import com.lgfrir.leddrivebt.protocol.ScheduleRecord
import com.lgfrir.leddrivebt.protocol.SegBinding
import com.lgfrir.leddrivebt.protocol.SegmentDetail
import com.lgfrir.leddrivebt.protocol.UsageReport
import com.lgfrir.leddrivebt.protocol.decodeBinding
import com.lgfrir.leddrivebt.protocol.decodeRecord
import com.lgfrir.leddrivebt.protocol.decodeSchedule
import com.lgfrir.leddrivebt.protocol.MODE_BREATH
import com.lgfrir.leddrivebt.protocol.MODE_CHASE
import com.lgfrir.leddrivebt.protocol.MODE_POINTS
import com.lgfrir.leddrivebt.protocol.MODE_STATIC
import com.lgfrir.leddrivebt.protocol.MODE_WAVE
import com.lgfrir.leddrivebt.protocol.Rgb
import com.lgfrir.leddrivebt.protocol.parseBreath
import com.lgfrir.leddrivebt.protocol.parseChase
import com.lgfrir.leddrivebt.protocol.parsePoints
import com.lgfrir.leddrivebt.protocol.parseStatic
import com.lgfrir.leddrivebt.protocol.encodeBinding
import com.lgfrir.leddrivebt.protocol.encodeBreath
import com.lgfrir.leddrivebt.protocol.encodeChase
import com.lgfrir.leddrivebt.protocol.encodeFlash
import com.lgfrir.leddrivebt.protocol.encodeIoMode
import com.lgfrir.leddrivebt.protocol.encodePoints
import com.lgfrir.leddrivebt.protocol.encodeRecord
import com.lgfrir.leddrivebt.protocol.encodeSchedule
import com.lgfrir.leddrivebt.protocol.encodeStatic
import com.lgfrir.leddrivebt.protocol.encodeTimeSet
import com.lgfrir.leddrivebt.protocol.encodeWave
import com.lgfrir.leddrivebt.protocol.parseClockStat
import com.lgfrir.leddrivebt.protocol.parseDetail
import com.lgfrir.leddrivebt.protocol.parseDevInfo
import com.lgfrir.leddrivebt.protocol.parseIoModes
import com.lgfrir.leddrivebt.protocol.parseOpResult
import com.lgfrir.leddrivebt.protocol.parsePresetHeads
import com.lgfrir.leddrivebt.protocol.parseTimeState
import com.lgfrir.leddrivebt.protocol.parseTopology
import com.lgfrir.leddrivebt.protocol.parseUsage
import com.lgfrir.leddrivebt.protocol.statusLabel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.UUID

data class ScanHit(val address: String, val name: String)

enum class LinkPhase { Idle, Connecting, Ready, Dropped }

data class ConnectStep(val index: Int, val total: Int, val label: String)

data class BleLogLine(
    val time: String,
    val direction: String,
    val step: String,
    val ok: Boolean,
    val detail: String,
)

class BleException(message: String) : Exception(message)

/**
 * 所有 GATT 操作串行执行。固件要求先写 select，后面的读和写才作用在选中的口或灯段上。
 * 滑条松手或点「写入参数」时才调用这里，避免连续写 NVS。
 */
class LightBleClient(context: Context) {
    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private val gate = Mutex()
    private val seen = LinkedHashMap<String, ScanHit>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /*
     * GATT 回调必须离开主线程。
     * 不传 Handler 时 connectGatt 会用主线程的 Looper 派发
     * onCharacteristicWrite / onCharacteristicRead / onNotify，
     * 界面只要在跑动画（比如每段灯效预览），这些回调就会排在渲染后面，
     * 表现为"手机显示切换成功、灯却过好几秒才动"，
     * 而且延迟忽长忽短（0s / 5s / 8s），完全取决于当时掉不掉帧。
     */
    private val cbThread = HandlerThread("leddrive-gatt").apply { start() }
    private val cbHandler = Handler(cbThread.looper)
    private val results = Channel<Stamped>(Channel.UNLIMITED)
    private var chunkWait: CompletableDeferred<ByteArray>? = null
    private var scanJob: Job? = null
    private var abortConnect = false
    private val logLines = ArrayDeque<BleLogLine>()
    private val clockFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private var gatt: BluetoothGatt? = null
    private var waiter: Waiter? = null
    private var selectedIo: Int? = null
    private var selectedSeg: Int? = null

    private val _devices = MutableStateFlow<List<ScanHit>>(emptyList())
    private val _scanning = MutableStateFlow(false)
    private val _connected = MutableStateFlow(false)
    private val _status = MutableStateFlow("未连接")
    private val _ios = MutableStateFlow<List<IoNode>>(emptyList())
    private val _clockReady = MutableStateFlow(false)
    private val _clockNote = MutableStateFlow("")
    private val _devInfo = MutableStateFlow<DevInfo?>(null)
    private val _presetIndex = MutableStateFlow<List<PresetHead>>(emptyList())
    private val _scheduleIds = MutableStateFlow<List<Int>>(emptyList())
    private val _ioModes = MutableStateFlow<Map<Int, Int>>(emptyMap())
    private val _clockStat = MutableStateFlow<ClockStat?>(null)
    private val _synced = MutableStateFlow(false)
    private val _deviceEpoch = MutableStateFlow(0L)
    private val _epochMark = MutableStateFlow(0L)
    private val _address = MutableStateFlow("")
    private val _peerName = MutableStateFlow("")
    private val _phase = MutableStateFlow(LinkPhase.Idle)
    private val _connectLog = MutableStateFlow<List<BleLogLine>>(emptyList())

    /** 只包含本次连接开始之后的日志，供连接页逐条打印。 */
    val connectLog: StateFlow<List<BleLogLine>> = _connectLog
    private val _connectStep = MutableStateFlow<ConnectStep?>(null)
    private var connectFrom = 0
    private val _dropReason = MutableStateFlow("")
    private val _mtu = MutableStateFlow(23)
    private val _log = MutableStateFlow<List<BleLogLine>>(emptyList())

    val devices: StateFlow<List<ScanHit>> = _devices
    val scanning: StateFlow<Boolean> = _scanning
    val connected: StateFlow<Boolean> = _connected
    val status: StateFlow<String> = _status
    val ios: StateFlow<List<IoNode>> = _ios
    val clockReady: StateFlow<Boolean> = _clockReady
    val clockNote: StateFlow<String> = _clockNote
    val devInfo: StateFlow<DevInfo?> = _devInfo
    val presetIndex: StateFlow<List<PresetHead>> = _presetIndex
    val scheduleIds: StateFlow<List<Int>> = _scheduleIds
    val ioModes: StateFlow<Map<Int, Int>> = _ioModes
    val clockStat: StateFlow<ClockStat?> = _clockStat
    val synced: StateFlow<Boolean> = _synced
    val deviceEpoch: StateFlow<Long> = _deviceEpoch
    val epochMark: StateFlow<Long> = _epochMark
    val address: StateFlow<String> = _address
    val peerName: StateFlow<String> = _peerName
    val phase: StateFlow<LinkPhase> = _phase
    val connectStep: StateFlow<ConnectStep?> = _connectStep
    val dropReason: StateFlow<String> = _dropReason
    val mtu: StateFlow<Int> = _mtu
    val commLog: StateFlow<List<BleLogLine>> = _log

    fun payloadBudget(): Int = (_mtu.value - 3).coerceAtLeast(20)

    fun bluetoothReady(): Boolean = try {
        adapter?.isEnabled == true
    } catch (_: SecurityException) {
        false
    }

    @SuppressLint("MissingPermission")
    fun startScan() {
        if (_scanning.value) return
        val scanner = adapter?.bluetoothLeScanner ?: run {
            _status.value = "这台手机没有低功耗蓝牙"
            return
        }
        if (adapter?.isEnabled != true) {
            _status.value = "请先打开手机蓝牙"
            return
        }
        synchronized(seen) {
            seen.clear()
            _devices.value = emptyList()
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(GattId.IO_SVC)).build()
        scanner.startScan(listOf(filter), settings, scanCallback)
        _scanning.value = true
        _status.value = "正在寻找灯带"
        note("手机→灯带", "扫描", true, "开始")
        scanJob?.cancel()
        scanJob = scope.launch {
            delay(15_000)
            scanJob = null
            finishScan(timedOut = true)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        finishScan(timedOut = false)
    }

    @SuppressLint("MissingPermission")
    private fun finishScan(timedOut: Boolean) {
        if (_scanning.value) {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
            _scanning.value = false
        }
        if (_phase.value == LinkPhase.Connecting || _phase.value == LinkPhase.Ready) return
        if (timedOut && _devices.value.isEmpty()) {
            _status.value = "没有找到灯带。若仍搜不到，请到系统设置打开定位后再试。"
            note("手机→灯带", "扫描", false, "没有找到")
        } else if (_status.value == "正在寻找灯带") {
            _status.value = ""
        }
    }

    fun prepareConnect() {
        abortConnect = false
        _connected.value = false
        _ios.value = emptyList()
        _phase.value = LinkPhase.Connecting
        _dropReason.value = ""
        synchronized(logLines) {
            connectFrom = logLines.size
            _connectLog.value = emptyList()
        }
        showStep(0, "正在连接")
    }

    @SuppressLint("MissingPermission")
    suspend fun connect(address: String) {
        if (abortConnect) throw BleException("已断开")
        var last: Exception? = null
        repeat(2) { attempt ->
            try {
                connectAttempt(address, refreshCache = attempt > 0)
                return
            } catch (error: Exception) {
                if (abortConnect || error is kotlinx.coroutines.CancellationException) throw error
                last = error
                Log.w(TAG, "connect attempt ${attempt + 1}: ${error.message}")
                if (attempt == 0) delay(400)
            }
        }
        throw last ?: BleException("连接失败")
    }

    /* connectGatt(..., Handler) 在 API 33 标了 deprecated（改用 Executor 版），
     * 但 minSdk 是 26，26~32 只能用 Handler 版，这里保留并压掉警告。 */
    /**
     * 设备鉴权：auth_id（16B UUID + 1B 名称长度 + 名称）→ auth_pin（默认 123456）。
     * 已识别过的 UUID 在 auth_id 之后直接放行，不需要再写 PIN。
     */
    private suspend fun authenticate() {
        val uuid = authUuid()
        val name = "Android Phone".toByteArray(Charsets.US_ASCII)
        val payload = ByteArray(16 + 1 + name.size)
        uuid.copyInto(payload, 0)
        payload[16] = name.size.toByte()
        name.copyInto(payload, 17)

        write(GattId.AUTH_SVC, GattId.AUTH_ID, payload)
        var stat = read(GattId.AUTH_SVC, GattId.AUTH_STAT)
        if (stat.isEmpty() || stat[0].toInt() != 1) {
            write(GattId.AUTH_SVC, GattId.AUTH_PIN, "123456".toByteArray(Charsets.US_ASCII))
            stat = read(GattId.AUTH_SVC, GattId.AUTH_STAT)
        }
        if (stat.isEmpty() || stat[0].toInt() != 1) {
            throw BleException("设备鉴权失败：PIN 123456 不正确或未授权")
        }
    }

    /** 本机在设备上的稳定身份 UUID，首次连接生成并持久化。 */
    private fun authUuid(): ByteArray {
        val prefs = appContext.getSharedPreferences("leddrive-auth", Context.MODE_PRIVATE)
        val saved = prefs.getString("uuid", null)
        if (saved != null && saved.length == 32) {
            runCatching {
                return ByteArray(16) { i ->
                    saved.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
            }
        }
        val uuid = UUID.randomUUID()
        val bytes = ByteBuffer.allocate(16)
            .putLong(uuid.mostSignificantBits)
            .putLong(uuid.leastSignificantBits)
            .array()
        prefs.edit()
            .putString("uuid", bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) })
            .apply()
        return bytes
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private suspend fun connectAttempt(address: String, refreshCache: Boolean) {
        val device = adapter?.getRemoteDevice(address)
            ?: throw BleException("找不到这台设备")
        gate.withLock {
            _phase.value = LinkPhase.Connecting
            _dropReason.value = ""
            closeGatt()
            _address.value = address
            val fromScan = _devices.value.firstOrNull { it.address.equals(address, ignoreCase = true) }?.name
                ?.takeIf { it.isNotBlank() && it != "灯带" }
            val fromRadio = runCatching { device.name }.getOrNull()?.takeIf { it.isNotBlank() }
            _peerName.value = fromScan ?: fromRadio ?: "灯带"
            showStep(0, "正在连接")
            val pending = Waiter(CompletableDeferred())
            waiter = pending
            val opened = device.connectGatt(
                appContext,
                false,
                callback,
                BluetoothDevice.TRANSPORT_LE,
                BluetoothDevice.PHY_LE_1M_MASK,
                cbHandler,
            )
            gatt = opened
            try {
                await(pending, 12_000, "连接超时")
                /*
                 * 交互式控制要主动要高连接优先级。
                 * 不设的话 Android 默认 30~50 ms 一个连接事件：
                 * 一次"写 + 读"就 60~100 ms，后台批量读时队列轻松排到几秒。
                 * 设了以后是 11~15 ms。
                 */
                opened.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                note("灯带→手机", "正在连接", true, "链路已建立")
                if (refreshCache) gatt?.let { refreshGattCache(it) }
                showStep(1, "协商 MTU")
                requestMtu()
                showStep(2, "发现服务")
                discover()
                note("灯带→手机", "发现服务", true, "GATT 就绪")
                showStep(3, "设备鉴权")
                authenticate()
                note("灯带→手机", "设备鉴权", true, "已通过")
                showStep(4, "订阅通知")
                enableNotify(GattId.IO_SVC, GattId.IO_TOPOLOGY)
                note("灯带→手机", "订阅通知", true, "io_topology")
                showStep(5, "读取灯段")
                val topology = parseTopology(read(GattId.IO_SVC, GattId.IO_TOPOLOGY))
                note(
                    "灯带→手机",
                    "读取灯段",
                    true,
                    "拓扑 · ${topology.size} 路 / ${topology.sumOf { it.segments.size }} 段",
                )
                showStep(6, "正在对时")
                bootstrapClock()
                /*
                 * 对时会让定时模式按当前时间套上预设。颜色必须在这之后读，
                 * 否则主页第一次显示的是对时前的旧效果。
                 */
                _ios.value = attachModes(topology)
                selectedIo = null
                selectedSeg = null
                _connected.value = true
                showStep(7, "同步调度")
                note("灯带→手机", "连接", true, "完成 · MTU ${_mtu.value}")
            } catch (error: Exception) {
                val reason = if (abortConnect) "已断开" else (error.message ?: "连接失败")
                val step = _connectStep.value?.label ?: "连接"
                gatt?.let { refreshGattCache(it) }
                closeGatt()
                _connected.value = false
                _phase.value = LinkPhase.Dropped
                _connectStep.value = null
                _dropReason.value = reason
                _status.value = reason
                note("灯带→手机", step, false, reason)
                throw error
            }
        }
    }

    fun markReady() {
        if (abortConnect || _phase.value != LinkPhase.Connecting) return
        _phase.value = LinkPhase.Ready
        _connectStep.value = null
        _status.value = ""
        _dropReason.value = ""
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        abortConnect = true
        stopScan()
        _dropReason.value = "已断开"
        _status.value = "已断开"
        _phase.value = LinkPhase.Dropped
        _connectStep.value = null
        _connected.value = false
        _ios.value = emptyList()
        closeGatt()
        _address.value = ""
        note("手机→灯带", "断开", true, "已断开")
    }

    suspend fun setIoBrightness(ioId: Int, brightness: Int) = gate.withLock {
        selectIo(ioId)
        write(GattId.IO_SVC, GattId.IO_BRIGHTNESS, byteArrayOf(brightness.coerceIn(0, 255).toByte()))
        patchIo(ioId) { it.copy(brightness = brightness.coerceIn(0, 255)) }
    }

    /** 只把亮度写到灯上，不改界面上记住的滑条数值。 */
    suspend fun writeIoBrightness(ioId: Int, brightness: Int) = gate.withLock {
        selectIo(ioId)
        write(GattId.IO_SVC, GattId.IO_BRIGHTNESS, byteArrayOf(brightness.coerceIn(0, 255).toByte()))
    }

    /** 所有灯段的动画相位在同一刻归零。 */
    suspend fun resetPhases() = gate.withLock {
        write(GattId.IO_SVC, GattId.PHASE_ZERO, byteArrayOf(0))
    }

    suspend fun setIoRefresh(ioId: Int, refreshMs: Int) = gate.withLock {
        val value = refreshMs.coerceIn(REFRESH_MIN, REFRESH_MAX)
        selectIo(ioId)
        write(
            GattId.IO_SVC,
            GattId.IO_REFRESH,
            byteArrayOf((value and 0xff).toByte(), ((value shr 8) and 0xff).toByte()),
        )
        patchIo(ioId) { it.copy(refreshMs = value) }
    }

    suspend fun loadSegment(segId: Int): SegmentDetail = gate.withLock {
        selectSeg(segId)
        val detail = parseDetail(
            mode = read(GattId.LIGHT_SVC, GattId.LIGHT_MODE).firstUnsigned(),
            brightness = read(GattId.LIGHT_SVC, GattId.SEG_BRIGHTNESS).firstUnsigned(),
            wave = read(GattId.CFG_SVC, GattId.WAVE),
            solid = read(GattId.CFG_SVC, GattId.STATIC),
            breath = read(GattId.CFG_SVC, GattId.BREATH),
            chase = read(GattId.CFG_SVC, GattId.CHASE),
            points = read(GattId.CFG_SVC, GattId.POINTS),
            flash = read(GattId.CFG_SVC, GattId.FLASH),
        )
        patchSegment(segId, detail.mode, tintOf(detail))
        detail
    }

    /** 把一整份灯效写到灯段上。手动模式选预设时用，避免逐个参数来回。 */
    suspend fun applySegmentDetail(segId: Int, detail: SegmentDetail) = gate.withLock {
        selectSeg(segId)
        write(GattId.LIGHT_SVC, GattId.LIGHT_MODE, byteArrayOf(detail.mode.coerceIn(MODE_OFF, MODE_FLASH).toByte()))
        write(GattId.LIGHT_SVC, GattId.SEG_BRIGHTNESS, byteArrayOf(detail.brightness.coerceIn(0, 255).toByte()))
        when (detail.mode) {
            MODE_WAVE -> write(GattId.CFG_SVC, GattId.WAVE, encodeWave(detail.wave))
            MODE_STATIC -> write(GattId.CFG_SVC, GattId.STATIC, encodeStatic(detail.solid))
            MODE_BREATH -> write(GattId.CFG_SVC, GattId.BREATH, encodeBreath(detail.breath))
            MODE_CHASE -> write(GattId.CFG_SVC, GattId.CHASE, encodeChase(detail.chase))
            MODE_POINTS -> write(GattId.CFG_SVC, GattId.POINTS, encodePoints(detail.points))
            MODE_FLASH -> write(GattId.CFG_SVC, GattId.FLASH, encodeFlash(detail.flash))
        }
        patchSegment(segId, detail.mode, tintOf(detail))
    }

    suspend fun setMode(segId: Int, mode: Int) = gate.withLock {
        require(mode in MODE_OFF..MODE_FLASH) { "模式编号无效" }
        selectSeg(segId)
        write(GattId.LIGHT_SVC, GattId.LIGHT_MODE, byteArrayOf(mode.toByte()))
        patchSegment(segId, mode, null)
    }

    suspend fun setSegBrightness(segId: Int, brightness: Int) = gate.withLock {
        selectSeg(segId)
        write(GattId.LIGHT_SVC, GattId.SEG_BRIGHTNESS, byteArrayOf(brightness.coerceIn(0, 255).toByte()))
    }

    suspend fun readIoBrightness(ioId: Int): Int = gate.withLock {
        selectIo(ioId)
        val value = read(GattId.IO_SVC, GattId.IO_BRIGHTNESS).firstUnsigned()
        patchIo(ioId) { it.copy(brightness = value) }
        value
    }

    suspend fun readIoRefresh(ioId: Int): Int = gate.withLock {
        selectIo(ioId)
        val raw = read(GattId.IO_SVC, GattId.IO_REFRESH)
        if (raw.size < 2) throw BleException("刷新读数不完整")
        val value = (raw[0].toInt() and 0xff) or ((raw[1].toInt() and 0xff) shl 8)
        patchIo(ioId) { it.copy(refreshMs = value) }
        value
    }

    suspend fun readSegBrightness(segId: Int): Int = gate.withLock {
        selectSeg(segId)
        read(GattId.LIGHT_SVC, GattId.SEG_BRIGHTNESS).firstUnsigned()
    }

    suspend fun writeWave(segId: Int, bytes: ByteArray) = writeConfig(segId, GattId.WAVE, bytes)
    suspend fun writeStatic(segId: Int, bytes: ByteArray) = writeConfig(segId, GattId.STATIC, bytes)
    suspend fun writeBreath(segId: Int, bytes: ByteArray) = writeConfig(segId, GattId.BREATH, bytes)
    suspend fun writeChase(segId: Int, bytes: ByteArray) = writeConfig(segId, GattId.CHASE, bytes)
    suspend fun writePoints(segId: Int, bytes: ByteArray) = writeConfig(segId, GattId.POINTS, bytes)
    suspend fun writeFlash(segId: Int, bytes: ByteArray) = writeConfig(segId, GattId.FLASH, bytes)

    /* ---------- OTA 控制 ---------- */

    /** 读设备 OTA 信息（JSON）：版本、运行分区、是否支持 OTA。 */
    suspend fun readOtaInfo(): String = gate.withLock {
        readJson(GattId.OTA_SVC, GattId.OTA_INFO)
    }

    /** 读设备 OTA 状态（JSON）：state/progress/read/total/version/error。 */
    suspend fun readOtaStatus(): String = gate.withLock {
        readJson(GattId.OTA_SVC, GattId.OTA_STAT)
    }

    /**
     * 发 START 命令。payload 由 [OtaBleLink] 按固件协议打包。
     * 固件里 ota_cmd 单包限 240 字节，超过时直接拒绝，不交给 BLE 栈去截断。
     */
    suspend fun startOta(payload: ByteArray) = gate.withLock {
        if (payload.isEmpty()) throw BleException("OTA 命令是空的")
        if (payload.size > OTA_CMD_MAX) {
            throw BleException("OTA 命令有 ${payload.size} 字节，设备单包最多收 $OTA_CMD_MAX 字节")
        }
        write(GattId.OTA_SVC, GattId.OTA_CMD, payload)
        note("手机→灯带", "OTA 升级", true, "已发送 START · ${payload.size} 字节")
    }

    /** 发 ABORT 命令。设备没在升级时会拒绝这次写入。 */
    suspend fun abortOta() = gate.withLock {
        write(GattId.OTA_SVC, GattId.OTA_CMD, byteArrayOf(2))
        note("手机→灯带", "OTA 升级", true, "已发送 ABORT")
    }

    private suspend fun readJson(service: UUID, id: UUID): String {
        val raw = read(service, id)
        return String(raw, Charsets.UTF_8).trimEnd('\u0000')
    }

    fun close() {
        stopScan()
        closeGatt()
    }

    private suspend fun writeConfig(segId: Int, characteristic: UUID, bytes: ByteArray) = gate.withLock {
        selectSeg(segId)
        write(GattId.CFG_SVC, characteristic, bytes)
    }

    @SuppressLint("MissingPermission")
    private suspend fun attachModes(ios: List<IoNode>): List<IoNode> {
        return ios.map { io ->
            selectIo(io.id)
            val filled = io.copy(
                segments = io.segments.map { seg ->
                    selectSeg(seg.id)
                    val mode = read(GattId.LIGHT_SVC, GattId.LIGHT_MODE).firstUnsigned()
                    seg.copy(mode = mode, tint = readTint(mode))
                },
            )
            // 每路的模式是 App 自己逐段读回来解析的，这里记一行结果。
            note(
                "灯带→手机",
                "读取灯段",
                true,
                "第 ${io.id} 路 · ${filled.segments.size} 段 · ${filled.segments.sumOf { it.length }} 颗",
            )
            filled
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun selectIo(ioId: Int) {
        if (selectedIo == ioId) return
        write(GattId.IO_SVC, GattId.IO_SELECT, byteArrayOf(ioId.toByte()))
        selectedIo = ioId
    }

    @SuppressLint("MissingPermission")
    private suspend fun selectSeg(segId: Int) {
        if (selectedSeg == segId) return
        write(GattId.LIGHT_SVC, GattId.SEG_SELECT, byteArrayOf(segId.toByte()))
        selectedSeg = segId
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestMtu() {
        val pending = Waiter(CompletableDeferred())
        waiter = pending
        val started = gatt?.requestMtu(247) == true
        if (!started) {
            _mtu.value = 23
            note("手机→灯带", "协商 MTU", false, "请求没有发出，按 23 字节继续")
            return
        }
        try {
            await(pending, 5_000, "MTU 协商超时")
        } catch (error: BleException) {
            _mtu.value = 23
            note("手机→灯带", "协商 MTU", false, error.message ?: "按 23 字节继续")
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun discover() {
        val pending = Waiter(CompletableDeferred())
        waiter = pending
        val started = gatt?.discoverServices() == true
        if (!started) throw BleException("无法开始发现服务")
        await(pending, 8_000, "发现服务超时")
    }

    @SuppressLint("MissingPermission")
    private suspend fun enableNotify(service: UUID, id: UUID) {
        val gatt = gatt ?: throw BleException("连接已经断开")
        val characteristic = characteristic(service, id)
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            throw BleException("无法订阅通知")
        }
        val descriptor = characteristic.getDescriptor(GattId.CCCD)
            ?: throw BleException("特征没有通知描述符")
        val pending = Waiter(CompletableDeferred())
        waiter = pending
        val started = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(descriptor)
        }
        if (!started) throw BleException("无法打开通知")
        await(pending, 5_000, "订阅超时")
    }

    @SuppressLint("MissingPermission")
    private suspend fun read(service: UUID, id: UUID): ByteArray {
        var last: BleException? = null
        repeat(3) { attempt ->
            try {
                return readOnce(service, id)
            } catch (error: BleException) {
                last = error
                val retry = error.message?.startsWith("读取被拒绝") == true && attempt < 2
                if (!retry) throw error
                delay(150L * (attempt + 1))
            }
        }
        throw last ?: BleException("读取被拒绝")
    }

    @SuppressLint("MissingPermission")
    private suspend fun readOnce(service: UUID, id: UUID): ByteArray {
        val gatt = gatt ?: throw BleException("连接已经断开")
        val pending = Waiter(CompletableDeferred())
        waiter = pending
        val started = gatt.readCharacteristic(characteristic(service, id))
        if (!started) throw BleException("读取没有发出去")
        return await(pending, 5_000, "读取超时")
    }

    @SuppressLint("MissingPermission")
    private suspend fun write(service: UUID, id: UUID, value: ByteArray) {
        val gatt = gatt ?: throw BleException("连接已经断开")
        val budget = payloadBudget()
        if (value.size > budget) {
            val message = "这一包有 ${value.size} 字节，当前链路只能发 $budget 字节"
            note("手机→灯带", "写入", false, message)
            throw BleException(message)
        }
        val characteristic = characteristic(service, id)
        val pending = Waiter(CompletableDeferred())
        waiter = pending
        val started = if (Build.VERSION.SDK_INT >= 33) {
            gatt.writeCharacteristic(
                characteristic,
                value,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            ) == BluetoothGatt.GATT_SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION")
            characteristic.value = value
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(characteristic)
        }
        if (!started) throw BleException("写入没有发出去")
        await(pending, 5_000, "写入超时")
    }

    private fun characteristic(serviceId: UUID, id: UUID): BluetoothGattCharacteristic {
        val service = gatt?.getService(serviceId)
            ?: throw BleException("没有找到服务。确认灯带固件已经在广播。")
        return service.getCharacteristic(id)
            ?: throw BleException("没有找到特征 $id")
    }

    private suspend fun await(pending: Waiter, timeoutMs: Long, timeoutMessage: String): ByteArray {
        return try {
            withTimeout(timeoutMs) { pending.gate.await() }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            if (waiter === pending) waiter = null
            throw BleException(timeoutMessage)
        } finally {
            if (waiter === pending && pending.gate.isCompleted) waiter = null
        }
    }

    private fun succeed(value: ByteArray = ByteArray(0)) {
        waiter?.gate?.complete(value)
    }

    private fun fail(message: String) {
        waiter?.gate?.completeExceptionally(BleException(message))
    }

    private fun readDenied(status: Int): String {
        val why = when (status) {
            133 -> "蓝牙栈还没准备好"
            2 -> "这个特征不可读"
            5, 15 -> "加密还没完成"
            8 -> "没有读取权限"
            else -> "状态 $status"
        }
        return "读取被拒绝（$why）"
    }

    private fun refreshGattCache(gatt: BluetoothGatt) {
        runCatching {
            val refresh = gatt.javaClass.getMethod("refresh")
            refresh.invoke(gatt)
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        selectedIo = null
        selectedSeg = null
        chunkWait?.completeExceptionally(BleException("连接已断开"))
        chunkWait = null
        while (results.tryReceive().isSuccess) Unit
        waiter?.gate?.completeExceptionally(BleException("连接已断开"))
        waiter = null
        gatt?.close()
        gatt = null
        clearClock()
    }

    private fun clearClock() {
        _clockReady.value = false
        _clockNote.value = ""
        _devInfo.value = null
        _presetIndex.value = emptyList()
        _scheduleIds.value = emptyList()
        _ioModes.value = emptyMap()
        _clockStat.value = null
        _synced.value = false
        _deviceEpoch.value = 0L
        _epochMark.value = 0L
    }

    private fun patchIo(ioId: Int, block: (IoNode) -> IoNode) {
        _ios.value = _ios.value.map { if (it.id == ioId) block(it) else it }
    }

    private fun patchSegment(segId: Int, mode: Int, tint: Int?) {
        _ios.value = _ios.value.map { io ->
            io.copy(segments = io.segments.map {
                if (it.id == segId) it.copy(mode = mode, tint = tint) else it
            })
        }
    }

    private suspend fun readTint(mode: Int): Int? = try {
        when (mode) {
            MODE_OFF -> 0xFF000000.toInt()
            MODE_STATIC -> argb(parseStatic(read(GattId.CFG_SVC, GattId.STATIC)).color)
            MODE_BREATH -> argb(parseBreath(read(GattId.CFG_SVC, GattId.BREATH)).color)
            MODE_CHASE -> parseChase(read(GattId.CFG_SVC, GattId.CHASE)).colors.firstOrNull()?.let(::argb)
            MODE_POINTS -> parsePoints(read(GattId.CFG_SVC, GattId.POINTS)).points.firstOrNull()
                ?.colors?.firstOrNull()?.let(::argb)
            else -> null
        }
    } catch (error: Exception) {
        if (error is kotlinx.coroutines.CancellationException) throw error
        null
    }

    private fun tintOf(detail: SegmentDetail): Int? = when (detail.mode) {
        MODE_OFF -> 0xFF000000.toInt()
        MODE_STATIC -> argb(detail.solid.color)
        MODE_BREATH -> argb(detail.breath.color)
        MODE_CHASE -> detail.chase.colors.firstOrNull()?.let(::argb)
        MODE_POINTS -> detail.points.points.firstOrNull()?.colors?.firstOrNull()?.let(::argb)
        else -> null
    }

    private fun argb(color: Rgb): Int =
        (0xFF shl 24) or (color.r.coerceIn(0, 255) shl 16) or (color.g.coerceIn(0, 255) shl 8) or color.b.coerceIn(0, 255)

    private fun mergeTopology(fresh: List<IoNode>) {
        val old = _ios.value.flatMap { it.segments }.associateBy { it.id }
        _ios.value = fresh.map { io ->
            io.copy(segments = io.segments.map { seg ->
                val prev = old[seg.id]
                seg.copy(mode = prev?.mode, tint = prev?.tint)
            })
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!matches(result)) return
            val name = result.scanRecord?.deviceName?.takeIf { it.isNotBlank() }
                ?: result.device.name?.takeIf { it.isNotBlank() }
                ?: "灯带"
            synchronized(seen) {
                seen[result.device.address] = ScanHit(result.device.address, name)
                _devices.value = seen.values.toList()
            }
        }

        override fun onScanFailed(errorCode: Int) {
            _scanning.value = false
            _status.value = "扫描失败（$errorCode）"
        }
    }

    private fun matches(result: ScanResult): Boolean {
        val raw = result.scanRecord?.bytes ?: return false
        return raw.indexOfSlice(GattId.IO_SVC_LE) >= 0
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                if (status == BluetoothGatt.GATT_SUCCESS) succeed() else fail("连接失败（$status）")
                return
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val reason = if (abortConnect || status == BluetoothGatt.GATT_SUCCESS) {
                    "连接已断开"
                } else {
                    "连接失败（$status）"
                }
                fail(reason)
                _connected.value = false
                if (_phase.value == LinkPhase.Ready) {
                    _dropReason.value = if (abortConnect) "已断开" else reason
                    _status.value = _dropReason.value
                    _phase.value = LinkPhase.Dropped
                    _connectStep.value = null
                    scope.launch { finishDrop() }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "mtu=$mtu status=$status")
            if (status == BluetoothGatt.GATT_SUCCESS) {
                _mtu.value = mtu
                note("灯带→手机", "协商 MTU", true, "$mtu")
                succeed()
            } else {
                fail("MTU 协商失败（$status）")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) succeed() else fail("发现服务失败（$status）")
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) succeed() else fail("订阅失败（$status）")
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) {
            finishRead(characteristic.uuid, value, status)
        }

        @Deprecated("API 33 之前的回调")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION")
            finishRead(characteristic.uuid, characteristic.value ?: ByteArray(0), status)
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (status == BluetoothGatt.GATT_SUCCESS) succeed() else fail("设备拒绝了这次写入（$status）")
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            onNotify(characteristic.uuid, value)
        }

        @Deprecated("API 33 之前的回调")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT >= 33) return
            @Suppress("DEPRECATION")
            onNotify(characteristic.uuid, characteristic.value ?: return)
        }
    }

    private fun finishRead(uuid: UUID, value: ByteArray, status: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS) {
            succeed(value.copyOf())
        } else {
            Log.w(TAG, "read $uuid failed $status")
            fail(readDenied(status))
        }
    }

    private fun onNotify(uuid: UUID, value: ByteArray) {
        val copy = value.copyOf()
        when (uuid) {
            GattId.IO_TOPOLOGY -> {
                try {
                    mergeTopology(parseTopology(copy))
                } catch (error: PacketException) {
                    Log.w(TAG, "topology notify ignored: ${error.message}")
                }
            }
            GattId.TIME_REQ -> scope.launch { answerTimeRequest() }
            GattId.PRST_DAT -> chunkWait?.complete(copy)
            GattId.RESP_DAT -> {
                try {
                    results.trySend(Stamped(SystemClock.elapsedRealtime(), parseOpResult(copy)))
                } catch (error: PacketException) {
                    Log.w(TAG, "result notify ignored: ${error.message}")
                    note("灯带→手机", "结果", false, error.message ?: "结果解析失败")
                }
            }
        }
    }

    private class Waiter(val gate: CompletableDeferred<ByteArray>)

    private fun ByteArray.firstUnsigned(): Int {
        if (isEmpty()) throw BleException("读到的数据是空的")
        return this[0].toInt() and 0xff
    }

    suspend fun refreshClock() = gate.withLock { reloadClockLocked() }

    suspend fun readPreset(id: Int): PresetRecord = gate.withLock {
        val buf = ByteArrayOutputStream()
        var offset = 0
        var guard = 0
        while (guard++ < 8) {
            val packet = pullChunk(id, offset)
            if (packet.size < 6) throw BleException("预设分块长度不对")
            val total = u16(packet, 3)
            val len = packet[5].toInt() and 0xff
            if (total == 0) {
                if (offset == 0) throw BleException("预设不存在")
                break
            }
            if (packet.size < 6 + len) throw BleException("预设分块被截断")
            buf.write(packet, 6, len)
            offset += len
            if (offset >= total) break
        }
        decodeRecord(id, buf.toByteArray())
    }

    suspend fun writePreset(id: Int, name: String, detail: SegmentDetail): OpResult = gate.withLock {
        val result = command(RESP_PRESET, "保存预设") {
            sendChunks(GattId.PRST_SVC, GattId.PRST_WRT, id, encodeRecord(name, detail))
        }
        if (result.status != 0) throw BleException(statusLabel(result.status))
        _presetIndex.value = parsePresetHeads(read(GattId.PRST_SVC, GattId.PRST_LST))
        reloadRuntimeLocked()
        result
    }

    suspend fun presetUsage(id: Int): UsageReport = gate.withLock {
        write(GattId.PRST_SVC, GattId.PRST_DEL, byteArrayOf(id.toByte()))
        parseUsage(read(GattId.PRST_SVC, GattId.PRST_DEL))
    }

    suspend fun confirmDeletePreset(id: Int): OpResult = gate.withLock {
        val result = command(RESP_PRESET_DEL, "删除预设") {
            write(GattId.PRST_SVC, GattId.PRST_DOK, byteArrayOf(id.toByte()))
        }
        if (result.status != 0) throw BleException(statusLabel(result.status))
        _presetIndex.value = parsePresetHeads(read(GattId.PRST_SVC, GattId.PRST_LST))
        reloadRuntimeLocked()
        result
    }

    suspend fun readSchedule(id: Int): ScheduleRecord = gate.withLock {
        write(GattId.SCHD_SVC, GattId.SCHD_GET, byteArrayOf(id.toByte()))
        decodeSchedule(read(GattId.SCHD_SVC, GattId.SCHD_GET))
    }

    suspend fun writeSchedule(id: Int, name: String, atSec: List<Int>): OpResult = gate.withLock {
        val result = command(RESP_SCHED, "保存调度") {
            write(GattId.SCHD_SVC, GattId.SCHD_SET, encodeSchedule(id, name, atSec))
        }
        if (result.status != 0) throw BleException(statusLabel(result.status))
        _scheduleIds.value = parseIdList(read(GattId.SCHD_SVC, GattId.SCHD_LST))
        reloadRuntimeLocked()
        result
    }

    suspend fun scheduleUsage(id: Int): UsageReport = gate.withLock {
        write(GattId.SCHD_SVC, GattId.SCHD_DEL, byteArrayOf(id.toByte()))
        parseUsage(read(GattId.SCHD_SVC, GattId.SCHD_DEL))
    }

    suspend fun confirmDeleteSchedule(id: Int): OpResult = gate.withLock {
        val result = command(RESP_SCHED_DEL, "删除调度") {
            write(GattId.SCHD_SVC, GattId.SCHD_DOK, byteArrayOf(id.toByte()))
        }
        if (result.status != 0) throw BleException(statusLabel(result.status))
        _scheduleIds.value = parseIdList(read(GattId.SCHD_SVC, GattId.SCHD_LST))
        reloadRuntimeLocked()
        result
    }

    suspend fun readBinding(segId: Int): SegBinding = gate.withLock {
        write(GattId.SCHD_SVC, GattId.BIND_GET, byteArrayOf(segId.toByte()))
        decodeBinding(read(GattId.SCHD_SVC, GattId.BIND_GET))
    }

    suspend fun writeBinding(binding: SegBinding): OpResult = gate.withLock {
        val result = command(RESP_BIND, "写入绑定") {
            write(GattId.SCHD_SVC, GattId.BIND_SET, encodeBinding(binding))
        }
        if (result.status != 0) throw BleException(statusLabel(result.status))
        reloadRuntimeLocked()
        result
    }

    fun stageIoMode(io: Int, mode: Int) {
        _ioModes.value = _ioModes.value + (io to mode)
    }

    fun replaceIoModes(modes: Map<Int, Int>) {
        _ioModes.value = modes
    }

    suspend fun setClockIoMode(io: Int, mode: Int): OpResult = gate.withLock {
        val result = command(RESP_IO_MODE, "切换模式") {
            write(GattId.SCHD_SVC, GattId.IO_MODE, encodeIoMode(io, mode))
        }
        if (result.status != 0) throw BleException(statusLabel(result.status))
        /* 固件在 resp_dat type=7 里回了 a=io、b=mode，本地直接落值，不必再读 io_mode */
        _ioModes.value = _ioModes.value + (io to (result.b and 0xff))
        /*
         * 再补一次 clk_stat：切到时间模式后 nextEvent 和各段当前预设会变，
         * 不读的话界面上的"下一时间点"会停在旧值。
         * 合计 2 个 GATT 往返：1 次写 + 1 次读。
         * time_state 与模式无关，用 deviceEpoch + epochMark 外推即可，不重读。
         */
        reloadClockStatLocked()
        result
    }

    suspend fun commitDraft(segId: Int, save: Boolean): OpResult = gate.withLock {
        val result = command(RESP_DRAFT, "草稿") {
            write(
                GattId.SCHD_SVC,
                GattId.EDIT_CMT,
                byteArrayOf(segId.toByte(), if (save) 1 else 0),
            )
        }
        if (result.status != 0) throw BleException(statusLabel(result.status))
        if (save) _presetIndex.value = parsePresetHeads(read(GattId.PRST_SVC, GattId.PRST_LST))
        reloadRuntimeLocked()
        result
    }

    private suspend fun bootstrapClock() {
        try {
            enableNotify(GattId.TIME_SVC, GattId.TIME_REQ)
            enableNotify(GattId.PRST_SVC, GattId.RESP_DAT)
            enableNotify(GattId.PRST_SVC, GattId.PRST_DAT)
            syncClockLocked()
            reloadClockLocked()
            _clockReady.value = true
            _clockNote.value = ""
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            _clockReady.value = false
            _clockNote.value = (error as? BleException)?.message ?: "定时暂时不可用，灯还可以手动控制"
            note("灯带→手机", "正在对时", false, _clockNote.value)
            Log.w(TAG, "clock bootstrap skipped: ${_clockNote.value}")
        }
    }

    private suspend fun answerTimeRequest() {
        try {
            gate.withLock {
                if (gatt == null) return@withLock
                syncClockLocked()
            }
        } catch (error: Exception) {
            Log.w(TAG, "time_req failed: ${error.message}")
        }
    }

    private suspend fun reloadClockLocked() {
        _devInfo.value = parseDevInfo(read(GattId.SCHD_SVC, GattId.DEVINFO))
        _presetIndex.value = parsePresetHeads(read(GattId.PRST_SVC, GattId.PRST_LST))
        _scheduleIds.value = parseIdList(read(GattId.SCHD_SVC, GattId.SCHD_LST))
        _ioModes.value = parseIoModes(read(GattId.SCHD_SVC, GattId.IO_MODE))
        reloadRuntimeLocked()
    }

    private suspend fun reloadRuntimeLocked() {
        reloadClockStatLocked()
        readTimeStateLocked()
    }

    /* 只刷运行状态，不读 time_state。切换模式这类操作只需要这一条。 */
    private suspend fun reloadClockStatLocked() {
        _clockStat.value = parseClockStat(read(GattId.SCHD_SVC, GattId.CLK_STAT))
    }

    /** 先读灯带现在的时间，再写入手机时间。调用方必须已经拿着 GATT 锁。 */
    private suspend fun syncClockLocked() {
        val before = try {
            parseTimeState(read(GattId.TIME_SVC, GattId.TIME_STATE))
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            null
        }
        val phoneMs = System.currentTimeMillis()
        write(GattId.TIME_SVC, GattId.TIME_SET, encodeTimeSet(phoneMs))
        readTimeStateLocked()
        note("灯带→手机", "正在对时", true, correctionText(before, phoneMs / 1000))
    }

    private fun correctionText(before: Pair<Boolean, Long>?, phoneSec: Long): String {
        val old = before?.takeIf { it.first && it.second > 0L }?.second ?: return "原先没有时间"
        val delta = phoneSec - old
        if (delta == 0L) return "时间一致"
        val sign = if (delta > 0) "+" else "−"
        return "纠正 $sign${formatSpan(kotlin.math.abs(delta))}"
    }

    private fun formatSpan(total: Long): String {
        var rest = total
        val days = rest / 86_400
        rest %= 86_400
        val hours = rest / 3_600
        rest %= 3_600
        val mins = rest / 60
        val secs = rest % 60
        val parts = ArrayList<String>(4)
        if (days > 0) parts.add("${days}天")
        if (hours > 0) parts.add("${hours}小时")
        if (mins > 0) parts.add("${mins}分")
        if (secs > 0 || parts.isEmpty()) parts.add("${secs}秒")
        return parts.joinToString("")
    }

    private suspend fun readTimeStateLocked() {
        val (valid, now) = parseTimeState(read(GattId.TIME_SVC, GattId.TIME_STATE))
        _synced.value = valid
        _deviceEpoch.value = now
        _epochMark.value = android.os.SystemClock.elapsedRealtime()
    }

    private suspend fun pullChunk(id: Int, offset: Int): ByteArray {
        val pending = CompletableDeferred<ByteArray>()
        chunkWait = pending
        val req = byteArrayOf(
            id.toByte(),
            (offset and 0xff).toByte(),
            ((offset shr 8) and 0xff).toByte(),
        )
        return try {
            write(GattId.PRST_SVC, GattId.PRST_RED, req)
            withTimeout(5_000) { pending.await() }
        } finally {
            if (chunkWait === pending) chunkWait = null
        }
    }

    private suspend fun sendChunks(service: UUID, id: UUID, target: Int, payload: ByteArray) {
        var offset = 0
        while (offset < payload.size) {
            val n = minOf(CHUNK, payload.size - offset)
            val pkt = ByteArray(5 + n)
            pkt[0] = target.toByte()
            putU16(pkt, 1, payload.size)
            putU16(pkt, 3, offset)
            payload.copyInto(pkt, 5, offset, offset + n)
            write(service, id, pkt)
            offset += n
        }
    }

    /*
     * 发一条"写 + 等结果"的命令。
     *
     * 固件是在 GATT 写回调里【先发 resp_dat 通知、再回 ATT 写响应】的：
     *     send_result(conn, 7, ...);   // 通知先出
     *     return 0;                    // 写响应后出
     * 所以通知经常比 onCharacteristicWrite 还早到。
     *
     * 时间基准必须在 send() 之前取。取在 write() 返回之后（旧实现），
     * 刚到的通知时间戳反而更早，会被当成"上一条的残留"丢掉，
     * 然后一直等到 8 s 超时；超时期间 gate 被占着，
     * 下一次用户操作就要排队约 8 s 才发得出去。
     * 这就是"第一次切换 0 s、之后每次 5~8 s"的真正来源。
     */
    private suspend fun command(type: Int, step: String, send: suspend () -> Unit): OpResult {
        /* 清掉上一条命令超时后可能残留的结果，避免被当成这次的响应 */
        while (results.tryReceive().isSuccess) {
            /* drop */
        }
        val since = SystemClock.elapsedRealtime()
        send()
        return takeResult(type, step, since)
    }

    private suspend fun takeResult(type: Int, step: String, since: Long): OpResult {
        while (true) {
            val stale = results.tryReceive().getOrNull() ?: break
            if (stale.at >= since && stale.result.type == type) return finishResult(stale.result, step)
        }
        return try {
            withTimeout(8_000) {
                var found: OpResult? = null
                while (found == null) {
                    val item = results.receive()
                    if (item.at >= since && item.result.type == type) found = item.result
                }
                val ready = found ?: throw BleException("设备没有返回结果")
                finishResult(ready, step)
            }
        } catch (error: kotlinx.coroutines.TimeoutCancellationException) {
            note("灯带→手机", step, false, "设备没有返回结果")
            throw BleException("设备没有返回结果")
        }
    }

    private fun finishResult(result: OpResult, step: String): OpResult {
        val ok = result.status == 0
        note("灯带→手机", step, ok, if (ok) result.name ?: "成功" else statusLabel(result.status))
        return result
    }

    private fun showStep(index: Int, label: String) {
        _connectStep.value = ConnectStep(index, 7, label)
        _status.value = label
    }

    private fun note(direction: String, step: String, ok: Boolean, detail: String) {
        val time = synchronized(clockFormat) { clockFormat.format(Date()) }
        val line = BleLogLine(time, direction, step, ok, detail)
        synchronized(logLines) {
            logLines.addLast(line)
            while (logLines.size > 500) {
                logLines.removeFirst()
                if (connectFrom > 0) connectFrom--
            }
            _log.value = logLines.toList()
            // 连接页只关心本次连接之后新增的行
            _connectLog.value = if (connectFrom in 0..logLines.size) {
                logLines.drop(connectFrom)
            } else {
                emptyList()
            }
        }
    }

    private suspend fun finishDrop() {
        val reason = _dropReason.value.ifEmpty { "连接已断开" }
        gate.withLock {
            closeGatt()
            _connected.value = false
            _ios.value = emptyList()
            _phase.value = LinkPhase.Dropped
            _connectStep.value = null
            _dropReason.value = reason
            _status.value = reason
        }
        note("灯带→手机", "连接", false, reason)
    }

    private class Stamped(val at: Long, val result: OpResult)

    private fun parseIdList(raw: ByteArray): List<Int> {
        if (raw.isEmpty()) return emptyList()
        val count = raw[0].toInt() and 0xff
        if (raw.size < 1 + count) throw BleException("列表被截断")
        return List(count) { raw[1 + it].toInt() and 0xff }
    }

    private fun u16(raw: ByteArray, index: Int): Int =
        (raw[index].toInt() and 0xff) or ((raw[index + 1].toInt() and 0xff) shl 8)

    private fun putU16(raw: ByteArray, index: Int, value: Int) {
        raw[index] = (value and 0xff).toByte()
        raw[index + 1] = ((value shr 8) and 0xff).toByte()
    }

    companion object {
        private const val TAG = "LEDDriveBT"
    }
}

private fun ByteArray.indexOfSlice(needle: ByteArray): Int {
    if (needle.isEmpty() || size < needle.size) return -1
    for (start in 0..size - needle.size) {
        var matched = true
        for (offset in needle.indices) {
            if (this[start + offset] != needle[offset]) {
                matched = false
                break
            }
        }
        if (matched) return start
    }
    return -1
}
