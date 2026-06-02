package com.example.msdksample

import android.util.Log
import org.opencv.calib3d.Calib3d
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.MatOfPoint2f
import org.opencv.core.MatOfPoint3f
import org.opencv.core.Point3
import org.opencv.objdetect.ArucoDetector
import org.opencv.objdetect.DetectorParameters
import org.opencv.objdetect.Objdetect
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt
import kotlin.math.tan

class VisionController {

    companion object {
        const val TAG = "VisionTest"
        const val HFOV_DEG = 70.3
        const val FILTER_ALPHA = 0.35
        const val MIN_VALID_DEPTH_M     = 0.05
        const val MAX_VALID_DEPTH_M     = 30.0
        const val MAX_VALID_LATERAL_M   = 8.0
        const val MAX_DEPTH_JUMP_RATIO  = 0.6
        const val MIN_TAG_PIXEL_AREA  = 400.0
        const val MAX_TAG_AREA_RATIO  = 0.55
        const val OPTIMAL_AREA_MIN    = 1500.0
        const val OPTIMAL_AREA_MAX    = 25000.0
        const val EDGE_MARGIN_PX      = 5
        const val LOG_INTERVAL_MS = 500L

        // ★ 从 MainActivity 移入：目标ID黏性 - 锁定ID丢失多久才允许切换
        const val STICKY_LOSE_TIMEOUT_MS = 2_000L
    }

    private val dictionary = Objdetect.getPredefinedDictionary(Objdetect.DICT_6X6_250)
    private val detectorParams = DetectorParameters()
    private val detector      = ArucoDetector(dictionary, detectorParams)

    private var grayMat: Mat? = null
    private val corners       = ArrayList<Mat>()
    private val ids           = Mat()
    private val rejected      = ArrayList<Mat>()

    private var cameraMatrix: Mat? = null
    private val distCoeffs    = MatOfDouble(0.0, 0.0, 0.0, 0.0, 0.0)
    private val rvec          = Mat()
    private val tvec          = Mat()
    private val rmat          = Mat()

    private var cachedObjPoints: MatOfPoint3f? = null
    private var cachedObjSize  = -1.0

    private var filterInited  = false
    private var fErrX         = 0.0
    private var fErrY         = 0.0
    private var fYaw          = 0.0
    private var lastDepth     = 0.0
    private var lastTargetId  = -1

    // ★ 从 MainActivity 移入：黏性锁定状态
    @Volatile private var stickyTargetId      = -1
    @Volatile private var lastStickyMatchTime = 0L

    var onTargetLocked: ((id: Int, errX: Double, errY: Double, depthZ: Double, yawDeg: Double) -> Unit)? = null

    // ─── 物理参数定义 ───
    private fun getTagPhysicalSize(id: Int): Double = when (id) {
        1, 2, 3, 4             -> 0.180  // 大码 18cm
        5, 6, 7, 8             -> 0.073  // 中码 7.3cm
        10, 12, 13, 14, 15, 16 -> 0.036  // 小码 3.6cm
        else                   -> -1.0
    }

    // ★ 关键：定义每个 Tag 相对降落板正中心的物理偏移 (单位:米)
    private fun getTagOffset(id: Int): Pair<Double, Double> {
        return when (id) {
            14 -> Pair(-0.036, 0.0)  //左方
            15 -> Pair(0.036, 0.0)  //右方
            13 -> Pair(0.05, 0.0)   //右方
            12 -> Pair(-0.05, 0.0)  // 左方
            16 -> Pair(0.0, -0.073)  //下方
            10 -> Pair(0.0, 0.073)  //上方
            5 -> Pair(0.0, 0.18)  // 上方
            7 -> Pair(0.0, -0.18)   // 下方
            2 -> Pair(0.15, 0.15) // 右上
            4 -> Pair(0.15, -0.15)  // 右下
            3 -> Pair(-0.15, -0.15)   // 左下
            1 -> Pair(-0.15, 0.15)   // 左上
            else -> Pair(0.0, 0.0)
        }
    }

    // ★ 从 MainActivity 移入：获取目标优先级
    private fun getTargetPriority(id: Int): Int {
        return when (id) {
            14, 15     -> 5  // 第一优先级：最靠近中心的小码，主导最后几厘米的微调
            10, 16     -> 4  // 第二优先级：上下小码
            12, 13     -> 3  // 第三优先级：左右偏外侧小码
            5, 6, 7, 8 -> 2  // 第四优先级：中码阵列
            1, 2, 3, 4 -> 1  // 第五优先级：大码阵列，主导高空捕捉
            else       -> 0
        }
    }

