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
import android.annotation.SuppressLint

class DroneControlService : Service() {

    companion object {
        private const val TAG = "DroneControlService"
        private const val CHANNEL_ID = "drone_ctrl_channel"
        private const val NOTIF_ID   = 1001

        private const val REG_RETRY_BASE_MS = 500L
        private const val REG_RETRY_MAX_MS  = 8000L

        @Volatile
        var currentLensCode: Byte = DroneCommProtocol.CAM_LENS_WIDE
            private set

        fun updateCurrentLens(lensCode: Byte) {
            currentLensCode = lensCode
        }

        // 静态持有 MainActivity 传入的 Controller 引用
        @Volatile
        var preflightController: PreflightController? = null
        @Volatile
        var landingController: LandingController? = null

        // 用于触发 MainActivity 开启/关闭视觉流的闭包
        @Volatile
        var onStartCameraStream: (() -> Unit)? = null
        @Volatile
        var onStopCameraStream: (() -> Unit)? = null

        // ⭐【新增控制点】：显式暴露给 MainActivity 的视觉重置闭包，确保在状态机跳转前清除黏性锁
        @Volatile
        var onResetVisionTracking: (() -> Unit)? = null

        // ⭐【速度面板协调标志】：VelocityControlPanel 激活时置 true，
        //   用于拦截 CMD_VEL 防止与本端手动速度控制冲突
        @Volatile
        var isVelocityPanelActive = false

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
        scheduleRegisterPayloadListener(immediate = true)
        Log.i(TAG, "Service 已启动，监听中")
    }

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

            // ⭐【核心修改点 1】：对 Jetson 发送的速度控制指令增加状态拦截锁
            DroneCommProtocol.CMD_VEL -> {
                if (landingController?.getTaskState() == TaskState.LANDING) {
                    Log.w(TAG, "🚨 [拦截控制冲突] 本端正在闭合执行 Aruco 降落判定，已强行丢弃来自 Jetson 的 CMD_VEL 指令")
                    sendAck(DroneCommProtocol.CMD_VEL, DroneCommProtocol.ACK_FAIL)
                    return
                }
                // ⭐【速度面板互斥】：手动速度控制面板激活时拦截 CMD_VEL
                if (isVelocityPanelActive) {
                    Log.w(TAG, "🚨 [拦截控制冲突] 速度控制面板激活中，已拦截 Jetson CMD_VEL")
                    sendAck(DroneCommProtocol.CMD_VEL, DroneCommProtocol.ACK_FAIL)
                    return
                }

                val vel = DroneCommProtocol.parseVelPayload(frame.payload) ?: run {
                    sendAck(DroneCommProtocol.CMD_VEL, DroneCommProtocol.ACK_FAIL)
                    return
                }
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

            // ── 云台角度 ─────────────────────────────────────
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
                cameraCtrl.setVideoCfg(currentLensCode, p.resolution, p.frameRate) { ok, _ ->
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

            // ── 任务/自动化指令 ───────────────────────────────
            DroneCommProtocol.CMD_CHECK_BEFORE_TAKEOFF -> {
                Log.i(TAG, "来自 Jetson: 起飞前检查")
                val ctrl = preflightController
                if (ctrl != null) {
                    onResetVisionTracking?.invoke() // 自检前也同步清理历史缓存
                    onStartCameraStream?.invoke()
                    mainHandler.post { ctrl.startCheck() }
                    sendAck(DroneCommProtocol.CMD_CHECK_BEFORE_TAKEOFF, DroneCommProtocol.ACK_OK)
                } else {
                    Log.w(TAG, "preflightController 未注入")
                    sendAck(DroneCommProtocol.CMD_CHECK_BEFORE_TAKEOFF, DroneCommProtocol.ACK_FAIL)
                }
            }

            // ⭐【核心修改点 2】：视觉降落分支，加入前置同步重置追踪，确保时序保底
            DroneCommProtocol.CMD_VISION_LANDING -> {
                Log.i(TAG, "来自 Jetson: 视觉降落")
                val ctrl = landingController
                if (ctrl != null) {
                    // 先在当前 Binder 线程立即触发重置，消除主线程排队带来的时序滞后风险
                    onResetVisionTracking?.invoke()
                    onStartCameraStream?.invoke()

                    mainHandler.post { ctrl.startVisionLanding() }
                    sendAck(DroneCommProtocol.CMD_VISION_LANDING, DroneCommProtocol.ACK_OK)
                } else {
                    Log.w(TAG, "landingController 未注入，忽略 CMD_VISION_LANDING")
                    sendAck(DroneCommProtocol.CMD_VISION_LANDING, DroneCommProtocol.ACK_FAIL)
                }
            }

            // ── 航点指令 ACK ─────────────────────────────────
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

    @SuppressLint("NewApi")
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