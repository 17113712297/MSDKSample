package com.example.msdksample

/**
 * FileListPageAssembler — 文件列表分页组装器
 *
 * 机载把 `mode_manager` 的整串文件名按"条"分页（每页 ≤236 B 文本），
 * 一页一页回给遥控器：
 *
 * ```
 * 0x94 / 0x95  payload: [offset:u16 LE][next:u16 LE][name\nname\n...]
 *              next == 0xFFFF 表示没有下一页
 * ```
 *
 * 本类只负责"按 offset 顺序收页、判重、判乱序、判断结束"，不碰 Android/网络，
 * 便于 JVM 单测。重试与超时由 [ModeController] 负责（它持有 Handler）。
 *
 * 设计要点：
 *   * 只接受 offset == expectedOffset 的页 —— 重复页、乱序页、上一轮遗留页一律忽略；
 *   * nextOffset 必须严格递增，否则判定为异常并忽略（防止死循环）；
 *   * 收到空页且 next=NO_MORE 表示"目录里没有文件"，这是合法结果；
 *   * 条目数超过 [maxItems] 时强制结束，避免机载异常导致无界增长。
 */
class FileListPageAssembler(
    private val maxItems: Int = 2000
) {

    companion object {
        /** 与固件一致的哨兵值：没有下一页 */
        const val NO_MORE = 0xFFFF
    }

    enum class Action {
        /** 该回包被忽略（重复页 / 乱序 / 未在收集中） */
        IGNORE,

        /** 还有下一页，调用方应继续请求 [expectedOffset] */
        NEXT,

        /** 收齐（或达到上限强制结束），[collected] 即结果 */
        COMPLETE
    }

    private val accumulated = ArrayList<String>()

    /** 已收集到的文件名（按机载返回顺序） */
    val collected: List<String>
        get() = accumulated

    /** 是否正在收集 */
    var isActive: Boolean = false
        private set

    /** 下一次应该请求的 offset */
    var expectedOffset: Int = 0
        private set

    fun start() {
        accumulated.clear()
        expectedOffset = 0
        isActive = true
    }

    fun cancel() {
        isActive = false
    }

    /**
     * 喂入一页。
     * @param offset 该页起始条数索引（机载回传，用于判重/判乱序）
     * @param nextOffset 下一页索引；[NO_MORE] 表示结束
     * @param names 该页文件名
     */
    fun onPage(offset: Int, nextOffset: Int, names: List<String>): Action {
        if (!isActive) return Action.IGNORE
        if (offset != expectedOffset) return Action.IGNORE            // 重复页 / 乱序页
        if (nextOffset != NO_MORE && nextOffset <= offset) return Action.IGNORE  // 非法递进

        accumulated.addAll(names)

        if (nextOffset == NO_MORE || accumulated.size >= maxItems) {
            isActive = false
            return Action.COMPLETE
        }
        expectedOffset = nextOffset
        return Action.NEXT
    }
}
