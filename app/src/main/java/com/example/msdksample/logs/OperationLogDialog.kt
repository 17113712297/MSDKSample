package com.example.msdksample.logs

import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.msdksample.R

/** 使用当前 Activity 内的对话框，查看日志不触发主飞行页面 onPause。 */
class OperationLogDialog(private val activity: AppCompatActivity) {
    private val root = activity.layoutInflater.inflate(R.layout.dialog_operation_logs, null)
    private val dialog = AlertDialog.Builder(activity).setView(root).create()
    private val filesSpinner = root.findViewById<Spinner>(R.id.spLogsFiles)
    private val content = root.findViewById<TextView>(R.id.tvLogsContent)
    private val info = root.findViewById<TextView>(R.id.tvLogsInfo)
    private val scroll = root.findViewById<ScrollView>(R.id.scrollLogsContent)
    private val refresh = root.findViewById<Button>(R.id.btnLogsRefresh)
    private val delete = root.findViewById<Button>(R.id.btnLogsDelete)
    private val clear = root.findViewById<Button>(R.id.btnLogsClear)
    private val older = root.findViewById<Button>(R.id.btnLogsOlder)
    private val newer = root.findViewById<Button>(R.id.btnLogsNewer)
    private var files = emptyList<OperationLogStore.LogFile>()
    private var selectedName: String? = null
    private var page: OperationLogStore.Page? = null
    private var requestId = 0L
    private var busy = false
    private var confirmation: AlertDialog? = null

    init {
        refresh.setOnClickListener { OperationLogger.operation("刷新操作日志"); loadFiles(selectedName) }
        delete.setOnClickListener { confirmDelete() }
        clear.setOnClickListener { confirmClear() }
        older.setOnClickListener { page?.let { OperationLogger.operation("日志更早一页"); loadPage(it.index - 1) } }
        newer.setOnClickListener { page?.let { OperationLogger.operation("日志更新一页"); loadPage(it.index + 1) } }
        root.findViewById<Button>(R.id.btnLogsClose).setOnClickListener { dialog.dismiss() }
        filesSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val next = files.getOrNull(position)?.name ?: return
                if (!busy && next != selectedName) {
                    selectedName = next
                    OperationLogger.operation("选择日志", next)
                    loadPage(null)
                }
            }
        }
        dialog.setOnDismissListener {
            requestId++
            confirmation?.dismiss()
            OperationLogger.operation("关闭操作日志")
        }
    }

    fun show() {
        if (dialog.isShowing) return
        dialog.show()
        val metrics = activity.resources.displayMetrics
        dialog.window?.setLayout((metrics.widthPixels * 0.94).toInt(), (metrics.heightPixels * 0.90).toInt())
        loadFiles()
    }

    fun dismiss() { dialog.dismiss() }

    private fun setBusy(value: Boolean) {
        busy = value
        refresh.isEnabled = !value
        filesSpinner.isEnabled = !value && files.isNotEmpty()
        delete.isEnabled = !value && selectedName != null
        clear.isEnabled = !value && files.isNotEmpty()
        older.isEnabled = !value && (page?.index ?: 0) > 0
        newer.isEnabled = !value && page?.let { it.index + 1 < it.pageCount } == true
    }

    private fun loadFiles(preferredName: String? = null) {
        val request = ++requestId
        setBusy(true)
        info.text = "正在读取日志…"
        OperationLogger.listFiles { result ->
            if (!dialog.isShowing || request != requestId) return@listFiles
            result.onSuccess { entries ->
                files = entries
                val selected = entries.indexOfFirst { it.name == preferredName }.coerceAtLeast(0)
                selectedName = entries.getOrNull(selected)?.name
                filesSpinner.adapter = object : ArrayAdapter<String>(activity, android.R.layout.simple_spinner_item, entries.map { it.label }) {
                    init { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                        super.getView(position, convertView, parent).also { (it as TextView).setTextColor(Color.WHITE) }
                }
                filesSpinner.setSelection(selected)
                if (selectedName == null) {
                    page = null
                    content.text = "暂无操作日志。后续按钮操作和关键结果将自动保存。"
                    info.text = OperationLogger.lastWriteError?.let { "最近保存失败：$it" } ?: "没有已保存的操作日志"
                    setBusy(false)
                } else loadPage(null)
            }.onFailure { showFailure(it) }
        }
    }

    private fun loadPage(index: Int?) {
        val name = selectedName ?: return
        val request = ++requestId
        setBusy(true)
        OperationLogger.readPage(name, index) { result ->
            if (!dialog.isShowing || request != requestId) return@readPage
            result.onSuccess { next ->
                page = next
                content.text = next.lines.joinToString("\n").ifEmpty { "这份日志暂无记录" }
                info.text = "第 ${next.index + 1} / ${next.pageCount} 页 · 共 ${next.totalLines} 条 · 显示 ${next.lines.size} 条" +
                    (OperationLogger.lastWriteError?.let { " · 保存失败：$it" } ?: "")
                scroll.post { if (request == requestId) scroll.scrollTo(0, 0) }
                setBusy(false)
            }.onFailure { showFailure(it) }
        }
    }

    private fun confirmDelete() {
        val name = selectedName ?: return
        OperationLogger.operation("删除本份日志", name)
        confirmation = AlertDialog.Builder(activity).setTitle("删除这份日志？")
            .setMessage("只删除当前选择的日志，其他日期和分卷不受影响。删除后无法恢复。")
            .setNegativeButton("取消") { _, _ -> OperationLogger.operation("取消删除日志") }
            .setPositiveButton("删除") { _, _ ->
                OperationLogger.operation("确认删除日志", name)
                requestId++
                setBusy(true)
                OperationLogger.delete(name) { result ->
                    if (!dialog.isShowing) return@delete
                    result.onSuccess { loadFiles() }.onFailure { showFailure(it) }
                }
            }.show()
    }

    private fun confirmClear() {
        OperationLogger.operation("清空全部操作日志")
        confirmation = AlertDialog.Builder(activity).setTitle("清空全部操作日志？")
            .setMessage("将删除所有已保存的操作日志。后续操作仍会自动记录，删除后无法恢复。")
            .setNegativeButton("取消") { _, _ -> OperationLogger.operation("取消清空日志") }
            .setPositiveButton("清空") { _, _ ->
                OperationLogger.operation("确认清空全部操作日志") // 该记录也会一起删除，不立即重建空日志。
                requestId++
                setBusy(true)
                OperationLogger.clear { result ->
                    if (!dialog.isShowing) return@clear
                    result.onSuccess { loadFiles() }.onFailure { showFailure(it) }
                }
            }.show()
    }

    private fun showFailure(error: Throwable) {
        info.text = "日志操作失败：${error.message ?: "未知错误"}；请点击刷新重试"
        setBusy(false)
        Toast.makeText(activity, info.text, Toast.LENGTH_LONG).show()
    }
}
