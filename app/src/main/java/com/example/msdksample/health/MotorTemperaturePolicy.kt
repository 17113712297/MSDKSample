package com.example.msdksample.health

/** SDK 返回的是电调过热标志，不是电机绕组的摄氏温度。未知状态不能当作正常。 */
object MotorTemperaturePolicy {
    const val FRESHNESS_MS = 6_000L
    enum class Status(val label: String) {
        NORMAL("电机温度正常"), HIGH("电机温度过高"),
        UNKNOWN("电机温度：状态未知"), DISCONNECTED("电机温度：未连接")
    }
    data class EscSample(val tooHot: Boolean?, val disconnected: Boolean?)

    fun evaluate(connected: Boolean?, samples: List<EscSample>?, receivedAt: Long?,
                 now: Long, healthOverheat: Boolean): Status {
        if (connected == false) return Status.DISCONNECTED
        if (connected != true) return Status.UNKNOWN
        if (healthOverheat) return Status.HIGH
        if (samples.isNullOrEmpty() || receivedAt == null || now < receivedAt ||
            now - receivedAt > FRESHNESS_MS) return Status.UNKNOWN
        if (samples.any { it.tooHot == true }) return Status.HIGH
        return if (samples.all { it.tooHot == false && it.disconnected == false })
            Status.NORMAL else Status.UNKNOWN
    }

    /** 健康告警作补充来源；电池、云台、相机、飞控过热不能误算成动力电机过热。 */
    fun isPropulsionOverheat(title: String, description: String): Boolean {
        fun classify(value: String): Boolean? {
            val text = value.lowercase(java.util.Locale.ROOT)
            val propulsion = listOf("电机", "电调", "动力系统", "motor", "propulsion").any(text::contains) ||
                Regex("\\besc\\b").containsMatchIn(text)
            val hot = listOf("温度过高", "过热", "高温", "overheat", "over heat", "overtemperature",
                "over temperature", "temperature too high", "temperature is too high", "temperature high", "high temperature")
                .any(text::contains)
            if (!propulsion || !hot) return null
            return listOf("云台", "gimbal", "电池", "battery", "相机", "camera", "飞控", "flight controller")
                .none(text::contains)
        }
        // 标题已明确告警部件时，不因处理建议里提及另一部件而漏报。
        return classify(title) ?: classify(description) ?: false
    }
}
