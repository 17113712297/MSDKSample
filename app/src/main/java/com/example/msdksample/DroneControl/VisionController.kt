package com.example.msdksample

import android.os.SystemClock
import android.util.Log
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.objdetect.ArucoDetector
import org.opencv.objdetect.DetectorParameters
import org.opencv.objdetect.Objdetect
import org.opencv.imgproc.Imgproc
import kotlin.math.*

/** 搜索时识别两种板；任务类型锁定后只运行该板的检测器。 */
class VisionController(private val dockSession: LandingDockSession) {
    companion object {
        const val TAG = "VisionTest"
        const val HFOV_DEG = DualMarkerLandingConfig.WIDE_CAMERA_HORIZONTAL_FOV_DEG
        private const val RESET_AFTER_MS = 200L
    }

    private data class Pose(
        val type: LandingDockType, val id: Int, val x: Double, val y: Double,
        val depth: Double, val yaw: Double, val score: Double
    )

    private val config = DualMarkerLandingConfig
    private val detectors = HashMap<LandingDockType, ArucoDetector>()
    private var gray: Mat? = null
    private var sourceGray: Mat? = null
    private var imageScale = 1.0
    private var cameraMatrix: Mat? = null
    private val distortion = MatOfDouble(0.0, 0.0, 0.0, 0.0, 0.0)
    private val ids = Mat()
    private val detected = ArrayList<Mat>()
    private val rejected = ArrayList<Mat>()
    private var lastAcceptedMs = 0L
    private var lastRaw: Pose? = null
    private var candidatePose: Pose? = null
    private var candidateTime = 0L
    private var filterSessionId: Long? = null
    private var fx = 0.0
    private var fy = 0.0
    private var fz = 0.0
    private var yaw = 0.0
    private var lastLogMs = 0L
    private var metricsStartedMs = 0L
    private var measuredFrames = 0
    private var maxProcessingMs = 0L

    var onTargetLocked: ((LandingVisionResult) -> Unit)? = null
    var onTargetLost: (() -> Unit)? = null

    private fun clear(items: ArrayList<Mat>) {
        items.forEach { it.release() }
        items.clear()
    }

    private fun reject(reason: String) {
        onTargetLost?.invoke()
        val now = SystemClock.elapsedRealtime()
        if (now - lastLogMs > 1000L) {
            Log.d(TAG, reason)
            lastLogMs = now
        }
    }

