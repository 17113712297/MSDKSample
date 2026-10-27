package com.example.msdksample

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件列表分页：协议编解码 + 分页组装器。
 *
 * 覆盖现场会出现的情况：多页拼接、重复页、乱序页、空目录、
 * 机载异常导致 next 不递增、以及中文文件名（多字节）。
 */
class FileListPagingTest {

    // ── 组装器 ─────────────────────────────────────────────

    @Test
    fun threePagesAreJoinedInOrder() {
        val a = FileListPageAssembler()
        a.start()
        assertEquals(FileListPageAssembler.Action.NEXT, a.onPage(0, 2, listOf("1.pcd", "2.pcd")))
        assertEquals(2, a.expectedOffset)
        assertEquals(FileListPageAssembler.Action.NEXT, a.onPage(2, 3, listOf("3.pcd")))
        assertEquals(FileListPageAssembler.Action.COMPLETE, a.onPage(3, FileListPageAssembler.NO_MORE, listOf("4.pcd")))
        assertEquals(listOf("1.pcd", "2.pcd", "3.pcd", "4.pcd"), a.collected)
        assertTrue(!a.isActive)
    }

    @Test
    fun duplicatePageIsIgnored() {
        val a = FileListPageAssembler()
        a.start()
        a.onPage(0, 2, listOf("1.pcd", "2.pcd"))
        assertEquals(FileListPageAssembler.Action.IGNORE, a.onPage(0, 2, listOf("1.pcd", "2.pcd")))
        assertEquals(listOf("1.pcd", "2.pcd"), a.collected)
        assertEquals(2, a.expectedOffset)
    }

    @Test
    fun outOfOrderPageIsIgnored() {
        val a = FileListPageAssembler()
        a.start()
        // 跳过第 0 页直接来第 2 页 → 忽略
        assertEquals(FileListPageAssembler.Action.IGNORE, a.onPage(2, 3, listOf("3.pcd")))
        assertTrue(a.collected.isEmpty())
        assertEquals(0, a.expectedOffset)
    }

    @Test
    fun nonAdvancingNextOffsetIsRejected() {
        // next == offset：会让调用方死循环，必须忽略
        val a = FileListPageAssembler()
        a.start()
        assertEquals(FileListPageAssembler.Action.IGNORE, a.onPage(0, 0, listOf("x.pcd")))
        assertTrue(a.collected.isEmpty())

        // next < offset：同样非法
        val b = FileListPageAssembler()
        b.start()
        b.onPage(0, 2, listOf("1.pcd", "2.pcd"))
        assertEquals(FileListPageAssembler.Action.IGNORE, b.onPage(2, 1, listOf("3.pcd")))
        assertEquals(2, b.collected.size)
        assertEquals(2, b.expectedOffset)
    }

    @Test
    fun emptyDirectoryCompletesImmediately() {
        val a = FileListPageAssembler()
        a.start()
        assertEquals(
            FileListPageAssembler.Action.COMPLETE,
            a.onPage(0, FileListPageAssembler.NO_MORE, emptyList())
        )
        assertTrue(a.collected.isEmpty())
    }

    @Test
    fun pageBeforeStartIsIgnored() {
        val a = FileListPageAssembler()
        assertEquals(FileListPageAssembler.Action.IGNORE, a.onPage(0, 1, listOf("x")))
    }

    @Test
    fun cancelStopsAcceptingPages() {
        val a = FileListPageAssembler()
        a.start()
        a.onPage(0, 1, listOf("1.pcd"))
        a.cancel()
        assertEquals(FileListPageAssembler.Action.IGNORE, a.onPage(1, 2, listOf("2.pcd")))
        assertEquals(1, a.collected.size)
    }

    @Test
    fun maxItemsGuardsAgainstRunawayServer() {
        val a = FileListPageAssembler(maxItems = 3)
        a.start()
        // 一页就超过上限 → 强制结束，避免无界增长
        assertEquals(
            FileListPageAssembler.Action.COMPLETE,
            a.onPage(0, 10, listOf("a", "b", "c", "d"))
        )
        assertEquals(4, a.collected.size)
    }

    @Test
    fun restartClearsPreviousRun() {
        val a = FileListPageAssembler()
        a.start()
        a.onPage(0, 1, listOf("old.pcd"))
        a.start()
        assertEquals(0, a.expectedOffset)
        a.onPage(0, FileListPageAssembler.NO_MORE, listOf("new.pcd"))
        assertEquals(listOf("new.pcd"), a.collected)
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

        // 帧本身必须能被既有解码器吃下（XOR 正确）
        val parsed = DroneCommProtocol.decode(big)
        assertTrue(parsed.valid)
        assertEquals(2, parsed.payload.size)
    }

    @Test
    fun pagePayloadIsParsedWithNames() {
        val text = "1.pcd\n园区A 2.pcd\nwaypoints823.yaml".toByteArray(Charsets.UTF_8)
        val payload = byteArrayOf(0x05, 0x00, (0xFF).toByte(), (0xFF).toByte()) + text
        val page = DroneCommProtocol.parseFileListPage(payload)!!
        assertEquals(5, page.offset)
        assertEquals(FileListPageAssembler.NO_MORE, page.nextOffset)
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
     * 这里断言 Kotlin 侧能原样解开 —— 两端布局一旦不一致，这条会立刻红。
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
