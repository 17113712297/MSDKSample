package com.example.msdksample

import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.Velocity3D
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.sdk.keyvalue.value.flightcontroller.FlightMode
import dji.sdk.keyvalue.value.flightcontroller.RollPitchControlMode
import dji.sdk.keyvalue.value.flightcontroller.VerticalControlMode
import dji.sdk.keyvalue.value.flightcontroller.VirtualStickFlightControlParam
import dji.sdk.keyvalue.value.flightcontroller.YawControlMode
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotation
import dji.sdk.keyvalue.value.gimbal.GimbalAngleRotationMode
import dji.sdk.keyvalue.value.gimbal.GimbalMode
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

// =========================================================================
// 状态枚举定义 (对外公开)
// =========================================================================
enum class TaskState { INACTIVE, LANDING_PREP, LANDING }

class LandingController {

    companion object {
        const val TAG = "LandingController"
        const val CONTROL_INTERVAL     = 50L
        const val TELEMETRY_INTERVAL   = 500L
        const val ALIGN_YAW_TIMEOUT_MS = 15_000L
        const val VISION_STALE_MS      = 5_000L
        const val VISION_HOLD_MS       = 200L
        const val INITIAL_SEARCH_TIMEOUT_MS = 8_000L
        const val ALIGN_YAW_THRESHOLD_DEG = 6.0
    }

