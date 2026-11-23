package com.lgfrir.leddrivebt.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.lgfrir.leddrivebt.ble.OtaLocalServer
import com.lgfrir.leddrivebt.ui.theme.AppType
import com.lgfrir.leddrivebt.ui.theme.MinTouchTarget
import com.lgfrir.leddrivebt.ui.theme.Radius
import com.lgfrir.leddrivebt.ui.theme.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import kotlin.math.roundToInt

/*
 * OTA 固件升级页面。
 *
 * 两种升级方式都通过 BLE 接通（见 [OtaBleLink]）：
 *   - 远程服务器：把 WiFi 和 HTTPS 地址打包成 START 命令，由设备自己下载；
 *   - 手机本地服务：先把固件复制到手机缓存，启动本机 HTTP 服务，再把手机 WiFi IPv4、
 *     端口、随机 token 和文件 SHA-256 打包进 START，设备连同一个 WiFi 来手机下载。
 * 两种方式都由设备自己写固件；APP 每秒读一次 ota_stat 驱动阶段和进度，
 * 本地服务在升级结束、失败或取消时关闭。手机热点（LocalOnlyHotspot）模式下一步再做，
 * 当前只支持手机和灯带连同一个 WiFi，界面不会把它说成热点已经实现。
 */

/**
 * 固件来源。
 *
 * [Hotspot] 这个名字为了和已有代码/协议保持一致而保留；当前实现的是
 * “手机和灯带连同一个 WiFi，手机当本地 HTTP 服务器”，不是手机开热点。
 */
enum class OtaSource(val label: String, val blurb: String) {
    Server("远程服务器", "设备按 HTTPS 地址下载固件"),
    Hotspot("手机本地服务", "手机热点或同一 WiFi 提供固件"),
}

/** 本地升级的网络方式：手机自己开热点，或手机和灯带连同一个 WiFi。 */
enum class OtaLocalMode(val label: String) {
    Hotspot("手机热点（手动开启）"),
    SameWifi("同一 WiFi"),
}

/** 页面上的升级阶段。 */
enum class OtaPhase(val label: String) {
    Idle("空闲"),
    Checking("检查中"),
    Starting("启动中"),
    Downloading("下载中"),
    Verifying("校验中"),
    Success("成功"),
    Failed("失败");

    val busy: Boolean
        get() = this == Checking || this == Starting || this == Downloading || this == Verifying
}

/** OTA 页面状态。设备版本、检查结果、下载进度都落在这里。 */
data class OtaUiState(
    val phase: OtaPhase = OtaPhase.Idle,
    val source: OtaSource = OtaSource.Server,
    /** 本地模式：手机热点（手动开启）或同一 WiFi。 */
    val localMode: OtaLocalMode = OtaLocalMode.Hotspot,
    /** 远程模式：固件服务器地址，必须以 https:// 开头。 */
    val serverUrl: String = "",
    /** 设备要连接的 WiFi/热点名称；远程和本地模式都必填。 */
    val wifiSsid: String = "",
    /** WiFi/热点密码；开放网络可以留空。 */
    val wifiPassword: String = "",
    /** 本地模式：手机端固件服务的端口。 */
    val localPort: Int = OtaLocalServer.DEFAULT_PORT,
    /** 本地模式：已经复制到手机缓存的固件。只放文件和摘要，不放文件字节。 */
    val localFile: OtaLocalFirmware? = null,
    /** 本地模式：最近一次读到的手机热点/WiFi IPv4。null = 读不到。 */
    val localIp: String? = null,
    /** 本地模式：本机 HTTP 服务是否在运行（只在升级期间为 true）。 */
    val localServiceRunning: Boolean = false,
    /** 本地模式：本机 HTTP 服务地址，运行期间非空，方便排查。 */
    val localServiceUrl: String? = null,
    /** 设备当前固件版本。null = 还没读到。 */
    val currentVersion: String? = null,
    /** 检查更新后拿到的目标版本。 */
    val targetVersion: String? = null,
    /** 0f..1f。 */
    val progress: Float = 0f,
    /** 最近一次操作的结果或说明，直接显示在状态区。 */
    val message: String = "",
)

/** 本地持久化的 OTA 输入，不含固件文件。 */
data class OtaSaved(
    val ssid: String = "",
    val password: String = "",
    val localMode: OtaLocalMode = OtaLocalMode.Hotspot,
    val serverUrl: String = "",
)

/** 真正交给设备端的请求。字段顺序和固件 START 命令一致。 */
data class OtaRequest(
    val source: OtaSource,
    val localMode: OtaLocalMode = OtaLocalMode.Hotspot,
    val serverUrl: String = "",
    val wifiSsid: String = "",
    val wifiPassword: String = "",
    val localPort: Int = OtaLocalServer.DEFAULT_PORT,
    /** 本地模式：已经导入缓存的固件和摘要；远程模式为 null。 */
    val localFirmware: OtaLocalFirmware? = null,
)

/**
 * 设备端 OTA 接线面。[OtaController.link] 由 [OtaBleLink] 实现：
 * 通过 BLE 读写 ota_info / ota_stat / ota_cmd，并用 controller 的 begin/finish 方法回写状态。
 */
interface OtaLink {
    fun readVersion()
    fun checkUpdate(request: OtaRequest)
    fun startUpdate(request: OtaRequest)
    fun cancelUpdate()
}

