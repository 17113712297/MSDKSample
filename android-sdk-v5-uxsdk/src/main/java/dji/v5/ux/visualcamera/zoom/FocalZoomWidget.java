package dji.v5.ux.visualcamera.zoom;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import dji.sdk.keyvalue.value.common.CameraLensType;
import dji.sdk.keyvalue.value.common.ComponentIndexType;
import dji.v5.ux.core.base.ICameraIndex;
import dji.v5.ux.core.base.widget.ConstraintLayoutWidget;

public class FocalZoomWidget extends ConstraintLayoutWidget<Object> implements ICameraIndex {

    private FocalZoomWidgetView focalZoomWidgetView;
    private CameraLensType currentLensType = CameraLensType.UNKNOWN;

    public FocalZoomWidget(Context context) {
        super(context);
    }

    public FocalZoomWidget(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public FocalZoomWidget(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void initView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        focalZoomWidgetView = new FocalZoomWidgetView(context, attrs, defStyleAttr);
        // Follow the size assigned by the containing layout; a fixed child height clips the minus button.
        ConstraintLayout.LayoutParams layoutParams = new ConstraintLayout.LayoutParams(0, 0);
        layoutParams.startToStart = ConstraintLayout.LayoutParams.PARENT_ID;
        layoutParams.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID;
        layoutParams.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
        layoutParams.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
        this.addView(focalZoomWidgetView, layoutParams);
    }

    /** Request the next supported zoom gear. False means unavailable or already at the limit. */
    public boolean zoomIn() {
        return isEnabled() && showZoomView(currentLensType) && focalZoomWidgetView != null && focalZoomWidgetView.zoomIn();
    }

    /** Request the previous supported zoom gear without synthesizing a touch event. */
    public boolean zoomOut() {
        return isEnabled() && showZoomView(currentLensType) && focalZoomWidgetView != null && focalZoomWidgetView.zoomOut();
    }

    @NonNull
    @Override
    public ComponentIndexType getCameraIndex() {
        if (focalZoomWidgetView != null) {
            return focalZoomWidgetView.getCameraIndex();
        }
        return ComponentIndexType.UNKNOWN;
    }

    @NonNull
    @Override
    public CameraLensType getLensType() {
        if (focalZoomWidgetView != null) {
            return focalZoomWidgetView.getLensType();
        }
        return CameraLensType.UNKNOWN;
    }

    @Override
    public void updateCameraSource(@NonNull ComponentIndexType cameraIndex, @NonNull CameraLensType lensType) {
        currentLensType = lensType;
        focalZoomWidgetView.updateCameraSource(cameraIndex, lensType);
        if (showZoomView(lensType)) {
            focalZoomWidgetView.setVisibility(VISIBLE);
            return;
        }
        focalZoomWidgetView.setVisibility(GONE);
    }

    @Override
    protected void reactToModelChanges() {
        //do nothing
    }

    @Nullable
    @Override
    public String getIdealDimensionRatioString() {
        return null;
    }

    private boolean showZoomView(CameraLensType lensType) {
        return lensType == CameraLensType.CAMERA_LENS_ZOOM || lensType == CameraLensType.CAMERA_LENS_THERMAL;
    }
}
