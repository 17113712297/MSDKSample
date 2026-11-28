package com.example.msdksample

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 到达航点抓拍。
 *
 * 收到机载 0x79（航点到位）后延迟 [CAPTURE_DELAY_MS] 抓一帧图传画面，
 * 等飞机在点位上悬停稳住、画面不糊了再抓。
 *
 * 抓的是视频流本身，不切相机模式、不下快门，所以**不会打断正在进行的录像**。
 * JPEG 编码放到独立线程，避免阻塞相机回调线程（那会拖累推流和视觉处理）。
 *
 * 照片只落盘不上传，真正的上传由 VideoTransferManager 在停录、收到 1.1 指令后
 * 统一按批次做，见 `uploadPendingPhotos`。
 */
class WaypointPhotoCapturer(
    private val store: WaypointPhotoStore,
    private val cameraProvider: () -> CameraController?,
    private val enabledProvider: () -> Boolean
) {

    companion object {
        private const val TAG = "WaypointPhoto"

        /** 到位后等悬停收敛再抓 */
        private const val CAPTURE_DELAY_MS = 300L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val encoder = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "wp-photo-encode").apply { isDaemon = true }
    }
    private val captureInFlight = AtomicBoolean(false)

    /** 状态提示（主线程回调） */
    var onStatus: ((String) -> Unit)? = null

    /** 抓拍成功（主线程回调），参数为落盘文件 */
    var onCaptured: ((File) -> Unit)? = null

    fun onWaypointArrived(stopNumber: Int, wpIndex: Int) {
        if (!enabledProvider()) {
            status("媒体上传已关闭，跳过点位 $stopNumber 抓拍")
            return
        }

        val camera = cameraProvider()
        if (camera == null) {
            status("相机未就绪，点位 $stopNumber 未抓拍")
            return
        }

        if (!captureInFlight.compareAndSet(false, true)) {
            status("上一张仍在处理，点位 $stopNumber 未抓拍")
            return
        }

        status("点位 $stopNumber 到位，抓拍中")
        mainHandler.postDelayed({
            camera.captureFrame { snapshot ->
                if (snapshot == null) {
                    captureInFlight.set(false)
                    status("点位 $stopNumber 抓拍失败：没有可用视频帧")
                    return@captureFrame
                }
                if (encoder.isShutdown) {
                    captureInFlight.set(false)
                    return@captureFrame
                }
                encoder.execute {
                    val saved = runCatching {
                        val jpeg = YuvJpegEncoder.encodeI420ToJpeg(
                            data = snapshot.bytes,
                            offset = 0,
                            length = snapshot.bytes.size,
                            width = snapshot.width,
                            height = snapshot.height
                        ) ?: error("JPEG 编码返回空")
                        val target = store.newPhotoFile(stopNumber, wpIndex)
                        target.writeBytes(jpeg)
                        target to jpeg.size
                    }.onFailure {
                        Log.w(TAG, "点位 $stopNumber 照片落盘失败: ${it.message}")
                    }.getOrNull()

                    captureInFlight.set(false)
                    mainHandler.post {
                        if (saved == null) {
                            status("点位 $stopNumber 抓拍失败：编码或写盘异常")
                        } else {
                            status("点位 $stopNumber 已抓图 ${saved.second / 1024}KB，待停录后上传")
                            onCaptured?.invoke(saved.first)
                        }
                    }
                }
            }
        }, CAPTURE_DELAY_MS)
    }

    fun release() {
        mainHandler.removeCallbacksAndMessages(null)
        encoder.shutdownNow()
    }

    private fun status(message: String) {
        Log.i(TAG, message)
        mainHandler.post { onStatus?.invoke(message) }
    }
}