    private fun wrap(degrees: Double): Double = ((degrees + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    private fun compatible(a: Pose, b: Pose): Boolean =
        a.type == b.type && sqrt((a.x-b.x).pow(2) + (a.y-b.y).pow(2) + (a.depth-b.depth).pow(2)) <= config.MAX_POSITION_JUMP_M &&
            abs(wrap(a.yaw-b.yaw)) <= config.MAX_YAW_JUMP_DEG

    private fun imageCorners(index: Int, width: Int, height: Int): List<Point>? {
        val values = FloatArray(8)
        detected[index].get(0, 0, values)
        val points = (0..3).map { Point(values[it * 2].toDouble(), values[it * 2 + 1].toDouble()) }
        if (points.any { !it.x.isFinite() || !it.y.isFinite() || it.x < 5 || it.y < 5 || it.x > width - 5 || it.y > height - 5 }) return null
        return points.takeIf { pixelArea(it) >= 400.0 * imageScale * imageScale }
    }

    private fun pixelArea(points: List<Point>): Double = abs(points.indices.sumOf { i ->
        val p = points[i]; val q = points[(i + 1) % points.size]
        p.x * q.y - q.x * p.y
    }) / 2.0

    /** 模型角点已处于板坐标系，所以 tvec 直接是板中心，而非当前码中心。 */
    private fun solve(type: LandingDockType, id: Int, model: List<DualMarkerLandingConfig.Corner>,
                      pixels: List<Point>, score: Double): Pose? {
        val objectPoints = MatOfPoint3f()
        val imagePoints = MatOfPoint2f()
        val projected = MatOfPoint2f()
        val rvec = Mat(); val tvec = Mat(); val rotation = Mat()
        try {
            objectPoints.fromList(model.map { Point3(it.x, it.y, it.z) })
            imagePoints.fromList(pixels)
            if (!Calib3d.solvePnP(objectPoints, imagePoints, cameraMatrix, distortion, rvec, tvec,
                    false, Calib3d.SOLVEPNP_ITERATIVE)) return null
            Calib3d.Rodrigues(rvec, rotation)
            val x = tvec.get(0, 0)[0]; val y = tvec.get(1, 0)[0]; val z = tvec.get(2, 0)[0]
            val heading = Math.toDegrees(atan2(rotation.get(0, 1)[0], -rotation.get(1, 1)[0]))
            val depth = abs(rotation.get(0, 2)[0] * x + rotation.get(1, 2)[0] * y + rotation.get(2, 2)[0] * z)
            if (!x.isFinite() || !y.isFinite() || !z.isFinite() || !heading.isFinite() || !depth.isFinite() ||
                depth <= 0.05 || z !in 0.05..30.0 || hypot(x, y) > 8.0 || model.any {
                    rotation.get(2, 0)[0] * it.x + rotation.get(2, 1)[0] * it.y + rotation.get(2, 2)[0] * it.z + z <= 0.0
                }) return null
            Calib3d.projectPoints(objectPoints, rvec, tvec, cameraMatrix, distortion, projected)
            val reprojection = projected.toArray()
            if (pixels.indices.any {
                    val error = hypot(pixels[it].x - reprojection[it].x, pixels[it].y - reprojection[it].y)
                    !error.isFinite() || error > config.MAX_REPROJECTION_ERROR_PX * imageScale
                }) return null
            return Pose(type, id, x, y, depth, heading, score)
        } finally {
            objectPoints.release(); imagePoints.release(); projected.release()
            rvec.release(); tvec.release(); rotation.release()
        }
    }

    private fun detect(type: LandingDockType, width: Int, height: Int): Pose? {
        val detector = detectors.getOrPut(type) {
            val dictionaryId = if (type == LandingDockType.NEW_DUAL) config.dictionaryId!!
                               else LegacyMarkerLandingConfig.DICTIONARY_ID
            ArucoDetector(Objdetect.getPredefinedDictionary(dictionaryId), DetectorParameters())
        }
        clear(detected); clear(rejected)
        detector.detectMarkers(gray, detected, ids, rejected)
        if (ids.empty()) return null
        val found = IntArray(ids.rows())
        ids.get(0, 0, found)
        if (type == LandingDockType.NEW_DUAL) {
            val front = found.indices.filter { found[it] == config.frontId }
            val rear = found.indices.filter { found[it] == config.rearId }
            if (front.size != 1 || rear.size != 1) return null
            val frontPixels = imageCorners(front.single(), width, height) ?: return null
            val rearPixels = imageCorners(rear.single(), width, height) ?: return null
            val halfDistance = config.centerDistanceM!! / 2.0
            val model = config.corners(config.frontSizeM!!, halfDistance, config.frontRotationDeg!!) +
                config.corners(config.rearSizeM!!, -halfDistance, config.rearRotationDeg!!)
            return solve(type, config.frontId!!, model, frontPixels + rearPixels, 0.0)
        }

        val known = found.indices.filter { found[it] in LegacyMarkerLandingConfig.markers }
        if (known.map { found[it] }.distinct().size != known.size) return null
        val poses = known.mapNotNull { index ->
            val marker = LegacyMarkerLandingConfig.markers.getValue(found[index])
            val pixels = imageCorners(index, width, height) ?: return@mapNotNull null
            val workingArea = pixelArea(pixels)
            if (workingArea > width.toDouble() * height * 0.55) return@mapNotNull null
            val area = workingArea / (imageScale * imageScale)
            val score = (if (area in 1500.0..25000.0) area else area * 0.4) +
                1000.0 / (1.0 + hypot(marker.x, marker.y))
            solve(type, marker.id, marker.corners(), pixels, score)
        }
        val best = poses.maxByOrNull { it.score } ?: return null
        // 同时看见的旧码必须指向一致的板中心，避免错误布局/多个板被混用。
        return best.takeIf { poses.all { compatible(best, it) } }
    }

    @Synchronized
    fun processFrame(data: ByteArray, offset: Int, length: Int, width: Int, height: Int,
                     capturedAt: Long = SystemClock.elapsedRealtime(), expectedSessionId: Long? = dockSession.activeId()) {
        val processingStarted = SystemClock.elapsedRealtime()
        val frameTime = capturedAt
        val sessionId = expectedSessionId
        if (sessionId != dockSession.activeId()) return // 丢弃排队中的旧任务/预检帧，不影响当前任务。
        fun miss(reason: String) {
            if (sessionId != null) dockSession.observe(sessionId, false, false, frameTime)
            candidatePose = null
            reject(reason)
        }
        config.visionError()?.let { miss(it); return }
        if (frameTime <= 0L || frameTime > processingStarted || processingStarted - frameTime > RESET_AFTER_MS) {
            miss("相机帧已过期"); return
        }
        if (width <= 0 || height <= 0 || offset < 0 || length < width.toLong() * height ||
            offset.toLong() + width.toLong() * height > data.size) {
            miss("无效 Y 平面缓冲区"); return
        }
        try {
            if (filterSessionId != sessionId) { resetTracking(); filterSessionId = sessionId }
            val detectWidth = width.coerceAtMost(LandingCameraModel.MAX_DETECTION_WIDTH)
            val detectHeight = (height.toDouble() * detectWidth / width).roundToInt().coerceAtLeast(1)
            imageScale = detectWidth.toDouble() / width
            if (gray?.cols() != detectWidth || gray?.rows() != detectHeight) {
                val intrinsics = LandingCameraModel.intrinsics(detectWidth, detectHeight)
                    ?: run { miss("相机标定参数无效或画面裁剪比例不匹配"); return }
                gray?.release(); cameraMatrix?.release()
                gray = Mat(detectHeight, detectWidth, CvType.CV_8UC1)
                cameraMatrix = Mat(3, 3, CvType.CV_64F).apply {
                    put(0, 0, intrinsics.fx, 0.0, intrinsics.cx, 0.0, intrinsics.fy, intrinsics.cy, 0.0, 0.0, 1.0)
                }
                distortion.fromArray(*intrinsics.distortion.toDoubleArray())
                Log.i(TAG, "识别分辨率 ${detectWidth}x${detectHeight}, calibrated=${intrinsics.calibrated}")
                resetTracking() // 只清测量缓存，不清任务类型锁。
            }
            if (detectWidth == width && detectHeight == height) {
                gray!!.put(0, 0, data, offset, width * height)
            } else {
                if (sourceGray?.cols() != width || sourceGray?.rows() != height) {
                    sourceGray?.release(); sourceGray = Mat(height, width, CvType.CV_8UC1)
                }
                sourceGray!!.put(0, 0, data, offset, width * height)
                Imgproc.resize(sourceGray, gray, Size(detectWidth.toDouble(), detectHeight.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            }
            val locked = if (sessionId != null) dockSession.lockedType() else null
            val newPose = if (locked != LandingDockType.LEGACY_MULTI) detect(LandingDockType.NEW_DUAL, detectWidth, detectHeight) else null
            val legacyPose = if (locked != LandingDockType.NEW_DUAL) detect(LandingDockType.LEGACY_MULTI, detectWidth, detectHeight) else null
            val measuredAt = SystemClock.elapsedRealtime()
            if (metricsStartedMs == 0L) metricsStartedMs = measuredAt
            measuredFrames++
            maxProcessingMs = max(maxProcessingMs, measuredAt - processingStarted)
            if (measuredAt - metricsStartedMs >= 2000L) {
                Log.i(TAG, "视觉性能: fps=${measuredFrames * 1000L / (measuredAt - metricsStartedMs)} maxProcessMs=$maxProcessingMs frameAgeMs=${measuredAt-frameTime}")
                measuredFrames = 0; maxProcessingMs = 0L; metricsStartedMs = measuredAt
            }
            if (SystemClock.elapsedRealtime() - frameTime > RESET_AFTER_MS) { miss("视觉处理延迟过大"); return }
            if (newPose != null && legacyPose != null) { miss("两类机巢同时有效，等待唯一目标"); return }
            val pose = newPose ?: legacyPose ?: run { miss("未看到当前任务可用的机巢码"); return }

            if (sessionId != null && locked == null) {
                val prev = candidatePose
                val consistent = prev == null || frameTime - candidateTime > RESET_AFTER_MS || compatible(prev, pose)
                candidatePose = pose; candidateTime = frameTime
                if (!consistent) {
                    dockSession.observe(sessionId, false, false, frameTime)
                    reject("机巢候选位姿不连续，重新累计确认"); return
                }
                val selected = dockSession.observe(sessionId, newPose != null, legacyPose != null, frameTime)
                if (selected == null) { reject("正在确认${pose.type.label}，保持零速度"); return }
                Log.i(TAG, "降落类型已锁定: ${selected.label}, session=$sessionId")
            }
            if (sessionId != null && !dockSession.accepts(sessionId, pose.type)) {
                reject("任务已结束或视觉结果不属于当前锁定类型"); return
            }

            val recent = lastAcceptedMs > 0L && frameTime - lastAcceptedMs <= RESET_AFTER_MS
            val previous = lastRaw
            // 大小码切换也必须连续；不能利用一次短暂丢帧绕过切码跳变检查。
            val switchingMarker = previous != null && previous.id != pose.id
            if (previous != null && (recent || switchingMarker) && !compatible(previous, pose)) {
                miss("板中心位姿跳变，拒绝切码/测量"); return
            }
            val alpha = if (recent) 0.35 else 1.0
            fx += alpha * (pose.x - fx); fy += alpha * (pose.y - fy); fz += alpha * (pose.depth - fz)
            yaw = wrap(yaw + alpha * wrap(pose.yaw - yaw))
            lastAcceptedMs = frameTime; lastRaw = pose
            onTargetLocked?.invoke(LandingVisionResult(pose.type, pose.id, fx, fy, fz, yaw, frameTime, sessionId))
        } catch (error: Exception) {
            miss("机巢识别异常: ${error.message}")
            Log.e(TAG, "机巢识别异常", error)
        }
    }

    /** 不允许在这里重选新/旧机巢，包括重新开流、画面尺寸变化和丢码恢复。 */
    @Synchronized fun resetTracking() {
        lastAcceptedMs = 0L; lastRaw = null; candidatePose = null; candidateTime = 0L
        dockSession.activeId()?.let { dockSession.observe(it, false, false, SystemClock.elapsedRealtime()) }
    }

    @Synchronized fun release() {
        gray?.release(); gray = null
        sourceGray?.release(); sourceGray = null
        cameraMatrix?.release(); cameraMatrix = null
        distortion.release(); ids.release(); clear(detected); clear(rejected)
        resetTracking()
    }
}
