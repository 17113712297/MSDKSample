package com.example.msdksample

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.example.msdksample.network.StreamAddressResolver
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.datacenter.camera.StreamInfo
import dji.v5.manager.datacenter.livestream.LiveStreamSettings
import dji.v5.manager.datacenter.livestream.LiveStreamStatus
import dji.v5.manager.datacenter.livestream.LiveStreamStatusListener
import dji.v5.manager.datacenter.livestream.LiveStreamType
import dji.v5.manager.datacenter.livestream.StreamQuality
import dji.v5.manager.datacenter.livestream.settings.RtmpSettings
import dji.v5.manager.interfaces.ICameraStreamManager
import dji.v5.manager.interfaces.ILiveStreamManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

data class LiveStreamCommandResult(
    val accepted: Boolean,
    val message: String
)

data class LiveStreamUiState(
    val isStreaming: Boolean = false,
    val isBusy: Boolean = false,
    val streamAddress: String = "",
    val streamStatusText: String = "Ready to stream",
    val isError: Boolean = false
)

class LiveStreamController(private val context: Context) {

    companion object {
        private const val TAG = "LiveStreamController"
        private const val PREFS_NAME = "live_stream_prefs"
        private const val PREF_KEY_RTMP_URL = "rtmp_url"
        private const val DEFAULT_RTMP_URL = "rtmp://10.29.3.171:1935/live/obs1"
        private const val MSG_NOT_CONNECTED = "Aircraft is not connected"
        private const val MSG_EMPTY_URL = "RTMP address is empty"
        private const val MSG_UNSUPPORTED_CODEC = "Unsupported video codec. Please switch the aircraft stream to H.264."
        private const val SRS_API_PORT = 1985
        private const val HEALTH_CHECK_INTERVAL_MS = 5_000L
        private const val HEALTH_CHECK_STARTUP_GRACE_MS = 15_000L
        private const val HEALTH_CHECK_CONNECT_TIMEOUT_MS = 2_500
        private const val HEALTH_CHECK_READ_TIMEOUT_MS = 2_500
        private const val HEALTH_CHECK_MIN_RECV_BYTES_DELTA = 32L * 1024L
        private const val HEALTH_CHECK_MAX_UNHEALTHY_POLLS = 3
    }

    private data class SrsStreamHealth(
        val streamFound: Boolean,
        val publishActive: Boolean,
        val recv30sKbps: Long?,
        val recvBytes: Long?,
        val frames: Long?,
        val hasVideo: Boolean,
        val matchedStreamName: String?,
        val detail: String
    )

    private val liveStreamManager: ILiveStreamManager
        get() = MediaDataCenter.getInstance().getLiveStreamManager()

    private val cameraStreamManager: ICameraStreamManager
        get() = MediaDataCenter.getInstance().cameraStreamManager

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentCameraIndex: ComponentIndexType = ComponentIndexType.LEFT_OR_MAIN
    @Volatile private var actionInFlight = false
    private var statusListenerRegistered = false
    private var receiveStreamListenerRegistered = false
    private var latestMimeType: ICameraStreamManager.MimeType? = null
    @Volatile private var pendingRecoveryReason: String? = null
    @Volatile private var lastState = LiveStreamUiState(
        streamAddress = getConfiguredStreamAddress(),
        streamStatusText = "Ready to stream"
    )
    private var healthCheckExecutor: ScheduledExecutorService? = null
    @Volatile private var streamStartedAtElapsedMs = 0L
    @Volatile private var lastObservedRecvBytes: Long? = null
    @Volatile private var consecutiveUnhealthyPolls = 0

    var onStateChanged: ((LiveStreamUiState) -> Unit)? = null

    private val statusListener = object : LiveStreamStatusListener {
        override fun onLiveStreamStatusUpdate(status: LiveStreamStatus) {
            actionInFlight = false
            val address = getConfiguredStreamAddress()
            val resolutionText = runCatching { status.getResolution().toString() }.getOrDefault("unknown")
            val nextStatus = when {
                !isCodecSupported() -> MSG_UNSUPPORTED_CODEC
                status.isStreaming() -> "Streaming ${status.getFps()} fps | ${status.getVbps()} bps | $resolutionText"
                else -> "Ready to stream"
            }
            publishState(
                lastState.copy(
                    isStreaming = status.isStreaming() && isCodecSupported(),
                    isBusy = false,
                    streamAddress = address,
                    streamStatusText = nextStatus,
                    isError = !isCodecSupported()
                )
            )
        }

        override fun onError(error: IDJIError) {
            actionInFlight = false
            publishState(
                lastState.copy(
                    isBusy = false,
                    isStreaming = false,
                    streamStatusText = error.description() ?: error.errorCode(),
                    isError = true
                )
            )
        }
    }

