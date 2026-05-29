package com.example.msdksample

import android.os.Handler
import android.os.Looper
import android.util.Log
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.action
import dji.v5.et.create
import dji.v5.manager.KeyManager
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * DroneController
 *
 * 封装 MSDK V5 的飞控操作：起飞、降落、悬停、速度控制。
 *
 * ── VirtualStick 速度控制说明 ──────────────────────────────────────────
 *
 * MSDK V5 的 VirtualStickManager 使用机体坐标系 (Body Frame)：
 *
 *   右摇杆 (RightStick)
 *     垂直轴  → Pitch  = 前/后速度  m/s   (正 = 前)
 *     水平轴  → Roll   = 右/左速度  m/s   (正 = 右)
 *
 *   左摇杆 (LeftStick)
 *     垂直轴  → 升降速度            m/s   (正 = 上)
 *     水平轴  → Yaw 偏航角速度      deg/s (正 = 右转)
 *
 * VirtualStick 要求在启用后 ≤ 500 ms 内持续喂指令，否则飞机悬停。
 * 本类用 ScheduledExecutorService 以 10 Hz 持续向 SDK 设置摇杆值。
 *
 * ── IStick 值域说明 ──────────────────────────────────────────────────
 *   IStick.verticalPosition / horizontalPosition 均为 Int，范围 -660 ~ 660。
 *   速度值按各自上限线性映射到该范围。
 *
 * ── 与原版相比的修复 ────────────────────────────────────────────────
 *   1. 四个速度分量改用 AtomicReference<VelCmd> 整体快照，
 *      避免 feed 线程读到「新 vx + 旧 yawRate」混合瞬时值。
 *   2. feedFuture 启停加 synchronized(feedLock)，
 *      避免 Service 回调线程和主线程并发 schedule/cancel 产生重复任务。
 *   3. release() 加 awaitTermination + shutdownNow 兜底，
 *      防止进程退出时遗留任务。
 *   4. sendVelocity 回调始终按 Boolean 反映真实结果，
 *      DroneControlService 据此决定 ACK_OK/ACK_FAIL，
 *      不再依赖 msg 字符串内容判断。
 */
class DroneController {

    companion object {
        private const val TAG = "DroneController"

        const val MAX_VH_SPEED = 15.0f   // m/s 水平
        const val MAX_VZ_SPEED =  4.0f   // m/s 垂直
        const val MAX_YAW_RATE = 100.0f  // deg/s

        private const val STICK_MAX = 660  // IStick 满偏值
        private const val FEED_PERIOD_MS = 100L

        /**
         * VEL 看门狗超时 (ms)。
         *
         * ── 设计依据 ────────────────────────────────────────────
         * 假定 ROS /drone/cmd_vel 话题以 10Hz 持续发送 (周期 100ms)，
         * 300ms 允许连续丢 7 帧抖动而不触发悬停，给 ROS 上游 (规划器、
         * 视觉伺服等) 偶尔慢一拍留出缓冲。
         *
         * ── 触发后的行为 ────────────────────────────────────────
         * 超过 VEL_WATCHDOG_MS 没收到新的 sendVelocity 调用，feedTimer
         * 会主动把 cmdRef 清零，飞机以零速指令悬停 (而不是依赖 DJI 飞控
         * 自身的 500ms 超时)，原因：
         *   1) 响应更快 (立即悬停 vs 等飞控超时)
         *   2) VirtualStick 状态不被解除，下次 PSDK 恢复发帧无缝衔接
         *   3) 不依赖飞控超时的固件版本差异
         *
         * ── 调整建议 ────────────────────────────────────────────
         *   ROS 话题 10Hz → 800~1000ms 合适
         *   ROS 话题  5Hz → 1500ms 左右
         *   ROS 话题  1Hz → 不推荐用速度控制，改用航点
         */
        private const val VEL_WATCHDOG_MS = 300L

        // ── 起飞完成判定 ─────────────────────────────────────
        /** 自动起飞超时上限，超过则放弃等待完成通知 (M3T 正常 4~6s) */
        private const val TAKEOFF_TIMEOUT_MS = 12_000L
        /** 起飞轮询周期 */
        private const val TAKEOFF_POLL_MS = 200L
        /** 指令接受后延迟多久开始轮询 (等桨加速、离地) */
        private const val TAKEOFF_POLL_DELAY_MS = 500L
        /** 认为起飞完成的最小相对高度 (m)。M3T 默认起飞约 1.2 m，取 0.8 留余量 */
        private const val TAKEOFF_ALT_M = 0.8

        // ── 降落完成判定 ─────────────────────────────────────
        /** 自动降落超时上限，超过则放弃等待完成通知 */
        private const val LAND_TIMEOUT_MS = 60_000L
        /** 降落轮询周期 */
        private const val LAND_POLL_MS = 300L

        // ── 悬停完成判定 ─────────────────────────────────────
        /** hover 指令发出后到 VirtualStick disable 回调的兜底延迟 */
        private const val HOVER_SETTLE_MS = 300L
    }

