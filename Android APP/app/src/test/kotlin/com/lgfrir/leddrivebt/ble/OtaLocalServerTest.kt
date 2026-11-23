package com.lgfrir.leddrivebt.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.nio.file.Files

/**
 * OtaLocalServer 的纯 JVM 测试：绑定 127.0.0.1 的临时端口，用 HttpURLConnection
 * 模拟设备端请求，验证 token、GET/HEAD、Content-Length 和 404。
 */
class OtaLocalServerTest {
    private val token = "0123456789abcdef0123456789abcdef"

    @Test
    fun servesFirmwareOnlyWithToken() {
        val payload = ByteArray(4096) { (it % 251).toByte() }
        val file = Files.createTempFile("ota-test", ".bin").toFile()
        file.writeBytes(payload)
        val server = OtaLocalServer("127.0.0.1")
        try {
            val url = server.start(file, token, 0).getOrThrow()
            assertTrue(server.isRunning)
            assertTrue(server.boundPort > 0)
            assertEquals("http://127.0.0.1:${server.boundPort}/firmware.bin", url)

            val ok = request(url, "GET", token)
            assertEquals(200, ok.code)
            assertEquals(payload.size.toLong(), ok.contentLength)
            assertArrayEquals(payload, ok.body)

            val head = request(url, "HEAD", token)
            assertEquals(200, head.code)
            assertEquals(payload.size.toLong(), head.contentLength)
            assertEquals(0, head.body.size)

            val denied = request(url, "GET", "wrong-token")
            assertEquals(403, denied.code)

            val noToken = request(url, "GET", null)
            assertEquals(403, noToken.code)

            val otherPath = request(url.replace("firmware.bin", "other.bin"), "GET", token)
            assertEquals(404, otherPath.code)
        } finally {
            server.stop()
            file.delete()
        }
        assertFalse(server.isRunning)
    }

    @Test
    fun servesMinimalHttp10Get() {
        val payload = "firmware-bytes-for-the-device".toByteArray(Charsets.US_ASCII)
        val file = Files.createTempFile("ota-test-http10", ".bin").toFile()
        file.writeBytes(payload)
        val server = OtaLocalServer("127.0.0.1")
        try {
            server.start(file, token, 0).getOrThrow()
            Socket("127.0.0.1", server.boundPort).use { socket ->
                socket.soTimeout = 3_000
                val request = "GET /firmware.bin HTTP/1.0\r\nX-OTA-Token: $token\r\n\r\n"
                socket.getOutputStream().apply {
                    write(request.toByteArray(Charsets.US_ASCII))
                    flush()
                }
                val response = socket.getInputStream().readBytes()
                val text = String(response, Charsets.ISO_8859_1)
                assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"))
                assertTrue(text.contains("Content-Length: ${payload.size}\r\n"))
                val bodyStart = text.indexOf("\r\n\r\n") + 4
                assertArrayEquals(payload, response.copyOfRange(bodyStart, response.size))
            }
        } finally {
            server.stop()
            file.delete()
        }
    }

    @Test
    fun refusesEmptyOrMissingFirmware() {
        val server = OtaLocalServer("127.0.0.1")
        val missing = File(System.getProperty("java.io.tmpdir"), "ota-test-missing-${System.nanoTime()}.bin")
        assertTrue(server.start(missing, token, 0).isFailure)

        val empty = Files.createTempFile("ota-test-empty", ".bin").toFile()
        try {
            assertTrue(server.start(empty, token, 0).isFailure)
        } finally {
            empty.delete()
            server.stop()
        }
    }

    private data class Response(val code: Int, val contentLength: Long, val body: ByteArray)

    private fun request(url: String, method: String, token: String?): Response {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = method
        if (token != null) connection.setRequestProperty("X-OTA-Token", token)
        connection.connectTimeout = 3_000
        connection.readTimeout = 3_000
        try {
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.use { it.readBytes() } ?: ByteArray(0)
            val length = connection.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
            return Response(code, length, body)
        } finally {
            connection.disconnect()
        }
    }
}
