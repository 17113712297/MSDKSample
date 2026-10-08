package com.example.msdksample

import android.os.SystemClock
import android.util.Log
import org.opencv.calib3d.Calib3d
import org.opencv.core.*
import org.opencv.objdetect.ArucoDetector
import org.opencv.objdetect.DetectorParameters
import org.opencv.objdetect.Objdetect
import kotlin.math.*

/** 两码必须同时可见；八个角点联合求解以两码中点为原点的板位姿。 */
class VisionController {
    companion object {
        const val TAG = "VisionTest"
        // 相机参数集中配置；变焦/镜头/裁剪改变后须重新标定。
        const val HFOV_DEG = DualMarkerLandingConfig.WIDE_CAMERA_HORIZONTAL_FOV_DEG
        private const val RESET_AFTER_MS = 200L
    }
    private val config = DualMarkerLandingConfig
    private var detector: ArucoDetector? = null
    private var gray: Mat? = null
    private var cameraMatrix: Mat? = null
    private val distortion = MatOfDouble(0.0, 0.0, 0.0, 0.0, 0.0)
    private val ids = Mat()
    private val detected = ArrayList<Mat>()
    private val rejected = ArrayList<Mat>()
    private var lastAcceptedMs = 0L
    private var fx = 0.0
    private var fy = 0.0
    private var fz = 0.0
    private var yaw = 0.0
    private var lastLogMs = 0L

    // frontId 表示整块板的有效性，不表示只使用了前码。
    var onTargetLocked: ((Int, Double, Double, Double, Double, Long) -> Unit)? = null
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

    private fun imageCorners(index: Int, width: Int, height: Int): List<Point>? {
        val values = FloatArray(8)
        detected[index].get(0, 0, values)
        val points = (0..3).map { Point(values[it * 2].toDouble(), values[it * 2 + 1].toDouble()) }
        if (points.any { !it.x.isFinite() || !it.y.isFinite() || it.x < 5 || it.y < 5 || it.x > width - 5 || it.y > height - 5 }) return null
        val area = abs(points.indices.sumOf { i ->
            val p = points[i]; val q = points[(i + 1) % 4]
            p.x * q.y - q.x * p.y
        }) / 2.0
        return points.takeIf { area >= 400.0 }
    }

