package com.example.msdksample.health

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.example.msdksample.logs.OperationLogger
import dji.sdk.keyvalue.key.FlightControllerKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.flightcontroller.EscStateInfo
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager
import dji.v5.manager.diagnostic.DJIDeviceHealthInfo
import dji.v5.manager.diagnostic.DJIDeviceHealthInfoChangeListener
import dji.v5.manager.diagnostic.DeviceHealthManager

/** 只读监测；全部状态在主线程合并，旧连接/旧页面的异步结果不能覆盖当前状态。 */
class MotorTemperatureMonitor(private val onStatus: (MotorTemperaturePolicy.Status) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val connectionKey by lazy { KeyTools.createKey(FlightControllerKey.KeyConnection) }
    private val escKey by lazy { KeyTools.createKey(FlightControllerKey.KeyEscState) }
    private var healthListener: DJIDeviceHealthInfoChangeListener? = null
    private var running = false
    private var generation = 0L
    private var connectionEpoch = 0L
    private var requestId = 0L
    private var connected: Boolean? = null
    private var samples: List<MotorTemperaturePolicy.EscSample>? = null
    private var receivedAt: Long? = null
    private var healthOverheat = false
    private var lastStatus: MotorTemperaturePolicy.Status? = null
    private val poll = object : Runnable {
        override fun run() {
            if (!running) return
            refresh()
            handler.postDelayed(this, 2_000L)
        }
    }

    fun start() {
        if (running) return
        running = true
        val session = ++generation
        connected = null; samples = null; receivedAt = null; healthOverheat = false
        lastStatus = null
        publish()
        runCatching {
            KeyManager.getInstance().listen(connectionKey, this, true) { _, value ->
                handler.post {
                    if (isCurrent(session)) {
                        updateConnection(value)
                        if (value == true) refresh()
                    }
                }
            }
        }.onFailure { Log.w("MotorTemperature", "连接监听暂不可用，继续定时读取", it) }
        val listener = DJIDeviceHealthInfoChangeListener { infos ->
            // 在回调内取快照，不把 SDK 可能复用的列表留到另一线程遍历。
            val hot = runCatching { hasHealthOverheat(infos) }.getOrNull()
            handler.post {
                if (isCurrent(session) && connected == true && hot != null) {
                    healthOverheat = hot
                    publish()
                }
            }
        }
        healthListener = listener
        runCatching { DeviceHealthManager.getInstance().addDJIDeviceHealthInfoChangeListener(listener) }
            .onFailure { Log.w("MotorTemperature", "健康告警监听暂不可用", it) }
        handler.post(poll)
    }

    fun stop() {
        running = false
        generation++; requestId++; connectionEpoch++
        handler.removeCallbacksAndMessages(null)
        runCatching { KeyManager.getInstance().cancelListen(connectionKey, this) }
        healthListener?.let { listener ->
            runCatching { DeviceHealthManager.getInstance().removeDJIDeviceHealthInfoChangeListener(listener) }
        }
        healthListener = null
    }

    private fun isCurrent(session: Long) = running && generation == session

    private fun updateConnection(value: Boolean?) {
        if (connected != value) {
            connected = value
            connectionEpoch++
            samples = null; receivedAt = null; healthOverheat = false
        }
        publish()
    }

    private fun refresh() {
        val session = generation
        val request = ++requestId
        val epoch = connectionEpoch
        publish() // 即使 SDK 未回调，超过有效期也转为未知。
        runCatching {
            KeyManager.getInstance().getValue(connectionKey, object : CommonCallbacks.CompletionCallbackWithParam<Boolean> {
                override fun onSuccess(value: Boolean?) {
                    handler.post {
                        if (!isCurrent(session) || request != requestId || epoch != connectionEpoch) return@post
                        updateConnection(value)
                        if (value == true) {
                            runCatching { updateHealth(DeviceHealthManager.getInstance().currentDJIDeviceHealthInfos) }
                            readEsc(session, request, connectionEpoch)
                        }
                    }
                }
                override fun onFailure(error: IDJIError) {
                    handler.post {
                        if (isCurrent(session) && request == requestId && epoch == connectionEpoch) updateConnection(null)
                    }
                }
            })
        }.onFailure { updateConnection(null) }
    }

    private fun readEsc(session: Long, request: Long, epoch: Long) {
        fun accepts() = isCurrent(session) && request == requestId && epoch == connectionEpoch && connected == true
        runCatching {
            KeyManager.getInstance().getValue(escKey, object : CommonCallbacks.CompletionCallbackWithParam<List<EscStateInfo>> {
                override fun onSuccess(value: List<EscStateInfo>?) {
                    val snapshot = runCatching {
                        value?.map { info ->
                            MotorTemperaturePolicy.EscSample(info?.state?.isTemperHigh, info?.state?.isESCDDisconnected)
                        }
                    }.getOrNull()
                    handler.post {
                        if (!accepts()) return@post
                        samples = snapshot; receivedAt = SystemClock.elapsedRealtime()
                        publish()
                    }
                }
                override fun onFailure(error: IDJIError) {
                    handler.post {
                        if (!accepts()) return@post
                        samples = null; receivedAt = null
                        publish() // 未支持/读取失败时不沿用上次“正常”。
                    }
                }
            })
        }.onFailure { if (accepts()) { samples = null; receivedAt = null; publish() } }
    }

    private fun updateHealth(infos: List<DJIDeviceHealthInfo>?) {
        healthOverheat = hasHealthOverheat(infos)
        publish()
    }

    private fun hasHealthOverheat(infos: List<DJIDeviceHealthInfo>?): Boolean =
        infos.orEmpty().any {
            MotorTemperaturePolicy.isPropulsionOverheat(it.title().orEmpty(), it.description().orEmpty())
        }

    private fun publish() {
        val status = MotorTemperaturePolicy.evaluate(connected, samples, receivedAt,
            SystemClock.elapsedRealtime(), healthOverheat)
        if (lastStatus == status) return
        lastStatus = status
        if (status == MotorTemperaturePolicy.Status.HIGH) OperationLogger.error("电机温度", status.label)
        else OperationLogger.state("电机温度", status.label)
        onStatus(status)
    }
}
