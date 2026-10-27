package com.example.msdksample

/**
 * FileListPager — 文件列表的**手动翻页**状态机（纯 Kotlin，可 JVM 单测）
 *
 * 机载的分页接口是无状态的：每次只按 `offset` 回一页
 * （`0x94/0x95` payload = `[offset:u16][next:u16][name\n...]`，`next=0xFFFF` 表示末页）。
 * 所以"上一页"必须由遥控器自己记住每一页的起始 offset —— 这里就是那个记录者。
 *
 * 设计：
 *   * 只缓存**访问过的**页，不预取（省带宽、每页一次往返）；
 *   * `next` 指向的页若已缓存则直接切（不回包）；否则返回 [Nav.FETCH] 让调用方发请求；
 *   * 到过末页后 `totalCount` / `pageCount` 才可知（协议里没有 total 字段）；
 *   * 旧固件（0x90/0x91 单帧）用 [onLegacy] 装成一页，`legacy=true`。
 *
 * 时间/超时/重试由 [ModeController] 负责，本类只做状态。
 */
class FileListPager {

    companion object {
        /** 与固件一致的末页哨兵 */
        const val NO_MORE = 0xFFFF
    }

    enum class Nav {
        /** 不能动（正在加载 / 没有上一页或下一页） */
        NONE,

        /** 目标页已缓存，直接切过去 */
        CACHED,

        /** 需要向机载请求 [pendingOffset] 那一页 */
        FETCH
    }

    data class State(
        val isMap: Boolean,
        val pageIndex: Int,        // 当前显示页（0-based）
        val loadingPageIndex: Int?,// 正在请求的页（0-based）；不在请求中为 null
        val pageCount: Int?,       // 已知总页数（到过末页才有）
        val items: List<String>,   // 当前页条目
        val pageStart: Int,        // 当前页第一条在整个列表里的 0-based 下标
        val loadedCount: Int,      // 已缓存的总条数
        val totalCount: Int?,      // 末页后 = loadedCount
        val hasPrev: Boolean,
        val hasNext: Boolean,
        val isLoading: Boolean,
        val legacy: Boolean = false,
        val error: String? = null
    )

    private val pages = ArrayList<List<String>>()
    private val starts = ArrayList<Int>()
    private val nexts = ArrayList<Int>()

    private var isMap = true
    private var index = -1

    var isLoading: Boolean = false
        private set

    var isLegacy: Boolean = false
        private set

    var error: String? = null
        private set

    /** 正在请求（或需要重试）的 offset */
    var pendingOffset: Int = 0
        private set

    /** 开始一轮新的拉取，返回第一页要请求的 offset（0） */
    fun begin(isMap: Boolean): Int {
        this.isMap = isMap
        pages.clear(); starts.clear(); nexts.clear()
        index = -1
        isLoading = true
        isLegacy = false
        error = null
        pendingOffset = 0
        return pendingOffset
    }

    /**
     * 收到一页。返回 false 表示被忽略（重复页 / 乱序页 / 当前没在等回包）。
     */
    fun onPage(offset: Int, next: Int, names: List<String>): Boolean {
        if (!isLoading) return false
        if (offset != pendingOffset) return false
        if (next != NO_MORE && next <= offset) return false

        pages.add(names)
        starts.add(offset)
        nexts.add(next)
        index = pages.size - 1
        isLoading = false
        error = null
        return true
    }

    /** 旧固件单帧：整份列表当作唯一一页 */
    fun onLegacy(names: List<String>) {
        pages.clear(); starts.clear(); nexts.clear()
        pages.add(names)
        starts.add(0)
        nexts.add(NO_MORE)
        index = 0
        isLoading = false
        isLegacy = true
        error = null
    }

    /** 当前页请求超时：保留已缓存的页，记录错误，等用户重试 */
    fun onTimeout(): Boolean {
        if (!isLoading) return false
        isLoading = false
        error = "第 ${index + 2} 页加载失败"
        return true
    }

    /** 重试当前待取的页（超时后点"下一页/重试"走这里） */
    fun retry(): Nav {
        if (isLoading) return Nav.NONE
        if (pages.isEmpty()) {
            isLoading = true
            pendingOffset = 0
            error = null
            return Nav.FETCH
        }
        val target = nexts.getOrElse(index) { NO_MORE }
        if (target == NO_MORE) return Nav.NONE
        isLoading = true
        pendingOffset = target
        error = null
        return Nav.FETCH
    }

    /** 下一页 */
    fun next(): Nav {
        if (isLoading) return Nav.NONE
        // 首页就失败过（还没有任何页缓存）→ 重试第 1 页
        if (pages.isEmpty()) return retry()
        if (index + 1 < pages.size) {
            index++
            return Nav.CACHED
        }
        val target = nexts.getOrElse(index) { NO_MORE }
        if (target == NO_MORE) return Nav.NONE
        isLoading = true
        pendingOffset = target
        return Nav.FETCH
    }

    /** 上一页 */
    fun prev(): Nav {
        if (isLoading || index <= 0) return Nav.NONE
        index--
        return Nav.CACHED
    }

    fun state(): State {
        val items = pages.getOrElse(index) { emptyList() }
        val loadedCount = pages.sumOf { it.size }
        // 只要有一页回的是 NO_MORE，就说明后面没有了（页是顺序缓存，故 loadedCount 即总数）
        val endKnown = nexts.isNotEmpty() && nexts.last() == NO_MORE
        return State(
            isMap = isMap,
            pageIndex = if (index < 0) 0 else index,
            loadingPageIndex = if (isLoading) pages.size else null,
            pageCount = if (endKnown) pages.size else null,
            items = items,
            pageStart = starts.getOrElse(index) { 0 },
            loadedCount = loadedCount,
            totalCount = if (endKnown) loadedCount else null,
            hasPrev = index > 0,
            hasNext = index in nexts.indices && nexts[index] != NO_MORE,
            isLoading = isLoading,
            legacy = isLegacy,
            error = error
        )
    }
}