    // =========================================================================
    // 对外暴露的回调接口 (UI 层只需监听这些回调)
    // =========================================================================
    var onTaskStateChanged: ((TaskState) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onMessage: ((String) -> Unit)? = null
    var onSpeedUpdate: ((velX: Double, velY: Double, velZ: Double) -> Unit)? = null
    var onYawRateUpdate: ((yawRate: Double) -> Unit)? = null
    var onBatteryUpdate: ((pct: Int) -> Unit)? = null

    // 当前使用的相机索引（云台控制需要）
    var currentCameraIndex: ComponentIndexType = ComponentIndexType.LEFT_OR_MAIN

    // =========================================================================
    // 数据结构与状态引用
    // =========================================================================
    private data class AircraftState(
        val isFlying: Boolean        = false,
        val altitude: Double         = 999.0,
        val ultrasonicHeight: Double = 999.0,
        val pitch: Double            = 0.0,
        val roll: Double             = 0.0,
        val yaw: Double              = Double.NaN,
        val velZ: Double             = 0.0
    )
    private val aircraftStateRef = AtomicReference(AircraftState())

    private data class VisionMeasurement(
        val targetId:  Int    = -1,
        val errX:      Double = Double.NaN,
        val errY:      Double = Double.NaN,
        val depthZ:    Double = Double.NaN,
        val yawDeg:    Double = Double.NaN,
        val timestamp: Long   = 0L
    )
    private val visionRef = AtomicReference(VisionMeasurement())

    private enum class MissionState { IDLE, SEARCHING, ALIGN_YAW, LANDING }

    private val taskStateRef = AtomicReference(TaskState.INACTIVE)
    @Volatile private var missionState        = MissionState.IDLE
    @Volatile private var targetLockedYaw     = Double.NaN
    @Volatile private var alignYawStartTimeMs = 0L

    private var validVisionFrameCount = 0
    private val REQUIRED_STABLE_FRAMES = 10
    @Volatile private var isVirtualStickActive = false
    @Volatile private var landingStartTimeMs = 0L
    private var lastValidVisionTimeMs = 0L
    private var lastAlignYawLogTime = 0L

    // 控制参数
    private val KP_XY           = 0.8
    private val KP_YAW          = 0.5
    private val KP_Z_VEL        = 1.2
    private val MAX_XY_VEL      = 0.15
    private val MAX_YAW_VEL     = 20.0
    private val MIN_YAW_VEL     = 8.0
    private val MAX_DESCEND_VEL = -0.25
    private val MIN_DESCEND_VEL = -0.05
    private val TILT_LIMIT_DEG  = 15.0
    private val DT              = CONTROL_INTERVAL / 1000.0
    private val MAX_XY_ACCEL    = 0.25
    private val MAX_Z_ACCEL     = 0.25
    private val MAX_YAW_ACCEL   = 30.0

    private var cmdPitch    = 0.0
    private var cmdRoll     = 0.0
    private var cmdYaw      = 0.0
    private var cmdThrottle = 0.0
    private var touchdownFrames = 0

    // 测距与 UI 相关
    private var lastUiYawDeg  = Double.NaN
    private var lastUiYawTime = 0L

    // =========================================================================
    // 线程、Runnable 与看门狗 (已修复初始化顺序)
    // =========================================================================
    private val controlThread = HandlerThread("FlightControlThread", android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
    private val controlHandler: Handler

    @Volatile private var lastCmdSendTime = 0L
    private var watchdogTimer: Timer? = null

    // ★ 修复：将 Runnable 定义提前，防止 init 块中调用时报未初始化错误
    private val flightControlRunnable = object : Runnable {
        override fun run() {
            if (taskStateRef.get() != TaskState.LANDING) return
            try {
                pollFlightStatusSync()
                executeLandingStateMachine()
            } catch (e: Exception) {
                Log.e(TAG, "飞控线程异常", e)
                stopMission("控制逻辑崩溃")
            } finally {
                if (taskStateRef.get() == TaskState.LANDING) {
                    controlHandler.postDelayed(this, CONTROL_INTERVAL)
                }
            }
        }
    }

    private val telemetryRunnable = object : Runnable {
        override fun run() {
            try {
                if (taskStateRef.get() != TaskState.LANDING) pollFlightStatusSync()
                pollTelemetryToUI()
            } catch (_: Exception) {}
            finally { controlHandler.postDelayed(this, TELEMETRY_INTERVAL) }
        }
    }

    // =========================================================================
    // 飞行模式监听器 (防抢夺)
    // =========================================================================
    @Volatile private var lastKnownFlightMode: String = ""
    private val flightModeListener = object : CommonCallbacks.KeyListener<FlightMode> {
        override fun onValueChange(oldValue: FlightMode?, newValue: FlightMode?) {
            val name = newValue?.name ?: ""
            lastKnownFlightMode = name
            if (taskStateRef.get() == TaskState.LANDING && name.isNotEmpty()) {
                if (!isAllowedFlightMode(name)) {
                    Log.w(TAG, "🛑 检测到非白名单模式 ($name),判定为飞手接管")
                    stopMission("飞手切挡接管 ($name)")
                }
            }
        }
    }

    // =========================================================================
    // 初始化块
    // =========================================================================
    init {
        controlThread.start()
        controlHandler = Handler(controlThread.looper)

        // 注册遥测与控制循环 (现在可以安全调用了)
        controlHandler.post(telemetryRunnable)

        // 注册飞行模式监听
        runCatching {
            KeyManager.getInstance().listen(KeyTools.createKey(FlightControllerKey.KeyFlightMode), this, flightModeListener)
        }
    }

    // =========================================================================
    // 公开 API
    // =========================================================================
    fun getTaskState(): TaskState = taskStateRef.get()

    fun updateVisionData(id: Int, errX: Double, errY: Double, depthZ: Double, yawDeg: Double) {
        if (taskStateRef.get() != TaskState.INACTIVE) {
            visionRef.set(VisionMeasurement(id, errX, errY, depthZ, yawDeg, System.currentTimeMillis()))
        }
    }

    fun startVisionLanding() {
        setTaskState(TaskState.LANDING_PREP)

        controlHandler.post {
            try {
                pollFlightStatusSync()
                val state = aircraftStateRef.get()

                if (!state.isFlying) {
                    dispatchError("⚠️ 拦截: 无人机尚未起飞!")
                    return@post
                }
                if (state.yaw.isNaN()) {
                    dispatchError("⚠️ 拦截: 等待 IMU 航向初始化")
                    return@post
                }
                val modeName = runCatching { KeyManager.getInstance().getValue(KeyTools.createKey(FlightControllerKey.KeyFlightMode))?.name ?: "" }.getOrElse { "" }
                if (modeName.isNotEmpty() && !isAllowedFlightMode(modeName)) {
                    dispatchError("⚠️ 拦截: 请切入 N 挡! (当前:$modeName)")
                    return@post
                }
                lastKnownFlightMode = modeName

                resetControlState()
                rotateGimbal(-90.0)

                VirtualStickManager.getInstance().enableVirtualStick(object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(true)
                        isVirtualStickActive = true
                        landingStartTimeMs = System.currentTimeMillis()
                        missionState = MissionState.SEARCHING
                        setTaskState(TaskState.LANDING)
                        startWatchdog()

                        controlHandler.removeCallbacks(flightControlRunnable)
                        controlHandler.post(flightControlRunnable)
                        Log.i(TAG, "✅ 虚拟摇杆已开启, SEARCHING 开始")
                    }

                    override fun onFailure(error: IDJIError) {
                        dispatchError("🛑 接管被拒: [${error.errorCode()}] ${error.description()}")
                    }
                })
            } catch (e: Exception) {
                Log.e(TAG, "启动降落异常", e)
                dispatchError("❌ 启动崩溃: ${e.message}")
            }
        }
    }

