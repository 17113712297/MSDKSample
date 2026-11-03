package com.example.msdksample.ui

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.msdksample.R
import com.example.msdksample.logs.OperationLogger
import java.util.Locale

/** 活动作业直接在图传旁操作。速度区复用原控件，避免两套按钮状态和回调不同步。 */
class OperationWorkspaceController(
    activity: AppCompatActivity,
    onRecord: () -> Unit,
    onSave: () -> Unit,
    onClear: () -> Unit,
    onFinish: () -> Unit
) {
    private val collectPanel = activity.findViewById<View>(R.id.quickCollectionPanel)
    private val collectStatus = activity.findViewById<TextView>(R.id.quickCollectionStatus)
    private val record = activity.findViewById<Button>(R.id.btnQuickRecordWaypoint)
    private val save = activity.findViewById<Button>(R.id.btnQuickSaveWaypoints)
    private val clear = activity.findViewById<Button>(R.id.btnQuickClearWaypoints)
    private val finish = activity.findViewById<Button>(R.id.btnQuickFinishCollect)
    private val velocityPanel = activity.findViewById<View>(R.id.quickVelocityPanel)
    private val velocityTargets = activity.findViewById<TextView>(R.id.quickVelocityTargets)
    private val velocityHost = activity.findViewById<FrameLayout>(R.id.velocityWorkspaceHost)
    private val controls = activity.findViewById<View>(R.id.velocityControlLayout)
    private val home = controls.parent as ViewGroup
    private val homeIndex = home.indexOfChild(controls)
    private val homeLayoutParams = controls.layoutParams
    private val velocityHint = controls.findViewById<View>(R.id.velocityControlHint)
    private val velocityButtons = listOf(R.id.btnVelStartStop, R.id.btnVelZero,
        R.id.btnVelXMinus, R.id.btnVelXPlus, R.id.btnVelYMinus, R.id.btnVelYPlus,
        R.id.btnVelZMinus, R.id.btnVelZPlus, R.id.btnVelYawMinus, R.id.btnVelYawPlus)
        .map { controls.findViewById<Button>(it) }
    private val buttonHomeParams = velocityButtons.map { it.layoutParams }
    private val compactHeight = (40 * activity.resources.displayMetrics.density).toInt()
    private val compactSpacing = (4 * activity.resources.displayMetrics.density).toInt()
    private var homeVisibility = controls.visibility
    private var collecting = false
    private var collectStopping = false
    private var velocityActive = false
    val velocityControlsOnMain: Boolean get() = velocityActive

    init {
        fun bind(button: Button, name: String, action: () -> Unit) {
            button.setOnClickListener {
                if (collecting && !collectStopping) {
                    OperationLogger.operation("采点快捷区：$name")
                    action()
                }
            }
        }
        bind(record, "记录航点", onRecord)
        bind(save, "保存航线", onSave)
        bind(clear, "清空航点", onClear)
        bind(finish, "结束采点", onFinish)
        updateVelocityTargets(0f, 0f, 0f, 0f)
    }

    fun setCollectionState(active: Boolean, stopping: Boolean, status: String) {
        collecting = active
        collectStopping = stopping
        collectPanel.visibility = if (active) View.VISIBLE else View.GONE
        collectStatus.text = if (stopping) "正在结束，等待机载确认…" else status
        collectStatus.setTextColor(if (stopping) 0xFFF4CE7C.toInt() else 0xFF5DE4AE.toInt())
        listOf(record, save, clear, finish).forEach { it.isEnabled = active && !stopping }
        finish.text = if (stopping) "正在结束…" else "结束采点"
    }

    fun setVelocityActive(active: Boolean) {
        if (velocityActive == active) return
        velocityActive = active
        if (active) {
            homeVisibility = controls.visibility
            (controls.parent as? ViewGroup)?.removeView(controls)
            velocityHost.addView(controls, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            controls.visibility = View.VISIBLE
            velocityHint.visibility = View.GONE
            velocityButtons.forEach { button ->
                button.layoutParams = LinearLayout.LayoutParams(button.layoutParams as LinearLayout.LayoutParams).apply {
                    height = compactHeight
                    topMargin = compactSpacing
                }
            }
            velocityPanel.visibility = View.VISIBLE
        } else {
            velocityPanel.visibility = View.GONE
            (controls.parent as? ViewGroup)?.removeView(controls)
            home.addView(controls, homeIndex.coerceAtMost(home.childCount), homeLayoutParams)
            controls.visibility = homeVisibility
            velocityHint.visibility = View.VISIBLE
            velocityButtons.forEachIndexed { index, button -> button.layoutParams = buttonHomeParams[index] }
        }
    }

    fun updateVelocityTargets(x: Float, y: Float, z: Float, yaw: Float) {
        velocityTargets.text = String.format(Locale.getDefault(),
            "目标 X %+.1f  Y %+.1f  Z %+.1f m/s\n偏航 %+.0f °/s", x, y, z, yaw)
    }
}
