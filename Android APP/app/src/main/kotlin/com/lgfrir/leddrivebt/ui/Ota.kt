package com.lgfrir.leddrivebt.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lgfrir.leddrivebt.ui.theme.AppType
import com.lgfrir.leddrivebt.ui.theme.MinTouchTarget
import com.lgfrir.leddrivebt.ui.theme.Radius
import com.lgfrir.leddrivebt.ui.theme.Space
import kotlin.math.roundToInt

/*
 * OTA 固件升级页面。
 *
 * 远程服务器模式已经通过 BLE 接通（见 [OtaBleLink]）：
 *   - 检查更新只校验 WiFi/URL 参数并读设备版本，设备端没有独立的清单检查接口；
 *   - 开始升级把 WiFi、URL 打包成 START 命令发给设备，由设备自己下载；
 *   - 升级中每秒读一次 ota_stat，驱动下面的阶段和进度。
 * 手机本地热点模式（手机当固件服务器）还没实现，只保留提示，不会发真实命令，
 * 也不会出现假的“升级成功”。
 */

/** 固件来源。远程服务器由设备自己走 HTTPS 下载；本地热点由手机当固件服务器。 */
enum class OtaSource(val label: String, val blurb: String) {
    Server("远程服务器", "设备按 HTTPS 地址下载固件"),
    Hotspot("手机本地热点", "手机开热点并充当固件服务器"),
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
    /** 远程模式：固件服务器地址，必须以 https:// 开头。 */
    val serverUrl: String = "",
    /** 远程模式：设备要连接的 WiFi 名称，不能为空。 */
    val wifiSsid: String = "",
    /** 远程模式：WiFi 密码，开放网络可以留空。 */
    val wifiPassword: String = "",
    /** 本地热点模式：手机端固件服务的端口，当前只做展示占位。 */
    val localPort: Int = 8080,
    /** 本地热点模式：选中的固件文件，当前恒为 null（文件选择占位）。 */
    val localFile: String? = null,
    /** 设备当前固件版本。null = 还没读到。 */
    val currentVersion: String? = null,
    /** 检查更新后拿到的目标版本。 */
    val targetVersion: String? = null,
    /** 0f..1f。 */
    val progress: Float = 0f,
    /** 最近一次操作的结果或说明，直接显示在状态区。 */
    val message: String = "",
)

/** 真正交给设备端的请求。字段顺序和固件 START 命令一致。 */
data class OtaRequest(
    val source: OtaSource,
    val serverUrl: String = "",
    val wifiSsid: String = "",
    val wifiPassword: String = "",
    val localPort: Int = 8080,
    val localFile: String? = null,
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
    val onServerUrl: (String) -> Unit,
    val onWifiSsid: (String) -> Unit,
    val onWifiPassword: (String) -> Unit,
    val onPickLocalFile: () -> Unit,
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
 */
class OtaController {
    var state by mutableStateOf(OtaUiState())
        private set

    /**
     * 设备端/传输层实现。LightViewModel 里赋成 [OtaBleLink]。
     */
    var link: OtaLink? = null

    val actions: OtaActions = OtaActions(
        onSource = { selectSource(it) },
        onServerUrl = { setServerUrl(it) },
        onWifiSsid = { setWifiSsid(it) },
        onWifiPassword = { setWifiPassword(it) },
        onPickLocalFile = { pickLocalFile() },
        onReadVersion = { readVersion() },
        onCheck = { check() },
        onStart = { start() },
        onCancel = { cancel() },
    )

    /** 切换升级方式。升级进行中不允许切换，避免请求参数和界面不一致。 */
    fun selectSource(source: OtaSource) {
        if (state.phase.busy || state.source == source) return
        state = state.copy(source = source, message = "")
    }

    fun setServerUrl(url: String) {
        state = state.copy(serverUrl = url, message = "")
    }

    fun setWifiSsid(ssid: String) {
        state = state.copy(wifiSsid = ssid, message = "")
    }

    fun setWifiPassword(password: String) {
        state = state.copy(wifiPassword = password, message = "")
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

    /**
     * 本地热点模式的文件选择占位。等手机本地 HTTP 服务实现后才改成系统文件选择器，
     * 选中后 `state = state.copy(localFile = name)`。
     */
    fun pickLocalFile() {
        state = state.copy(message = "本地固件文件选择还没接入；手机本地 HTTP 服务下一步实现，本地热点升级不会发送命令。")
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
            OtaSource.Hotspot ->
                "手机本地 HTTP 服务下一步实现，本地热点升级还没有开始，不会向设备发送命令。"
        }
        if (problem != null) {
            state = current.copy(message = problem)
            return null
        }
        return OtaRequest(
            source = current.source,
            serverUrl = current.serverUrl.trim(),
            wifiSsid = current.wifiSsid.trim(),
            wifiPassword = current.wifiPassword,
            localPort = current.localPort,
            localFile = current.localFile,
        )
    }
}

/**
 * OTA 子页。内容全部是 APP 侧状态，BLE 命令由 [OtaController.link] 转发。
 */
@Composable
fun OtaScreen(
    state: OtaUiState,
    actions: OtaActions,
    modifier: Modifier = Modifier,
) {
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
            OtaSource.Hotspot -> OtaHotspotBlock(state, actions.onPickLocalFile)
        }
        OtaRunBlock(state, actions)
    }
}

