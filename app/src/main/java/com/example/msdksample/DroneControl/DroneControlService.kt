package com.example.msdksample

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import dji.v5.manager.aircraft.payload.PayloadCenter
import dji.v5.manager.aircraft.payload.PayloadIndexType
import dji.v5.manager.aircraft.payload.listener.PayloadDataListener

class DroneControlService : Service() {

    companion object {
        private const val TAG = "DroneControlService"
        private const val CHANNEL_ID = "drone_ctrl_channel"
        private const val NOTIF_ID   = 1001
    }

    private val droneCtrl    = DroneController()
    private val payloadIndex = PayloadIndexType.UP

    private val payloadDataListener = PayloadDataListener { bytes ->
        if (bytes == null || bytes.isEmpty()) return@PayloadDataListener

        val frame = DroneCommProtocol.decode(bytes)
        if (!frame.valid) {
            Log.w(TAG, "帧校验失败，丢弃")
            return@PayloadDataListener
        }

        Log.i(TAG, "CMD=0x${frame.cmd.toUByte().toString(16).uppercase()}")
        dispatchFrame(frame)
    }

    // ── 生命周期 ──────────────────────────────────────────
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification())
        registerPayloadListener()
        Log.i(TAG, "Service 已启动，监听中")
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterPayloadListener()
        droneCtrl.release()
        Log.i(TAG, "Service 已停止")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── 帧分发 (与 DroneControlActivity 相同逻辑) ─────────
    private fun dispatchFrame(frame: DroneCommProtocol.ParsedFrame) {
        when (frame.cmd) {
            DroneCommProtocol.CMD_TAKEOFF -> droneCtrl.takeoff { ok, _ ->
                sendAck(DroneCommProtocol.CMD_TAKEOFF,
                    if (ok) DroneCommProtocol.ACK_OK else DroneCommProtocol.ACK_FAIL)
            }
            DroneCommProtocol.CMD_LAND -> droneCtrl.land { ok, _ ->
                sendAck(DroneCommProtocol.CMD_LAND,
                    if (ok) DroneCommProtocol.ACK_OK else DroneCommProtocol.ACK_FAIL)
            }
            DroneCommProtocol.CMD_HOVER -> droneCtrl.hover { _, _ ->
                sendAck(DroneCommProtocol.CMD_HOVER, DroneCommProtocol.ACK_OK)
            }
            DroneCommProtocol.CMD_VEL -> {
                val vel = DroneCommProtocol.parseVelPayload(frame.payload) ?: run {
                    sendAck(DroneCommProtocol.CMD_VEL, DroneCommProtocol.ACK_FAIL)
                    return
                }
                droneCtrl.sendVelocity(vel.vx, vel.vy, vel.vz, vel.yawRate) { ok, msg ->
                    if (msg.contains("enabled", ignoreCase = true)) {
                        sendAck(DroneCommProtocol.CMD_VEL, DroneCommProtocol.ACK_OK)
                    }
                }
            }
            else -> Log.w(TAG, "未知 CMD=0x${frame.cmd.toUByte().toString(16)}")
        }
    }

    // ── 低速通道工具 ──────────────────────────────────────
    private fun registerPayloadListener() {
        PayloadCenter.getInstance().payloadManager[payloadIndex]
            ?.addPayloadDataListener(payloadDataListener)
            ?: Log.e(TAG, "PayloadManager[$payloadIndex] 为空")
    }

    private fun unregisterPayloadListener() {
        PayloadCenter.getInstance().payloadManager[payloadIndex]
            ?.removePayloadDataListener(payloadDataListener)
    }

    private fun sendAck(ackedCmd: Byte, status: Byte) {
        val mgr = PayloadCenter.getInstance().payloadManager[payloadIndex] ?: return
        mgr.sendDataToPayload(
            DroneCommProtocol.encodeAck(ackedCmd, status),
            object : dji.v5.common.callback.CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    Log.i(TAG, "ACK sent: cmd=0x${ackedCmd.toUByte().toString(16)} status=$status")
                }
                override fun onFailure(error: dji.v5.common.error.IDJIError) {
                    Log.w(TAG, "ACK send failed: ${error.description()}")
                }
            }
        )
    }

    // ── 通知 ──────────────────────────────────────────────
    private fun createNotificationChannel() {
        val chan = NotificationChannel(
            CHANNEL_ID, "飞控监听",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("飞控监听中")
            .setContentText("正在接收 PSDK 指令")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
}