    private val receiveStreamListener = object : ICameraStreamManager.ReceiveStreamListener {
        override fun onReceiveStream(
            data: ByteArray,
            offset: Int,
            length: Int,
            streamInfo: StreamInfo
        ) {
            val mimeType = streamInfo.getMimeType()
            if (mimeType == latestMimeType) {
                return
            }

            latestMimeType = mimeType
            if (mimeType == ICameraStreamManager.MimeType.H265) {
                publishState(
                    lastState.copy(
                        isStreaming = false,
                        isBusy = false,
                        streamStatusText = MSG_UNSUPPORTED_CODEC,
                        isError = true
                    )
                )
                stopStreamIfNeeded()
            } else if (!lastState.isStreaming && !lastState.isBusy && !lastState.isError) {
                publishState(
                    lastState.copy(
                        streamStatusText = "Ready to stream",
                        isError = false
                    )
                )
            }
        }
    }

    fun bind() {
        ensureStatusListener()
        ensureReceiveStreamListener(forceRebind = true)
        val address = getConfiguredStreamAddress()
        val isStreaming = runCatching { liveStreamManager.isStreaming() }.getOrDefault(false)
        publishState(
            lastState.copy(
                isStreaming = isStreaming && isCodecSupported(),
                isBusy = false,
                streamAddress = address,
                streamStatusText = when {
                    !isCodecSupported() -> MSG_UNSUPPORTED_CODEC
                    isStreaming -> "Streaming in progress"
                    else -> "Ready to stream"
                },
                isError = !isCodecSupported()
            )
        )
    }

    fun release() {
        stopStreamHealthMonitor()
        if (statusListenerRegistered) {
            runCatching { liveStreamManager.removeLiveStreamStatusListener(statusListener) }
            statusListenerRegistered = false
        }
        if (receiveStreamListenerRegistered) {
            runCatching { cameraStreamManager.removeReceiveStreamListener(receiveStreamListener) }
            receiveStreamListenerRegistered = false
        }
    }

    fun isStreaming(): Boolean = lastState.isStreaming

    fun updateLiveStreamCameraSource(cameraIndex: ComponentIndexType) {
        currentCameraIndex = cameraIndex
        runCatching {
            liveStreamManager.setCameraIndex(cameraIndex)
            ensureReceiveStreamListener(forceRebind = true)
        }.onFailure { Log.w(TAG, "Failed to update live stream camera source: ${it.message}") }
    }

    fun getConfiguredStreamAddress(): String {
        return prefs.getString(PREF_KEY_RTMP_URL, DEFAULT_RTMP_URL)?.trim().orEmpty()
            .ifBlank { DEFAULT_RTMP_URL }
    }

    fun updateConfiguredStreamAddress(address: String): String {
        val normalized = address.trim()
        prefs.edit().putString(PREF_KEY_RTMP_URL, normalized).apply()
        publishState(
            lastState.copy(
                streamAddress = normalized,
                streamStatusText = when {
                    lastState.isStreaming -> lastState.streamStatusText
                    normalized.isBlank() -> MSG_EMPTY_URL
                    !isCodecSupported() -> MSG_UNSUPPORTED_CODEC
                    else -> "Ready to stream"
                },
                isError = normalized.isBlank() || !isCodecSupported()
            )
        )
        return normalized
    }

    fun refreshConfiguredStreamAddress(): String {
        val address = getConfiguredStreamAddress()
        publishState(
            lastState.copy(
                streamAddress = address,
                streamStatusText = when {
                    lastState.isStreaming -> lastState.streamStatusText
                    address.isBlank() -> MSG_EMPTY_URL
                    !isCodecSupported() -> MSG_UNSUPPORTED_CODEC
                    else -> "Ready to stream"
                },
                isError = address.isBlank() || !isCodecSupported()
            )
        )
        return address
    }

