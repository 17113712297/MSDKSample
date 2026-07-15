package com.example.msdksample

import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
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
import dji.sdk.keyvalue.key.DJIKey
import dji.sdk.keyvalue.key.CameraKey
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
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        const val TAG = "DBG_CAM"
        const val TAG_SDK = "DBG_SDK"
        const val REATTACH_DELAY = 3_000L
        const val POLL_INTERVAL = 500L
        const val AUTO_STREAM_RETRY_DELAY_MS = 3_000L
        const val AUTO_STREAM_MAX_ATTEMPTS = 10
        const val VISUAL_LAND_CONFIRM_POLL_MS = 500L
        const val VISUAL_LAND_CONFIRM_TIMEOUT_MS = 20_000L
        const val VISUAL_LAND_GROUNDED_POLLS_REQUIRED = 2
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

    private lateinit var xSpeedText: TextView
    private lateinit var ySpeedText: TextView
    private lateinit var zSpeedText: TextView
    private lateinit var yawRateText: TextView
    private lateinit var remainingTimeText: TextView
    private lateinit var transferDebugText: TextView
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

    private var testCameraController: CameraController? = null
    private var visionController: VisionController? = null
    private lateinit var liveStreamController: LiveStreamController
    private lateinit var deviceStatusReportManager: DeviceStatusReportManager
