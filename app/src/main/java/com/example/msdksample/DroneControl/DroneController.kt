package com.example.msdksample

import android.os.Handler
import android.os.Looper
import android.util.Log
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.value.common.EmptyMsg           // ✅ 官方正确包路径
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.et.action                                 // ✅ 官方扩展函数
import dji.v5.et.create                                 // ✅ 官方扩展函数
import dji.v5.manager.aircraft.virtualstick.VirtualStickManager
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
 *     垂直轴  → Pitch  = 前/后速度  m/s  (正 = 前)
 *     水平轴  → Roll   = 右/左速度  m/s  (正 = 右)
 *
 *   左摇杆 (LeftStick)
 *     垂直轴  → 升降速度            m/s  (正 = 上)
 *     水平轴  → Yaw 偏航角速度      deg/s (正 = 右转)
 *
 * VirtualStick 要求在启用后 ≤ 500 ms 内持续喂指令，否则飞机悬停。
 * 本类用 ScheduledExecutorService 以 10 Hz 持续向 SDK 设置摇杆值。
 *
 * ── IStick 值域说明 ──────────────────────────────────────────────────
 *   IStick.verticalPosition / horizontalPosition 均为 Int，范围 -660 ~ 660。
 *   速度值按各自上限线性映射到该范围。
 */
class DroneController {

    companion object {
        private const val TAG = "DroneController"

        const val MAX_VH_SPEED = 15.0f   // m/s 水平
        const val MAX_VZ_SPEED =  4.0f   // m/s 垂直
        const val MAX_YAW_RATE = 100.0f  // deg/s

        private const val STICK_MAX = 660  // IStick 满偏值
    }

    private val virtualStickEnabled = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var cmdVx      = 0.0f
    @Volatile private var cmdVy      = 0.0f
    @Volatile private var cmdVz      = 0.0f
    @Volatile private var cmdYawRate = 0.0f

    private val feedExecutor = Executors.newSingleThreadScheduledExecutor()
    private var feedFuture: ScheduledFuture<*>? = null

    var logCallback: ((String) -> Unit)? = null

    // ─────────────────────────────────────────────────────
    //  公开 API
    // ─────────────────────────────────────────────────────

    /** 起飞 — 采用官方 BasicAircraftControlVM 同款扩展函数写法 */
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

    /** 降落 — 采用官方 BasicAircraftControlVM 同款扩展函数写法 */
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

    /** 悬停：停止发速度指令，飞机自动悬停 */
    fun hover(onResult: (Boolean, String) -> Unit) {
        log("CMD: HOVER")
        stopVirtualStick()
        onResult(true, "OK")
    }

    /**
     * 发送速度指令 (Body Frame)
     * 调用前无需手动开启 VirtualStick，本方法会自动按需开启。
     */
    fun sendVelocity(
        vx: Float, vy: Float, vz: Float, yawRate: Float,
        onResult: (Boolean, String) -> Unit
    ) {
        cmdVx      = vx.coerceIn(-MAX_VH_SPEED, MAX_VH_SPEED)
        cmdVy      = vy.coerceIn(-MAX_VH_SPEED, MAX_VH_SPEED)
        cmdVz      = vz.coerceIn(-MAX_VZ_SPEED, MAX_VZ_SPEED)
        cmdYawRate = yawRate.coerceIn(-MAX_YAW_RATE, MAX_YAW_RATE)

        if (!virtualStickEnabled.get()) {
            enableVirtualStick { success, msg ->
                if (success) {
                    startFeedTimer()
                    onResult(true, "VirtualStick enabled, velocity accepted")
                } else {
                    onResult(false, "enableVirtualStick failed: $msg")
                }
            }
        } else {
            onResult(true, "OK")
        }
    }

    /** Activity onDestroy 时调用，释放资源 */
    fun release() {
        stopVirtualStick()
        feedExecutor.shutdown()
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
        feedFuture?.cancel(false)
        feedFuture = null
        cmdVx = 0f; cmdVy = 0f; cmdVz = 0f; cmdYawRate = 0f

        if (virtualStickEnabled.get()) {
            VirtualStickManager.getInstance().disableVirtualStick(
                object : CommonCallbacks.CompletionCallback {
                    override fun onSuccess() {
                        virtualStickEnabled.set(false)
                        log("VirtualStick DISABLED")
                    }
                    override fun onFailure(error: IDJIError) {
                        virtualStickEnabled.set(false)
                        log("VirtualStick disable FAIL: ${error.description()}")
                    }
                }
            )
        }
    }

    /**
     * 以 10 Hz 将速度缓冲映射到 IStick（Int，-660~660）并写入 SDK。
     * IStick 属性类型为 Int，需将物理速度按比例缩放后转换。
     */
    private fun startFeedTimer() {
        if (feedFuture != null) return
        feedFuture = feedExecutor.scheduleAtFixedRate({
            if (!virtualStickEnabled.get()) return@scheduleAtFixedRate
            try {
                val vsm = VirtualStickManager.getInstance()

                val pitchStick = (cmdVx      / MAX_VH_SPEED * STICK_MAX)
                    .toInt().coerceIn(-STICK_MAX, STICK_MAX)
                val rollStick  = (cmdVy      / MAX_VH_SPEED * STICK_MAX)
                    .toInt().coerceIn(-STICK_MAX, STICK_MAX)
                val throttle   = (cmdVz      / MAX_VZ_SPEED * STICK_MAX)
                    .toInt().coerceIn(-STICK_MAX, STICK_MAX)
                val yawStick   = (cmdYawRate / MAX_YAW_RATE * STICK_MAX)
                    .toInt().coerceIn(-STICK_MAX, STICK_MAX)

                vsm.rightStick.verticalPosition   = pitchStick
                vsm.rightStick.horizontalPosition = rollStick
                vsm.leftStick.verticalPosition    = throttle
                vsm.leftStick.horizontalPosition  = yawStick

            } catch (e: Exception) {
                Log.w(TAG, "feedTimer exception: ${e.message}")
            }
        }, 0L, 100L, TimeUnit.MILLISECONDS)
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        mainHandler.post { logCallback?.invoke(msg) }
    }
}
