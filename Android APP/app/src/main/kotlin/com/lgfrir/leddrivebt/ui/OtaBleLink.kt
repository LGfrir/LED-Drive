package com.lgfrir.leddrivebt.ui

import com.lgfrir.leddrivebt.ble.BleException
import com.lgfrir.leddrivebt.ble.LightBleClient
import com.lgfrir.leddrivebt.ble.LinkPhase
import com.lgfrir.leddrivebt.ble.OtaLocalServer
import com.lgfrir.leddrivebt.protocol.OTA_CMD_MAX
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.SecureRandom

/**
 * OTA 页面的 BLE 实现。
 *
 * 设备端协议（ESP-IDF 已实现）：
 *   ota_info  读  {"version":"1.0.0","partition":"factory","ota":true}
 *   ota_stat  读  {"state":0,"progress":0,"read":0,"total":0,"version":"1.0.0","error":""}
 *   ota_cmd   写  START: [0]=1,[1]=flags,[2]=ssid_len,[3]=pass_len,[4]=url_len,
 *                     [5]=token_len,[6]=sha_len,[7..]=ssid,password,url,token,sha256(hex)
 *                 ABORT: [0]=2
 *
 * flags bit0 = local_http（手机本地服务），bit1 = auto_reboot。
 *
 * 这里只复用现有连接和 LightBleClient 的 gate，不新开连接。
 * 远程 HTTPS：flags=0，token/sha 留空，设备 sha 为空就跳过校验。
 * 手机本地服务：flags=bit0|bit1，启动 OtaLocalServer，随机 128-bit token，
 * START 里带 sha256(hex)；升级终态/失败/取消时关闭 server。
 */