    private fun ensureCameraMatrix(width: Int, height: Int) {
        val focal = width.toDouble() / (2.0 * tan(Math.toRadians(HFOV_DEG / 2.0)))
        cameraMatrix?.release()
        cameraMatrix = Mat(3, 3, CvType.CV_64F).apply {
            put(0, 0, focal, 0.0, width / 2.0, 0.0, focal, height / 2.0, 0.0, 0.0, 1.0)
        }
    }

    private fun releaseAndClear(list: ArrayList<Mat>) {
        for (m in list) m.release()
        list.clear()
    }

    private fun cornerArea(c: Mat): Double {
        val p = FloatArray(8)
        c.get(0, 0, p)
        return 0.5 * abs((p[0]*p[3]-p[2]*p[1]) + (p[2]*p[5]-p[4]*p[3]) + (p[4]*p[7]-p[6]*p[5]) + (p[6]*p[1]-p[0]*p[7]))
    }

    private fun isFullyInside(c: Mat, width: Int, height: Int): Boolean {
        val p = FloatArray(8)
        c.get(0, 0, p)
        val m = EDGE_MARGIN_PX.toFloat()
        for (i in 0 until 4) if (p[i*2] < m || p[i*2] > width - m || p[i*2+1] < m || p[i*2+1] > height - m) return false
        return true
    }

    private fun pickBestTagIndex(idArr: IntArray, cs: List<Mat>, w: Int, h: Int): Int {
        val areaCap = w.toDouble() * h * MAX_TAG_AREA_RATIO
        var bestIdx = -1
        var bestScore = -1.0
        for (i in idArr.indices) {
            val tagId = idArr[i]
            if (getTagPhysicalSize(tagId) <= 0.0 || !isFullyInside(cs[i], w, h)) continue
            val a = cornerArea(cs[i])
            if (a < MIN_TAG_PIXEL_AREA || a > areaCap) continue

            // 智能加权：越靠近中心的码优先级越高
            val (ox, oy) = getTagOffset(tagId)
            val score = if (a in OPTIMAL_AREA_MIN..OPTIMAL_AREA_MAX) a else a * 0.4
            val priority = (1.0 / (1.0 + sqrt(ox * ox + oy * oy))) * 1000.0
            if (score + priority > bestScore) {
                bestScore = score + priority
                bestIdx = i
            }
        }
        return bestIdx
    }

    private fun extractYawDeg(rmat: Mat): Double {
        return Math.toDegrees(atan2(rmat.get(0, 1)[0], -rmat.get(1, 1)[0]))
    }

    private fun filterYaw(current: Double, target: Double, alpha: Double): Double {
        var d = target - current
        if (d > 180.0) d -= 360.0 else if (d < -180.0) d += 360.0
        var next = current + alpha * d
        return if (next > 180.0) next - 360.0 else if (next < -180.0) next + 360.0 else next
    }

    // ★ 从 MainActivity 移入：目标 ID 黏性过滤逻辑
    private fun applyStickyTargetFilter(id: Int, errX: Double, errY: Double, depthZ: Double, yawDeg: Double) {
        val now = System.currentTimeMillis()
        val currentPriority = getTargetPriority(stickyTargetId)
        val newPriority = getTargetPriority(id)

        // 1. 绝对抢占机制
        if (newPriority > currentPriority && stickyTargetId != -1) {
            Log.w(TAG, "🎯 发现更高精度码(ID:$id 优先级:$newPriority)！抛弃原码(ID:$stickyTargetId)，强制抢占！")
            stickyTargetId = id
            lastStickyMatchTime = now
            onTargetLocked?.invoke(id, errX, errY, depthZ, yawDeg)
            return
        }

        // 2. 同级或低优先级时的常规黏性逻辑
        when {
            stickyTargetId == -1 -> {
                // 第一次锁定
                stickyTargetId = id
                lastStickyMatchTime = now
                onTargetLocked?.invoke(id, errX, errY, depthZ, yawDeg)
                Log.i(TAG, "🔒 首次锁定目标 ID:$id (优先级:$newPriority)")
            }
            id == stickyTargetId -> {
                // 仍是锁定的 ID,正常刷新
                lastStickyMatchTime = now
                onTargetLocked?.invoke(id, errX, errY, depthZ, yawDeg)
            }
            now - lastStickyMatchTime > STICKY_LOSE_TIMEOUT_MS -> {
                // 当前锁定的码丢失太久，允许降级或切换到视野内的其他码
                Log.w(TAG, "🔀 锁定 ID:$stickyTargetId 已丢 ${now - lastStickyMatchTime}ms → 降级切到 ID:$id")
                stickyTargetId = id
                lastStickyMatchTime = now
                onTargetLocked?.invoke(id, errX, errY, depthZ, yawDeg)
            }
            else -> {
                // 锁定 ID 还在保护期内，且新看到的码优先级不高于当前码，忽略该帧以防震荡
            }
        }
    }