private lateinit var landingController: LandingController
    private lateinit var preflightController: PreflightController

    // ★ 新模式控制器
    private lateinit var modeController: ModeController
    private lateinit var btnModeMapping: Button
    private lateinit var btnModeCollect: Button
    private lateinit var btnModeCruise: Button
    private lateinit var videoTransferManager: VideoTransferManager
    @Volatile private var currentTargetId = -1
    @Volatile private var previousLandingState: TaskState = TaskState.INACTIVE

    private var autoStreamAttemptCount = 0
    private var autoStreamAwaitingResult = false
    private var autoStreamStopped = false
    private var liveStreamTouchedByUser = false
    private var isLiveStreamPanelVisible = false
    private var recordingMonitorKey: DJIKey<Boolean>? = null
    private var lastRecordingState: Boolean? = null
    @Volatile private var awaitingVisualLandConfirmation = false

    private val pollHandler = Handler(Looper.getMainLooper())
    private val visualLandingHandler = Handler(Looper.getMainLooper())
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
            videoTransferManager.statusCallback = { status ->
                runOnUiThread { renderTransferDebugStatus(status) }
            }
            setupLiveStreamController()
            deviceStatusReportManager.start()
            landingController = LandingController()
            preflightController = PreflightController()
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
            // ★ 初始化模式控制器
            modeController = ModeController()
            DroneControlService.modeController = modeController
            setupModeController()

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

        btnAutoLanding?.setOnClickListener { onLandingClicked() }
        btnTakeoff?.setOnClickListener { onTakeoffClicked() }

        // ★ 模式按钮点击
        btnModeMapping.setOnClickListener { showMappingDialog() }
        btnModeCollect.setOnClickListener { showCollectDialog() }
        btnModeCruise.setOnClickListener { showCruiseDialog() }
        // ═══════════════════════════════════════════════════════
        // ★ 速度控制面板初始化
        // ═══════════════════════════════════════════════════════
        setupVelocityPanel()

        window.decorView.postDelayed({ reattachStatusWidgets() }, REATTACH_DELAY)
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, DroneControlService::class.java))
        }
        VideoUploadCommandService.start(this)
    }

    override fun onResume() {
        super.onResume()
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
        pollHandler.removeCallbacks(pollRunnable)
        pollHandler.removeCallbacks(autoStartLiveStreamRunnable)
        compositeDisposable?.dispose()
        compositeDisposable = null
        if (::liveStreamController.isInitialized && liveStreamController.isStreaming()) {
            liveStreamController.stopStreamIfNeeded()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        pollHandler.removeCallbacks(autoStartLiveStreamRunnable)
        visualLandingHandler.removeCallbacksAndMessages(null)
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
            videoTransferManager.release()
        }
        DroneControlService.onEnqueueLatestVideoTransfer = null
        DroneControlService.onStartCameraStream = null
        DroneControlService.onStopCameraStream = null
        DroneControlService.onResetVisionTracking = null
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
                                    if (yawRateText.currentTextColor != 0xFFD32F2F.toInt() &&
                                        yawRateText.currentTextColor != 0xFF4CAF50.toInt()
                                    ) {
                                        yawRateText.text = "%+.1f".format(delta / dtSec)
                                    }
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
        landingController.onTaskStateChanged = { state ->
            runOnUiThread {
                when (state) {
                    TaskState.INACTIVE -> {
                        btnAutoLanding?.text = "视觉降落"
                        btnAutoLanding?.setBackgroundColor(0xFFD32F2F.toInt())
                        btnAutoLanding?.isEnabled = true
                        btnTakeoff?.isEnabled = true
                    }

                    TaskState.LANDING_PREP -> {
                        btnAutoLanding?.text = "准备中..."
                        btnAutoLanding?.setBackgroundColor(0xFFFF9800.toInt())
                        btnAutoLanding?.isEnabled = false
                        btnTakeoff?.isEnabled = false
                    }

                    TaskState.LANDING -> {
                        btnAutoLanding?.text = "中止降落"
                        btnAutoLanding?.setBackgroundColor(0xFF4CAF50.toInt())
                        btnAutoLanding?.isEnabled = true
                        btnTakeoff?.isEnabled = false
                        clearErrorOnUI()
                        previousLandingState = TaskState.LANDING
                    }
                }
            }
            if (state == TaskState.INACTIVE && previousLandingState == TaskState.LANDING) {
                previousLandingState = TaskState.INACTIVE
                DroneControlService.onStopCameraStream?.invoke()
            }
        }

        landingController.onError = { msg ->
            runOnUiThread { showErrorOnUI(msg) }
        }
        landingController.onMessage = { msg ->
            if (isVisualLandingSuccessMessage(msg)) {
                awaitVisualLandingConfirmation()
            }
            runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_LONG).show() }
        }

        preflightController.onCheckStarted = {
            runOnUiThread {
                btnTakeoff?.isEnabled = false
                btnTakeoff?.text = "正在检查..."
                Toast.makeText(this, "正在进行起飞前安全检查...", Toast.LENGTH_SHORT).show()
                clearErrorOnUI()
            }
        }

        preflightController.onCheckPassed = {
            runOnUiThread {
                btnTakeoff?.isEnabled = true
                btnTakeoff?.text = "检查通过"
                yawRateText.text = "检查完毕，可以起飞"
                yawRateText.setTextColor(0xFF4CAF50.toInt())
                yawRateText.textSize = 14f
                Toast.makeText(this, "安全检查通过", Toast.LENGTH_LONG).show()
            }
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
            visionController = VisionController()

            visionController?.onTargetLocked = { id, errX, errY, depthZ, yawDeg ->
                currentTargetId = id
                landingController.updateVisionData(id, errX, errY, depthZ, yawDeg)
            }

            testCameraController?.frameCallback = { data, offset, length, width, height ->
                try {
                    val isLanding =
                        if (::landingController.isInitialized) landingController.getTaskState() == TaskState.LANDING else false
                    val isPreflightChecking =
                        if (::preflightController.isInitialized) preflightController.isChecking else false

                    if (isLanding || isPreflightChecking) {
                        currentTargetId = -1
                        visionController?.processFrame(data, offset, length, width, height)
                    }

                    if (isPreflightChecking) {
                        val isTargetLocked = currentTargetId != -1
                        preflightController.processFrame(data, offset, width, height, isTargetLocked)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "视觉处理异常: ${e.message}", e)
                }
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
            ensureVisionSystemReady()
            visionController?.resetTracking()
            testCameraController?.startVideoStream()
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
        ensureVisionSystemReady()
        testCameraController?.startVideoStream()
        preflightController.startCheck()
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
        transferDebugText = findViewById(R.id.transferDebugText)
        liveStreamLayout = findViewById(R.id.liveStreamLayout)
        liveStreamAddressText = findViewById(R.id.liveStreamAddressText)
        liveStreamStatusText = findViewById(R.id.liveStreamStatusText)
        liveStreamAddressInput = findViewById(R.id.liveStreamAddressInput)
        btnLiveStreamSave = findViewById(R.id.btnLiveStreamSave)

        btnAutoLanding = findViewById(R.id.btnAutoLanding)
        btnTakeoff = findViewById(R.id.btnTakeoff)

        // ★ 模式按钮
        btnModeMapping = findViewById(R.id.btnModeMapping)
        btnModeCollect = findViewById(R.id.btnModeCollect)
        btnModeCruise = findViewById(R.id.btnModeCruise)

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
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // 面板状态变更 → 更新按钮外观
        velocityPanel.onPanelStateChanged = { state ->
            runOnUiThread {
                when (state) {
                    VelocityControlPanel.PanelState.IDLE -> {
                        btnVelStartStop.text = "启动速度控制"
                        btnVelStartStop.setBackgroundColor(
                            if (velocityPanel.isInNMode()) 0xFF4CAF50.toInt()
                            else 0xFF555555.toInt()
                        )
                        btnVelStartStop.isEnabled = velocityPanel.isInNMode()
                        btnVelZero.isEnabled = false
                        btnVelZero.setBackgroundColor(0xFF555555.toInt())
                        setVelButtonsEnabled(false)
                        txtVelStatus.text = if (velocityPanel.isInNMode()) "N挡就绪" else "等待N挡..."
                        txtVelStatus.setTextColor(
                            if (velocityPanel.isInNMode()) 0xFF888888.toInt()
                            else 0xFFFF9800.toInt()
                        )
                    }
                    VelocityControlPanel.PanelState.ACTIVE -> {
                        btnVelStartStop.text = "关闭速度控制"
                        btnVelStartStop.setBackgroundColor(0xFFD32F2F.toInt())
                        btnVelStartStop.isEnabled = true
                        btnVelZero.isEnabled = true
                        btnVelZero.setBackgroundColor(0xFFFF9800.toInt())
                        setVelButtonsEnabled(true)
                        txtVelStatus.text = "● 控制中"
                        txtVelStatus.setTextColor(0xFF4CAF50.toInt())
                    }
                }
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

        btnVelStartStop.setOnClickListener {
            velocityPanel.toggleStartStop()
            velocityPanel.updateStartButtonState(btnVelStartStop)
        }

        btnVelZero.setOnClickListener {
            if (velocityPanel.getState() == VelocityControlPanel.PanelState.ACTIVE) {
                velocityPanel.zeroVelocity()
                Toast.makeText(this, "速度已归零 → 悬停", Toast.LENGTH_SHORT).show()
            }
        }

        // Yaw 偏航 +/-
        btnVelYawPlus.setOnClickListener  { velocityPanel.adjustYaw(+1f) }
        btnVelYawMinus.setOnClickListener { velocityPanel.adjustYaw(-1f) }

        // X 轴速度 +/-
        btnVelXPlus.setOnClickListener  { velocityPanel.adjustVelocity('X', +1f) }
        btnVelXMinus.setOnClickListener { velocityPanel.adjustVelocity('X', -1f) }

        // Y 轴速度 +/-
        btnVelYPlus.setOnClickListener  { velocityPanel.adjustVelocity('Y', +1f) }
        btnVelYMinus.setOnClickListener { velocityPanel.adjustVelocity('Y', -1f) }

        // Z 轴速度 +/-
        btnVelZPlus.setOnClickListener  { velocityPanel.adjustVelocity('Z', +1f) }
        btnVelZMinus.setOnClickListener { velocityPanel.adjustVelocity('Z', -1f) }

        // 初始状态：所有速度按钮禁用
        setVelButtonsEnabled(false)
        btnVelZero.isEnabled = false
        btnVelZero.setBackgroundColor(0xFF555555.toInt())
        velocityPanel.updateStartButtonState(btnVelStartStop)

        Log.i(TAG, "✅ 速度控制面板初始化完成")
    }

    private fun setVelButtonsEnabled(enabled: Boolean) {
        listOf(btnVelYawPlus, btnVelYawMinus,
               btnVelXPlus, btnVelXMinus,
               btnVelYPlus, btnVelYMinus,
               btnVelZPlus, btnVelZMinus).forEach { btn ->
            btn.isEnabled = enabled
            if (!enabled) btn.setBackgroundColor(0xFF555555.toInt())
        }
        if (enabled) {
            btnVelYawPlus.setBackgroundColor(0xFF9C27B0.toInt())
            btnVelYawMinus.setBackgroundColor(0xFF9C27B0.toInt())
            btnVelXPlus.setBackgroundColor(0xFF1976D2.toInt())
            btnVelXMinus.setBackgroundColor(0xFF1976D2.toInt())
            btnVelYPlus.setBackgroundColor(0xFF388E3C.toInt())
            btnVelYMinus.setBackgroundColor(0xFF388E3C.toInt())
            btnVelZPlus.setBackgroundColor(0xFFF57C00.toInt())
            btnVelZMinus.setBackgroundColor(0xFFF57C00.toInt())
        }
    }

    private fun showErrorOnUI(msg: String) {
        yawRateText.text = msg
        yawRateText.setTextColor(0xFFD32F2F.toInt())
        yawRateText.textSize = 12f
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    private fun clearErrorOnUI() {
        yawRateText.setTextColor(0xFFFFFFFF.toInt())
        yawRateText.textSize = 14f
        yawRateText.text = "0.0"
    }

private fun renderTransferDebugStatus(message: String) {
        transferDebugText.visibility = View.VISIBLE
        transferDebugText.text = message
        val color = when {
            message.contains("失败") || message.contains("未确认") || message.contains("异常") ->
                0xFFFFCDD2.toInt()
            message.contains("完成") || message.contains("成功") ->
                0xFFC8E6C9.toInt()
            else -> 0xFFFFFFFF.toInt()
        }
        transferDebugText.setTextColor(color)
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
        btnLensWide.setOnClickListener { switchLens(CameraVideoStreamSourceType.WIDE_CAMERA) }
        btnLensZoom.setOnClickListener { switchLens(CameraVideoStreamSourceType.ZOOM_CAMERA) }
        btnLensThermal.setOnClickListener { switchLens(CameraVideoStreamSourceType.INFRARED_CAMERA) }
    }

    private fun setupWaypointButtons() {
        btnRecordWaypoint.setOnClickListener { waypointCtrl.recordWaypoint() }
        btnSaveWaypoints.setOnClickListener { waypointCtrl.saveWaypoints() }
        btnClearWaypoints.setOnClickListener { waypointCtrl.clearWaypoints() }
    }

    private fun setupLiveStreamController() {
        liveStreamController.onStateChanged = { state ->
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

    private fun isVisualLandingSuccessMessage(message: String): Boolean {
        return message.contains("自动直降完成") || message.contains("强制停桨完成")
    }

    private fun awaitVisualLandingConfirmation() {
        if (awaitingVisualLandConfirmation) {
            Log.i(TAG, "Visual landing confirmation is already in progress")
            return
        }

        awaitingVisualLandConfirmation = true
        val startTimeMs = System.currentTimeMillis()
        var groundedPollCount = 0

        val poll = object : Runnable {
            override fun run() {
                val isFlying = runCatching {
                    KeyManager.getInstance().getValue(
                        KeyTools.createKey(FlightControllerKey.KeyIsFlying)
                    ) ?: false
                }.getOrDefault(true)

                groundedPollCount = if (!isFlying) groundedPollCount + 1 else 0

                if (groundedPollCount >= VISUAL_LAND_GROUNDED_POLLS_REQUIRED) {
                    awaitingVisualLandConfirmation = false
                    Log.i(TAG, "Visual landing confirmed by KeyIsFlying=false")
                    handleVisualLandingConfirmed()
                    return
                }

                if (System.currentTimeMillis() - startTimeMs >= VISUAL_LAND_CONFIRM_TIMEOUT_MS) {
                    awaitingVisualLandConfirmation = false
                    val msg = "视觉降落任务已结束，但未确认无人机已落地，未发送完成通知"
                    Log.w(TAG, msg)
                    runOnUiThread { showErrorOnUI(msg) }
                    return
                }

                visualLandingHandler.postDelayed(this, VISUAL_LAND_CONFIRM_POLL_MS)
            }
        }

        visualLandingHandler.post(poll)
    }

    private fun handleVisualLandingConfirmed() {
        runCatching {
            val frame = DroneCommProtocol.encodeSimple(DroneCommProtocol.CMD_ACK_LAND_COMPLETE)
            DroneControlService.sendFrame(frame)
        }.onFailure {
            Log.w(TAG, "Failed to send visual landing completion ACK: ${it.message}")
        }
    }

private fun setupLiveStreamButton() {
        btnLiveStreamPanel.setOnClickListener {
            toggleLiveStreamPanel()
        }
        btnLiveStreamSave.setOnClickListener {
            val address = liveStreamAddressInput.text?.toString().orEmpty()
            liveStreamController.updateConfiguredStreamAddress(address)
            if (::deviceStatusReportManager.isInitialized) {
                deviceStatusReportManager.reportNow()
            }
            Toast.makeText(this, "推流地址已保存", Toast.LENGTH_SHORT).show()
        }
        btnLiveStreamAction.setOnClickListener {
            if (!::liveStreamController.isInitialized) {
                Toast.makeText(this, "直播模块尚未初始化", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
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
                    DroneControlService.updateCurrentLens(streamTypeToCode(target))
                    if (::liveStreamController.isInitialized) {
                        liveStreamController.updateLiveStreamCameraSource(currentCameraIndex)
                        liveStreamController.refreshConfiguredStreamAddress()
                    }
                }

                override fun onFailure(error: IDJIError) {
                    Log.e(TAG, "切换失败: ${error.description()}")
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

    // ═══════════════════════════════════════════════════════
    // ★ 模式控制
    // ═══════════════════════════════════════════════════════

    private fun setupModeController() {
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

    private fun showMappingDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_mapping, null)
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

        btnStart.setOnClickListener {
            val name = etMapName.text.toString().trim()
            if (name.isNotEmpty()) {
                modeController.mappingSetName(name)
            }
            modeController.mappingStart()
        }

        btnSave.setOnClickListener {
            val name = etMapName.text.toString().trim()
            modeController.mappingSaveMap(name)
        }

        btnStop.setOnClickListener {
            modeController.mappingStop()
            updateUi(ModeController.MappingState.IDLE)
        }

        btnClose.setOnClickListener {
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

    private fun showCollectDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_collect, null)
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

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        val waypointCtrl = WaypointController()

        // 地图 Spinner 适配器
        val mapAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, mutableListOf<String>()).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spMap.adapter = mapAdapter

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

        modeController.onCollectStateChanged = { state -> updateUi(state) }
        updateUi(modeController.collectState)

        // 文件列表更新回调 → 刷新地图 Spinner
        modeController.onFileListUpdated = {
            runOnUiThread {
                mapAdapter.clear()
                mapAdapter.addAll(modeController.mapFileList)
                mapAdapter.notifyDataSetChanged()
                if (modeController.mapFileList.isNotEmpty()) {
                    spMap.setSelection(0)
                }
            }
        }

        // 初始加载地图列表
        modeController.listMaps()

        // ── 按钮事件 ─────────────────────────────────────────
        btnRefreshMap.setOnClickListener { modeController.listMaps() }

        btnApplyMap.setOnClickListener {
            val mapFile = spMap.selectedItem?.toString() ?: return@setOnClickListener
            modeController.collectSetMap(mapFile)
        }

        btnApplyWpName.setOnClickListener {
            val wpName = etWpName.text.toString().trim()
            if (wpName.isNotEmpty()) {
                modeController.collectSetWpName(wpName)
            }
        }

        btnStart.setOnClickListener {
            val mapFile = spMap.selectedItem?.toString()
            val wpName = etWpName.text.toString().trim()
            if (mapFile != null) modeController.collectSetMap(mapFile)
            if (wpName.isNotEmpty()) modeController.collectSetWpName(wpName)
            modeController.collectStart()
        }

        btnRecord.setOnClickListener { waypointCtrl.recordWaypoint() }
        btnSave.setOnClickListener { waypointCtrl.saveWaypoints() }
        btnGen2D.setOnClickListener { modeController.collectGen2D() }
        btnGenPixel.setOnClickListener { modeController.collectGenPixel() }

        btnStop.setOnClickListener {
            modeController.collectStop()
            updateUi(ModeController.CollectState.IDLE)
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setOnDismissListener {
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
        val etServer = dialogView.findViewById<EditText>(R.id.etCruiseServer)
        val etGimbalPitch = dialogView.findViewById<EditText>(R.id.etCruiseGimbalPitch)
        val btnSetGimbalPitch = dialogView.findViewById<Button>(R.id.btnCruiseSetGimbalPitch)
        val spWp = dialogView.findViewById<Spinner>(R.id.spCruiseWp)
        val btnRefreshWp = dialogView.findViewById<Button>(R.id.btnCruiseRefreshWp)
        val btnSelectWp = dialogView.findViewById<Button>(R.id.btnCruiseSelectWp)
        val btnStart = dialogView.findViewById<Button>(R.id.btnCruiseStart)
        val btnClose = dialogView.findViewById<Button>(R.id.btnCruiseClose)
        val tvStatus = dialogView.findViewById<TextView>(R.id.tvCruiseStatus)

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

        // 文件列表更新回调 → 刷新航线 Spinner
        modeController.onFileListUpdated = {
            runOnUiThread {
                wpAdapter.clear()
                wpAdapter.addAll(modeController.waypointFileList)
                wpAdapter.notifyDataSetChanged()
                if (modeController.waypointFileList.isNotEmpty()) {
                    spWp.setSelection(0)
                }
            }
        }

        // 初始加载航线列表
        refreshWaypoints()

        btnRefreshWp.setOnClickListener { refreshWaypoints() }

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
        btnSetGimbalPitch.setOnClickListener {
            val pitch = etGimbalPitch.text.toString().trim()
            if (pitch.isNotEmpty()) {
                modeController.cruiseSetGimbalPitch(pitch)
                setStatus("云台角度已设为 ${pitch}°", 0xFF4CAF50.toInt())
            }
        }

        // 选择航线 → 通过 PSDK 发到 mode_manager，由它调用 airlineInfo API
        btnSelectWp.setOnClickListener {
            val wpName = spWp.selectedItem?.toString() ?: return@setOnClickListener
            setStatus("正在选择航线...", 0xFFFF9800.toInt())
            modeController.cruiseSelectWp(wpName)
        }

        // 开始巡航 → 通过 PSDK 发到 mode_manager，由它调用 sendCommand API
        btnStart.setOnClickListener {
            setStatus("正在发送起飞指令...", 0xFFFF9800.toInt())
            btnStart.isEnabled = false
            modeController.cruiseStart()
            btnStart.text = "起飞指令已发送"
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.setOnDismissListener { }

        dialog.show()
    }
}
