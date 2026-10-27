package com.example.msdksample

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0xAA 帧重组器回归测试。
 *
 * 现场故障：地图/航线文件一多，遥控器列表整条加载不出来。
 * 根因是 PSDK 低速通道物理包上限 128 B，>128 B 的帧会被拆成多次回调，
 * 而接收端原先只认「一次回调 = 一整帧」。
 * 这里覆盖分包/粘包/半包/脏数据四种边界。
 */
class DroneFrameAssemblerTest {

    private fun frame(cmd: Byte, payload: ByteArray) = DroneCommProtocol.encodePayload(cmd, payload)

    private fun textOf(frame: DroneCommProtocol.ParsedFrame) = String(frame.payload, Charsets.UTF_8)

    @Test
    fun singleShortFrameIsDelivered() {
        val assembler = DroneFrameAssembler()
        val frames = assembler.feed(frame(DroneCommProtocol.CMD_FILE_LIST_RESPONSE, "1.pcd,2.pcd".toByteArray()))
        assertEquals(1, frames.size)
        assertEquals(DroneCommProtocol.CMD_FILE_LIST_RESPONSE, frames[0].cmd)
        assertEquals("1.pcd,2.pcd", textOf(frames[0]))
        assertEquals(0, assembler.pendingBytes)
    }

    /** 复现真实现场：244 B 的列表帧被 PSDK 切成 128 + 116 两次回调 */
    @Test
    fun frameSplitAt128BytesIsReassembled() {
        val assembler = DroneFrameAssembler()
        val payload = ByteArray(240) { 'A'.code.toByte() }
        val wire = frame(DroneCommProtocol.CMD_FILE_LIST_RESPONSE, payload)
        assertEquals(244, wire.size)

        // PSDK 第一包 128 B
        assertTrue(assembler.feed(wire.copyOfRange(0, 128)).isEmpty())
        assertEquals(128, assembler.pendingBytes)

        // 第二包 116 B → 必须凑出完整一帧
        val frames = assembler.feed(wire.copyOfRange(128, wire.size))
        assertEquals(1, frames.size)
        assertEquals(240, frames[0].payload.size)
        assertEquals(0, assembler.pendingBytes)
    }

    /** 粘包：一次回调里多帧 */
    @Test
    fun multipleFramesInOneCallbackAreAllDelivered() {
        val assembler = DroneFrameAssembler()
        val a = frame(DroneCommProtocol.CMD_VEL, ByteArray(16))
        val b = DroneCommProtocol.encodeSimple(DroneCommProtocol.CMD_ACK_HOVER_COMPLETE)
        val c = frame(DroneCommProtocol.CMD_MISSION_STATE, byteArrayOf(3, 5, 9, 0))
        val frames = assembler.feed(a + b + c)
        assertEquals(3, frames.size)
        assertEquals(DroneCommProtocol.CMD_VEL, frames[0].cmd)
        assertEquals(DroneCommProtocol.CMD_ACK_HOVER_COMPLETE, frames[1].cmd)
        assertEquals(DroneCommProtocol.CMD_MISSION_STATE, frames[2].cmd)
    }

    /** 半包极端情况：逐字节喂入 */
    @Test
    fun byteByByteFeedingStillYieldsOneFrame() {
        val assembler = DroneFrameAssembler()
        val wire = frame(DroneCommProtocol.CMD_FILE_LIST_RESPONSE_WP, "waypoints.yaml,wp1.yaml".toByteArray())
        var delivered = 0
        for (byte in wire) {
            delivered += assembler.feed(byteArrayOf(byte)).size
        }
        assertEquals(1, delivered)
        assertEquals(0, assembler.pendingBytes)
    }

    /** 帧前有垃圾字节时仍能靠 0xAA 同步 */
    @Test
    fun garbagePrefixIsSkipped() {
        val assembler = DroneFrameAssembler()
        val junk = byteArrayOf(0x00, 0x11, 0x7F, 0x00)
        val frames = assembler.feed(junk + DroneCommProtocol.encodeSimple(DroneCommProtocol.CMD_ACK))
        assertEquals(1, frames.size)
        assertEquals(DroneCommProtocol.CMD_ACK, frames[0].cmd)
    }

