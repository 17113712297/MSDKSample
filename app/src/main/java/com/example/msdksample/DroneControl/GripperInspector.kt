package com.example.msdksample

import android.util.Log
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Rect

class GripperInspector {

    companion object {
        const val TAG = "GripperInspector"

        // ★ 核心参数：上下半场亮度比例阈值
        // 如果 下半部分亮度 < 上半部分亮度 * 0.65，就说明下半部被异常遮挡了
        const val DARK_RATIO_THRESHOLD = 0.65
    }

    private var grayMat: Mat? = null
    private val meanTop = MatOfDouble()
    private val stdDevTop = MatOfDouble()
    private val meanBottom = MatOfDouble()
    private val stdDevBottom = MatOfDouble()

    fun inspect(data: ByteArray, offset: Int, width: Int, height: Int, isTargetLocked: Boolean): Boolean {
        // 1. 交叉验证优先
        if (isTargetLocked) {
            return false
        }

        if (grayMat == null || grayMat!!.cols() != width || grayMat!!.rows() != height) {
            grayMat?.release()
            grayMat = Mat(height, width, CvType.CV_8UC1)
        }

        val topRoi = Mat()
        val bottomRoi = Mat()

        return try {
            grayMat!!.put(0, 0, data, offset, width * height)

            // 2. 将画面劈成两半
            val topRect = Rect(0, 0, width, height / 2)
            val bottomRect = Rect(0, height / 2, width, height / 2)

            topRoi.apply { grayMat!!.submat(topRect).copyTo(this) }
            bottomRoi.apply { grayMat!!.submat(bottomRect).copyTo(this) }

            // 3. 分别计算上半部和下半部的平均亮度
            Core.meanStdDev(topRoi, meanTop, stdDevTop)
            Core.meanStdDev(bottomRoi, meanBottom, stdDevBottom)

            val topBrightness = meanTop.get(0, 0)[0]
            val bottomBrightness = meanBottom.get(0, 0)[0]

            // 打印日志，方便你在现场观察两者比例
            Log.d(TAG, "检测帧 -> 上半亮度:${"%.1f".format(topBrightness)}, 下半亮度:${"%.1f".format(bottomBrightness)} (锁定:$isTargetLocked)")

            // 4. 防除零保护与极限暗光保护
            // 如果连上半部分的天空/远景都黑得看不见 (比如小于20)，说明真的是黑夜，此时无法用比例判定
            if (topBrightness < 20.0) {
                return bottomBrightness < 15.0 // 极端暗光下退化为绝对值判定
            }

            // 5. 相对判定：下半部分亮度是否异乎寻常地低于上半部分？
            val currentRatio = bottomBrightness / topBrightness
            currentRatio < DARK_RATIO_THRESHOLD

        } catch (e: Exception) {
            Log.e(TAG, "夹爪检测算法异常", e)
            true // 发生异常时，保守判定为被遮挡
        } finally {
            topRoi.release()
            bottomRoi.release()
        }
    }

    fun release() {
        grayMat?.release()
        meanTop.release()
        stdDevTop.release()
        meanBottom.release()
        stdDevBottom.release()
        grayMat = null
    }
}