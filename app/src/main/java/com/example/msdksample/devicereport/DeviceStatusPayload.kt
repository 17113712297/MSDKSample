package com.example.msdksample.devicereport

import org.json.JSONObject
import kotlin.math.round

data class DeviceStatusPayload(
    val uavState: Int,
    val controlState: Int,
    val controlSoc: Double,
    val controlRssi: Double,
    val batteryTemp: Double,
    val batterySoc: Double,
    val batteryRssi: Double,
    val batteryVolt: Double,
    val batteryCycleNum: Int
) {
    fun toJsonString(): String {
        return JSONObject()
            .put("uavState", uavState)
            .put("controlState", controlState)
            .put("controlSoc", controlSoc.roundTo(2))
            .put("controlRssi", controlRssi.roundTo(2))
            .put("batteryTemp", batteryTemp.roundTo(2))
            .put("batterySoc", batterySoc.roundTo(2))
            .put("batteryRssi", batteryRssi.roundTo(2))
            .put("batteryVolt", batteryVolt.roundTo(2))
            .put("batteryCycleNum", batteryCycleNum)
            .toString()
    }

    private fun Double.roundTo(scale: Int): Double {
        var factor = 1.0
        repeat(scale) { factor *= 10.0 }
        return round(this * factor) / factor
    }
}