/** OtaScreen 对外只依赖这一组回调，接 BLE 时换 controller 的 link，不用动界面。 */
data class OtaActions(
    val onSource: (OtaSource) -> Unit,
    val onLocalMode: (OtaLocalMode) -> Unit,
    val onServerUrl: (String) -> Unit,
    val onWifiSsid: (String) -> Unit,
    val onWifiPassword: (String) -> Unit,
    /** 文件选择器已经拿到 Uri，开始复制并算 SHA-256。 */
    val onLocalImportStarted: () -> Unit,
    /** 固件复制完成，把文件名/大小/SHA-256 交给状态机。 */
    val onLocalFirmware: (OtaLocalFirmware) -> Unit,
    /** 导入失败、用户取消、或读不到 WiFi 时的真实错误。 */
    val onLocalFileProblem: (String) -> Unit,
    /** 重新读一次手机 WiFi IPv4。 */
    val onRefreshLocalNetwork: () -> Unit,
    val onReadVersion: () -> Unit,
    val onCheck: () -> Unit,
    val onStart: () -> Unit,
    val onCancel: () -> Unit,
)

/**
 * OTA 状态机 + 接线点。
 *
 * link 默认由 LightViewModel 装成 [OtaBleLink]；没装时点按钮只会得到一句
 * “设备端 OTA 服务尚未接入”的提示。接线后由 [OtaLink] 的实现调用 [beginCheck] 等方法推进状态，
 * 界面自动跟随。
 *
 * [wifiIpv4] 是手机 WiFi IPv4 的读取器，由 LightViewModel 用 Application Context 注入；
 * 读不到返回 null，界面和 [requestOrNull] 都会给出“请先连接 WiFi”的真实提示。
 */
