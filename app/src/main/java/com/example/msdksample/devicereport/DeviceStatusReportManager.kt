package com.example.msdksample.devicereport

import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.URI
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DeviceStatusReportManager(
    private val streamAddressProvider: () -> String
) {

    companion object {
        private const val TAG = "DeviceStatusReport"
        private const val REPORT_INTERVAL_MS = 30_000L
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 5_000
        private const val SITE_ID = 11
        private const val DEVICE_ID = 1
        private const val REPORT_PORT = 7000
    }

    private val collector = DeviceStatusCollector()
    private val started = AtomicBoolean(false)
    private var executor: ScheduledExecutorService? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return

        executor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "device-status-report").apply { isDaemon = true }
        }.also { scheduledExecutor ->
            scheduledExecutor.scheduleAtFixedRate(
                { reportOnce() },
                0L,
                REPORT_INTERVAL_MS,
                TimeUnit.MILLISECONDS
            )
        }
    }

    fun reportNow() {
        if (!started.get()) return
        executor?.execute { reportOnce() }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        executor?.shutdownNow()
        executor = null
    }

    private fun reportOnce() {
        val streamAddress = streamAddressProvider.invoke().trim()
        val host = extractHost(streamAddress)
        if (host.isNullOrBlank()) {
            Log.w(TAG, "Skip report because RTMP host is unavailable: $streamAddress")
            return
        }

        val payload = collector.collect()
        val json = payload.toJsonString()

        runCatching {
            postMultipartJson(host, json)
        }.onSuccess { response ->
            Log.i(TAG, "Device status reported to $host:7000, response=$response")
        }.onFailure { error ->
            Log.w(TAG, "Device status report failed: ${error.message}")
        }
    }

    private fun extractHost(streamAddress: String): String? {
        if (streamAddress.isBlank()) return null

        val parsed = runCatching { URI(streamAddress).host }.getOrNull()
        if (!parsed.isNullOrBlank()) {
            return parsed
        }

        val normalized = streamAddress.removePrefix("rtmp://")
        val hostPort = normalized.substringBefore("/").substringBefore("?")
        return hostPort.substringBefore(":").ifBlank { null }
    }

    private fun postMultipartJson(host: String, json: String): String {
        val boundary = "----MSDKMerge${System.currentTimeMillis()}"
        val lineBreak = "\r\n"
        val bodyPrefix = buildString {
            append("--").append(boundary).append(lineBreak)
            append("Content-Disposition: form-data; name=\"file\"; filename=\"file.json\"")
            append(lineBreak)
            append("Content-Type: application/json; charset=UTF-8")
            append(lineBreak).append(lineBreak)
        }.toByteArray(StandardCharsets.UTF_8)
        val bodyJson = json.toByteArray(StandardCharsets.UTF_8)
        val bodySuffix = (lineBreak + "--" + boundary + "--" + lineBreak)
            .toByteArray(StandardCharsets.UTF_8)
        val bodyBytes = ByteArrayOutputStream().apply {
            write(bodyPrefix)
            write(bodyJson)
            write(bodySuffix)
        }.toByteArray()

        val requestPath = buildString {
            append("/sendDeviceData")
            append("?siteId=").append(SITE_ID)
            append("&deviceId=").append(DEVICE_ID)
            append("&file=file.json")
        }

        Socket().use { socket ->
            socket.soTimeout = READ_TIMEOUT_MS
            socket.connect(InetSocketAddress(host, REPORT_PORT), CONNECT_TIMEOUT_MS)

            val requestHeaders = buildString {
                append("POST ").append(requestPath).append(" HTTP/1.1").append(lineBreak)
                append("Host: ").append(host).append(":").append(REPORT_PORT).append(lineBreak)
                append("Accept: application/json").append(lineBreak)
                append("Connection: close").append(lineBreak)
                append("Content-Type: multipart/form-data; boundary=").append(boundary)
                    .append(lineBreak)
                append("Content-Length: ").append(bodyBytes.size).append(lineBreak)
                append(lineBreak)
            }.toByteArray(StandardCharsets.UTF_8)

            val output = socket.getOutputStream()
            output.write(requestHeaders)
            output.write(bodyBytes)
            output.flush()

            val responseBytes = ByteArrayOutputStream()
            BufferedInputStream(socket.getInputStream()).use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    val readCount = input.read(buffer)
                    if (readCount < 0) break
                    responseBytes.write(buffer, 0, readCount)
                }
            }

            val responseText = responseBytes.toString(StandardCharsets.UTF_8.name())
            val statusLine = responseText.lineSequence().firstOrNull().orEmpty()
            val statusCode = statusLine
                .split(" ")
                .getOrNull(1)
                ?.toIntOrNull()
                ?: throw IllegalStateException("Invalid HTTP response: $statusLine")
            val responseBody = responseText
                .substringAfter("\r\n\r\n", "")
                .trim()

            if (statusCode !in 200..299) {
                throw IllegalStateException("HTTP $statusCode $responseBody")
            }

            return responseBody.ifBlank { "HTTP $statusCode" }
        }
    }
}
