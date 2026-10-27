package com.example.msdksample

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件列表：分页协议编解码 + 手动翻页状态机（FileListPager）。
 *
 * 覆盖：逐页前进、回退走缓存、重复/乱序页被拒、超时与重试、
 * 末页后才知道总数、旧固件单帧兼容、空目录、中文名。
 */
class FileListPagingTest {

    private val page1 = listOf("1.pcd", "2.pcd", "3.pcd")
    private val page2 = listOf("4.pcd", "5.pcd")

    // ── 手动翻页状态机 ──────────────────────────────────────

    @Test
    fun beginRequestsFirstPageAndWaits() {
        val p = FileListPager()
        assertEquals(0, p.begin(isMap = true))
        assertEquals(0, p.pendingOffset)
        assertTrue(p.isLoading)
        val s = p.state()
        assertEquals(0, s.pageIndex)
        assertTrue(s.items.isEmpty())
        assertFalse(s.hasPrev)
        assertFalse(s.hasNext)
        assertNull(s.totalCount)
    }

    @Test
    fun forwardPagingFillsPagesAndTotalIsKnownOnlyAtLastPage() {
        val p = FileListPager()
        p.begin(isMap = true)
        assertTrue(p.onPage(0, 3, page1))
        var s = p.state()
        assertEquals(page1, s.items)
        assertEquals(3, s.loadedCount)
        assertTrue(s.hasNext)
        assertNull(s.totalCount)      // 还没到末页 → 总数未知
        assertNull(s.pageCount)

        assertEquals(FileListPager.Nav.FETCH, p.next())
        assertEquals(3, p.pendingOffset)
        assertTrue(p.onPage(3, FileListPager.NO_MORE, page2))

        s = p.state()
        assertEquals(page2, s.items)
        assertEquals(1, s.pageIndex)
        assertEquals(5, s.loadedCount)
        assertEquals(5, s.totalCount)
        assertEquals(2, s.pageCount)
        assertFalse(s.hasNext)
        assertTrue(s.hasPrev)
    }

    @Test
    fun goingBackUsesCacheWithoutFetch() {
        val p = FileListPager()
        p.begin(isMap = true)
        p.onPage(0, 3, page1)
        p.next()
        p.onPage(3, FileListPager.NO_MORE, page2)

        assertEquals(FileListPager.Nav.CACHED, p.prev())
        assertEquals(page1, p.state().items)
        assertEquals(0, p.state().pageIndex)

        // 再往前已经在缓存里，不应再发请求
        assertEquals(FileListPager.Nav.CACHED, p.next())
        assertEquals(page2, p.state().items)
        assertEquals(5, p.state().totalCount)
    }

    @Test
    fun duplicateOrOutOfOrderPageIsRejected() {
        val p = FileListPager()
        p.begin(isMap = true)
        assertTrue(p.onPage(0, 3, page1))
        // 已经收过第 0 页，再收一次（不是当前在等的页）→ 拒绝
        assertFalse(p.onPage(0, 3, page1))
        assertEquals(page1.size, p.state().items.size)

        p.next()
        // 在等 offset=3，却来了 offset=5 → 拒绝
        assertFalse(p.onPage(5, FileListPager.NO_MORE, page2))
        assertTrue(p.isLoading)
    }

    @Test
    fun nonAdvancingNextOffsetIsRejected() {
        val p = FileListPager()
        p.begin(isMap = false)
        assertFalse(p.onPage(0, 0, page1))     // next == offset，会让翻页死循环
        assertTrue(p.isLoading)
    }

    @Test
    fun timeoutKeepsPageAndRetryResendsSameOffset() {
        val p = FileListPager()
        p.begin(isMap = true)
        assertTrue(p.onTimeout())
        assertFalse(p.isLoading)
        assertEquals("第 1 页加载失败", p.state().error)

        assertEquals(FileListPager.Nav.FETCH, p.retry())
        assertEquals(0, p.pendingOffset)
        assertNull(p.state().error)
        assertTrue(p.onPage(0, FileListPager.NO_MORE, page1))
        assertEquals(3, p.state().totalCount)
    }

    @Test
    fun emptyDirectoryIsOneEmptyPage() {
        val p = FileListPager()
        p.begin(isMap = true)
        assertTrue(p.onPage(0, FileListPager.NO_MORE, emptyList()))
        val s = p.state()
        assertTrue(s.items.isEmpty())
        assertEquals(0, s.totalCount)
        assertEquals(1, s.pageCount)
        assertFalse(s.hasNext)
    }

    @Test
    fun legacySingleFrameBecomesOnePage() {
        val p = FileListPager()
        p.begin(isMap = false)
        p.onLegacy(page1 + page2)
        val s = p.state()
        assertTrue(s.legacy)
        assertEquals(5, s.totalCount)
        assertEquals(1, s.pageCount)
        assertFalse(s.hasNext)
        assertFalse(s.hasPrev)
    }

