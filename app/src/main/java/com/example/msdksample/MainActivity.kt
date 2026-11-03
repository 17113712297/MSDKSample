package com.example.msdksample

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.graphics.Rect
import android.text.InputType
import com.example.msdksample.logs.OperationLogger
import com.example.msdksample.logs.OperationLogDialog
import com.example.msdksample.health.MotorTemperatureMonitor
import com.example.msdksample.health.MotorTemperaturePolicy
import com.example.msdksample.ui.FlightMenuController
import com.example.msdksample.ui.OperationWorkspaceController
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.msdksample.devicereport.DeviceStatusReportManager
import com.example.msdksample.transfer.VideoTransferManager
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.camera.CameraVideoStreamSourceType
import dji.sdk.keyvalue.value.common.Attitude
import dji.sdk.keyvalue.value.common.CameraLensType
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.sdk.keyvalue.value.common.Velocity3D
import dji.sdk.keyvalue.value.flightcontroller.LowBatteryRTHInfo
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import dji.v5.ux.cameracore.widget.cameracapture.recordvideo.RecordVideoWidget
import dji.v5.ux.cameracore.widget.cameracapture.shootphoto.ShootPhotoWidget
import dji.v5.ux.cameracore.widget.cameracontrols.photovideoswitch.PhotoVideoSwitchWidget
import dji.v5.ux.core.util.DataProcessor
import dji.v5.ux.core.widget.fpv.FPVStreamSourceListener
import dji.v5.ux.core.widget.fpv.FPVWidget
import dji.v5.ux.visualcamera.zoom.FocalZoomWidget
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.CompositeDisposable
import org.opencv.android.OpenCVLoader
import android.os.SystemClock
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        const val TAG = "DBG_CAM"
        const val TAG_SDK = "DBG_SDK"
        const val REATTACH_DELAY = 3_000L
        const val POLL_INTERVAL = 500L
        const val AUTO_STREAM_RETRY_DELAY_MS = 3_000L
        const val AUTO_STREAM_MAX_ATTEMPTS = 10
    }

    private lateinit var fpvWidget: FPVWidget
    private lateinit var shootPhotoWidget: ShootPhotoWidget
    private lateinit var recordVideoWidget: RecordVideoWidget
    private lateinit var focalZoomWidget: FocalZoomWidget
    private lateinit var photoVideoSwitchWidget: PhotoVideoSwitchWidget

    private lateinit var btnLensWide: Button
    private lateinit var btnLensZoom: Button
    private lateinit var btnLensThermal: Button
    private lateinit var btnLiveStreamPanel: Button
    private lateinit var btnLiveStreamAction: Button

    private lateinit var btnRecordWaypoint: Button
    private lateinit var btnSaveWaypoints: Button
    private lateinit var btnClearWaypoints: Button

    private var btnAutoLanding: Button? = null
    private var btnTakeoff: Button? = null

    // ── 任务控制（暂停 / 继续 / 返航）──────────────────────
    private lateinit var btnMissionPause: Button
    private lateinit var btnMissionResume: Button
    private lateinit var btnMissionReturn: Button
    private val missionCtrl = MissionController()

    private lateinit var xSpeedText: TextView
    private lateinit var ySpeedText: TextView
    private lateinit var zSpeedText: TextView
    private lateinit var yawRateText: TextView
    private lateinit var remainingTimeText: TextView
    private lateinit var taskStatusText: TextView
    private lateinit var missionStatusText: TextView
    private lateinit var motorTemperatureText: TextView
    private lateinit var functionMenu: FlightMenuController
    private lateinit var operationWorkspace: OperationWorkspaceController
    private var collectionWorkspaceActive = false
    private var collectDialog: AlertDialog? = null
    private var motorTemperatureMonitor: MotorTemperatureMonitor? = null
    private lateinit var liveStreamLayout: LinearLayout
    private lateinit var liveStreamAddressText: TextView
    private lateinit var liveStreamStatusText: TextView
    private lateinit var liveStreamAddressInput: EditText
    private lateinit var btnLiveStreamSave: Button

    // ── ★ 速度控制面板 ─────────────────────────────────────────
    private lateinit var velocityPanel: VelocityControlPanel
    private lateinit var btnVelStartStop: Button
    private lateinit var btnVelZero: Button
    private lateinit var btnVelYawMinus: Button
    private lateinit var btnVelYawPlus: Button
    private lateinit var btnVelXMinus: Button
    private lateinit var btnVelXPlus: Button
    private lateinit var btnVelYMinus: Button
    private lateinit var btnVelYPlus: Button
    private lateinit var btnVelZMinus: Button
    private lateinit var btnVelZPlus: Button
    private lateinit var txtVelStatus: TextView

    private var currentCameraIndex: ComponentIndexType = ComponentIndexType.LEFT_OR_MAIN

    private val cameraSourceProcessor = DataProcessor.create(
        Pair(ComponentIndexType.UNKNOWN, CameraLensType.UNKNOWN)
    )
    private var compositeDisposable: CompositeDisposable? = null

    private var lastYawDeg = Double.NaN
    private var lastYawTimeMs = 0L
    private val waypointCtrl = WaypointController()

    private lateinit var modeController: ModeController
    private lateinit var btnModeMapping: Button
    private lateinit var btnModeCollect: Button
    private lateinit var btnModeCruise: Button
    private lateinit var btnModeSettings: Button

    private var testCameraController: CameraController? = null
    private var visionController: VisionController? = null
    private lateinit var liveStreamController: LiveStreamController
    private lateinit var deviceStatusReportManager: DeviceStatusReportManager
    private lateinit var videoTransferManager: VideoTransferManager
    private lateinit var landingController: LandingController
    private lateinit var preflightController: PreflightController
    private var operationLogDialog: OperationLogDialog? = null
    private var lastLoggedStreamState: Triple<Boolean, Boolean, Boolean>? = null
    private var sdkTapTarget: View? = null
    private var sdkTapLabel: String? = null
    private var sdkTapX = 0f
    private var sdkTapY = 0f
    private val visionOperations = VisionOperationGate()
    private data class VisionFrame(val bytes: ByteArray, val width: Int, val height: Int,
                                   val capturedAt: Long, val landingId: Long?, val checkId: Long?)
    private val visionFrames = LatestFrameProcessor<VisionFrame>(
        { processVisionFrame(it) }, { error -> Log.e(TAG, "视觉工作线程异常", error) })
    @Volatile private var currentTargetId = -1
    @Volatile private var previousLandingState: TaskState = TaskState.INACTIVE

    private var autoStreamAttemptCount = 0
    private var autoStreamAwaitingResult = false
    private var autoStreamStopped = false
    private var liveStreamTouchedByUser = false
    private var isLiveStreamPanelVisible = false
    private var recordingMonitorKey: DJIKey<Boolean>? = null
    private var lastRecordingState: Boolean? = null

    private val pollHandler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            pollVelocity()
            pollYawRate()
            pollRemainingFlightTime()
            pollHandler.postDelayed(this, POLL_INTERVAL)
        }
    }
    private val autoStartLiveStreamRunnable = Runnable {
        attemptAutoStartLiveStream()
    }

    private val availableCameraUpdatedListener =
        object : ICameraStreamManager.AvailableCameraUpdatedListener {
            override fun onAvailableCameraUpdated(list: MutableList<ComponentIndexType>) {
                Log.d(TAG, "onAvailableCameraUpdated: $list")
                runOnUiThread {
                    if (list.isNullOrEmpty()) return@runOnUiThread
                    val source = if (list.contains(ComponentIndexType.LEFT_OR_MAIN)) {
                        ComponentIndexType.LEFT_OR_MAIN
                    } else {
                        list[0]
                    }

                    currentCameraIndex = source
                    fpvWidget.updateVideoSource(source)
                    if (::liveStreamController.isInitialized) {
                        liveStreamController.updateLiveStreamCameraSource(source)
                        liveStreamController.refreshConfiguredStreamAddress()
                    }
                    registerRecordingStateListener()
                    if (::landingController.isInitialized) {
                        landingController.currentCameraIndex = source
                    }
                    if (::preflightController.isInitialized) {
                        preflightController.currentCameraIndex = source
                    }
                }
            }

            override fun onCameraStreamEnableUpdate(map: MutableMap<ComponentIndexType, Boolean>) = Unit
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Thread {
            val ok = OpenCVLoader.initDebug()
            Log.i(TAG, "OpenCV init: $ok")
        }.start()

        setContentView(R.layout.activity_main)
        initUIWidgets()
        functionMenu = FlightMenuController(this,
            onPauseMission = { if (missionCtrl.canPause) missionCtrl.pause() },
            onAbortLanding = {
                if (::landingController.isInitialized && landingController.getTaskState() != TaskState.INACTIVE) {
                    landingController.stopMission("用户快捷中止降落")
                    runCatching { testCameraController?.stopVideoStream() }
                }
            },
            onStopVelocity = {
                if (::velocityPanel.isInitialized && velocityPanel.getState() == VelocityControlPanel.PanelState.ACTIVE) {
                    velocityPanel.requestStop()
                    velocityPanel.updateStartButtonState(btnVelStartStop)
                }
            })
        operationWorkspace = OperationWorkspaceController(this,
            onRecord = {
                waypointCtrl.recordWaypoint()
                Toast.makeText(this, "已请求记录当前航点", Toast.LENGTH_SHORT).show()
            },
            onSave = {
                waypointCtrl.saveWaypoints()
                Toast.makeText(this, "已请求保存航线", Toast.LENGTH_SHORT).show()
            },
            onClear = { confirmClearWaypoints() },
            onFinish = { confirmFinishCollect() })
        motorTemperatureMonitor = MotorTemperatureMonitor(::renderMotorTemperature)
        OperationLogger.state("界面", "主飞行页面打开")
        findViewById<Button>(R.id.btnOperationLogs).setLoggedClickListener {
            functionMenu.close()
            operationLogDialog?.dismiss()
            operationLogDialog = OperationLogDialog(this).also { it.show() }
        }
        modeController = ModeController()
        DroneControlService.modeController = modeController
        setupModeController()

        // ★ 任务控制（暂停 / 继续 / 返航）
        DroneControlService.missionController = missionCtrl
        setupMissionButtons()

        try {
            liveStreamController = LiveStreamController(this)
            deviceStatusReportManager = DeviceStatusReportManager {
                liveStreamController.getConfiguredStreamAddress()
            }
            videoTransferManager = VideoTransferManager(
                context = applicationContext,
                streamAddressProvider = { liveStreamController.getConfiguredStreamAddress() },
                cameraIndexProvider = { currentCameraIndex }
            )
            videoTransferManager.onTransferFinished = { success, summary ->
                OperationLogger.result("视频传输", success, summary)
                val reason = if (success) {
                    "media transfer finished: $summary"
                } else {
                    "media transfer failed: $summary"
                }
                liveStreamController.recoverAfterMediaTransfer(reason)
            }
            setupLiveStreamController()
            deviceStatusReportManager.start()
            landingController = LandingController(visionOperations)
            preflightController = PreflightController(visionOperations)
            setupControllerCallbacks()
            DroneControlService.preflightController = preflightController
            DroneControlService.landingController = landingController

            // ★ 注入相机流启停闭包（供 DroneControlService 在 PSDK 触发时调用）
            DroneControlService.onStartCameraStream = {
                runOnUiThread {
                    ensureVisionSystemReady()
                    visionController?.resetTracking()
                    testCameraController?.startVideoStream()
                }
            }
            DroneControlService.onResetVisionTracking = {
                visionController?.resetTracking()
            }
            DroneControlService.onStopCameraStream = {
                runCatching { testCameraController?.stopVideoStream() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Controller init failed", e)
            showErrorOnUI("控制器初始化失败，请确保飞机已连接")
        }

        fpvWidget.isCameraSourceNameVisible = false
        fpvWidget.isCameraSourceSideVisible = false
        fpvWidget.setOnFPVStreamSourceListener(object : FPVStreamSourceListener {
            override fun onStreamSourceUpdated(pos: ComponentIndexType, lens: CameraLensType) {
                Log.d(TAG, "onStreamSourceUpdated: pos=$pos lens=$lens")
                cameraSourceProcessor.onNext(Pair(pos, lens))
            }
        })

        MediaDataCenter.getInstance()
            .getCameraStreamManager()
            .addAvailableCameraUpdatedListener(availableCameraUpdatedListener)

        setupLensButtons()
        setupWaypointButtons()
        setupLiveStreamButton()

        btnAutoLanding?.setLoggedClickListener { onLandingClicked() }
        btnTakeoff?.setLoggedClickListener { onTakeoffClicked() }

        btnModeMapping.setLoggedClickListener { functionMenu.close(); showMappingDialog() }
        btnModeCollect.setLoggedClickListener { functionMenu.close(); showCollectDialog() }
        btnModeCruise.setLoggedClickListener { functionMenu.close(); showCruiseDialog() }
        btnModeSettings.setLoggedClickListener { functionMenu.close(); showSettingsDialog() }

        // ═══════════════════════════════════════════════════════
        // ★ 速度控制面板初始化
        // ═══════════════════════════════════════════════════════
        setupVelocityPanel()

        window.decorView.postDelayed({ reattachStatusWidgets() }, REATTACH_DELAY)
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, DroneControlService::class.java))
        }
        runCatching { VideoUploadCommandService.start(this) }.onFailure { error ->
            Log.e(TAG, "视频上传服务启动失败", error)
            showErrorOnUI("视频上传服务启动失败：${error.message ?: "请查看日志"}")
        }
    }

    override fun onResume() {
        super.onResume()
        motorTemperatureMonitor?.start()
        if (::liveStreamController.isInitialized) {
            liveStreamController.bind()
            liveStreamController.updateLiveStreamCameraSource(currentCameraIndex)
            liveStreamController.refreshConfiguredStreamAddress()
            scheduleAutoStartLiveStream()
        }
        registerRecordingStateListener()

        compositeDisposable = CompositeDisposable()
        compositeDisposable?.add(
            cameraSourceProcessor.toFlowable()
                .throttleLast(500, TimeUnit.MILLISECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { (pos, lens) ->
                        if (pos != ComponentIndexType.UNKNOWN) {
                            updateViewVisibility(lens)
                            updateAllWidgetSource(pos, lens)
                            updateLensButtonState(lens)
                            DroneControlService.updateCurrentLens(lensTypeToCode(lens))
                        }
                    },
                    { e -> Log.e(TAG, "cameraSource error: ${e.message}") }
                )
        )
        pollHandler.post(pollRunnable)
    }

    override fun onPause() {
        super.onPause()
        OperationLogger.state("界面", "主飞行页面退出前台")
        motorTemperatureMonitor?.stop()
        if (::landingController.isInitialized) landingController.stopMission("界面退出前台，中止视觉降落")
        if (::preflightController.isInitialized) preflightController.cancel("界面退出前台，中止预检")
        pollHandler.removeCallbacks(pollRunnable)
        pollHandler.removeCallbacks(autoStartLiveStreamRunnable)
        compositeDisposable?.dispose()
        compositeDisposable = null
    }

    override fun onDestroy() {
        super.onDestroy()
        collectDialog?.dismiss()
        collectDialog = null
        if (::modeController.isInitialized) modeController.release()
        operationLogDialog?.dismiss()
        operationLogDialog = null
        motorTemperatureMonitor?.stop()
        pollHandler.removeCallbacks(autoStartLiveStreamRunnable)
        MediaDataCenter.getInstance()
            .getCameraStreamManager()
            .removeAvailableCameraUpdatedListener(availableCameraUpdatedListener)
        if (::liveStreamController.isInitialized) {
            liveStreamController.stopStreamIfNeeded()
            liveStreamController.release()
        }
        if (::deviceStatusReportManager.isInitialized) {
            deviceStatusReportManager.stop()
        }
        unregisterRecordingStateListener()
        if (::videoTransferManager.isInitialized) {
            videoTransferManager.statusCallback = null
            videoTransferManager.onTransferFinished = null
            videoTransferManager.release()
        }
        DroneControlService.onEnqueueLatestVideoTransfer = null
        DroneControlService.onStartCameraStream = null
        DroneControlService.onStopCameraStream = null
        DroneControlService.onResetVisionTracking = null
        if (::modeController.isInitialized && DroneControlService.modeController === modeController) {
            DroneControlService.modeController = null
        }
        if (DroneControlService.missionController === missionCtrl) {
            missionCtrl.onStateChanged = null
            missionCtrl.onLogMessage = null
            DroneControlService.missionController = null
        }
        if (::landingController.isInitialized && DroneControlService.landingController === landingController) {
            DroneControlService.landingController = null
        }
        if (::preflightController.isInitialized && DroneControlService.preflightController === preflightController) {
            DroneControlService.preflightController = null
        }
        visionFrames.close()
        if (::landingController.isInitialized) landingController.release()
        if (::preflightController.isInitialized) preflightController.release()
        visionController?.release()
        runCatching { testCameraController?.stopVideoStream() }

        // ★ 释放速度控制面板
        if (::velocityPanel.isInitialized) velocityPanel.release()
    }

    private fun pollVelocity() {
        try {
            KeyManager.getInstance().getValue(
                KeyTools.createKey(FlightControllerKey.KeyAircraftVelocity),
                object : CommonCallbacks.CompletionCallbackWithParam<Velocity3D> {
                    override fun onSuccess(value: Velocity3D?) {
                        value ?: return
                        runOnUiThread {
                            xSpeedText.text = "%+.2f".format(value.x)
                            ySpeedText.text = "%+.2f".format(value.y)
                            zSpeedText.text = "%+.2f".format(value.z)
                        }
                    }

                    override fun onFailure(error: IDJIError) = Unit
                }
            )
        } catch (_: Exception) {
        }
    }

    private fun pollYawRate() {
        if (::preflightController.isInitialized && preflightController.isChecking) return

        try {
            KeyManager.getInstance().getValue(
                KeyTools.createKey(FlightControllerKey.KeyAircraftAttitude),
                object : CommonCallbacks.CompletionCallbackWithParam<Attitude> {
                    override fun onSuccess(value: Attitude?) {
                        value ?: return
                        val nowMs = System.currentTimeMillis()
                        val yawNow = value.yaw
                        if (!lastYawDeg.isNaN() && lastYawTimeMs > 0) {
                            val dtSec = (nowMs - lastYawTimeMs) / 1000.0
                            if (dtSec > 0.05) {
                                var delta = yawNow - lastYawDeg
                                if (delta > 180.0) delta -= 360.0
                                if (delta < -180.0) delta += 360.0
                                runOnUiThread {
                                    yawRateText.text = "%+.1f".format(delta / dtSec)
                                }
                            }
                        }
                        lastYawDeg = yawNow
                        lastYawTimeMs = nowMs
                    }

                    override fun onFailure(error: IDJIError) = Unit
                }
            )
        } catch (_: Exception) {
        }
    }

    private fun pollRemainingFlightTime() {
        try {
            KeyManager.getInstance().getValue(
                KeyTools.createKey(FlightControllerKey.KeyLowBatteryRTHInfo),
                object : CommonCallbacks.CompletionCallbackWithParam<LowBatteryRTHInfo> {
                    override fun onSuccess(value: LowBatteryRTHInfo?) {
                        value ?: return
                        val sec = value.remainingFlightTime
                        runOnUiThread {
                            remainingTimeText.text =
                                if (sec > 0) "%d:%02d".format(sec / 60, sec % 60) else "--:--"
                        }
                    }

                    override fun onFailure(error: IDJIError) = Unit
                }
            )
        } catch (_: Exception) {
        }
    }

    private fun setupControllerCallbacks() {
        landingController.onPreparationStarted = {
            val sessionId = landingController.dockSession.activeId()
            runOnUiThread {
                if (sessionId != null && landingController.dockSession.activeId() == sessionId) {
                    ensureVisionSystemReady()
                    visionController?.resetTracking()
                    testCameraController?.startVideoStream()
                }
            }
        }
        landingController.onDockTypeLocked = { type ->
            runOnUiThread {
                if (landingController.getTaskState() == TaskState.LANDING &&
                    landingController.dockSession.lockedType() == type) {
                    btnAutoLanding?.text = if (type == LandingDockType.NEW_DUAL) "新版机巢\n中止降落" else "旧版机巢\n中止降落"
                    taskStatusText.text = "视觉降落：已锁定${type.label}"
                }
            }
        }
        landingController.onLandingSucceed = {
            runOnUiThread {
                taskStatusText.text = "降落完成"
                taskStatusText.setTextColor(0xFF5DE4AE.toInt())
            }
            DroneControlService.sendFrame(DroneCommProtocol.encodeSimple(DroneCommProtocol.CMD_ACK_LAND_COMPLETE))
        }
        landingController.onTaskStateChanged = { state ->
            runOnUiThread {
                functionMenu.setLandingActive(state != TaskState.INACTIVE)
                when (state) {
                    TaskState.INACTIVE -> {
                        taskStatusText.text = "视觉降落已结束"
                        btnAutoLanding?.text = "视觉降落"
                        btnAutoLanding?.setDashboardColor(0xFFD32F2F.toInt())
                        btnAutoLanding?.isEnabled = true
                        btnTakeoff?.isEnabled = true
                    }

                    TaskState.LANDING_PREP -> {
                        taskStatusText.text = "正在准备视觉降落"
                        taskStatusText.setTextColor(0xFFD5E3F4.toInt())
                        previousLandingState = TaskState.LANDING_PREP
                        btnAutoLanding?.text = "准备中..."
                        btnAutoLanding?.setDashboardColor(0xFFFF9800.toInt())
                        btnAutoLanding?.isEnabled = false
                        btnTakeoff?.isEnabled = false
                    }

                    TaskState.LANDING -> {
                        btnAutoLanding?.text = "识别机巢\n点击中止"
                        btnAutoLanding?.setDashboardColor(0xFF4CAF50.toInt())
                        btnAutoLanding?.isEnabled = true
                        btnTakeoff?.isEnabled = false
                        clearErrorOnUI()
                        taskStatusText.text = "视觉降落：正在识别机巢"
                        previousLandingState = TaskState.LANDING
                    }
                }
            }
            if (state == TaskState.INACTIVE && previousLandingState != TaskState.INACTIVE) {
                previousLandingState = TaskState.INACTIVE
                // 退出或中止后关闭视觉流；仅确认落地才发送完成通知。
                DroneControlService.onStopCameraStream?.invoke()
            }
        }

        landingController.onError = { msg ->
            runOnUiThread { showErrorOnUI(msg) }
        }
        landingController.onMessage = { msg ->
            runOnUiThread {
                taskStatusText.text = msg
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }

        preflightController.onCheckStarted = {
            runOnUiThread {
                if (!preflightController.isChecking) return@runOnUiThread
                ensureVisionSystemReady()
                visionController?.resetTracking()
                testCameraController?.startVideoStream()
                btnTakeoff?.isEnabled = false
                btnTakeoff?.text = "正在检查..."
                Toast.makeText(this, "正在进行起飞前安全检查...", Toast.LENGTH_SHORT).show()
                clearErrorOnUI()
                taskStatusText.text = "正在进行起飞前检查"
            }
        }

        preflightController.onCheckPassed = {
            runOnUiThread {
                btnTakeoff?.isEnabled = true
                btnTakeoff?.text = "检查通过"
                taskStatusText.text = "检查完毕，可以起飞"
                taskStatusText.setTextColor(0xFF5DE4AE.toInt())
                Toast.makeText(this, "安全检查通过", Toast.LENGTH_LONG).show()
            }
            // ★ 自检结束后关闭视频流
            DroneControlService.onStopCameraStream?.invoke()
            runCatching {
                val frame = DroneCommProtocol.encodeSimple(
                    DroneCommProtocol.CMD_ACK_CHECK_PASSED
                )
                DroneControlService.sendFrame(frame)
            }
        }

        preflightController.onCheckFailed = { reason ->
            runOnUiThread {
                btnTakeoff?.isEnabled = true
                btnTakeoff?.text = "重新检查"
                showErrorOnUI(reason)
            }
            // ★ 自检结束后关闭视频流
            DroneControlService.onStopCameraStream?.invoke()
            runCatching {
                val reasonCode: Byte = when {
                    reason.contains("夹爪", ignoreCase = true) ||
                        reason.contains("grip", ignoreCase = true) ||
                        reason.contains("遮挡", ignoreCase = true) ->
                        DroneCommProtocol.CHECK_FAIL_REASON_GRIP_NOT_DETECTED

                    reason.contains("cv", ignoreCase = true) ||
                        reason.contains("vision", ignoreCase = true) ||
                        reason.contains("视觉", ignoreCase = true) ->
                        DroneCommProtocol.CHECK_FAIL_REASON_CV_ERROR

                    reason.contains("云台", ignoreCase = true) ||
                        reason.contains("gimbal", ignoreCase = true) ->
                        DroneCommProtocol.CHECK_FAIL_REASON_GIMBAL_ERROR

                    else -> DroneCommProtocol.CHECK_FAIL_REASON_UNKNOWN
                }
                val frame = DroneCommProtocol.encodePayload(
                    DroneCommProtocol.CMD_ACK_CHECK_FAILED,
                    byteArrayOf(reasonCode)
                )
                DroneControlService.sendFrame(frame)
            }
        }
    }

    private fun ensureVisionSystemReady() {
        if (testCameraController != null) return

        try {
            testCameraController = CameraController(currentCameraIndex)
            visionController = VisionController(landingController.dockSession)

            visionController?.onTargetLocked = { result ->
                currentTargetId = result.targetId
                landingController.updateVisionData(result)
            }
            visionController?.onTargetLost = {
                currentTargetId = -1
                landingController.invalidateVisionData()
            }

            testCameraController?.frameCallback = frame@{ data, offset, length, width, height ->
                val capturedAt = SystemClock.elapsedRealtime()
                val landingId = landingController.dockSession.activeId().takeIf { landingController.canProcessVision() }
                val checkId = preflightController.activeCheckId()
                if (landingId == null && checkId == null) return@frame
                val pixels = width.toLong() * height
                if (width <= 0 || height <= 0 || offset < 0 || pixels > Int.MAX_VALUE ||
                    pixels > length || offset.toLong() + pixels > data.size) return@frame
                // SDK 缓冲区可能被复用，只复制 Y 平面；最多保留一张待处理帧。
                visionFrames.submit(VisionFrame(data.copyOfRange(offset, offset + pixels.toInt()),
                    width, height, capturedAt, landingId, checkId))
            }
            Log.i(TAG, "视觉流分发初始化完成")
        } catch (e: Throwable) {
            Log.e(TAG, "视觉模块初始化失败", e)
            runOnUiThread {
                Toast.makeText(this, "视觉模块初始化失败，请检查 Logcat", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun onLandingClicked() {
        if (!::landingController.isInitialized) {
            Toast.makeText(this, "控制器未就绪，请重新连接飞机后重启 APP", Toast.LENGTH_SHORT).show()
            return
        }

        if (landingController.getTaskState() == TaskState.INACTIVE) {
            landingController.startVisionLanding()
        } else {
            landingController.stopMission("用户手动中止")
            runCatching { testCameraController?.stopVideoStream() }
        }
    }

    private fun onTakeoffClicked() {
        if (!::landingController.isInitialized || !::preflightController.isInitialized) {
            Toast.makeText(this, "控制器未就绪，请重新连接飞机后重启 APP", Toast.LENGTH_SHORT).show()
            return
        }
        if (landingController.getTaskState() != TaskState.INACTIVE) {
            Toast.makeText(this, "正在降落，无法进行预检", Toast.LENGTH_SHORT).show()
            return
        }
        if (!preflightController.startCheck()) {
            Toast.makeText(this, "相机/云台任务尚未结束，请稍后重试预检", Toast.LENGTH_SHORT).show()
        }
    }

    private fun processVisionFrame(frame: VisionFrame) {
        val landing = frame.landingId != null && landingController.canProcessVision() &&
            landingController.dockSession.activeId() == frame.landingId
        val checking = frame.checkId != null && preflightController.activeCheckId() == frame.checkId
        if ((!landing && !checking) || SystemClock.elapsedRealtime() - frame.capturedAt > LandingController.VISION_HOLD_MS) return
        currentTargetId = -1
        visionController?.processFrame(frame.bytes, 0, frame.bytes.size, frame.width, frame.height,
            frame.capturedAt, if (landing) frame.landingId else null)
        if (checking) preflightController.processFrame(frame.bytes, 0, frame.width, frame.height,
            currentTargetId != -1, frame.checkId!!, frame.capturedAt)
    }

    private fun initUIWidgets() {
        fpvWidget = findViewById(R.id.fpvWidget)
        shootPhotoWidget = findViewById(R.id.shootPhotoWidget)
        recordVideoWidget = findViewById(R.id.recordVideoWidget)
        focalZoomWidget = findViewById(R.id.focalZoomWidget)
        photoVideoSwitchWidget = findViewById(R.id.photoVideoSwitchWidget)

        btnLensWide = findViewById(R.id.btnLensWide)
        btnLensZoom = findViewById(R.id.btnLensZoom)
        btnLensThermal = findViewById(R.id.btnLensThermal)
        btnLiveStreamPanel = findViewById(R.id.btnLiveStreamPanel)
        btnLiveStreamAction = findViewById(R.id.btnLiveStreamAction)

        btnRecordWaypoint = findViewById(R.id.btnRecordWaypoint)
        btnSaveWaypoints = findViewById(R.id.btnSaveWaypoints)
        btnClearWaypoints = findViewById(R.id.btnClearWaypoints)

        xSpeedText = findViewById(R.id.xSpeedText)
        ySpeedText = findViewById(R.id.ySpeedText)
        zSpeedText = findViewById(R.id.zSpeedText)
        yawRateText = findViewById(R.id.yawRateText)
        remainingTimeText = findViewById(R.id.remainingTimeText)
        taskStatusText = findViewById(R.id.taskStatusText)
        missionStatusText = findViewById(R.id.missionStatusText)
        motorTemperatureText = findViewById(R.id.motorTemperatureText)
        liveStreamLayout = findViewById(R.id.liveStreamLayout)
        liveStreamAddressText = findViewById(R.id.liveStreamAddressText)
        liveStreamStatusText = findViewById(R.id.liveStreamStatusText)
        liveStreamAddressInput = findViewById(R.id.liveStreamAddressInput)
        btnLiveStreamSave = findViewById(R.id.btnLiveStreamSave)

        btnAutoLanding = findViewById(R.id.btnAutoLanding)
        btnTakeoff = findViewById(R.id.btnTakeoff)

        btnMissionPause = findViewById(R.id.btnMissionPause)
        btnMissionResume = findViewById(R.id.btnMissionResume)
        btnMissionReturn = findViewById(R.id.btnMissionReturn)

        btnModeMapping = findViewById(R.id.btnModeMapping)
        btnModeCollect = findViewById(R.id.btnModeCollect)
        btnModeCruise = findViewById(R.id.btnModeCruise)
        btnModeSettings = findViewById(R.id.btnModeSettings)

        // ═══════════════════════════════════════════════════════
        // ★ 速度控制面板按钮绑定
        // ═══════════════════════════════════════════════════════
        btnVelStartStop = findViewById(R.id.btnVelStartStop)
        btnVelZero      = findViewById(R.id.btnVelZero)
        btnVelYawMinus  = findViewById(R.id.btnVelYawMinus)
        btnVelYawPlus   = findViewById(R.id.btnVelYawPlus)
        btnVelXMinus    = findViewById(R.id.btnVelXMinus)
        btnVelXPlus     = findViewById(R.id.btnVelXPlus)
        btnVelYMinus    = findViewById(R.id.btnVelYMinus)
        btnVelYPlus     = findViewById(R.id.btnVelYPlus)
        btnVelZMinus    = findViewById(R.id.btnVelZMinus)
        btnVelZPlus     = findViewById(R.id.btnVelZPlus)
        txtVelStatus    = findViewById(R.id.txtVelStatus)
    }

    // ═══════════════════════════════════════════════════════
    // ★ 速度控制面板
    // ═══════════════════════════════════════════════════════
    private fun setupVelocityPanel() {
        velocityPanel = VelocityControlPanel()

        // Toast 回调
        velocityPanel.toastCallback = { msg ->
            OperationLogger.state("手动速度", msg)
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // 面板状态变更 → 更新按钮外观
        velocityPanel.onPanelStateChanged = { _ ->
            runOnUiThread {
                // SDK 回调可能晚于自动停止，按当前实际状态绘制，避免旧 ACTIVE 恢复已结束的面板。
                val state = velocityPanel.getState()
                OperationLogger.state("手动速度", if (state == VelocityControlPanel.PanelState.ACTIVE) "速度控制已启动" else "速度控制已停止")
                operationWorkspace.setVelocityActive(state == VelocityControlPanel.PanelState.ACTIVE)
                functionMenu.setVelocityActive(state == VelocityControlPanel.PanelState.ACTIVE)
                if (state == VelocityControlPanel.PanelState.ACTIVE) functionMenu.close()
                when (state) {
                    VelocityControlPanel.PanelState.IDLE -> {
                        btnVelStartStop.text = "启动速度控制"
                        btnVelStartStop.setDashboardColor(
                            if (velocityPanel.isInNMode()) 0xFF4CAF50.toInt()
                            else 0xFF555555.toInt()
                        )
                        btnVelStartStop.isEnabled = velocityPanel.isInNMode()
                        btnVelZero.isEnabled = false
                        btnVelZero.setDashboardColor(0xFF555555.toInt())
                        setVelButtonsEnabled(false)
                        txtVelStatus.text = if (velocityPanel.isInNMode()) "N挡就绪" else "等待N挡..."
                        txtVelStatus.setTextColor(
                            if (velocityPanel.isInNMode()) 0xFF888888.toInt()
                            else 0xFFFF9800.toInt()
                        )
                    }
                    VelocityControlPanel.PanelState.ACTIVE -> {
                        btnVelStartStop.text = "关闭速度控制"
                        btnVelStartStop.setDashboardColor(0xFFD32F2F.toInt())
                        btnVelStartStop.isEnabled = true
                        btnVelZero.isEnabled = true
                        btnVelZero.setDashboardColor(0xFFFF9800.toInt())
                        setVelButtonsEnabled(true)
                        txtVelStatus.text = "● 控制中"
                        txtVelStatus.setTextColor(0xFF4CAF50.toInt())
                    }
                }
            }
        }

        velocityPanel.onVelocityChanged = { _, _, _ ->
            runOnUiThread {
                val target = velocityPanel.getTargetVelocity()
                operationWorkspace.updateVelocityTargets(target.x, target.y, target.z, target.yaw)
            }
        }

        // N 挡可用性变更 → 更新启动按钮及状态文字
        velocityPanel.onNModeAvailabilityChanged = { available ->
            runOnUiThread {
                if (velocityPanel.getState() == VelocityControlPanel.PanelState.IDLE) {
                    velocityPanel.updateStartButtonState(btnVelStartStop)
                    val modeName = velocityPanel.getCurrentFlightModeName()
                    txtVelStatus.text = if (available) "N挡就绪"
                        else "等待N挡... [$modeName]"
                    txtVelStatus.setTextColor(
                        if (available) 0xFF888888.toInt() else 0xFFFF9800.toInt()
                    )
                }
            }
        }

        // ★ 回调全部设置完毕后，再注册监听（触发初始飞行模式查询）
        velocityPanel.registerFlightModeListener()

        // ── 按钮点击事件绑定 ──────────────────────────────────────

        btnVelStartStop.setLoggedClickListener {
            if (operationWorkspace.velocityControlsOnMain) velocityPanel.requestStop()
            else velocityPanel.toggleStartStop()
            velocityPanel.updateStartButtonState(btnVelStartStop)
        }

        btnVelZero.setLoggedClickListener {
            if (velocityPanel.getState() == VelocityControlPanel.PanelState.ACTIVE) {
                velocityPanel.zeroVelocity()
                Toast.makeText(this, "速度已归零 → 悬停", Toast.LENGTH_SHORT).show()
            }
        }

        // Yaw 偏航 +/-
        btnVelYawPlus.setLoggedClickListener  { velocityPanel.adjustYaw(+1f) }
        btnVelYawMinus.setLoggedClickListener { velocityPanel.adjustYaw(-1f) }

        // X 轴速度 +/-
        btnVelXPlus.setLoggedClickListener  { velocityPanel.adjustVelocity('X', +1f) }
        btnVelXMinus.setLoggedClickListener { velocityPanel.adjustVelocity('X', -1f) }

        // Y 轴速度 +/-
        btnVelYPlus.setLoggedClickListener  { velocityPanel.adjustVelocity('Y', +1f) }
        btnVelYMinus.setLoggedClickListener { velocityPanel.adjustVelocity('Y', -1f) }

        // Z 轴速度 +/-
        btnVelZPlus.setLoggedClickListener  { velocityPanel.adjustVelocity('Z', +1f) }
        btnVelZMinus.setLoggedClickListener { velocityPanel.adjustVelocity('Z', -1f) }

        // 初始状态：所有速度按钮禁用
        setVelButtonsEnabled(false)
        btnVelZero.isEnabled = false
        btnVelZero.setDashboardColor(0xFF555555.toInt())
        velocityPanel.updateStartButtonState(btnVelStartStop)

        Log.i(TAG, "✅ 速度控制面板初始化完成")
    }

    private fun Button.setDashboardColor(color: Int) {
        backgroundTintList = ColorStateList.valueOf(color)
    }

    private fun setVelButtonsEnabled(enabled: Boolean) {
        listOf(btnVelYawPlus, btnVelYawMinus,
               btnVelXPlus, btnVelXMinus,
               btnVelYPlus, btnVelYMinus,
               btnVelZPlus, btnVelZMinus).forEach { btn ->
            btn.isEnabled = enabled
            if (!enabled) btn.setDashboardColor(0xFF555555.toInt())
        }
        if (enabled) {
            btnVelYawPlus.setDashboardColor(0xFF9C27B0.toInt())
            btnVelYawMinus.setDashboardColor(0xFF9C27B0.toInt())
            btnVelXPlus.setDashboardColor(0xFF1976D2.toInt())
            btnVelXMinus.setDashboardColor(0xFF1976D2.toInt())
            btnVelYPlus.setDashboardColor(0xFF388E3C.toInt())
            btnVelYMinus.setDashboardColor(0xFF388E3C.toInt())
            btnVelZPlus.setDashboardColor(0xFFF57C00.toInt())
            btnVelZMinus.setDashboardColor(0xFFF57C00.toInt())
        }
    }

    private fun showErrorOnUI(msg: String) {
        OperationLogger.error("界面提示", msg)
        taskStatusText.text = msg
        taskStatusText.setTextColor(0xFFFF727C.toInt())
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun clearErrorOnUI() {
        taskStatusText.setTextColor(0xFFD5E3F4.toInt())
        taskStatusText.text = "等待操作"
    }

    private fun renderMotorTemperature(status: MotorTemperaturePolicy.Status) {
        motorTemperatureText.text = status.label
        motorTemperatureText.setTextColor(when (status) {
            MotorTemperaturePolicy.Status.NORMAL -> 0xFF5DE4AE.toInt()
            MotorTemperaturePolicy.Status.HIGH -> 0xFFFF727C.toInt()
            MotorTemperaturePolicy.Status.UNKNOWN -> 0xFFDAB979.toInt()
            MotorTemperaturePolicy.Status.DISCONNECTED -> 0xFF93A4B8.toInt()
        })
    }

    private fun reattachStatusWidgets() {
        Log.d(TAG_SDK, "重新挂载顶栏 Widget...")
        listOf(
            R.id.systemStatusWidget,
            R.id.flightModeWidget,
            R.id.gpsSignalWidget,
            R.id.rcSignalWidget,
            R.id.videoSignalWidget,
            R.id.batteryWidget
        ).forEach { id ->
            findViewById<View>(id)?.let { reattachView(it) }
        }
        Log.d(TAG_SDK, "完成")
    }

    private fun reattachView(view: View) {
        val parent = view.parent as? ViewGroup ?: return
        val index = parent.indexOfChild(view)
        val lp = view.layoutParams
        parent.removeView(view)
        parent.addView(view, index, lp)
    }

    private fun setupLensButtons() {
        btnLensWide.setLoggedClickListener { switchLens(CameraVideoStreamSourceType.WIDE_CAMERA) }
        btnLensZoom.setLoggedClickListener { switchLens(CameraVideoStreamSourceType.ZOOM_CAMERA) }
        btnLensThermal.setLoggedClickListener { switchLens(CameraVideoStreamSourceType.INFRARED_CAMERA) }
    }

    private fun setupWaypointButtons() {
        btnRecordWaypoint.setLoggedClickListener { waypointCtrl.recordWaypoint() }
        btnSaveWaypoints.setLoggedClickListener { waypointCtrl.saveWaypoints() }
        btnClearWaypoints.setLoggedClickListener { confirmClearWaypoints() }
    }

    // ═══════════════════════════════════════════════════════
    // ★ 任务控制：暂停 / 继续 / 返航
    // ═══════════════════════════════════════════════════════
    private fun setupMissionButtons() {
        missionCtrl.onStateChanged = { state, index, total ->
            runOnUiThread { renderMissionState() }
        }
        missionCtrl.onLogMessage = { msg ->
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
        }

        btnMissionPause.setLoggedClickListener {
            missionCtrl.pause()
        }

        btnMissionResume.setLoggedClickListener {
            missionCtrl.resume()
        }

        btnMissionReturn.setLoggedClickListener {
            // 返航会一路退回起点，属于不可逆任务变更，加一次确认
            AlertDialog.Builder(this)
                .setTitle("确认返航")
                .setMessage("将沿已飞过的航点反向退回第一个航点，随后自动进入视觉降落。")
                .setPositiveButton("返航") { _, _ -> OperationLogger.operation("确认返航"); missionCtrl.goReturn() }
                .setNegativeButton("取消") { _, _ -> OperationLogger.operation("取消返航") }
                .show()
        }

        // 初始状态未知：三个按钮全部可用，等机载首帧状态上报后再按状态置灰
        renderMissionState()
    }

    /**
     * 根据机载状态机上报刷新按钮可用性。
     *
     * 语义与 indooruav_core 状态机保持一致：
     *   Cruise   → 可暂停；继续/返航置灰
     *   Paused   → 可继续、可返航；暂停置灰
     *   Return   → 三个都置灰（返航是终止流程）
     *   Land 等  → 三个都置灰
     */
    private fun renderMissionState() {
        btnMissionPause.isEnabled = missionCtrl.canPause
        functionMenu.setMissionCanPause(missionCtrl.canPause)
        missionStatusText.text = "任务：${missionCtrl.stateName}" +
            if (missionCtrl.wpTotal > 0) " · 航点 ${missionCtrl.wpIndex}/${missionCtrl.wpTotal}" else ""
        btnMissionResume.isEnabled = missionCtrl.canResume
        btnMissionReturn.isEnabled = missionCtrl.canReturn
        Log.d(
            TAG,
            "mission state=${missionCtrl.stateName} wp=${missionCtrl.wpIndex}/${missionCtrl.wpTotal}"
        )
    }

    private fun setupLiveStreamController() {
        liveStreamController.onStateChanged = { state ->
            val key = Triple(state.isStreaming, state.isBusy, state.isError)
            if (key != lastLoggedStreamState) {
                lastLoggedStreamState = key
                OperationLogger.state("直播", state.streamStatusText)
            }
            runOnUiThread { renderLiveStreamState(state) }
        }
        liveStreamController.updateLiveStreamCameraSource(currentCameraIndex)
        liveStreamController.bind()
        liveStreamAddressInput.setText(liveStreamController.getConfiguredStreamAddress())
    }

    private fun registerRecordingStateListener() {
        unregisterRecordingStateListener()
        val key = KeyTools.createKey(CameraKey.KeyIsRecording, currentCameraIndex)
        recordingMonitorKey = key
        lastRecordingState = KeyManager.getInstance().getValue(key)
        Log.i(TAG, "Register recording listener on camera index=$currentCameraIndex initial=$lastRecordingState")
        runCatching {
            KeyManager.getInstance().listen(key, this, true) { oldValue, newValue ->
                val current = newValue ?: oldValue ?: false
                val previous = lastRecordingState
                if (current != previous) OperationLogger.state("相机", if (current) "SDK 报告录像中" else "SDK 报告未录像")
                Log.d(TAG, "Recording state update old=$oldValue new=$newValue previous=$previous current=$current")
                if (previous == true && current == false && ::videoTransferManager.isInitialized) {
                    Log.i(TAG, "Recording transitioned to stopped, enqueue latest video transfer")
                    videoTransferManager.enqueueLatestVideoTransferAfterRecordStop()
                }
                lastRecordingState = current
            }
        }.onFailure {
            Log.w(TAG, "Failed to register recording listener: ${it.message}")
        }
    }

    private fun unregisterRecordingStateListener() {
        val key = recordingMonitorKey ?: return
        Log.i(TAG, "Unregister recording listener")
        runCatching { KeyManager.getInstance().cancelListen(key, this) }
        recordingMonitorKey = null
        lastRecordingState = null
    }

    private fun setupLiveStreamButton() {
        btnLiveStreamPanel.setLoggedClickListener {
            toggleLiveStreamPanel()
        }
        btnLiveStreamSave.setLoggedClickListener {
            val address = liveStreamAddressInput.text?.toString().orEmpty()
            liveStreamController.updateConfiguredStreamAddress(address)
            if (::deviceStatusReportManager.isInitialized) {
                deviceStatusReportManager.reportNow()
            }
            Toast.makeText(this, "推流地址已保存", Toast.LENGTH_SHORT).show()
        }
        btnLiveStreamAction.setLoggedClickListener {
            if (!::liveStreamController.isInitialized) {
                Toast.makeText(this, "直播模块尚未初始化", Toast.LENGTH_SHORT).show()
                return@setLoggedClickListener
            }
            liveStreamController.updateConfiguredStreamAddress(
                liveStreamAddressInput.text?.toString().orEmpty()
            )
            if (::deviceStatusReportManager.isInitialized) {
                deviceStatusReportManager.reportNow()
            }
            stopAutoStartLiveStream()
            val result = if (liveStreamController.isStreaming()) {
                liveStreamController.stopLiveStream()
            } else {
                liveStreamController.startRtmpLiveStream()
            }
            if (!result.accepted) {
                Toast.makeText(this, result.message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun renderLiveStreamState(state: LiveStreamUiState) {
        liveStreamAddressText.text = if (state.streamAddress.isBlank()) "--" else state.streamAddress
        if (liveStreamAddressInput.text?.toString() != state.streamAddress) {
            liveStreamAddressInput.setText(state.streamAddress)
            liveStreamAddressInput.setSelection(liveStreamAddressInput.text?.length ?: 0)
        }
        liveStreamStatusText.text = state.streamStatusText
        handleAutoStartLiveStreamState(state)
        liveStreamStatusText.setTextColor(
            when {
                state.isError -> 0xFFD32F2F.toInt()
                state.isStreaming -> 0xFF4CAF50.toInt()
                else -> 0xFFFFFFFF.toInt()
            }
        )
        btnLiveStreamAction.isEnabled = !state.isBusy
        btnLiveStreamSave.isEnabled = !state.isBusy
        btnLiveStreamAction.text = when {
            state.isBusy && state.isStreaming -> "正在停止..."
            state.isBusy -> "正在开始..."
            state.isStreaming -> "停止 RTMP 推流"
            else -> "开始 RTMP 推流"
        }
        btnLiveStreamAction.backgroundTintList = ColorStateList.valueOf(
            if (state.isStreaming) 0xFFC62828.toInt() else 0xFF2E7D32.toInt()
        )
        btnLiveStreamSave.backgroundTintList = ColorStateList.valueOf(0xFF1565C0.toInt())
    }

    private fun toggleLiveStreamPanel() {
        isLiveStreamPanelVisible = !isLiveStreamPanelVisible
        liveStreamLayout.visibility = if (isLiveStreamPanelVisible) View.VISIBLE else View.GONE
        btnLiveStreamPanel.text = if (isLiveStreamPanelVisible) "收起直播设置" else "展开直播设置"
        btnLiveStreamPanel.backgroundTintList = ColorStateList.valueOf(
            if (isLiveStreamPanelVisible) 0xFF1976D2.toInt() else 0xFF455A64.toInt()
        )
    }

    private fun scheduleAutoStartLiveStream() {
        if (!::liveStreamController.isInitialized ||
            liveStreamTouchedByUser ||
            autoStreamStopped ||
            autoStreamAwaitingResult ||
            liveStreamController.isStreaming()
        ) {
            return
        }
        if (autoStreamAttemptCount >= AUTO_STREAM_MAX_ATTEMPTS) {
            autoStreamStopped = true
            return
        }
        pollHandler.removeCallbacks(autoStartLiveStreamRunnable)
        pollHandler.post(autoStartLiveStreamRunnable)
    }

    private fun attemptAutoStartLiveStream() {
        if (!::liveStreamController.isInitialized ||
            liveStreamTouchedByUser ||
            autoStreamStopped ||
            autoStreamAwaitingResult ||
            isFinishing ||
            isDestroyed
        ) {
            return
        }
        if (liveStreamController.isStreaming()) {
            autoStreamStopped = true
            return
        }
        if (autoStreamAttemptCount >= AUTO_STREAM_MAX_ATTEMPTS) {
            autoStreamStopped = true
            return
        }

        autoStreamAttemptCount += 1
        val result = liveStreamController.startRtmpLiveStream()
        if (result.accepted) {
            autoStreamAwaitingResult = true
        } else {
            scheduleNextAutoStartIfNeeded(result.message)
        }
    }

    private fun handleAutoStartLiveStreamState(state: LiveStreamUiState) {
        if (liveStreamTouchedByUser || autoStreamStopped) {
            return
        }
        if (state.isStreaming) {
            autoStreamAwaitingResult = false
            autoStreamStopped = true
            pollHandler.removeCallbacks(autoStartLiveStreamRunnable)
            return
        }
        if (!state.isBusy && autoStreamAwaitingResult) {
            scheduleNextAutoStartIfNeeded(state.streamStatusText)
        }
    }

    private fun scheduleNextAutoStartIfNeeded(message: String) {
        autoStreamAwaitingResult = false
        if (liveStreamTouchedByUser || autoStreamStopped) {
            return
        }
        if (autoStreamAttemptCount >= AUTO_STREAM_MAX_ATTEMPTS) {
            autoStreamStopped = true
            return
        }

        val shouldRetry =
            message == "Aircraft is not connected" ||
                message == "RTMP address is empty"

        if (!shouldRetry) {
            autoStreamStopped = true
            return
        }

        pollHandler.removeCallbacks(autoStartLiveStreamRunnable)
        pollHandler.postDelayed(autoStartLiveStreamRunnable, AUTO_STREAM_RETRY_DELAY_MS)
    }

    private fun stopAutoStartLiveStream() {
        liveStreamTouchedByUser = true
        autoStreamStopped = true
        autoStreamAwaitingResult = false
        pollHandler.removeCallbacks(autoStartLiveStreamRunnable)
    }

    private fun switchLens(target: CameraVideoStreamSourceType) {
        Log.d(TAG, "切换镜头 -> $target")
        KeyManager.getInstance().setValue(
            KeyTools.createKey(CameraKey.KeyCameraVideoStreamSource, currentCameraIndex),
            target,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    Log.d(TAG, "切换成功 -> $target")
                    OperationLogger.result("镜头", true, "镜头已切换：$target")
                    DroneControlService.updateCurrentLens(streamTypeToCode(target))
                    if (::liveStreamController.isInitialized) {
                        liveStreamController.updateLiveStreamCameraSource(currentCameraIndex)
                        liveStreamController.refreshConfiguredStreamAddress()
                    }
                }

                override fun onFailure(error: IDJIError) {
                    Log.e(TAG, "切换失败: ${error.description()}")
                    OperationLogger.result("镜头", false, "镜头切换失败：${error.description()}")
                    runOnUiThread {
                        Toast.makeText(
                            this@MainActivity,
                            "切换失败: ${error.description()}",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }

    private fun updateLensButtonState(lensType: CameraLensType) {
        val active = 0xFF1976D2.toInt()
        val inactive = 0xFF555555.toInt()
        btnLensWide.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (lensType == CameraLensType.CAMERA_LENS_WIDE) active else inactive
        )
        btnLensZoom.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (lensType == CameraLensType.CAMERA_LENS_ZOOM) active else inactive
        )
        btnLensThermal.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (lensType == CameraLensType.CAMERA_LENS_THERMAL) active else inactive
        )
    }

    private fun updateViewVisibility(lensType: CameraLensType) {
        val showZoom =
            lensType == CameraLensType.CAMERA_LENS_ZOOM || lensType == CameraLensType.CAMERA_LENS_THERMAL
        focalZoomWidget.visibility = if (showZoom) View.VISIBLE else View.GONE
    }

    private fun updateAllWidgetSource(pos: ComponentIndexType, lens: CameraLensType) {
        shootPhotoWidget.updateCameraSource(pos, lens)
        recordVideoWidget.updateCameraSource(pos, lens)
        focalZoomWidget.updateCameraSource(pos, lens)
        photoVideoSwitchWidget.updateCameraSource(pos, lens)
    }

    private fun lensTypeToCode(lens: CameraLensType): Byte = when (lens) {
        CameraLensType.CAMERA_LENS_ZOOM -> DroneCommProtocol.CAM_LENS_ZOOM
        CameraLensType.CAMERA_LENS_THERMAL -> DroneCommProtocol.CAM_LENS_INFRARED
        else -> DroneCommProtocol.CAM_LENS_WIDE
    }

    private fun streamTypeToCode(t: CameraVideoStreamSourceType): Byte = when (t) {
        CameraVideoStreamSourceType.ZOOM_CAMERA -> DroneCommProtocol.CAM_LENS_ZOOM
        CameraVideoStreamSourceType.INFRARED_CAMERA -> DroneCommProtocol.CAM_LENS_INFRARED
        else -> DroneCommProtocol.CAM_LENS_WIDE
    }
    private fun setupModeController() {
        modeController.onCollectSessionChanged = { runOnUiThread { renderCollectionWorkspace() } }
        renderCollectionWorkspace()
        modeController.onMappingStateChanged = { state ->
            runOnUiThread {
                when (state) {
                    ModeController.MappingState.IDLE -> Log.i(TAG, "建图状态: 空闲")
                    ModeController.MappingState.RUNNING -> Log.i(TAG, "建图状态: 运行中")
                    ModeController.MappingState.SAVED -> Log.i(TAG, "建图状态: 已保存")
                    else -> {}
                }
            }
        }

        modeController.onCollectStateChanged = { state ->
            runOnUiThread {
                when (state) {
                    ModeController.CollectState.IDLE -> Log.i(TAG, "采点状态: 空闲")
                    ModeController.CollectState.RUNNING -> Log.i(TAG, "采点状态: 采点中")
                    ModeController.CollectState.MAP_2D_DONE -> Log.i(TAG, "采点状态: 2D已生成")
                    ModeController.CollectState.PIXEL_DONE -> Log.i(TAG, "采点状态: 像素已生成")
                    else -> {}
                }
            }
        }

        modeController.onCruiseStateChanged = { state ->
            runOnUiThread {
                when (state) {
                    ModeController.CruiseState.IDLE -> Log.i(TAG, "巡航状态: 空闲")
                    ModeController.CruiseState.MAP_SET -> Log.i(TAG, "巡航状态: 地图已设置")
                    ModeController.CruiseState.WP_SET -> Log.i(TAG, "巡航状态: 航线已设置")
                    ModeController.CruiseState.READY -> Log.i(TAG, "巡航状态: 已起飞")
                    else -> {}
                }
            }
        }

        modeController.onLogMessage = { msg ->
            runOnUiThread {
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun renderCollectionWorkspace() {
        val active = modeController.isCollecting
        operationWorkspace.setCollectionState(active, modeController.collectStopPending, when (modeController.collectState) {
            ModeController.CollectState.IDLE -> "采点未开始"
            ModeController.CollectState.RUNNING -> "正在采点"
            ModeController.CollectState.MAP_2D_DONE -> "采点中 · 2D 地图已生成"
            ModeController.CollectState.PIXEL_DONE -> "采点中 · 像素坐标已生成"
        })
        if (active && !collectionWorkspaceActive) {
            collectionWorkspaceActive = true
            collectDialog?.dismiss()
            functionMenu.close()
            OperationLogger.state("采点界面", "机载确认开始采点，已展开主画面操作区")
        } else if (!active && collectionWorkspaceActive) {
            collectionWorkspaceActive = false
            OperationLogger.state("采点界面", "机载确认结束采点，已收起主画面操作区")
        }
    }

    private fun confirmClearWaypoints() {
        AlertDialog.Builder(this).setTitle("清空全部航点？")
            .setMessage("将清空记录器内存中的全部航点，已保存的航线文件不受影响。此操作无法撤销。")
            .setPositiveButton("清空") { _, _ ->
                OperationLogger.operation("确认清空全部航点")
                if (::modeController.isInitialized && modeController.collectStopPending) {
                    Toast.makeText(this, "正在结束采点，请等待机载确认", Toast.LENGTH_SHORT).show()
                } else waypointCtrl.clearWaypoints()
            }
            .setNegativeButton("取消") { _, _ -> OperationLogger.operation("取消清空航点") }
            .show()
    }

    private fun confirmFinishCollect() {
        if (!modeController.isCollecting || modeController.collectStopPending) return
        AlertDialog.Builder(this).setTitle("结束本次采点？")
            .setMessage("将停止雷达、定位和航点采集。请先保存需要保留的航线；结束操作不会自动保存航线。")
            .setPositiveButton("结束采点") { _, _ ->
                OperationLogger.operation("确认结束采点")
                modeController.collectStop()
            }
            .setNegativeButton("继续采点") { _, _ -> OperationLogger.operation("取消结束采点") }
            .show()
    }

    private fun showMappingDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_mapping, null)
        dialogView.setTag(R.id.operation_log_scope, "建图")
        val etMapName = dialogView.findViewById<EditText>(R.id.etMappingMapName)
        val btnStart = dialogView.findViewById<Button>(R.id.btnMappingStart)
        val btnSave = dialogView.findViewById<Button>(R.id.btnMappingSave)
        val btnStop = dialogView.findViewById<Button>(R.id.btnMappingStop)
        val btnClose = dialogView.findViewById<Button>(R.id.btnMappingClose)
        val tvStatus = dialogView.findViewById<TextView>(R.id.tvMappingStatus)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        // 状态更新
        val updateUi = { state: ModeController.MappingState ->
            runOnUiThread {
                when (state) {
                    ModeController.MappingState.IDLE -> {
                        btnStart.isEnabled = true
                        btnSave.isEnabled = false
                        btnStop.isEnabled = false
                        tvStatus.text = "● 就绪"
                        tvStatus.setTextColor(0xFFAAAAAA.toInt())
                    }
                    ModeController.MappingState.RUNNING -> {
                        btnStart.isEnabled = false
                        btnSave.isEnabled = true
                        btnStop.isEnabled = true
                        tvStatus.text = "● 建图中..."
                        tvStatus.setTextColor(0xFF4CAF50.toInt())
                    }
                    ModeController.MappingState.SAVED -> {
                        btnStart.isEnabled = false
                        btnSave.isEnabled = true
                        btnStop.isEnabled = true
                        tvStatus.text = "● 已保存"
                        tvStatus.setTextColor(0xFF1976D2.toInt())
                    }
                    else -> {}
                }
            }
        }

        // 监听状态变更
        modeController.onMappingStateChanged = { state ->
            updateUi(state)
        }

        // 初始状态
        updateUi(modeController.mappingState)

        btnStart.setLoggedClickListener {
            val name = etMapName.text.toString().trim()
            if (name.isNotEmpty()) {
                modeController.mappingSetName(name)
            }
            modeController.mappingStart()
        }

        btnSave.setLoggedClickListener {
            val name = etMapName.text.toString().trim()
            modeController.mappingSaveMap(name)
        }

        btnStop.setLoggedClickListener {
            modeController.mappingStop()
            updateUi(ModeController.MappingState.IDLE)
        }

        btnClose.setLoggedClickListener {
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            // 恢复全局回调
            modeController.onMappingStateChanged = { state ->
                runOnUiThread {
                    when (state) {
                        ModeController.MappingState.IDLE -> Log.i(TAG, "建图状态: 空闲")
                        ModeController.MappingState.RUNNING -> Log.i(TAG, "建图状态: 运行中")
                        ModeController.MappingState.SAVED -> Log.i(TAG, "建图状态: 已保存")
                        else -> {}
                    }
                }
            }
        }

        dialog.show()
    }

    /**
     * 对话框标题：加载中显示"加载中…（第 x 页）"，到过末页后显示"共 N 条 · 第 x/y 页"。
     *
     * 为什么总数不是一开始就有：分页协议里没有 total 字段，
     * 只有翻到末页（next=0xFFFF）才能确定总数和总页数。
     */
    private fun buildListTitle(label: String, s: FileListPager.State): String {
        val page = s.pageIndex + 1
        val loadingPage = (s.loadingPageIndex ?: s.pageIndex) + 1
        return when {
            s.isLoading -> "$label · 加载中…（第 $loadingPage 页）"
            s.error != null -> "$label · ${s.error}"
            s.legacy -> "$label · 共 ${s.loadedCount} 条（机载不支持分页）"
            s.totalCount != null -> "$label · 共 ${s.totalCount} 条 · 第 $page/${s.pageCount ?: page} 页"
            else -> "$label · 第 $page 页 · 已加载 ${s.loadedCount} 条"
        }
    }

    /** 页码行：第 x/y 页 · 本页 k 条 · 第 a–b 条 */
    private fun buildPageInfo(s: FileListPager.State): String = when {
        s.isLoading -> "加载中…"
        s.error != null -> "加载失败，点「下一页」或「刷新」重试"
        s.items.isEmpty() -> "（空）"
        s.totalCount != null ->
            "第 ${s.pageIndex + 1}/${s.pageCount ?: (s.pageIndex + 1)} 页 · " +
                "本页 ${s.items.size} 条 · 共 ${s.totalCount} 条"
        else -> "第 ${s.pageIndex + 1} 页 · 本页 ${s.items.size} 条 · " +
            "第 ${s.pageStart + 1}–${s.pageStart + s.items.size} 条"
    }

    private fun showCollectDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_collect, null)
        dialogView.setTag(R.id.operation_log_scope, "采点")
        val spMap = dialogView.findViewById<Spinner>(R.id.spCollectMap)
        val etWpName = dialogView.findViewById<EditText>(R.id.etCollectWpName)
        val btnRefreshMap = dialogView.findViewById<Button>(R.id.btnCollectRefreshMap)
        val btnApplyMap = dialogView.findViewById<Button>(R.id.btnCollectApplyMap)
        val btnApplyWpName = dialogView.findViewById<Button>(R.id.btnCollectApplyWpName)
        val btnStart = dialogView.findViewById<Button>(R.id.btnCollectStart)
        val btnRecord = dialogView.findViewById<Button>(R.id.btnCollectRecord)
        val btnSave = dialogView.findViewById<Button>(R.id.btnCollectSave)
        val btnGen2D = dialogView.findViewById<Button>(R.id.btnCollectGen2D)
        val btnGenPixel = dialogView.findViewById<Button>(R.id.btnCollectGenPixel)
        val btnStop = dialogView.findViewById<Button>(R.id.btnCollectStop)
        val btnClose = dialogView.findViewById<Button>(R.id.btnCollectClose)
        val tvStatus = dialogView.findViewById<TextView>(R.id.tvCollectStatus)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvCollectTitle)
        val tvPageInfo = dialogView.findViewById<TextView>(R.id.tvCollectPageInfo)
        val btnPrevPage = dialogView.findViewById<Button>(R.id.btnCollectPrevPage)
        val btnNextPage = dialogView.findViewById<Button>(R.id.btnCollectNextPage)
        val btnAllPages = dialogView.findViewById<Button>(R.id.btnCollectAllPages)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        collectDialog = dialog

        // 地图 Spinner 适配器
        val mapAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf<String>()).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spMap.adapter = mapAdapter

        // 分页状态 → 标题 / 页码 / 按钮 / Spinner
        fun renderPage(state: FileListPager.State) {
            runOnUiThread {
                mapAdapter.clear()
                mapAdapter.addAll(state.items)
                mapAdapter.notifyDataSetChanged()
                if (state.items.isNotEmpty()) spMap.setSelection(0)
                tvTitle.text = buildListTitle("采点模式", state)
                tvPageInfo.text = buildPageInfo(state)
                btnPrevPage.isEnabled = state.hasPrev && !state.isLoading
                btnNextPage.isEnabled = state.hasNext && !state.isLoading
                btnAllPages.isEnabled = state.hasNext && !state.isLoading
            }
        }

        // 状态更新回调
        val updateUi = { state: ModeController.CollectState ->
            runOnUiThread {
                when (state) {
                    ModeController.CollectState.IDLE -> {
                        btnStart.isEnabled = true
                        btnRecord.isEnabled = false
                        btnSave.isEnabled = false
                        btnGen2D.isEnabled = false
                        btnGenPixel.isEnabled = false
                        btnStop.isEnabled = false
                        tvStatus.text = "● 就绪"
                        tvStatus.setTextColor(0xFFAAAAAA.toInt())
                    }
                    ModeController.CollectState.RUNNING -> {
                        btnStart.isEnabled = false
                        btnRecord.isEnabled = true
                        btnSave.isEnabled = true
                        btnGen2D.isEnabled = true
                        btnGenPixel.isEnabled = true
                        btnStop.isEnabled = true
                        tvStatus.text = "● 采点中..."
                        tvStatus.setTextColor(0xFF4CAF50.toInt())
                    }
                    ModeController.CollectState.MAP_2D_DONE -> {
                        // 保持可用，可重复操作
                        btnStart.isEnabled = false
                        btnRecord.isEnabled = true
                        btnSave.isEnabled = true
                        btnGen2D.isEnabled = true
                        btnGenPixel.isEnabled = true
                        btnStop.isEnabled = true
                        tvStatus.text = "● 2D地图已生成"
                        tvStatus.setTextColor(0xFF1976D2.toInt())
                    }
                    ModeController.CollectState.PIXEL_DONE -> {
                        // 保持可用，可重复操作
                        btnStart.isEnabled = false
                        btnRecord.isEnabled = true
                        btnSave.isEnabled = true
                        btnGen2D.isEnabled = true
                        btnGenPixel.isEnabled = true
                        btnStop.isEnabled = true
                        tvStatus.text = "● 像素坐标已生成"
                        tvStatus.setTextColor(0xFF1976D2.toInt())
                    }
                    else -> {}
                }
            }
        }

        modeController.onCollectStateChanged = { state ->
            updateUi(state)
            runOnUiThread {
                if (modeController.collectStopPending) {
                    listOf(btnRecord, btnSave, btnGen2D, btnGenPixel, btnStop, btnApplyMap, btnApplyWpName).forEach { it.isEnabled = false }
                    tvStatus.text = "正在结束，等待机载确认…"
                } else {
                    btnApplyMap.isEnabled = true
                    btnApplyWpName.isEnabled = true
                }
            }
        }
        modeController.onCollectStateChanged?.invoke(modeController.collectState)

        // 文件列表：手动翻页（每页一次请求，回退走缓存）
        modeController.onFileListPageState = { state -> renderPage(state) }
        btnPrevPage.setLoggedClickListener { modeController.prevPage() }
        btnNextPage.setLoggedClickListener { modeController.nextPage() }
        btnAllPages.setLoggedClickListener { modeController.loadAllPages() }

        // 初始加载第 1 页
        modeController.listMaps()

        // ── 按钮事件 ─────────────────────────────────────────
        btnRefreshMap.setLoggedClickListener { modeController.listMaps() }

        btnApplyMap.setLoggedClickListener {
            val mapFile = spMap.selectedItem?.toString() ?: return@setLoggedClickListener
            modeController.collectSetMap(mapFile)
        }

        btnApplyWpName.setLoggedClickListener {
            val wpName = etWpName.text.toString().trim()
            if (wpName.isNotEmpty()) {
                modeController.collectSetWpName(wpName)
            }
        }

        btnStart.setLoggedClickListener {
            val mapFile = spMap.selectedItem?.toString()
            val wpName = etWpName.text.toString().trim()
            if (mapFile != null) modeController.collectSetMap(mapFile)
            if (wpName.isNotEmpty()) modeController.collectSetWpName(wpName)
            modeController.collectStart()
            tvStatus.text = "请求开始采点，等待机载确认…"
        }

        btnRecord.setLoggedClickListener { waypointCtrl.recordWaypoint() }
        btnSave.setLoggedClickListener { waypointCtrl.saveWaypoints() }
        btnGen2D.setLoggedClickListener { modeController.collectGen2D() }
        btnGenPixel.setLoggedClickListener { modeController.collectGenPixel() }

        btnStop.setLoggedClickListener {
            confirmFinishCollect()
        }

        btnClose.setLoggedClickListener {
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
            if (collectDialog === dialog) collectDialog = null
            modeController.onFileListPageState = null
            modeController.onCollectStateChanged = { state ->
                runOnUiThread {
                    when (state) {
                        ModeController.CollectState.IDLE -> Log.i(TAG, "采点状态: 空闲")
                        ModeController.CollectState.RUNNING -> Log.i(TAG, "采点状态: 采点中")
                        ModeController.CollectState.MAP_2D_DONE -> Log.i(TAG, "采点状态: 2D已生成")
                        ModeController.CollectState.PIXEL_DONE -> Log.i(TAG, "采点状态: 像素已生成")
                        else -> {}
                    }
                }
            }
        }

        dialog.show()
    }

    private fun showCruiseDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_cruise, null)
        dialogView.setTag(R.id.operation_log_scope, "巡航")
        val etServer = dialogView.findViewById<EditText>(R.id.etCruiseServer)
        val etGimbalPitch = dialogView.findViewById<EditText>(R.id.etCruiseGimbalPitch)
        val btnSetGimbalPitch = dialogView.findViewById<Button>(R.id.btnCruiseSetGimbalPitch)
        val spWp = dialogView.findViewById<Spinner>(R.id.spCruiseWp)
        val btnRefreshWp = dialogView.findViewById<Button>(R.id.btnCruiseRefreshWp)
        val btnSelectWp = dialogView.findViewById<Button>(R.id.btnCruiseSelectWp)
        val btnStart = dialogView.findViewById<Button>(R.id.btnCruiseStart)
        val btnClose = dialogView.findViewById<Button>(R.id.btnCruiseClose)
        val tvStatus = dialogView.findViewById<TextView>(R.id.tvCruiseStatus)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvCruiseTitle)
        val tvPageInfo = dialogView.findViewById<TextView>(R.id.tvCruisePageInfo)
        val btnPrevPage = dialogView.findViewById<Button>(R.id.btnCruisePrevPage)
        val btnNextPage = dialogView.findViewById<Button>(R.id.btnCruiseNextPage)
        val btnAllPages = dialogView.findViewById<Button>(R.id.btnCruiseAllPages)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        val wpAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf<String>()).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spWp.adapter = wpAdapter

        fun setStatus(text: String, color: Int = 0xFFAAAAAA.toInt()) {
            runOnUiThread {
                tvStatus.text = text
                tvStatus.setTextColor(color)
            }
        }

        fun refreshWaypoints() {
            modeController.listWaypoints()
        }

        // 分页状态 → 标题 / 页码 / 按钮 / Spinner
        fun renderPage(state: FileListPager.State) {
            runOnUiThread {
                wpAdapter.clear()
                wpAdapter.addAll(state.items)
                wpAdapter.notifyDataSetChanged()
                if (state.items.isNotEmpty()) spWp.setSelection(0)
                tvTitle.text = buildListTitle("巡航模式", state)
                tvPageInfo.text = buildPageInfo(state)
                btnPrevPage.isEnabled = state.hasPrev && !state.isLoading
                btnNextPage.isEnabled = state.hasNext && !state.isLoading
                btnAllPages.isEnabled = state.hasNext && !state.isLoading
            }
        }

        // 文件列表：手动翻页
        modeController.onFileListPageState = { state -> renderPage(state) }
        btnPrevPage.setLoggedClickListener { modeController.prevPage() }
        btnNextPage.setLoggedClickListener { modeController.nextPage() }
        btnAllPages.setLoggedClickListener { modeController.loadAllPages() }

        // 初始加载第 1 页
        refreshWaypoints()

        btnRefreshWp.setLoggedClickListener { refreshWaypoints() }

        // 服务器地址变更时发到 mode_manager 存储
        etServer.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val addr = etServer.text.toString().trim()
                if (addr.isNotEmpty()) {
                    modeController.cruiseSetServer(addr)
                }
            }
        }
        // 打开弹窗时先发一次当前地址
        val initialAddr = etServer.text.toString().trim()
        if (initialAddr.isNotEmpty()) {
            modeController.cruiseSetServer(initialAddr)
        }

        // 云台俯仰角 → 点击"应用"按钮后发送到 mode_manager 存储
        btnSetGimbalPitch.setLoggedClickListener {
            val pitch = etGimbalPitch.text.toString().trim()
            if (pitch.isNotEmpty()) {
                modeController.cruiseSetGimbalPitch(pitch)
                setStatus("云台角度已设为 ${pitch}°", 0xFF4CAF50.toInt())
            }
        }

        // 选择航线 → 通过 PSDK 发到 mode_manager，由它调用 airlineInfo API
        btnSelectWp.setLoggedClickListener {
            val wpName = spWp.selectedItem?.toString() ?: return@setLoggedClickListener
            setStatus("正在选择航线...", 0xFFFF9800.toInt())
            modeController.cruiseSelectWp(wpName)
        }

        // 开始巡航 → 通过 PSDK 发到 mode_manager，由它调用 sendCommand API
        btnStart.setLoggedClickListener {
            setStatus("正在发送起飞指令...", 0xFFFF9800.toInt())
            btnStart.isEnabled = false
            modeController.cruiseStart()
            btnStart.text = "起飞指令已发送"
        }

        btnClose.setLoggedClickListener {
            dialog.dismiss()
        }

        dialog.setOnDismissListener { }

        dialog.show()
    }

    private fun showSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)
        dialogView.setTag(R.id.operation_log_scope, "设置")
        val etServerIp = dialogView.findViewById<EditText>(R.id.etServerIp)
        val etServerPort = dialogView.findViewById<EditText>(R.id.etServerPort)
        val etRemoteCtrlIp = dialogView.findViewById<EditText>(R.id.etRemoteCtrlIp)
        val etRemoteCtrlPort = dialogView.findViewById<EditText>(R.id.etRemoteCtrlPort)
        val etFtpServerIp = dialogView.findViewById<EditText>(R.id.etFtpServerIp)
        val etFtpServerPort = dialogView.findViewById<EditText>(R.id.etFtpServerPort)
        val etLocalPort = dialogView.findViewById<EditText>(R.id.etLocalPort)
        val btnClose = dialogView.findViewById<Button>(R.id.btnSettingsClose)
        val tvStatus = dialogView.findViewById<TextView>(R.id.tvSettingsStatus)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        fun setStatus(text: String, color: Int = 0xFFAAAAAA.toInt()) {
            runOnUiThread {
                tvStatus.text = text
                tvStatus.setTextColor(color)
            }
        }

        // 为每个配置项绑定保存按钮
        fun setupSaveButton(btnId: Int, etId: EditText, key: String) {
            dialogView.findViewById<Button>(btnId).setLoggedClickListener {
                val value = etId.text.toString().trim()
                if (value.isNotEmpty()) {
                    modeController.settingsUpdate(key, value)
                    setStatus("$key 已设为 $value", 0xFF4CAF50.toInt())
                } else {
                    setStatus("输入不能为空", 0xFFFF4444.toInt())
                }
            }
        }

        setupSaveButton(R.id.btnServerIp, etServerIp, "server_ip")
        setupSaveButton(R.id.btnServerPort, etServerPort, "server_port")
        setupSaveButton(R.id.btnRemoteCtrlIp, etRemoteCtrlIp, "remote_controller_ip")
        setupSaveButton(R.id.btnRemoteCtrlPort, etRemoteCtrlPort, "remote_controller_port")
        setupSaveButton(R.id.btnFtpServerIp, etFtpServerIp, "ftp_server_ip")
        setupSaveButton(R.id.btnFtpServerPort, etFtpServerPort, "ftp_server_port")
        setupSaveButton(R.id.btnLocalPort, etLocalPort, "local_port")

        // 配置回读回调：更新所有 EditText
        modeController.onSettingsResponse = { config ->
            runOnUiThread {
                config["server_ip"]?.let { etServerIp.setText(it) }
                config["server_port"]?.let { etServerPort.setText(it) }
                config["remote_controller_ip"]?.let { etRemoteCtrlIp.setText(it) }
                config["remote_controller_port"]?.let { etRemoteCtrlPort.setText(it) }
                config["ftp_server_ip"]?.let { etFtpServerIp.setText(it) }
                config["ftp_server_port"]?.let { etFtpServerPort.setText(it) }
                config["local_port"]?.let { etLocalPort.setText(it) }
                setStatus("✅ 配置已刷新", 0xFF4CAF50.toInt())
            }
        }

        btnClose.setLoggedClickListener { dialog.dismiss() }
        dialog.show()
    }

    /** 所有应用自有按钮从同一入口记录，随后执行原有动作，不改变受理/完成语义。 */
    private fun View.setLoggedClickListener(listener: (View) -> Unit) {
        setOnClickListener { clicked ->
            runCatching {
                val idName = runCatching { resources.getResourceEntryName(clicked.id) }.getOrDefault("")
                val specialNames = mapOf(
                    "btnVelXPlus" to "X轴速度增加", "btnVelXMinus" to "X轴速度减少",
                    "btnVelYPlus" to "Y轴速度增加", "btnVelYMinus" to "Y轴速度减少",
                    "btnVelZPlus" to "Z轴速度增加", "btnVelZMinus" to "Z轴速度减少",
                    "btnVelYawPlus" to "偏航速度增加", "btnVelYawMinus" to "偏航速度减少",
                    "btnCollectApplyMap" to "应用地图", "btnCollectApplyWpName" to "应用航点文件名",
                    "btnServerIp" to "保存服务器IP", "btnServerPort" to "保存服务器端口",
                    "btnRemoteCtrlIp" to "保存遥控器IP", "btnRemoteCtrlPort" to "保存遥控器端口",
                    "btnFtpServerIp" to "保存FTP服务器IP", "btnFtpServerPort" to "保存FTP端口",
                    "btnLocalPort" to "保存本地端口"
                )
                val label = specialNames[idName] ?: (clicked as? TextView)?.text?.toString()
                    ?.replace('\n', ' ')?.takeIf { it.isNotBlank() } ?: idName
                var scope: View? = clicked
                while (scope != null && scope.getTag(R.id.operation_log_scope) == null) scope = scope.parent as? View
                val title = scope?.getTag(R.id.operation_log_scope)?.toString()
                val details = if (scope != null) operationInputSnapshot(scope) else if (
                    idName == "btnLiveStreamSave" || idName == "btnLiveStreamAction") {
                    "推流地址=${liveStreamAddressInput.text.toString().take(240)}"
                } else ""
                OperationLogger.operation((title?.let { "$it：" } ?: "") +
                    if (label == "✕" || label == "×") "关闭窗口" else label, details)
            }.onFailure { Log.w(TAG, "按钮操作日志入队失败", it) }
            listener(clicked)
        }
    }

    private fun operationInputSnapshot(root: View): String {
        val values = mutableListOf<String>()
        val labels = mapOf(
            "etMappingMapName" to "地图名", "spCollectMap" to "地图", "etCollectWpName" to "航点名",
            "spCruiseWp" to "航线", "etCruiseGimbalPitch" to "云台俯仰", "etServerIp" to "服务器IP",
            "etServerPort" to "服务器端口", "etRemoteCtrlIp" to "遥控器IP", "etRemoteCtrlPort" to "遥控器端口",
            "etFtpServerIp" to "FTP服务器IP", "etFtpServerPort" to "FTP端口", "etLocalPort" to "本地端口"
        )
        fun visit(view: View) {
            val name = runCatching { resources.getResourceEntryName(view.id) }.getOrDefault("")
            if (view is EditText) {
                val variation = view.inputType and InputType.TYPE_MASK_VARIATION
                val secret = variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) ||
                    (view.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_NUMBER &&
                        variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
                values.add("${labels[name] ?: name}=${if (secret) "[已隐藏]" else view.text.toString().take(240)}")
            } else if (view is Spinner) {
                values.add("${labels[name] ?: name}=${view.selectedItem?.toString().orEmpty().take(240)}")
            } else if (view is ViewGroup) repeat(view.childCount) { visit(view.getChildAt(it)) }
        }
        visit(root)
        return values.joinToString("；").take(1000)
    }

    /** SDK 控件保持原有监听器；这里只观察轻触，不消费事件，也不把点击当作执行成功。 */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (::functionMenu.isInitialized) functionMenu.observeTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                sdkTapTarget = null; sdkTapLabel = null
                sdkTapX = event.rawX; sdkTapY = event.rawY
                val targets = mutableListOf<Pair<View, String>>()
                if (::shootPhotoWidget.isInitialized) targets.add(shootPhotoWidget to "拍照控件")
                if (::recordVideoWidget.isInitialized) targets.add(recordVideoWidget to "录像控件")
                if (::photoVideoSwitchWidget.isInitialized) targets.add(photoVideoSwitchWidget to "拍照/录像模式控件")
                targets.firstOrNull { (view, _) ->
                    val bounds = Rect()
                    view.isShown && view.isEnabled && view.getGlobalVisibleRect(bounds) && bounds.contains(event.rawX.toInt(), event.rawY.toInt())
                }?.let { (view, name) -> sdkTapTarget = view; sdkTapLabel = name }
            }
            MotionEvent.ACTION_MOVE -> {
                val slop = ViewConfiguration.get(this).scaledTouchSlop
                if (kotlin.math.hypot((event.rawX - sdkTapX).toDouble(), (event.rawY - sdkTapY).toDouble()) > slop) {
                    sdkTapTarget = null; sdkTapLabel = null
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> { sdkTapTarget = null; sdkTapLabel = null }
            MotionEvent.ACTION_UP -> {
                val view = sdkTapTarget
                val bounds = Rect()
                if (view != null && view.isShown && view.isEnabled && view.getGlobalVisibleRect(bounds) &&
                    bounds.contains(event.rawX.toInt(), event.rawY.toInt()) &&
                    event.eventTime - event.downTime <= ViewConfiguration.getLongPressTimeout()) {
                    OperationLogger.operation(sdkTapLabel ?: "相机控件")
                }
                sdkTapTarget = null; sdkTapLabel = null
            }
        }
        return super.dispatchTouchEvent(event)
    }
}
