package com.example.msdksample

import android.app.*
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import dji.v5.manager.aircraft.payload.PayloadCenter
import dji.v5.manager.aircraft.payload.PayloadIndexType
import dji.v5.manager.aircraft.payload.listener.PayloadDataListener

/**
 * DroneControlService
 *
 * 后台前台服务：常驻监听 PSDK 低速通道，分发飞控 / 云台 / 相机指令。
 *
 * 帧分发逻辑与 DroneControlActivity.dispatchFrame() 保持一致。
 */
class DroneControlService : Service() {

    companion object {
        private const val TAG = "DroneControlService"
        private const val CHANNEL_ID = "drone_ctrl_channel"
        private const val NOTIF_ID   = 1001
    }

    private val droneCtrl    = DroneController()
    private val gimbalCtrl   = GimbalController()
    private val cameraCtrl   = CameraController()
    private val auxLightCtrl = AuxLightController()
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

    // ── 帧分发 ────────────────────────────────────────────
    private fun dispatchFrame(frame: DroneCommProtocol.ParsedFrame) {
        when (frame.cmd) {

            // ── 飞控 ────────────────────────────────────────
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
                droneCtrl.sendVelocity(vel.vx, vel.vy, vel.vz, vel.yawRate) { _, msg ->
                    if (msg.contains("enabled", ignoreCase = true)) {
                        sendAck(DroneCommProtocol.CMD_VEL, DroneCommProtocol.ACK_OK)
                    }
                }
            }

            // ── 云台 ────────────────────────────────────────
            DroneCommProtocol.CMD_GIMBAL_YAW_FOLLOW -> {
                val p = DroneCommProtocol.parseGimbalYawFollowPayload(frame.payload) ?: run {
                    Log.e(TAG, "GIMBAL_YAW_FOLLOW 载荷解析失败 (len=${frame.payload.size})")
                    sendAck(DroneCommProtocol.CMD_GIMBAL_YAW_FOLLOW,
                            DroneCommProtocol.ACK_FAIL)
                    return
                }
                Log.i(TAG, "GIMBAL_YAW_FOLLOW pitch=${p.pitch} roll=${p.roll}")
                gimbalCtrl.setYawFollow(p.pitch, p.roll) { ok, _ ->
                    sendAck(DroneCommProtocol.CMD_GIMBAL_YAW_FOLLOW,
                        if (ok) DroneCommProtocol.ACK_OK else DroneCommProtocol.ACK_FAIL)
                }
            }

            DroneCommProtocol.CMD_GIMBAL_ANGLE -> {
                val p = DroneCommProtocol.parseGimbalAnglePayload(frame.payload) ?: run {
                    Log.e(TAG, "GIMBAL_ANGLE 载荷解析失败 (len=${frame.payload.size})")
                    sendAck(DroneCommProtocol.CMD_GIMBAL_ANGLE,
                            DroneCommProtocol.ACK_FAIL)
                    return
                }
                val modeStr = if (p.isRelative) "REL" else "ABS"
                Log.i(TAG, "GIMBAL_ANGLE[$modeStr] pitch=${p.pitch} roll=${p.roll} " +
                           "yaw=${p.yaw} dur=${p.duration}s")
                gimbalCtrl.rotateByAngle(p.mode, p.pitch, p.roll, p.yaw, p.duration) { ok, _ ->
                    sendAck(DroneCommProtocol.CMD_GIMBAL_ANGLE,
                        if (ok) DroneCommProtocol.ACK_OK else DroneCommProtocol.ACK_FAIL)
                }
            }

            // ── 相机 ────────────────────────────────────────
            DroneCommProtocol.CMD_CAM_MODE -> {
                val p = DroneCommProtocol.parseCamModePayload(frame.payload) ?: run {
                    Log.e(TAG, "CAM_MODE 载荷解析失败")
                    sendAck(DroneCommProtocol.CMD_CAM_MODE, DroneCommProtocol.ACK_FAIL)
                    return
                }
                Log.i(TAG, "CAM_MODE ${if (p.isPhoto) "PHOTO" else "VIDEO"}")
                cameraCtrl.setMode(p.isPhoto) { ok, _ ->
                    sendAck(DroneCommProtocol.CMD_CAM_MODE,
                        if (ok) DroneCommProtocol.ACK_OK else DroneCommProtocol.ACK_FAIL)
                }
            }

            DroneCommProtocol.CMD_CAM_SHOOT -> {
                Log.i(TAG, "CAM_SHOOT")
                cameraCtrl.shootPhoto { ok, _ ->
                    sendAck(DroneCommProtocol.CMD_CAM_SHOOT,
                        if (ok) DroneCommProtocol.ACK_OK else DroneCommProtocol.ACK_FAIL)
                }
            }

            DroneCommProtocol.CMD_CAM_RECORD -> {
                val p = DroneCommProtocol.parseCamRecordPayload(frame.payload) ?: run {
                    Log.e(TAG, "CAM_RECORD 载荷解析失败")
                    sendAck(DroneCommProtocol.CMD_CAM_RECORD, DroneCommProtocol.ACK_FAIL)
                    return
                }
                Log.i(TAG, "CAM_RECORD ${if (p.isStart) "START" else "STOP"}")
                val callback: (Boolean, String) -> Unit = { ok, _ ->
                    sendAck(DroneCommProtocol.CMD_CAM_RECORD,
                        if (ok) DroneCommProtocol.ACK_OK else DroneCommProtocol.ACK_FAIL)
                }
                if (p.isStart) cameraCtrl.startRecord(callback)
                else           cameraCtrl.stopRecord(callback)
            }

            DroneCommProtocol.CMD_CAM_VIDEO_CFG -> {
                val p = DroneCommProtocol.parseCamVideoCfgPayload(frame.payload) ?: run {
                    Log.e(TAG, "CAM_VIDEO_CFG 载荷解析失败")
                    sendAck(DroneCommProtocol.CMD_CAM_VIDEO_CFG, DroneCommProtocol.ACK_FAIL)
                    return
                }
                Log.i(TAG, "CAM_VIDEO_CFG res=0x${p.resolution.toUByte().toString(16)} " +
                           "fps=0x${p.frameRate.toUByte().toString(16)}")
                cameraCtrl.setVideoCfg(p.resolution, p.frameRate) { ok, _ ->
                    sendAck(DroneCommProtocol.CMD_CAM_VIDEO_CFG,
                        if (ok) DroneCommProtocol.ACK_OK else DroneCommProtocol.ACK_FAIL)
                }
            }

            DroneCommProtocol.CMD_CAM_ZOOM -> {
                val p = DroneCommProtocol.parseCamZoomPayload(frame.payload) ?: run {
                    Log.e(TAG, "CAM_ZOOM 载荷解析失败")
                    sendAck(DroneCommProtocol.CMD_CAM_ZOOM, DroneCommProtocol.ACK_FAIL)
                    return
                }
                Log.i(TAG, "CAM_ZOOM lens=0x${p.lens.toUByte().toString(16)} " +
                           "ratio=${if (p.shouldSetRatio) p.ratio else "keep"}")
                cameraCtrl.setLensAndZoom(p.lens, p.shouldSetRatio, p.ratio) { ok, _ ->
                    sendAck(DroneCommProtocol.CMD_CAM_ZOOM,
                        if (ok) DroneCommProtocol.ACK_OK else DroneCommProtocol.ACK_FAIL)
                }
            }
// ── 配件 ────────────────────────────────────────
            DroneCommProtocol.CMD_AUX_LIGHT -> {
                val p = DroneCommProtocol.parseAuxLightPayload(frame.payload) ?: run {
                    Log.e(TAG, "AUX_LIGHT 载荷解析失败 (len=${frame.payload.size})")
                    sendAck(DroneCommProtocol.CMD_AUX_LIGHT, DroneCommProtocol.ACK_FAIL)
                    return
                }
                val modeStr = when {
                    p.isOff  -> "OFF"
                    p.isOn   -> "ON"
                    p.isAuto -> "AUTO"
                    else     -> "UNKNOWN"
                }
                Log.i(TAG, "AUX_LIGHT $modeStr")
                auxLightCtrl.setBottomAuxLight(p.mode) { ok, _ ->
                    sendAck(DroneCommProtocol.CMD_AUX_LIGHT,
                        if (ok) DroneCommProtocol.ACK_OK else DroneCommProtocol.ACK_FAIL)
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