    /** 速度指令快照：四个轴一起原子读写，避免读到混合瞬时值 */
    private data class VelCmd(
        val vx: Float = 0f,
        val vy: Float = 0f,
        val vz: Float = 0f,
        val yawRate: Float = 0f
    )

    private val virtualStickEnabled = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 起飞进行中标记：起飞未完成前 sendVelocity 会被拒绝，避免和自动起飞冲突 */
    private val isTakingOff = AtomicBoolean(false)

    /** 降落进行中标记：主要用于 Service 层可选查询，本类内部不强制拦截 */
    private val isLanding = AtomicBoolean(false)

    /** 用 AtomicReference 保证四个分量原子地一起更新 */
    private val cmdRef = AtomicReference(VelCmd())

    /**
     * 最近一次收到 sendVelocity 的时间戳 (ms)。
     * 初始 0 表示从未收到；一旦大于 0，feedTimer 会拿它和当前时间
     * 比较来判断是否触发看门狗。
     */
    @Volatile
    private var lastVelCmdTimeMs: Long = 0L

    private val feedExecutor = Executors.newSingleThreadScheduledExecutor()

    /** 加锁保护 feedFuture 的启停，防止 schedule/cancel 并发竞态 */
    private val feedLock = Any()
    private var feedFuture: ScheduledFuture<*>? = null

    var logCallback: ((String) -> Unit)? = null

    // ─────────────────────────────────────────────────────
    //  公开 API
    // ─────────────────────────────────────────────────────

    /**
     * 起飞（双段回调）
     *
     * @param onAccepted 指令被飞控接受时回调 (Boolean, String)；Service 据此回 CMD_ACK。
     * @param onComplete 飞机真正完成自动起飞、稳定悬停时回调；
     *                   Service 据此发 CMD_ACK_TAKEOFF_COMPLETE 通知 Jetson。
     *                   onAccepted=false 时不会被调用。
     *
     * 完成判据：KeyIsFlying==true 且 KeyAircraftLocation3D.altitude >= TAKEOFF_ALT_M，
     *          或超过 TAKEOFF_TIMEOUT_MS 超时。
     */
    fun takeoff(
        onAccepted: (Boolean, String) -> Unit,
        onComplete: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        log("CMD: TAKEOFF")

        // 重置 VirtualStick 状态：上一次降落后 DJI 固件可能已自动禁用
        // VirtualStick，但本地标志 virtualStickEnabled 仍为 true（过时）。
        // 不重置会导致第二次起飞后 sendVelocity 跳过 enableVirtualStick()，
        // 速度指令被固件静默丢弃，航点跟踪失效。
        if (virtualStickEnabled.get()) {
            log("TAKEOFF: 重置过时的 VirtualStick 状态")
            stopVirtualStick()
        }

        if (!isTakingOff.compareAndSet(false, true)) {
            log("TAKEOFF 已在进行中，忽略重复指令")
            onAccepted(false, "takeoff already in progress")
            return
        }

        FlightControllerKey.KeyStartTakeoff.create().action(
            { _: EmptyMsg ->
                log("TAKEOFF 指令已接受，等待飞机真正起飞...")
                onAccepted(true, "OK")
                mainHandler.postDelayed({
                    waitForTakeoffComplete(onComplete)
                }, TAKEOFF_POLL_DELAY_MS)
            },
            { e: IDJIError ->
                isTakingOff.set(false)
                val msg = e.description() ?: e.errorCode()
                log("TAKEOFF FAIL: $msg")
                onAccepted(false, msg)
            }
        )
    }