    @Synchronized
    fun processFrame(data: ByteArray, offset: Int, length: Int, width: Int, height: Int) {
        val frameTime = SystemClock.elapsedRealtime()
        config.visionError()?.let { reject(it); return }
        if (width <= 0 || height <= 0 || offset < 0 || length < width.toLong() * height ||
            offset.toLong() + width.toLong() * height > data.size) {
            reject("无效 Y 平面缓冲区"); return
        }
        val objectPoints = MatOfPoint3f()
        val imagePoints = MatOfPoint2f()
        val projected = MatOfPoint2f()
        val rvec = Mat()
        val tvec = Mat()
        val rotation = Mat()
        try {
            if (detector == null) {
                val dictionary = Objdetect.getPredefinedDictionary(config.dictionaryId!!)
                val bytes = dictionary.get_bytesList()
                val count = try { bytes.rows() } finally { bytes.release() }
                if (config.frontId!! >= count || config.rearId!! >= count) {
                    reject("标记 ID 超出所选字典范围"); return
                }
                detector = ArucoDetector(dictionary, DetectorParameters())
            }
            if (gray?.cols() != width || gray?.rows() != height) {
                gray?.release(); cameraMatrix?.release()
                gray = Mat(height, width, CvType.CV_8UC1)
                val focal = width / (2.0 * tan(Math.toRadians(HFOV_DEG / 2.0)))
                cameraMatrix = Mat(3, 3, CvType.CV_64F).apply {
                    put(0, 0, focal, 0.0, width / 2.0, 0.0, focal, height / 2.0, 0.0, 0.0, 1.0)
                }
                resetTracking()
            }
            gray!!.put(0, 0, data, offset, width * height)
            clear(detected); clear(rejected)
            detector!!.detectMarkers(gray, detected, ids, rejected)
            if (ids.empty()) { reject("两码不可见"); return }
            val found = IntArray(ids.rows())
            ids.get(0, 0, found)
            val front = found.indices.filter { found[it] == config.frontId }
            val rear = found.indices.filter { found[it] == config.rearId }
            if (front.size != 1 || rear.size != 1) { reject("需要两个唯一的配置标记同时可见"); return }
            val frontPixels = imageCorners(front.single(), width, height)
            val rearPixels = imageCorners(rear.single(), width, height)
            if (frontPixels == null || rearPixels == null) { reject("码过小或角点出界"); return }
            val halfDistance = config.centerDistanceM!! / 2.0
            val model = config.corners(config.frontSizeM!!, halfDistance, config.frontRotationDeg!!) +
                config.corners(config.rearSizeM!!, -halfDistance, config.rearRotationDeg!!)
            objectPoints.fromList(model.map { Point3(it.x, it.y, it.z) })
            val pixels = frontPixels + rearPixels
            imagePoints.fromList(pixels)
            if (!Calib3d.solvePnP(objectPoints, imagePoints, cameraMatrix, distortion, rvec, tvec,
                    false, Calib3d.SOLVEPNP_ITERATIVE)) { reject("双码位姿求解失败"); return }
            Calib3d.Rodrigues(rvec, rotation)
            val x = tvec.get(0, 0)[0]; val y = tvec.get(1, 0)[0]; val z = tvec.get(2, 0)[0]
            val heading = Math.toDegrees(atan2(rotation.get(0, 1)[0], -rotation.get(1, 1)[0]))
            // R 的第 3 列为板面法向；法向距离消除光轴倾斜导致的高度混用。
            val boardDistance = abs(rotation.get(0, 2)[0] * x + rotation.get(1, 2)[0] * y + rotation.get(2, 2)[0] * z)
            if (!x.isFinite() || !y.isFinite() || !z.isFinite() || !heading.isFinite() || !boardDistance.isFinite() || boardDistance <= 0.05 ||
                z !in 0.05..30.0 || hypot(x, y) > 8.0 || model.any {
                    rotation.get(2, 0)[0] * it.x + rotation.get(2, 1)[0] * it.y + z <= 0.0
                }) { reject("双码位姿越界"); return }
            Calib3d.projectPoints(objectPoints, rvec, tvec, cameraMatrix, distortion, projected)
            val reprojection = projected.toArray()
            val errors = pixels.indices.map { hypot(pixels[it].x - reprojection[it].x, pixels[it].y - reprojection[it].y) }
            if (errors.any { !it.isFinite() } || errors.maxOrNull()!! > config.MAX_REPROJECTION_ERROR_PX) {
                reject("双码重投影误差过大，请检查尺寸/朝向/相机标定"); return
            }
            if (SystemClock.elapsedRealtime() - frameTime > RESET_AFTER_MS) { reject("视觉处理延迟过大"); return }
            val recent = lastAcceptedMs > 0L && frameTime - lastAcceptedMs <= RESET_AFTER_MS
            if (recent && (sqrt((x-fx).pow(2) + (y-fy).pow(2) + (boardDistance-fz).pow(2)) > config.MAX_POSITION_JUMP_M ||
                    abs(wrap(heading-yaw)) > config.MAX_YAW_JUMP_DEG)) { reject("双码位姿跳变"); return }
            val alpha = if (recent) 0.35 else 1.0
            fx += alpha * (x - fx); fy += alpha * (y - fy); fz += alpha * (boardDistance - fz)
            yaw = wrap(yaw + alpha * wrap(heading - yaw))
            lastAcceptedMs = frameTime
            onTargetLocked?.invoke(config.frontId!!, fx, fy, fz, yaw, frameTime)
        } catch (error: Exception) {
            reject("双码处理异常: " + error.message)
            Log.e(TAG, "双码处理异常", error)
        } finally {
            objectPoints.release(); imagePoints.release(); projected.release()
            rvec.release(); tvec.release(); rotation.release()
        }
    }

    @Synchronized
    fun resetTracking() { lastAcceptedMs = 0L }

    @Synchronized
    fun release() {
        gray?.release(); gray = null
        cameraMatrix?.release(); cameraMatrix = null
        distortion.release(); ids.release(); clear(detected); clear(rejected)
        resetTracking()
    }
}
