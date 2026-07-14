package com.example.msdksample.devicereport

import android.util.Log
import com.example.msdksample.network.MultipartHttpClient
import com.example.msdksample.network.StreamAddressResolver
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
        val host = StreamAddressResolver.extractHost(streamAddress)
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

    private fun postMultipartJson(host: String, json: String): String {
        val bodyJson = json.toByteArray(StandardCharsets.UTF_8)

        val requestPath = buildString {
            append("/sendDeviceData")
            append("?siteId=").append(SITE_ID)
            append("&deviceId=").append(DEVICE_ID)
            append("&file=file.json")
        }

        return MultipartHttpClient.postMultipart(
            host = host,
            port = REPORT_PORT,
            requestPath = requestPath,
            accept = "application/json",
            partFieldName = "file",
            fileName = "file.json",
            contentType = "application/json; charset=UTF-8",
            contentLength = bodyJson.size.toLong(),
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = READ_TIMEOUT_MS
        ) { output ->
            output.write(bodyJson)
        }
    }
}