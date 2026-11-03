package com.example.msdksample.ui

import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import com.example.msdksample.R
import com.example.msdksample.logs.OperationLogger

/** 菜单只切换控件显示，不启停任何飞行任务。按钮仍使用主页面的原有业务绑定。 */
class FlightMenuController(
    private val activity: AppCompatActivity,
    onPauseMission: () -> Unit,
    onAbortLanding: () -> Unit,
    onStopVelocity: () -> Unit
) {
    private val drawer = activity.findViewById<View>(R.id.menuDrawer)
    private val scrim = activity.findViewById<View>(R.id.menuScrim)
    private val menu = activity.findViewById<Button>(R.id.btnMenu)
    private val scroll = activity.findViewById<ScrollView>(R.id.menuScroll)
    private val zoom = activity.findViewById<View>(R.id.focalZoomWidget)
    private val quickPause = activity.findViewById<Button>(R.id.btnQuickPause)
    private val quickAbort = activity.findViewById<Button>(R.id.btnQuickAbortLanding)
    private val quickStop = activity.findViewById<Button>(R.id.btnQuickStopVelocity)
    private val velocityToggle = activity.findViewById<Button>(R.id.btnVelocityPanel)
    private val velocityControls = activity.findViewById<View>(R.id.velocityControlLayout)
    private val tabs = listOf(R.id.btnMenuFlight, R.id.btnMenuMission, R.id.btnMenuCamera, R.id.btnMenuTools)
        .map { activity.findViewById<Button>(it) }
    private val panels = listOf(R.id.menuFlightPanel, R.id.menuMissionPanel, R.id.menuCameraPanel, R.id.menuToolsPanel)
        .map { activity.findViewById<View>(it) }
    private var open = false
    private var landingActive = false
    private var velocityActive = false
    private var zoomGesture = false
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            OperationLogger.operation("返回键收起菜单")
            close()
        }
    }

    init {
        activity.onBackPressedDispatcher.addCallback(activity, back)
        menu.setOnClickListener {
            OperationLogger.operation(if (open) "收起功能菜单" else "打开功能菜单")
            setOpen(!open)
        }
        activity.findViewById<Button>(R.id.btnMenuClose).setOnClickListener {
            OperationLogger.operation("关闭功能菜单"); close()
        }
        scrim.setOnClickListener { OperationLogger.operation("点击画面收起菜单"); close() }
        tabs.forEachIndexed { index, button ->
            button.setOnClickListener { OperationLogger.operation("菜单：${button.text}"); selectTab(index) }
        }
        velocityToggle.setOnClickListener {
            if (velocityActive) {
                OperationLogger.operation("返回主画面速度控制")
                close()
                return@setOnClickListener
            }
            val show = velocityControls.visibility != View.VISIBLE
            OperationLogger.operation(if (show) "展开速度控制" else "收起速度控制")
            velocityControls.visibility = if (show) View.VISIBLE else View.GONE
            velocityToggle.text = if (show) "收起速度控制" else "展开速度控制"
            velocityToggle.isSelected = show
        }
        activity.findViewById<EditText>(R.id.liveStreamAddressInput).setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) { hideKeyboard(); true } else false
        }
        quickPause.setOnClickListener {
            OperationLogger.operation("快捷暂停任务")
            onPauseMission()
        }
        quickAbort.setOnClickListener {
            if (landingActive) { OperationLogger.operation("快捷中止降落"); onAbortLanding() }
        }
        quickStop.setOnClickListener {
            if (velocityActive) { OperationLogger.operation("快捷停止速度控制"); onStopVelocity() }
        }
        selectTab(0)
    }

    fun close() { setOpen(false) }

    /** 只观察变焦操作日志，手势持有/释放由实际消费触摸的变焦子控件处理。 */
    fun observeTouchEvent(event: MotionEvent) {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val bounds = Rect()
            zoomGesture = open && zoom.isShown && zoom.getGlobalVisibleRect(bounds) &&
                bounds.contains(event.rawX.toInt(), event.rawY.toInt())
        } else if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (zoomGesture) {
                if (event.actionMasked == MotionEvent.ACTION_UP) OperationLogger.operation("相机：操作变焦控件")
            }
            zoomGesture = false
        }
    }

    fun setMissionCanPause(canPause: Boolean) { quickPause.isEnabled = canPause }

    fun setLandingActive(active: Boolean) {
        landingActive = active
        quickAbort.visibility = if (active) View.VISIBLE else View.GONE
    }

    fun setVelocityActive(active: Boolean) {
        velocityActive = active
        quickStop.visibility = if (active) View.VISIBLE else View.GONE
        velocityToggle.text = when {
            active -> "返回主画面速度控制"
            velocityControls.visibility == View.VISIBLE -> "收起速度控制"
            else -> "展开速度控制"
        }
        velocityToggle.isSelected = active || velocityControls.visibility == View.VISIBLE
    }

    private fun setOpen(value: Boolean) {
        open = value
        drawer.visibility = if (value) View.VISIBLE else View.GONE
        scrim.visibility = if (value) View.VISIBLE else View.GONE
        menu.text = if (value) "收起菜单" else "☰ 菜单"
        menu.contentDescription = if (value) "收起功能菜单" else "打开功能菜单"
        menu.isSelected = value
        back.isEnabled = value
        if (!value) {
            hideKeyboard()
        }
    }

    private fun selectTab(index: Int) {
        panels.forEachIndexed { position, view -> view.visibility = if (position == index) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { position, button -> button.isSelected = position == index }
        scroll.scrollTo(0, 0)
        hideKeyboard()
    }

    private fun hideKeyboard() {
        activity.currentFocus?.clearFocus()
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(menu.windowToken, 0)
    }
}
