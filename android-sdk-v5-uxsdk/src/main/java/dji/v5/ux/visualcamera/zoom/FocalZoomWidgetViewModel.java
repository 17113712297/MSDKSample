package dji.v5.ux.visualcamera.zoom;

import android.os.SystemClock;

import java.util.concurrent.TimeUnit;

import androidx.annotation.NonNull;
import dji.sdk.keyvalue.key.CameraKey;
import dji.sdk.keyvalue.key.DJIKey;
import dji.sdk.keyvalue.key.KeyTools;
import dji.sdk.keyvalue.value.camera.ZoomRatiosRange;
import dji.sdk.keyvalue.value.common.CameraLensType;
import dji.sdk.keyvalue.value.common.ComponentIndexType;
import dji.v5.manager.KeyManager;
import dji.v5.ux.core.base.DJISDKModel;
import dji.v5.ux.core.base.ICameraIndex;
import dji.v5.ux.core.base.WidgetModel;
import dji.v5.ux.core.communication.ObservableInMemoryKeyedStore;
import dji.v5.ux.core.util.DataProcessor;
import io.reactivex.rxjava3.core.Emitter;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;

/**
 * Class Description
 *
 * @author Hoker
 * @date 2022/9/5
 * <p>
 * Copyright (c) 2022, DJI All Rights Reserved.
 */
class FocalZoomWidgetViewModel extends WidgetModel implements ICameraIndex {

    private static final int SAMPLE_TIME = 500;
    private ComponentIndexType cameraIndex = ComponentIndexType.LEFT_OR_MAIN;
    private CameraLensType lensType = CameraLensType.CAMERA_LENS_ZOOM;
    private Emitter<Double> mSendFocalLengthEmitter;
    private DJIKey<Double> cameraZoomRatiosKey;
    private DJIKey<Double> thermalZoomRatiosKey;
    private DJIKey<ZoomRatiosRange> cameraZoomRatiosRangeKey;
    private DJIKey<ZoomRatiosRange> thermalZoomRatiosRangeKey;
    public final DataProcessor<Double> focalZoomRatios = DataProcessor.create(0.0D);
    public final DataProcessor<ZoomRatiosRange> focalZoomRatiosRange = DataProcessor.create(new ZoomRatiosRange());
    private final DataProcessor<Double> visibleFocalZoomRatios =  DataProcessor.create(0.0D);
    private final DataProcessor<Double> thermalFocalZoomRatios =  DataProcessor.create(0.0D);
    private final DataProcessor<ZoomRatiosRange> visibleFocalZoomRatiosRange =  DataProcessor.create(new ZoomRatiosRange());
    private final DataProcessor<ZoomRatiosRange> thermalFocalZoomRatiosRange =  DataProcessor.create(new ZoomRatiosRange());
    private long mSendFocusDistanceTime = 0;
    private volatile long sourceGeneration;
    private boolean setupActive;

    public FocalZoomWidgetViewModel(@NonNull DJISDKModel djiSdkModel, @NonNull ObservableInMemoryKeyedStore uxKeyManager) {
        super(djiSdkModel, uxKeyManager);
    }

