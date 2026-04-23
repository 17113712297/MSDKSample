package com.example.msdksample

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
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
import dji.v5.et.create
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

    // ── 左侧 HUD TextView ────────────────────────────────────────────
    // 注：以下三个轴显示的是 DJI KeyAircraftVelocity 的字段值，
    //     该值采用 NEU (North-East-Up) 大地坐标系，与机体系不同：
    //       value.x = 北向速度 m/s   (正 = 向北)
    //       value.y = 东向速度 m/s   (正 = 向东)
    //       value.z = 垂直速度 m/s   (正 = 向上)
    //     与 PSDK 协议里 VelPayload 的机体系 (前/右/上) 含义不同，
    //     这里只用于显示当前实测速度，不能直接用作机体速度回放。
    private lateinit var xSpeedText: TextView
    private lateinit var ySpeedText: TextView
    private lateinit var zSpeedText: TextView
    private lateinit var yawRateText: TextView
    private lateinit var remainingTimeText: TextView

    // ── 当前激活的相机位置 ───────────────────────────────────────────
    private var currentCameraIndex: ComponentIndexType = ComponentIndexType.LEFT_OR_MAIN

    // ── RxJava 相机源处理器 ──────────────────────────────────────────
    private val cameraSourceProcessor = DataProcessor.create(
        Pair(ComponentIndexType.UNKNOWN, CameraLensType.UNKNOWN)
    )
    private var compositeDisposable: CompositeDisposable? = null

    // ── 偏航速度计算状态 ─────────────────────────────────────────────
    private var lastYawDeg    = Double.NaN
    private var lastYawTimeMs = 0L

    // ── 轮询 Handler ─────────────────────────────────────────────────
    private val pollHandler  = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            pollVelocity()
            pollYawRate()
            pollRemainingFlightTime()
            pollHandler.postDelayed(this, POLL_INTERVAL)
        }
    }

    // ── 相机流可用性监听器 ────────────────────────────────────────────
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
                }
            }
            override fun onCameraStreamEnableUpdate(map: MutableMap<ComponentIndexType, Boolean>) {}
        }

    // ── 生命周期 ──────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        fpvWidget              = findViewById(R.id.fpvWidget)
        shootPhotoWidget       = findViewById(R.id.shootPhotoWidget)
        recordVideoWidget      = findViewById(R.id.recordVideoWidget)
        focalZoomWidget        = findViewById(R.id.focalZoomWidget)
        photoVideoSwitchWidget = findViewById(R.id.photoVideoSwitchWidget)

        btnLensWide    = findViewById(R.id.btnLensWide)
        btnLensZoom    = findViewById(R.id.btnLensZoom)
        btnLensThermal = findViewById(R.id.btnLensThermal)

        xSpeedText        = findViewById(R.id.xSpeedText)
        ySpeedText        = findViewById(R.id.ySpeedText)
        zSpeedText        = findViewById(R.id.zSpeedText)
        yawRateText       = findViewById(R.id.yawRateText)
        remainingTimeText = findViewById(R.id.remainingTimeText)

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

        window.decorView.postDelayed({ reattachStatusWidgets() }, REATTACH_DELAY)
        startForegroundService(Intent(this, DroneControlService::class.java))
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
                            // 修复：把 UI 端镜头切换同步给 Service，
                            // 让 CameraController.setVideoCfg 用正确的 lens key
                            DroneControlService.updateCurrentLens(lensTypeToCode(lens))
                        }
                    },
                    { e -> Log.e(TAG, "cameraSource 错误: ${e.message}") }
                )
        )
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
    }

    // ── HUD 轮询：N / E / U 三轴速度 (NEU 大地坐标系，非机体系) ───────
    private fun pollVelocity() {
        try {
            KeyManager.getInstance().getValue(
                KeyTools.createKey(FlightControllerKey.KeyAircraftVelocity),
                object : CommonCallbacks.CompletionCallbackWithParam<Velocity3D> {
                    override fun onSuccess(value: Velocity3D?) {
                        value ?: return
                        runOnUiThread {
                            // value.x = 北向, value.y = 东向, value.z = 垂直 (NEU)
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

    // ── HUD 轮询：偏航速度 (差分 Attitude.yaw, °/s) ──────────────────
    private fun pollYawRate() {
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
                                    yawRateText.text = "%+.1f °/s".format(delta / dtSec)
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

    // ── HUD 轮询：剩余飞行时间 ───────────────────────────────────────
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

    // ── 顶栏 UXSDK Widget 重挂载 ────────────────────────────────────
    private fun reattachStatusWidgets() {
        Log.d(TAG_SDK, "重新挂载顶栏 Widget...")
        listOf(
            R.id.systemStatusWidget,
            R.id.flightModeWidget,
            R.id.gpsSignalWidget,
            R.id.rcSignalWidget,
            R.id.videoSignalWidget,
            R.id.batteryWidget
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

    // ── 镜头切换 ──────────────────────────────────────────────────────
    private fun setupLensButtons() {
        btnLensWide.setOnClickListener    { switchLens(CameraVideoStreamSourceType.WIDE_CAMERA) }
        btnLensZoom.setOnClickListener    { switchLens(CameraVideoStreamSourceType.ZOOM_CAMERA) }
        btnLensThermal.setOnClickListener { switchLens(CameraVideoStreamSourceType.INFRARED_CAMERA) }
    }

    private fun switchLens(target: CameraVideoStreamSourceType) {
        Log.d(TAG, "切换镜头 → $target")
        KeyManager.getInstance().setValue(
            CameraKey.KeyCameraVideoStreamSource.create(currentCameraIndex),
            target,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    Log.d(TAG, "切换成功 → $target")
                    // 同步给 Service (UI → cameraSourceProcessor 也会同步，
                    // 但流回调有延迟，这里立即更新更稳)
                    DroneControlService.updateCurrentLens(streamTypeToCode(target))
                }
                override fun onFailure(error: IDJIError) {
                    Log.e(TAG, "切换失败: ${error.description()}")
                    runOnUiThread {
                        Toast.makeText(this@MainActivity,
                            "切换失败: ${error.description()}", Toast.LENGTH_SHORT).show()
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
        val showZoom = lensType == CameraLensType.CAMERA_LENS_ZOOM
                || lensType == CameraLensType.CAMERA_LENS_THERMAL
        focalZoomWidget.visibility = if (showZoom) View.VISIBLE else View.GONE
    }

    private fun updateAllWidgetSource(pos: ComponentIndexType, lens: CameraLensType) {
        shootPhotoWidget.updateCameraSource(pos, lens)
        recordVideoWidget.updateCameraSource(pos, lens)
        focalZoomWidget.updateCameraSource(pos, lens)
        photoVideoSwitchWidget.updateCameraSource(pos, lens)
    }

    // ── 镜头类型 ↔ 协议字节 转换 ────────────────────────────────────
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
