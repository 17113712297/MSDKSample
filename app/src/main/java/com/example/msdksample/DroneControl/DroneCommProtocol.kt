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
    const val CMD_TAKEOFF: Byte           = 0x01
    const val CMD_LAND:    Byte           = 0x02
    const val CMD_HOVER:   Byte           = 0x03
    const val CMD_VEL:     Byte           = 0x04
    // 云台 (Jetson → Android)
    const val CMD_GIMBAL_YAW_FOLLOW: Byte = 0x11
    const val CMD_GIMBAL_ANGLE:      Byte = 0x12
    // 相机 (Jetson → Android)
    const val CMD_CAM_MODE:      Byte     = 0x21
    const val CMD_CAM_SHOOT:     Byte     = 0x22
    const val CMD_CAM_RECORD:    Byte     = 0x23
    const val CMD_CAM_VIDEO_CFG: Byte     = 0x24
    const val CMD_CAM_ZOOM:      Byte     = 0x25
    // 配件 (Jetson → Android)
    const val CMD_AUX_LIGHT: Byte         = 0x31
    // 航点 (Android → Jetson，无载荷)
    const val CMD_RECORD_WAYPOINT: Byte  = 0x41
    const val CMD_SAVE_WAYPOINTS:  Byte  = 0x42
    const val CMD_CLEAR_WAYPOINTS: Byte  = 0x43
    // 应答 (Android → Jetson)
    const val CMD_ACK:     Byte           = 0x80.toByte()

    // ── 完成通知 (Android → Jetson，无载荷) ─────────────────
    //   CMD_ACK (0x80)             表示「指令被接受」，协议层快反馈。
    //   CMD_ACK_*_COMPLETE (0x8x)  表示「物理动作真正完成」，延后发出，
    //                              上层可据此决定能否发下一条动作指令。
    const val CMD_ACK_TAKEOFF_COMPLETE: Byte = 0x81.toByte()
    const val CMD_ACK_LAND_COMPLETE:    Byte = 0x82.toByte()
    const val CMD_ACK_HOVER_COMPLETE:   Byte = 0x83.toByte()

    // ── ACK 状态码 ────────────────────────────────────────
    const val ACK_OK:      Byte = 0x00
    const val ACK_FAIL:    Byte = 0x01
    const val ACK_UNKNOWN: Byte = 0xFF.toByte()

    // ── 云台角度模式 ─────────────────────────────────────
    const val GIMBAL_MODE_ABSOLUTE: Byte = 0x00
    const val GIMBAL_MODE_RELATIVE: Byte = 0x01

    // ── 相机枚举 ─────────────────────────────────────────
    // 相机工作模式
    const val CAM_MODE_PHOTO: Byte = 0x00
    const val CAM_MODE_VIDEO: Byte = 0x01

    // 录像动作
    const val CAM_RECORD_STOP:  Byte = 0x00
    const val CAM_RECORD_START: Byte = 0x01

    // 视频分辨率
    const val CAM_RES_1920X1080: Byte = 0x01
    const val CAM_RES_3840X2160: Byte = 0x02
    const val CAM_RES_2720X1530: Byte = 0x03

    // 视频帧率
    const val CAM_FPS_24: Byte = 0x01
    const val CAM_FPS_25: Byte = 0x02
    const val CAM_FPS_30: Byte = 0x03
    const val CAM_FPS_48: Byte = 0x04
    const val CAM_FPS_50: Byte = 0x05
    const val CAM_FPS_60: Byte = 0x06

    // 镜头
    const val CAM_LENS_WIDE:     Byte = 0x00
    const val CAM_LENS_ZOOM:     Byte = 0x01
    const val CAM_LENS_INFRARED: Byte = 0x02

    // ZOOM 动作
    const val CAM_ZOOM_SWITCH_ONLY:    Byte = 0x00
    const val CAM_ZOOM_SWITCH_AND_SET: Byte = 0x01
    // 补光灯模式
    const val AUX_LIGHT_OFF:  Byte = 0x00
    const val AUX_LIGHT_ON:   Byte = 0x01
    const val AUX_LIGHT_AUTO: Byte = 0x02
    // ── 载荷长度常量 ─────────────────────────────────────
    const val VEL_PAYLOAD_LEN               = 16
    const val GIMBAL_YAW_FOLLOW_PAYLOAD_LEN = 8
    const val GIMBAL_ANGLE_PAYLOAD_LEN      = 18
    const val CAM_MODE_PAYLOAD_LEN          = 1
    const val CAM_RECORD_PAYLOAD_LEN        = 1
    const val CAM_VIDEO_CFG_PAYLOAD_LEN     = 2
    const val CAM_ZOOM_PAYLOAD_LEN          = 8  // 1+1+1+1+4
    const val AUX_LIGHT_PAYLOAD_LEN         = 1
    // ─────────────────────────────────────────────────────
    //  解码
    // ─────────────────────────────────────────────────────

    fun decode(data: ByteArray): ParsedFrame {
        val invalid = ParsedFrame(valid = false)
        if (data.size < 4) return invalid
        if (data[0] != FRAME_HEADER) return invalid
        val payloadLen = data[2].toInt() and 0xFF
        if (data.size < 4 + payloadLen) return invalid
        var xor = 0
        for (i in 0 until 3 + payloadLen) xor = xor xor (data[i].toInt() and 0xFF)
        if (xor.toByte() != data[3 + payloadLen]) return invalid

        val payload = data.copyOfRange(3, 3 + payloadLen)
        return ParsedFrame(valid = true, cmd = data[1], payload = payload)
    }

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

    fun parseGimbalYawFollowPayload(payload: ByteArray): GimbalYawFollowPayload? {
        if (payload.size < GIMBAL_YAW_FOLLOW_PAYLOAD_LEN) return null
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        return GimbalYawFollowPayload(pitch = bb.float, roll = bb.float)
    }

    fun parseGimbalAnglePayload(payload: ByteArray): GimbalAnglePayload? {
        if (payload.size < GIMBAL_ANGLE_PAYLOAD_LEN) return null
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val mode = bb.get()
        bb.get()  // reserved
        return GimbalAnglePayload(
            mode     = mode,
            pitch    = bb.float,
            roll     = bb.float,
            yaw      = bb.float,
            duration = bb.float
        )
    }

    fun parseCamModePayload(payload: ByteArray): CamModePayload? {
        if (payload.size < CAM_MODE_PAYLOAD_LEN) return null
        return CamModePayload(mode = payload[0])
    }

    fun parseCamRecordPayload(payload: ByteArray): CamRecordPayload? {
        if (payload.size < CAM_RECORD_PAYLOAD_LEN) return null
        return CamRecordPayload(action = payload[0])
    }

    fun parseCamVideoCfgPayload(payload: ByteArray): CamVideoCfgPayload? {
        if (payload.size < CAM_VIDEO_CFG_PAYLOAD_LEN) return null
        return CamVideoCfgPayload(resolution = payload[0], frameRate = payload[1])
    }

    fun parseCamZoomPayload(payload: ByteArray): CamZoomPayload? {
        if (payload.size < CAM_ZOOM_PAYLOAD_LEN) return null
        val bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        val lens   = bb.get()
        val action = bb.get()
        bb.get(); bb.get()  // reserved0, reserved1
        val ratio  = bb.float
        return CamZoomPayload(lens = lens, action = action, ratio = ratio)
    }
    fun parseAuxLightPayload(payload: ByteArray): AuxLightPayload? {
        if (payload.size < AUX_LIGHT_PAYLOAD_LEN) return null
        return AuxLightPayload(mode = payload[0])
    }
    // ─────────────────────────────────────────────────────
    //  编码 (ACK 回包)
    // ─────────────────────────────────────────────────────

    fun encodeAck(ackedCmd: Byte, status: Byte): ByteArray {
        val payload = byteArrayOf(ackedCmd, status)
        return encodeFrame(CMD_ACK, payload)
    }

    /**
     * 编码无载荷帧 (len=0)。
     * 用于发送完成通知帧，如 CMD_ACK_TAKEOFF_COMPLETE / _LAND_COMPLETE / _HOVER_COMPLETE。
     */
    fun encodeSimple(cmd: Byte): ByteArray = encodeFrame(cmd, ByteArray(0))

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
     * 速度 (DJI 机体系 BODY + VELOCITY)
     *   vx      : 前向 m/s  (正 = 前)
     *   vy      : 右向 m/s  (正 = 右)
     *   vz      : 上向 m/s  (正 = 上)
     *   yawRate : 右转 deg/s (正 = 顺时针俯视)
     */
    data class VelPayload(
        val vx: Float, val vy: Float, val vz: Float, val yawRate: Float
    )

    data class GimbalYawFollowPayload(val pitch: Float, val roll: Float)

    data class GimbalAnglePayload(
        val mode:     Byte,
        val pitch:    Float,
        val roll:     Float,
        val yaw:      Float,
        val duration: Float
    ) {
        val isRelative: Boolean get() = mode == GIMBAL_MODE_RELATIVE
    }

    /** 相机工作模式 */
    data class CamModePayload(val mode: Byte) {
        val isPhoto: Boolean get() = mode == CAM_MODE_PHOTO
        val isVideo: Boolean get() = mode == CAM_MODE_VIDEO
    }

    /** 录像控制 */
    data class CamRecordPayload(val action: Byte) {
        val isStart: Boolean get() = action == CAM_RECORD_START
        val isStop:  Boolean get() = action == CAM_RECORD_STOP
    }

    /** 录像配置 (分辨率+帧率) */
    data class CamVideoCfgPayload(val resolution: Byte, val frameRate: Byte)

    /**
     * 变焦 / 镜头切换
     *   lens   : CAM_LENS_WIDE / ZOOM / INFRARED
     *   action : CAM_ZOOM_SWITCH_ONLY / SWITCH_AND_SET
     *   ratio  : 当 action=SWITCH_AND_SET 且 lens=ZOOM 时生效
     */
    data class CamZoomPayload(
        val lens:   Byte,
        val action: Byte,
        val ratio:  Float
    ) {
        val shouldSetRatio: Boolean get() = action == CAM_ZOOM_SWITCH_AND_SET
    }
    /** 下视补光灯 */
    data class AuxLightPayload(val mode: Byte) {
        val isOff:  Boolean get() = mode == AUX_LIGHT_OFF
        val isOn:   Boolean get() = mode == AUX_LIGHT_ON
        val isAuto: Boolean get() = mode == AUX_LIGHT_AUTO
    }

}
