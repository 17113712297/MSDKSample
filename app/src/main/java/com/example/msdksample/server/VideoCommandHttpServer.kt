package com.example.msdksample.server

import android.util.Log
import com.example.msdksample.transfer.VideoUploadCommand
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class VideoCommandHttpServer(
    private val onCommandReceived: (VideoUploadCommand) -> Unit
) {
    companion object {
        const val PORT = 20032
        private const val TAG = "VideoCommandServer"
        private const val MAX_BODY_BYTES = 8 * 1024
    }

    private val running = AtomicBoolean(false)
    private val clientExecutor: ExecutorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "video-command-client").apply { isDaemon = true }
    }
    @Volatile private var serverSocket: ServerSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        Thread({ acceptRequests() }, "video-command-server").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
        clientExecutor.shutdownNow()
    }

    private fun acceptRequests() {
        try {
            ServerSocket(PORT).use { socket ->
                serverSocket = socket
                Log.i(TAG, "HTTP command server listening on port $PORT")
                while (running.get()) {
                    val client = try {
                        socket.accept()
                    } catch (error: Exception) {
                        if (running.get()) Log.w(TAG, "Failed to accept command connection", error)
                        break
                    }
                    clientExecutor.execute { handleClient(client) }
                }
            }
        } catch (error: Exception) {
            if (running.get()) Log.e(TAG, "Unable to listen on port $PORT", error)
        } finally {
            serverSocket = null
            running.set(false)
        }
    }

    private fun handleClient(socket: Socket) {
        socket.use { client ->
            client.soTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            val requestParts = requestLine.split(' ', limit = 3)
            if (requestParts.size < 2) {
                writeResponse(client.getOutputStream(), 400, "Invalid HTTP request")
                return
            }

            val method = requestParts[0].uppercase()
            val headers = readHeaders(reader)
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            if (contentLength !in 0..MAX_BODY_BYTES) {
                writeResponse(client.getOutputStream(), 413, "Request body is too large")
                return
            }
            val body = readBody(reader, contentLength)
            if (method !in setOf("GET", "POST")) {
                writeResponse(client.getOutputStream(), 405, "Only GET and POST are supported")
                return
            }

            val command = parseCommand(parseValues(requestParts[1], headers["content-type"], body))
            if (command == null) {
                writeResponse(client.getOutputStream(), 400, "siteId, deviceId, airlineKey and detectTimeCur(yyyyMMddHHmmss) are required")
                return
            }

            onCommandReceived(command)
            Log.i(TAG, "Accepted video command: siteId=${command.siteId}, deviceId=${command.deviceId}, detectTimeCur=${command.detectTimeCur}")
            writeResponse(client.getOutputStream(), 202, "accepted", resultCode = 1)
        }
    }

    private fun readHeaders(reader: BufferedReader): Map<String, String> {
        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) headers[line.substring(0, separator).trim().lowercase()] = line.substring(separator + 1).trim()
        }
        return headers
    }

    private fun readBody(reader: BufferedReader, contentLength: Int): String {
        if (contentLength == 0) return ""
        val chars = CharArray(contentLength)
        var offset = 0
        while (offset < chars.size) {
            val count = reader.read(chars, offset, chars.size - offset)
            if (count < 0) break
            offset += count
        }
        return String(chars, 0, offset)
    }

    private fun parseValues(requestTarget: String, contentType: String?, body: String): Map<String, String> {
        val values = linkedMapOf<String, String>()
        parseFormEncoded(requestTarget.substringAfter('?', ""), values)
        if (body.isBlank()) return values
        if (contentType?.contains("application/json", ignoreCase = true) == true) {
            runCatching {
                val json = JSONObject(body)
                listOf("siteId", "deviceId", "airlineKey", "detectTimeCur").forEach { key ->
                    if (json.has(key) && !json.isNull(key)) values[key] = json.get(key).toString()
                }
            }.onFailure { Log.w(TAG, "Invalid JSON command body", it) }
        } else {
            parseFormEncoded(body, values)
        }
        return values
    }

    private fun parseFormEncoded(text: String, values: MutableMap<String, String>) {
        text.split('&').filter { it.isNotBlank() }.forEach { entry ->
            val separator = entry.indexOf('=')
            val rawKey = if (separator >= 0) entry.substring(0, separator) else entry
            val rawValue = if (separator >= 0) entry.substring(separator + 1) else ""
            values[URLDecoder.decode(rawKey, StandardCharsets.UTF_8.name())] =
                URLDecoder.decode(rawValue, StandardCharsets.UTF_8.name())
        }
    }

    private fun parseCommand(values: Map<String, String>): VideoUploadCommand? {
        val siteId = values["siteId"]?.trim()?.toIntOrNull() ?: return null
        val deviceId = values["deviceId"]?.trim()?.toIntOrNull() ?: return null
        val airlineKey = values["airlineKey"]?.trim().orEmpty()
        val detectTimeCur = values["detectTimeCur"]?.trim().orEmpty()
        if (airlineKey.isEmpty() || !detectTimeCur.matches(Regex("\\d{14}"))) return null
        return VideoUploadCommand(siteId, deviceId, airlineKey, detectTimeCur)
    }

    private fun writeResponse(output: OutputStream, statusCode: Int, message: String, resultCode: Int = 2) {
        val body = "{\"resultCode\":$resultCode,\"message\":\"${message.replace("\"", "\\\"")}\"}"
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val statusText = when (statusCode) {
            202 -> "Accepted"
            400 -> "Bad Request"
            405 -> "Method Not Allowed"
            413 -> "Payload Too Large"
            else -> "OK"
        }
        output.write(("HTTP/1.1 $statusCode $statusText\r\nContent-Type: application/json; charset=UTF-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(StandardCharsets.UTF_8))
        output.write(bytes)
        output.flush()
    }
}
