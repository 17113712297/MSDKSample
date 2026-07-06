package com.example.msdksample

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.flightcontroller.FlightMode
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * VelocityControlPanel — 手动速度控制面板
 *
 * 提供基于 VirtualStick 的手动 XYZ 三轴速度控制功能。
 *
 * ── 状态机 ────────────────────────────────────────────────
 *   IDLE  → 用户点击"启动速度控制" + 当前在 N 挡 → ACTIVE
 *   ACTIVE → 用户点击"关闭速度控制" / 切出 N 挡 / 降落激活 → IDLE
 *
 * ── N 挡限制 ──────────────────────────────────────────────
 *   速度控制功能仅允许在 N 挡下使用。
 *   一旦检测到飞手切出 N 挡，立即执行：
 *     1. 速度归零（发送零速指令让飞机悬停）
 *     2. 关闭 VirtualStick
 *     3. 复位启动按钮
 *   飞手重新切入 N 挡后，可再次点击"启动"重新激活。
 *
 * ── 速度映射 ──────────────────────────────────────────────
 *   机体坐标系 (Body Frame)：
 *     X   → 前/后速度  m/s  (正 = 前)   → rightStick.verticalPosition
 *     Y   → 右/左速度  m/s  (正 = 右)   → rightStick.horizontalPosition
 *     Z   → 上/下速度  m/s  (正 = 上)   → leftStick.verticalPosition
 *     Yaw → 偏航角速度 °/s (正 = 右转)  → leftStick.horizontalPosition
 *
 * ── 分度值与限幅 ──────────────────────────────────────────
 *   X/Y  步进 0.1 m/s， 限幅 ±15 m/s
 *   Z    步进 0.1 m/s， 限幅 ±4 m/s
 *   Yaw  步进 5 °/s，   限幅 ±100 °/s
 *   Stick 满偏值 = 660
 *
 * ── 安全约束 ──────────────────────────────────────────────
 *   1. N 挡外禁止激活
 *   2. 降落进行中禁止激活
 *   3. VirtualStick 启用失败时保持 IDLE，不残留半激活状态
 *   4. 切出 N 挡时优先发送零速指令再关闭 VirtualStick
 */
class VelocityControlPanel {

    companion object {
        private const val TAG = "VelocityPanel"

        /** 控制指令喂帧周期 (ms)，与 DroneController 一致 */
        private const val FEED_PERIOD_MS = 100L

        /** IStick 满偏值 */
        private const val STICK_MAX = 660

        /** 水平速度上限 m/s */
        private const val MAX_VH_SPEED = 15.0f

        /** 垂直速度上限 m/s */
        private const val MAX_VZ_SPEED = 4.0f

        /** X/Y 轴步进 m/s */
        private const val STEP_XY = 0.1f

        /** Z 轴步进 m/s */
        private const val STEP_Z = 0.1f

        /** Yaw 偏航步进 deg/s */
        private const val STEP_YAW = 5f

        /** 偏航角速度上限 deg/s */
        private const val MAX_YAW_RATE = 100f

        /** N 挡白名单 — M3T 不同固件版本可能报不同枚举名 */
        private val ALLOWED_MODES = setOf(
            "NORMAL", "GPS_NORMAL", "POSITION_CTRL",
            "JOYSTICK", "VIRTUAL_STICK",
            "GPS", "P_GPS", "P_GPS_NORMAL"
        )

        /** 明确不允许的模式 — S 挡 / 姿态 / 手动 及其各种固件变体 */
        private val BLOCKED_MODES = setOf(
            "SPORT", "GPS_SPORT", "P_SPORT", "SPORT_MODE",
            "ATTI", "ATTI_MODE", "ATTITUDE", "ATTI_LIMITED",
            "MANUAL", "MANUAL_MODE",
            "GENTLE", "CINE", "TRIPOD"  // C挡也拦截（非N挡）
        )
    }

    // ═══════════════════════════════════════════════════════
    // 状态定义
    // ═══════════════════════════════════════════════════════

    enum class PanelState {
        /** 未激活：VirtualStick 关闭，不发送速度指令 */
        IDLE,
        /** 已激活：VirtualStick 开启，持续喂帧 */
        ACTIVE
    }

    @Volatile
    private var panelState = PanelState.IDLE

    /** 当前目标速度值 (Body Frame) */
    @Volatile private var velX = 0f
    @Volatile private var velY = 0f
    @Volatile private var velZ = 0f
    @Volatile private var velYaw = 0f

