package com.example.msdksample

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.example.msdksample.logs.OperationLogger
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode
import dji.sdk.keyvalue.value.gimbal.GimbalMode
import dji.v5.manager.KeyManager

class PreflightController(private val operations: VisionOperationGate) {
    companion object {
        const val TAG = "PreflightCtrl"
        const val EXPOSURE_WAIT_MS = 1500L
        const val CHECK_FRAMES = 5
        private const val CHECK_TIMEOUT_MS = 8000L
    }

    private enum class State { IDLE, WAITING_EXPOSURE, SAMPLING, FINISHING }
    @Volatile private var state = State.IDLE
    private var lease: VisionOperationGate.Lease? = null
    private val inspector = GripperInspector()
    private val handler = Handler(Looper.getMainLooper())
    private var passCount = 0
    private var failCount = 0
    private var samplingStartedMs = 0L

    var currentCameraIndex: ComponentIndexType = ComponentIndexType.LEFT_OR_MAIN
    var onCheckStarted: (() -> Unit)? = null
    var onCheckPassed: (() -> Unit)? = null
    var onCheckFailed: ((String) -> Unit)? = null
    val isChecking: Boolean get() = state != State.IDLE

    @Synchronized fun activeCheckId(): Long? =
        lease?.takeIf { operations.owns(it) && state != State.FINISHING }?.id

    fun startCheck(): Boolean {
        val token = synchronized(this) {
            if (state != State.IDLE) {
                OperationLogger.result("起飞前检查", false, "预检启动未受理：已有检查尚未结束")
                return false
            }
            val acquired = operations.acquire(VisionOperationGate.Owner.PREFLIGHT) ?: run {
                OperationLogger.result("起飞前检查", false, "预检启动未受理：相机/云台被其他任务占用")
                return false
            }
            lease = acquired
            state = State.WAITING_EXPOSURE
            passCount = 0; failCount = 0; samplingStartedMs = 0L
            acquired
        }
        try {
            OperationLogger.state("起飞前检查", "预检已启动，等待曝光与夹爪采样")
            onCheckStarted?.invoke() // 获准后才允许开启/重置相机流
            rotateGimbal(-90.0)
        } catch (error: Exception) {
            synchronized(this) { if (lease == token) state = State.FINISHING }
            handler.post { finish(token, false, "预检启动失败: ${error.message}") }
            return false
        }
        handler.postDelayed({
            synchronized(this) {
                if (lease == token && operations.owns(token) && state == State.WAITING_EXPOSURE) {
                    state = State.SAMPLING
                    samplingStartedMs = SystemClock.elapsedRealtime()
                }
            }
        }, EXPOSURE_WAIT_MS)
        handler.postDelayed({
            val timedOut = synchronized(this) {
                if (lease == token && state != State.IDLE && state != State.FINISHING) {
                    state = State.FINISHING
                    true
                } else false
            }
            if (timedOut) finish(token, false, "预检等待相机采样超时")
        }, CHECK_TIMEOUT_MS)
        return true
    }

    @Synchronized fun processFrame(data: ByteArray, offset: Int, width: Int, height: Int,
                                    isTargetLocked: Boolean, checkId: Long, capturedAt: Long) {
        val token = lease ?: return
        if (state != State.SAMPLING || token.id != checkId || !operations.owns(token) ||
            capturedAt < samplingStartedMs) return
        if (inspector.inspect(data, offset, width, height, isTargetLocked)) failCount++ else passCount++
        if (passCount + failCount < CHECK_FRAMES) return
        val passed = failCount < 2
        state = State.FINISHING
        handler.post { finish(token, passed, "检测到夹爪未松开，请勿起飞") }
    }

    /** 直到云台恢复请求和关闭视觉流等结束回调全部执行完毕，才交出使用权。 */
    private fun finish(token: VisionOperationGate.Lease, passed: Boolean, reason: String) {
        val allowed = synchronized(this) { lease == token && operations.owns(token) }
        if (!allowed) return
        try {
            OperationLogger.result("起飞前检查", passed, if (passed) "起飞前检查通过" else reason)
            rotateGimbal(0.0)
            if (passed) onCheckPassed?.invoke() else onCheckFailed?.invoke(reason)
        } finally {
            synchronized(this) {
                if (lease == token) {
                    state = State.IDLE
                    lease = null
                    operations.release(token)
                }
            }
        }
    }

    @Synchronized fun cancel(reason: String) {
        val token = lease ?: return
        if (state == State.FINISHING) return
        state = State.FINISHING
        handler.post { finish(token, false, reason) }
    }

    private fun rotateGimbal(pitchDeg: Double) {
        runCatching {
            KeyManager.getInstance().setValue(KeyTools.createKey(GimbalKey.KeyGimbalMode, currentCameraIndex),
                GimbalMode.YAW_FOLLOW, null)
            val rotation = GimbalAngleRotation().apply {
                mode = GimbalAngleRotationMode.ABSOLUTE_ANGLE
                pitch = pitchDeg
                duration = 1.0
            }
            KeyManager.getInstance().performAction(KeyTools.createKey(GimbalKey.KeyRotateByAngle, currentCameraIndex), rotation, null)
        }.onFailure { Log.w(TAG, "预检云台角度请求失败", it) }
    }

    @Synchronized fun release() {
        handler.removeCallbacksAndMessages(null)
        state = State.IDLE
        operations.release(lease)
        lease = null
        inspector.release()
    }
}