    /** XOR 损坏的帧被丢弃，且不能拖累紧随其后的好帧 */
    @Test
    fun corruptedFrameIsDroppedAndNextFrameStillParses() {
        val assembler = DroneFrameAssembler()
        val bad = frame(DroneCommProtocol.CMD_CAM_SHOOT, ByteArray(0))
        bad[bad.size - 1] = (bad[bad.size - 1].toInt() xor 0xFF).toByte()
        val good = frame(DroneCommProtocol.CMD_FILE_LIST_RESPONSE, "ok.pcd".toByteArray())

        val frames = assembler.feed(bad + good)
        assertEquals(1, frames.size)
        assertEquals("ok.pcd", textOf(frames[0]))
        assertEquals(0, assembler.pendingBytes)
    }

    /** LEN 字段非法（>251）时不能死等，也不能崩 */
    @Test
    fun illegalLengthFieldIsResynced() {
        val assembler = DroneFrameAssembler()
        val bogus = byteArrayOf(0xAA.toByte(), 0x90.toByte(), 0xFF.toByte(), 0x01, 0x02)
        assertTrue(assembler.feed(bogus).isEmpty())
        // 后面来一个正常帧，依然能被解析出来
        val frames = assembler.feed(DroneCommProtocol.encodeSimple(DroneCommProtocol.CMD_HOVER))
        assertEquals(1, frames.size)
    }

    @Test
    fun zeroLengthPayloadFrameIsDelivered() {
        val assembler = DroneFrameAssembler()
        val frames = assembler.feed(DroneCommProtocol.encodeSimple(DroneCommProtocol.CMD_ACK_TAKEOFF_COMPLETE))
        assertEquals(1, frames.size)
        assertEquals(0, frames[0].payload.size)
    }

    /** 长连续流：100 帧一次喂入 */
    @Test
    fun longStreamOfFramesIsCountedCorrectly() {
        val assembler = DroneFrameAssembler()
        var wire = ByteArray(0)
        repeat(100) { wire += frame(DroneCommProtocol.CMD_MISSION_STATE, byteArrayOf(3, it.toByte(), 50, 0)) }
        val frames = assembler.feed(wire)
        assertEquals(100, frames.size)
        assertEquals(100L, assembler.frameCount)
        assertEquals(0, assembler.pendingBytes)
    }

    /**
     * 全是垃圾字节时：在"找同步头"阶段就被丢干净，缓冲不会被撑爆，
     * 也不会影响后续正常帧。（`maxBufferedBytes` 只是兜底，正常流量永远碰不到。）
     */
    @Test
    fun pureGarbageIsDrainedAndDoesNotPoisonNextFrame() {
        val assembler = DroneFrameAssembler(maxBufferedBytes = 256)
        assertEquals(0, assembler.feed(ByteArray(4096) { 0x42 }).size)
        assertEquals(0, assembler.pendingBytes)
        assertEquals(0L, assembler.droppedBytes)
        assertEquals(1, assembler.feed(DroneCommProtocol.encodeSimple(DroneCommProtocol.CMD_ACK)).size)
    }

    @Test
    fun resetClearsHalfFrame() {
        val assembler = DroneFrameAssembler()
        val wire = frame(DroneCommProtocol.CMD_FILE_LIST_RESPONSE, "a.pcd,b.pcd".toByteArray())
        assembler.feed(wire.copyOfRange(0, 6))
        assertTrue(assembler.pendingBytes > 0)
        assembler.reset()
        assertEquals(0, assembler.pendingBytes)
        // 重置后重新收到完整帧仍应正常
        assertEquals(1, assembler.feed(wire).size)
    }

    /**
     * 关键回归：一个"假帧头 + 巨大 LEN"不能把后续真实帧吞掉。
     *
     * 场景：链路上出现脏字节 `AA 90 FB`（LEN=251，看起来像一帧的开头）。
     * 组包器会先等够 255 字节，凑满后 XOR 校验失败 → 滑 1 字节重新找 0xAA，
     * 紧随其后的 `0x03 HOVER` 帧必须仍然被交付。
     * 这条保证 A 的引入不会影响 0x04/0x93 这类短帧。
     */
    @Test
    fun bogusLengthHeaderDoesNotSwallowFollowingFrames() {
        val assembler = DroneFrameAssembler()
        val bogus = byteArrayOf(0xAA.toByte(), 0x90.toByte(), 0xFB.toByte())
        val good = DroneCommProtocol.encodeSimple(DroneCommProtocol.CMD_HOVER)

        assertTrue(assembler.feed(bogus + good).isEmpty())   // 还在等满 255 字节

        val frames = assembler.feed(ByteArray(300) { 0x00 })
        assertEquals(1, frames.size)
        assertEquals(DroneCommProtocol.CMD_HOVER, frames[0].cmd)
    }
}