    /** 当前飞行模式名 (由监听器持续更新) */
    @Volatile private var currentFlightMode: String = ""

    // ═══════════════════════════════════════════════════════
    // 线程与定时器
    // ═══════════════════════════════════════════════════════

    private val mainHandler = Handler(Looper.getMainLooper())
    private val feedExecutor = Executors.newSingleThreadScheduledExecutor()
    private val feedLock = Any()
    private var feedFuture: ScheduledFuture<*>? = null
    private val virtualStickEnabled = AtomicBoolean(false)

    // ═══════════════════════════════════════════════════════
    // 回调接口 (由 MainActivity 绑定)
    // ═══════════════════════════════════════════════════════

    /** 面板状态变更回调 */
    var onPanelStateChanged: ((PanelState) -> Unit)? = null

    /** 速度值变更回调 (velX, velY, velZ)，用于更新 UI 显示 */
    var onVelocityChanged: ((Float, Float, Float) -> Unit)? = null

    /** N 挡可用性变更回调 (isAvailable: Boolean)，用于更新启动按钮 */
    var onNModeAvailabilityChanged: ((Boolean) -> Unit)? = null

    /** Toast 消息回调，由 MainActivity 提供 Context */
    var toastCallback: ((String) -> Unit)? = null

    // ═══════════════════════════════════════════════════════
    // 公开 API
    // ═══════════════════════════════════════════════════════

    /** 获取当前面板状态 */
    fun getState(): PanelState = panelState

    /** 当前是否处于 N 挡 — 直接查询 SDK，不依赖缓存的 currentFlightMode */
    fun isInNMode(): Boolean {
        val modeName = queryFlightModeNow()
        return isAllowedMode(modeName)
    }

    /** 获取当前飞行模式名 — 直接查询 SDK */
    fun getCurrentFlightModeName(): String = queryFlightModeNow().ifEmpty { "未知" }

    /** 从 SDK 实时读取当前飞行模式名 */
    private fun queryFlightModeNow(): String {
        return runCatching {
            KeyManager.getInstance()
                .getValue(KeyTools.createKey(FlightControllerKey.KeyFlightMode))
                ?.name ?: ""
        }.getOrDefault("")
    }

    /**
     * 启动飞行模式轮询（独立于 LandingController 的监听器，不冲突）。
     *
     * 每 500ms 通过 getValue() 查询当前模式，与上一次比较。
     * 模式变化时立即更新 UI + 必要时 forceStop。
     */
    fun registerFlightModeListener() {
        // 立即更新一次 UI
        val initialAllowed = isInNMode()
        currentFlightMode = queryFlightModeNow()
        Log.i(TAG, "初始飞行模式: ${getCurrentFlightModeName()} (允许:$initialAllowed)")
        mainHandler.post {
            onNModeAvailabilityChanged?.invoke(initialAllowed)
        }

        // 启动 500ms 轮询
        mainHandler.postDelayed(modePollRunnable, 500L)
    }

    /** 独立于 feed 线程的模式轮询，仅负责检测变化 → 更新 UI / forceStop */
    private val modePollRunnable = object : Runnable {
        override fun run() {
            if (released) return
            try {
                val newMode = queryFlightModeNow()
                val prevAllowed = isAllowedMode(currentFlightMode)
                val newAllowed = isAllowedMode(newMode)
                val changed = newMode != currentFlightMode

                if (changed) {
                    Log.i(TAG, "模式轮询检测到变化: $currentFlightMode → $newMode (允许:$newAllowed)")
                    currentFlightMode = newMode

                    // 切出 N 挡 + 正在控制中 → 强制停止
                    if (prevAllowed && !newAllowed && panelState == PanelState.ACTIVE) {
                        Log.w(TAG, "🛑 切出 N 挡，强制停止速度控制")
                        toastCallback?.invoke("已切出 N 挡，速度控制自动关闭")
                        forceStop("飞行模式切换: $currentFlightMode → $newMode")
                    }

                    // 通知 UI 更新按钮和状态文字
                    onNModeAvailabilityChanged?.invoke(newAllowed)
                }
            } catch (_: Exception) {}
            mainHandler.postDelayed(this, 500L)
        }
    }

    @Volatile private var released = false