class OtaController(
    private val wifiIpv4: (() -> String?)? = null,
    private val hotspotIpv4: (() -> String?)? = null,
    initial: OtaSaved? = null,
    private val onSave: ((OtaSaved) -> Unit)? = null,
) {
    var state by mutableStateOf(
        OtaUiState(
            localMode = initial?.localMode ?: OtaLocalMode.Hotspot,
            serverUrl = initial?.serverUrl ?: "",
            wifiSsid = initial?.ssid ?: "",
            wifiPassword = initial?.password ?: "",
        ),
    )
        private set

    private fun persist() {
        onSave?.invoke(
            OtaSaved(
                ssid = state.wifiSsid,
                password = state.wifiPassword,
                localMode = state.localMode,
                serverUrl = state.serverUrl,
            ),
        )
    }

    /**
     * 设备端/传输层实现。LightViewModel 里赋成 [OtaBleLink]。
     */
    var link: OtaLink? = null

    val actions: OtaActions = OtaActions(
        onSource = { selectSource(it) },
        onLocalMode = { selectLocalMode(it) },
        onServerUrl = { setServerUrl(it) },
        onWifiSsid = { setWifiSsid(it) },
        onWifiPassword = { setWifiPassword(it) },
        onLocalImportStarted = { localImportStarted() },
        onLocalFirmware = { setLocalFirmware(it) },
        onLocalFileProblem = { localFileProblem(it) },
        onRefreshLocalNetwork = { refreshLocalNetwork() },
        onReadVersion = { readVersion() },
        onCheck = { check() },
        onStart = { start() },
        onCancel = { cancel() },
    )

    /** 切换升级方式。升级进行中不允许切换，避免请求参数和界面不一致。 */
    fun selectSource(source: OtaSource) {
        if (state.phase.busy || state.source == source) return
        state = state.copy(source = source, message = "")
        if (source == OtaSource.Hotspot) refreshLocalNetwork(quiet = true)
    }

    fun selectLocalMode(mode: OtaLocalMode) {
        if (state.phase.busy || state.localMode == mode) return
        state = state.copy(localMode = mode, message = "")
        persist()
        refreshLocalNetwork(quiet = true)
    }

    fun setServerUrl(url: String) {
        state = state.copy(serverUrl = url, message = "")
        persist()
    }

    fun setWifiSsid(ssid: String) {
        state = state.copy(wifiSsid = ssid, message = "")
        persist()
    }

    fun setWifiPassword(password: String) {
        state = state.copy(wifiPassword = password, message = "")
        persist()
    }

    /** 手机 WiFi IPv4 的当前值，取自注入的读取器；OtaBleLink 也用它拼 URL。 */
    fun currentWifiIpv4(): String? = wifiIpv4?.invoke()

    /** 当前本地模式对应的 IPv4：热点模式读 AP 网卡，同一 WiFi 模式读 WiFi 网卡。 */
    fun currentLocalIp(mode: OtaLocalMode = state.localMode): String? = when (mode) {
        OtaLocalMode.Hotspot -> hotspotIpv4?.invoke()
        OtaLocalMode.SameWifi -> wifiIpv4?.invoke()
    }

    /**
     * 重新读一次手机热点/WiFi IPv4。[quiet] = true 时只更新地址不写提示，
     * 用于用户切到本地模式时静默刷新。
     */
    fun refreshLocalNetwork(quiet: Boolean = false) {
        if (state.phase.busy) return
        val ip = currentLocalIp()
        state = state.copy(
            localIp = ip,
            message = when {
                quiet -> state.message
                ip == null && state.localMode == OtaLocalMode.Hotspot ->
                    "没有检测到手机热点 IP。请先在系统设置里打开热点（2.4GHz），再点“刷新热点 IP”。"
                ip == null ->
                    "没有读到手机 WiFi 的 IPv4 地址。请先让手机连接 WiFi，再试本地升级。"
                state.localMode == OtaLocalMode.Hotspot -> "手机热点地址：$ip"
                else -> "手机当前 WiFi 地址：$ip"
            },
        )
    }

    /** 文件选择器返回后，复制固件期间的状态提示。 */
    fun localImportStarted() {
        if (state.phase.busy) return
        state = state.copy(message = "正在把固件复制到手机缓存并计算 SHA-256…")
    }

    /** 固件导入完成。本地模式下的“选择文件”真正落到这里。 */
    fun setLocalFirmware(firmware: OtaLocalFirmware) {
        if (state.phase.busy) return
        state = state.copy(
            localFile = firmware,
            localIp = currentLocalIp() ?: state.localIp,
            message = "已选择 ${firmware.name}（${formatByteCount(firmware.size)}），" +
                "SHA-256 ${firmware.sha256.take(16)}…",
        )
    }

    fun localFileProblem(message: String) {
        if (state.phase.busy) return
        state = state.copy(message = message.ifBlank { "读取固件文件失败。" })
    }

    /**
     * 本地 HTTP 服务已启动。由 [OtaBleLink] 在 START 发出去之前调用，
     * 页面据此显示服务状态和设备要访问的 URL。
     */
    fun localServiceStarted(ip: String, url: String) {
        state = state.copy(localIp = ip, localServiceRunning = true, localServiceUrl = url)
    }

    /** 本地服务已关闭。终态、失败、取消都会走到这里。 */
    fun localServiceStopped() {
        state = state.copy(localServiceRunning = false, localServiceUrl = null)
    }

    /**
     * 同步设备版本。LightApp 把连接时读到的 DevInfo.fw 传进来；
     * 点“读取版本”时 [OtaBleLink] 也会把 ota_info 里的版本同步进来。
     */
    fun applyDeviceVersion(version: String?) {
        val next = version?.trim()?.takeIf { it.isNotEmpty() }
        if (state.currentVersion != next) state = state.copy(currentVersion = next)
    }

    fun readVersion() {
        if (state.phase.busy) return
        val link = link
        if (link == null) {
            state = state.copy(
                message = "设备端还没提供 OTA 版本读取命令。上面显示的是连接灯带时从设备信息里读到的版本。",
            )
            return
        }
        state = state.copy(message = "正在从设备读取版本…")
        link.readVersion()
    }

    fun check() {
        if (state.phase.busy) return
        val link = link
        if (link == null) {
            state = state.copy(
                phase = OtaPhase.Idle,
                progress = 0f,
                targetVersion = null,
                message = "设备端 OTA 服务尚未接入，本次没有发起任何检查。",
            )
            return
        }
        val request = requestOrNull() ?: return
        state = state.copy(
            phase = OtaPhase.Checking,
            progress = 0f,
            targetVersion = null,
            message = "正在检查更新…",
        )
        link.checkUpdate(request)
    }

    fun start() {
        if (state.phase.busy) return
        val link = link
        if (link == null) {
            state = state.copy(
                phase = OtaPhase.Idle,
                progress = 0f,
                message = "设备端 OTA 服务尚未接入，没有开始升级，设备固件没有被改动。",
            )
            return
        }
        val request = requestOrNull() ?: return
        beginStart()
        link.startUpdate(request)
    }

    fun cancel() {
        if (!state.phase.busy) {
            state = state.copy(message = "当前没有正在进行的升级。")
            return
        }
        val link = link
        if (link == null) {
            state = state.copy(
                phase = OtaPhase.Idle,
                progress = 0f,
                message = "设备端 OTA 服务尚未接入，没有可取消的升级。",
            )
            return
        }
        /*
         * 先回到空闲，避免轮询继续写阶段；ABORT 的结果由 OtaBleLink 通过
         * note / finishAborted / finishFailure 回写。这里不断言“已取消”，
         * 因为设备是否真的停下来要等它回状态。
         */
        state = state.copy(
            phase = OtaPhase.Idle,
            progress = 0f,
            message = "已向设备发送取消请求，等设备确认…",
        )
        link.cancelUpdate()
    }

    /* ---------- 以下方法供 OtaLink 实现调用，用来驱动界面 ---------- */

    fun beginCheck() {
        state = state.copy(phase = OtaPhase.Checking, progress = 0f, message = "正在检查更新…")
    }

    /** 正在把 START 命令发给设备。 */
    fun beginStart() {
        state = state.copy(phase = OtaPhase.Starting, progress = 0f, message = "正在向设备发送升级命令…")
    }

    /** 设备已接受 START，正在连 WiFi。 */
    fun beginConnectingWifi() {
        state = state.copy(phase = OtaPhase.Starting, progress = 0f, message = "设备正在连接 WiFi…")
    }

    /** 只改说明文字，不动阶段。用于进度说明和读取版本的结果。 */
    fun note(message: String) {
        state = state.copy(message = message)
    }

    fun beginDownload() {
        state = state.copy(phase = OtaPhase.Downloading, progress = 0f, message = "正在下载固件…")
    }

    fun updateProgress(value: Float) {
        state = state.copy(progress = value.coerceIn(0f, 1f))
    }

    fun beginVerify() {
        state = state.copy(phase = OtaPhase.Verifying, progress = 1f, message = "正在校验固件…")
    }

    /** 检查结束但设备端没有清单接口，拿不到目标版本。 */
    fun finishCheck(message: String) {
        state = state.copy(phase = OtaPhase.Idle, progress = 0f, message = message)
    }

    /** 新固件已写入，等设备重启。设备端没有回报目标版本，重启后要重新读版本确认。 */
    fun finishReadyReboot() {
        state = state.copy(
            phase = OtaPhase.Success,
            progress = 1f,
            message = "新固件已写入，等待设备重启。重启并重新连接后，请再读一次版本确认。",
        )
    }

    fun finishSuccess(version: String?) {
        state = state.copy(
            phase = OtaPhase.Success,
            progress = 1f,
            targetVersion = version ?: state.targetVersion,
            message = "升级完成。设备重启并重新连接后，再确认一次版本。",
        )
    }

    /** 设备确认中止，回到空闲。 */
    fun finishAborted(message: String) {
        state = state.copy(
            phase = OtaPhase.Idle,
            progress = 0f,
            message = message.ifBlank { "设备已中止升级，固件没有被改动。" },
        )
    }

    fun finishFailure(message: String) {
        state = state.copy(
            phase = OtaPhase.Failed,
            message = message.ifBlank { "升级失败，设备固件没有被改动。" },
        )
    }

    private fun requestOrNull(): OtaRequest? {
        val current = state
        var ip = current.localIp
        val problem = when (current.source) {
            OtaSource.Server -> {
                val url = current.serverUrl.trim()
                val ssid = current.wifiSsid.trim()
                when {
                    !url.startsWith("https://") || url.length <= "https://".length ->
                        "请先填写 https:// 开头的固件服务器地址。"
                    url.any { it.isWhitespace() } ->
                        "固件服务器地址里不能有空格。"
                    ssid.isEmpty() ->
                        "请先填写设备要连接的 WiFi 名称。"
                    else -> null
                }
            }

            OtaSource.Hotspot -> {
                ip = currentLocalIp(current.localMode)
                val firmware = current.localFile
                val file = firmware?.file
                when {
                    firmware == null || file == null ->
                        "请先选择固件文件；没有固件文件时本地服务不会启动，也不会向设备发送命令。"
                    !file.isFile || file.length() <= 0L ->
                        "本地固件缓存已经不在了，请重新选择固件文件。"
                    ip == null && current.localMode == OtaLocalMode.Hotspot ->
                        "没有检测到手机热点 IP。请先在系统设置打开热点（2.4GHz），点刷新后再开始。"
                    ip == null ->
                        "没有读到手机 WiFi 的 IPv4 地址。请先让手机连接 WiFi，再开始本地升级。"
                    current.wifiSsid.trim().isEmpty() && current.localMode == OtaLocalMode.Hotspot ->
                        "请填写手机热点名称（SSID）和密码。"
                    current.wifiSsid.trim().isEmpty() ->
                        "请填写灯带要连接的 WiFi 名称；它必须和手机在同一个 WiFi。"
                    else -> null
                }
            }
        }
        if (problem != null) {
            state = current.copy(message = problem, localIp = ip)
            return null
        }
        if (current.source == OtaSource.Hotspot) {
            state = state.copy(localIp = ip)
        }
        return OtaRequest(
            source = current.source,
            localMode = current.localMode,
            serverUrl = current.serverUrl.trim(),
            wifiSsid = current.wifiSsid.trim(),
            wifiPassword = current.wifiPassword,
            localPort = current.localPort,
            localFirmware = current.localFile,
        )
    }
}

