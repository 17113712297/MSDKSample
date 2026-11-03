package com.example.msdksample

/** 租约覆盖准备、处理和结束回调；旧回调不能释放新任务的相机/云台使用权。 */
class VisionOperationGate {
    enum class Owner { PREFLIGHT, LANDING }
    class Lease internal constructor(val owner: Owner, val id: Long)
    private var nextId = 0L
    private var current: Lease? = null

    @Synchronized fun acquire(owner: Owner): Lease? {
        if (current != null) return null
        return Lease(owner, ++nextId).also { current = it }
    }
    @Synchronized fun owns(lease: Lease?): Boolean = lease != null && current == lease
    @Synchronized fun release(lease: Lease?) { if (owns(lease)) current = null }
}
