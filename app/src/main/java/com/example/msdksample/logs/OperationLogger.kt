package com.example.msdksample.logs

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

/** 磁盘读写统一走独立线程，操作记录不阻塞按钮、相机或飞控线程。 */
object OperationLogger {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "operation-logs").apply { isDaemon = true } }
    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var store: OperationLogStore? = null
    @Volatile var lastWriteError: String? = null
        private set

    @Synchronized fun initialize(context: Context) {
        if (store != null) return
        store = OperationLogStore(File(context.applicationContext.filesDir, "operation_logs"))
        state("应用", "应用进程启动，操作日志自动保存")
    }

    fun record(category: String, source: String, message: String) {
        val target = store ?: return
        val time = System.currentTimeMillis()
        // 入队前截断，防止异常大消息占用内存。写入失败不影响原业务动作。
        val bounded = message.take(1600)
        executor.execute {
            runCatching { target.append(time, category, source, bounded) }
                .onSuccess { lastWriteError = null }
                .onFailure { lastWriteError = it.message ?: "写入失败"; Log.e("OperationLogger", "操作日志保存失败", it) }
        }
    }

    fun operation(action: String, details: String = "") =
        record("操作", "界面", "点击：$action" + if (details.isBlank()) "" else "；$details")
    fun state(source: String, message: String) = record("状态", source, message)
    fun result(source: String, success: Boolean, message: String) = record(if (success) "结果" else "失败", source, message)
    fun error(source: String, message: String) = record("错误", source, message)

    fun listFiles(callback: (Result<List<OperationLogStore.LogFile>>) -> Unit) = read(callback) { it.listFiles() }
    fun readPage(name: String, page: Int?, callback: (Result<OperationLogStore.Page>) -> Unit) =
        read(callback) { it.readPage(name, page) }
    fun delete(name: String, callback: (Result<Boolean>) -> Unit) = read(callback) { it.delete(name) }
    fun clear(callback: (Result<Int>) -> Unit) = read(callback) { it.clear() }

    private fun <T> read(callback: (Result<T>) -> Unit, task: (OperationLogStore) -> T) {
        executor.execute {
            val result = runCatching { task(checkNotNull(store) { "操作日志尚未初始化" }) }
            mainHandler.post { callback(result) }
        }
    }
}