    /**
     * 启动/关闭速度控制 (供按钮 onClick 调用)
     *
     * IDLE → 检查 N 挡 + 降落状态 → 启用 VirtualStick → ACTIVE
     * ACTIVE → 速度归零 + 关闭 VirtualStick → IDLE
     */
    fun toggleStartStop() {
        when (panelState) {
            PanelState.IDLE -> startVelocityControl()
            PanelState.ACTIVE -> stopVelocityControl(userInitiated = true)
        }
    }

    /**
     * 调整指定轴的速度
     *
     * @param axis  'X' | 'Y' | 'Z'
     * @param delta 正值 = 增加，负值 = 减少
     */
    fun adjustVelocity(axis: Char, delta: Float) {
        if (panelState != PanelState.ACTIVE) {
            toastCallback?.invoke("请先启动速度控制")
            return
        }

        val step = if (axis == 'Z') STEP_Z else STEP_XY
        val maxVal = if (axis == 'Z') MAX_VZ_SPEED else MAX_VH_SPEED
        val actualDelta = if (delta > 0) step else -step

        when (axis) {
            'X' -> velX = (velX + actualDelta).coerceIn(-maxVal, maxVal)
            'Y' -> velY = (velY + actualDelta).coerceIn(-maxVal, maxVal)
            'Z' -> velZ = (velZ + actualDelta).coerceIn(-maxVal, maxVal)
        }

        Log.d(TAG, "速度调整: axis=$axis delta=$actualDelta → X=$velX Y=$velY Z=$velZ")
        onVelocityChanged?.invoke(velX, velY, velZ)
    }

    /**
     * 调整偏航角速度
     *
     * @param delta 正值 = 增加（顺时针），负值 = 减少（逆时针）
     */
    fun adjustYaw(delta: Float) {
        if (panelState != PanelState.ACTIVE) {
            toastCallback?.invoke("请先启动速度控制")
            return
        }
        val actualDelta = if (delta > 0) STEP_YAW else -STEP_YAW
        velYaw = (velYaw + actualDelta).coerceIn(-MAX_YAW_RATE, MAX_YAW_RATE)
        Log.d(TAG, "Yaw调整: delta=$actualDelta → Yaw=$velYaw")
        onVelocityChanged?.invoke(velX, velY, velZ)
    }

    /** 速度归零（面板保持 ACTIVE，飞机悬停） */
    fun zeroVelocity() {
        if (panelState != PanelState.ACTIVE) return

        velX = 0f
        velY = 0f
        velZ = 0f
        velYaw = 0f
        Log.i(TAG, "速度归零 → 悬停")
        onVelocityChanged?.invoke(0f, 0f, 0f)
    }

