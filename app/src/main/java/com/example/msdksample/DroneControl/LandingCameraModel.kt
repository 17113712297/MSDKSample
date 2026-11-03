package com.example.msdksample

import kotlin.math.abs
import kotlin.math.tan

/** 填入同一广角镜头/裁剪模式的实测标定；不把估值冒充标定。 */
data class LandingCameraCalibration(
    val width: Int, val height: Int,
    val fx: Double, val fy: Double, val cx: Double, val cy: Double,
    val distortion: List<Double> = listOf(0.0, 0.0, 0.0, 0.0, 0.0)
) {
    fun valid(): Boolean = width > 0 && height > 0 && fx.isFinite() && fy.isFinite() && fx > 0 && fy > 0 &&
        cx.isFinite() && cy.isFinite() && cx in 0.0..width.toDouble() && cy in 0.0..height.toDouble() &&
        distortion.size in listOf(4, 5, 8, 12, 14) && distortion.all { it.isFinite() }
}

object LandingCameraModel {
    // 尚无本机实测内参，保持原近似模型。标定后在这里填写，不改变机巢几何参数。
    val calibration: LandingCameraCalibration? = null
    const val MAX_DETECTION_WIDTH = 1280
    data class Intrinsics(val fx: Double, val fy: Double, val cx: Double, val cy: Double,
                          val distortion: List<Double>, val calibrated: Boolean)

    fun intrinsics(width: Int, height: Int, source: LandingCameraCalibration? = calibration): Intrinsics? {
        if (width <= 0 || height <= 0) return null
        if (source != null) {
            if (!source.valid() || abs(width.toDouble() / height - source.width.toDouble() / source.height) > 0.001) return null
            val sx = width.toDouble() / source.width; val sy = height.toDouble() / source.height
            return Intrinsics(source.fx * sx, source.fy * sy, source.cx * sx, source.cy * sy, source.distortion, true)
        }
        val focal = width / (2.0 * tan(Math.toRadians(DualMarkerLandingConfig.WIDE_CAMERA_HORIZONTAL_FOV_DEG / 2.0)))
        return Intrinsics(focal, focal, width / 2.0, height / 2.0, List(5) { 0.0 }, false)
    }
}
