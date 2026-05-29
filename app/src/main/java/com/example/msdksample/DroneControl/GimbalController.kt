package com.example.msdksample

import android.os.Handler
import android.os.Looper
import android.util.Log
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode
import dji.sdk.keyvalue.value.gimbal.GimbalMode
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager

/**
 * GimbalController
 *
 * 封装 MSDK V5 的云台控制：
 *   1) 偏航跟随模式：yaw 跟随飞机航向，pitch / roll 可独立控制
 *   2) 角度模式：一次性将云台转动到目标姿态 (绝对 / 相对)
 *
 * ── 与原版相比的修复 ──────────────────────────────────────────
 *   原版在 setMode(YAW_FOLLOW) 的 onSuccess 回调里 **立刻** 下发 rotate，
 *   但 SDK 的 onSuccess 通常只表示「指令被接受」，不代表底层云台
 *   已经真正切到新模式。在旧模式还没退出时下角度，可能被旧模式
 *   的语义执行一下再被新模式覆盖，产生可见抖动。
 *
 *   本版改为：setMode 成功后 postDelayed MODE_SETTLE_MS 再下角度，
 *   给底层云台留出实际切换时间。只用 setValue + performAction，
 *   不依赖 getValue 读取当前模式 (KeyGimbalMode 是否可读未确认)。
 *
 * ── Mavic 3T 云台特性 ─────────────────────────────────────────
 *   - roll 轴不可独立控制，下发会被忽略
 *   - pitch 范围约 -90° (向下) ~ +35° (向上)
 *   - YAW_FOLLOW 模式下 yaw 自动跟随机头，下发 yaw 无效
 */