/**
 * OTA 子页。内容全部是 APP 侧状态，BLE 命令由 [OtaController.link] 转发。
 *
 * 文件选择器放在这里而不是 controller 里：`OpenDocument` 必须由 Activity 的
 * ActivityResult 注册表发起，controller 只是纯状态机。选中后先在 IO 线程把 Uri
 * 复制进缓存并算 SHA-256，再把结果交给 actions。
 */
@Composable
fun OtaScreen(
    state: OtaUiState,
    actions: OtaActions,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importing by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            actions.onLocalFileProblem("没有选择固件文件，本地升级还缺少固件。")
            return@rememberLauncherForActivityResult
        }
        importing = true
        actions.onLocalImportStarted()
        scope.launch {
            try {
                val firmware = OtaFirmwareStore.import(context, uri)
                importing = false
                actions.onLocalFirmware(firmware)
            } catch (error: CancellationException) {
                /* 页面已经离开：不要把它当成“读取失败”，把取消继续抛给协程。 */
                throw error
            } catch (error: Exception) {
                importing = false
                actions.onLocalFileProblem(error.message ?: "读取固件文件失败。")
            }
        }
    }

    /* 进入本地模式时静默读一次手机 IP；切换模式后不会反复触发。 */
    LaunchedEffect(state.source) {
        if (state.source == OtaSource.Hotspot) actions.onRefreshLocalNetwork()
    }

    if (state.phase == OtaPhase.Starting ||
        state.phase == OtaPhase.Downloading ||
        state.phase == OtaPhase.Verifying
    ) {
        OtaProgressDialog(state = state, onCancel = actions.onCancel)
    }

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Space.x6),
    ) {
        OtaNotice()
        OtaVersionBlock(state, actions.onReadVersion)
        OtaSourceBlock(state, actions)
        when (state.source) {
            OtaSource.Server -> OtaServerBlock(state, actions)
            OtaSource.Hotspot -> OtaHotspotBlock(
                state = state,
                importing = importing,
                onPickFile = {
                    if (!state.phase.busy) picker.launch(arrayOf("*/*"))
                },
                onRefreshNetwork = actions.onRefreshLocalNetwork,
                actions = actions,
            )
        }
        OtaRunBlock(state, actions, importing)
    }
}

