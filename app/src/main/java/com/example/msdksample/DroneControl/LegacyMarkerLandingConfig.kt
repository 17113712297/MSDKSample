package com.example.msdksample

/** 旧板坐标沿用历史源码：+X 右，+Y 期望机头方向。码的规范朝向沿用旧模型。 */
object LegacyMarkerLandingConfig {
    const val DICTIONARY_ID = 10 // OpenCV DICT_6X6_250
    // 用户暂定旧板码面与承载面同高；独立配置，不能混入旧版超声/支架高度。
    const val MARKER_PLANE_ABOVE_SUPPORT_M = 0.0

    data class Marker(val id: Int, val sizeM: Double, val x: Double, val y: Double) {
        fun corners(): List<DualMarkerLandingConfig.Corner> =
            DualMarkerLandingConfig.corners(sizeM, y, 0.0).map { it.copy(x = it.x + x) }
    }

    // 6/8 仅知对称候选位置，左右编号未确认，明确不参与定位。
    val markers: Map<Int, Marker> = listOf(
        Marker(1, 0.180, -0.15, 0.15), Marker(2, 0.180, 0.15, 0.15),
        Marker(3, 0.180, -0.15, -0.15), Marker(4, 0.180, 0.15, -0.15),
        Marker(5, 0.073, 0.0, 0.18), Marker(7, 0.073, 0.0, -0.18),
        Marker(10, 0.036, 0.0, 0.073), Marker(12, 0.036, -0.05, 0.0),
        Marker(13, 0.036, 0.05, 0.0), Marker(14, 0.036, -0.036, 0.0),
        Marker(15, 0.036, 0.036, 0.0), Marker(16, 0.036, 0.0, -0.073)
    ).associateBy { it.id }
}

/** 飞机/控制参数共用，触地几何按已锁定的机巢类型选择。 */
object LandingDockGeometry {
    fun touchdownDepthM(type: LandingDockType): Double = when (type) {
        LandingDockType.NEW_DUAL -> DualMarkerLandingConfig.cameraToBoardAtTouchdownM!!
        LandingDockType.LEGACY_MULTI -> DualMarkerLandingConfig.cameraHeightAboveFeetM!! -
            LegacyMarkerLandingConfig.MARKER_PLANE_ABOVE_SUPPORT_M
    }

    fun handoffDepthM(type: LandingDockType): Double =
        touchdownDepthM(type) + DualMarkerLandingConfig.handoffClearanceM!!

    fun clearanceM(type: LandingDockType, depth: Double): Double = depth - touchdownDepthM(type)

    fun landingError(): String? = DualMarkerLandingConfig.landingError() ?:
        if (touchdownDepthM(LandingDockType.LEGACY_MULTI).let { !it.isFinite() || it <= 0.05 })
            "旧机巢码面/承载面高度配置无效" else null
}
