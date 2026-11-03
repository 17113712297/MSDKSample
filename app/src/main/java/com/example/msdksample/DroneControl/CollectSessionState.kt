package com.example.msdksample

/** 采点会话只随机载回执切换；发出结束请求时保留活动状态和操作入口。 */
class CollectSessionState {
    enum class Phase { IDLE, RUNNING, MAP_2D_DONE, PIXEL_DONE }

    companion object { const val STOP_TIMEOUT_MS = 10_000L }

    @Volatile var phase: Phase = Phase.IDLE
        private set
    @Volatile private var stopRequestedAt: Long? = null
    val isCollecting: Boolean get() = phase != Phase.IDLE
    val stopPending: Boolean get() = stopRequestedAt != null

    fun acceptStart() { phase = Phase.RUNNING }

    /** 生成结果可晚于结束回执到达，此时不能重新激活已经结束的会话。 */
    fun acceptGenerated(next: Phase): Boolean {
        require(next == Phase.MAP_2D_DONE || next == Phase.PIXEL_DONE)
        if (!isCollecting) return false
        phase = next
        return true
    }

    fun requestStop(now: Long): Boolean {
        if (!isCollecting || stopPending) return false
        stopRequestedAt = now
        return true
    }

    fun acknowledgeStop(success: Boolean) {
        stopRequestedAt = null
        if (success) phase = Phase.IDLE
    }

    /** 未收到回执时允许重试，不能把远端未确认结束的会话当成空闲。 */
    fun expireStop(now: Long): Boolean {
        val requestedAt = stopRequestedAt ?: return false
        if (now < requestedAt || now - requestedAt < STOP_TIMEOUT_MS) return false
        stopRequestedAt = null
        return true
    }
}