class GimbalController(
    /** 云台索引，M3T 只有一个云台，用 LEFT_OR_MAIN */
    private val gimbalIndex: ComponentIndexType = ComponentIndexType.LEFT_OR_MAIN
) {

    companion object {
        private const val TAG = "GimbalController"

        /** 默认转动时间 s */
        const val DEFAULT_DURATION = 1.0

        /** 切换 GimbalMode 后等待底层云台真正进入新模式的延迟 (ms) */
        private const val MODE_SETTLE_MS = 150L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 日志回调，供 Activity 把信息打到 UI */
    var logCallback: ((String) -> Unit)? = null

    // ─────────────────────────────────────────────────────
    //  公开 API
    // ─────────────────────────────────────────────────────

    /**
     * 偏航跟随模式 + 调整 pitch / roll
     *
     *   1. 先 setMode → YAW_FOLLOW；
     *   2. onSuccess 回调里 postDelayed MODE_SETTLE_MS 让底层云台真正生效；
     *   3. 再下发绝对角度，yaw 设为 ignored = true，
     *      由 YAW_FOLLOW 模式接管自动跟随机头。
     */
    fun setYawFollow(
        pitch: Float, roll: Float,
        onResult: (Boolean, String) -> Unit
    ) {
        log("CMD: YAW_FOLLOW pitch=$pitch roll=$roll")

        KeyManager.getInstance().setValue(
            KeyTools.createKey(GimbalKey.KeyGimbalMode, gimbalIndex),
            GimbalMode.YAW_FOLLOW,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    log("Gimbal mode → YAW_FOLLOW OK，等待 ${MODE_SETTLE_MS}ms 生效后下角度")
                    mainHandler.postDelayed({
                        rotateAbsolute(
                            pitch        = pitch.toDouble(),
                            roll         = roll.toDouble(),
                            yaw          = 0.0,
                            duration     = DEFAULT_DURATION,
                            pitchIgnored = false,
                            rollIgnored  = false,
                            yawIgnored   = true,   // 跟随模式下 yaw 交给飞机
                            onResult     = onResult
                        )
                    }, MODE_SETTLE_MS)
                }
                override fun onFailure(error: IDJIError) {
                    val msg = error.description() ?: error.errorCode()
                    log("Gimbal mode set FAIL: $msg")
                    onResult(false, "setMode FAIL: $msg")
                }
            }
        )
    }

    /**
     * 角度模式：转动到目标姿态
     *
     * @param mode     GIMBAL_MODE_ABSOLUTE (0x00) 绝对  / GIMBAL_MODE_RELATIVE (0x01) 相对
     * @param pitch    俯仰角 deg
     * @param roll     横滚角 deg
     * @param yaw      偏航角 deg
     * @param duration 完成转动时间 s，≤0 时使用 DEFAULT_DURATION
     */
    fun rotateByAngle(
        mode: Byte, pitch: Float, roll: Float, yaw: Float, duration: Float,
        onResult: (Boolean, String) -> Unit
    ) {
        val dur = if (duration > 0f) duration.toDouble() else DEFAULT_DURATION
        val isRelative = (mode == DroneCommProtocol.GIMBAL_MODE_RELATIVE)
        log("CMD: GIMBAL_ANGLE ${if (isRelative) "relative" else "absolute"} " +
            "pitch=$pitch roll=$roll yaw=$yaw dur=${dur}s")

        if (isRelative) {
            rotateRelative(pitch.toDouble(), roll.toDouble(), yaw.toDouble(), dur, onResult)
        } else {
            rotateAbsolute(
                pitch        = pitch.toDouble(),
                roll         = roll.toDouble(),
                yaw          = yaw.toDouble(),
                duration     = dur,
                pitchIgnored = false,
                rollIgnored  = false,
                yawIgnored   = false,
                onResult     = onResult
            )
        }
    }

    // ─────────────────────────────────────────────────────
    //  内部：调用 MSDK KeyRotateByAngle
    // ─────────────────────────────────────────────────────

    private fun rotateAbsolute(
        pitch: Double, roll: Double, yaw: Double, duration: Double,
        pitchIgnored: Boolean, rollIgnored: Boolean, yawIgnored: Boolean,
        onResult: (Boolean, String) -> Unit
    ) {
        val rotation = GimbalAngleRotation().apply {
            this.mode         = GimbalAngleRotationMode.ABSOLUTE_ANGLE
            this.pitch        = pitch
            this.roll         = roll
            this.yaw          = yaw
            this.duration     = duration
            this.pitchIgnored = pitchIgnored
            this.rollIgnored  = rollIgnored
            this.yawIgnored   = yawIgnored
            // 使用关节坐标系 = false 表示地理坐标系 (north-ref)，多数场景更直观
            this.jointReferenceUsed = false
        }
        performRotate(rotation, "ABS", onResult)
    }

    private fun rotateRelative(
        pitch: Double, roll: Double, yaw: Double, duration: Double,
        onResult: (Boolean, String) -> Unit
    ) {
        val rotation = GimbalAngleRotation().apply {
            this.mode         = GimbalAngleRotationMode.RELATIVE_ANGLE
            this.pitch        = pitch
            this.roll         = roll
            this.yaw          = yaw
            this.duration     = duration
            this.pitchIgnored = false
            this.rollIgnored  = false
            this.yawIgnored   = false
            this.jointReferenceUsed = false
        }
        performRotate(rotation, "REL", onResult)
    }

    private fun performRotate(
        rotation: GimbalAngleRotation,
        tag: String,
        onResult: (Boolean, String) -> Unit
    ) {
        KeyManager.getInstance().performAction(
            KeyTools.createKey(GimbalKey.KeyRotateByAngle, gimbalIndex),
            rotation,
            object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(value: EmptyMsg?) {
                    log("Gimbal rotate[$tag] OK")
                    onResult(true, "OK")
                }
                override fun onFailure(error: IDJIError) {
                    val msg = error.description() ?: error.errorCode()
                    log("Gimbal rotate[$tag] FAIL: $msg")
                    onResult(false, msg)
                }
            }
        )
    }

    // ─────────────────────────────────────────────────────
    //  工具
    // ─────────────────────────────────────────────────────

    private fun log(msg: String) {
        Log.i(TAG, msg)
        mainHandler.post { logCallback?.invoke(msg) }
    }
}
