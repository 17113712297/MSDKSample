package com.example.msdksample

import android.os.Handler
import android.os.Looper
import android.util.Log
import dji.sdk.keyvalue.key.FlightAssistantKey
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.value.flightassistant.AuxiliaryLightMode
import dji.v5.common.callback.CommonCallbacks
import dji.v5.common.error.IDJIError
import dji.v5.manager.KeyManager

/**
 * AuxLightController
 *
 * 控制 Mavic 3T 下视补光灯 (Bottom Auxiliary Light)。
 *
 * 使用 FlightAssistantKey.KeyBottomAuxiliaryLightMode：
 *   AuxiliaryLightMode.OFF   关
 *   AuxiliaryLightMode.ON    开
 *   AuxiliaryLightMode.AUTO  自动 (低光环境自动开启)
 */
class AuxLightController {

    companion object {
        private const val TAG = "AuxLightController"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    var logCallback: ((String) -> Unit)? = null

    /**
     * 设置下视补光灯模式
     *
     * @param modeCode 协议字节 AUX_LIGHT_OFF / ON / AUTO
     */
    fun setBottomAuxLight(modeCode: Byte, onResult: (Boolean, String) -> Unit) {
        val mode = mapMode(modeCode) ?: run {
            val err = "unknown aux light code: 0x${modeCode.toUByte().toString(16)}"
            log("setBottomAuxLight FAIL: $err")
            onResult(false, err)
            return
        }
        log("CMD: setBottomAuxLight mode=$mode")

        KeyManager.getInstance().setValue(
            KeyTools.createKey(FlightAssistantKey.KeyBottomAuxiliaryLightMode),
            mode,
            object : CommonCallbacks.CompletionCallback {
                override fun onSuccess() {
                    log("setBottomAuxLight OK: $mode")
                    onResult(true, "OK")
                }
                override fun onFailure(error: IDJIError) {
                    val msg = error.description() ?: error.errorCode()
                    log("setBottomAuxLight FAIL: $msg")
                    onResult(false, msg)
                }
            }
        )
    }

    private fun mapMode(code: Byte): AuxiliaryLightMode? = when (code) {
        DroneCommProtocol.AUX_LIGHT_OFF  -> AuxiliaryLightMode.OFF
        DroneCommProtocol.AUX_LIGHT_ON   -> AuxiliaryLightMode.ON
        DroneCommProtocol.AUX_LIGHT_AUTO -> AuxiliaryLightMode.AUTO
        else -> null
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        mainHandler.post { logCallback?.invoke(msg) }
    }
}