/** 升级进行中的全屏进度界面；只在 Starting / Downloading / Verifying 时出现。 */
@Composable
private fun OtaProgressDialog(state: OtaUiState, onCancel: () -> Unit) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        OtaProgressScreen(state = state, onCancel = onCancel)
    }
}

@Composable
private fun OtaProgressScreen(state: OtaUiState, onCancel: () -> Unit) {
    val palette = LocalPalette.current
    val animated by animateFloatAsState(
        targetValue = state.progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 420),
        label = "ota-progress",
    )
    val endless = rememberInfiniteTransition(label = "ota-ring")
    val spin by endless.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "ota-spin",
    )

    val title = when (state.phase) {
        OtaPhase.Starting -> "正在连接设备"
        OtaPhase.Downloading -> "正在下载固件"
        OtaPhase.Verifying -> "正在校验固件"
        else -> "正在升级"
    }
    val step = when (state.phase) {
        OtaPhase.Starting -> 0
        OtaPhase.Downloading -> 1
        OtaPhase.Verifying -> 2
        else -> 3
    }
    val sourceLabel = buildString {
        append(if (state.source == OtaSource.Server) "远程 HTTPS" else "手机本地服务")
        if (state.source == OtaSource.Hotspot) {
            append(" · ")
            append(state.localMode.label)
        }
    }
    val fileName = state.localFile?.name
        ?: state.serverUrl.takeIf { it.isNotBlank() }
        ?: "固件文件"

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(palette.page, palette.surface),
                ),
            ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = Space.x4, vertical = Space.x6),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "FIRMWARE UPDATE",
                    color = palette.accent,
                    style = AppType.caption,
                    letterSpacing = 2.sp,
                )
                Spacer(Modifier.height(Space.x4))

                Box(
                    Modifier.size(214.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        val stroke = 11.dp.toPx()
                        drawCircle(
                            color = palette.hairline.copy(alpha = 0.38f),
                            style = Stroke(width = stroke),
                        )
                        drawArc(
                            color = palette.accent,
                            startAngle = -90f,
                            sweepAngle = 360f * animated,
                            useCenter = false,
                            style = Stroke(width = stroke, cap = StrokeCap.Round),
                        )
                    }
                    Canvas(
                        Modifier
                            .fillMaxSize()
                            .rotate(spin),
                    ) {
                        drawArc(
                            color = palette.accent.copy(alpha = 0.55f),
                            startAngle = 0f,
                            sweepAngle = 26f,
                            useCenter = false,
                            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
                        )
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "${(animated * 100).roundToInt()}%",
                            color = palette.text,
                            style = AppType.title.copy(fontSize = 44.sp),
                        )
                        Text(
                            state.phase.label,
                            color = palette.muted,
                            style = AppType.caption,
                        )
                    }
                }

                Spacer(Modifier.height(Space.x4))
                Text(title, color = palette.text, style = AppType.strong)
                Text(
                    state.message,
                    color = palette.muted,
                    style = AppType.caption,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = Space.x1, start = Space.x2, end = Space.x2),
                )
            }

            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.medium))
                        .background(palette.surfaceHigh.copy(alpha = 0.55f))
                        .padding(horizontal = Space.x3, vertical = Space.x2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(sourceLabel, color = palette.accent, style = AppType.caption)
                        Text(
                            fileName,
                            color = palette.text,
                            style = AppType.body,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (state.localFile != null) {
                        Text(
                            formatByteCount(state.localFile.size),
                            color = palette.muted,
                            style = AppType.caption,
                        )
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(top = Space.x4),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    listOf("准备", "下载", "校验", "重启").forEachIndexed { index, label ->
                        val active = index <= step
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier
                                    .size(if (index == step) 10.dp else 8.dp)
                                    .clip(CircleShape)
                                    .background(if (active) palette.accent else palette.hairline),
                            )
                            Text(
                                label,
                                color = if (active) palette.text else palette.muted,
                                style = AppType.caption,
                                modifier = Modifier.padding(top = Space.x1),
                            )
                        }
                    }
                }

                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Space.x4)
                        .heightIn(min = MinTouchTarget),
                ) { Text("取消升级") }
                Text(
                    "升级期间请保持 APP 在前台。取消后设备仍运行原固件；若已进入校验/重启阶段，结果以设备回报为准。",
                    color = palette.muted,
                    style = AppType.caption,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = Space.x2),
                )
            }
        }
    }
}

