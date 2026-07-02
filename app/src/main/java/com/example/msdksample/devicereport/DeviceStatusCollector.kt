package com.example.msdksample.devicereport

import dji.sdk.keyvalue.key.AirLinkKey
import dji.sdk.keyvalue.key.BatteryKey
import dji.sdk.keyvalue.key.DJIKeyInfo
import dji.sdk.keyvalue.key.KeyTools
import dji.sdk.keyvalue.key.ProductKey
import dji.sdk.keyvalue.key.RemoteControllerKey
import dji.sdk.keyvalue.value.remotecontroller.BatteryInfo
import dji.v5.manager.KeyManager

class DeviceStatusCollector {

    fun collect(): DeviceStatusPayload {
        val keyManager = KeyManager.getInstance()

        val uavConnected = keyManager.booleanValue(ProductKey.KeyConnection)
        val rcConnected = keyManager.booleanValue(RemoteControllerKey.KeyConnection)
        val rcBatteryInfo = keyManager.getValue(
            KeyTools.createKey(RemoteControllerKey.KeyBatteryInfo)
        ) as? BatteryInfo

        val controlSoc = rcBatteryInfo?.batteryPercent?.toDouble() ?: 0.0
        val controlRssi = keyManager.intValue(AirLinkKey.KeyUpLinkQuality).toDouble()
        val batteryTemp = keyManager.doubleValue(BatteryKey.KeyBatteryTemperature)
        val batterySoc = keyManager.intValue(BatteryKey.KeyChargeRemainingInPercent).toDouble()
        val batteryRssi = keyManager.intValue(AirLinkKey.KeyDownLinkQuality).toDouble()
        val batteryVolt = normalizeBatteryVoltage(keyManager.intValue(BatteryKey.KeyVoltage))
        val batteryCycleNum = keyManager.intValue(BatteryKey.KeyNumberOfDischarges)

        return DeviceStatusPayload(
            uavState = if (uavConnected) 1 else 0,
            controlState = if (rcConnected) 1 else 0,
            controlSoc = controlSoc,
            controlRssi = controlRssi,
            batteryTemp = batteryTemp,
            batterySoc = batterySoc,
            batteryRssi = batteryRssi,
            batteryVolt = batteryVolt,
            batteryCycleNum = batteryCycleNum
        )
    }

    private fun normalizeBatteryVoltage(rawValue: Int): Double {
        if (rawValue <= 0) return 0.0
        return if (rawValue >= 1000) rawValue / 1000.0 else rawValue.toDouble()
    }

    private fun KeyManager.booleanValue(keyInfo: DJIKeyInfo<Boolean>): Boolean {
        val value = valueOf(keyInfo)
        return value as? Boolean ?: false
    }

    private fun KeyManager.intValue(keyInfo: DJIKeyInfo<Int>): Int {
        val value = valueOf(keyInfo)
        return value as? Int ?: 0
    }

    private fun KeyManager.doubleValue(keyInfo: DJIKeyInfo<*>): Double {
        val value = valueOf(keyInfo)
        return when (value) {
            is Double -> value
            is Float -> value.toDouble()
            is Int -> value.toDouble()
            else -> 0.0
        }
    }

    private fun KeyManager.valueOf(keyInfo: DJIKeyInfo<*>): Any? {
        return runCatching { getValue(KeyTools.createKey(keyInfo)) }.getOrNull()
    }
}
