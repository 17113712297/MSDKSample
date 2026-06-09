package com.example.msdksample

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
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
        const val TAG            = "DBG_CAM"
        const val TAG_SDK        = "DBG_SDK"
        const val REATTACH_DELAY = 3_000L
        const val POLL_INTERVAL  = 500L
    }

    // ── 相机相关 Widget ──────────────────────────────────────────────
    private lateinit var fpvWidget: FPVWidget
    private lateinit var shootPhotoWidget: ShootPhotoWidget
    private lateinit var recordVideoWidget: RecordVideoWidget
    private lateinit var focalZoomWidget: FocalZoomWidget
    private lateinit var photoVideoSwitchWidget: PhotoVideoSwitchWidget

    // 镜头切换按钮
    private lateinit var btnLensWide: Button
    private lateinit var btnLensZoom: Button
    private lateinit var btnLensThermal: Button

    // 航点记录按钮 (保留原有功能)
    private lateinit var btnRecordWaypoint: Button
    private lateinit var btnSaveWaypoints: Button
    private lateinit var btnClearWaypoints: Button

    // 新增：视觉/预检按钮 (来自代码段2)
    private var btnAutoLanding: Button? = null
    private var btnTakeoff: Button? = null

    // ── 左侧 HUD TextView ────────────────────────────────────────────
    private lateinit var xSpeedText: TextView
    private lateinit var ySpeedText: TextView
    private lateinit var zSpeedText: TextView
    private lateinit var yawRateText: TextView
    private lateinit var remainingTimeText: TextView

    // ── 状态管理 ───────────────────────────────────────────
    private var currentCameraIndex: ComponentIndexType = ComponentIndexType.LEFT_OR_MAIN

    private val cameraSourceProcessor = DataProcessor.create(
        Pair(ComponentIndexType.UNKNOWN, CameraLensType.UNKNOWN)
    )
    private var compositeDisposable: CompositeDisposable? = null

    private var lastYawDeg    = Double.NaN
    private var lastYawTimeMs = 0L
    private val waypointCtrl = WaypointController()

    // ── 新增：核心视觉与控制组件 ─────────────────────────────────────────
    private var testCameraController: CameraController? = null
    private var visionController: VisionController? = null
    private lateinit var landingController: LandingController
    private lateinit var preflightController: PreflightController
    @Volatile private var currentTargetId = -1

    // ── 新增：跟踪降落状态以检测 LANDING→INACTIVE 完成信号 ──
    @Volatile private var previousLandingState: TaskState = TaskState.INACTIVE

    // ── 轮询 Handler (保留原有功能) ─────────────────────────────────────────────────
    private val pollHandler  = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            pollVelocity()
            pollYawRate()
            pollRemainingFlightTime()
            pollHandler.postDelayed(this, POLL_INTERVAL)
        }
    }

    // ── 相机流可用性监听器 (合并两者逻辑) ────────────────────────────────────────────
    private val availableCameraUpdatedListener =
        object : ICameraStreamManager.AvailableCameraUpdatedListener {
            override fun onAvailableCameraUpdated(list: MutableList<ComponentIndexType>) {
                Log.d(TAG, "onAvailableCameraUpdated: $list")
                runOnUiThread {
                    if (list.isNullOrEmpty()) return@runOnUiThread
                    val source = if (list.contains(ComponentIndexType.LEFT_OR_MAIN))
                        ComponentIndexType.LEFT_OR_MAIN else list[0]

                    currentCameraIndex = source
                    fpvWidget.updateVideoSource(source)

                    // ★ 将源同步给新加入的控制器
                    if (::landingController.isInitialized) {
                        landingController.currentCameraIndex = source
                    }
                    if (::preflightController.isInitialized) {
                        preflightController.currentCameraIndex = source
                    }
                }
            }
            override fun onCameraStreamEnableUpdate(map: MutableMap<ComponentIndexType, Boolean>) {}
        }

    // ── 生命周期 ──────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. 初始化 OpenCV
        Thread {
            val ok = OpenCVLoader.initDebug()
            Log.i(TAG, "OpenCV init: $ok")
        }.start()

        setContentView(R.layout.activity_main)

        // 2. 绑定已有与新增的 UI 组件
        initUIWidgets()

        // 3. ★ 初始化新增的控制器（带安全保护）
        try {
            landingController = LandingController()
            preflightController = PreflightController()
            setupControllerCallbacks()

            // 注入控制器引用给 Service
            DroneControlService.preflightController = preflightController
            DroneControlService.landingController = landingController

            // ★ 新增：注入开启与关闭相机流的闭包
            DroneControlService.onStartCameraStream = {
                runOnUiThread { // 确保在主线程初始化 UI 强相关的视觉组件
                    ensureVisionSystemReady()
                    visionController?.resetTracking() // PSDK 触发视觉降落也需要重置追踪
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
            Log.e(TAG, "⚠️ 控制器初始化失败 (SDK可能未就绪或未连接飞机)", e)
            showErrorOnUI("控制器初始化失败，请确保飞机已连接！")
        }

        // 4. 设置相机数据流监听
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

        // 5. 设置各种按钮点击事件
        setupLensButtons()
        setupWaypointButtons()

        // 新增的视觉降落与起飞检查点击事件
        btnAutoLanding?.setOnClickListener { onLandingClicked() }
        btnTakeoff?.setOnClickListener { onTakeoffClicked() }

        // 6. 挂载系统状态Widget并启动服务
        window.decorView.postDelayed({ reattachStatusWidgets() }, REATTACH_DELAY)
        runCatching {
            ContextCompat.startForegroundService(this, Intent(this, DroneControlService::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
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
                            // 保持原有的服务同步逻辑
                            DroneControlService.updateCurrentLens(lensTypeToCode(lens))
                        }
                    },
                    { e -> Log.e(TAG, "cameraSource 错误: ${e.message}") }
                )
        )
        // 恢复原有轮询
        pollHandler.post(pollRunnable)
    }

    override fun onPause() {
        super.onPause()
        pollHandler.removeCallbacks(pollRunnable)
        compositeDisposable?.dispose()
        compositeDisposable = null
    }

    override fun onDestroy() {
        super.onDestroy()
        MediaDataCenter.getInstance()
            .getCameraStreamManager()
            .removeAvailableCameraUpdatedListener(availableCameraUpdatedListener)

        // 释放新增控制器资源
        if (::landingController.isInitialized) landingController.release()
        if (::preflightController.isInitialized) preflightController.release()
        visionController?.release()
        runCatching { testCameraController?.stopVideoStream() }
    }

    // ── HUD 轮询相关 (保持原样) ───────────────────────────────────────
    private fun pollVelocity() {
        try {
            KeyManager.getInstance().getValue(
                KeyTools.createKey(FlightControllerKey.KeyAircraftVelocity),
                object : CommonCallbacks.CompletionCallbackWithParam<Velocity3D> {
                    override fun onSuccess(value: Velocity3D?) {
                        value ?: return
                        runOnUiThread {
                            xSpeedText.text = "%+.2f m/s".format(value.x)
                            ySpeedText.text = "%+.2f m/s".format(value.y)
                            zSpeedText.text = "%+.2f m/s".format(value.z)
                        }
                    }
                    override fun onFailure(error: IDJIError) {}
                }
            )
        } catch (_: Exception) {}
    }

    private fun pollYawRate() {
        // 如果处于检查状态，暂停用轮询数据覆盖UI文字 (避免遮盖 "✅ 检查完毕" 等提示)
        if (::preflightController.isInitialized && preflightController.isChecking) return

        try {
            KeyManager.getInstance().getValue(
                KeyTools.createKey(FlightControllerKey.KeyAircraftAttitude),
                object : CommonCallbacks.CompletionCallbackWithParam<Attitude> {
                    override fun onSuccess(value: Attitude?) {
                        value ?: return
                        val nowMs  = System.currentTimeMillis()
                        val yawNow = value.yaw
                        if (!lastYawDeg.isNaN() && lastYawTimeMs > 0) {
                            val dtSec = (nowMs - lastYawTimeMs) / 1000.0
                            if (dtSec > 0.05) {
                                var delta = yawNow - lastYawDeg
                                if (delta >  180.0) delta -= 360.0
                                if (delta < -180.0) delta += 360.0
                                runOnUiThread {
                                    // 若检测到UI被置为错误提示颜色，则暂时不覆盖
                                    if (yawRateText.currentTextColor != 0xFFD32F2F.toInt() &&
                                        yawRateText.currentTextColor != 0xFF4CAF50.toInt()) {
                                        yawRateText.text = "%+.1f °/s".format(delta / dtSec)
                                    }
                                }
                            }
                        }
                        lastYawDeg    = yawNow
                        lastYawTimeMs = nowMs
                    }
                    override fun onFailure(error: IDJIError) {}
                }
            )
        } catch (_: Exception) {}
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
                                if (sec > 0) "%d:%02d".format(sec / 60, sec % 60)
                                else "--:--"
                        }
                    }
                    override fun onFailure(error: IDJIError) {}
                }
            )
        } catch (_: Exception) {}
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
                        btnAutoLanding?.text = "中止降落!"
                        btnAutoLanding?.setBackgroundColor(0xFF4CAF50.toInt())
                        btnAutoLanding?.isEnabled = true
                        btnTakeoff?.isEnabled = false
                        clearErrorOnUI()
                        previousLandingState = TaskState.LANDING
                    }
                }
            }

            // ═════════════════════════════════════════════════
            // ★ 新增：降落完成时关闭视频流并通知 Jetson
            // ═════════════════════════════════════════════════
            if (state == TaskState.INACTIVE && previousLandingState == TaskState.LANDING) {
                previousLandingState = TaskState.INACTIVE

                // ★ 新增：关闭视频流
                DroneControlService.onStopCameraStream?.invoke()

                runCatching {
                    val frame = DroneCommProtocol.encodeSimple(
                        DroneCommProtocol.CMD_ACK_LAND_COMPLETE
                    )
                    DroneControlService.sendFrame(frame)
                }
            }
        }

        landingController.onError = { msg ->
            runOnUiThread { showErrorOnUI(msg) }
        }
        landingController.onMessage = { msg ->
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
                yawRateText.text = "✅ 检查完毕，可以起飞"
                yawRateText.setTextColor(0xFF4CAF50.toInt())
                yawRateText.textSize = 14f
                Toast.makeText(this, "✅ 安全检查通过", Toast.LENGTH_LONG).show()
            }

            // ★ 新增：自检通过后关闭视频流
            DroneControlService.onStopCameraStream?.invoke()

            // 通知 Jetson 检查通过
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

            // ★ 新增：自检失败后关闭视频流
            DroneControlService.onStopCameraStream?.invoke()

            // 通知 Jetson 检查失败（带原因码）
            runCatching {
                val reasonCode: Byte = when {
                    reason.contains("夹爪", ignoreCase = true) ||
                            reason.contains("grip", ignoreCase = true) ||
                            reason.contains("遮挡", ignoreCase = true)
                        -> DroneCommProtocol.CHECK_FAIL_REASON_GRIP_NOT_DETECTED

                    reason.contains("cv", ignoreCase = true) ||
                            reason.contains("vision", ignoreCase = true) ||
                            reason.contains("视觉", ignoreCase = true)
                        -> DroneCommProtocol.CHECK_FAIL_REASON_CV_ERROR

                    reason.contains("云台", ignoreCase = true) ||
                            reason.contains("gimbal", ignoreCase = true)
                        -> DroneCommProtocol.CHECK_FAIL_REASON_GIMBAL_ERROR

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
            visionController     = VisionController()

            visionController?.onTargetLocked = { id, errX, errY, depthZ, yawDeg ->
                currentTargetId = id
                landingController.updateVisionData(id, errX, errY, depthZ, yawDeg)
            }

            testCameraController?.frameCallback = { data, offset, length, width, height ->
                try {
                    val isLanding = if (::landingController.isInitialized) landingController.getTaskState() == TaskState.LANDING else false
                    val isPreflightChecking = if (::preflightController.isInitialized) preflightController.isChecking else false

                    if (isLanding || isPreflightChecking) {
                        currentTargetId = -1
                        visionController?.processFrame(data, offset, length, width, height)
                    }

                    if (isPreflightChecking) {
                        val isTargetLocked = (currentTargetId != -1)
                        preflightController.processFrame(data, offset, width, height, isTargetLocked)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ 视觉处理帧时发生异常: ${e.message}", e)
                }
            }
            Log.i(TAG, "✅ 视觉流分发枢纽初始化完成")

        } catch (e: Throwable) {
            Log.e(TAG, "❌ 严重崩溃拦截: 控制器实例化失败 (可能是 OpenCV 未就绪)", e)
            runOnUiThread {
                Toast.makeText(this, "视觉模块初始化失败，请查看 Logcat！", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun onLandingClicked() {
        if (!::landingController.isInitialized) {
            Toast.makeText(this, "控制器未就绪，请重新连接飞机并重启APP", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "控制器未就绪，请重新连接飞机并重启APP", Toast.LENGTH_SHORT).show()
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

    // ── UI 辅助方法 ──────────────────────────────────────────────────
    private fun initUIWidgets() {
        // 原有组件
        fpvWidget              = findViewById(R.id.fpvWidget)
        shootPhotoWidget       = findViewById(R.id.shootPhotoWidget)
        recordVideoWidget      = findViewById(R.id.recordVideoWidget)
        focalZoomWidget        = findViewById(R.id.focalZoomWidget)
        photoVideoSwitchWidget = findViewById(R.id.photoVideoSwitchWidget)

        btnLensWide    = findViewById(R.id.btnLensWide)
        btnLensZoom    = findViewById(R.id.btnLensZoom)
        btnLensThermal = findViewById(R.id.btnLensThermal)

        btnRecordWaypoint = findViewById(R.id.btnRecordWaypoint)
        btnSaveWaypoints  = findViewById(R.id.btnSaveWaypoints)
        btnClearWaypoints = findViewById(R.id.btnClearWaypoints)

        xSpeedText        = findViewById(R.id.xSpeedText)
        ySpeedText        = findViewById(R.id.ySpeedText)
        zSpeedText        = findViewById(R.id.zSpeedText)
        yawRateText       = findViewById(R.id.yawRateText)
        remainingTimeText = findViewById(R.id.remainingTimeText)

        // 新增的视觉降落与起飞按钮
        btnAutoLanding = findViewById(R.id.btnAutoLanding)
        btnTakeoff     = findViewById(R.id.btnTakeoff)
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
        yawRateText.text = "0.0 °/s"
    }

    private fun reattachStatusWidgets() {
        Log.d(TAG_SDK, "重新挂载顶栏 Widget...")
        listOf(
            R.id.systemStatusWidget, R.id.flightModeWidget, R.id.gpsSignalWidget,
            R.id.rcSignalWidget, R.id.videoSignalWidget, R.id.batteryWidget
        ).forEach { id -> findViewById<View>(id)?.let { reattachView(it) } }
        Log.d(TAG_SDK, "完成")
    }

    private fun reattachView(view: View) {
        val parent = view.parent as? ViewGroup ?: return
        val index  = parent.indexOfChild(view)
        val lp     = view.layoutParams
        parent.removeView(view)
        parent.addView(view, index, lp)
    }

    private fun setupLensButtons() {
        btnLensWide.setOnClickListener    { switchLens(CameraVideoStreamSourceType.WIDE_CAMERA) }
        btnLensZoom.setOnClickListener    { switchLens(CameraVideoStreamSourceType.ZOOM_CAMERA) }
        btnLensThermal.setOnClickListener { switchLens(CameraVideoStreamSourceType.INFRARED_CAMERA) }
    }

    private fun setupWaypointButtons() {
        btnRecordWaypoint.setOnClickListener { waypointCtrl.recordWaypoint() }
        btnSaveWaypoints.setOnClickListener  { waypointCtrl.saveWaypoints() }
        btnClearWaypoints.setOnClickListener { waypointCtrl.clearWaypoints() }
    }

    private fun switchLens(target: CameraVideoStreamSourceType) {
        Log.d(TAG, "切换镜头 → $target")
        KeyManager.getInstance().setValue(
            KeyTools.createKey(CameraKey.KeyCameraVideoStreamSource, currentCameraIndex),
            target,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    Log.d(TAG, "切换成功 → $target")
                    // 保留原有行为：通知服务切换
                    DroneControlService.updateCurrentLens(streamTypeToCode(target))
                }
                override fun onFailure(error: IDJIError) {
                    Log.e(TAG, "切换失败: ${error.description()}")
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "切换失败: ${error.description()}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    private fun updateLensButtonState(lensType: CameraLensType) {
        val active   = 0xFF1976D2.toInt()
        val inactive = 0xFF555555.toInt()
        btnLensWide.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (lensType == CameraLensType.CAMERA_LENS_WIDE) active else inactive)
        btnLensZoom.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (lensType == CameraLensType.CAMERA_LENS_ZOOM) active else inactive)
        btnLensThermal.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (lensType == CameraLensType.CAMERA_LENS_THERMAL) active else inactive)
    }

    private fun updateViewVisibility(lensType: CameraLensType) {
        val showZoom = lensType == CameraLensType.CAMERA_LENS_ZOOM || lensType == CameraLensType.CAMERA_LENS_THERMAL
        focalZoomWidget.visibility = if (showZoom) View.VISIBLE else View.GONE
    }

    private fun updateAllWidgetSource(pos: ComponentIndexType, lens: CameraLensType) {
        shootPhotoWidget.updateCameraSource(pos, lens)
        recordVideoWidget.updateCameraSource(pos, lens)
        focalZoomWidget.updateCameraSource(pos, lens)
        photoVideoSwitchWidget.updateCameraSource(pos, lens)
    }

    // ── 镜头类型 ↔ 协议字节 转换 (保持原样) ────────────────────────────────────
    private fun lensTypeToCode(lens: CameraLensType): Byte = when (lens) {
        CameraLensType.CAMERA_LENS_ZOOM    -> DroneCommProtocol.CAM_LENS_ZOOM
        CameraLensType.CAMERA_LENS_THERMAL -> DroneCommProtocol.CAM_LENS_INFRARED
        else                               -> DroneCommProtocol.CAM_LENS_WIDE
    }

    private fun streamTypeToCode(t: CameraVideoStreamSourceType): Byte = when (t) {
        CameraVideoStreamSourceType.ZOOM_CAMERA     -> DroneCommProtocol.CAM_LENS_ZOOM
        CameraVideoStreamSourceType.INFRARED_CAMERA -> DroneCommProtocol.CAM_LENS_INFRARED
        else                                        -> DroneCommProtocol.CAM_LENS_WIDE
    }
}