/** 页面顶部说清楚两种模式各自做什么、本地模式的边界在哪里。 */
@Composable
private fun OtaNotice() {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(Radius.medium)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = Space.x4)
            .clip(shape)
            .background(palette.warn.copy(alpha = 0.10f))
            .border(1.dp, palette.warn.copy(alpha = 0.35f), shape)
            .padding(horizontal = Space.x3, vertical = Space.x3),
    ) {
        Text("本地升级：手机和灯带必须在同一个 WiFi", color = palette.warn, style = AppType.strong)
        Text(
            "本地模式会先把固件复制到手机缓存，启动本机 HTTP 服务，再通过 BLE 把手机 WiFi 地址、" +
                "端口、随机 token 和 SHA-256 发给灯带，由灯带自己下载。远程服务器模式保持原样。" +
                "手机热点需要先在系统设置手动开启，频段必须选 2.4GHz；升级期间请让 App 留在前台。",
            color = palette.muted,
            style = AppType.caption,
            modifier = Modifier.padding(top = Space.x1),
        )
    }
}

@Composable
private fun OtaVersionBlock(state: OtaUiState, onReadVersion: () -> Unit) {
    val palette = LocalPalette.current
    AcrylicGroup {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "当前固件版本",
                color = palette.text,
                style = AppType.strong,
                modifier = Modifier.weight(1f),
            )
            Text(
                state.currentVersion ?: "未读取",
                color = if (state.currentVersion == null) palette.muted else palette.text,
                style = AppType.strong,
            )
        }
        Text(
            if (state.currentVersion == null) {
                "还没从设备读到版本。连接灯带后点“读取版本”，会读设备的 OTA 信息（ota_info）。"
            } else {
                "版本来自设备 OTA 信息服务（ota_info）；连接灯带时也会先用设备信息里的版本填空。"
            },
            color = palette.muted,
            style = AppType.caption,
            modifier = Modifier.padding(top = Space.x1),
        )
        OutlinedButton(
            onClick = onReadVersion,
            enabled = !state.phase.busy,
            modifier = Modifier.padding(top = Space.x2).heightIn(min = MinTouchTarget),
        ) { Text(if (state.currentVersion == null) "读取版本" else "重新读取版本") }
    }
}

@Composable
private fun OtaSourceBlock(state: OtaUiState, actions: OtaActions) {
    val palette = LocalPalette.current
    AcrylicGroup {
        Text("升级方式", color = palette.text, style = AppType.strong)
        OtaSource.entries.forEach { source ->
            OtaSourceRow(
                source = source,
                selected = state.source == source,
                enabled = !state.phase.busy,
                onClick = { actions.onSource(source) },
            )
        }
        Text(
            "远程模式让设备按 HTTPS 地址自己下载；本地模式由手机提供固件，设备要通过同一个 WiFi 来手机取文件。两种模式都由设备自己写固件。",
            color = palette.muted,
            style = AppType.caption,
            modifier = Modifier.padding(top = Space.x2),
        )
    }
}

@Composable
private fun OtaSourceRow(
    source: OtaSource,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(Radius.medium)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = Space.x2)
            .clip(shape)
            .background(if (selected) palette.accent.copy(alpha = 0.14f) else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (selected) palette.accent.copy(alpha = 0.55f) else palette.hairline,
                shape = shape,
            )
            .enabledDim(enabled)
            .tapTarget(
                enabled = enabled,
                description = source.label,
                clickRole = Role.RadioButton,
                onClick = onClick,
            )
            .padding(horizontal = Space.x3, vertical = Space.x2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(18.dp)
                .border(
                    width = 1.5.dp,
                    color = if (selected) palette.accent else palette.muted,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Box(Modifier.size(10.dp).background(palette.accent, CircleShape))
            }
        }
        Column(Modifier.weight(1f).padding(start = Space.x3)) {
            Text(
                source.label,
                color = if (selected) palette.text else palette.muted,
                style = AppType.strong,
            )
            Text(source.blurb, color = palette.muted, style = AppType.caption)
        }
    }
}

@Composable
private fun OtaServerBlock(state: OtaUiState, actions: OtaActions) {
    val palette = LocalPalette.current
    val enabled = !state.phase.busy
    AcrylicGroup {
        Text("服务器地址", color = palette.text, style = AppType.strong)
        OtaField(
            value = state.serverUrl,
            placeholder = "https://",
            keyboardType = KeyboardType.Uri,
            enabled = enabled,
            onValueChange = actions.onServerUrl,
        )
        Text(
            "只接受 https:// 地址。设备按这个地址下载固件，远程 HTTPS 不需要 token。",
            color = palette.muted,
            style = AppType.caption,
            modifier = Modifier.padding(top = Space.x2),
        )

        Text(
            "设备 WiFi",
            color = palette.text,
            style = AppType.strong,
            modifier = Modifier.padding(top = Space.x3),
        )
        OtaField(
            value = state.wifiSsid,
            placeholder = "WiFi 名称（SSID）",
            keyboardType = KeyboardType.Text,
            enabled = enabled,
            onValueChange = actions.onWifiSsid,
        )
        OtaField(
            value = state.wifiPassword,
            placeholder = "WiFi 密码（开放网络留空）",
            keyboardType = KeyboardType.Password,
            password = true,
            enabled = enabled,
            onValueChange = actions.onWifiPassword,
        )
        Text(
            "设备会连这组 WiFi，再从上面的地址下载固件。WiFi 名称不能为空；密码只在本次请求里发给设备。",
            color = palette.muted,
            style = AppType.caption,
            modifier = Modifier.padding(top = Space.x2),
        )
    }
}

