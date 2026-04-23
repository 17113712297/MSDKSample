package com.example.msdksample

import android.os.Handler
import android.os.Looper
import android.util.Log
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.value.common.EmptyMsg
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.action
import dji.v5.et.create
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

    /** 起飞 */
    fun takeoff(onResult: (Boolean, String) -> Unit) {
        log("CMD: TAKEOFF")
        FlightControllerKey.KeyStartTakeoff.create().action(
            { _: EmptyMsg ->
                log("TAKEOFF OK")
                onResult(true, "OK")
            },
            { e: IDJIError ->
                val msg = e.description() ?: e.errorCode()
                log("TAKEOFF FAIL: $msg")
                onResult(false, msg)
            }
        )
    }

    /** 降落 */
    fun land(onResult: (Boolean, String) -> Unit) {
        log("CMD: LAND")
        if (virtualStickEnabled.get()) stopVirtualStick()
        FlightControllerKey.KeyStartAutoLanding.create().action(
            { _: EmptyMsg ->
                log("LAND OK")
                onResult(true, "OK")
            },
            { e: IDJIError ->
                val msg = e.description() ?: e.errorCode()
                log("LAND FAIL: $msg")
                onResult(false, msg)
            }
        )
    }

    /** 悬停：清零速度并停掉 VirtualStick，飞机自动定点 */
    fun hover(onResult: (Boolean, String) -> Unit) {
        log("CMD: HOVER")
        stopVirtualStick()
        onResult(true, "OK")
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

        if (virtualStickEnabled.get()) {
            VirtualStickManager.getInstance().disableVirtualStick(
                object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        virtualStickEnabled.set(false)
                        log("VirtualStick DISABLED")
                    }
                    override fun onFailure(error: IDJIError) {
                        // 即使 SDK 回失败，也将本地状态置 false，避免一直卡在 enabled
                        virtualStickEnabled.set(false)
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
