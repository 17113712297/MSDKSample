package com.example.msdksample

import android.os.Handler
import android.os.SystemClock
import dji.sdk.keyvalue.value.common.EmptyMsg
import android.os.HandlerThread
import android.util.Log
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.GimbalKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.camera.CameraVideoStreamSourceType
import dji.sdk.keyvalue.value.common.Velocity3D
import dji.sdk.keyvalue.value.flightcontroller.FlightCoordinateSystem
import dji.sdk.keyvalue.value.flightcontroller.FlightMode
import dji.sdk.keyvalue.value.flightcontroller.FlightControlAuthorityChangeReason
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
import dji.v5.manager.aircraft.virtualstick.VirtualStickState
import dji.v5.manager.aircraft.virtualstick.VirtualStickStateListener
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
    // 对外暴露的回调接口
    // =========================================================================
    var onTaskStateChanged: ((TaskState) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onMessage: ((String) -> Unit)? = null
    var onSpeedUpdate: ((velX: Double, velY: Double, velZ: Double) -> Unit)? = null
    var onYawRateUpdate: ((yawRate: Double) -> Unit)? = null
    var onBatteryUpdate: ((pct: Int) -> Unit)? = null
    var onLandingSucceed: (() -> Unit)? = null

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

    private enum class MissionState { IDLE, SEARCHING, ALIGN_YAW, LANDING, FINAL_LANDING, WAIT_MOTORS_OFF }

    private fun isFinalPhase(): Boolean = missionState == MissionState.FINAL_LANDING ||
        missionState == MissionState.WAIT_MOTORS_OFF

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

    // 追踪进入最终阶段和超声波丢帧的绝对生命周期
    @Volatile private var enterLandingStateTimeMs = 0L
    private var ultrasonicInvalidFrameCount = 0

    @Volatile private var lastKnownFlightMode: String = ""
    private val flightModeListener = object : CommonCallbacks.KeyListener<FlightMode> {
        override fun onValueChange(oldValue: FlightMode?, newValue: FlightMode?) {
            val name = newValue?.name ?: ""
            lastKnownFlightMode = name
            if (taskStateRef.get() != TaskState.INACTIVE) {
                val finalModeAllowed = isFinalPhase() &&
                    (name == "AUTO_LANDING" || name == "LANDING")
                // 正常触地也会退出 AUTO_LANDING，不能仅凭模式变化取消着陆。
                if (!finalModeAllowed && !isAllowedFlightMode(name)) {
                    Log.w(TAG, "🛑 检测到非白名单模式 ($name),判定为飞手接管")
                    stopMission("飞手切挡接管 ($name)")
                }
            }
        }
    }

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
    private var handoffStableSinceMs = 0L
    private var finalLandingStartedMs = 0L
    private var finalLandingAccepted = false
    private var confirmationPending = false
    private var lastFinalStatusLogMs = 0L
    private var finalCompleteSinceMs = 0L
    private var motorsWaitStartedMs = 0L
    private var finalWarningSent = false
    private var motorWarningSent = false
    private var confirmationAttempts = 0
    private var confirmationRequestedMs = 0L
    private var confirmationExhaustedWarning = false
    private var observedAutoLanding = false
    private var autoLandingMissingSinceMs = 0L
    private var descentProgressSinceMs = 0L
    private var descentProgressDepth = Double.NaN
    @Volatile private var missionGeneration = 0
    @Volatile private var stopping = false
    @Volatile private var enablePending = false
    private var stopStickConfirmed = false
    private var stopLandingConfirmed = false
    private var stopDisablePending = false
    private var stopDisableAgain = false
    private var stopCancelPending = false
    private var stopStartedMs = 0L
    private var observedStickEnabled: Boolean? = null
    private var observedStickAtMs = 0L
    private val stickStateListener = object : VirtualStickStateListener {
        override fun onVirtualStickStateUpdate(stickState: VirtualStickState) {
            synchronized(this@LandingController) {
                observedStickEnabled = stickState.isVirtualStickEnable
                observedStickAtMs = SystemClock.elapsedRealtime()
                if (stopping) {
                    stopStickConfirmed = !stickState.isVirtualStickEnable
                    refreshStopCompletion()
                }
            }
        }

        override fun onChangeReasonUpdate(reason: FlightControlAuthorityChangeReason) {
            Log.i(TAG, "控制权变化原因: $reason")
        }
    }

    private var lastUiYawDeg  = Double.NaN
    private var lastUiYawTime = 0L

    // 线程、Runnable 与看门狗
    private val controlThread = HandlerThread("FlightControlThread", android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
    private val controlHandler: Handler

    @Volatile private var lastCmdSendTime = 0L
    private var watchdogTimer: Timer? = null

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
    // 初始化块
    // =========================================================================
    init {
        controlThread.start()
        controlHandler = Handler(controlThread.looper)
        controlHandler.post(telemetryRunnable)
        runCatching {
            KeyManager.getInstance().listen(KeyTools.createKey(FlightControllerKey.KeyFlightMode), this, flightModeListener)
        }
        runCatching { VirtualStickManager.getInstance().setVirtualStickStateListener(stickStateListener) }
            .onFailure { Log.w(TAG, "无法监听虚拟摇杆状态，释放恢复仅依赖接口应答", it) }
    }

    // =========================================================================
    // 公开 API
    // =========================================================================
    fun getTaskState(): TaskState = taskStateRef.get()

    fun updateVisionData(id: Int, errX: Double, errY: Double, depthZ: Double, yawDeg: Double, timestamp: Long) {
        if (taskStateRef.get() != TaskState.INACTIVE) {
            visionRef.set(VisionMeasurement(id, errX, errY, depthZ, yawDeg, timestamp))
        }
    }

    fun invalidateVisionData() {
        visionRef.set(VisionMeasurement())
    }

    @Synchronized
    fun startVisionLanding() {
        if (stopping) {
            refreshStopCompletion()
            if (stopping) {
                val km = KeyManager.getInstance()
                val mode = km.getValue(KeyTools.createKey(FlightControllerKey.KeyFlightMode))?.name.orEmpty()
                if (km.getValue(KeyTools.createKey(FlightControllerKey.KeyConnection)) != true || !isNormalFlightMode(mode)) {
                    onError?.invoke("上次中止尚未确认释放，请保持连接并切回 N 挡后再次点击")
                } else if (enablePending || stopDisablePending || stopCancelPending) {
                    onError?.invoke("仍在等待控制权操作应答，暂不能重新降落；请保持人工控制")
                } else {
                    onMessage?.invoke("正在重新确认释放控制权，完成后请再次点击降落")
                    requestStopRelease(missionGeneration)
                }
                return
            }
        }
        if (enablePending) {
            onError?.invoke("正在等待控制权接管应答，请勿重复启动")
            return
        }
        if (taskStateRef.get() != TaskState.INACTIVE) return
        if (DroneControlService.isVelocityPanelActive) {
            onError?.invoke("请先停止手动速度控制，再启动视觉降落")
            return
        }
        DualMarkerLandingConfig.landingError()?.let {
            onError?.invoke("双码降落参数未完成: $it")
            return
        }
        if (!isWideCameraSelected()) {
            onError?.invoke("视觉降落需要广角相机，请先切换广角并等待画面稳定")
            return
        }
        val generation = ++missionGeneration
        setTaskState(TaskState.LANDING_PREP)

        controlHandler.post {
            try {
                if (generation != missionGeneration) return@post
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
                if (!isNormalFlightMode(modeName)) {
                    dispatchError("⚠️ 拦截: 请切入 N 挡! (当前:$modeName)")
                    return@post
                }
                lastKnownFlightMode = modeName

                resetControlState()
                DroneControlService.onPrepareVisualLanding?.invoke()
                rotateGimbal(-90.0)

                enablePending = true
                observedStickEnabled = null
                observedStickAtMs = 0L
                VirtualStickManager.getInstance().enableVirtualStick(object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        synchronized(this@LandingController) {
                        enablePending = false
                        if (generation != missionGeneration || taskStateRef.get() != TaskState.LANDING_PREP) {
                            // 被中止的接管请求晚到：先清除旧释放证据，再关闭，不能让新任务抢先启动。
                            stopStickConfirmed = false
                            observedStickEnabled = null
                            observedStickAtMs = 0L
                            stopDisableAgain = true
                            requestStopRelease(missionGeneration)
                            return
                        }
                        VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(true)
                        isVirtualStickActive = true
                        landingStartTimeMs = SystemClock.elapsedRealtime()
                        missionState = MissionState.SEARCHING
                        setTaskState(TaskState.LANDING)
                        startWatchdog()

                        controlHandler.removeCallbacks(flightControlRunnable)
                        controlHandler.post(flightControlRunnable)
                        Log.i(TAG, "✅ 虚拟摇杆已开启, SEARCHING 开始")
                        }
                    }

                    override fun onFailure(error: IDJIError) {
                        synchronized(this@LandingController) {
                        enablePending = false
                        if (generation == missionGeneration)
                            dispatchError("🛑 接管被拒: [${error.errorCode()}] ${error.description()}")
                        else refreshStopCompletion()
                        }
                    }
                })
            } catch (e: Exception) {
                enablePending = false
                Log.e(TAG, "启动降落异常", e)
                dispatchError("❌ 启动崩溃: ${e.message}")
            }
        }
    }

    @Synchronized
    fun stopMission(reason: String, completed: Boolean = false) {
        val prev = taskStateRef.getAndSet(TaskState.INACTIVE)
        if (prev == TaskState.INACTIVE) return
        stopping = true
        stopStartedMs = SystemClock.elapsedRealtime()
        val wasFinalLanding = isFinalPhase()
        stopStickConfirmed = false
        stopLandingConfirmed = !wasFinalLanding || completed
        stopDisablePending = false
        stopDisableAgain = false
        stopCancelPending = false
        missionGeneration++
        // 先切断后续喂帧，再请求零速度，最后释放 SDK 控制权。
        controlHandler.removeCallbacks(flightControlRunnable)
        sendZeroVelocity()
        visionRef.set(VisionMeasurement())
        Log.w(TAG, "🔴 停止任务: $reason (prev=$prev)")
        missionState          = MissionState.IDLE
        targetLockedYaw       = Double.NaN
        alignYawStartTimeMs   = 0L
        validVisionFrameCount = 0
        stopWatchdog()
        isVirtualStickActive = false
        landingStartTimeMs = 0L
        enterLandingStateTimeMs = 0L

        onTaskStateChanged?.invoke(TaskState.INACTIVE)
        onMessage?.invoke("🔴 已退出: $reason")

        controlHandler.removeCallbacks(flightControlRunnable)
        requestStopRelease(missionGeneration)
    }

    /** 只能根据成功应答或近期 SDK 状态解除中止锁；切回 N 挡本身不是释放证据。 */
    @Synchronized
    private fun refreshStopCompletion() {
        if (!stopping || enablePending || stopDisablePending || stopCancelPending) return
        if (stopDisableAgain) {
            requestStopRelease(missionGeneration)
            return
        }
        val km = KeyManager.getInstance()
        if (runCatching { km.getValue(KeyTools.createKey(FlightControllerKey.KeyConnection)) }.getOrNull() != true) {
            stopStickConfirmed = false
            observedStickEnabled = null
            observedStickAtMs = 0L
            return
        }
        if (observedStickAtMs >= stopStartedMs && SystemClock.elapsedRealtime() - observedStickAtMs <= 2000L &&
            observedStickEnabled == false) stopStickConfirmed = true
        if (!stopLandingConfirmed &&
            runCatching { km.getValue(KeyTools.createKey(FlightControllerKey.KeyIsInLandingMode)) }.getOrNull() == false) {
            stopLandingConfirmed = true
        }
        if (stopStickConfirmed && stopLandingConfirmed) {
            stopping = false
            onMessage?.invoke("已确认控制权释放；切回 N 挡后可手动重新发起降落")
        }
    }

    @Synchronized
    private fun requestStopRelease(generation: Int) {
        if (!stopping || generation != missionGeneration || stopDisablePending || stopCancelPending) return
        // 先标记全部请求，防止同步回调提前解锁。旧任务回调不得改变新任务的锁。
        stopDisablePending = true
        stopDisableAgain = false
        stopCancelPending = !stopLandingConfirmed
        if (stopCancelPending) {
            runCatching {
                KeyManager.getInstance().performAction(KeyTools.createKey(FlightControllerKey.KeyStopAutoLanding), null,
                    object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                        override fun onSuccess(value: EmptyMsg?) {
                            synchronized(this@LandingController) {
                                if (generation != missionGeneration) return
                                stopCancelPending = false
                                stopLandingConfirmed = true
                                refreshStopCompletion()
                            }
                        }
                        override fun onFailure(error: IDJIError) {
                            synchronized(this@LandingController) {
                                if (generation != missionGeneration) return
                                stopCancelPending = false
                                Log.w(TAG, "取消自动降落失败: ${error.description()}")
                                refreshStopCompletion()
                                if (stopping) onError?.invoke("取消降落未确认，请人工接管；回 N 挡后可点击降落重试释放")
                            }
                        }
                    })
            }.onFailure {
                stopCancelPending = false
                onError?.invoke("无法取消自动降落，请使用遥控器接管")
            }
        }
        // 高级模式设置失败不能阻止后续关闭虚拟摇杆请求。
        runCatching { VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(false) }
            .onFailure { Log.w(TAG, "关闭高级模式异常，仍尝试释放虚拟摇杆", it) }
            runCatching {
                VirtualStickManager.getInstance().disableVirtualStick(object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        synchronized(this@LandingController) {
                            if (generation != missionGeneration) return
                            stopDisablePending = false
                            stopStickConfirmed = true
                            refreshStopCompletion()
                        }
                    }
                    override fun onFailure(error: IDJIError) {
                        synchronized(this@LandingController) {
                            if (generation != missionGeneration) return
                            stopDisablePending = false
                            Log.w(TAG, "摇杆释放接口失败: [${error.errorCode()}] ${error.description()}")
                            refreshStopCompletion()
                            if (stopping) onError?.invoke("控制权释放尚未确认，请人工接管；回 N 挡后可点击降落重试释放")
                        }
                    }
                })
            }.onFailure {
                stopDisablePending = false
                refreshStopCompletion()
                if (stopping) onError?.invoke("控制权释放异常，请人工接管；回 N 挡后可点击降落重试释放")
            }
    }

    fun release() {
        stopMission("Controller Released")
        runCatching { KeyManager.getInstance().cancelListen(KeyTools.createKey(FlightControllerKey.KeyFlightMode), this) }
        runCatching { VirtualStickManager.getInstance().removeVirtualStickStateListener(stickStateListener) }
        controlHandler.removeCallbacksAndMessages(null)
        controlThread.quitSafely()
    }

    private fun setTaskState(newState: TaskState) {
        taskStateRef.set(newState)
        onTaskStateChanged?.invoke(newState)
    }

    private fun dispatchError(msg: String) {
        stopMission(msg)
        onError?.invoke(msg)
    }

    private fun isNormalFlightMode(name: String): Boolean =
        name == "NORMAL" || name == "GPS_NORMAL" || name == "POSITION_CTRL"

    private fun isAllowedFlightMode(name: String): Boolean {
        return isNormalFlightMode(name) ||
                name == "JOYSTICK" || name == "VIRTUAL_STICK"
    }

    private fun sendZeroVelocity() {
        cmdPitch = 0.0; cmdRoll = 0.0; cmdYaw = 0.0; cmdThrottle = 0.0
        if (!isVirtualStickActive) return
        runCatching {
            VirtualStickManager.getInstance().sendVirtualStickAdvancedParam(VirtualStickFlightControlParam().apply {
                rollPitchControlMode = RollPitchControlMode.VELOCITY
                yawControlMode = YawControlMode.ANGULAR_VELOCITY
                verticalControlMode = VerticalControlMode.VELOCITY
                rollPitchCoordinateSystem = FlightCoordinateSystem.BODY
                roll = 0.0; pitch = 0.0; yaw = 0.0; verticalThrottle = 0.0
            })
        }.onFailure { Log.w(TAG, "零速度请求未成功，继续释放控制权", it) }
    }

    private fun resetControlState() {
        targetLockedYaw     = Double.NaN
        alignYawStartTimeMs = 0L
        cmdPitch = 0.0; cmdRoll = 0.0; cmdYaw = 0.0; cmdThrottle = 0.0
        visionRef.set(VisionMeasurement())
        validVisionFrameCount = 0
        lastValidVisionTimeMs = 0L
        enterLandingStateTimeMs = 0L
        ultrasonicInvalidFrameCount = 0
        handoffStableSinceMs = 0L
        lastFinalStatusLogMs = 0L
        finalLandingAccepted = false
        confirmationPending = false
        finalCompleteSinceMs = 0L
        motorsWaitStartedMs = 0L
        finalWarningSent = false
        motorWarningSent = false
        confirmationAttempts = 0
        confirmationRequestedMs = 0L
        confirmationExhaustedWarning = false
        observedAutoLanding = false
        autoLandingMissingSinceMs = 0L
        descentProgressSinceMs = 0L
        descentProgressDepth = Double.NaN
    }

    private fun triggerFinalLanding(reason: String = "已到机巢交接间隙并稳定对中") {
        if (taskStateRef.get() != TaskState.LANDING || isFinalPhase()) return
        Log.i(TAG, "交给 DJI 自动降落: $reason")
        sendZeroVelocity()
        missionState = MissionState.FINAL_LANDING
        finalLandingStartedMs = SystemClock.elapsedRealtime()
        finalLandingAccepted = false
        stopWatchdog()
        val generation = missionGeneration
        VirtualStickManager.getInstance().setVirtualStickAdvancedModeEnabled(false)
        VirtualStickManager.getInstance().disableVirtualStick(object : CommonCallbacks.CompletionCallback {
            override fun onSuccess() {
                controlHandler.post {
                    if (generation != missionGeneration || missionState != MissionState.FINAL_LANDING) return@post
                    isVirtualStickActive = false
                    KeyManager.getInstance().performAction(
                        KeyTools.createKey(FlightControllerKey.KeyStartAutoLanding), null,
                        object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                            override fun onSuccess(value: EmptyMsg?) {
                                controlHandler.post {
                                    if (generation == missionGeneration) {
                                        finalLandingAccepted = true
                                    } else {
                                        KeyManager.getInstance().performAction(KeyTools.createKey(FlightControllerKey.KeyStopAutoLanding), null)
                                    }
                                }
                            }
                            override fun onFailure(error: IDJIError) {
                                controlHandler.post {
                                    if (generation == missionGeneration) stopMission("自动降落请求失败: ${error.description()}")
                                }
                            }
                        })
                }
            }
            override fun onFailure(error: IDJIError) {
                controlHandler.post {
                    if (generation == missionGeneration) stopMission("虚拟摇杆释放失败: ${error.description()}")
                }
            }
        })
    }

    /** 飞控负责着陆和停桨；完成必须同时满足非飞行和电机关闭。 */
    private fun monitorFinalLanding(now: Long) {
        val km = KeyManager.getInstance()
        val flying = km.getValue(KeyTools.createKey(FlightControllerKey.KeyIsFlying))
        val motorsOn = km.getValue(KeyTools.createKey(FlightControllerKey.KeyAreMotorsOn))
        val inLandingMode = km.getValue(KeyTools.createKey(FlightControllerKey.KeyIsInLandingMode))
        val needsConfirmation = km.getValue(KeyTools.createKey(FlightControllerKey.KeyIsLandingConfirmationNeeded))
        if (now - lastFinalStatusLogMs >= 1000L) {
            lastFinalStatusLogMs = now
            Log.i(TAG, "最终状态: phase=$missionState flying=$flying motorsOn=$motorsOn landing=$inLandingMode confirm=$needsConfirmation attempts=$confirmationAttempts ultraM=${aircraftStateRef.get().ultrasonicHeight}")
        }

        if (finalLandingAccepted && flying == false && motorsOn == false) {
            if (finalCompleteSinceMs == 0L) finalCompleteSinceMs = now
            if (now - finalCompleteSinceMs >= 1000L) {
                stopMission("已确认着陆机巢且电机停转", completed = true)
                onLandingSucceed?.invoke()
            }
            return
        }
        finalCompleteSinceMs = 0L

        if (!finalLandingAccepted) {
            if (now - finalLandingStartedMs > DualMarkerLandingConfig.FINAL_COMMAND_TIMEOUT_MS)
                stopMission("自动降落交接未获得应答，请人工接管")
            return
        }
        if (flying == false) {
            missionState = MissionState.WAIT_MOTORS_OFF
            if (motorsWaitStartedMs == 0L) motorsWaitStartedMs = now
            if (!motorWarningSent && now - motorsWaitStartedMs >= DualMarkerLandingConfig.MOTOR_OFF_WARNING_MS) {
                motorWarningSent = true
                onError?.invoke("已报告触地但尚未确认停桨，请检查机巢接触状态并人工接管；不会按超时强制关电机")
            }
            // 触地不代表确认流程已结束，仍允许处理飞控明确提出的确认请求。
        } else if (flying == true) {
            missionState = MissionState.FINAL_LANDING
            motorsWaitStartedMs = 0L
        }
        if (!finalWarningSent && now - finalLandingStartedMs > DualMarkerLandingConfig.FINAL_STATUS_WARNING_MS) {
            finalWarningSent = true
            onError?.invoke("最终着陆/停桨超过 30 秒，继续监视飞控状态；不会仅因计时到期取消降落")
        }

        if (inLandingMode == true) {
            observedAutoLanding = true
            autoLandingMissingSinceMs = 0L
        } else if (inLandingMode == false && flying == true && needsConfirmation != true) {
            if (autoLandingMissingSinceMs == 0L) autoLandingMissingSinceMs = now
            val grace = if (observedAutoLanding) 1500L else DualMarkerLandingConfig.FINAL_COMMAND_TIMEOUT_MS
            if (now - autoLandingMissingSinceMs >= grace) {
                stopMission("飞控未进入或已退出自动降落，停止自动重试，请人工接管")
                return
            }
        } else {
            autoLandingMissingSinceMs = 0L
        }
        if (confirmationPending) {
            if (now - confirmationRequestedMs > DualMarkerLandingConfig.FINAL_COMMAND_TIMEOUT_MS && !confirmationExhaustedWarning) {
                confirmationExhaustedWarning = true
                onError?.invoke("确认降落应答超时，停止重复发送，请人工确认飞机状态")
            }
            return
        }
        if (needsConfirmation != true || now - confirmationRequestedMs < DualMarkerLandingConfig.FINAL_CONFIRM_RETRY_MS) return
        if (confirmationAttempts >= DualMarkerLandingConfig.MAX_CONFIRM_ATTEMPTS) {
            if (!confirmationExhaustedWarning) {
                confirmationExhaustedWarning = true
                onError?.invoke("飞控仍要求确认降落，自动确认重试已达上限，请人工接管")
            }
            return
        }
        confirmationPending = true
        confirmationRequestedMs = now
        confirmationAttempts++
        val attempt = confirmationAttempts
        val generation = missionGeneration
        km.performAction(KeyTools.createKey(FlightControllerKey.KeyConfirmLanding), null,
            object : CommonCallbacks.CompletionCallbackWithParam<EmptyMsg> {
                override fun onSuccess(value: EmptyMsg?) {
                    controlHandler.post {
                        if (generation == missionGeneration && isFinalPhase()) {
                            confirmationPending = false
                            Log.i(TAG, "确认降落请求已接受，第 $attempt 次；继续等待实际着陆和停桨")
                        }
                    }
                }
                override fun onFailure(error: IDJIError) {
                    controlHandler.post {
                        if (generation == missionGeneration && isFinalPhase()) {
                            confirmationPending = false
                            Log.w(TAG, "确认降落失败，第 $attempt 次: ${error.description()}")
                            // 仅飞控仍明确要求确认时有限重试，不将失败自动转成取消降落。
                        }
                    }
                }
            })
    }

    @Synchronized
    private fun executeLandingStateMachine() {
        if (taskStateRef.get() != TaskState.LANDING) return
        val state = aircraftStateRef.get()
        val now = SystemClock.elapsedRealtime()

        val km = KeyManager.getInstance()
        if (km.getValue(KeyTools.createKey(FlightControllerKey.KeyConnection)) != true) {
            stopMission("飞机连接丢失"); return
        }
        val modeNow = km.getValue(KeyTools.createKey(FlightControllerKey.KeyFlightMode))?.name.orEmpty()
        val allowed = isAllowedFlightMode(modeNow) || (isFinalPhase() &&
            (modeNow == "AUTO_LANDING" || modeNow == "LANDING"))
        if (!allowed) { stopMission("切出 N 档或模式不可用 ($modeNow)"); return }
        if (isFinalPhase()) { monitorFinalLanding(now); return }
        if (!isWideCameraSelected()) {
            stopMission("视觉降落镜头已切换或状态未知，请切回广角后重新启动"); return
        }
        if (now - landingStartTimeMs > 120_000L) {
            stopMission("视觉降落任务超时，交回人工控制"); return
        }
        if (!state.isFlying) { stopMission("视觉交接前飞行状态终止，请检查是否触地及电机状态"); return }
        if (!state.yaw.isFinite() || !state.pitch.isFinite() || !state.roll.isFinite() || !state.velZ.isFinite()) {
            stopMission("姿态或速度数据无效"); return
        }
        if (abs(state.pitch) > TILT_LIMIT_DEG || abs(state.roll) > TILT_LIMIT_DEG) { stopMission("姿态越界(防侧翻)"); return }
        if (!isVirtualStickActive) { stopMission("虚拟摇杆未激活"); return }

        val curMode = lastKnownFlightMode
        if (curMode.isNotEmpty() && !isAllowedFlightMode(curMode)) {
            stopMission("飞手切挡接管 ($curMode)")
            return
        }

        val v = visionRef.get()
        val isFrameValid = LandingVisionPolicy.isFresh(now, v.timestamp,
            v.targetId != -1 && v.errX.isFinite() && v.errY.isFinite() &&
                v.depthZ.isFinite() && v.yawDeg.isFinite(), VISION_HOLD_MS)
        val isNewFrame = isFrameValid && v.timestamp > lastValidVisionTimeMs
        if (isNewFrame) {
            if (lastValidVisionTimeMs == 0L || v.timestamp - lastValidVisionTimeMs > VISION_HOLD_MS)
                validVisionFrameCount = 0
            lastValidVisionTimeMs = v.timestamp
            validVisionFrameCount = min(validVisionFrameCount + 1, REQUIRED_STABLE_FRAMES + 1)
        }
        val neverSeenTarget = lastValidVisionTimeMs == 0L
        val timeSinceLastValidMs = if (neverSeenTarget) now - landingStartTimeMs else now - lastValidVisionTimeMs

        var tPitch = 0.0; var tRoll = 0.0; var tYaw = 0.0; var tThrottle = 0.0

        when {
            neverSeenTarget && timeSinceLastValidMs > INITIAL_SEARCH_TIMEOUT_MS -> { stopMission("初始搜索超时"); return }
            !neverSeenTarget && timeSinceLastValidMs > VISION_STALE_MS -> { stopMission("目标丢失超时"); return }
            !isFrameValid -> {
                validVisionFrameCount = 0
                handoffStableSinceMs = 0L
                descentProgressSinceMs = 0L
                // 旧测量失效后立即发零速度；不沿用上一帧下降速度做渐变。
                cmdPitch = 0.0; cmdRoll = 0.0; cmdYaw = 0.0; cmdThrottle = 0.0
            }
            else -> {
                when (missionState) {
                    MissionState.SEARCHING -> {
                        if (validVisionFrameCount >= REQUIRED_STABLE_FRAMES) {
                            missionState = MissionState.ALIGN_YAW
                            alignYawStartTimeMs = now
                        }
                    }
                    MissionState.ALIGN_YAW -> {
                        if (now - alignYawStartTimeMs > ALIGN_YAW_TIMEOUT_MS) {
                            stopMission("双码航向对齐超时")
                            return
                        } else if (!v.yawDeg.isNaN() && abs(v.yawDeg) < ALIGN_YAW_THRESHOLD_DEG) {
                            targetLockedYaw = state.yaw
                            missionState = MissionState.LANDING
                            enterLandingStateTimeMs = now
                        } else if (!v.yawDeg.isNaN()) {
                            val rawYaw = KP_YAW * v.yawDeg
                            tYaw = if (abs(rawYaw) < MIN_YAW_VEL) (if (rawYaw > 0) MIN_YAW_VEL else -MIN_YAW_VEL) else rawYaw.coerceIn(-MAX_YAW_VEL, MAX_YAW_VEL)
                        }
                        if (now - lastAlignYawLogTime > 200L) { lastAlignYawLogTime = now; Log.d(TAG, "🧭 ALIGN_YAW tYaw=${tYaw}") }
                    }
                    MissionState.LANDING -> {
                        if (enterLandingStateTimeMs == 0L) enterLandingStateTimeMs = now

                        if (targetLockedYaw.isNaN()) targetLockedYaw = state.yaw

                        var yawErr = state.yaw - targetLockedYaw
                        if (yawErr > 180.0) yawErr -= 360.0
                        if (yawErr < -180.0) yawErr += 360.0
                        tYaw = (-1.5 * yawErr).coerceIn(-MAX_YAW_VEL, MAX_YAW_VEL)

                        val errForwardCG = -v.errY + DualMarkerLandingConfig.cameraForwardM!!
                        val errRightCG = v.errX + DualMarkerLandingConfig.cameraRightM!!
                        val radialErr = hypot(errForwardCG, errRightCG)
                        var pPitch = KP_XY * errRightCG
                        var pRoll = KP_XY * errForwardCG
                        val vel2d = hypot(pPitch, pRoll)
                        if (vel2d > MAX_XY_VEL) {
                            val scale = MAX_XY_VEL / vel2d
                            pPitch *= scale; pRoll *= scale
                        }
                        tPitch = pPitch; tRoll = pRoll

                        // 统一使用相机到板面的视觉深度，不能与地面超声高度混用。
                        val depth = v.depthZ
                        val aligned = radialErr <= DualMarkerLandingConfig.ALIGN_TOLERANCE_M &&
                            abs(v.yawDeg) < ALIGN_YAW_THRESHOLD_DEG
                        val stable = validVisionFrameCount >= REQUIRED_STABLE_FRAMES
                        if (stable && abs(v.yawDeg) >= ALIGN_YAW_THRESHOLD_DEG) {
                            sendZeroVelocity()
                            handoffStableSinceMs = 0L
                            descentProgressSinceMs = 0L
                            missionState = MissionState.ALIGN_YAW
                            alignYawStartTimeMs = now
                            return
                        }
                        val handoffDepth = DualMarkerLandingConfig.handoffDepthM!!
                        val nestClearance = DualMarkerLandingConfig.nestClearanceM(depth)!!
                        // 保留所有交接前的水平纠偏。低空滞留只触发飞控接管，不认定触地。
                        val fallbackWindow = aligned && stable && nestClearance >= -0.03 &&
                            nestClearance <= DualMarkerLandingConfig.handoffClearanceM!! +
                                DualMarkerLandingConfig.LOW_ALTITUDE_FALLBACK_MARGIN_M && depth > handoffDepth
                        if (fallbackWindow && isNewFrame) {
                            if (descentProgressSinceMs == 0L ||
                                descentProgressDepth - depth >= DualMarkerLandingConfig.MIN_DESCENT_PROGRESS_M) {
                                descentProgressSinceMs = now
                                descentProgressDepth = depth
                            } else if (now - descentProgressSinceMs >= DualMarkerLandingConfig.DESCENT_STALL_MS) {
                                triggerFinalLanding("巢顶附近对中且下降 3.5 秒无有效进展，当前起落架间隙约 ${nestClearance}m")
                                return
                            }
                        } else if (!fallbackWindow) {
                            descentProgressSinceMs = 0L
                        }
                        if (LandingVisionPolicy.canHandoff(depth, handoffDepth, radialErr, v.yawDeg, stable)) {
                            if (handoffStableSinceMs == 0L) handoffStableSinceMs = now
                            if (isNewFrame && now - handoffStableSinceMs >= 500L) {
                                triggerFinalLanding()
                                return
                            }
                        } else {
                            handoffStableSinceMs = 0L
                        }
                        // 达到交接高度但未对中时只修正水平位置，不继续盲降。
                        if (aligned && stable && depth > handoffDepth) {
                            val desiredVelZ = -((depth - handoffDepth) * 0.20).coerceIn(0.05, 0.20)
                            val currentVelZUp = -state.velZ
                            tThrottle = (desiredVelZ + KP_Z_VEL * (desiredVelZ - currentVelZUp))
                                .coerceIn(MAX_DESCEND_VEL, 0.0)
                        } else {
                            cmdThrottle = 0.0
                        }
                    }
                    else -> {}
                }
            }
        }

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
            lastCmdSendTime = SystemClock.elapsedRealtime()
        }
    }

    private fun accelLimit(current: Double, target: Double, maxAccel: Double): Double {
        val maxDelta = maxAccel * DT
        return current + (target - current).coerceIn(-maxDelta, maxDelta)
    }

    private fun pollFlightStatusSync() {
        runCatching {
            val km = KeyManager.getInstance()
            val prev = aircraftStateRef.get()
            val isFlying = km.getValue(KeyTools.createKey(FlightControllerKey.KeyIsFlying)) ?: prev.isFlying
            val alt = km.getValue(KeyTools.createKey(FlightControllerKey.KeyAltitude))?.toDouble() ?: prev.altitude
            // DJI KeyUltrasonicHeight 单位为分米；仅作遥测，不用它推断回波命中了巢顶还是地面。
            val ultra = km.getValue(KeyTools.createKey(FlightControllerKey.KeyUltrasonicHeight))?.toDouble()?.div(10.0)
            val att = km.getValue(KeyTools.createKey(FlightControllerKey.KeyAircraftAttitude))
            val vel = km.getValue(KeyTools.createKey(FlightControllerKey.KeyAircraftVelocity))
            val mode = km.getValue(KeyTools.createKey(FlightControllerKey.KeyFlightMode))?.name
            if (!mode.isNullOrEmpty()) lastKnownFlightMode = mode

            // 维护超声波无效帧计数器
            if (ultra == null || ultra <= 0.0) {
                ultrasonicInvalidFrameCount = min(ultrasonicInvalidFrameCount + 1, 20)
            } else {
                ultrasonicInvalidFrameCount = 0
            }

            aircraftStateRef.set(AircraftState(
                isFlying = isFlying, altitude = alt,
                ultrasonicHeight = if (ultra != null && ultra > 0.0) ultra else Double.NaN,
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
            val now = SystemClock.elapsedRealtime()
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
        lastCmdSendTime = SystemClock.elapsedRealtime()
        watchdogTimer = Timer("FlightWatchdog").also { t ->
            t.scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    if (taskStateRef.get() == TaskState.LANDING) {
                        val delay = SystemClock.elapsedRealtime() - lastCmdSendTime
                        if (delay > 2500L) {
                            stopMission("通信阻塞超时 ($delay ms)")
                        }
                    }
                }
            }, 200, 100)
        }
    }

    private fun stopWatchdog() { watchdogTimer?.cancel(); watchdogTimer = null }

    private fun isWideCameraSelected(): Boolean = runCatching {
        KeyManager.getInstance().getValue(
            KeyTools.createKey(CameraKey.KeyCameraVideoStreamSource, currentCameraIndex)
        ) == CameraVideoStreamSourceType.WIDE_CAMERA
    }.getOrDefault(false)

    private fun rotateGimbal(pitchDeg: Double) {
        runCatching { KeyManager.getInstance().setValue(KeyTools.createKey(GimbalKey.KeyGimbalMode, currentCameraIndex), GimbalMode.YAW_FOLLOW, null) }
        val rotation = GimbalAngleRotation().apply {
            mode = GimbalAngleRotationMode.ABSOLUTE_ANGLE
            // M4T 支持 -90°。沿用 -87° 会给朝下模型引入随高度变化的前后偏差。
            pitch = pitchDeg.coerceIn(-90.0, 70.0)
            duration = 1.0
        }
        runCatching { KeyManager.getInstance().performAction(KeyTools.createKey(GimbalKey.KeyRotateByAngle, currentCameraIndex), rotation, null) }
    }
}
