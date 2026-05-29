package com.example.msdksample

import android.app.*
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import dji.v5.manager.aircraft.payload.PayloadCenter
import dji.v5.manager.aircraft.payload.PayloadIndexType
import dji.v5.manager.aircraft.payload.listener.PayloadDataListener
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DroneControlService
 *
 * 后台前台服务：常驻监听 PSDK 低速通道，分发飞控 / 云台 / 相机 / 配件指令。
 *
 * 设计要点：
 *   1. 启动方式声明为 START_STICKY，被系统杀掉后会自动重启；
 *      `onStartCommand` 中重新调用 `startForeground`，确保 5s 内挂上通知，
 *      避免 ForegroundServiceDidNotStartInTimeException。
 *   2. PayloadManager 在 SDK 未注册完毕时可能为 null，
 *      `registerPayloadListener` 失败后会按指数退避自动重试。
 *   3. dispatchFrame 严格根据 controller 的 Boolean 回调结果决定 ACK 状态，
 *      不依赖 msg 字符串内容（旧实现用 contains("enabled") 会漏 ACK / 误 ACK）。
 *   4. 维护 `currentLensCode`：用户从 UI 切镜头时调 `updateCurrentLens`，
 *      Service 收到 CMD_CAM_ZOOM 切镜头时也会更新。CameraController.setVideoCfg
 *      用这个 lens 选择正确的 KeyVideoResolutionFrameRate。
 */
class DroneControlService : Service() {

