package com.example.msdksample

import com.example.msdksample.health.MotorTemperaturePolicy as Policy
import com.example.msdksample.health.MotorTemperaturePolicy.EscSample
import com.example.msdksample.health.MotorTemperaturePolicy.Status
import org.junit.Assert.*
import org.junit.Test

class MotorTemperaturePolicyTest {
    private val normal = List(4) { EscSample(false, false) }
    @Test fun validClearEscFlagsShowNormal() {
        assertEquals(Status.NORMAL, Policy.evaluate(true, normal, 100, 200, false))
    }
    @Test fun anyHotMotorOverridesIncompleteOtherMotors() {
        assertEquals(Status.HIGH, Policy.evaluate(true, listOf(EscSample(null, null), EscSample(true, false)), 100, 200, false))
    }
    @Test fun healthOverheatOverridesClearEscFlags() {
        assertEquals(Status.HIGH, Policy.evaluate(true, normal, 100, 200, true))
        assertEquals(Status.HIGH, Policy.evaluate(true, null, null, 200, true))
    }
    @Test fun disconnectionNeverShowsCachedNormalOrOverheat() {
        assertEquals(Status.DISCONNECTED, Policy.evaluate(false, normal, 100, 200, true))
        assertEquals(Status.UNKNOWN, Policy.evaluate(null, normal, 100, 200, false))
    }
    @Test fun absentOrIncompleteDataIsUnknown() {
        for (samples in listOf(null, emptyList(), listOf(EscSample(null, false)), listOf(EscSample(false, null)),
            listOf(EscSample(false, true)), normal + EscSample(null, null))) {
            assertEquals(Status.UNKNOWN, Policy.evaluate(true, samples, 100, 200, false))
        }
        assertEquals(Status.UNKNOWN, Policy.evaluate(true, normal, null, 200, false))
    }
    @Test fun staleDataAndInvalidTimeAreUnknown() {
        assertEquals(Status.UNKNOWN, Policy.evaluate(true, normal, 100, 6_101, false))
        assertEquals(Status.UNKNOWN, Policy.evaluate(true, listOf(EscSample(true, false)), 100, 6_101, false))
        assertEquals(Status.UNKNOWN, Policy.evaluate(true, normal, 200, 100, false))
    }
    @Test fun clearingAlarmRequiresCurrentEscDataToShowNormal() {
        assertEquals(Status.UNKNOWN, Policy.evaluate(true, null, null, 200, false))
        assertEquals(Status.NORMAL, Policy.evaluate(true, normal, 100, 200, false))
    }
    @Test fun chineseAndEnglishPropulsionAlertsAreRecognized() {
        for (message in listOf("电机温度过高", "电调过热，请降落", "动力系统高温", "Motor temperature too high",
            "ESC overheating", "Propulsion system high temperature")) {
            assertTrue(message, Policy.isPropulsionOverheat(message, ""))
        }
    }
    @Test fun unrelatedTemperatureAlertsAndEscErrorsAreExcluded() {
        for (message in listOf("云台电机温度过高", "Gimbal motor temperature too high", "电池温度过高", "Battery overheating",
            "飞控温度过高", "Camera high temperature", "ESC disconnected", "电机堵转", "电机温度正常", "Description: high temperature")) {
            assertFalse(message, Policy.isPropulsionOverheat(message, ""))
        }
    }
    @Test fun clearAlarmSubjectTakesPriorityOverComponentsMentionedInAdvice() {
        assertTrue(Policy.isPropulsionOverheat("电机温度过高", "请降落后关闭飞行器，再检查云台和电池"))
        assertFalse(Policy.isPropulsionOverheat("云台电机温度过高", "Motor temperature too high"))
        assertTrue(Policy.isPropulsionOverheat("设备告警", "ESC temperature is too high"))
    }
}