    /** 释放资源，应在 MainActivity.onDestroy 中调用 */
    fun release() {
        released = true
        mainHandler.removeCallbacks(modePollRunnable)
        forceStop("VelocityPanel Released")
        feedExecutor.shutdown()
        try {
            if (!feedExecutor.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                feedExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            feedExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }
        Log.i(TAG, "资源已释放")
    }

    /**
     * 更新启动按钮的 UI 状态（供 MainActivity 在 flightMode 变化时调用）
     *
     * @param startStopBtn 启动/关闭按钮
     */
    fun updateStartButtonState(startStopBtn: Button) {
        when {
            panelState == PanelState.ACTIVE -> {
                startStopBtn.text = "关闭速度控制"
                startStopBtn.setBackgroundColor(0xFFD32F2F.toInt())
                startStopBtn.isEnabled = true
            }
            !isInNMode() -> {
                startStopBtn.text = "启动速度控制"
                startStopBtn.setBackgroundColor(0xFF555555.toInt())
                startStopBtn.isEnabled = false
            }
            else -> {
                startStopBtn.text = "启动速度控制"
                startStopBtn.setBackgroundColor(0xFF4CAF50.toInt())
                startStopBtn.isEnabled = true
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    // 内部实现
    // ═══════════════════════════════════════════════════════

    private fun startVelocityControl() {
        // 1. 前置检查：N 挡 — 直接查询 SDK
        if (!isInNMode()) {
            val modeName = queryFlightModeNow()
            val msg = "⚠️ 请在 N 挡下启动速度控制 (当前: ${modeName.ifEmpty { "未知" }})"
            Log.w(TAG, msg)
            toastCallback?.invoke(msg)
            return
        }

        // 2. 前置检查：起飞状态 — 必须起飞后才能进行速度控制
        val isFlying = runCatching {
            KeyManager.getInstance().getValue(
                KeyTools.createKey(FlightControllerKey.KeyIsFlying)
            ) ?: false
        }.getOrDefault(false)
        if (!isFlying) {
            val msg = "⚠️ 请先起飞后再启动速度控制"
            Log.w(TAG, msg)
            toastCallback?.invoke(msg)
            return
        }

        // 3. 前置检查：降落状态
        val landingState = DroneControlService.landingController?.getTaskState()
        if (landingState == TaskState.LANDING || landingState == TaskState.LANDING_PREP) {
            val msg = "⚠️ 视觉降落进行中，无法启动速度控制"
            Log.w(TAG, msg)
            toastCallback?.invoke(msg)
            return
        }

        Log.i(TAG, "▶ 启动速度控制 (当前模式: ${queryFlightModeNow()})")

        // 4. ★ 先设置全局协调标志，阻止 Jetson CMD_VEL 竞争 VirtualStick
        DroneControlService.isVelocityPanelActive = true

        // 5. 启用 VirtualStick
        VirtualStickManager.getInstance().enableVirtualStick(
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    virtualStickEnabled.set(true)
                    panelState = PanelState.ACTIVE
                    startFeedTimer()

                    mainHandler.post {
                        onPanelStateChanged?.invoke(PanelState.ACTIVE)
                        toastCallback?.invoke("✅ 速度控制已启动")
                    }
                    Log.i(TAG, "✅ VirtualStick 已启用，开始喂帧")
                }

                override fun onFailure(error: IDJIError) {
                    // 启用失败，清除标志
                    DroneControlService.isVelocityPanelActive = false
                    val msg = error.description() ?: error.errorCode()
                    Log.e(TAG, "❌ VirtualStick 启用失败: $msg")
                    mainHandler.post {
                        onPanelStateChanged?.invoke(PanelState.IDLE)
                        toastCallback?.invoke("❌ 速度控制启动失败: $msg")
                    }
                }
            }
        )
    }

    private fun stopVelocityControl(userInitiated: Boolean) {
        Log.i(TAG, "■ 关闭速度控制 (用户操作: $userInitiated)")

        // 1. 先发送零速指令让飞机悬停
        velX = 0f; velY = 0f; velZ = 0f; velYaw = 0f
        sendZeroVelocityOnce()

        // 2. 停止喂帧定时器
        stopFeedTimer()

        // 3. 关闭 VirtualStick
        if (virtualStickEnabled.getAndSet(false)) {
            VirtualStickManager.getInstance().disableVirtualStick(
                object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        Log.i(TAG, "✅ VirtualStick 已关闭")
                    }
                    override fun onFailure(error: IDJIError) {
                        Log.w(TAG, "VirtualStick 关闭失败: ${error.description()}")
                    }
                }
            )
        }

        // 4. 清除全局协调标志
        DroneControlService.isVelocityPanelActive = false

        // 5. 更新状态
        panelState = PanelState.IDLE
        onPanelStateChanged?.invoke(PanelState.IDLE)
        onVelocityChanged?.invoke(0f, 0f, 0f)

        if (userInitiated) {
            toastCallback?.invoke("速度控制已关闭")
        }
    }

    /**
     * 强制停止（切出 N 挡 / 释放资源等非用户主动操作触发）
     * 与 stopVelocityControl 的区别：不发 Toast（避免重复提示）
     */
    private fun forceStop(reason: String) {
        if (panelState == PanelState.IDLE) return
        Log.w(TAG, "🔴 强制停止: $reason")

        velX = 0f; velY = 0f; velZ = 0f; velYaw = 0f
        sendZeroVelocityOnce()
        stopFeedTimer()

        if (virtualStickEnabled.getAndSet(false)) {
            VirtualStickManager.getInstance().disableVirtualStick(
                object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() { Log.i(TAG, "✅ VS 已强制关闭") }
                    override fun onFailure(error: IDJIError) {
                        Log.w(TAG, "VS 强制关闭失败: ${error.description()}")
                    }
                }
            )
        }

