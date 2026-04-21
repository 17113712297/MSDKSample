package com.example.msdksample

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import dji.v5.manager.aircraft.payload.PayloadCenter
import dji.v5.manager.aircraft.payload.PayloadIndexType
import dji.v5.manager.aircraft.payload.listener.PayloadDataListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * DroneControlActivity
 *
 * 低速通道接收入口：收到 PSDK 帧 → DroneCommProtocol 解析 → DroneController 执行 → ACK 回发
 *
 * UI 与 PayloadCommTestActivity 保持相同风格：
 *   - 顶部状态栏 (statusText)：显示 VirtualStick 状态
 *   - 日志列表   (logListView)：实时滚动日志
 *   - 手动测试按钮：TAKEOFF / LAND / HOVER
 *
 * AndroidManifest.xml 中需新增：
 *   <activity android:name=".DroneControlActivity" />
 * 并在 MainActivity 里加跳转入口或直接设为 launcher Activity。
 */
class DroneControlActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DroneControl"
        private const val MAX_LOG_LINES = 500
    }

    // ── UI 控件 ───────────────────────────────────────────
    private lateinit var statusText:  TextView
    private lateinit var logListView: ListView
    private lateinit var btnTakeoff:  Button
    private lateinit var btnLand:     Button
    private lateinit var btnHover:    Button
    private lateinit var btnClear:    Button

    // ── 日志适配器 ────────────────────────────────────────
    private val logEntries  = ArrayList<String>()
    private lateinit var logAdapter: ArrayAdapter<String>
    private val mainHandler = Handler(Looper.getMainLooper())
    private val tsFmt       = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    // ── 核心对象 ──────────────────────────────────────────
    private val droneCtrl = DroneController()

    /** 固定使用 UP 端口 (M3T E-Port → MSDK 中映射为 UP) */
    private val payloadIndex = PayloadIndexType.UP

    // ── 统计 ──────────────────────────────────────────────
    private var rxCount  = 0
    private var ackCount = 0

    // ─────────────────────────────────────────────────────
    //  PSDK 低速通道监听器
    // ─────────────────────────────────────────────────────

    private val payloadDataListener = PayloadDataListener { bytes ->
        if (bytes == null || bytes.isEmpty()) return@PayloadDataListener

        rxCount++
        val time = tsFmt.format(Date())
        appendLog("[$time RX #$rxCount ${bytes.size}B] raw=${bytes.toHex()}")

        // 解码
        val frame = DroneCommProtocol.decode(bytes)
        if (!frame.valid) {
            appendLog("  ↳ [WARN] 帧校验失败，丢弃")
            return@PayloadDataListener
        }

        appendLog("  ↳ CMD=0x${frame.cmd.toUByte().toString(16).uppercase()} " +
                  "payloadLen=${frame.payload.size}")

        // 分发
        dispatchFrame(frame)
    }

    // ─────────────────────────────────────────────────────
    //  生命周期
    // ─────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_drone_control)

        statusText  = findViewById(R.id.droneStatusText)
        logListView = findViewById(R.id.droneLogListView)
        btnTakeoff  = findViewById(R.id.btnTakeoff)
        btnLand     = findViewById(R.id.btnLand)
        btnHover    = findViewById(R.id.btnHover)
        btnClear    = findViewById(R.id.btnDroneClear)

        logAdapter = ArrayAdapter(this, R.layout.item_log, R.id.tvLog, logEntries)
        logListView.adapter = logAdapter

        // 手动测试按钮
        btnTakeoff.setOnClickListener { droneCtrl.takeoff { ok, msg -> appendLog("[手动] TAKEOFF → $msg") } }
        btnLand.setOnClickListener    { droneCtrl.land   { ok, msg -> appendLog("[手动] LAND   → $msg") } }
        btnHover.setOnClickListener   { droneCtrl.hover  { ok, msg -> appendLog("[手动] HOVER  → $msg") } }
        btnClear.setOnClickListener   { logEntries.clear(); logAdapter.notifyDataSetChanged(); rxCount = 0; ackCount = 0 }

        // DroneController 日志回调 → 同步到 UI
        droneCtrl.logCallback = { msg -> appendLog("[DroneCtrl] $msg") }

        registerPayloadListener()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterPayloadListener()
        droneCtrl.release()
    }

    // ─────────────────────────────────────────────────────
    //  帧分发
    // ─────────────────────────────────────────────────────

    private fun dispatchFrame(frame: DroneCommProtocol.ParsedFrame) {
        when (frame.cmd) {
            DroneCommProtocol.CMD_TAKEOFF -> {
                appendLog("  ↳ 执行 TAKEOFF")
                droneCtrl.takeoff { ok, msg ->
                    val status = if (ok) DroneCommProtocol.ACK_OK else DroneCommProtocol.ACK_FAIL
                    sendAck(DroneCommProtocol.CMD_TAKEOFF, status)
                    appendLog("  ↳ TAKEOFF 结果: $msg")
                    updateStatus("TAKEOFF ${if (ok) "OK" else "FAIL"}")
                }
            }

            DroneCommProtocol.CMD_LAND -> {
                appendLog("  ↳ 执行 LAND")
                droneCtrl.land { ok, msg ->
                    val status = if (ok) DroneCommProtocol.ACK_OK else DroneCommProtocol.ACK_FAIL
                    sendAck(DroneCommProtocol.CMD_LAND, status)
                    appendLog("  ↳ LAND 结果: $msg")
                    updateStatus("LAND ${if (ok) "OK" else "FAIL"}")
                }
            }

            DroneCommProtocol.CMD_HOVER -> {
                appendLog("  ↳ 执行 HOVER")
                droneCtrl.hover { ok, msg ->
                    sendAck(DroneCommProtocol.CMD_HOVER, DroneCommProtocol.ACK_OK)
                    appendLog("  ↳ HOVER 结果: $msg")
                    updateStatus("HOVERING")
                }
            }

            DroneCommProtocol.CMD_VEL -> {
                val vel = DroneCommProtocol.parseVelPayload(frame.payload)
                if (vel == null) {
                    appendLog("  ↳ [ERROR] VEL 载荷解析失败 (len=${frame.payload.size})")
                    sendAck(DroneCommProtocol.CMD_VEL, DroneCommProtocol.ACK_FAIL)
                    return
                }
                appendLog("  ↳ VEL vx=%.2f vy=%.2f vz=%.2f yaw=%.1f".format(
                    vel.vx, vel.vy, vel.vz, vel.yawRate))
                droneCtrl.sendVelocity(vel.vx, vel.vy, vel.vz, vel.yawRate) { ok, msg ->
                    // VEL_CMD 高频指令，只在首次开启 VirtualStick 时回 ACK
                    if (msg.contains("enabled", ignoreCase = true)) {
                        sendAck(DroneCommProtocol.CMD_VEL, DroneCommProtocol.ACK_OK)
                    }
                    updateStatus("VEL %.1f/%.1f/%.1f yaw%.0f".format(
                        vel.vx, vel.vy, vel.vz, vel.yawRate))
                }
            }

            else -> appendLog("  ↳ [WARN] 未知 CMD=0x${frame.cmd.toUByte().toString(16)}")
        }
    }

    // ─────────────────────────────────────────────────────
    //  低速通道工具
    // ─────────────────────────────────────────────────────

    private fun registerPayloadListener() {
        val mgr = PayloadCenter.getInstance().payloadManager[payloadIndex]
        if (mgr == null) {
            appendLog("[ERROR] PayloadManager[$payloadIndex] 为空，请确认飞机已连接")
            statusText.text = "状态：未连接 ($payloadIndex)"
            return
        }
        mgr.addPayloadDataListener(payloadDataListener)
        statusText.text = "状态：监听中 → $payloadIndex"
        appendLog("[INFO] 监听器已注册 on $payloadIndex")
    }

    private fun unregisterPayloadListener() {
        PayloadCenter.getInstance().payloadManager[payloadIndex]
            ?.removePayloadDataListener(payloadDataListener)
    }

    /** 向 PSDK 发送 ACK 帧 */
    private fun sendAck(ackedCmd: Byte, status: Byte) {
        val mgr = PayloadCenter.getInstance().payloadManager[payloadIndex] ?: return
        val ackBytes = DroneCommProtocol.encodeAck(ackedCmd, status)
        ackCount++
        mgr.sendDataToPayload(ackBytes, object :
            dji.v5.common.callback.CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                Log.i(TAG, "ACK #$ackCount sent: cmd=0x${ackedCmd.toUByte().toString(16)} status=$status")
            }
            override fun onFailure(error: dji.v5.common.error.IDJIError) {
                Log.w(TAG, "ACK send failed: ${error.description()}")
            }
        })
    }

    // ─────────────────────────────────────────────────────
    //  UI 工具
    // ─────────────────────────────────────────────────────

    private fun appendLog(msg: String) {
        mainHandler.post {
            if (logEntries.size >= MAX_LOG_LINES) logEntries.removeAt(0)
            logEntries.add(msg)
            logAdapter.notifyDataSetChanged()
            logListView.setSelection(logEntries.size - 1)
        }
    }

    private fun updateStatus(msg: String) {
        mainHandler.post { statusText.text = "状态：$msg" }
    }

    // ByteArray → 十六进制字符串，调试用
    private fun ByteArray.toHex(): String =
        joinToString(" ") { "%02X".format(it) }
}