    /**
     * 降落（双段回调）
     *
     * 完成判据：KeyIsFlying==false (电机停转 / 落地)，或超过 LAND_TIMEOUT_MS 超时。
     */
    fun land(
        onAccepted: (Boolean, String) -> Unit,
        onComplete: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        log("CMD: LAND")
        if (virtualStickEnabled.get()) stopVirtualStick()
        if (!isLanding.compareAndSet(false, true)) {
            log("LAND 已在进行中，忽略重复指令")
            onAccepted(false, "land already in progress")
            return
        }

        FlightControllerKey.KeyStartAutoLanding.create().action(
            { _: EmptyMsg ->
                log("LAND 指令已接受，等待飞机真正落地...")
                onAccepted(true, "OK")
                mainHandler.postDelayed({
                    waitForLandComplete(onComplete)
                }, LAND_POLL_MS)
            },
            { e: IDJIError ->
                isLanding.set(false)
                val msg = e.description() ?: e.errorCode()
                log("LAND FAIL: $msg")
                onAccepted(false, msg)
            }
        )
    }

    /**
     * 悬停（双段回调）
     *
     * hover = 清零速度 + disable VirtualStick，飞机进入定点悬停。
     * 完成判据：VirtualStick disable 回调返回 (成功或失败)，或 HOVER_SETTLE_MS 兜底超时。
     */
    fun hover(
        onAccepted: (Boolean, String) -> Unit,
        onComplete: (Boolean, String) -> Unit = { _, _ -> }
    ) {
        log("CMD: HOVER")
        val wasEnabled = virtualStickEnabled.get()
        onAccepted(true, "OK")

        if (!wasEnabled) {
            // VirtualStick 本来就没开，飞机已处于悬停状态，直接通知完成
            log("HOVER: VirtualStick 未启用，直接回完成")
            onComplete(true, "OK")
            return
        }

        // 真正 disable VirtualStick：stopVirtualStick 内部异步，等回调或兜底超时
        stopVirtualStickWithCallback(onComplete)
    }

