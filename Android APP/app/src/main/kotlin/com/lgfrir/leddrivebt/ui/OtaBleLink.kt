package com.lgfrir.leddrivebt.ui

import com.lgfrir.leddrivebt.ble.BleException
import com.lgfrir.leddrivebt.ble.LightBleClient
import com.lgfrir.leddrivebt.ble.LinkPhase
import com.lgfrir.leddrivebt.protocol.OTA_CMD_MAX
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/*
 * OTA 页面的 BLE 实现。
 *
 * 设备端协议（ESP-IDF 已实现）：
 *   ota_info  读  {"version":"1.0.0","partition":"factory","ota":true}
 *   ota_stat  读  {"state":0,"progress":0,"read":0,"total":0,"version":"1.0.0","error":""}
 *   ota_cmd   写  START: [0]=1,[1]=flags,[2]=ssid_len,[3]=pass_len,[4]=url_len,
 *                     [5]=token_len,[6]=sha_len,[7..]=ssid,password,url,token,sha256(hex)
 *                 ABORT: [0]=2
 *
 * 这里只复用现有连接和 LightBleClient 的 gate，不新开连接。
 * 远程 HTTPS 升级不带 token、不带 SHA（设备端 sha 为空就跳过校验）；
 * 手机本地 HTTP 服务还没实现，本地热点模式不会走到 START。
 */
class OtaBleLink(
    private val ble: LightBleClient,
    private val ota: OtaController,
    private val scope: CoroutineScope,
) : OtaLink {
    private var versionJob: Job? = null
    private var updateJob: Job? = null

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
                if (request.source != OtaSource.Server) {
                    ota.finishCheck(
                        "手机本地 HTTP 服务下一步实现，本地热点升级还没有开始；设备固件没有被改动。",
                    )
                    return@launch
                }

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
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ota.finishFailure("检查更新失败：${explain(error)}")
            } finally {
                updateJob = null
            }
        }
    }

    override fun startUpdate(request: OtaRequest) {
        if (updateJob?.isActive == true) return
        updateJob = scope.launch {
            try {
                requireConnected()
                if (request.source != OtaSource.Server) {
                    ota.finishCheck("手机本地 HTTP 服务下一步实现，本地热点升级没有发送任何命令。")
                    return@launch
                }

                val ssid = request.wifiSsid.trim()
                val password = request.wifiPassword
                val url = request.serverUrl.trim()
                validate(ssid, password, url)

                val info = readInfo()
                info.version?.let { ota.applyDeviceVersion(it) }
                if (!info.ota) {
                    throw OtaLinkException("设备返回 ota_info.ota=false，这个固件不支持 OTA。")
                }
                val before = readStatus()
                before.blockingReason()?.let { throw OtaLinkException(it) }

                val payload = packStart(ssid, password, url)
                ble.startOta(payload)
                ota.beginConnectingWifi()
                ota.note("升级命令已发送（${payload.size} 字节），设备正在连接 WiFi…")
                pollStatus()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                ota.finishFailure(explain(error))
            } finally {
                updateJob = null
            }
        }
    }

    override fun cancelUpdate() {
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
    private suspend fun pollStatus() {
        val startedAt = System.currentTimeMillis()
        var lastState = STATE_IDLE
        var sawActive = false
        var lastProgressStep = -1
        var readFailures = 0

        while (true) {
            delay(POLL_INTERVAL_MS)

            if (!ble.connected.value || ble.phase.value != LinkPhase.Ready) {
                throw OtaLinkException(
                    "连接已断开，升级结果未知。设备可能还在下载；重新连接后请再读一次版本确认。",
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
                                "正在下载固件：${status.read} / ${status.total} 字节（$percent%）"
                            } else {
                                "正在下载固件（$percent%）"
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

    /**
     * 校验 START 参数。设备端字段上限：ssid 32、password 64、url 255；
     * 整包还必须压进 ota_cmd 的 240 字节（MTU 247）。
     */
    private fun validate(ssid: String, password: String, url: String) {
        val ssidBytes = ssid.toByteArray(Charsets.UTF_8)
        val passBytes = password.toByteArray(Charsets.UTF_8)
        val urlBytes = url.toByteArray(Charsets.UTF_8)
        when {
            !url.startsWith("https://") || url.length <= "https://".length ->
                throw OtaLinkException("固件地址必须是 https:// 开头。")
            url.any { it.isWhitespace() } ->
                throw OtaLinkException("固件地址里不能有空格。")
            ssidBytes.isEmpty() ->
                throw OtaLinkException("WiFi 名称不能为空。")
            ssidBytes.size > SSID_MAX ->
                throw OtaLinkException("WiFi 名称太长（最多 $SSID_MAX 字节）。")
            passBytes.size > PASS_MAX ->
                throw OtaLinkException("WiFi 密码太长（最多 $PASS_MAX 字节）。")
            urlBytes.size > URL_MAX ->
                throw OtaLinkException("固件地址太长（最多 $URL_MAX 字节）。")
        }
        val total = HEADER_LEN + ssidBytes.size + passBytes.size + urlBytes.size
        if (total > OTA_CMD_MAX) {
            throw OtaLinkException(
                "升级参数共 $total 字节，超过 BLE 单包上限 $OTA_CMD_MAX 字节。请缩短固件地址或 WiFi 名称/密码。",
            )
        }
    }

    private fun packStart(ssid: String, password: String, url: String): ByteArray {
        val ssidBytes = ssid.toByteArray(Charsets.UTF_8)
        val passBytes = password.toByteArray(Charsets.UTF_8)
        val urlBytes = url.toByteArray(Charsets.UTF_8)
        val out = ByteArray(HEADER_LEN + ssidBytes.size + passBytes.size + urlBytes.size)
        out[0] = CMD_START.toByte()
        /* bit0 local_http=0（远程 HTTPS），bit1 auto_reboot=0：留给用户自己重启，App 才能读到 READY_REBOOT。 */
        out[1] = 0
        out[2] = ssidBytes.size.toByte()
        out[3] = passBytes.size.toByte()
        out[4] = urlBytes.size.toByte()
        out[5] = 0 /* 远程 HTTPS 不需要 token */
        out[6] = 0 /* SHA-256 留空，设备跳过校验 */
        var at = HEADER_LEN
        ssidBytes.copyInto(out, at)
        at += ssidBytes.size
        passBytes.copyInto(out, at)
        at += passBytes.size
        urlBytes.copyInto(out, at)
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
    }
}
