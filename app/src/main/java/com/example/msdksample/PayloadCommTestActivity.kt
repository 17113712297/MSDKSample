package com.example.msdksample

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.aircraft.payload.PayloadCenter
import dji.v5.manager.aircraft.payload.PayloadIndexType
import dji.v5.manager.aircraft.payload.listener.PayloadDataListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * PSDK ↔ MSDK 低速数据通道测试界面。
 * - 固定使用 PayloadIndexType.UP（对应 M3T 的 E-Port）
 * - 自动监听 PSDK 发来的数据，显示在日志列表中
 * - 用户可输入文本通过"发送"按钮回发给 PSDK
 */
class PayloadCommTestActivity : AppCompatActivity() {

    companion object {
        const val TAG = "PayloadCommTest"
        const val MAX_SEND_LENGTH = 255   // MSDK 侧单次最大 255 字节
        const val MAX_LOG_LINES = 500
    }

    private lateinit var statusText: TextView
    private lateinit var edInput: EditText
    private lateinit var btnSend: Button
    private lateinit var btnClear: Button
    private lateinit var logListView: ListView

    private val logEntries = ArrayList<String>()
    private lateinit var logAdapter: ArrayAdapter<String>
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tsFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** 固定使用 UP 端口（M3T E-Port 在 MSDK 中映射为 UP） */
    private val payloadIndex: PayloadIndexType = PayloadIndexType.UP
    private var txCount = 0
    private var rxCount = 0

    private val payloadDataListener = PayloadDataListener { bytes ->
        val time = tsFmt.format(Date())
        val content = if (bytes.isNotEmpty()) String(bytes) else "<empty>"
        rxCount++
        val line = "[$time RX #$rxCount ${bytes.size}B] $content"
        Log.i(TAG, line)
        mainHandler.post { appendLog(line) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_payload_comm_test)

        statusText = findViewById(R.id.statusText)
        edInput = findViewById(R.id.edInput)
        btnSend = findViewById(R.id.btnSend)
        btnClear = findViewById(R.id.btnClear)
        logListView = findViewById(R.id.logListView)

        logAdapter = ArrayAdapter(this, R.layout.item_log, R.id.tvLog, logEntries)
        logListView.adapter = logAdapter

        btnSend.setOnClickListener { doSend() }
        btnClear.setOnClickListener {
            logEntries.clear()
            logAdapter.notifyDataSetChanged()
            txCount = 0
            rxCount = 0
        }

        edInput.setOnEditorActionListener { v, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(v.windowToken, 0)
                doSend()
                true
            } else {
                false
            }
        }

        // 默认填一段，方便快速点发送
        edInput.setText("Hello PSDK from MSDK!")

        registerListener()
    }

    /** 在固定的 UP 端口上注册监听器 */
    private fun registerListener() {
        val map = PayloadCenter.getInstance().payloadManager

        appendLog("[DIAG] All entries in payloadManager map:")
        map.forEach { (key, value) ->
            appendLog("       - $key : ${if (value != null) "manager OK" else "null"}")
        }

        val mgr = map[payloadIndex]
        if (mgr == null) {
            statusText.text = "状态：$payloadIndex 对应的 manager 为空，飞机未连接"
            appendLog("[ERROR] PayloadManager[$payloadIndex] is null")
            btnSend.isEnabled = false
            return
        }

        mgr.addPayloadDataListener(payloadDataListener)
        statusText.text = "状态：监听中 → $payloadIndex"
        appendLog("[INFO] Listener attached on $payloadIndex")
        Log.i(TAG, "Using PayloadManager: $payloadIndex, manager=$mgr")
    }

    private fun doSend() {
        val text = edInput.text.toString()
        if (text.isEmpty()) {
            Toast.makeText(this, "请输入内容", Toast.LENGTH_SHORT).show()
            return
        }

        val bytes = text.toByteArray()
        if (bytes.size > MAX_SEND_LENGTH) {
            Toast.makeText(this, "超出最大长度 $MAX_SEND_LENGTH 字节", Toast.LENGTH_SHORT).show()
            return
        }

        val mgr = PayloadCenter.getInstance().payloadManager[payloadIndex]
        if (mgr == null) {
            Toast.makeText(this, "PayloadManager 不可用，请稍候再试", Toast.LENGTH_SHORT).show()
            appendLog("[ERROR] PayloadManager[$payloadIndex] is null when sending")
            return
        }

        mgr.sendDataToPayload(bytes, object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                val time = tsFmt.format(Date())
                txCount++
                val line = "[$time TX #$txCount ${bytes.size}B] $text"
                Log.i(TAG, line)
                mainHandler.post { appendLog(line) }
            }

            override fun onFailure(error: IDJIError) {
                val time = tsFmt.format(Date())
                val desc = error.description() ?: "code=${error.errorCode()}"
                val line = "[$time TX FAILED] $desc"
                Log.e(TAG, line)
                mainHandler.post { appendLog(line) }
            }
        })
    }

    private fun appendLog(line: String) {
        if (logEntries.size >= MAX_LOG_LINES) {
            val keep = logEntries.takeLast(MAX_LOG_LINES / 2)
            logEntries.clear()
            logEntries.addAll(keep)
        }
        logEntries.add(line)
        logAdapter.notifyDataSetChanged()
        logListView.setSelection(logEntries.size - 1)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            PayloadCenter.getInstance().payloadManager[payloadIndex]
                ?.removePayloadDataListener(payloadDataListener)
        } catch (e: Exception) {
            Log.w(TAG, "removePayloadDataListener error: ${e.message}")
        }
    }
}