class OtaBleLink(
    private val ble: LightBleClient,
    private val ota: OtaController,
    private val scope: CoroutineScope,
) : OtaLink {
    private var versionJob: Job? = null
    private var updateJob: Job? = null
    private var localServer: OtaLocalServer? = null

    override fun readVersion() {
        if (versionJob?.isActive == true) return
        versionJob = scope.launch {
            try {
                requireConnected()
                val info = readInfo()
                val version = info.version ?: throw OtaLinkException("设备返回的 OTA 信息里没有版本号。")
                ota.applyDeviceVersion(version)
                ota.note(
                    buildString {
                        append("设备当前固件 ")
                        append(version)
                        info.partition?.let { append("（运行分区 ").append(it).append("）") }
                        if (!info.ota) append("；这个固件报告不支持 OTA")
                        append("。")
                    },
                )
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ota.note("读取版本失败：${explain(error)}")
            } finally {
                versionJob = null
            }
        }
    }

    override fun checkUpdate(request: OtaRequest) {
        if (updateJob?.isActive == true) return
        updateJob = scope.launch {
            ota.beginCheck()
            try {
                requireConnected()
                when (request.source) {
                    OtaSource.Server -> checkRemote()
                    OtaSource.Hotspot -> checkLocal(request)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ota.finishFailure("检查更新失败：${explain(error)}")
            } finally {
                updateJob = null
            }
        }
    }

    private suspend fun checkRemote() {
        val info = readInfo()
        info.version?.let { ota.applyDeviceVersion(it) }
        val status = readStatus()
        ota.finishCheck(
            buildString {
                append("设备当前固件 ")
                append(info.version ?: "未回报")
                info.partition?.let { append("（分区 ").append(it).append("）") }
                append("。设备端没有独立的清单检查接口，本次只校验了 HTTPS 地址和 WiFi 参数，并确认 OTA 服务可读。")
                status.busyText()?.let { append(it) }
            },
        )
    }

    /**
     * 本地模式的“检查”不启动 HTTP 服务，只确认文件、手机 WiFi 和 START 参数能装进 240 字节。
     * 真正启动服务在 [startUpdate] 里。
     */
    private suspend fun checkLocal(request: OtaRequest) {
        val firmware = request.localFirmware
            ?: throw OtaLinkException("请先选择固件文件；没有固件文件时不能开始本地升级。")
        if (!firmware.file.isFile || firmware.file.length() <= 0L) {
            throw OtaLinkException("本地固件缓存已经不在了，请重新选择固件文件。")
        }
        val ip = ota.currentWifiIpv4()
            ?: throw OtaLinkException("没有读到手机 WiFi 的 IPv4 地址。请先让手机连接 WiFi。")
        val ssid = request.wifiSsid.trim()
        validateCommon(ssid, request.wifiPassword)
        val url = localUrl(ip, request.localPort)
        validateUrl(url, requireHttps = false)
        val total = startPacketSize(ssid, request.wifiPassword, url, TOKEN_HEX_LEN, SHA_HEX_LEN)
        if (total > OTA_CMD_MAX) {
            throw OtaLinkException(
                "本地 START 参数共 $total 字节，超过 BLE 单包上限 $OTA_CMD_MAX 字节。" +
                    "请缩短 WiFi 名称/密码后重试。",
            )
        }
        val info = readInfo()
        info.version?.let { ota.applyDeviceVersion(it) }
        if (!info.ota) throw OtaLinkException("设备返回 ota_info.ota=false，这个固件不支持 OTA。")
        val status = readStatus()
        ota.finishCheck(
            buildString {
                append("本地升级已就绪：手机 ")
                append(ip)
                append(':')
                append(request.localPort)
                append(" 将提供 ")
                append(firmware.name)
                append("（")
                append(formatByteCount(firmware.size))
                append("，SHA-256 ")
                append(firmware.sha256.take(16))
                append("…）。START 参数 $total/$OTA_CMD_MAX 字节。")
                append("开始前请确认手机和灯带连接同一个 WiFi。")
                status.busyText()?.let { append(it) }
            },
        )
    }

    override fun startUpdate(request: OtaRequest) {
        if (updateJob?.isActive == true) return
        updateJob = scope.launch {
            var server: OtaLocalServer? = null
            try {
                requireConnected()
                when (request.source) {
                    OtaSource.Server -> startRemote(request)
                    OtaSource.Hotspot -> {
                        /*
                         * 先确认设备可升级、没有旧任务，再启动手机 HTTP 服务：
                         * 设备忙或未鉴权时不必在手机上开监听端口。
                         */
                        val firmware = request.localFirmware
                            ?: throw OtaLinkException("请先选择固件文件；没有固件文件时不会启动本地服务。")
                        if (!firmware.file.isFile || firmware.file.length() <= 0L) {
                            throw OtaLinkException("本地固件缓存已经不在了，请重新选择固件文件。")
                        }
                        val ip = ota.currentWifiIpv4()
                            ?: throw OtaLinkException(
                                "没有读到手机 WiFi 的 IPv4 地址。请先让手机连接 WiFi，再开始本地升级。",
                            )
                        val ssid = request.wifiSsid.trim()
                        val password = request.wifiPassword
                        validateCommon(ssid, password)
                        ensureUpdateReady()

                        val token = randomToken()
                        val started = OtaLocalServer(ip)
                        server = started
                        localServer = started
                        /*
                         * bind/accept 放到 IO 线程，避免在主线程碰网络 socket；
                         * server 先赋值，取消或失败时 finally 一定能关掉。
                         */
                        val url = withContext(Dispatchers.IO) {
                            started.start(firmware.file, token, request.localPort).getOrElse { error ->
                                throw OtaLinkException(error.message ?: "手机本地固件服务启动失败。")
                            }
                        }
                        validateUrl(url, requireHttps = false)
                        ota.localServiceStarted(ip, url)
                        val payload = packStart(
                            ssid = ssid,
                            password = password,
                            url = url,
                            flags = FLAG_LOCAL_HTTP or FLAG_AUTO_REBOOT,
                            token = token,
                            sha256 = firmware.sha256,
                        )
                        ble.startOta(payload)
                        ota.beginConnectingWifi()
                        ota.note(
                            "本地服务 $url 已启动（${formatByteCount(firmware.size)}）。" +
                                "START 已发送（${payload.size} 字节），设备正在连接 WiFi…",
                        )
                    }
                }
                pollStatus(local = request.source == OtaSource.Hotspot)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ota.finishFailure(explain(error))
            } finally {
                /*
                 * 终态、失败、取消都会走这里。server 可能还没启动（null），stop 本身幂等。
                 * 设备下载完固件后不再需要这个端口，及时关掉也避免下次端口占用。
                 * 取消时也要保证 stop 执行完，所以套 NonCancellable。
                 */
                withContext(NonCancellable + Dispatchers.IO) {
                    server?.stop()
                }
                localServer = null
                if (request.source == OtaSource.Hotspot) ota.localServiceStopped()
                updateJob = null
            }
        }
    }

    /** 远程 HTTPS 分支：和本地服务接入前完全一致，flags=0、token/SHA 留空。 */
    private suspend fun startRemote(request: OtaRequest) {
        val ssid = request.wifiSsid.trim()
        val password = request.wifiPassword
        val url = request.serverUrl.trim()
        validateCommon(ssid, password)
        validateUrl(url, requireHttps = true)
        ensureUpdateReady()
        val payload = packStart(
            ssid = ssid,
            password = password,
            url = url,
            flags = 0,
            token = "",
            sha256 = "",
        )
        ble.startOta(payload)
        ota.beginConnectingWifi()
        ota.note("升级命令已发送（${payload.size} 字节），设备正在连接 WiFi…")
    }

    /** 读 OTA 信息并确认设备当前没在升级。两种来源发 START 之前都要过这一关。 */
    private suspend fun ensureUpdateReady() {
        val info = readInfo()
        info.version?.let { ota.applyDeviceVersion(it) }
        if (!info.ota) {
            throw OtaLinkException("设备返回 ota_info.ota=false，这个固件不支持 OTA。")
        }
        val before = readStatus()
        before.blockingReason()?.let { throw OtaLinkException(it) }
    }

    override fun cancelUpdate() {
        /* 先关本地服务，让设备正在进行的下载立刻断开，再让 BLE 任务收尾并补发 ABORT。 */
        localServer?.stop()
        localServer = null
        updateJob?.cancel()
        updateJob = null
        scope.launch {
            try {
                requireConnected()
                ble.abortOta()
                ota.finishAborted("设备已受理中止请求，固件没有被改动。")
            } catch (error: CancellationException) {
                throw error
            } catch (error: BleException) {
                if ((error.message ?: "").startsWith("设备拒绝了这次写入（14）")) {
                    /* 设备端 ota_abort 在没在升级时返回 INVALID_STATE → ATT 0x0E。 */
                    ota.finishAborted("设备当前没有在升级，没有需要中止的任务。")
                } else {
                    ota.finishFailure("取消请求没有成功：${explain(error)}")
                }
            } catch (error: Exception) {
                ota.finishFailure("取消请求没有成功：${explain(error)}")
            }
        }
    }

    /** 每秒读一次 ota_stat，按固件回报的 state 驱动界面。 */
    private suspend fun pollStatus(local: Boolean) {
        val startedAt = System.currentTimeMillis()
        var lastState = STATE_IDLE
        var sawActive = false
        var lastProgressStep = -1
        var readFailures = 0

        while (true) {
            delay(POLL_INTERVAL_MS)

            if (!ble.connected.value || ble.phase.value != LinkPhase.Ready) {
                throw OtaLinkException(
                    when {
                        lastState == STATE_VERIFYING || lastState == STATE_READY_REBOOT ->
                            "连接已断开。设备可能已经写入完成并重启；升级结果未知，重连后请读取版本确认。"
                        sawActive ->
                            "连接已断开。设备可能还在下载或写入；重新连接后请读取版本确认。"
                        else ->
                            "连接已断开，升级结果未知。重新连接后请再读一次版本确认。"
                    },
                )
            }
            if (System.currentTimeMillis() - startedAt > UPDATE_TIMEOUT_MS) {
                throw OtaLinkException(
                    "等待升级结果超时。设备可能还在后台下载，请重新连接后读取版本确认。",
                )
            }

            val status = try {
                readStatus()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                readFailures++
                if (readFailures >= MAX_READ_FAILURES) {
                    throw OtaLinkException(
                        "连续 $MAX_READ_FAILURES 次读不到升级状态：${explain(error)}。设备可能仍在升级，重连后请读取版本确认。",
                    )
                }
                continue
            }
            readFailures = 0

            when (status.state) {
                STATE_IDLE -> {
                    if (sawActive) {
                        throw OtaLinkException("设备回到了空闲状态，没有报告升级成功。请重新读取版本确认。")
                    }
                    /* 刚发完 START 时给设备一点时间切状态，超时还没动静才算没开始。 */
                    if (System.currentTimeMillis() - startedAt > START_GRACE_MS) {
                        throw OtaLinkException("设备一直没有开始升级（状态停为空闲）。请重新读取版本确认。")
                    }
                }

                STATE_CONNECTING_WIFI -> {
                    sawActive = true
                    if (lastState != STATE_CONNECTING_WIFI) {
                        ota.beginConnectingWifi()
                        lastState = STATE_CONNECTING_WIFI
                    }
                }

                STATE_DOWNLOADING -> {
                    sawActive = true
                    if (lastState != STATE_DOWNLOADING) {
                        ota.beginDownload()
                        lastState = STATE_DOWNLOADING
                    }
                    val fraction = when {
                        status.total > 0L -> status.read.toFloat() / status.total.toFloat()
                        status.progress > 0 -> status.progress / 100f
                        else -> 0f
                    }
                    ota.updateProgress(fraction)
                    val percent = (fraction.coerceIn(0f, 1f) * 100).toInt()
                    /* 进度按 5% 一档更新说明，避免每秒都改一次文本。 */
                    if (percent / 5 != lastProgressStep) {
                        lastProgressStep = percent / 5
                        ota.note(
                            if (status.total > 0L) {
                                if (local) {
                                    "正在从手机下载固件：${status.read} / ${status.total} 字节（$percent%）"
                                } else {
                                    "正在下载固件：${status.read} / ${status.total} 字节（$percent%）"
                                }
                            } else {
                                if (local) "正在从手机下载固件（$percent%）" else "正在下载固件（$percent%）"
                            },
                        )
                    }
                }

                STATE_VERIFYING -> {
                    sawActive = true
                    if (lastState != STATE_VERIFYING) {
                        ota.beginVerify()
                        lastState = STATE_VERIFYING
                    }
                }

                STATE_READY_REBOOT -> {
                    ota.finishReadyReboot()
                    return
                }

                STATE_SUCCESS -> {
                    ota.finishSuccess(null)
                    return
                }

                STATE_FAILED -> {
                    ota.finishFailure("升级失败：${status.error.ifBlank { "设备没有给出原因" }}")
                    return
                }

                STATE_ABORTED -> {
                    ota.finishAborted("设备报告升级已中止，固件没有被改动。")
                    return
                }

                else -> throw OtaLinkException("设备回报了未知的升级状态 ${status.state}。")
            }
        }
    }

    private fun requireConnected() {
        if (!ble.connected.value || ble.phase.value != LinkPhase.Ready) {
            throw OtaLinkException("设备未连接。请先连接灯带，连上后再升级。")
        }
        /* ota_stat 的 JSON 最长约 200 字节，MTU 太小时读回来会被截断成半个 JSON。 */
        if (ble.payloadBudget() < OTA_MIN_PAYLOAD) {
            throw OtaLinkException(
                "当前 BLE 包长只有 ${ble.payloadBudget()} 字节，读不全设备的 OTA 信息。请断开后重新连接灯带再试。",
            )
        }
    }

    private suspend fun readInfo(): OtaInfo {
        val raw = ble.readOtaInfo()
        return try {
            val json = JSONObject(raw)
            OtaInfo(
                version = json.optString("version").trim().takeIf { it.isNotEmpty() },
                partition = json.optString("partition").trim().takeIf { it.isNotEmpty() },
                ota = json.optBoolean("ota", true),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw OtaLinkException("设备返回的 OTA 信息不是合法 JSON。")
        }
    }

    private suspend fun readStatus(): OtaStatus {
        val raw = ble.readOtaStatus()
        return try {
            val json = JSONObject(raw)
            OtaStatus(
                state = json.optInt("state", -1),
                progress = json.optInt("progress", 0),
                read = json.optLong("read", 0L),
                total = json.optLong("total", 0L),
                error = json.optString("error").trim(),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throw OtaLinkException("设备返回的升级状态不是合法 JSON。")
        }
    }

    /** 设备端字段上限：ssid 32、password 64、url 255（见 [packStart] 的整包检查）。 */
    private fun validateCommon(ssid: String, password: String) {
        val ssidBytes = ssid.toByteArray(Charsets.UTF_8)
        val passBytes = password.toByteArray(Charsets.UTF_8)
        when {
            ssidBytes.isEmpty() ->
                throw OtaLinkException("WiFi 名称不能为空。")
            ssidBytes.size > SSID_MAX ->
                throw OtaLinkException("WiFi 名称太长（最多 $SSID_MAX 字节）。")
            passBytes.size > PASS_MAX ->
                throw OtaLinkException("WiFi 密码太长（最多 $PASS_MAX 字节）。")
        }
    }

    private fun validateUrl(url: String, requireHttps: Boolean) {
        val prefix = if (requireHttps) "https://" else "http://"
        val urlBytes = url.toByteArray(Charsets.UTF_8)
        when {
            !url.startsWith(prefix) || url.length <= prefix.length ->
                throw OtaLinkException("固件地址必须是 ${prefix} 开头。")
            url.any { it.isWhitespace() } ->
                throw OtaLinkException("固件地址里不能有空格。")
            urlBytes.size > URL_MAX ->
                throw OtaLinkException("固件地址太长（最多 $URL_MAX 字节）。")
        }
    }

    private fun localUrl(ip: String, port: Int): String =
        "http://$ip:$port/${OtaLocalServer.FIRMWARE_PATH}"

    private fun startPacketSize(
        ssid: String,
        password: String,
        url: String,
        tokenLen: Int,
        shaLen: Int,
    ): Int = HEADER_LEN +
        ssid.toByteArray(Charsets.UTF_8).size +
        password.toByteArray(Charsets.UTF_8).size +
        url.toByteArray(Charsets.UTF_8).size +
        tokenLen +
        shaLen

    /** 128-bit 随机 token，16 进制 32 字符。设备通过 X-OTA-Token 请求头带回来。 */
    private fun randomToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        SecureRandom().nextBytes(bytes)
        return buildString(bytes.size * 2) {
            for (value in bytes) {
                val unsigned = value.toInt() and 0xff
                append(HEX[unsigned ushr 4])
                append(HEX[unsigned and 0x0f])
            }
        }
    }

    /**
     * 打包 START。远程模式 token/sha 为空；本地模式带 32 字符 token 和 64 字符 SHA-256。
     * 设备端 `ota_cmd` 单包上限 [OTA_CMD_MAX]，这里先算总长再分配。
     */
    private fun packStart(
        ssid: String,
        password: String,
        url: String,
        flags: Int,
        token: String,
        sha256: String,
    ): ByteArray {
        val ssidBytes = ssid.toByteArray(Charsets.UTF_8)
        val passBytes = password.toByteArray(Charsets.UTF_8)
        val urlBytes = url.toByteArray(Charsets.UTF_8)
        val tokenBytes = token.toByteArray(Charsets.US_ASCII)
        val shaBytes = sha256.toByteArray(Charsets.US_ASCII)
        val total = HEADER_LEN + ssidBytes.size + passBytes.size + urlBytes.size +
            tokenBytes.size + shaBytes.size
        if (total > OTA_CMD_MAX) {
            throw OtaLinkException(
                "升级参数共 $total 字节，超过 BLE 单包上限 $OTA_CMD_MAX 字节。" +
                    "请缩短固件地址或 WiFi 名称/密码。",
            )
        }
        val out = ByteArray(total)
        out[0] = CMD_START.toByte()
        out[1] = flags.toByte()
        out[2] = ssidBytes.size.toByte()
        out[3] = passBytes.size.toByte()
        out[4] = urlBytes.size.toByte()
        out[5] = tokenBytes.size.toByte()
        out[6] = shaBytes.size.toByte()
        var at = HEADER_LEN
        ssidBytes.copyInto(out, at)
        at += ssidBytes.size
        passBytes.copyInto(out, at)
        at += passBytes.size
        urlBytes.copyInto(out, at)
        at += urlBytes.size
        tokenBytes.copyInto(out, at)
        at += tokenBytes.size
        shaBytes.copyInto(out, at)
        return out
    }

    private fun explain(error: Exception): String {
        val raw = (error as? BleException)?.message ?: error.message ?: "未知错误"
        return when {
            raw.startsWith("设备拒绝了这次写入（5）") ->
                "设备拒绝了写入（状态 5：鉴权未完成）。请先完成设备鉴权再升级。"
            raw.startsWith("设备拒绝了这次写入（17）") ->
                "设备正忙或有升级还没结束（状态 17），本次没有开始新的升级。"
            raw.startsWith("设备拒绝了这次写入（14）") ->
                "设备拒绝了 START 命令（状态 14）。请检查固件地址和 WiFi 参数后重试。"
            raw.startsWith("没有找到服务") || raw.startsWith("没有找到特征") ->
                "设备上没有 OTA 服务（ota_svc）。这个固件可能还不支持 BLE OTA。"
            else -> raw
        }
    }

    private data class OtaInfo(
        val version: String?,
        val partition: String?,
        val ota: Boolean,
    )

    private data class OtaStatus(
        val state: Int,
        val progress: Int,
        val read: Long,
        val total: Long,
        val error: String,
    ) {
        val stateLabel: String
            get() = when (state) {
                STATE_IDLE -> "空闲"
                STATE_CONNECTING_WIFI -> "连接 WiFi"
                STATE_DOWNLOADING -> "下载中"
                STATE_VERIFYING -> "校验中"
                STATE_READY_REBOOT -> "等待重启"
                STATE_SUCCESS -> "成功"
                STATE_FAILED -> "失败"
                STATE_ABORTED -> "已中止"
                else -> "未知（$state）"
            }

        /** 设备正在升级或有新固件等重启时的提示；其他状态返回 null。 */
        fun busyText(): String? = when (state) {
            STATE_CONNECTING_WIFI, STATE_DOWNLOADING, STATE_VERIFYING ->
                "设备现在正在升级（$stateLabel），请等它结束或先取消。"
            STATE_READY_REBOOT, STATE_SUCCESS ->
                "设备已有新固件等待重启，重启并重新连接后再检查。"
            else -> null
        }

        fun blockingReason(): String? = when (state) {
            STATE_CONNECTING_WIFI, STATE_DOWNLOADING, STATE_VERIFYING ->
                "设备正在升级中（$stateLabel），请等它结束或先取消，再开始新的升级。"
            STATE_READY_REBOOT, STATE_SUCCESS ->
                "设备已有新固件等待重启，请先重启设备并重新连接，再开始新的升级。"
            else -> null
        }
    }

    private class OtaLinkException(message: String) : Exception(message)

    private companion object {
        const val POLL_INTERVAL_MS = 1_000L
        const val UPDATE_TIMEOUT_MS = 15 * 60 * 1_000L
        const val START_GRACE_MS = 8_000L
        const val MAX_READ_FAILURES = 3

        /** 设备端 ota_stat 的缓冲区是 224 字节，低于这个包长会读到截断的 JSON。 */
        const val OTA_MIN_PAYLOAD = 224

        const val STATE_IDLE = 0
        const val STATE_CONNECTING_WIFI = 1
        const val STATE_DOWNLOADING = 2
        const val STATE_VERIFYING = 3
        const val STATE_READY_REBOOT = 4
        const val STATE_SUCCESS = 5
        const val STATE_FAILED = 6
        const val STATE_ABORTED = 7

        const val CMD_START = 1
        const val HEADER_LEN = 7
        const val SSID_MAX = 32
        const val PASS_MAX = 64
        const val URL_MAX = 255

        /* START flags：bit0 = local_http，bit1 = auto_reboot。 */
        const val FLAG_LOCAL_HTTP = 1
        const val FLAG_AUTO_REBOOT = 2

        const val TOKEN_BYTES = 16
        const val TOKEN_HEX_LEN = TOKEN_BYTES * 2
        const val SHA_HEX_LEN = 64
        const val HEX = "0123456789abcdef"
    }
}