        DroneControlService.isVelocityPanelActive = false
        panelState = PanelState.IDLE
        onPanelStateChanged?.invoke(PanelState.IDLE)
        onVelocityChanged?.invoke(0f, 0f, 0f)
    }

    // ═══════════════════════════════════════════════════════
    // VirtualStick 喂帧
    // ═══════════════════════════════════════════════════════

    private fun startFeedTimer() {
        synchronized(feedLock) {
            if (feedFuture != null) return
            feedFuture = feedExecutor.scheduleAtFixedRate(
                { feedVelocityToStick() },
                0L,
                FEED_PERIOD_MS,
                TimeUnit.MILLISECONDS
            )
            Log.i(TAG, "喂帧定时器启动 (${FEED_PERIOD_MS}ms)")
        }
    }

    private fun stopFeedTimer() {
        synchronized(feedLock) {
            feedFuture?.cancel(false)
            feedFuture = null
        }
        Log.i(TAG, "喂帧定时器停止")
    }

    /**
     * 单次喂帧：将当前的 velX/velY/velZ 映射到 VirtualStick 并写入 SDK。
     *
     * 同时检查降落状态 —— 若检测到降落已激活，立即自停。
     */
    private fun feedVelocityToStick() {
        if (panelState != PanelState.ACTIVE || !virtualStickEnabled.get()) return

        try {
            // ★ 安全检查：已切出 N 挡则自停
            if (!isInNMode()) {
                Log.w(TAG, "🛑 检测到已切出 N 挡，速度面板自停")
                mainHandler.post { forceStop("已切出 N 挡") }
                return
            }

            // ★ 安全检查：已落地则自停
            val isFlying = runCatching {
                KeyManager.getInstance().getValue(
                    KeyTools.createKey(FlightControllerKey.KeyIsFlying)
                ) ?: false
            }.getOrDefault(false)
            if (!isFlying) {
                Log.w(TAG, "🛑 检测到飞机已落地，速度面板自停")
                mainHandler.post { forceStop("飞机已落地") }
                return
            }

            // ★ 安全检查：降落进行中则自停
            val landingState = DroneControlService.landingController?.getTaskState()
            if (landingState == TaskState.LANDING || landingState == TaskState.LANDING_PREP) {
                Log.w(TAG, "🛑 检测到降落激活，速度面板自停")
                mainHandler.post { forceStop("降落任务冲突") }
                return
            }

            val vsm = VirtualStickManager.getInstance()

            // Body Frame 映射:
            //   rightStick.vertical   = pitch  = X (前/后)
            //   rightStick.horizontal = roll   = Y (右/左)
            //   leftStick.vertical    = throttle = Z (上/下)
            //   leftStick.horizontal  = yaw = 0 (本面板不控制偏航)
            val pitchStick  = (velX / MAX_VH_SPEED * STICK_MAX).toInt().coerceIn(-STICK_MAX, STICK_MAX)
            val rollStick   = (velY / MAX_VH_SPEED * STICK_MAX).toInt().coerceIn(-STICK_MAX, STICK_MAX)
            val throttle    = (velZ / MAX_VZ_SPEED * STICK_MAX).toInt().coerceIn(-STICK_MAX, STICK_MAX)
            val yawStick    = (velYaw / MAX_YAW_RATE * STICK_MAX).toInt().coerceIn(-STICK_MAX, STICK_MAX)

            vsm.rightStick.verticalPosition   = pitchStick
            vsm.rightStick.horizontalPosition = rollStick
            vsm.leftStick.verticalPosition    = throttle
            vsm.leftStick.horizontalPosition  = yawStick

        } catch (e: Exception) {
            Log.w(TAG, "喂帧异常: ${e.message}")
        }
    }

    /**
     * 立即发送一次零速指令。
     * 在关闭 VirtualStick 之前调用，确保飞机先收到悬停指令。
     */
    private fun sendZeroVelocityOnce() {
        try {
            if (!virtualStickEnabled.get()) return
            val vsm = VirtualStickManager.getInstance()
            vsm.rightStick.verticalPosition   = 0
            vsm.rightStick.horizontalPosition = 0
            vsm.leftStick.verticalPosition    = 0
            vsm.leftStick.horizontalPosition  = 0
            Log.d(TAG, "已发送零速指令(含Yaw)")
        } catch (e: Exception) {
            Log.w(TAG, "零速发送异常: ${e.message}")
        }
    }

    // ═══════════════════════════════════════════════════════
    // 工具方法
    // ═══════════════════════════════════════════════════════

    private fun isAllowedMode(modeName: String): Boolean {
        if (modeName.isEmpty()) return false
        // 明确在黑名单 → 不允许
        if (modeName in BLOCKED_MODES) return false
        // 在白名单 → 允许
        if (modeName in ALLOWED_MODES) return true
        // 未知模式 → 宽松放行（避免因固件枚举变化永久锁定）
        Log.w(TAG, "未知飞行模式: $modeName，暂按允许处理")
        return true
    }
}
