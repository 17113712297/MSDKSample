package com.example.msdksample

import android.content.Context
import android.util.Log
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
        private const val DEFAULT_RTMP_URL = "rtmp://192.168.1.20:1935/live/obs1"
        private const val MSG_NOT_CONNECTED = "Aircraft is not connected"
        private const val MSG_EMPTY_URL = "RTMP address is empty"
        private const val MSG_UNSUPPORTED_CODEC = "Unsupported video codec. Please switch the aircraft stream to H.264."
    }

    private val liveStreamManager: ILiveStreamManager
        get() = MediaDataCenter.getInstance().getLiveStreamManager()

    private val cameraStreamManager: ICameraStreamManager
        get() = MediaDataCenter.getInstance().cameraStreamManager

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private var currentCameraIndex: ComponentIndexType = ComponentIndexType.LEFT_OR_MAIN
    private var actionInFlight = false
    private var statusListenerRegistered = false
    private var receiveStreamListenerRegistered = false
    private var latestMimeType: ICameraStreamManager.MimeType? = null
    private var lastState = LiveStreamUiState(
        streamAddress = getConfiguredStreamAddress(),
        streamStatusText = "Ready to stream"
    )

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
            }
        })

        return LiveStreamCommandResult(true, "Stop command accepted")
    }

    fun stopStreamIfNeeded() {
        if (runCatching { liveStreamManager.isStreaming() }.getOrDefault(false)) {
            stopLiveStream()
        }
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

    private fun publishState(state: LiveStreamUiState) {
        lastState = state
        onStateChanged?.invoke(state)
    }
}
