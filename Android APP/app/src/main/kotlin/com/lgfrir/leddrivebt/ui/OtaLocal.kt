package com.lgfrir.leddrivebt.ui

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest
import java.util.Locale

/**
 * 本地升级选中的固件。只保留缓存文件和摘要，不在 Compose 状态里放大字节数组。
 *
 * [name] 是用户在文件选择器里看到的文件名；设备实际下载的路径固定为
 * `firmware.bin`，和这个显示名无关。
 */
data class OtaLocalFirmware(
    val file: File,
    val name: String,
    val size: Long,
    val sha256: String,
)

/**
 * 把系统文件选择器返回的 Uri 复制进 `cacheDir/ota-fw.bin`，边复制边算 SHA-256。
 *
 * 必须复制：`OpenDocument` 给的 Uri 只保证本次进程里可读，HTTP 服务是在别的线程
 * 里稍后才去读文件的，不能依赖临时授权一直有效。复制到固定文件名，服务端就不需要
 * 处理任意路径。
 */
object OtaFirmwareStore {
    private const val CACHE_FILE = "ota-fw.bin"
    private const val TEMP_FILE = "ota-fw.bin.part"
    private const val MAX_BYTES = 32L * 1024L * 1024L
    private const val BUFFER = 64 * 1024

    suspend fun import(context: Context, uri: Uri): OtaLocalFirmware = withContext(Dispatchers.IO) {
        val target = File(context.cacheDir, CACHE_FILE)
        val temp = File(context.cacheDir, TEMP_FILE)
        val displayName = displayName(context, uri)
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        var done = false
        try {
            val source = context.contentResolver.openInputStream(uri)
                ?: throw OtaFileException("打不开选中的文件，可能已被删除或没有读取权限。")
            source.use { input ->
                temp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        size += read
                        if (size > MAX_BYTES) {
                            throw OtaFileException(
                                "固件文件超过 ${formatByteCount(MAX_BYTES)}，不像是灯带固件，已取消导入。",
                            )
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (size <= 0L) throw OtaFileException("选中的文件是空的。")
            runCatching { target.delete() }
            if (!temp.renameTo(target)) {
                throw OtaFileException("无法把固件写入手机缓存。")
            }
            done = true
            OtaLocalFirmware(
                file = target,
                name = displayName,
                size = size,
                sha256 = digest.digest().toHex(),
            )
        } finally {
            if (!done) {
                runCatching { temp.delete() }
            }
        }
    }

    private fun displayName(context: Context, uri: Uri): String {
        val fromProvider = runCatching {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) cursor.getString(index) else null
                }
        }.getOrNull()
        return fromProvider?.trim()?.takeIf { it.isNotEmpty() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: "firmware.bin"
    }
}

class OtaFileException(message: String) : Exception(message)

/** 固件大小，页面和提示里共用。 */
fun formatByteCount(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    else -> "$bytes B"
}

/**
 * 当前 WiFi 的 IPv4 地址。
 *
 * 不用已废弃的 `WifiInfo.ipAddress`：Android 12 起它不保证和实际链路一致。
 * 这里走 ConnectivityManager 的 LinkProperties，并且优先看当前活动网络；
 * 活动网络是蜂窝时再找一遍 WiFi 网络，避免“手机连着 WiFi 但默认走流量”时读不到地址。
 * 读不到就返回 null，由界面提示“请先连接 WiFi”。
 */
@SuppressLint("MissingPermission")
// allNetworks 在 API 37 标了废弃，但没有等价的同步枚举接口；保留它作为
// “活动网络是蜂窝、WiFi 仍连着”时的回退。没有用废弃的 WifiInfo.ipAddress。
@Suppress("DEPRECATION")
fun phoneWifiIpv4(context: Context): String? {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
    return try {
        val active = manager.activeNetwork
        val networks = buildList {
            if (active != null) add(active)
            manager.allNetworks.forEach { network ->
                if (network != active) add(network)
            }
        }
        networks.firstNotNullOfOrNull { network ->
            val capabilities = manager.getNetworkCapabilities(network) ?: return@firstNotNullOfOrNull null
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return@firstNotNullOfOrNull null
            }
            manager.getLinkProperties(network)
                ?.linkAddresses
                ?.asSequence()
                ?.map { it.address }
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull {
                    !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress
                }
                ?.hostAddress
        }
    } catch (_: SecurityException) {
        null
    } catch (_: Exception) {
        null
    }
}

/**
 * 手机热点（AP）的 IPv4 地址。
 *
 * 手机开热点时不是 WiFi 客户端，[phoneWifiIpv4] 读不到地址；这里枚举网卡，
 * 优先 ap0 / wlan1 / swlan0 / softap，排除 rmnet/ccmni 等蜂窝接口。
 * 读不到返回 null，由界面提示用户确认热点已开启并点刷新。
 */
fun phoneHotspotIpv4(): String? {
    return try {
        val all = NetworkInterface.getNetworkInterfaces() ?: return null
        val list = mutableListOf<NetworkInterface>()
        while (all.hasMoreElements()) {
            list += all.nextElement()
        }
        fun rank(name: String): Int = when {
            name.startsWith("ap") -> 0
            name.startsWith("swlan") -> 0
            name.startsWith("softap") -> 0
            name.startsWith("wlan1") -> 1
            name.startsWith("wlan") -> 2
            name.startsWith("p2p") -> 3
            name.startsWith("rmnet") || name.startsWith("ccmni") ||
                name.startsWith("pdp") || name.startsWith("clat") -> 9
            else -> 5
        }

        list.asSequence()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { rank(it.name.lowercase(Locale.US)) }
            .mapNotNull { iface ->
                val addresses = iface.inetAddresses
                var found: String? = null
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address &&
                        !address.isLoopbackAddress &&
                        !address.isLinkLocalAddress &&
                        address.isSiteLocalAddress
                    ) {
                        found = address.hostAddress
                        break
                    }
                }
                found
            }
            .firstOrNull()
    } catch (_: SecurityException) {
        null
    } catch (_: Exception) {
        null
    }
}

private fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    val out = CharArray(size * 2)
    for (i in indices) {
        val value = this[i].toInt() and 0xff
        out[i * 2] = digits[value ushr 4]
        out[i * 2 + 1] = digits[value and 0x0f]
    }
    return String(out)
}
