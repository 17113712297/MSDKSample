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

    fun alignmentYawRate(error: Double): Double {
        if (!error.isFinite()) return 0.0
        // 接近 2° 对齐线时降低角速度，取消原来至少 8°/s 的硬下限。
        return (0.8 * error).coerceIn(-20.0, 20.0)
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
