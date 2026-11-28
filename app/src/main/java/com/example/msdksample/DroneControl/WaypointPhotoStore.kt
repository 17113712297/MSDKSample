package com.example.msdksample

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * 待上传的航点抓拍照片。
 *
 * 拍照时刻还拿不到 1.1 指令里的 detectTimeCur，所以本地先按点位命名落盘；
 * 等收到 1.1 指令、开始上传时再按 `{detectTimeCur}-pic-000N.jpg` 改名上传。
 * 这样照片和视频共用同一个批次前缀，前端按前缀就能把它们归到同一次任务里。
 */
class WaypointPhotoStore(context: Context) {

    companion object {
        private const val TAG = "WaypointPhoto"
        private const val DIR_NAME = "waypoint_photos"

        /** 待上传照片上限，超出丢最旧的，避免长时间任务把遥控器存储写满 */
        private const val MAX_PENDING = 80
    }

    private val directory = File(context.applicationContext.filesDir, DIR_NAME).apply { mkdirs() }

    /** 生成一个新的落盘文件（尚未写入内容） */
    fun newPhotoFile(stopNumber: Int, wpIndex: Int): File {
        trimIfNeeded()
        val name = String.format(
            Locale.US,
            "wp%02d_%03d_%d.jpg",
            stopNumber.coerceIn(0, 99),
            wpIndex.coerceIn(0, 999),
            System.currentTimeMillis()
        )
        return File(directory, name)
    }

    /** 待上传照片，按拍摄时间升序（先到的点位先传） */
    fun pending(): List<File> {
        val files = directory.listFiles() ?: return emptyList()
        return files
            .filter { it.isFile && it.length() > 0L && it.name.endsWith(".jpg", ignoreCase = true) }
            .sortedBy { it.lastModified() }
    }

    fun delete(file: File) {
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "删除已上传照片失败: ${file.name}")
        }
    }

    fun clearPending() {
        pending().forEach { delete(it) }
    }

    private fun trimIfNeeded() {
        val files = pending()
        if (files.size < MAX_PENDING) return
        val overflow = files.size - MAX_PENDING + 1
        files.take(overflow).forEach {
            Log.w(TAG, "待上传照片超过上限，丢弃最旧的一张: ${it.name}")
            delete(it)
        }
    }
}