/** 页面顶部说清楚现在能做什么、还没有做什么，避免用户以为本地热点也能升级。 */
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
        Text("手机本地 HTTP 服务尚未实现", color = palette.warn, style = AppType.strong)
        Text(
            "远程服务器升级已通过 BLE 接通：填写 WiFi 和固件地址后，由设备自己下载并写入新固件。" +
                "手机本地热点模式只保留参数和提示，下一步实现手机本地 HTTP 服务；" +
                "在那之前它不会向设备发送升级命令，也不会出现假的“升级成功”。",
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
            "远程模式通过 BLE 让设备自己下载；本地热点模式还没实现，不会发送升级命令。",
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
private fun OtaField(
    value: String,
    placeholder: String,
    keyboardType: KeyboardType,
    password: Boolean = false,
    enabled: Boolean = true,
    onValueChange: (String) -> Unit,
) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(Radius.small)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        enabled = enabled,
        textStyle = AppType.body.copy(color = palette.text),
        cursorBrush = SolidColor(palette.accent),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = if (password) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        modifier = Modifier.fillMaxWidth().padding(top = Space.x2).enabledDim(enabled),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(palette.surfaceHigh.copy(alpha = 0.72f))
                    .border(1.dp, palette.hairline, shape)
                    .padding(horizontal = Space.x3, vertical = Space.x3),
            ) {
                if (value.isEmpty()) {
                    Text(placeholder, color = palette.muted, style = AppType.body)
                }
                inner()
            }
        },
    )
}

@Composable
private fun OtaHotspotBlock(state: OtaUiState, onPickLocalFile: () -> Unit) {
    val palette = LocalPalette.current
    AcrylicGroup {
        Text("手机本地热点", color = palette.text, style = AppType.strong)
        Text(
            "手机开热点，设备连入后从本机下载。",
            color = palette.muted,
            style = AppType.body,
            modifier = Modifier.padding(top = Space.x1),
        )
        OtaFact("监听端口", "${state.localPort}")
        OtaFact("固件文件", state.localFile ?: "未选择")
        OutlinedButton(
            onClick = onPickLocalFile,
            modifier = Modifier.padding(top = Space.x2).heightIn(min = MinTouchTarget),
        ) { Text("选择固件文件") }
        Text(
            "手机本地 HTTP 服务下一步实现。端口和文件选择目前是占位，在这之前本地热点模式不会向设备发送任何命令。",
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
private fun OtaRunBlock(state: OtaUiState, actions: OtaActions) {
    val palette = LocalPalette.current
    val busy = state.phase.busy
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
        Row(
            Modifier.fillMaxWidth().padding(top = Space.x3),
            horizontalArrangement = Arrangement.spacedBy(Space.x2),
        ) {
            OutlinedButton(
                onClick = actions.onCheck,
                enabled = !busy,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) { Text("检查更新") }
            Button(
                onClick = actions.onStart,
                enabled = !busy,
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
