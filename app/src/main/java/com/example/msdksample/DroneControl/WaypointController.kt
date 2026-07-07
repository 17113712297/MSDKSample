package com.example.msdksample

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * WaypointController
 *
 * 通过 PSDK 低速通道发送航点记录指令到 Jetson。
 * Jetson 收到后调用 waypoint_recorder_node 的 ROS 服务，并返回 ACK。
 *
 * CMD_RECORD_WAYPOINT (0x41) — 记录当前位姿为航点
 * CMD_SAVE_WAYPOINTS  (0x42) — 保存航点到文件
 * CMD_CLEAR_WAYPOINTS (0x43) — 清除内存中所有航点
 */
class WaypointController {

    companion object {
        private const val TAG = "WaypointController"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    var logCallback: ((String) -> Unit)? = null

    fun recordWaypoint() {
        log("CMD: record waypoint")
        DroneControlService.sendFrame(
            DroneCommProtocol.encodeSimple(DroneCommProtocol.CMD_RECORD_WAYPOINT)
        )
    }

    fun saveWaypoints() {
        log("CMD: save waypoints")
        DroneControlService.sendFrame(
            DroneCommProtocol.encodeSimple(DroneCommProtocol.CMD_SAVE_WAYPOINTS)
        )
    }

    fun clearWaypoints() {
        log("CMD: clear waypoints")
        DroneControlService.sendFrame(
            DroneCommProtocol.encodeSimple(DroneCommProtocol.CMD_CLEAR_WAYPOINTS)
        )
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        mainHandler.post { logCallback?.invoke(msg) }
    }
}
