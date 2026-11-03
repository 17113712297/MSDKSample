package com.example.msdksample

enum class LandingDockType(val label: String) {
    NEW_DUAL("新版双码"), LEGACY_MULTI("旧版多尺寸码")
}

/** 一次降落的类型锁。跟踪缓存重置不影响它，只有下一次获准启动任务才能 begin。 */
class LandingDockSession {
    companion object {
        const val REQUIRED_FRAMES = 10
        const val MIN_CONFIRM_MS = 500L
        const val MAX_FRAME_GAP_MS = 200L
    }

    private var generation = 0L
    private var active = false
    private var locked: LandingDockType? = null
    private var candidate: LandingDockType? = null
    private var count = 0
    private var firstTime = 0L
    private var lastTime = 0L

    @Synchronized fun begin(): Long {
        check(!active) { "当前降落任务尚未结束，禁止重选机巢类型" }
        generation++
        active = true
        locked = null
        clearCandidate()
        return generation
    }

    // 中止即停止接收；保留类型供诊断。控制器确认释放后才允许 begin 新任务。
    @Synchronized fun freeze() { active = false; clearCandidate() }
    @Synchronized fun activeId(): Long? = generation.takeIf { active }
    @Synchronized fun lockedType(): LandingDockType? = locked
    @Synchronized fun accepts(id: Long?, type: LandingDockType): Boolean =
        active && id == generation && locked == type

    /** 两类同时有效属于歧义；重复/过期帧不能累计确认。锁定后永不改型。 */
    @Synchronized fun observe(id: Long, newValid: Boolean, legacyValid: Boolean, time: Long): LandingDockType? {
        if (!active || id != generation) return null
        locked?.let { return it }
        val type = when {
            newValid && !legacyValid -> LandingDockType.NEW_DUAL
            legacyValid && !newValid -> LandingDockType.LEGACY_MULTI
            else -> { clearCandidate(); return null }
        }
        if (time <= 0L || (lastTime > 0L && time <= lastTime)) return null
        if (candidate != type || time - lastTime > MAX_FRAME_GAP_MS) {
            candidate = type
            count = 0
            firstTime = time
        }
        lastTime = time
        count++
        if (count >= REQUIRED_FRAMES && time - firstTime >= MIN_CONFIRM_MS) locked = type
        return locked
    }

    private fun clearCandidate() {
        candidate = null; count = 0; firstTime = 0L; lastTime = 0L
    }
}

data class LandingVisionResult(
    val dockType: LandingDockType,
    val targetId: Int,
    val errX: Double,
    val errY: Double,
    val depthZ: Double,
    val yawDeg: Double,
    val timestamp: Long,
    val sessionId: Long?
)
