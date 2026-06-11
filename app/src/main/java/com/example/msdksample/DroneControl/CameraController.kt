package com.example.msdksample

import android.os.Handler
import android.os.Looper
import android.util.Log
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.camera.CameraMode
import dji.sdk.keyvalue.value.camera.CameraVideoStreamSourceType
import dji.sdk.keyvalue.value.camera.VideoFrameRate
import dji.sdk.keyvalue.value.camera.VideoResolution
import dji.sdk.keyvalue.value.camera.VideoResolutionFrameRate
import dji.sdk.keyvalue.value.common.CameraLensType
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager

class CameraController(
    private val cameraIndex: ComponentIndexType = ComponentIndexType.LEFT_OR_MAIN
) {

    companion object {
        private const val TAG = "CameraController"
        private const val PHOTO_SETTLE_MS = 800L
        private const val RECORD_SETTLE_MS = 300L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    var logCallback: ((String) -> Unit)? = null

    var frameCallback: ((data: ByteArray, offset: Int, length: Int, width: Int, height: Int) -> Unit)? = null

    private val frameListener = object : ICameraStreamManager.CameraFrameListener {
        override fun onFrame(
            frameData: ByteArray,
            offset: Int,
            length: Int,
            width: Int,
            height: Int,
            format: ICameraStreamManager.FrameFormat
        ) {
            frameCallback?.invoke(frameData, offset, length, width, height)
        }
    }

    fun startVideoStream() {
        log("CMD: startVideoStream")
        try {
            MediaDataCenter.getInstance().cameraStreamManager.addFrameListener(
                cameraIndex,
                ICameraStreamManager.FrameFormat.YUV420_888,
                frameListener
            )
            log("视频帧监听器注册成功")
        } catch (e: Exception) {
            Log.e(TAG, "❌ 注册视频帧监听器失败: ${e.message}", e)
            log("流注册异常: ${e.message}")
        }
    }

    fun stopVideoStream() {
        log("CMD: stopVideoStream")
        MediaDataCenter.getInstance().cameraStreamManager.removeFrameListener(frameListener)
    }

    fun setMode(isPhoto: Boolean, onResult: (Boolean, String) -> Unit) {
        val mode = if (isPhoto) CameraMode.PHOTO_NORMAL else CameraMode.VIDEO_NORMAL
        log("CMD: setMode ${if (isPhoto) "PHOTO" else "VIDEO"}")

        KeyManager.getInstance().setValue(
            KeyTools.createKey(CameraKey.KeyCameraMode, cameraIndex),
            mode,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    log("setMode OK")
                    onResult(true, "OK")
                }
                override fun onFailure(error: IDJIError) {
                    val msg = error.description() ?: error.errorCode()
                    log("setMode FAIL: $msg")
                    onResult(false, msg)
                }
            }
        )
    }

    fun shootPhoto(onResult: (Boolean, String) -> Unit) {
        log("CMD: shootPhoto")
        KeyManager.getInstance().performAction(
            KeyTools.createKey(CameraKey.KeyStartShootPhoto, cameraIndex),
            object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(value: EmptyMsg?) {
                    mainHandler.postDelayed({
                        log("shootPhoto OK")
                        onResult(true, "OK")
                    }, PHOTO_SETTLE_MS)
                }
                override fun onFailure(error: IDJIError) {
                    val msg = error.description() ?: error.errorCode()
                    log("shootPhoto FAIL: $msg")
                    onResult(false, msg)
                }
            }
        )
    }

    fun startRecord(onResult: (Boolean, String) -> Unit) {
        log("CMD: startRecord")
        KeyManager.getInstance().performAction(
            KeyTools.createKey(CameraKey.KeyStartRecord, cameraIndex),
            object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(value: EmptyMsg?) {
                    mainHandler.postDelayed({
                        log("startRecord OK")
                        onResult(true, "OK")
                    }, RECORD_SETTLE_MS)
                }
                override fun onFailure(error: IDJIError) {
                    val msg = error.description() ?: error.errorCode()
                    log("startRecord FAIL: $msg")
                    onResult(false, msg)
                }
            }
        )
    }

    fun stopRecord(onResult: (Boolean, String) -> Unit) {
        log("CMD: stopRecord")
        KeyManager.getInstance().performAction(
            KeyTools.createKey(CameraKey.KeyStopRecord, cameraIndex),
            object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(value: EmptyMsg?) {
                    mainHandler.postDelayed({
                        log("stopRecord OK")
                        onResult(true, "OK")
                    }, RECORD_SETTLE_MS)
                }
                override fun onFailure(error: IDJIError) {
                    val msg = error.description() ?: error.errorCode()
                    log("stopRecord FAIL: $msg")
                    onResult(false, msg)
                }
            }
        )
    }

    // ⭐ 深度重构点：参数显式依赖 lensCode，与外部状态解耦；增加红外镜头拦截
    fun setVideoCfg(lensCode: Byte, resCode: Byte, fpsCode: Byte, onResult: (Boolean, String) -> Unit) {
        val lens = mapLensCodeToCameraLensType(lensCode)

        // 【核心保护机制】：多镜头模组中的红外相机分辨率被硬件锁死在 640x512，拒绝更改
        if (lens == CameraLensType.CAMERA_LENS_THERMAL) {
            log("CMD: setVideoCfg 拦截 - 当前在红外画面，分辨率固定不支持修改")
            onResult(false, "红外镜头不支持修改分辨率")
            return
        }

        val res = mapResolution(resCode) ?: run {
            onResult(false, "未知的辨率代码")
            return
        }
        val fps = mapFrameRate(fpsCode) ?: run {
            onResult(false, "未知的帧率代码")
            return
        }

        log("CMD: setVideoCfg res=$res fps=$fps lens=$lens")
        val combo = VideoResolutionFrameRate(res, fps)

        KeyManager.getInstance().setValue(
            KeyTools.createCameraKey(CameraKey.KeyVideoResolutionFrameRate, cameraIndex, lens),
            combo,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    log("setVideoCfg OK")
                    onResult(true, "OK")
                }
                override fun onFailure(error: IDJIError) {
                    val msg = error.description() ?: error.errorCode()
                    // 增加对【录制中】这种高发冲突状态的拦截提示
                    if (msg.contains("recording", ignoreCase = true) || msg.contains("busy", ignoreCase = true)) {
                        log("setVideoCfg FAIL: 正在录制中无法切换分辨率")
                        onResult(false, "录制中，无法切换")
                    } else {
                        log("setVideoCfg FAIL: $msg")
                        onResult(false, msg)
                    }
                }
            }
        )
    }

    private fun mapLensCodeToCameraLensType(code: Byte): CameraLensType = when (code) {
        DroneCommProtocol.CAM_LENS_ZOOM     -> CameraLensType.CAMERA_LENS_ZOOM
        DroneCommProtocol.CAM_LENS_INFRARED -> CameraLensType.CAMERA_LENS_THERMAL
        else                                -> CameraLensType.CAMERA_LENS_WIDE
    }

    private fun mapResolution(code: Byte): VideoResolution? = when (code) {
        DroneCommProtocol.CAM_RES_1920X1080 -> VideoResolution.RESOLUTION_1920x1080
        DroneCommProtocol.CAM_RES_3840X2160 -> VideoResolution.RESOLUTION_3840x2160
        DroneCommProtocol.CAM_RES_2720X1530 -> VideoResolution.RESOLUTION_2720x1530
        else -> null
    }

    private fun mapFrameRate(code: Byte): VideoFrameRate? = when (code) {
        DroneCommProtocol.CAM_FPS_24 -> VideoFrameRate.RATE_24FPS
        DroneCommProtocol.CAM_FPS_25 -> VideoFrameRate.RATE_25FPS
        DroneCommProtocol.CAM_FPS_30 -> VideoFrameRate.RATE_30FPS
        DroneCommProtocol.CAM_FPS_48 -> VideoFrameRate.RATE_48FPS
        DroneCommProtocol.CAM_FPS_50 -> VideoFrameRate.RATE_50FPS
        DroneCommProtocol.CAM_FPS_60 -> VideoFrameRate.RATE_60FPS
        else -> null
    }

    fun setLensAndZoom(
        lensCode: Byte, shouldSetRatio: Boolean, ratio: Float,
        onResult: (Boolean, String) -> Unit
    ) {
        val streamSource = mapLensToStreamSource(lensCode) ?: run {
            onResult(false, "unknown lens code: 0x${lensCode.toUByte().toString(16)}")
            return
        }
        val willSetRatio = shouldSetRatio && lensCode == DroneCommProtocol.CAM_LENS_ZOOM
        log("CMD: setLens stream=$streamSource " +
                if (willSetRatio) "ratio=$ratio" else "(no ratio change)")

        KeyManager.getInstance().setValue(
            KeyTools.createKey(CameraKey.KeyCameraVideoStreamSource, cameraIndex),
            streamSource,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    log("setLens OK ($streamSource)")
                    if (willSetRatio) {
                        setZoomRatio(ratio, onResult)
                    } else {
                        onResult(true, "OK")
                    }
                }
                override fun onFailure(error: IDJIError) {
                    val msg = error.description() ?: error.errorCode()
                    log("setLens FAIL: $msg")
                    onResult(false, msg)
                }
            }
        )
    }

    private fun setZoomRatio(ratio: Float, onResult: (Boolean, String) -> Unit) {
        KeyManager.getInstance().setValue(
            KeyTools.createCameraKey(
                CameraKey.KeyCameraZoomRatios,
                cameraIndex,
                CameraLensType.CAMERA_LENS_ZOOM
            ),
            ratio.toDouble(),
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    log("setZoomRatio OK: $ratio")
                    onResult(true, "OK")
                }
                override fun onFailure(error: IDJIError) {
                    val msg = error.description() ?: error.errorCode()
                    log("setZoomRatio FAIL: $msg")
                    onResult(false, msg)
                }
            }
        )
    }

    private fun mapLensToStreamSource(code: Byte): CameraVideoStreamSourceType? = when (code) {
        DroneCommProtocol.CAM_LENS_WIDE     -> CameraVideoStreamSourceType.WIDE_CAMERA
        DroneCommProtocol.CAM_LENS_ZOOM     -> CameraVideoStreamSourceType.ZOOM_CAMERA
        DroneCommProtocol.CAM_LENS_INFRARED -> CameraVideoStreamSourceType.INFRARED_CAMERA
        else -> null
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        mainHandler.post { logCallback?.invoke(msg) }
    }
}
