package com.example.msdksample

import kotlin.math.cos
import kotlin.math.sin

/** 安装参数统一填在这里；照片约值和临时估值见各项注释。null 表示待补齐，禁止启动降落。
 * 板坐标：+X 向右，+Y 指向期望机头方向，+Z 离开板面；原点为两码中心的中点。
 * 两码必须刚性安装、共面，使用不同 ID。尺寸含黑边，不含外侧白边。
 */
object DualMarkerLandingConfig {
    // 机巢整图的码纹匹配 DICT_5X5_50 中的 ID 1 / 2；箭头为期望机头方向。
    val dictionaryId: Int? = 4 // OpenCV Objdetect.DICT_5X5_50
    val frontId: Int? = 1
    val rearId: Int? = 2
    // 按尺子照片读数：黑框约 6 cm，前码 0..6 cm、后码 9..15 cm。
    val frontSizeM: Double? = 0.06
    val rearSizeM: Double? = 0.06
    val centerDistanceM: Double? = 0.09
    // 从板 +X 向 +Y 为正旋转；码的规范 +Y（打印图上方）朝板 +Y 时为 0。
    val frontRotationDeg: Double? = 0.0
    val rearRotationDeg: Double? = 0.0
    val cameraForwardM: Double? = 0.11 // 用户提供：相机在飞机中心前方 11 cm
    val cameraRightM: Double? = 0.0 // 按相机位于机身纵向中心线建模
    const val AIRCRAFT_MODEL = "DJI Matrice 4T"
    const val LANDING_GEAR_TO_BODY_BOTTOM_M = 0.08 // 已确认：起落架接触面到机身底部；不参与光心高度计算
    // 保留旧工程的近似水平视场角，尚非 M4T 实测标定；官方 82° FOV 不能直接当作水平角。
    const val WIDE_CAMERA_HORIZONTAL_FOV_DEG = 70.3
    val nestTopHeightM: Double = 0.15 // 用户提供的机巢顶面离地约值，安装后复测
    val markerPlaneHeightM: Double? = nestTopHeightM // 用户已确认：码面与起落架承载面同高
    // 用户指定下视光心到起落架接触面为 9 cm；与机身底部的 8 cm 尺寸分开。
    val cameraHeightAboveFeetM: Double? = 0.09
    val handoffClearanceM: Double? = 0.50 // 用户指定：起落架距承载面 50 cm 时交给 DJI 自动降落

    // 双码输出是相机到码平面的法向距离，不是起落架离地高度。
    val cameraToBoardAtTouchdownM: Double?
        get() = markerPlaneHeightM?.let { markerHeight ->
            cameraHeightAboveFeetM?.let { cameraHeight -> cameraHeight + nestTopHeightM - markerHeight }
        }
    // 当前码面与承载面同高，正常交接的相机到码面距离为 0.09 + 0.50 = 0.59 m。
    val handoffDepthM: Double?
        get() = cameraToBoardAtTouchdownM?.let { contact -> handoffClearanceM?.let { contact + it } }

    fun nestClearanceM(cameraToBoardM: Double): Double? =
        cameraToBoardAtTouchdownM?.let { cameraToBoardM - it }

    const val LOW_ALTITUDE_FALLBACK_MARGIN_M = 0.15
    const val DESCENT_STALL_MS = 3500L
    const val MIN_DESCENT_PROGRESS_M = 0.02
    const val FINAL_STATUS_WARNING_MS = 30_000L
    const val MOTOR_OFF_WARNING_MS = 10_000L
    const val FINAL_COMMAND_TIMEOUT_MS = 8000L
    const val FINAL_CONFIRM_RETRY_MS = 2000L
    const val MAX_CONFIRM_ATTEMPTS = 3

    const val ALIGN_TOLERANCE_M = 0.05
    const val MAX_REPROJECTION_ERROR_PX = 3.0
    const val MAX_POSITION_JUMP_M = 0.25
    const val MAX_YAW_JUMP_DEG = 20.0

    fun visionError(): String? {
        if (dictionaryId == null || dictionaryId !in 0..21) return "请填写并确认两码字典 dictionaryId (0..21)"
        if (frontId == null || rearId == null || frontId < 0 || rearId < 0 || frontId == rearId)
            return "请填写两个不同的非负 ArUco ID"
        val dictionarySizes = intArrayOf(50, 100, 250, 1000, 50, 100, 250, 1000,
            50, 100, 250, 1000, 50, 100, 250, 1000, 1024, 30, 35, 2320, 587, 250)
        if (frontId >= dictionarySizes[dictionaryId] || rearId >= dictionarySizes[dictionaryId])
            return "标记 ID 超出所选字典范围"
        if (listOf(frontSizeM, rearSizeM, centerDistanceM).any { it == null || !it.isFinite() || it <= 0.0 })
            return "请填写两码黑框边长和中心间距（米）"
        if (listOf(frontRotationDeg, rearRotationDeg).any { it == null || !it.isFinite() })
            return "请填写两码安装旋转角（度）"
        return null
    }

    fun landingError(): String? = visionError() ?: when {
        listOf(cameraForwardM, cameraRightM).any { it == null || !it.isFinite() } -> "请填写相机相对触地点的偏移（米）"
        !nestTopHeightM.isFinite() || nestTopHeightM < 0.0 -> "请确认机巢顶面离地高度"
        markerPlaneHeightM == null || !markerPlaneHeightM.isFinite() || markerPlaneHeightM < 0.0 -> "请填写码平面离地高度"
        cameraHeightAboveFeetM == null || !cameraHeightAboveFeetM.isFinite() || cameraHeightAboveFeetM <= 0.0 -> "请填写相机光心到起落架接触面的高度"
        handoffClearanceM == null || !handoffClearanceM.isFinite() || handoffClearanceM <= 0.0 -> "请填写起落架到巢顶的自动降落交接间隙"
        cameraToBoardAtTouchdownM?.let { !it.isFinite() || it <= 0.05 } != false -> "当前码平面/相机高度不适用于此向下双码模型"
        else -> null
    }

    data class Corner(val x: Double, val y: Double, val z: Double = 0.0)

    /** 与 detectMarkers 返回的规范角点顺序一致，不能按图像上下重新排序。 */
    fun corners(size: Double, centerY: Double, rotationDeg: Double): List<Corner> {
        val h = size / 2.0
        val a = Math.toRadians(rotationDeg)
        return listOf(-h to h, h to h, h to -h, -h to -h).map { (x, y) ->
            Corner(cos(a) * x - sin(a) * y, sin(a) * x + cos(a) * y + centerY)
        }
    }
}
