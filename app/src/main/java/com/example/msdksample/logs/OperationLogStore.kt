package com.example.msdksample.logs

import java.io.File
import java.io.FileNotFoundException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** 纯文件存储，所有操作可串行调用；只管理本目录内符合名称规则的操作日志。 */
class OperationLogStore(
    private val directory: File,
    private val maxFileBytes: Long = 512 * 1024L,
    private val maxFiles: Int = 40,
    timeZone: TimeZone = TimeZone.getDefault()
) {
    data class LogFile(val name: String, val sizeBytes: Long) {
        val label: String get() = name.removePrefix("operations-").removeSuffix(".log") +
            "  ·  ${((sizeBytes + 1023) / 1024).coerceAtLeast(1)} KB"
    }
    data class Page(val fileName: String, val index: Int, val pageCount: Int,
                    val totalLines: Int, val lines: List<String>)

    companion object { const val PAGE_SIZE = 200 }
    private val pattern = Regex("operations-(\\d{4}-\\d{2}-\\d{2})-(\\d{5})\\.log")
    private val dayFormat = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { this.timeZone = timeZone }
    private val timeFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.ROOT).apply { this.timeZone = timeZone }
    private var activeFile: File? = null
    private var activeDay: String? = null

    init { require(maxFileBytes > 0 && maxFiles > 0) }

    @Synchronized fun append(timestamp: Long, category: String, source: String, message: String) {
        if (!directory.isDirectory && !directory.mkdirs()) error("无法创建操作日志目录")
        val day = dayFormat.format(Date(timestamp))
        val line = "${timeFormat.format(Date(timestamp))} [${clean(category, 24)}] [${clean(source, 40)}] ${clean(message, 1600)}\n"
        val bytes = line.toByteArray(Charsets.UTF_8)
        var file = activeFile?.takeIf { activeDay == day && it.isFile && it.length() + bytes.size <= maxFileBytes }
        var created = false
        if (file == null) {
            val existing = ownedFiles().filter { it.name.startsWith("operations-$day-") }.maxByOrNull { it.name }
            file = existing?.takeIf { it.length() + bytes.size <= maxFileBytes }
            if (file == null) {
                val sequence = existing?.let { pattern.matchEntire(it.name)!!.groupValues[2].toInt() + 1 } ?: 0
                check(sequence <= 99999) { "当日操作日志数量超过上限" }
                file = checkedFile("operations-$day-${sequence.toString().padStart(5, '0')}.log")
                created = true
            }
            activeFile = file
            activeDay = day
        }
        file.appendBytes(bytes) // 每条写完即关闭，不在内存中长时间积攒。
        if (created) {
            val files = ownedFiles().sortedBy { it.name }
            files.filter { it != file }.take((files.size - maxFiles).coerceAtLeast(0)).forEach {
                check(it.delete()) { "无法清理最早的操作日志" }
            }
        }
    }

    @Synchronized fun listFiles(): List<LogFile> = ownedFiles().sortedByDescending { it.name }
        .map { LogFile(it.name, it.length()) }

    @Synchronized fun readPage(name: String, requestedPage: Int? = null): Page {
        val file = checkedFile(name)
        if (!file.isFile) throw FileNotFoundException("这份日志已删除或轮换，请刷新列表")
        val lines = file.readLines(Charsets.UTF_8)
        val count = ((lines.size + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)
        val page = (requestedPage ?: count - 1).coerceIn(0, count - 1)
        return Page(name, page, count, lines.size, lines.drop(page * PAGE_SIZE).take(PAGE_SIZE))
    }

    @Synchronized fun delete(name: String): Boolean {
        val file = checkedFile(name)
        if (!file.exists()) return false
        check(file.delete()) { "删除日志失败" }
        if (activeFile == file) { activeFile = null; activeDay = null }
        return true
    }

    @Synchronized fun clear(): Int {
        var removed = 0
        ownedFiles().forEach { check(it.delete()) { "部分日志未能删除" }; removed++ }
        activeFile = null; activeDay = null
        return removed
    }

    private fun ownedFiles(): List<File> = directory.listFiles()?.filter {
        it.isFile && pattern.matches(it.name) && it.canonicalFile.parentFile == directory.canonicalFile
    }.orEmpty()

    private fun checkedFile(name: String): File {
        require(pattern.matches(name)) { "无效的操作日志名称" }
        return File(directory, name).also {
            require(it.canonicalFile.parentFile == directory.canonicalFile) { "日志文件不在操作日志目录中" }
        }
    }

    private fun clean(text: String, limit: Int): String = text.take(limit)
        .replace("\r", "\\r").replace("\n", "\\n").replace("\t", " ").replace('\u0000', ' ')
}
