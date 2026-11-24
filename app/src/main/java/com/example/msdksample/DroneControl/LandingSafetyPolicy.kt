package com.example.msdksample

import kotlin.math.abs
import kotlin.math.hypot

object LandingSafetyPolicy {
    const val GIMBAL_TIMEOUT_MS = 8000L
    const val GIMBAL_STABLE_MS = 500L
    const val GIMBAL_PITCH_TOLERANCE_DEG = 1.5
    const val GIMBAL_ROLL_TOLERANCE_DEG = 2.0
    const val GIMBAL_YAW_TOLERANCE_DEG = 3.0
    const val AUTHORITY_TIMEOUT_MS = 2000L
    const val HANDOFF_STABLE_MS = 800L
    const val MAX_HANDOFF_HORIZONTAL_SPEED = 0.05
    const val MAX_HANDOFF_VERTICAL_SPEED = 0.10
    const val MAX_HANDOFF_TILT_DEG = 3.0
    const val ENTER_RADIAL_ERROR_M = 0.03
    const val ENTER_YAW_ERROR_DEG = 2.0

    /** 常规对齐角速度上限 */
    const val ALIGN_YAW_CAP_DEG = 20.0
    /** 大角度粗对齐的角速度上限（仅在高帧率、误差 > 30° 时启用） */
    const val ALIGN_YAW_FAST_CAP_DEG = 35.0
    /** 启用"快转"的角误差门槛 */
    const val ALIGN_YAW_FAST_ERROR_DEG = 30.0
    /** 启用"快转"的最低视觉帧率；帧率低时仍用常规上限，避免糊帧越转越瞎 */
    const val ALIGN_YAW_FAST_MIN_FPS = 3.0
    /**
     * 视觉帧失效时允许保持 yaw 角速度的时长。
     *
     * 为什么需要：PSDK 视觉帧率偏低时（约 2 fps），每帧失效都会把角速度清 0，
     * 而角加速度限幅只有 30 °/s²，重新加速到 20 °/s 要 0.67 s —— 平均转速会掉到
     * 命令值的 1/3，180° 转头就会撞上 15 s 对齐超时。
     * 保持 300 ms 的过冲上界 = 0.8 × 误差 × 0.3 ≤ 误差的 24%，且下一帧立即按新误差回算，
     * 闭环仍是一阶收敛，不会自激摆动。
     */
    const val YAW_HOLD_MS = 300L

    fun gimbalReady(pitch: Double, roll: Double, relativeYaw: Double): Boolean =
        pitch.isFinite() && roll.isFinite() && relativeYaw.isFinite() &&
            abs(pitch + 90.0) <= GIMBAL_PITCH_TOLERANCE_DEG &&
            abs(roll) <= GIMBAL_ROLL_TOLERANCE_DEG && abs(relativeYaw) <= GIMBAL_YAW_TOLERANCE_DEG

    fun motionSettled(vx: Double, vy: Double, vz: Double, pitch: Double, roll: Double): Boolean =
        listOf(vx, vy, vz, pitch, roll).all { it.isFinite() } &&
            hypot(vx, vy) <= MAX_HANDOFF_HORIZONTAL_SPEED && abs(vz) <= MAX_HANDOFF_VERTICAL_SPEED &&
            abs(pitch) <= MAX_HANDOFF_TILT_DEG && abs(roll) <= MAX_HANDOFF_TILT_DEG

    fun authorityValid(enabled: Boolean, advanced: Boolean, msdkOwner: Boolean): Boolean =
        enabled && advanced && msdkOwner

    /** 兼容旧调用：不启用大角度快转 */
    fun alignmentYawRate(error: Double): Double = alignmentYawRate(error, 0.0)

    /**
     * 航向对齐角速度：`0.8 × 误差`，常规上限 20 °/s。
     *
     * 当误差 > 30° 且最近视觉帧率 ≥ 3 fps 时，上限放宽到 35 °/s —— 180° 初始对头
     * 可以少花约 3 秒。帧率低时不放大：转太快只会让码更糊、帧率更低。
     */
    fun alignmentYawRate(error: Double, recentFps: Double): Double {
        if (!error.isFinite()) return 0.0
        val fast = abs(error) > ALIGN_YAW_FAST_ERROR_DEG && recentFps >= ALIGN_YAW_FAST_MIN_FPS
        val cap = if (fast) ALIGN_YAW_FAST_CAP_DEG else ALIGN_YAW_CAP_DEG
        return (0.8 * error).coerceIn(-cap, cap)
    }

    /** 视觉帧失效时是否仍在 yaw 保持窗口内（只保持航向，平移/升降照旧清零）。 */
    fun shouldHoldYaw(nowMs: Long, lastValidMs: Long, holdMs: Long = YAW_HOLD_MS): Boolean =
        lastValidMs > 0L && nowMs >= lastValidMs && nowMs - lastValidMs <= holdMs
}

/**
 * 最近若干帧的到达速率估计（只统计被接受的新帧）。
 *
 * 用于"高帧率才允许快转"这一条件：帧率低时保持 20 °/s 上限，
 * 避免为了赶时间反而把画面转糊、越转越丢码。
 */
class FrameRateEstimator(private val window: Int = 6) {
    private val times = ArrayDeque<Long>()

    fun reset() = times.clear()

    fun record(timestampMs: Long) {
        if (timestampMs <= 0L) return
        if (times.isNotEmpty() && timestampMs <= times.last()) return  // 重复/过期帧不计
        times.addLast(timestampMs)
        while (times.size > window) times.removeFirst()
    }

    /** 估计帧率（Hz）；不足两帧返回 0 */
    fun hz(): Double {
        if (times.size < 2) return 0.0
        val span = times.last() - times.first()
        return if (span <= 0L) 0.0 else (times.size - 1) * 1000.0 / span
    }
}

/** 3 cm / 2° 内进入下降，仍以 4 cm / 3° 为退出上限；失帧立即关闭。 */
class LandingAlignmentGate {
    var open = false
        private set
    fun reset() { open = false }
    fun update(radialError: Double, yawError: Double, freshAndStable: Boolean): Boolean {
        if (!freshAndStable || !radialError.isFinite() || !yawError.isFinite() || radialError < 0.0 ||
            radialError > DualMarkerLandingConfig.ALIGN_TOLERANCE_M || abs(yawError) >= 3.0) {
            open = false
        } else if (!open && radialError <= LandingSafetyPolicy.ENTER_RADIAL_ERROR_M &&
            abs(yawError) < LandingSafetyPolicy.ENTER_YAW_ERROR_DEG) open = true
        return open
    }
}
