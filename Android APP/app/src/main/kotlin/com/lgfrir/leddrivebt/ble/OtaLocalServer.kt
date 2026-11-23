package com.lgfrir.leddrivebt.ble

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 手机本地固件 HTTP 服务。
 *
 * 只做这一件事：在 `0.0.0.0:<port>` 上监听，用随机 token 校验后把选中的固件文件
 * 原样发给 `GET/HEAD /firmware.bin`。设备端只需要一个最简单的 HTTP/1.x 客户端，
 * 所以这里也只实现固定路径、单个文件、`Connection: close` 这一种形态。
 *
 * 约束：
 * - 只依赖标准库（ServerSocket / File / MessageDigest），不引入网络框架；
 * - token 不匹配一律 403，路径不对 404，方法不对 405；
 * - HEAD 只回响应头，GET 才回文件字节；
 * - [stop] 幂等，会同时关闭监听 socket 和所有进行中的连接。
 *
 * 这个类不读 Android API，方便在 JVM 单元测试里直接跑。手机 IP 由调用方通过
 * [host] 传进来（见 ui/OtaLocal.kt 的 phoneWifiIpv4）。
 */
class OtaServerException(message: String) : Exception(message)

class OtaLocalServer(private val host: String) {
    private val running = AtomicBoolean(false)
    private val clients = ConcurrentHashMap.newKeySet<Socket>()

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var firmware: File? = null

    @Volatile
    private var token: String = ""

    @Volatile
    private var acceptThread: Thread? = null

    @Volatile
    private var port: Int = 0

    val isRunning: Boolean
        get() = running.get()

    /** 实际绑定的端口。请求端口为 0 时这里会是系统分配的端口。 */
    val boundPort: Int
        get() = port

    /** 设备要访问的地址。必须在 [start] 成功之后取。 */
    fun url(path: String = FIRMWARE_PATH): String {
        val name = path.trim().trimStart('/').ifEmpty { FIRMWARE_PATH }
        return "http://$host:$port/$name"
    }