    fun stopMission(reason: String) {
        val prev = taskStateRef.getAndSet(TaskState.INACTIVE)
        if (prev == TaskState.INACTIVE) return

        Log.w(TAG, "🔴 停止任务: $reason (prev=$prev)")
        missionState          = MissionState.IDLE
        targetLockedYaw       = Double.NaN
        alignYawStartTimeMs   = 0L
        validVisionFrameCount = 0
        stopWatchdog()
        isVirtualStickActive = false
        landingStartTimeMs = 0L

        onTaskStateChanged?.invoke(TaskState.INACTIVE)
        onMessage?.invoke("🔴 已退出: $reason")

        if (prev == TaskState.LANDING || prev == TaskState.LANDING_PREP) {
            runCatching {
                VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(false)
                VirtualStickManager.getInstance().disableVirtualStick(object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() { Log.i(TAG, "✅ 虚拟摇杆已关闭") }
                    override fun onFailure(error: IDJIError) { Log.e(TAG, "摇杆释放失败: ${error.description()}") }
                })
            }
        }
    }

    fun release() {
        stopMission("Controller Released")
        runCatching { KeyManager.getInstance().cancelListen(KeyTools.createKey(FlightControllerKey.KeyFlightMode), this) }
        controlHandler.removeCallbacksAndMessages(null)
        controlThread.quitSafely()
    }

    // =========================================================================
    // 内部控制逻辑
    // =========================================================================
    private fun setTaskState(newState: TaskState) {
        taskStateRef.set(newState)
        onTaskStateChanged?.invoke(newState)
    }

    private fun dispatchError(msg: String) {
        setTaskState(TaskState.INACTIVE)
        onError?.invoke(msg)
    }

    private fun isAllowedFlightMode(name: String): Boolean {
        return name == "NORMAL" || name == "GPS_NORMAL" || name == "POSITION_CTRL" ||
                name == "JOYSTICK" || name == "VIRTUAL_STICK"
    }

    private fun resetControlState() {
        targetLockedYaw     = Double.NaN
        alignYawStartTimeMs = 0L
        cmdPitch = 0.0; cmdRoll = 0.0; cmdYaw = 0.0; cmdThrottle = 0.0
        visionRef.set(VisionMeasurement())
        validVisionFrameCount = 0
        lastValidVisionTimeMs = 0L
    }

    private fun triggerFinalLanding() {
        if (taskStateRef.get() != TaskState.LANDING) return
        Log.w(TAG, "🔴 开始执行最终降落与停桨接管")

        val wasFlying = aircraftStateRef.get().isFlying
        missionState = MissionState.IDLE
        touchdownFrames = 0
        stopWatchdog()

        runCatching {
            VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(false)
            VirtualStickManager.getInstance().disableVirtualStick(object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    Log.i(TAG, "✅ VS释放成功，下发 FC 停桨指令")
                    controlHandler.postDelayed({
                        runCatching { KeyManager.getInstance().performAction(KeyTools.createKey(FlightControllerKey.KeyStartAutoLanding), null) }
                        controlHandler.postDelayed({
                            runCatching { KeyManager.getInstance().performAction(KeyTools.createKey(FlightControllerKey.KeyConfirmLanding), null) }
                            stopMission(if (wasFlying) "自动盲降完成" else "强制停桨完成")
                        }, 1000)
                    }, 800)
                }
                override fun onFailure(error: IDJIError) {
                    Log.e(TAG, "VS释放失败: ${error.description()}")
                    stopMission("降落接管失败")
                }
            })
        }
    }

    private fun executeLandingStateMachine() {
        val state = aircraftStateRef.get()
        val now = System.currentTimeMillis()

        if (!state.isFlying) { triggerFinalLanding(); return }
        if (state.yaw.isNaN()) { stopMission("IMU 偏航角丢失"); return }
        if (abs(state.pitch) > TILT_LIMIT_DEG || abs(state.roll) > TILT_LIMIT_DEG) { stopMission("姿态越界(防侧翻)"); return }
        if (!isVirtualStickActive) { stopMission("虚拟摇杆未激活"); return }

        val curMode = lastKnownFlightMode
        if (curMode.isNotEmpty() && !isAllowedFlightMode(curMode)) {
            stopMission("飞手切挡接管 ($curMode)")
            return
        }

        val v = visionRef.get()
        val isFrameValid = v.targetId != -1 && !v.errX.isNaN() && !v.errY.isNaN()
        if (isFrameValid) lastValidVisionTimeMs = now

        val neverSeenTarget = (lastValidVisionTimeMs == 0L)
        val timeSinceLastValidMs = if (neverSeenTarget) now - landingStartTimeMs else now - lastValidVisionTimeMs

        var tPitch = 0.0; var tRoll = 0.0; var tYaw = 0.0; var tThrottle = 0.0

        when {
            neverSeenTarget && timeSinceLastValidMs > INITIAL_SEARCH_TIMEOUT_MS -> { stopMission("初始搜索超时"); return }
            !neverSeenTarget && timeSinceLastValidMs > VISION_STALE_MS -> { stopMission("目标丢失超时"); return }
            timeSinceLastValidMs > VISION_HOLD_MS -> { validVisionFrameCount = 0 }
            else -> {
                validVisionFrameCount = min(validVisionFrameCount + 1, REQUIRED_STABLE_FRAMES + 1)
                when (missionState) {
                    MissionState.SEARCHING -> {
                        if (validVisionFrameCount >= REQUIRED_STABLE_FRAMES) {
                            missionState = MissionState.ALIGN_YAW
                            alignYawStartTimeMs = now
                        }
                    }
                    MissionState.ALIGN_YAW -> {
                        if (now - alignYawStartTimeMs > ALIGN_YAW_TIMEOUT_MS) {
                            targetLockedYaw = state.yaw
                            missionState = MissionState.LANDING
                        } else if (!v.yawDeg.isNaN() && abs(v.yawDeg) < ALIGN_YAW_THRESHOLD_DEG) {
                            targetLockedYaw = state.yaw
                            missionState = MissionState.LANDING
                        } else if (!v.yawDeg.isNaN()) {
                            val rawYaw = KP_YAW * v.yawDeg
                            tYaw = if (abs(rawYaw) < MIN_YAW_VEL) (if (rawYaw > 0) MIN_YAW_VEL else -MIN_YAW_VEL) else rawYaw.coerceIn(-MAX_YAW_VEL, MAX_YAW_VEL)
                        }
                        if (now - lastAlignYawLogTime > 200L) { lastAlignYawLogTime = now; Log.d(TAG, "🧭 ALIGN_YAW tYaw=${tYaw}") }
                    }
                    MissionState.LANDING -> {
                        if (targetLockedYaw.isNaN()) targetLockedYaw = state.yaw

                        var yawErr = state.yaw - targetLockedYaw
                        if (yawErr > 180.0) yawErr -= 360.0
                        if (yawErr < -180.0) yawErr += 360.0
                        tYaw = (-1.5 * yawErr).coerceIn(-MAX_YAW_VEL, MAX_YAW_VEL)

                        val CAMERA_OFFSET_FORWARD = 0.10
                        val CAMERA_OFFSET_RIGHT   = 0.00
                        val errForwardCG = -v.errY + CAMERA_OFFSET_FORWARD
                        val errRightCG   = v.errX + CAMERA_OFFSET_RIGHT

                        // 轴互换
                        var pPitch = KP_XY * errRightCG
                        var pRoll  = KP_XY * errForwardCG

                        val vel2d = hypot(pPitch, pRoll)
                        if (vel2d > MAX_XY_VEL) { val s = MAX_XY_VEL / vel2d; pPitch *= s; pRoll *= s }
                        if (abs(pPitch) < 0.02) pPitch = 0.0
                        if (abs(pRoll) < 0.02) pRoll = 0.0

                        tPitch = pPitch; tRoll = pRoll

                        val ultraOk = state.ultrasonicHeight in 0.01..10.0
                        val depthOk = !v.depthZ.isNaN() && v.depthZ in 0.01..20.0
                        val height = when {
                            ultraOk -> state.ultrasonicHeight
                            depthOk -> v.depthZ
                            else -> { stopMission("高度数据全部失效"); return }
                        }

                        val radialErr = hypot(v.errX, v.errY)
                        val allowedErr = max(0.10, height * 0.15)
                        val alignFactor = ((allowedErr - radialErr) / allowedErr).coerceIn(0.0, 1.0)
                        val currentVelZUp = -state.velZ
                        val desiredVelZ = if (alignFactor < 0.1) 0.0 else {
                            val minSpeed = if (height < 1.0) 0.10 else 0.15
                            val rawSpeed = max(minSpeed, height * 0.20) * alignFactor
                            (-rawSpeed).coerceIn(MAX_DESCEND_VEL, MIN_DESCEND_VEL)
                        }

                        tThrottle = desiredVelZ + KP_Z_VEL * (desiredVelZ - currentVelZUp)

                        if (desiredVelZ < -0.1 && abs(currentVelZUp) < 0.05 && height < 1.0) touchdownFrames++ else touchdownFrames = 0

                        if (height in 0.01..0.30 || touchdownFrames > 15) {
                            triggerFinalLanding(); return
                        }
                    }
                    else -> {}
                }
            }
        }

        // 我们之前已经移除了 EMA，这里仅做纯粹的加速度限幅，不影响向前的数学证明
        cmdPitch    = accelLimit(cmdPitch, tPitch, MAX_XY_ACCEL)
        cmdRoll     = accelLimit(cmdRoll, tRoll, MAX_XY_ACCEL)
        cmdYaw      = accelLimit(cmdYaw, tYaw, MAX_YAW_ACCEL)
        cmdThrottle = accelLimit(cmdThrottle, tThrottle, MAX_Z_ACCEL)

        val param = VirtualStickFlightControlParam().apply {
            rollPitchControlMode = RollPitchControlMode.VELOCITY
            yawControlMode = YawControlMode.ANGULAR_VELOCITY
            verticalControlMode = VerticalControlMode.VELOCITY
            rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
            roll = cmdRoll; pitch = cmdPitch; yaw = cmdYaw; verticalThrottle = cmdThrottle
        }
        runCatching {
            VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(param)
            lastCmdSendTime = System.currentTimeMillis()
        }
    }

    private fun accelLimit(current: Double, target: Double, maxAccel: Double): Double {
        val maxDelta = maxAccel * DT
        return current + (target - current).coerceIn(-maxDelta, maxDelta)
    }

    // =========================================================================
    // 状态拉取、看门狗与云台
    // =========================================================================
    private fun pollFlightStatusSync() {
        runCatching {
            val km = KeyManager.getInstance()
            val prev = aircraftStateRef.get()
            val isFlying = km.getValue(KeyTools.createKey(FlightControllerKey.KeyIsFlying)) ?: prev.isFlying
            val alt = km.getValue(KeyTools.createKey(FlightControllerKey.KeyAltitude))?.toDouble() ?: prev.altitude
            val ultra = km.getValue(KeyTools.createKey(FlightControllerKey.KeyUltrasonicHeight))?.toDouble()
            val att = km.getValue(KeyTools.createKey(FlightControllerKey.KeyAircraftAttitude))
            val vel = km.getValue(KeyTools.createKey(FlightControllerKey.KeyAircraftVelocity))
            val mode = km.getValue(KeyTools.createKey(FlightControllerKey.KeyFlightMode))?.name
            if (!mode.isNullOrEmpty()) lastKnownFlightMode = mode

            aircraftStateRef.set(AircraftState(
                isFlying = isFlying, altitude = alt,
                ultrasonicHeight = if (ultra != null && ultra > 0.0) ultra else prev.ultrasonicHeight,
                pitch = att?.pitch ?: prev.pitch, roll = att?.roll ?: prev.roll,
                yaw = att?.yaw ?: prev.yaw, velZ = vel?.z ?: prev.velZ
            ))
        }
    }

    private fun pollTelemetryToUI() {
        val km = KeyManager.getInstance()
        val vel = km.getValue(KeyTools.createKey(FlightControllerKey.KeyAircraftVelocity))
        if (vel is Velocity3D) onSpeedUpdate?.invoke(vel.x, vel.y, vel.z)

        val pct = km.getValue(KeyTools.createKey(BatteryKey.KeyChargeRemainingInPercent))
        if (pct is Int) onBatteryUpdate?.invoke(pct)

        val yaw = aircraftStateRef.get().yaw
        if (!yaw.isNaN()) {
            val now = System.currentTimeMillis()
            if (!lastUiYawDeg.isNaN() && lastUiYawTime > 0) {
                val dt = (now - lastUiYawTime) / 1000.0
                if (dt > 0.0) {
                    var delta = yaw - lastUiYawDeg
                    if (delta > 180.0) delta -= 360.0 else if (delta < -180.0) delta += 360.0
                    onYawRateUpdate?.invoke(delta / dt)
                }
            }
            lastUiYawDeg = yaw; lastUiYawTime = now
        }
    }

    private fun startWatchdog() {
        stopWatchdog()
        lastCmdSendTime = System.currentTimeMillis()
        watchdogTimer = Timer("FlightWatchdog").also { t ->
            t.scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    if (taskStateRef.get() == TaskState.LANDING) {
                        val delay = System.currentTimeMillis() - lastCmdSendTime
                        if (delay > 2500L) {
                            dispatchError("💀 看门狗: 控制线程死锁超时 ($delay ms)")
                            stopMission("通信阻塞超时")
                        }
                    }
                }
            }, 200, 100)
        }
    }

    private fun stopWatchdog() { watchdogTimer?.cancel(); watchdogTimer = null }

    private fun rotateGimbal(pitchDeg: Double) {
        runCatching { KeyManager.getInstance().setValue(KeyTools.createKey(GimbalKey.KeyGimbalMode, currentCameraIndex), GimbalMode.YAW_FOLLOW, null) }
        val rotation = GimbalAngleRotation().apply {
            mode = GimbalAngleRotationMode.ABSOLUTE_ANGLE
            pitch = if (pitchDeg <= -90.0) -87.0 else pitchDeg
            duration = 1.0
        }
        runCatching { KeyManager.getInstance().performAction(KeyTools.createKey(GimbalKey.KeyRotateByAngle, currentCameraIndex), rotation, null) }
    }
}