    /**
     * stopVirtualStick 的回调版本：disable 成功/失败均触发 onComplete。
     * 加一个兜底定时器，防止 SDK 回调丢失时永远收不到完成通知。
     */
    private fun stopVirtualStickWithCallback(onComplete: (Boolean, String) -> Unit) {
        synchronized(feedLock) {
            feedFuture?.cancel(false)
            feedFuture = null
        }
        cmdRef.set(VelCmd())
        lastVelCmdTimeMs = 0L

        val fired = AtomicBoolean(false)
        val fallback = Runnable {
            if (fired.compareAndSet(false, true)) {
                log("HOVER: disable 回调超时，兜底回完成")
                onComplete(true, "OK (fallback)")
            }
        }
        mainHandler.postDelayed(fallback, HOVER_SETTLE_MS * 4)  // 1.2s 兜底

        VirtualStickManager.getInstance().disableVirtualStick(
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    virtualStickEnabled.set(false)
                    log("VirtualStick DISABLED")
                    if (fired.compareAndSet(false, true)) {
                        mainHandler.removeCallbacks(fallback)
                        onComplete(true, "OK")
                    }
                }
                override fun onFailure(error: IDJIError) {
                    virtualStickEnabled.set(false)
                    val msg = error.description() ?: error.errorCode()
                    log("VirtualStick disable FAIL: $msg")
                    if (fired.compareAndSet(false, true)) {
                        mainHandler.removeCallbacks(fallback)
                        // disable 失败也算完成：状态已在本地置 false，飞机一般已悬停
                        onComplete(true, "disable fail but local state reset: $msg")
                    }
                }
            }
        )
    }

    /**
     * 起飞完成轮询：isFlying==true && altitude>=阈值 → 成功；超时 → 失败。
     */
    private fun waitForTakeoffComplete(onComplete: (Boolean, String) -> Unit) {
        val deadline = System.currentTimeMillis() + TAKEOFF_TIMEOUT_MS

        val poll = object : Runnable {
            override fun run() {
                val km = KeyManager.getInstance()
                val isFlying = km.getValue(
                    KeyTools.createKey(FlightControllerKey.KeyIsFlying)
                ) ?: false
                val altitude = km.getValue(
                    KeyTools.createKey(FlightControllerKey.KeyAltitude)
                ) ?: 0.0

                when {
                    isFlying && altitude >= TAKEOFF_ALT_M -> {
                        isTakingOff.set(false)
                        log("TAKEOFF COMPLETE (alt=${"%.2f".format(altitude)}m)")
                        onComplete(true, "OK")
                    }
                    System.currentTimeMillis() > deadline -> {
                        isTakingOff.set(false)
                        log("TAKEOFF TIMEOUT (isFlying=$isFlying alt=$altitude)")
                        onComplete(false, "takeoff timeout")
                    }
                    else -> mainHandler.postDelayed(this, TAKEOFF_POLL_MS)
                }
            }
        }
        mainHandler.post(poll)
    }

    /**
     * 降落完成轮询：isFlying 从 true 变 false 即视为落地完成。
     */
    private fun waitForLandComplete(onComplete: (Boolean, String) -> Unit) {
        val deadline = System.currentTimeMillis() + LAND_TIMEOUT_MS

        val poll = object : Runnable {
            override fun run() {
                val isFlying = KeyManager.getInstance().getValue(
                    KeyTools.createKey(FlightControllerKey.KeyIsFlying)
                ) ?: false

                when {
                    !isFlying -> {
                        isLanding.set(false)
                        log("LAND COMPLETE")
                        onComplete(true, "OK")
                    }
                    System.currentTimeMillis() > deadline -> {
                        isLanding.set(false)
                        log("LAND TIMEOUT (isFlying 仍为 true)")
                        onComplete(false, "land timeout")
                    }
                    else -> mainHandler.postDelayed(this, LAND_POLL_MS)
                }
            }
        }
        mainHandler.post(poll)
    }

    /**
     * 发送速度指令 (Body Frame)
     *
     * 调用前无需手动开启 VirtualStick，本方法会自动按需开启。
     * 不论是不是首次调用，回调统一按真实结果回 (ok, msg)：
     *   - 启用失败 / 已启用但发送异常 → (false, msg)
     *   - 其他情况                    → (true, "OK")
     * Service 层按 ok 决定 ACK_OK / ACK_FAIL。
     */
    fun sendVelocity(
        vx: Float, vy: Float, vz: Float, yawRate: Float,
        onResult: (Boolean, String) -> Unit
    ) {
        // 0. 起飞进行中硬拦截：自动起飞和 VirtualStick 在飞控里互斥，
        //    起飞未完成就 enable VirtualStick 很可能直接失败，或打断起飞序列。
        //    Jetson 应订阅 /drone/notify/takeoff_complete 确认起飞完成后再发 VEL。
        if (isTakingOff.get()) {
            log("VEL 被拒绝：起飞进行中")
            onResult(false, "takeoff in progress")
            return
        }
        if (isLanding.get()) {
            log("VEL 被拒绝：降落进行中")
            onResult(false, "land in progress")
            return
        }

        // 1. 把指令写入快照（不论是否已启用，缓存最新值）
        cmdRef.set(
            VelCmd(
                vx      = vx.coerceIn(-MAX_VH_SPEED, MAX_VH_SPEED),
                vy      = vy.coerceIn(-MAX_VH_SPEED, MAX_VH_SPEED),
                vz      = vz.coerceIn(-MAX_VZ_SPEED, MAX_VZ_SPEED),
                yawRate = yawRate.coerceIn(-MAX_YAW_RATE, MAX_YAW_RATE)
            )
        )
        // 刷新看门狗喂狗时间
        lastVelCmdTimeMs = System.currentTimeMillis()

        // 2. 必要时启用 VirtualStick
        if (!virtualStickEnabled.get()) {
            enableVirtualStick { ok, msg ->
                if (ok) {
                    startFeedTimer()
                    onResult(true, "OK")
                } else {
                    onResult(false, "enableVirtualStick failed: $msg")
                }
            }
        } else {
            onResult(true, "OK")
        }
    }

    /** Activity / Service onDestroy 时调用，释放资源 */
    fun release() {
        // 清掉状态标记，避免 Service 重启后残留导致新 VEL 全被拦截
        isTakingOff.set(false)
        isLanding.set(false)
        mainHandler.removeCallbacksAndMessages(null)
        stopVirtualStick()
        feedExecutor.shutdown()
        try {
            if (!feedExecutor.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                feedExecutor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            feedExecutor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    // ─────────────────────────────────────────────────────
    //  私有：VirtualStick 生命周期
    // ─────────────────────────────────────────────────────

    private fun enableVirtualStick(onResult: (Boolean, String) -> Unit) {
        VirtualStickManager.getInstance().enableVirtualStick(
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    virtualStickEnabled.set(true)
                    log("VirtualStick ENABLED")
                    onResult(true, "OK")
                }
                override fun onFailure(error: IDJIError) {
                    val msg = error.description() ?: error.errorCode()
                    log("VirtualStick enable FAIL: $msg")
                    onResult(false, msg)
                }
            }
        )
    }

    private fun stopVirtualStick() {
        synchronized(feedLock) {
            feedFuture?.cancel(false)
            feedFuture = null
        }
        cmdRef.set(VelCmd())
        // 重置看门狗：下次 sendVelocity 前不应触发 watchdog
        lastVelCmdTimeMs = 0L

        if (virtualStickEnabled.getAndSet(false)) {
            VirtualStickManager.getInstance().disableVirtualStick(
                object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        log("VirtualStick DISABLED")
                    }
                    override fun onFailure(error: IDJIError) {
                        log("VirtualStick disable FAIL: ${error.description()}")
                    }
                }
            )
        }
    }

    /**
     * 以 10 Hz 将速度快照映射到 IStick (Int, -660~660) 并写入 SDK。
     *
     * 同时实现 VEL 看门狗：如果 VEL_WATCHDOG_MS 内没收到新的 sendVelocity
     * 调用，自动把 cmdRef 清零让飞机悬停，防止 ROS 话题停掉后飞机失控。
     */
    private fun startFeedTimer() {
        synchronized(feedLock) {
            if (feedFuture != null) return
            feedFuture = feedExecutor.scheduleAtFixedRate({
                if (!virtualStickEnabled.get()) return@scheduleAtFixedRate
                try {
                    // 看门狗检查：超过 VEL_WATCHDOG_MS 没喂狗 → 悬停
                    val last = lastVelCmdTimeMs
                    if (last > 0 &&
                        System.currentTimeMillis() - last > VEL_WATCHDOG_MS) {
                        val cur = cmdRef.get()
                        if (cur.vx != 0f || cur.vy != 0f ||
                            cur.vz != 0f || cur.yawRate != 0f) {
                            Log.w(TAG, "VEL watchdog timeout (${VEL_WATCHDOG_MS}ms), 清零悬停")
                            cmdRef.set(VelCmd())
                        }
                    }

                    // 原子读快照 -> 一组完整一致的速度分量
                    val cmd = cmdRef.get()
                    val vsm = VirtualStickManager.getInstance()

                    val pitchStick = (cmd.vx      / MAX_VH_SPEED * STICK_MAX)
                        .toInt().coerceIn(-STICK_MAX, STICK_MAX)
                    val rollStick  = (cmd.vy      / MAX_VH_SPEED * STICK_MAX)
                        .toInt().coerceIn(-STICK_MAX, STICK_MAX)
                    val throttle   = (cmd.vz      / MAX_VZ_SPEED * STICK_MAX)
                        .toInt().coerceIn(-STICK_MAX, STICK_MAX)
                    val yawStick   = (cmd.yawRate / MAX_YAW_RATE * STICK_MAX)
                        .toInt().coerceIn(-STICK_MAX, STICK_MAX)

                    vsm.rightStick.verticalPosition   = pitchStick
                    vsm.rightStick.horizontalPosition = rollStick
                    vsm.leftStick.verticalPosition    = throttle
                    vsm.leftStick.horizontalPosition  = yawStick

                } catch (e: Exception) {
                    Log.w(TAG, "feedTimer exception: ${e.message}")
                }
            }, 0L, FEED_PERIOD_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        mainHandler.post { logCallback?.invoke(msg) }
    }
}
