package com.example.msdksample.transfer

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 上传失败的媒体暂存区。
 *
 * 自动上传失败的视频/照片不再直接丢弃，而是把本地文件挪到 `filesDir/failed_uploads/`，
 * 并把当时的批次信息（detectTimeCur、siteId、deviceId、airlineKey）一起记下来。
 * 用户在工具面板点“补传失败媒体”时，按原批次前缀重新上传，
 * 前端看到的文件名与首次上传完全一致，不会串到新任务里。
 *
 * 只在本地保留有限份数/有限体积，超出按时间丢最旧的，避免把遥控器写满。
 */
class FailedUploadStore(context: Context) {

    companion object {
        private const val TAG = "FailedUpload"
        private const val DIR_NAME = "failed_uploads"
        private const val PREFS_NAME = "failed_upload_prefs"
        private const val PREF_KEY_INDEX = "index"

        const val TYPE_VIDEO = "video"
        const val TYPE_PHOTO = "photo"

        /** 最多保留多少个失败文件 */
        private const val MAX_ENTRIES = 12

        /** 最多占用多少字节，超出丢最旧的（视频往往上百 MB，必须设上限） */
        private const val MAX_TOTAL_BYTES = 2L * 1024L * 1024L * 1024L
    }

    data class Entry(
        val id: String,
        val detectTimeCur: String,
        val siteId: Int,
        val deviceId: Int,
        val airlineKey: String,
        /** 上传到前端时使用的文件名，补传必须沿用 */
        val uploadName: String,
        /** [TYPE_VIDEO] 或 [TYPE_PHOTO] */
        val type: String,
        /** 暂存目录里的实际文件名 */
        val localName: String,
        val sizeBytes: Long,
        val createdAtMs: Long,
        /** 最近一次失败原因，仅用于展示 */
        val error: String
    ) {
        fun command(): VideoUploadCommand =
            VideoUploadCommand(siteId, deviceId, airlineKey, detectTimeCur)
    }

    data class Summary(val count: Int, val bytes: Long)

    private val directory = File(context.applicationContext.filesDir, DIR_NAME).apply { mkdirs() }
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    /**
     * 把上传失败的文件收进暂存区。
     *
     * @param source 本地已下载好的文件（视频工作副本或待传照片）
     * @return true 表示文件已保留，可以在工具里补传
     */
    fun adopt(
        source: File,
        uploadName: String,
        command: VideoUploadCommand,
        type: String,
        error: String?
    ): Boolean {
        synchronized(lock) {
            if (!source.isFile || source.length() <= 0L) {
                Log.w(TAG, "跳过暂存不存在的文件: ${source.absolutePath}")
                return false
            }
            val localName = "${System.currentTimeMillis()}_${sanitize(uploadName)}"
            val target = File(directory, localName)
            val moved = runCatching {
                if (source.renameTo(target)) {
                    true
                } else {
                    source.copyTo(target, overwrite = true)
                    source.delete()
                    true
                }
            }.getOrElse { failure ->
                Log.w(TAG, "暂存上传失败文件出错: ${failure.message}", failure)
                false
            }
            if (!moved || !target.isFile) {
                Log.w(TAG, "无法暂存失败文件，按原逻辑丢弃: ${source.absolutePath}")
                return false
            }

            val entries = readEntries().toMutableList()
            // 同一批次同名文件重复失败时只留最新一份
            entries.removeAll { it.uploadName == uploadName && it.detectTimeCur == command.detectTimeCur }
            entries += Entry(
                id = "${System.currentTimeMillis()}-${entries.size}-$localName",
                detectTimeCur = command.detectTimeCur,
                siteId = command.siteId,
                deviceId = command.deviceId,
                airlineKey = command.airlineKey,
                uploadName = uploadName,
                type = type,
                localName = localName,
                sizeBytes = target.length(),
                createdAtMs = System.currentTimeMillis(),
                error = error.orEmpty()
            )
            writeEntries(trim(entries))
            Log.i(TAG, "已暂存失败媒体: $uploadName -> $localName (${target.length()} bytes), 待补传 ${entries.size} 个")
            return true
        }
    }

    /** 待补传清单，按失败时间升序（先失败的先补） */
    fun entries(): List<Entry> = synchronized(lock) {
        readEntries().sortedBy { it.createdAtMs }
    }

