package com.example.msdksample

import com.example.msdksample.logs.OperationCommandNames
import com.example.msdksample.logs.OperationLogStore
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class OperationLogStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val utc = TimeZone.getTimeZone("UTC")
    private fun store(folder: File, maxBytes: Long = 1024 * 1024L, maxFiles: Int = 40) =
        OperationLogStore(folder, maxBytes, maxFiles, utc)

    @Test fun recordsSurviveStoreRecreationAndIncludeTimeCategoryAndOrigin() {
        val directory = temp.newFolder()
        store(directory).append(0L, "操作", "界面", "点击：视觉降落")
        val reopened = store(directory)
        val files = reopened.listFiles()
        assertEquals(1, files.size)
        assertTrue(files.single().name.startsWith("operations-1970-01-01-"))
        val line = reopened.readPage(files.single().name).lines.single()
        assertTrue(line.contains("1970-01-01 00:00:00.000 +0000"))
        assertTrue(line.contains("[操作] [界面] 点击：视觉降落"))
    }

    @Test fun messagesCannotInjectExtraRowsAndAreBounded() {
        val directory = temp.newFolder()
        val logs = store(directory)
        logs.append(0L, "操作", "界面", "第一行\n第二行\r\n第三行\t\u0000" + "长".repeat(3000))
        val page = logs.readPage(logs.listFiles().single().name)
        assertEquals(1, page.totalLines)
        assertTrue(page.lines.single().contains("第一行\\n第二行\\r\\n第三行"))
        assertFalse(page.lines.single().contains('\u0000'))
        assertTrue(page.lines.single().length < 1800)
    }

    @Test fun newestPageIsDefaultAndOlderRecordsAreStillAccessible() {
        val directory = temp.newFolder()
        val logs = store(directory)
        repeat(451) { logs.append(it.toLong(), "操作", "界面", "记录-$it") }
        val name = logs.listFiles().single().name
        val latest = logs.readPage(name)
        assertEquals(2, latest.index)
        assertEquals(3, latest.pageCount)
        assertEquals(451, latest.totalLines)
        assertEquals(51, latest.lines.size)
        assertTrue(latest.lines.first().endsWith("记录-400"))
        assertTrue(latest.lines.last().endsWith("记录-450"))
        val previous = logs.readPage(name, 1)
        assertEquals(200, previous.lines.size)
        assertTrue(previous.lines.first().endsWith("记录-200"))
        assertTrue(logs.readPage(name, -1).lines.first().endsWith("记录-0"))
        assertEquals(2, logs.readPage(name, 100).index)
    }

    @Test fun rotationCountsUtf8BytesAndPrunesOldestVolumes() {
        val directory = temp.newFolder()
        val logs = store(directory, 512, 3)
        repeat(60) { logs.append(it.toLong(), "操作", "界面", "视觉降落记录-$it " + "中文".repeat(12)) }
        val files = logs.listFiles()
        assertEquals(3, files.size)
        assertTrue(files.all { it.sizeBytes <= 512 })
        assertTrue(logs.readPage(files.first().name).lines.last().contains("视觉降落记录-59"))
        assertEquals(3, directory.listFiles()!!.size)
    }

    @Test fun eachDayHasSeparateFilesAndNewestDayAppearsFirst() {
        val directory = temp.newFolder()
        val logs = store(directory, maxFiles = 3)
        repeat(4) { logs.append(it * 86_400_000L, "操作", "界面", "第 $it 天") }
        assertEquals(3, logs.listFiles().size)
        assertTrue(logs.listFiles().first().name.contains("1970-01-04"))
        assertFalse(logs.listFiles().any { it.name.contains("1970-01-01") })
    }

    @Test fun deletingActiveFileDoesNotStopSubsequentLogging() {
        val directory = temp.newFolder()
        val logs = store(directory)
        logs.append(0, "操作", "界面", "旧记录")
        val name = logs.listFiles().single().name
        assertTrue(logs.delete(name))
        assertFalse(logs.delete(name))
        assertTrue(logs.listFiles().isEmpty())
        logs.append(1, "操作", "界面", "新记录")
        assertEquals(1, logs.readPage(logs.listFiles().single().name).totalLines)
        assertTrue(logs.readPage(logs.listFiles().single().name).lines.single().endsWith("新记录"))
    }

    @Test fun deletingOneVolumePreservesOtherDates() {
        val directory = temp.newFolder()
        val logs = store(directory)
        logs.append(0, "操作", "界面", "第一天")
        logs.append(86_400_000, "操作", "界面", "第二天")
        val files = logs.listFiles()
        logs.delete(files.last().name)
        assertEquals(listOf(files.first()), logs.listFiles())
    }

    @Test fun clearOnlyDeletesOwnedLogsAndLaterActionsStartFresh() {
        val directory = temp.newFolder()
        val unrelated = File(directory, "notes.txt").apply { writeText("保留") }
        val subdirectory = File(directory, "other").apply { mkdirs() }
        val logs = store(directory)
        logs.append(0, "操作", "界面", "旧记录")
        logs.append(86_400_000, "操作", "界面", "另一份")
        assertEquals(2, logs.clear())
        assertTrue(logs.listFiles().isEmpty())
        assertEquals("保留", unrelated.readText())
        assertTrue(subdirectory.isDirectory)
        logs.append(86_400_001, "操作", "界面", "清空后的操作")
        assertEquals(1, logs.readPage(logs.listFiles().single().name).totalLines)
    }

    @Test fun pathTraversalAndNonLogNamesAreRejected() {
        val directory = temp.newFolder()
        val outside = temp.newFile("outside.txt").apply { writeText("不能删除") }
        val logs = store(directory)
        for (name in listOf("../outside.txt", "..\\outside.txt", outside.absolutePath, "notes.txt")) {
            assertThrows(IllegalArgumentException::class.java) { logs.delete(name) }
            assertThrows(IllegalArgumentException::class.java) { logs.readPage(name) }
        }
        assertEquals("不能删除", outside.readText())
    }

    @Test fun appendRecoversIfActiveFileWasRemovedExternally() {
        val directory = temp.newFolder()
        val logs = store(directory)
        logs.append(0, "操作", "界面", "第一条")
        File(directory, logs.listFiles().single().name).delete()
        logs.append(1, "操作", "界面", "第二条")
        assertTrue(logs.readPage(logs.listFiles().single().name).lines.single().endsWith("第二条"))
    }

    @Test fun concurrentEventsAreNotOverwritten() {
        val directory = temp.newFolder()
        val logs = store(directory)
        val done = CountDownLatch(4)
        repeat(4) { worker -> Thread {
            try { repeat(75) { logs.append(it.toLong(), "操作", "线程$worker", "记录-$worker-$it") } }
            finally { done.countDown() }
        }.apply { isDaemon = true; start() } }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        val name = logs.listFiles().single().name
        val first = logs.readPage(name, 0)
        val second = logs.readPage(name, 1)
        assertEquals(300, first.totalLines)
        assertEquals(300, (first.lines + second.lines).distinct().size)
    }

    @Test fun highFrequencyTelemetryAndPagingAreExcludedFromOperationLogs() {
        for (cmd in listOf(0x04, 0x64, 0x6E, 0x80, 0x90, 0x91, 0x93, 0x94, 0x95)) {
            assertFalse(OperationCommandNames.shouldRecord(cmd.toByte()))
        }
        for (cmd in listOf(0x01, 0x02, 0x03, 0x41, 0x50, 0x51, 0x72, 0x75, 0x76, 0x77, 0x82)) {
            assertTrue(OperationCommandNames.shouldRecord(cmd.toByte()))
        }
        assertEquals("视觉降落", OperationCommandNames.name(0x51))
    }
}