    @Test
    fun endlessNextOnLastPageStaysStill() {
        val p = FileListPager()
        p.begin(isMap = true)
        p.onPage(0, FileListPager.NO_MORE, page1)
        assertEquals(FileListPager.Nav.NONE, p.next())
        assertEquals(0, p.state().pageIndex)
    }

    @Test
    fun loadingPageIndexPointsAtThePageBeingFetched() {
        val p = FileListPager()
        p.begin(isMap = true)
        assertEquals(0, p.state().loadingPageIndex)   // 正在取第 1 页
        p.onPage(0, 3, page1)

        p.next()
        val s = p.state()
        assertTrue(s.isLoading)
        assertEquals(1, s.loadingPageIndex)          // 正在取第 2 页
        assertEquals(0, s.pageIndex)                 // 但显示的仍是第 1 页
        assertEquals(page1, s.items)
    }

    @Test
    fun totalStaysKnownAfterGoingBack() {
        val p = FileListPager()
        p.begin(isMap = true)
        p.onPage(0, 3, page1)
        p.next()
        p.onPage(3, FileListPager.NO_MORE, page2)
        assertEquals(5, p.state().totalCount)

        p.prev()
        // 回到第 1 页后，总数/页数依然应该显示（已经到过末页）
        assertEquals(5, p.state().totalCount)
        assertEquals(2, p.state().pageCount)
    }

    @Test
    fun nextAfterFirstPageTimeoutRetriesSameOffset() {
        val p = FileListPager()
        p.begin(isMap = false)
        p.onTimeout()
        assertEquals(FileListPager.Nav.FETCH, p.next())
        assertEquals(0, p.pendingOffset)
        assertTrue(p.isLoading)
    }

    // ── 协议 ───────────────────────────────────────────────

    @Test
    fun listRequestEncodesOffsetLittleEndian() {
        val zero = DroneCommProtocol.encodeListRequest(DroneCommProtocol.CMD_LIST_MAPS, 0)
        assertEquals(6, zero.size)                       // 4 + 2 字节 payload
        assertEquals(DroneCommProtocol.CMD_LIST_MAPS, zero[1])
        assertEquals(2, zero[2].toInt())
        assertArrayEquals(byteArrayOf(0, 0), byteArrayOf(zero[3], zero[4]))

        val big = DroneCommProtocol.encodeListRequest(DroneCommProtocol.CMD_LIST_WAYPOINTS, 300)
        assertEquals(300 and 0xFF, big[3].toInt() and 0xFF)
        assertEquals(300 shr 8, big[4].toInt() and 0xFF)

        val parsed = DroneCommProtocol.decode(big)
        assertTrue(parsed.valid)
        assertEquals(2, parsed.payload.size)
    }

    @Test
    fun pagePayloadIsParsedWithNames() {
        val text = "1.pcd\n园区A 2.pcd\nwaypoints823.yaml".toByteArray(Charsets.UTF_8)
        val payload = byteArrayOf(0x05, 0x00, 0xFF.toByte(), 0xFF.toByte()) + text
        val page = DroneCommProtocol.parseFileListPage(payload)!!
        assertEquals(5, page.offset)
        assertEquals(FileListPager.NO_MORE, page.nextOffset)
        assertTrue(page.isLastPage)
        assertEquals(listOf("1.pcd", "园区A 2.pcd", "waypoints823.yaml"), page.names)
    }

    @Test
    fun emptyPageIsParsedAsEmptyList() {
        val payload = byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0xFF.toByte())
        val page = DroneCommProtocol.parseFileListPage(payload)!!
        assertEquals(0, page.offset)
        assertTrue(page.isLastPage)
        assertTrue(page.names.isEmpty())
    }

    @Test
    fun tooShortPayloadReturnsNull() {
        assertNull(DroneCommProtocol.parseFileListPage(byteArrayOf(0, 0, 0)))
        assertNull(DroneCommProtocol.parseFileListPage(ByteArray(0)))
    }

    /**
     * 字节级交叉校验：这串十六进制是**机载 C++ 实现**（file_list_paging.hpp 的
     * BuildFrame）对 `{"1.pcd", "园区A 2.pcd"}` 第 0 页生成的真实帧。
     * 两端布局一旦不一致，这条会立刻红。
     */
    @Test
    fun airborneCppFrameParsesIdentically() {
        val hex = "AA 94 17 00 00 FF FF 31 2E 70 63 64 0A E5 9B AD E5 8C BA 41 20 32 2E 70 63 64 41"
        val frame = hex.split(" ").map { it.toInt(16).toByte() }.toByteArray()

        val parsed = DroneCommProtocol.decode(frame)
        assertTrue(parsed.valid)
        assertEquals(DroneCommProtocol.CMD_FILE_LIST_PAGE, parsed.cmd)

        val page = DroneCommProtocol.parseFileListPage(parsed.payload)!!
        assertEquals(0, page.offset)
        assertTrue(page.isLastPage)
        assertEquals(listOf("1.pcd", "园区A 2.pcd"), page.names)
    }
}