    fun startRtmpLiveStream(): LiveStreamCommandResult {
        if (actionInFlight) {
            return LiveStreamCommandResult(false, "A stream action is already in progress")
        }
        if (runCatching { liveStreamManager.isStreaming() }.getOrDefault(false)) {
            return LiveStreamCommandResult(false, "The stream is already running")
        }
        if (!isProductConnected()) {
            publishState(
                lastState.copy(
                    isBusy = false,
                    isStreaming = false,
                    streamStatusText = MSG_NOT_CONNECTED,
                    isError = true
                )
            )
            return LiveStreamCommandResult(false, MSG_NOT_CONNECTED)
        }

        val address = getConfiguredStreamAddress()
        if (address.isBlank()) {
            publishState(
                lastState.copy(
                    isBusy = false,
                    isStreaming = false,
                    streamAddress = address,
                    streamStatusText = MSG_EMPTY_URL,
                    isError = true
                )
            )
            return LiveStreamCommandResult(false, MSG_EMPTY_URL)
        }
        if (!address.startsWith("rtmp://", ignoreCase = true)) {
            val message = "RTMP address must start with rtmp://"
            publishState(
                lastState.copy(
                    isBusy = false,
                    isStreaming = false,
                    streamAddress = address,
                    streamStatusText = message,
                    isError = true
                )
            )
            return LiveStreamCommandResult(false, message)
        }
        if (!isCodecSupported()) {
            publishState(
                lastState.copy(
                    isBusy = false,
                    isStreaming = false,
                    streamAddress = address,
                    streamStatusText = MSG_UNSUPPORTED_CODEC,
                    isError = true
                )
            )
            return LiveStreamCommandResult(false, MSG_UNSUPPORTED_CODEC)
        }

        val configureResult = runCatching { configureRtmpSettings(address) }
        if (configureResult.isFailure) {
            val message = configureResult.exceptionOrNull()?.message ?: "Failed to configure RTMP stream"
            publishState(
                lastState.copy(
                    isBusy = false,
                    isStreaming = false,
                    streamAddress = address,
                    streamStatusText = message,
                    isError = true
                )
            )
            return LiveStreamCommandResult(false, message)
        }

        actionInFlight = true
        publishState(
            lastState.copy(
                isBusy = true,
                isStreaming = false,
                streamAddress = address,
                streamStatusText = "Starting RTMP stream...",
                isError = false
            )
        )

        liveStreamManager.startStream(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                actionInFlight = false
                publishState(
                    lastState.copy(
                        isBusy = false,
                        isStreaming = true,
                        streamAddress = address,
                        streamStatusText = "Streaming in progress",
                        isError = false
                    )
                )
            }

            override fun onFailure(error: IDJIError) {
                actionInFlight = false
                publishState(
                    lastState.copy(
                        isBusy = false,
                        isStreaming = false,
                        streamAddress = address,
                        streamStatusText = error.description() ?: error.errorCode(),
                        isError = true
                    )
                )
            }
        })

        return LiveStreamCommandResult(true, "Start command accepted")
    }

    fun stopLiveStream(): LiveStreamCommandResult {
        if (actionInFlight) {
            return LiveStreamCommandResult(false, "A stream action is already in progress")
        }
        if (!runCatching { liveStreamManager.isStreaming() }.getOrDefault(false)) {
            val address = getConfiguredStreamAddress()
            publishState(
                lastState.copy(
                    isBusy = false,
                    isStreaming = false,
                    streamAddress = address,
                    streamStatusText = when {
                        address.isBlank() -> MSG_EMPTY_URL
                        !isCodecSupported() -> MSG_UNSUPPORTED_CODEC
                        else -> "Ready to stream"
                    },
                    isError = address.isBlank() || !isCodecSupported()
                )
            )
            return LiveStreamCommandResult(false, "The stream is not running")
        }

        actionInFlight = true
        publishState(
            lastState.copy(
                isBusy = true,
                streamStatusText = "Stopping RTMP stream...",
                isError = false
            )
        )

        liveStreamManager.stopStream(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                actionInFlight = false
                val address = getConfiguredStreamAddress()
                publishState(
                    lastState.copy(
                        isBusy = false,
                        isStreaming = false,
                        streamAddress = address,
                        streamStatusText = when {
                            address.isBlank() -> MSG_EMPTY_URL
                            !isCodecSupported() -> MSG_UNSUPPORTED_CODEC
                            else -> "Ready to stream"
                        },
                        isError = address.isBlank() || !isCodecSupported()
                    )
                )
                if (pendingRecoveryReason != null) {
                    scheduleRecoveryAttempt(1, 1200L)
                }
            }

            override fun onFailure(error: IDJIError) {
                actionInFlight = false
                publishState(
                    lastState.copy(
                        isBusy = false,
                        streamStatusText = error.description() ?: error.errorCode(),
                        isError = true
                    )
                )
                if (pendingRecoveryReason != null) {
                    scheduleRecoveryAttempt(1, 1200L)
                }
            }
        })

        return LiveStreamCommandResult(true, "Stop command accepted")
    }

    fun stopStreamIfNeeded() {
        if (runCatching { liveStreamManager.isStreaming() }.getOrDefault(false)) {
            stopLiveStream()
        }
    }

    fun recoverAfterMediaTransfer(reason: String) {
        requestStreamRecovery("media transfer: $reason")
    }

    private fun ensureStatusListener() {
        if (statusListenerRegistered) return
        liveStreamManager.addLiveStreamStatusListener(statusListener)
        statusListenerRegistered = true
    }

    private fun ensureReceiveStreamListener(forceRebind: Boolean) {
        if (receiveStreamListenerRegistered && !forceRebind) {
            return
        }
        if (receiveStreamListenerRegistered) {
            runCatching { cameraStreamManager.removeReceiveStreamListener(receiveStreamListener) }
            receiveStreamListenerRegistered = false
        }
        cameraStreamManager.addReceiveStreamListener(currentCameraIndex, receiveStreamListener)
        receiveStreamListenerRegistered = true
    }

    private fun configureRtmpSettings(address: String) {
        ensureStatusListener()
        ensureReceiveStreamListener(forceRebind = false)
        val rtmpSettings = RtmpSettings.Builder()
            .setUrl(address)
            .build()

        val liveStreamSettings = LiveStreamSettings.Builder()
            .setLiveStreamType(LiveStreamType.RTMP)
            .setRtmpSettings(rtmpSettings)
            .build()

        liveStreamManager.setLiveStreamSettings(liveStreamSettings)
        liveStreamManager.setCameraIndex(currentCameraIndex)
        liveStreamManager.setLiveStreamQuality(StreamQuality.HD)
        liveStreamManager.setLiveAudioEnabled(false)
    }

    private fun isCodecSupported(): Boolean {
        return latestMimeType == null || latestMimeType == ICameraStreamManager.MimeType.H264
    }

    private fun isProductConnected(): Boolean {
        return runCatching {
            KeyManager.getInstance().getValue(KeyTools.createKey(ProductKey.KeyConnection)) ?: false
        }.getOrDefault(false)
    }

    private fun requestStreamRecovery(reason: String) {
        if (pendingRecoveryReason != null) {
            Log.i(TAG, "Ignore duplicate RTMP recovery request while one is pending: $reason")
            return
        }

        pendingRecoveryReason = reason
        Log.i(TAG, "Request RTMP recovery: $reason")
        scheduleRecoveryAttempt(0, 0L)
    }

    private fun scheduleRecoveryAttempt(attempt: Int, delayMs: Long) {
        mainHandler.postDelayed({
            restartStreamForRecovery(attempt)
        }, delayMs)
    }

    private fun restartStreamForRecovery(attempt: Int) {
        val reason = pendingRecoveryReason ?: return
        if (attempt >= 8) {
            Log.w(TAG, "Give up RTMP recovery after repeated attempts: $reason")
            pendingRecoveryReason = null
            return
        }

        if (!isProductConnected()) {
            Log.w(TAG, "Skip RTMP recovery because aircraft is not connected yet")
            scheduleRecoveryAttempt(attempt + 1, 2000L)
            return
        }

        if (actionInFlight) {
            scheduleRecoveryAttempt(attempt + 1, 750L)
            return
        }

        val managerStreaming = runCatching { liveStreamManager.isStreaming() }.getOrDefault(false)
        if (managerStreaming) {
            val stopResult = stopLiveStream()
            if (!stopResult.accepted && stopResult.message != "A stream action is already in progress") {
                scheduleRecoveryAttempt(attempt + 1, 1200L)
            }
            return
        }

        updateLiveStreamCameraSource(currentCameraIndex)
        refreshConfiguredStreamAddress()
        val startResult = startRtmpLiveStream()
        when {
            startResult.accepted -> {
                Log.i(TAG, "RTMP recovery start requested: $reason")
                pendingRecoveryReason = null
            }
            startResult.message == "The stream is already running" -> {
                Log.i(TAG, "RTMP recovery skipped because stream is already running")
                pendingRecoveryReason = null
            }
            else -> {
                Log.w(TAG, "RTMP recovery attempt failed: ${startResult.message}")
                scheduleRecoveryAttempt(attempt + 1, 1500L)
            }
        }
    }

    private fun publishState(state: LiveStreamUiState) {
        val wasStreaming = lastState.isStreaming
        lastState = state
        when {
            state.isStreaming && !wasStreaming -> startStreamHealthMonitor()
            !state.isStreaming && wasStreaming -> stopStreamHealthMonitor()
        }
        onStateChanged?.invoke(state)
    }

    private fun startStreamHealthMonitor() {
        streamStartedAtElapsedMs = SystemClock.elapsedRealtime()
        lastObservedRecvBytes = null
        consecutiveUnhealthyPolls = 0
        if (healthCheckExecutor != null) {
            return
        }

        healthCheckExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "rtmp-health-check").apply { isDaemon = true }
        }.also { executor ->
            executor.scheduleAtFixedRate(
                { runStreamHealthCheckSafely() },
                HEALTH_CHECK_INTERVAL_MS,
                HEALTH_CHECK_INTERVAL_MS,
                TimeUnit.MILLISECONDS
            )
        }
        Log.i(TAG, "Started RTMP health monitor")
    }

    private fun stopStreamHealthMonitor() {
        healthCheckExecutor?.shutdownNow()
        healthCheckExecutor = null
        streamStartedAtElapsedMs = 0L
        lastObservedRecvBytes = null
        consecutiveUnhealthyPolls = 0
        Log.i(TAG, "Stopped RTMP health monitor")
    }

    private fun runStreamHealthCheckSafely() {
        runCatching { runStreamHealthCheck() }
            .onFailure { error ->
                Log.w(TAG, "RTMP health check failed: ${error.message}")
            }
    }

    private fun runStreamHealthCheck() {
        if (!lastState.isStreaming || actionInFlight || pendingRecoveryReason != null) {
            return
        }
        if (SystemClock.elapsedRealtime() - streamStartedAtElapsedMs < HEALTH_CHECK_STARTUP_GRACE_MS) {
            return
        }

        val parsedAddress = StreamAddressResolver.parse(getConfiguredStreamAddress()) ?: run {
            Log.w(TAG, "Skip RTMP health check because stream address cannot be parsed")
            return
        }
        val streamPath = parsedAddress.streamPath ?: run {
            Log.w(TAG, "Skip RTMP health check because app/stream cannot be resolved from RTMP URL")
            return
        }

        val health = querySrsStreamHealth(parsedAddress.host, streamPath)
        val currentRecvBytes = health.recvBytes
        val previousRecvBytes = lastObservedRecvBytes
        val recvBytesDelta = if (
            previousRecvBytes != null &&
            currentRecvBytes != null &&
            currentRecvBytes >= previousRecvBytes
        ) {
            currentRecvBytes - previousRecvBytes
        } else {
            null
        }
        lastObservedRecvBytes = currentRecvBytes

        val missingStream = !health.streamFound
        val inactivePublish = health.streamFound && !health.publishActive
        val missingVideo = health.streamFound && !health.hasVideo
        val stalledInput = recvBytesDelta != null && recvBytesDelta < HEALTH_CHECK_MIN_RECV_BYTES_DELTA

        val unhealthyReason = when {
            missingStream -> "SRS stream $streamPath not found"
            inactivePublish -> "SRS publish inactive for $streamPath"
            missingVideo -> "SRS reports no video track for $streamPath"
            stalledInput -> "SRS receive bytes too low for $streamPath: +$recvBytesDelta in ${HEALTH_CHECK_INTERVAL_MS / 1000}s"
            else -> null
        }

        if (unhealthyReason == null) {
            consecutiveUnhealthyPolls = 0
            Log.d(
                TAG,
                "RTMP health ok for ${health.matchedStreamName ?: streamPath}: recv30s=${health.recv30sKbps}kbps recvBytes=${health.recvBytes} delta=${recvBytesDelta ?: "n/a"} frames=${health.frames}"
            )
            return
        }

        consecutiveUnhealthyPolls += 1
        Log.w(
            TAG,
            "RTMP health unhealthy (${consecutiveUnhealthyPolls}/$HEALTH_CHECK_MAX_UNHEALTHY_POLLS): $unhealthyReason; ${health.detail}"
        )
        if (consecutiveUnhealthyPolls < HEALTH_CHECK_MAX_UNHEALTHY_POLLS) {
            return
        }

        consecutiveUnhealthyPolls = 0
        requestStreamRecovery("SRS health check failed: $unhealthyReason")
    }

    private fun querySrsStreamHealth(host: String, targetStreamPath: String): SrsStreamHealth {
        val requestUrl = "http://$host:$SRS_API_PORT/api/v1/streams/"
        val connection = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = HEALTH_CHECK_CONNECT_TIMEOUT_MS
            readTimeout = HEALTH_CHECK_READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
        }

        try {
            val statusCode = connection.responseCode
            val body = (if (statusCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(StandardCharsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            if (statusCode !in 200..299) {
                throw IllegalStateException("HTTP $statusCode from $requestUrl: $body")
            }

            val root = JSONObject(body)
            val streams = extractSrsStreams(root)
            val matchedStream = findMatchingSrsStream(streams, targetStreamPath)
                ?: return SrsStreamHealth(
                    streamFound = false,
                    publishActive = false,
                    recv30sKbps = null,
                    recvBytes = null,
                    frames = null,
                    hasVideo = false,
                    matchedStreamName = null,
                    detail = "SRS returned ${streams.length()} streams"
                )

            val publishActive = optBooleanFlexible(matchedStream.opt("publish"), "active")
            val video = matchedStream.optJSONObject("video")
            val recv30sKbps = optLongFlexible(matchedStream.optJSONObject("kbps"), "recv_30s")
            val recvBytes = optLongFlexible(matchedStream, "recv_bytes")
            val frames = optLongFlexible(matchedStream, "frames")
                ?: optLongFlexible(video, "frames")
            val matchedName = buildSrsStreamPath(matchedStream)
                ?: matchedStream.optString("name").ifBlank { null }

            return SrsStreamHealth(
                streamFound = true,
                publishActive = publishActive,
                recv30sKbps = recv30sKbps,
                recvBytes = recvBytes,
                frames = frames,
                hasVideo = video != null && video.length() > 0,
                matchedStreamName = matchedName,
                detail = "matched=${matchedName ?: targetStreamPath} recv30s=${recv30sKbps ?: -1}kbps recvBytes=${recvBytes ?: -1} frames=${frames ?: -1}"
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun extractSrsStreams(root: JSONObject): JSONArray {
        val directStreams = root.optJSONArray("streams")
        if (directStreams != null) {
            return directStreams
        }

        val data = root.opt("data")
        if (data is JSONArray) {
            return data
        }
        if (data is JSONObject) {
            data.optJSONArray("streams")?.let { return it }
        }

        return JSONArray()
    }

    private fun findMatchingSrsStream(streams: JSONArray, targetStreamPath: String): JSONObject? {
        val normalizedTargetPath = "/${targetStreamPath.trim('/')}"
        for (index in 0 until streams.length()) {
            val stream = streams.optJSONObject(index) ?: continue
            val derivedPath = buildSrsStreamPath(stream)
            if (derivedPath == normalizedTargetPath) {
                return stream
            }
        }
        return null
    }

    private fun buildSrsStreamPath(stream: JSONObject): String? {
        val url = stream.optString("url").trim().substringBefore('?')
        if (url.isNotBlank()) {
            return "/${url.trim('/')}"
        }

        val app = stream.optString("app").trim()
        val streamName = stream.optString("name").ifBlank {
            stream.optString("stream")
        }.trim()
        if (app.isBlank() || streamName.isBlank()) {
            return null
        }
        return "/$app/$streamName"
    }

    private fun optBooleanFlexible(source: Any?, key: String): Boolean {
        val value = when (source) {
            is JSONObject -> source.opt(key)
            else -> null
        } ?: return false

        return when (value) {
            is Boolean -> value
            is Number -> value.toInt() != 0
            is String -> value.equals("true", ignoreCase = true) || value == "1"
            else -> false
        }
    }

    private fun optLongFlexible(source: JSONObject?, key: String): Long? {
        if (source == null || !source.has(key)) {
            return null
        }

        val value = source.opt(key)
        return when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
    }
}
