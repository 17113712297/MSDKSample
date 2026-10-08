package com.example.msdksample

/** 无 Android 依赖，便于回归测试失帧和交接条件。所有时间均为单调时钟。 */
object LandingVisionPolicy {
    fun isFresh(now: Long, timestamp: Long, valid: Boolean, maxAgeMs: Long): Boolean =
        valid && timestamp > 0L && now >= timestamp && now - timestamp <= maxAgeMs

    fun canHandoff(depth: Double, handoffDepth: Double, radialError: Double,
                   yawError: Double, stable: Boolean): Boolean =
        stable && depth.isFinite() && depth > 0.05 && depth <= handoffDepth &&
            radialError.isFinite() && radialError <= DualMarkerLandingConfig.ALIGN_TOLERANCE_M &&
            yawError.isFinite() && kotlin.math.abs(yawError) < 6.0
}
