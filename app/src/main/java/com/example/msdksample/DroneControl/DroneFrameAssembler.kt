package com.example.msdksample

/**
 * DroneFrameAssembler — 0xAA 帧重组器（解决 PSDK 分包 / 粘包 / 半包）
 *
 * ── 为什么需要它 ────────────────────────────────────────────────
 * PSDK 低速数据通道的**物理链路单包上限是 128 字节**，DJI 原厂头文件
 * `psdk_lib/include/dji_low_speed_data_channel.h` 写得很明确：
 *
 *   "Max size of data package sent to selected channel address end on a
 *    physical link of command channel is 128. If the length of data to be
 *    sent is greater than 128, data to be sent will be divided into multiple
 *    packages to send, and the user will also receive multiple data packages
 *    on the selected channel address end."
 *
 * 也就是说：机载一次 SendFrame 只要 >128 字节，遥控器端就会收到**多次回调**。
 * 而回调里直接 `decode(bytes)` 只认「一次回调 = 一整帧」，于是：
 *
 *   回调#1：128 B        → 不足 4+LEN → XOR/长度校验失败 → 丢弃
 *   回调#2：剩余 N B     → 连帧头都没有                → 丢弃
 *
 * 结果是整帧一个字节都到不了业务层。典型受害者就是 `0x90 / 0x91`
 * 文件列表（一整串逗号分隔的文件名），文件一多整条列表就消失了。
 *
 * ── 做法 ───────────────────────────────────────────────────────
 * 累积字节 → 找 `0xAA` 同步头 → 读 `LEN` → 凑满 `4+LEN` 就切一帧交付。
 * 顺带解决两类与分包同源的问题：
 *   * 粘包：一次回调里来了 2~3 帧（例如 `0x04` 与 `0x93` 相邻）
 *   * 半包：一帧横跨多次回调（PSDK 128 字节切分必然出现）
 *
 * 纯 Kotlin、无 Android 依赖，便于 JVM 单测覆盖。
 *
 * @param maxPayloadBytes 单帧 payload 上限，与协议 `LEN` 字段一致（251）
 * @param maxBufferedBytes 缓冲上限；超过说明链路已错位，整段丢弃防止无界增长
 */
class DroneFrameAssembler(
    private val maxPayloadBytes: Int = DroneCommProtocol.MAX_PAYLOAD_LEN,
    private val maxBufferedBytes: Int = 2048
) {

    private var buf = ByteArray(512)
    private var size = 0

    /** 已解析交付的完整帧数量（诊断用） */
    var frameCount: Long = 0L
        private set

    /** 因缓冲超限而丢弃的字节数（诊断用）。正常链路应恒为 0。 */
    var droppedBytes: Long = 0L
        private set

    /** 当前还压在缓冲里、尚未凑成整帧的字节数 */
    val pendingBytes: Int
        get() = size

    /** 断链/重连时清空残留，避免上一条半帧污染新数据 */
    fun reset() {
        size = 0
    }

    /**
     * 喂入一次回调的字节，返回本次凑齐并可交付的全部完整帧。
     * 不合法（XOR 错、长度错）的候选帧会被跳过，并从下一个字节重新同步。
     */
    fun feed(bytes: ByteArray): List<DroneCommProtocol.ParsedFrame> {
        if (bytes.isEmpty()) return emptyList()
        append(bytes)

        val frames = ArrayList<DroneCommProtocol.ParsedFrame>(4)
        var off = 0
        while (true) {
            // 1) 找同步头（丢掉帧间的垃圾字节）
            while (off < size && buf[off] != DroneCommProtocol.FRAME_HEADER) off++
            if (size - off < 4) break

            // 2) 读长度字段
            val len = buf[off + 2].toInt() and 0xFF
            if (len > maxPayloadBytes) {
                // LEN 非法 → 这个 0xAA 是假的，滑一字节继续找
                off++
                continue
            }
            val total = 4 + len
            // 3) 半包：数据还不够，保留剩余字节等下次回调
            if (size - off < total) break

            val frame = DroneCommProtocol.decode(buf.copyOfRange(off, off + total))
            if (frame.valid) {
                frames.add(frame)
                frameCount++
                off += total
            } else {
                // XOR 不符 → 同步头是假的（或该帧损坏），滑一字节重新同步
                off++
            }
        }

        // 4) 压紧剩余字节
        val remaining = size - off
        if (remaining > 0) System.arraycopy(buf, off, buf, 0, remaining)
        size = remaining

        // 5) 兜底：缓冲长期超限说明链路已经错位，整段丢弃，避免无界增长
        if (size > maxBufferedBytes) {
            droppedBytes += size.toLong()
            size = 0
        }
        return frames
    }

    private fun append(bytes: ByteArray) {
        if (size + bytes.size > buf.size) {
            var newCapacity = buf.size
            while (newCapacity < size + bytes.size) newCapacity *= 2
            buf = buf.copyOf(newCapacity)
        }
        System.arraycopy(bytes, 0, buf, size, bytes.size)
        size += bytes.size
    }
}
