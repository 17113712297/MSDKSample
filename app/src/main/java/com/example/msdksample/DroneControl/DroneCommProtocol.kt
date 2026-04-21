package com.example.msdksample

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PSDK ↔ MSDK 低速通道二进制帧协议 (Android/Kotlin 侧)
 *
 * 与 C++ 的 drone_comm_protocol.hpp 保持完全一致。
 *
 * 帧格式：
 *   [0xAA] [CMD 1B] [LEN 1B] [PAYLOAD N B] [XOR 1B]
 *
 * 所有 float 使用 IEEE 754 小端序 (Little-Endian)，与 C++ 一致。
 */
object DroneCommProtocol {

    // ── 帧头 ─────────────────────────────────────────────
    const val FRAME_HEADER: Byte = 0xAA.toByte()
    const val MAX_PAYLOAD_LEN = 251

    // ── 指令类型 ─────────────────────────────────────────
    // 飞控 (Jetson → Android)
    const val CMD_TAKEOFF: Byte  = 0x01
    const val CMD_LAND:    Byte  = 0x02
    const val CMD_HOVER:   Byte  = 0x03
    const val CMD_VEL:     Byte  = 0x04
    // 应答 (Android → Jetson)
    const val CMD_ACK:     Byte  = 0x80.toByte()

    // ── ACK 状态码 ────────────────────────────────────────
    const val ACK_OK:      Byte = 0x00
    const val ACK_FAIL:    Byte = 0x01
    const val ACK_UNKNOWN: Byte = 0xFF.toByte()

    // ── VEL_CMD 载荷常量 (16 B = 4 × float32) ────────────
    const val VEL_PAYLOAD_LEN = 16

    // ─────────────────────────────────────────────────────
    //  解码
    // ─────────────────────────────────────────────────────

    /**
     * 解码一帧字节流
     * @param data 收到的原始字节数组
     * @return ParsedFrame；若校验失败则 valid = false
     */
    fun decode(data: ByteArray): ParsedFrame {
        val invalid = ParsedFrame(valid = false)
        if (data.size < 4) return invalid
        if (data[0] != FRAME_HEADER) return invalid
        val payloadLen = data[2].toInt() and 0xFF
        if (data.size < 4 + payloadLen) return invalid
        // XOR 校验
        var xor = 0
        for (i in 0 until 3 + payloadLen) xor = xor xor (data[i].toInt() and 0xFF)
        if (xor.toByte() != data[3 + payloadLen]) return invalid

        val payload = data.copyOfRange(3, 3 + payloadLen)
        return ParsedFrame(valid = true, cmd = data[1], payload = payload)
    }

    /**
     * 解析 VEL_CMD 载荷为 VelPayload (小端序 float × 4)
     */
    fun parseVelPayload(payload: ByteArray): VelPayload? {
        if (payload.size < VEL_PAYLOAD_LEN) return null
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        return VelPayload(
            vx      = bb.float,
            vy      = bb.float,
            vz      = bb.float,
            yawRate = bb.float
        )
    }

    // ─────────────────────────────────────────────────────
    //  编码 (ACK 回包：Android → Jetson)
    // ─────────────────────────────────────────────────────

    /**
     * 编码 ACK 帧
     * @param ackedCmd 被应答的原始指令字节
     * @param status   ACK_OK / ACK_FAIL / ACK_UNKNOWN
     * @return 编码后的字节数组 (6 B)
     */
    fun encodeAck(ackedCmd: Byte, status: Byte): ByteArray {
        // payload: [ackedCmd 1B][status 1B] = 2 B
        val payload = byteArrayOf(ackedCmd, status)
        return encodeFrame(CMD_ACK, payload)
    }

    // ─────────────────────────────────────────────────────
    //  内部帧编码工具
    // ─────────────────────────────────────────────────────

    private fun encodeFrame(cmd: Byte, payload: ByteArray = ByteArray(0)): ByteArray {
        val len = payload.size
        val frame = ByteArray(4 + len)
        frame[0] = FRAME_HEADER
        frame[1] = cmd
        frame[2] = len.toByte()
        payload.copyInto(frame, destinationOffset = 3)
        var xor = 0
        for (i in 0 until 3 + len) xor = xor xor (frame[i].toInt() and 0xFF)
        frame[3 + len] = xor.toByte()
        return frame
    }

    // ─────────────────────────────────────────────────────
    //  数据类
    // ─────────────────────────────────────────────────────

    data class ParsedFrame(
        val valid:   Boolean = false,
        val cmd:     Byte    = 0,
        val payload: ByteArray = ByteArray(0)
    )

    /**
     * VEL_CMD 载荷
     *  坐标系：机体坐标系 (Body Frame)
     *    vx      前向  m/s   (正 = 向机头方向)
     *    vy      右向  m/s   (正 = 向右)
     *    vz      上向  m/s   (正 = 向上)
     *    yawRate 右转  deg/s (正 = 顺时针)
     */
    data class VelPayload(
        val vx:      Float,
        val vy:      Float,
        val vz:      Float,
        val yawRate: Float
    )
}
