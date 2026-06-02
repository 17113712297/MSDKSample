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

/**
 * CameraController
 *
 * 封装 MSDK V5 的相机控制。
 *
 * ── 关键修复 ───────────────────────────────────────────────────
 * 1. setVideoCfg 改用 `createCameraKey(..., cameraIndex, lensType)`，
 * Mavic 3T 多镜头相机的分辨率/帧率是按 lens 分别配置的，
 * 旧实现用 `createKey(KeyVideoResolutionFrameRate, cameraIndex)`
 * 没指定 lens，可能配置到错误的镜头上。
 * lens 通过 `lensProvider` 由外部 (Service/Activity) 注入当前激活镜头。
 *
 * 2. shootPhoto / startRecord / stopRecord 的 onSuccess 仅代表
 * "指令被相机接受"，文件落盘 / 状态切换还需若干百毫秒。
 * 本版在 onSuccess 后用 mainHandler.postDelayed 加固定延迟再回 ACK，
 * 让 PSDK 端拿到 ACK 时操作确实完成。
 * （没有用 KeyIsStoringPhoto / KeyIsRecording 这些状态键，
 * 因为不同 SDK 版本的命名不一致，固定延迟更稳。）
 */
class CameraController(
    private val cameraIndex: ComponentIndexType = ComponentIndexType.LEFT_OR_MAIN
) {

    companion object {
        private const val TAG = "CameraController"

        /**
         * 拍照后等待落盘的延迟。
         * 经验值：JPG 单拍约 300~500ms，JPG+RAW 约 600~1500ms。
         * 这里取 800ms 覆盖多数场景；超大尺寸 RAW 可上调到 1500ms。
         */
        private const val PHOTO_SETTLE_MS = 800L

        /**
         * 录像启停后等待状态稳定的延迟。
         * 经验值：start/stop 内部状态切换 < 200ms，取 300ms 留余量。
         */
        private const val RECORD_SETTLE_MS = 300L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    var logCallback: ((String) -> Unit)? = null

    /**
     * 当前激活镜头提供者。Service 调用 `updateCurrentLens` 时变化，
     * 或 MainActivity 镜头按钮触发后由 Service 同步。
     * 默认返回 WIDE，避免空指针。
     */
    var lensProvider: () -> Byte = { DroneCommProtocol.CAM_LENS_WIDE }

// ─────────────────────────────────────────────────────
    //  新增：视频流获取与分发逻辑 (解决 MainActivity 报错)
    // ─────────────────────────────────────────────────────

    /**
     * 将底层视频流数据回调给外部 (如 VisionController, PreflightController)
     */
    var frameCallback: ((data: ByteArray, offset: Int, length: Int, width: Int, height: Int) -> Unit)? = null

    /**
     * MSDK V5 的视频帧监听器 (修复了接口名和类型推导报错)
     */
    private val frameListener = object : ICameraStreamManager.CameraFrameListener {
        override fun onFrame(
            frameData: ByteArray,
            offset: Int,
            length: Int,
            width: Int,
            height: Int,
            format: ICameraStreamManager.FrameFormat
        ) {
            // 将拿到的 YUV 视频流数据分发给外部的回调函数
            frameCallback?.invoke(frameData, offset, length, width, height)
        }
    }

    /**
     * 开启视频数据流
     */
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

    /**
     * 停止视频数据流
     */
    fun stopVideoStream() {
        log("CMD: stopVideoStream")
        // 移除监听器，节省系统资源
        MediaDataCenter.getInstance().cameraStreamManager.removeFrameListener(frameListener)
    }


    private fun currentLensType(): CameraLensType =
        when (lensProvider.invoke()) {
            DroneCommProtocol.CAM_LENS_ZOOM     -> CameraLensType.CAMERA_LENS_ZOOM
            DroneCommProtocol.CAM_LENS_INFRARED -> CameraLensType.CAMERA_LENS_THERMAL
            else                                -> CameraLensType.CAMERA_LENS_WIDE
        }

    // ─────────────────────────────────────────────────────
    //  工作模式
    // ─────────────────────────────────────────────────────

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

    // ─────────────────────────────────────────────────────
    //  拍照
    // ─────────────────────────────────────────────────────

    /**
     * 单拍一张。
     *
     * onSuccess 仅表示「快门指令被接受」，相机内部还在写文件 (尤其 RAW+JPG)。
     * 这里 onSuccess 后再延迟 PHOTO_SETTLE_MS 回调，
     * 让 PSDK 端拿到 ACK_OK 时照片大概率已经实际落盘。
     */
    fun shootPhoto(onResult: (Boolean, String) -> Unit) {
        log("CMD: shootPhoto")
        KeyManager.getInstance().performAction(
            KeyTools.createKey(CameraKey.KeyStartShootPhoto, cameraIndex),
            object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(value: EmptyMsg?) {
                    log("shootPhoto accepted, ${PHOTO_SETTLE_MS}ms 后回 ACK")
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

    // ─────────────────────────────────────────────────────
    //  录像
    // ─────────────────────────────────────────────────────

    fun startRecord(onResult: (Boolean, String) -> Unit) {
        log("CMD: startRecord")
        KeyManager.getInstance().performAction(
            KeyTools.createKey(CameraKey.KeyStartRecord, cameraIndex),
            object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(value: EmptyMsg?) {
                    log("startRecord accepted, ${RECORD_SETTLE_MS}ms 后回 ACK")
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
                    log("stopRecord accepted, ${RECORD_SETTLE_MS}ms 后回 ACK")
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

    // ─────────────────────────────────────────────────────
    //  分辨率 + 帧率
    // ─────────────────────────────────────────────────────

    fun setVideoCfg(resCode: Byte, fpsCode: Byte, onResult: (Boolean, String) -> Unit) {
        val res = mapResolution(resCode) ?: run {
            onResult(false, "unknown resolution code: 0x${resCode.toUByte().toString(16)}")
            return
        }
        val fps = mapFrameRate(fpsCode) ?: run {
            onResult(false, "unknown fps code: 0x${fpsCode.toUByte().toString(16)}")
            return
        }
        val lens = currentLensType()
        log("CMD: setVideoCfg res=$res fps=$fps lens=$lens")

        val combo = VideoResolutionFrameRate(res, fps)
        // 关键修复：必须用 createCameraKey 带 lensType，否则多镜头相机配错位
        KeyManager.getInstance().setValue(
            KeyTools.createCameraKey(
                CameraKey.KeyVideoResolutionFrameRate,
                cameraIndex,
                lens
            ),
            combo,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    log("setVideoCfg OK")
                    onResult(true, "OK")
                }
                override fun onFailure(error: IDJIError) {
                    val msg = error.description() ?: error.errorCode()
                    log("setVideoCfg FAIL: $msg")
                    onResult(false, msg)
                }
            }
        )
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

    // ─────────────────────────────────────────────────────
    //  镜头切换 + 变焦
    // ─────────────────────────────────────────────────────

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

    /**
     * 设置数字变焦倍数 (仅变焦镜头)
     *
     * KeyCameraZoomRatios 要求同时指定 ComponentIndexType + CameraLensType，
     * 必须使用 KeyTools.createCameraKey 三参重载 (createKey 不支持 CameraLensType)。
     */
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