    fun summary(): Summary = synchronized(lock) {
        val entries = readEntries()
        Summary(entries.size, entries.sumOf { it.sizeBytes })
    }

    fun file(entry: Entry): File = File(directory, entry.localName)

    /** 补传成功后移除记录（文件由调用方删除） */
    fun remove(id: String) {
        synchronized(lock) {
            val entries = readEntries().toMutableList()
            val removed = entries.firstOrNull { it.id == id } ?: return
            entries.removeAll { it.id == id }
            writeEntries(entries)
            runCatching { file(removed).delete() }
            Log.i(TAG, "已移除已补传记录: ${removed.uploadName}")
        }
    }

    /** 补传再次失败时只更新原因，文件继续保留 */
    fun markFailed(id: String, error: String) {
        synchronized(lock) {
            val entries = readEntries().toMutableList()
            val index = entries.indexOfFirst { it.id == id }
            if (index < 0) return
            entries[index] = entries[index].copy(error = error)
            writeEntries(entries)
        }
    }

    /** 清空暂存区，返回删除的文件数 */
    fun clearAll(): Int {
        synchronized(lock) {
            val entries = readEntries()
            entries.forEach { entry -> runCatching { file(entry).delete() } }
            directory.listFiles()?.forEach { file ->
                if (file.isFile) runCatching { file.delete() }
            }
            writeEntries(emptyList())
            Log.i(TAG, "已清空失败媒体暂存区: ${entries.size} 个")
            return entries.size
        }
    }

    private fun trim(entries: List<Entry>): List<Entry> {
        val kept = entries.sortedBy { it.createdAtMs }.toMutableList()
        while (kept.size > MAX_ENTRIES) {
            dropOldest(kept, "超出份数上限")
        }
        var totalBytes = kept.sumOf { it.sizeBytes }
        while (totalBytes > MAX_TOTAL_BYTES && kept.size > 1) {
            val dropped = kept.removeAt(0)
            totalBytes -= dropped.sizeBytes
            runCatching { File(directory, dropped.localName).delete() }
            Log.w(TAG, "失败媒体占用超过上限，丢弃最旧的: ${dropped.uploadName}")
        }
        return kept
    }

    private fun dropOldest(kept: MutableList<Entry>, reason: String) {
        val dropped = kept.removeAt(0)
        runCatching { File(directory, dropped.localName).delete() }
        Log.w(TAG, "失败媒体$reason，丢弃最旧的: ${dropped.uploadName}")
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun readEntries(): List<Entry> {
        val raw = prefs.getString(PREF_KEY_INDEX, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val localName = obj.optString("localName")
                if (localName.isBlank()) return@mapNotNull null
                if (!File(directory, localName).isFile) return@mapNotNull null
                Entry(
                    id = obj.optString("id"),
                    detectTimeCur = obj.optString("detectTimeCur"),
                    siteId = obj.optInt("siteId"),
                    deviceId = obj.optInt("deviceId"),
                    airlineKey = obj.optString("airlineKey"),
                    uploadName = obj.optString("uploadName"),
                    type = obj.optString("type", TYPE_VIDEO),
                    localName = localName,
                    sizeBytes = obj.optLong("sizeBytes"),
                    createdAtMs = obj.optLong("createdAtMs"),
                    error = obj.optString("error")
                )
            }.filter { it.id.isNotBlank() && it.uploadName.isNotBlank() }
        }.getOrElse { failure ->
            Log.w(TAG, "读取失败媒体索引异常: ${failure.message}")
            emptyList()
        }
    }

    private fun writeEntries(entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject().apply {
                    put("id", entry.id)
                    put("detectTimeCur", entry.detectTimeCur)
                    put("siteId", entry.siteId)
                    put("deviceId", entry.deviceId)
                    put("airlineKey", entry.airlineKey)
                    put("uploadName", entry.uploadName)
                    put("type", entry.type)
                    put("localName", entry.localName)
                    put("sizeBytes", entry.sizeBytes)
                    put("createdAtMs", entry.createdAtMs)
                    put("error", entry.error)
                }
            )
        }
        prefs.edit().putString(PREF_KEY_INDEX, array.toString()).apply()
    }
}