    fun processFrame(data: ByteArray, offset: Int, length: Int, width: Int, height: Int) {
        try {
            if (grayMat == null || grayMat!!.cols() != width || grayMat!!.rows() != height) {
                grayMat?.release()
                grayMat = Mat(height, width, CvType.CV_8UC1)
                ensureCameraMatrix(width, height)
            }
            grayMat!!.put(0, 0, data, offset, width * height)

            releaseAndClear(corners); releaseAndClear(rejected)
            detector.detectMarkers(grayMat, corners, ids, rejected)
            if (ids.empty()) return

            val idArr = IntArray(ids.rows())
            ids.get(0, 0, idArr)
            val bestIdx = pickBestTagIndex(idArr, corners, width, height)
            if (bestIdx < 0) return

            val targetId = idArr[bestIdx]
            val tagSize = getTagPhysicalSize(targetId)

            if (abs(cachedObjSize - tagSize) > 1e-6) {
                val h = tagSize / 2.0
                cachedObjPoints?.release()
                cachedObjPoints = MatOfPoint3f(Point3(-h, h, 0.0), Point3(h, h, 0.0), Point3(h, -h, 0.0), Point3(-h, -h, 0.0))
                cachedObjSize = tagSize
            }

            val pts = FloatArray(8); corners[bestIdx].get(0, 0, pts)
            val imgPts2f = MatOfPoint2f(org.opencv.core.Point(pts[0].toDouble(), pts[1].toDouble()), org.opencv.core.Point(pts[2].toDouble(), pts[3].toDouble()), org.opencv.core.Point(pts[4].toDouble(), pts[5].toDouble()), org.opencv.core.Point(pts[6].toDouble(), pts[7].toDouble()))

            Calib3d.solvePnP(cachedObjPoints, imgPts2f, cameraMatrix, distCoeffs, rvec, tvec)
            Calib3d.Rodrigues(rvec, rmat)

            val (ox, oy) = getTagOffset(targetId)
            val padCenterInTag = Mat(3, 1, CvType.CV_64F).apply { put(0, 0, -ox); put(1, 0, -oy); put(2, 0, 0.0) }
            val padCenterInCam = Mat()
            Core.gemm(rmat, padCenterInTag, 1.0, tvec, 1.0, padCenterInCam)

            val tx = padCenterInCam.get(0, 0)[0]; val ty = padCenterInCam.get(1, 0)[0]; val tz = padCenterInCam.get(2, 0)[0]

            if (tz in MIN_VALID_DEPTH_M..MAX_VALID_DEPTH_M) {
                if (!filterInited || targetId != lastTargetId) {
                    fErrX = tx; fErrY = ty; fYaw = extractYawDeg(rmat); filterInited = true
                } else {
                    fErrX = FILTER_ALPHA * tx + (1 - FILTER_ALPHA) * fErrX
                    fErrY = FILTER_ALPHA * ty + (1 - FILTER_ALPHA) * fErrY
                    fYaw  = filterYaw(fYaw, extractYawDeg(rmat), FILTER_ALPHA)
                }
                lastTargetId = targetId

                // ★ 修改点：不再直接抛出结果，而是先经过黏性过滤
                applyStickyTargetFilter(targetId, fErrX, fErrY, tz, fYaw)
            }

            padCenterInTag.release(); padCenterInCam.release(); imgPts2f.release()
        } catch (e: Exception) { Log.e(TAG, "Process error", e) }
    }

    // ★ 新增：供外部调用，在每次中止降落/重新开始降落时，清空黏性状态
    fun resetTracking() {
        stickyTargetId = -1
        lastStickyMatchTime = 0L
        filterInited = false
        lastTargetId = -1
    }

    fun release() {
        grayMat?.release(); ids.release(); rvec.release(); tvec.release(); rmat.release()
        cameraMatrix?.release(); distCoeffs.release(); cachedObjPoints?.release()
        releaseAndClear(corners); releaseAndClear(rejected)
        resetTracking()
    }
}