    @Override
    protected void inSetup() {
        setupActive = true;
        final long generation = sourceGeneration;
        initSendFocalLengthObservable();
        //SDK在Key做过了区分，所以这里直接用
        cameraZoomRatiosKey = KeyTools.createCameraKey(CameraKey.KeyCameraZoomRatios, cameraIndex, CameraLensType.CAMERA_LENS_ZOOM);
        thermalZoomRatiosKey = KeyTools.createCameraKey(CameraKey.KeyThermalZoomRatios, cameraIndex, CameraLensType.CAMERA_LENS_THERMAL);
        bindDataProcessor(cameraZoomRatiosKey, visibleFocalZoomRatios, ratios -> {
            if (generation == sourceGeneration && lensType == CameraLensType.CAMERA_LENS_ZOOM) {
                focalZoomRatios.onNext(ratios);
            }
        });
        bindDataProcessor(thermalZoomRatiosKey, thermalFocalZoomRatios, ratios -> {
            if (generation == sourceGeneration && lensType == CameraLensType.CAMERA_LENS_THERMAL) {
                focalZoomRatios.onNext(ratios);
            }
        });

        cameraZoomRatiosRangeKey = KeyTools.createCameraKey(CameraKey.KeyCameraZoomRatiosRange, cameraIndex, CameraLensType.CAMERA_LENS_ZOOM);
        thermalZoomRatiosRangeKey = KeyTools.createCameraKey(CameraKey.KeyThermalZoomRatiosRange, cameraIndex, CameraLensType.CAMERA_LENS_THERMAL);

        bindDataProcessor(cameraZoomRatiosRangeKey, visibleFocalZoomRatiosRange, range -> {
            if (generation == sourceGeneration && lensType == CameraLensType.CAMERA_LENS_ZOOM) {
                focalZoomRatiosRange.onNext(range);
            }
        });
        bindDataProcessor(thermalZoomRatiosRangeKey, thermalFocalZoomRatiosRange, range -> {
            if (generation == sourceGeneration && lensType == CameraLensType.CAMERA_LENS_THERMAL) {
                focalZoomRatiosRange.onNext(range);
            }
        });
    }

    @Override
    protected void inCleanup() {
        setupActive = false;
        sourceGeneration++;
        mSendFocalLengthEmitter = null;
    }

    @NonNull
    @Override
    public ComponentIndexType getCameraIndex() {
        return cameraIndex;
    }

    @NonNull
    @Override
    public CameraLensType getLensType() {
        return lensType;
    }

    @Override
    public synchronized void updateCameraSource(@NonNull ComponentIndexType cameraIndex, @NonNull CameraLensType lensType) {
        final boolean started = setupActive;
        if (started) cleanup();
        this.cameraIndex = cameraIndex;
        this.lensType = lensType;
        // A new camera/lens must not replay the old lens's ratio and gear range as if they were current.
        focalZoomRatios.onNext(0D);
        focalZoomRatiosRange.onNext(new ZoomRatiosRange());
        visibleFocalZoomRatios.onNext(0D);
        thermalFocalZoomRatios.onNext(0D);
        visibleFocalZoomRatiosRange.onNext(new ZoomRatiosRange());
        thermalFocalZoomRatiosRange.onNext(new ZoomRatiosRange());
        mSendFocusDistanceTime = 0;
        if (started) setup();
    }

    private void initSendFocalLengthObservable() {
        final long generation = sourceGeneration;
        Disposable disposable = Observable.<Double>create(emitter -> mSendFocalLengthEmitter = emitter)
                .filter((value) -> {
                    if (SystemClock.uptimeMillis() - mSendFocusDistanceTime > SAMPLE_TIME) {
                        sendFocusDistance(generation, value);
                        return false;
                    } else {
                        return true;
                    }
                })
                .throttleLast(SAMPLE_TIME, TimeUnit.MILLISECONDS)
                .subscribe(value -> sendFocusDistance(generation, value));
        addDisposable(disposable);
    }

    public void setFocusDistance(double value) {
        if (mSendFocalLengthEmitter != null) {
            mSendFocalLengthEmitter.onNext(value);
        }
    }

    private synchronized void sendFocusDistance(long generation, double value) {
        if (generation != sourceGeneration || Double.isNaN(value) || Double.isInfinite(value) || value <= 0) return;
        mSendFocusDistanceTime = SystemClock.uptimeMillis();
        if (lensType == CameraLensType.CAMERA_LENS_ZOOM) {
            KeyManager.getInstance().setValue(cameraZoomRatiosKey, value, null);
        } else if (lensType == CameraLensType.CAMERA_LENS_THERMAL) {
            KeyManager.getInstance().setValue(thermalZoomRatiosKey, value, null);
        }
    }
}
