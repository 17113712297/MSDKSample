package com.example.msdksample

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.Arrays

/**
 * 把 ICameraStreamManager 送来的 YUV420_888 帧压成 JPEG。
 *
 * MSDK V5 的视频帧按 I420 平面序给出：Y 全尺寸，随后 U、V 各 1/4 尺寸。
 * Android 的 [YuvImage] 只吃 NV21（Y + VU 交错），所以这里做一次重排：
 *   nv21[ySize + 2i]     = V[i]
 *   nv21[ySize + 2i + 1] = U[i]
 *
 * 若某机型/某镜头只给了 Y 平面（长度不足 1.5 倍像素数），退化成灰度图
 * （色度填 128），保证不会因为越界读出花屏。
 *
 * 注意：这里不做缩放，抓到的就是图传流分辨率。压缩质量 75 下
 * 1080p 单张约 150~350KB，适合在推流的同时上传。
 */
object YuvJpegEncoder {

    private const val TAG = "YuvJpeg"
    private const val DEFAULT_QUALITY = 75

    fun encodeI420ToJpeg(
        data: ByteArray,
        offset: Int,
        length: Int,
        width: Int,
        height: Int,
        quality: Int = DEFAULT_QUALITY
    ): ByteArray? {
        if (width <= 0 || height <= 0) {
            Log.w(TAG, "无效帧尺寸: ${width}x$height")
            return null
        }
        if (offset < 0 || length <= 0 || offset + length > data.size) {
            Log.w(TAG, "帧数据范围越界: offset=$offset length=$length size=${data.size}")
            return null
        }

        val ySize = width * height
        if (length < ySize) {
            Log.w(TAG, "亮度平面不完整: length=$length 需要 $ySize")
            return null
        }

        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val chromaSize = chromaWidth * chromaHeight
        val nv21Size = ySize + chromaSize * 2

        val nv21 = ByteArray(nv21Size)
        System.arraycopy(data, offset, nv21, 0, ySize)

        if (length >= ySize + chromaSize * 2) {
            val uStart = offset + ySize
            val vStart = uStart + chromaSize
            var target = ySize
            for (i in 0 until chromaSize) {
                nv21[target++] = data[vStart + i]
                nv21[target++] = data[uStart + i]
            }
        } else {
            Log.w(TAG, "帧只有亮度平面，按灰度图编码")
            Arrays.fill(nv21, ySize, nv21Size, 128.toByte())
        }

        return runCatching {
            val image = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            ByteArrayOutputStream().use { output ->
                image.compressToJpeg(Rect(0, 0, width, height), quality, output)
                output.toByteArray()
            }
        }.onFailure {
            Log.w(TAG, "JPEG 编码失败: ${it.message}")
        }.getOrNull()
    }
}