/** 页面里的单行输入框。和原来的地址框保持同一套外观，多了密码遮罩和禁用态。 */
@Composable
internal fun OtaField(
    value: String,
    placeholder: String,
    keyboardType: KeyboardType,
    password: Boolean = false,
    enabled: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(Radius.small)
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        enabled = enabled,
        textStyle = AppType.body.copy(color = palette.text),
        cursorBrush = SolidColor(palette.accent),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (password && !passwordVisible) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        modifier = Modifier.fillMaxWidth().padding(top = Space.x2).enabledDim(enabled),
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(shape)
                    .background(palette.surfaceHigh.copy(alpha = 0.72f))
                    .border(1.dp, palette.hairline, shape)
                    .padding(start = Space.x3, end = Space.x1),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(placeholder, color = palette.muted, style = AppType.body)
                    }
                    inner()
                }
                if (password) {
                    TextButton(
                        onClick = { passwordVisible = !passwordVisible },
                        enabled = enabled,
                    ) {
                        Text(
                            if (passwordVisible) "隐藏" else "显示",
                            color = palette.accent,
                            style = AppType.caption,
                        )
                    }
                }
            }
        },
    )
}

@Composable
private fun OtaHotspotBlock(
    state: OtaUiState,
    importing: Boolean,
    onPickFile: () -> Unit,
    onRefreshNetwork: () -> Unit,
    actions: OtaActions,
) {
    val palette = LocalPalette.current
    val enabled = !state.phase.busy && !importing
    val hotspot = state.localMode == OtaLocalMode.Hotspot
    val predictedUrl = state.localIp?.let {
        "http://$it:${state.localPort}/${OtaLocalServer.FIRMWARE_PATH}"
    }
    AcrylicGroup {
        Text("手机本地固件服务", color = palette.text, style = AppType.strong)

        Row(
            Modifier.fillMaxWidth().padding(top = Space.x2),
            horizontalArrangement = Arrangement.spacedBy(Space.x2),
        ) {
            OtaLocalMode.entries.forEach { mode ->
                val selected = state.localMode == mode
                val onClick = { actions.onLocalMode(mode) }
                if (selected) {
                    Button(
                        onClick = onClick,
                        enabled = enabled,
                        modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget),
                    ) { Text(mode.label) }
                } else {
                    OutlinedButton(
                        onClick = onClick,
                        enabled = enabled,
                        modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget),
                    ) { Text(mode.label) }
                }
            }
        }

        Text(
            if (hotspot) {
                "请先在系统设置里打开手机热点，频段选 2.4GHz（ESP32 不支持 5GHz）。" +
                    "APP 会检测热点 IP、启动本机 HTTP 服务，并把热点名称/密码通过 BLE 发给灯带。"
            } else {
                "手机先启动一个本机 HTTP 服务，再把地址和 token 通过 BLE 发给灯带。" +
                    "灯带必须和手机连在同一个 WiFi。"
            },
            color = palette.muted,
            style = AppType.body,
            modifier = Modifier.padding(top = Space.x1),
        )
        OtaFact(if (hotspot) "手机热点 IP" else "手机 WiFi IP", state.localIp ?: "未检测到")
        OtaFact("监听端口", "${state.localPort}")
        OtaFact(
            "服务状态",
            when {
                state.localServiceRunning -> "运行中"
                state.phase.busy -> "启动中"
                else -> "未启动（开始升级时启动）"
            },
        )
        OtaFact("固件地址", state.localServiceUrl ?: predictedUrl ?: "检测到 IP 后生成")
        OtaFact("固件文件", state.localFile?.name ?: "未选择")
        state.localFile?.let { firmware ->
            OtaFact("文件大小", formatByteCount(firmware.size))
            OtaFact("SHA-256", "${firmware.sha256.take(16)}…${firmware.sha256.takeLast(8)}")
        }
        if (hotspot && state.localIp == null) {
            Text(
                "没有检测到热点 IP：请确认系统热点已开启、频段为 2.4GHz，然后点“刷新热点 IP”。" +
                    "部分手机需要先断开 WiFi 再开热点。",
                color = palette.warn,
                style = AppType.caption,
                modifier = Modifier.padding(top = Space.x2),
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(top = Space.x2),
            horizontalArrangement = Arrangement.spacedBy(Space.x2),
        ) {
            OutlinedButton(
                onClick = onRefreshNetwork,
                enabled = enabled,
                modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget),
            ) { Text(if (hotspot) "刷新热点 IP" else "刷新手机 IP") }
            OutlinedButton(
                onClick = onPickFile,
                enabled = enabled,
                modifier = Modifier.weight(1f).heightIn(min = MinTouchTarget),
            ) {
                Text(
                    when {
                        importing -> "导入中…"
                        state.localFile == null -> "选择固件文件"
                        else -> "更换固件文件"
                    },
                )
            }
        }
        if (state.localFile == null) {
            Text(
                "还没选择固件文件。点“选择固件文件”后会复制到手机缓存并算出 SHA-256；" +
                    "没有文件时点“开始升级”只会提示先选择文件，不会向设备发送命令。",
                color = palette.warn,
                style = AppType.caption,
                modifier = Modifier.padding(top = Space.x2),
            )
        }

        Text(
            if (hotspot) "灯带要连接的热点" else "灯带要连接的 WiFi",
            color = palette.text,
            style = AppType.strong,
            modifier = Modifier.padding(top = Space.x3),
        )
        OtaField(
            value = state.wifiSsid,
            placeholder = if (hotspot) "热点名称（SSID）" else "WiFi 名称（SSID，和手机同一个）",
            keyboardType = KeyboardType.Text,
            enabled = !state.phase.busy,
            onValueChange = actions.onWifiSsid,
        )
        OtaField(
            value = state.wifiPassword,
            placeholder = if (hotspot) "热点密码" else "WiFi 密码（开放网络留空）",
            keyboardType = KeyboardType.Password,
            password = true,
            enabled = !state.phase.busy,
            onValueChange = actions.onWifiPassword,
        )
        Text(
            if (hotspot) {
                "这里填的是手机热点的名称和密码，会随 START 命令发给设备；设备会断开原来的 WiFi 去连这个热点。" +
                    "热点必须是 2.4GHz。升级结束、失败或取消后，手机上的本地服务会自动关闭。升级期间请让 App 留在前台。" +
                    "如果手机热点开了客户端隔离，灯带会连不到手机；端口 12894 被占用时本地服务会启动失败并给出提示。"
            } else {
                "手机和灯带必须在同一个 WiFi；这里填的是那个 WiFi 的名称和密码，会随 START 命令发给设备。" +
                    "升级结束、失败或取消后，手机上的本地服务会自动关闭。升级期间请让 App 留在前台。" +
                    "如果路由器开启了 AP 隔离（客户端隔离），灯带会连不到手机；端口 12894 被别的 App 占用时，本地服务会启动失败并给出提示。"
            },
            color = palette.muted,
            style = AppType.caption,
            modifier = Modifier.padding(top = Space.x2),
        )
    }
}