    /**
     * 启动服务并返回设备要下载的 URL。
     *
     * 失败时返回 [Result.failure]，消息可以直接显示给用户；不会留下半开的 socket。
     * 和 [stop] 互斥：取消操作不会在 bind 过程中把 socket 状态改乱。
     */
    @Synchronized
    fun start(file: File, token: String, port: Int): Result<String> {
        if (!file.isFile) {
            return Result.failure(OtaServerException("固件文件不存在：${file.name}"))
        }
        if (file.length() <= 0L) {
            return Result.failure(OtaServerException("固件文件是空的，无法提供服务。"))
        }
        if (token.isBlank()) {
            return Result.failure(OtaServerException("本地固件服务的 token 不能为空。"))
        }
        /* 上一次没停干净时先收掉，避免同一个端口被占用。 */
        stop()

        val socket = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress("0.0.0.0", port), BACKLOG)
            }
        } catch (error: Exception) {
            return Result.failure(OtaServerException(bindFailure(port, error)))
        }

        firmware = file
        this.token = token
        this.port = socket.localPort
        server = socket
        running.set(true)
        try {
            acceptThread = Thread({ acceptLoop(socket) }, "ota-local-http").apply {
                isDaemon = true
                start()
            }
        } catch (error: Exception) {
            stop()
            return Result.failure(
                OtaServerException("手机本地固件服务启动失败：${error.message ?: "无法创建监听线程"}"),
            )
        }
        return Result.success(url())
    }

    /** 关闭监听和所有连接。重复调用没有副作用。 */
    @Synchronized
    fun stop() {
        running.set(false)
        server?.let { runCatching { it.close() } }
        server = null
        clients.forEach { client -> runCatching { client.close() } }
        clients.clear()
        acceptThread?.interrupt()
        acceptThread = null
        firmware = null
        token = ""
        port = 0
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                return
            } catch (_: SecurityException) {
                return
            }
            if (!running.get()) {
                runCatching { client.close() }
                return
            }
            clients.add(client)
            if (!running.get()) {
                clients.remove(client)
                runCatching { client.close() }
                return
            }
            try {
                Thread({ serve(client) }, "ota-local-http-client").apply {
                    isDaemon = true
                    start()
                }
            } catch (_: Exception) {
                clients.remove(client)
                runCatching { client.close() }
            }
        }
    }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = READ_TIMEOUT_MS
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())
            val request = readRequest(input)
            if (request == null) {
                writeError(output, 400, "Bad Request", headOnly = true)
            } else {
                handle(request, output)
            }
            output.flush()
        } catch (_: Exception) {
            /* 设备断连、手机切 WiFi 都会让读写抛异常；这里只负责关掉这条连接。 */
        } finally {
            clients.remove(client)
            runCatching { client.close() }
        }
    }

    private fun handle(request: HttpRequest, output: BufferedOutputStream) {
        val headOnly = request.method == "HEAD"
        if (request.method != "GET" && !headOnly) {
            writeError(output, 405, "Method Not Allowed", headOnly)
            return
        }
        val path = request.target.substringBefore('?').substringBefore('#')
        if (path != "/$FIRMWARE_PATH") {
            writeError(output, 404, "Not Found", headOnly)
            return
        }
        if (!tokenMatches(request.headers[TOKEN_HEADER])) {
            writeError(output, 403, "Forbidden", headOnly)
            return
        }
        val file = firmware
        if (file == null || !file.isFile || file.length() <= 0L) {
            writeError(output, 404, "Not Found", headOnly)
            return
        }
        val length = file.length()
        output.writeAscii("HTTP/1.1 200 OK\r\n")
        output.writeAscii("Content-Type: application/octet-stream\r\n")
        output.writeAscii("Content-Length: $length\r\n")
        output.writeAscii("Content-Disposition: attachment; filename=\"firmware.bin\"\r\n")
        output.writeAscii("Cache-Control: no-store\r\n")
        output.writeAscii("Connection: close\r\n")
        output.writeAscii("\r\n")
        if (headOnly) return

        FileInputStream(file).use { source ->
            val buffer = ByteArray(STREAM_BUFFER)
            while (true) {
                val read = source.read(buffer)
                if (read < 0) break
                output.write(buffer, 0, read)
            }
        }
    }

    private fun tokenMatches(provided: String?): Boolean {
        if (provided == null) return false
        val expected = token.toByteArray(Charsets.US_ASCII)
        val actual = provided.toByteArray(Charsets.US_ASCII)
        if (expected.isEmpty()) return false
        return MessageDigest.isEqual(expected, actual)
    }

    private fun writeError(output: BufferedOutputStream, code: Int, reason: String, headOnly: Boolean) {
        val body = "$code $reason\n".toByteArray(Charsets.US_ASCII)
        output.writeAscii("HTTP/1.1 $code $reason\r\n")
        output.writeAscii("Content-Type: text/plain; charset=us-ascii\r\n")
        output.writeAscii("Content-Length: ${body.size}\r\n")
        output.writeAscii("Cache-Control: no-store\r\n")
        output.writeAscii("Connection: close\r\n")
        output.writeAscii("\r\n")
        if (!headOnly) output.write(body)
    }

    /**
     * 读到空行为止。请求头和请求行加起来超过 [MAX_HEAD_BYTES] 视为坏请求，
     * 避免某个客户端一直发数据把线程占住。
     */
    private fun readRequest(input: BufferedInputStream): HttpRequest? {
        val head = readHeaderBlock(input) ?: return null
        val text = String(head, Charsets.ISO_8859_1)
        val lines = text.split("\r\n")
        val requestLine = lines.firstOrNull()?.trim().orEmpty()
        val parts = requestLine.split(' ').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase()
            if (name.isEmpty()) continue
            headers[name] = line.substring(colon + 1).trim()
        }
        return HttpRequest(parts[0].uppercase(), parts[1], headers)
    }

    private fun readHeaderBlock(input: BufferedInputStream): ByteArray? {
        val buffer = ByteArrayOutputStream(512)
        var state = 0
        while (buffer.size() < MAX_HEAD_BYTES) {
            val value = input.read()
            if (value < 0) return null
            buffer.write(value)
            state = when {
                state == 0 && value == CR -> 1
                state == 1 && value == LF -> 2
                state == 2 && value == CR -> 3
                state == 3 && value == LF -> 4
                value == CR -> 1
                else -> 0
            }
            if (state == 4) return buffer.toByteArray()
        }
        return null
    }

    private fun BufferedOutputStream.writeAscii(text: String) {
        write(text.toByteArray(Charsets.US_ASCII))
    }

    private fun bindFailure(port: Int, error: Exception): String = when (error) {
        is BindException -> "端口 $port 已被占用，手机本地固件服务没有启动。请关掉占用该端口的 App 后重试。"
        is SecurityException -> "系统不允许监听端口 $port，手机本地固件服务没有启动。"
        else -> "手机本地固件服务启动失败：${error.message ?: error.javaClass.simpleName}"
    }

    private data class HttpRequest(
        val method: String,
        val target: String,
        val headers: Map<String, String>,
    )

    companion object {
        const val FIRMWARE_PATH = "firmware.bin"
        const val DEFAULT_PORT = 8080

        private const val TOKEN_HEADER = "x-ota-token"
        private const val BACKLOG = 8
        private const val MAX_HEAD_BYTES = 16 * 1024
        private const val READ_TIMEOUT_MS = 15_000
        private const val STREAM_BUFFER = 64 * 1024
        private const val CR = '\r'.code
        private const val LF = '\n'.code
    }
}
