package com.example.msdksample

import android.os.Handler
import android.os.Looper
import android.util.Log
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode
import dji.sdk.keyvalue.value.gimbal.GimbalMode
import dji.v5.manager.KeyManager

class PreflightController {

    companion object {
        const val TAG = "PreflightCtrl"
        const val EXPOSURE_WAIT_MS = 1500L
        const val CHECK_FRAMES = 5
    }

    private enum class State { IDLE, WAITING_EXPOSURE, SAMPLING }
    @Volatile private var state = State.IDLE

    private val inspector = GripperInspector()
    private val handler = Handler(Looper.getMainLooper())

    private var passCount = 0
    private var failCount = 0

    // 由 MainActivity 传入当前相机索引
    var currentCameraIndex: ComponentIndexType = ComponentIndexType.LEFT_OR_MAIN

    // UI 回调接口
    var onCheckStarted: (() -> Unit)? = null
    var onCheckPassed: (() -> Unit)? = null
    var onCheckFailed: ((reason: String) -> Unit)? = null

    // 暴露给外部判断当前是否正在占用相机流
    val isChecking: Boolean
        get() = state != State.IDLE

    /**
     * 启动安全自检
     */
    fun startCheck() {
        if (state != State.IDLE) return

        Log.i(TAG, "🛫 启动起飞预检流程...")
        state = State.WAITING_EXPOSURE
        passCount = 0
        failCount = 0

        onCheckStarted?.invoke()

        // 1. 云台强制朝下
        rotateGimbal(-90.0)

        // 2. 延迟等待云台到位及曝光稳定，随后进入采样状态
        handler.postDelayed({
            if (state == State.WAITING_EXPOSURE) {
                state = State.SAMPLING
                Log.i(TAG, "曝光等待结束，开始连续采样")
            }
        }, EXPOSURE_WAIT_MS)
    }

    /**
     * 处理相机帧数据 (由 MainActivity 喂入)
     */
    fun processFrame(data: ByteArray, offset: Int, width: Int, height: Int, isTargetLocked: Boolean) {
        // 如果不在采样阶段，直接忽略该帧
        if (state != State.SAMPLING) return

        val isBlocked = inspector.inspect(data, offset, width, height, isTargetLocked)
        if (isBlocked) failCount++ else passCount++

        val totalChecked = passCount + failCount
        if (totalChecked >= CHECK_FRAMES) {
            // 采样完毕，关闭检测通道
            state = State.IDLE

            // 恢复云台平视
            rotateGimbal(0.0)

            // 裁决：2帧或以上失败即判定为夹爪未松开
            handler.post {
                if (failCount >= 2) {
                    Log.w(TAG, "🛑 检查未通过：检测到夹爪遮挡")
                    onCheckFailed?.invoke("⚠️ 警告：检测到夹爪未松开，请勿起飞！")
                } else {
                    Log.i(TAG, "✅ 夹爪检查通过，安全状态确认")
                    onCheckPassed?.invoke()
                }
            }
        }
    }

    /**
     * 云台控制
     */
    private fun rotateGimbal(pitchDeg: Double) {
        runCatching {
            KeyManager.getInstance().setValue(
                KeyTools.createKey(GimbalKey.KeyGimbalMode, currentCameraIndex),
                GimbalMode.YAW_FOLLOW, null
            )
        }
        val rotation = GimbalAngleRotation().apply {
            mode = GimbalAngleRotationMode.ABSOLUTE_ANGLE
            pitch = pitchDeg
            duration = 1.0
        }
        runCatching {
            KeyManager.getInstance().performAction(
                KeyTools.createKey(GimbalKey.KeyRotateByAngle, currentCameraIndex),
                rotation, null
            )
        }
    }

    fun release() {
        state = State.IDLE
        handler.removeCallbacksAndMessages(null)
        inspector.release()
    }
}