@Composable
private fun OtaFact(label: String, value: String) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(top = Space.x2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = palette.muted, style = AppType.body, modifier = Modifier.weight(1f))
        Text(
            value,
            color = palette.text,
            style = AppType.body,
            textAlign = TextAlign.End,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 自己的细进度条。不用 Material3 的 determinate 样式：它在 0% 时也会在末端画一个
 * 停止点，看起来像"快完成了"，和"空闲"状态矛盾。这里只画底轨和按比例填充。
 */
@Composable
private fun OtaProgressBar(progress: Float) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(2.dp)
    val fraction = progress.coerceIn(0f, 1f)
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = Space.x2)
            .height(4.dp)
            .clip(shape)
            .background(palette.surfaceHigh.copy(alpha = 0.8f)),
    ) {
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(palette.accent, shape),
            )
        }
    }
}

@Composable
private fun OtaRunBlock(state: OtaUiState, actions: OtaActions, importing: Boolean) {
    val palette = LocalPalette.current
    val busy = state.phase.busy
    /* 导入固件期间不允许开始/检查，避免用旧文件或半截文件发 START。 */
    val canRun = !busy && !importing
    AcrylicGroup {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "升级状态",
                color = palette.text,
                style = AppType.strong,
                modifier = Modifier.weight(1f),
            )
            Text(
                buildString {
                    append(state.phase.label)
                    if (state.phase == OtaPhase.Downloading) {
                        append("  ")
                        append((state.progress.coerceIn(0f, 1f) * 100).roundToInt())
                        append('%')
                    }
                },
                color = when (state.phase) {
                    OtaPhase.Success -> palette.ok
                    OtaPhase.Failed -> palette.warn
                    else -> palette.muted
                },
                style = AppType.strong,
            )
        }
        if (state.phase == OtaPhase.Checking || state.phase == OtaPhase.Verifying) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().padding(top = Space.x2),
                color = palette.accent,
            )
        } else {
            OtaProgressBar(state.progress)
        }
        if (state.targetVersion != null) {
            OtaFact("目标版本", state.targetVersion)
        }
        if (state.message.isNotEmpty()) {
            Text(
                state.message,
                color = when (state.phase) {
                    OtaPhase.Success -> palette.ok
                    OtaPhase.Failed -> palette.warn
                    else -> palette.muted
                },
                style = AppType.body,
                modifier = Modifier.padding(top = Space.x2),
            )
        }
        if (busy) {
            Text(
                "升级过程中请保持 App 在前台并靠近灯带。BLE 断开后不会显示成功；重连后请重新读取版本确认。",
                color = palette.muted,
                style = AppType.caption,
                modifier = Modifier.padding(top = Space.x2),
            )
        }
        if (importing) {
            Text(
                "正在导入固件文件（复制到手机缓存并计算 SHA-256），导入完成前不会开始升级。",
                color = palette.muted,
                style = AppType.caption,
                modifier = Modifier.padding(top = Space.x2),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(top = Space.x3),
            horizontalArrangement = Arrangement.spacedBy(Space.x2),
        ) {
            OutlinedButton(
                onClick = actions.onCheck,
                enabled = canRun,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text("检查更新") }
            Button(
                onClick = actions.onStart,
                enabled = canRun,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text("开始升级") }
        }
        TextButton(
            onClick = actions.onCancel,
            enabled = busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = MinTouchTarget),
        ) { Text("取消升级") }
    }
}
