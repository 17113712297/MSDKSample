package com.example.msdksample

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.Toast
import dji.sdk.keyvalue.key.CameraKey
import dji.sdk.keyvalue.value.camera.CameraVideoStreamSourceType
import dji.sdk.keyvalue.value.common.CameraLensType
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.create
import dji.v5.manager.KeyManager
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import dji.v5.ux.core.util.DataProcessor
import dji.v5.ux.core.widget.fpv.FPVWidget
import dji.v5.ux.core.widget.fpv.FPVStreamSourceListener
import dji.v5.ux.cameracore.widget.cameracapture.shootphoto.ShootPhotoWidget
import dji.v5.ux.cameracore.widget.cameracapture.recordvideo.RecordVideoWidget
import dji.v5.ux.cameracore.widget.cameracontrols.photovideoswitch.PhotoVideoSwitchWidget
import dji.v5.ux.visualcamera.zoom.FocalZoomWidget
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.CompositeDisposable
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    companion object {
        const val TAG = "DBG_CAM"
    }

    // ── Widget 引用 ──────────────────────────────────────────────────
    private lateinit var fpvWidget: FPVWidget
    private lateinit var shootPhotoWidget: ShootPhotoWidget
    private lateinit var recordVideoWidget: RecordVideoWidget
    private lateinit var focalZoomWidget: FocalZoomWidget
    private lateinit var photoVideoSwitchWidget: PhotoVideoSwitchWidget

    // 镜头切换按钮
    private lateinit var btnLensWide: Button
    private lateinit var btnLensZoom: Button
    private lateinit var btnLensThermal: Button

    // ── 当前激活的相机位置（切换镜头时需要用到）────────────────────
    private var currentCameraIndex: ComponentIndexType = ComponentIndexType.LEFT_OR_MAIN

    // ── RxJava 相机源处理器（缓存最新值，onResume 订阅时立即拿到）──
    private val cameraSourceProcessor = DataProcessor.create(
        Pair(ComponentIndexType.UNKNOWN, CameraLensType.UNKNOWN)
    )
    private var compositeDisposable: CompositeDisposable? = null

    // ── 相机流可用性监听器 ────────────────────────────────────────────
    private val availableCameraUpdatedListener =
        object : ICameraStreamManager.AvailableCameraUpdatedListener {
            override fun onAvailableCameraUpdated(
                availableCameraList: MutableList<ComponentIndexType>
            ) {
                Log.d(TAG, "onAvailableCameraUpdated: $availableCameraList")
                runOnUiThread {
                    if (availableCameraList.isNullOrEmpty()) return@runOnUiThread
                    val source =
                        if (availableCameraList.contains(ComponentIndexType.LEFT_OR_MAIN))
                            ComponentIndexType.LEFT_OR_MAIN
                        else
                            availableCameraList[0]
                    currentCameraIndex = source
                    fpvWidget.updateVideoSource(source)
                }
            }

            override fun onCameraStreamEnableUpdate(
                cameraStreamEnableMap: MutableMap<ComponentIndexType, Boolean>
            ) {
                // 暂不处理
            }
        }

    // ── 生命周期 ──────────────────────────────────────────────────────
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 绑定 Widget
        fpvWidget              = findViewById(R.id.fpvWidget)
        shootPhotoWidget       = findViewById(R.id.shootPhotoWidget)
        recordVideoWidget      = findViewById(R.id.recordVideoWidget)
        focalZoomWidget        = findViewById(R.id.focalZoomWidget)
        photoVideoSwitchWidget = findViewById(R.id.photoVideoSwitchWidget)

        // 绑定镜头按钮
        btnLensWide    = findViewById(R.id.btnLensWide)
        btnLensZoom    = findViewById(R.id.btnLensZoom)
        btnLensThermal = findViewById(R.id.btnLensThermal)

        // FPV 配置
        fpvWidget.isCameraSourceNameVisible = false
        fpvWidget.isCameraSourceSideVisible = false

        // 注册 FPV 流源监听（先注册，再触发视频源）
        fpvWidget.setOnFPVStreamSourceListener(object : FPVStreamSourceListener {
            override fun onStreamSourceUpdated(
                devicePosition: ComponentIndexType,
                lensType: CameraLensType
            ) {
                Log.d(TAG, "onStreamSourceUpdated: position=$devicePosition, lens=$lensType")
                cameraSourceProcessor.onNext(Pair(devicePosition, lensType))
            }
        })

        // 注册相机流可用性监听
        MediaDataCenter.getInstance()
            .getCameraStreamManager()
            .addAvailableCameraUpdatedListener(availableCameraUpdatedListener)

        // 设置镜头切换按钮点击事件
        setupLensButtons()
    }

    override fun onResume() {
        super.onResume()
        compositeDisposable = CompositeDisposable()
        compositeDisposable?.add(
            cameraSourceProcessor.toFlowable()
                .throttleLast(500, TimeUnit.MILLISECONDS)       // 防抖：500ms 内只取最后一次
                .observeOn(AndroidSchedulers.mainThread())       // 切回主线程再操作 UI
                .subscribe(
                    { (devicePosition, lensType) ->
                        if (devicePosition != ComponentIndexType.UNKNOWN) {
                            updateViewVisibility(lensType)
                            updateAllWidgetSource(devicePosition, lensType)
                            updateLensButtonState(lensType)
                        }
                    },
                    { error -> Log.e(TAG, "cameraSourceProcessor 错误: ${error.message}") }
                )
        )
    }

    override fun onPause() {
        super.onPause()
        compositeDisposable?.dispose()
        compositeDisposable = null
    }

    override fun onDestroy() {
        super.onDestroy()
        MediaDataCenter.getInstance()
            .getCameraStreamManager()
            .removeAvailableCameraUpdatedListener(availableCameraUpdatedListener)
    }

    // ── 镜头切换 ──────────────────────────────────────────────────────
    private fun setupLensButtons() {
        btnLensWide.setOnClickListener {
            switchLens(CameraVideoStreamSourceType.WIDE_CAMERA)
        }
        btnLensZoom.setOnClickListener {
            switchLens(CameraVideoStreamSourceType.ZOOM_CAMERA)
        }
        btnLensThermal.setOnClickListener {
            switchLens(CameraVideoStreamSourceType.INFRARED_CAMERA)
        }
    }

    private fun switchLens(targetLens: CameraVideoStreamSourceType) {
        Log.d(TAG, "切换镜头 → $targetLens")
        KeyManager.getInstance().setValue(
            CameraKey.KeyCameraVideoStreamSource.create(currentCameraIndex),
            targetLens,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    Log.d(TAG, "镜头切换成功 → $targetLens")
                    // onStreamSourceUpdated 回调会自动触发，无需手动更新 Widget
                }

                override fun onFailure(error: IDJIError) {
                    Log.e(TAG, "镜头切换失败: ${error.description()}")
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

    // ── 根据当前镜头高亮对应按钮 ─────────────────────────────────────
    private fun updateLensButtonState(lensType: CameraLensType) {
        val activeColor   = 0xFF1976D2.toInt()  // 蓝色：当前激活
        val inactiveColor = 0xFF555555.toInt()  // 灰色：未激活

        btnLensWide.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (lensType == CameraLensType.CAMERA_LENS_WIDE) activeColor else inactiveColor
        )
        btnLensZoom.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (lensType == CameraLensType.CAMERA_LENS_ZOOM) activeColor else inactiveColor
        )
        btnLensThermal.backgroundTintList = android.content.res.ColorStateList.valueOf(
            if (lensType == CameraLensType.CAMERA_LENS_THERMAL) activeColor else inactiveColor
        )
    }

    // ── 根据镜头类型控制变焦 Widget 显示 ────────────────────────────
    private fun updateViewVisibility(lensType: CameraLensType) {
        val showZoom = lensType == CameraLensType.CAMERA_LENS_ZOOM
                || lensType == CameraLensType.CAMERA_LENS_THERMAL
        focalZoomWidget.visibility = if (showZoom) View.VISIBLE else View.GONE
    }

    // ── 同步所有 Widget 的相机源 ─────────────────────────────────────
    private fun updateAllWidgetSource(
        devicePosition: ComponentIndexType,
        lensType: CameraLensType
    ) {
        shootPhotoWidget.updateCameraSource(devicePosition, lensType)
        recordVideoWidget.updateCameraSource(devicePosition, lensType)
        focalZoomWidget.updateCameraSource(devicePosition, lensType)
        photoVideoSwitchWidget.updateCameraSource(devicePosition, lensType)
    }
}