    companion object {
        private const val TAG = "DroneControlService"
        private const val CHANNEL_ID = "drone_ctrl_channel"
        private const val NOTIF_ID   = 1001

        // PayloadListener 注册重试参数
        private const val REG_RETRY_BASE_MS = 500L
        private const val REG_RETRY_MAX_MS  = 8000L

        /** 当前激活的镜头（被 MainActivity 与 Service 共享） */
        @Volatile
        var currentLensCode: Byte = DroneCommProtocol.CAM_LENS_WIDE
            private set

        fun updateCurrentLens(lensCode: Byte) {
            currentLensCode = lensCode
        }

        /**
         * 发送编码帧到 Jetson（供 WaypointController 等外部调用）。
         * fire-and-forget：ACK 结果通过 payloadDataListener → dispatchFrame 中的 Toast 反馈。
         */
        fun sendFrame(data: ByteArray) {
            val mgr = try {
                PayloadCenter.getInstance().payloadManager[PayloadIndexType.UP]
            } catch (e: Throwable) {
                Log.w(TAG, "sendFrame: payloadManager 访问异常: ${e.message}")
                return
            } ?: run {
                Log.w(TAG, "sendFrame: payloadManager 为空")
                return
            }
            mgr.sendDataToPayload(data,
                object : dji.v5.common.callback.CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        Log.i(TAG, "Frame sent (${data.size}B)")
                    }
                    override fun onFailure(error: dji.v5.common.error.IDJIError) {
                        Log.w(TAG, "Frame send failed: ${error.description()}")
                    }
                }
            )
        }
    }

    private val droneCtrl    = DroneController()
    private val gimbalCtrl   = GimbalController()
    private val cameraCtrl   = CameraController()
    private val auxLightCtrl = AuxLightController()
    private val waypointCtrl = WaypointController()
    private val payloadIndex = PayloadIndexType.UP

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listenerRegistered = AtomicBoolean(false)
    @Volatile private var nextRetryDelayMs = REG_RETRY_BASE_MS

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
        // 把 CameraController 与 Service 关联，用于读取 currentLensCode
        cameraCtrl.lensProvider = { currentLensCode }
        scheduleRegisterPayloadListener(immediate = true)
        Log.i(TAG, "Service 已启动，监听中")
    }

    /**
     * 系统重启 Service 时只走 onStartCommand（不走 onCreate），
     * 这里再保险地 startForeground 一次，避免 5s 超时崩溃。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIF_ID, buildNotification())
        if (!listenerRegistered.get()) {
            scheduleRegisterPayloadListener(immediate = true)
        }
        return START_STICKY
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
            // TAKEOFF/LAND/HOVER 使用双段回调：
            //   onAccepted → CMD_ACK               (协议层快反馈，指令被接受)
            //   onComplete → CMD_ACK_*_COMPLETE    (动作真正完成的延后通知)
            // Jetson 上层需要等到完成通知才能安全地发下一条动作指令 (尤其是 VEL)。

            DroneCommProtocol.CMD_TAKEOFF -> droneCtrl.takeoff(
                onAccepted = { ok, _ ->
                    sendAck(DroneCommProtocol.CMD_TAKEOFF, ackOf(ok))
                },
                onComplete = { ok, _ ->
                    if (ok) sendNotification(DroneCommProtocol.CMD_ACK_TAKEOFF_COMPLETE)
                    else    Log.w(TAG, "TAKEOFF complete 失败或超时，不发完成通知")
                }
            )

            DroneCommProtocol.CMD_LAND -> droneCtrl.land(
                onAccepted = { ok, _ ->
                    sendAck(DroneCommProtocol.CMD_LAND, ackOf(ok))
                },
                onComplete = { ok, _ ->
                    if (ok) sendNotification(DroneCommProtocol.CMD_ACK_LAND_COMPLETE)
                    else    Log.w(TAG, "LAND complete 失败或超时，不发完成通知")
                }
            )

            DroneCommProtocol.CMD_HOVER -> droneCtrl.hover(
                onAccepted = { ok, _ ->
                    sendAck(DroneCommProtocol.CMD_HOVER, ackOf(ok))
                },
                onComplete = { ok, _ ->
                    if (ok) sendNotification(DroneCommProtocol.CMD_ACK_HOVER_COMPLETE)
                    else    Log.w(TAG, "HOVER complete 异常: ok=false")
                }
            )

            DroneCommProtocol.CMD_VEL -> {
                val vel = DroneCommProtocol.parseVelPayload(frame.payload) ?: run {
                    sendAck(DroneCommProtocol.CMD_VEL, DroneCommProtocol.ACK_FAIL)
                    return
                }
                // 修正：严格根据 ok 回 ACK，不再用 msg.contains("enabled") 判断
                droneCtrl.sendVelocity(vel.vx, vel.vy, vel.vz, vel.yawRate) { ok, _ ->
                    sendAck(DroneCommProtocol.CMD_VEL, ackOf(ok))
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
                    sendAck(DroneCommProtocol.CMD_GIMBAL_YAW_FOLLOW, ackOf(ok))
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
                    sendAck(DroneCommProtocol.CMD_GIMBAL_ANGLE, ackOf(ok))
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
                    sendAck(DroneCommProtocol.CMD_CAM_MODE, ackOf(ok))
                }
            }

            DroneCommProtocol.CMD_CAM_SHOOT -> {
                Log.i(TAG, "CAM_SHOOT")
                // CameraController 内部会留 800ms 落盘等待再回调，再回 ACK
                cameraCtrl.shootPhoto { ok, _ ->
                    sendAck(DroneCommProtocol.CMD_CAM_SHOOT, ackOf(ok))
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
                    sendAck(DroneCommProtocol.CMD_CAM_RECORD, ackOf(ok))
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
                           "fps=0x${p.frameRate.toUByte().toString(16)} " +
                           "lens=0x${currentLensCode.toUByte().toString(16)}")
                cameraCtrl.setVideoCfg(p.resolution, p.frameRate) { ok, _ ->
                    sendAck(DroneCommProtocol.CMD_CAM_VIDEO_CFG, ackOf(ok))
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
                    if (ok) updateCurrentLens(p.lens)
                    sendAck(DroneCommProtocol.CMD_CAM_ZOOM, ackOf(ok))
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
                    sendAck(DroneCommProtocol.CMD_AUX_LIGHT, ackOf(ok))
                }
            }

            // ── 航点指令 ACK (Jetson 对 Android 主动指令的应答) ──
            DroneCommProtocol.CMD_RECORD_WAYPOINT,
            DroneCommProtocol.CMD_SAVE_WAYPOINTS,
            DroneCommProtocol.CMD_CLEAR_WAYPOINTS -> {
                val ok = frame.payload.size >= 2 && frame.payload[1] == DroneCommProtocol.ACK_OK
                val cmdName = when (frame.cmd) {
                    DroneCommProtocol.CMD_RECORD_WAYPOINT -> "记录航点"
                    DroneCommProtocol.CMD_SAVE_WAYPOINTS  -> "保存航线"
                    DroneCommProtocol.CMD_CLEAR_WAYPOINTS -> "清除航点"
                    else -> "未知"
                }
                val resultMsg = if (ok) "$cmdName 成功" else "$cmdName 失败"
                Log.i(TAG, "WAYPOINT ACK: $resultMsg")
                mainHandler.post {
                    android.widget.Toast.makeText(
                        this, resultMsg, android.widget.Toast.LENGTH_SHORT
                    ).show()
                }
            }

            else -> Log.w(TAG, "未知 CMD=0x${frame.cmd.toUByte().toString(16)}")
        }
    }

    private fun ackOf(ok: Boolean): Byte =
        if (ok) DroneCommProtocol.ACK_OK else DroneCommProtocol.ACK_FAIL

    // ── 低速通道工具 ──────────────────────────────────────

    /**
     * 注册 PayloadListener，失败按指数退避自动重试。
     *
     * Service 启动时 PSDK 设备可能尚未连接，PayloadManager[UP] 为 null。
     * 旧实现仅打了一条日志就放弃，导致后续即使飞机连上也收不到帧。
     */
    private fun scheduleRegisterPayloadListener(immediate: Boolean) {
        val delay = if (immediate) 0L else nextRetryDelayMs
        mainHandler.postDelayed({ tryRegisterPayloadListener() }, delay)
    }

    private fun tryRegisterPayloadListener() {
        if (listenerRegistered.get()) return

        val mgr = try {
            PayloadCenter.getInstance().payloadManager[payloadIndex]
        } catch (e: Throwable) {
            Log.w(TAG, "payloadManager 访问异常: ${e.message}")
            null
        }

        if (mgr != null) {
            mgr.addPayloadDataListener(payloadDataListener)
            listenerRegistered.set(true)
            nextRetryDelayMs = REG_RETRY_BASE_MS
            Log.i(TAG, "PayloadListener 注册成功")
            return
        }

        // 失败 → 指数退避重试 (上限 REG_RETRY_MAX_MS)
        Log.w(TAG, "PayloadManager[$payloadIndex] 暂未就绪，${nextRetryDelayMs}ms 后重试")
        scheduleRegisterPayloadListener(immediate = false)
        nextRetryDelayMs = (nextRetryDelayMs * 2).coerceAtMost(REG_RETRY_MAX_MS)
    }

    private fun unregisterPayloadListener() {
        mainHandler.removeCallbacksAndMessages(null)
        if (!listenerRegistered.get()) return
        try {
            PayloadCenter.getInstance().payloadManager[payloadIndex]
                ?.removePayloadDataListener(payloadDataListener)
        } catch (e: Throwable) {
            Log.w(TAG, "removePayloadDataListener 异常: ${e.message}")
        }
        listenerRegistered.set(false)
    }

    private fun sendAck(ackedCmd: Byte, status: Byte) {
        val mgr = try {
            PayloadCenter.getInstance().payloadManager[payloadIndex]
        } catch (e: Throwable) {
            Log.w(TAG, "ACK send: payloadManager 访问异常: ${e.message}")
            return
        } ?: run {
            Log.w(TAG, "ACK send: payloadManager 为空，ack 丢弃 cmd=0x${ackedCmd.toUByte().toString(16)}")
            return
        }
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

    /**
     * 发送无载荷通知帧 (CMD_ACK_TAKEOFF_COMPLETE / _LAND_COMPLETE / _HOVER_COMPLETE)。
     *
     * 与 sendAck 的区别：sendAck 带 [ackedCmd, status] 两字节载荷，
     * sendNotification 是纯通知帧 (len=0)，表示某个异步动作真正完成。
     */
    private fun sendNotification(cmd: Byte) {
        val mgr = try {
            PayloadCenter.getInstance().payloadManager[payloadIndex]
        } catch (e: Throwable) {
            Log.w(TAG, "NOTIFY send: payloadManager 访问异常: ${e.message}")
            return
        } ?: run {
            Log.w(TAG, "NOTIFY send: payloadManager 为空，通知丢弃 cmd=0x${cmd.toUByte().toString(16)}")
            return
        }
        mgr.sendDataToPayload(
            DroneCommProtocol.encodeSimple(cmd),
            object : dji.v5.common.callback.CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    Log.i(TAG, "NOTIFY sent: cmd=0x${cmd.toUByte().toString(16)}")
                }
                override fun onFailure(error: dji.v5.common.error.IDJIError) {
                    Log.w(TAG, "NOTIFY send failed: ${error.description()}")
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
