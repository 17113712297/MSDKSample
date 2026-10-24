package com.example.msdksample

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * MissionController — 任务控制（暂停 / 继续 / 返航）
 *
 * 只做两件事：
 *  1. 把三个按钮的意图编码成 0x75 / 0x76 / 0x77 帧，经 PSDK 低速通道发给机载；
 *  2. 接收机载状态机上报 (0x93)，驱动按钮置灰与状态显示。
 *
 * ── 合法性由机载判定 ────────────────────────────────────────────
 * 真正决定"能不能返航"的是 Jetson 侧 indooruav_core 状态机：
 *   Cruise 收到返航会被直接拒绝，必须先暂停。
 * 这里的前置检查只为两件事：少发无意义的帧、让操作员立刻看到反馈。
 * 所以即使本地判断和机载不一致，也不会造成危险动作——最坏情况是被机载忽略。
 *
 * ── 状态未知时的策略 ───────────────────────────────────────────
 * 开机后到第一帧 0x93 到达之前，state == UNKNOWN。此时三个按钮全部放开，
 * 方便在没有状态上报的调试环境下也能把指令发出去；一旦收到状态就严格按状态置灰。
 */
class MissionController {

    companion object {
        private const val TAG = "MissionController"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    var state: Int = DroneCommProtocol.MissionState.UNKNOWN
        private set

    @Volatile
    var wpIndex: Int = 0
        private set

    @Volatile
    var wpTotal: Int = 0
        private set

    /** 状态变化回调：(state, wpIndex, wpTotal)，已切到主线程 */
    var onStateChanged: ((Int, Int, Int) -> Unit)? = null
    var onLogMessage: ((String) -> Unit)? = null

    // ── 按钮可用性 ───────────────────────────────────────────
    val canPause: Boolean
        get() = state == DroneCommProtocol.MissionState.UNKNOWN ||
                state == DroneCommProtocol.MissionState.CRUISE

    val canResume: Boolean
        get() = state == DroneCommProtocol.MissionState.UNKNOWN ||
                state == DroneCommProtocol.MissionState.PAUSED

    val canReturn: Boolean
        get() = state == DroneCommProtocol.MissionState.UNKNOWN ||
                state == DroneCommProtocol.MissionState.PAUSED

    val stateName: String
        get() = DroneCommProtocol.MissionState.name(state)

    // ── 指令 ─────────────────────────────────────────────────
    fun pause() {
        val s = state
        if (s == DroneCommProtocol.MissionState.PAUSED) {
            log("已在暂停状态，忽略")
            return
        }
        if (s != DroneCommProtocol.MissionState.UNKNOWN &&
            s != DroneCommProtocol.MissionState.CRUISE
        ) {
            log("暂停被拒绝：当前状态为「$stateName」，只有巡航中才能暂停")
            return
        }
        send(DroneCommProtocol.CMD_PAUSE, "暂停")
    }

    fun resume() {
        val s = state
        if (s == DroneCommProtocol.MissionState.CRUISE) {
            log("已在巡航状态，忽略")
            return
        }
        if (s != DroneCommProtocol.MissionState.UNKNOWN &&
            s != DroneCommProtocol.MissionState.PAUSED
        ) {
            log("继续被拒绝：当前状态为「$stateName」，只有暂停中才能继续")
            return
        }
        send(DroneCommProtocol.CMD_RESUME, "继续")
    }

    fun goReturn() {
        val s = state
        if (s != DroneCommProtocol.MissionState.UNKNOWN &&
            s != DroneCommProtocol.MissionState.PAUSED
        ) {
            // 这就是"不能在飞的时候返航"
            log("返航被拒绝：当前状态为「$stateName」，必须先暂停再返航")
            return
        }
        send(DroneCommProtocol.CMD_RETURN, "返航")
    }

    private fun send(cmd: Byte, name: String) {
        Log.i(TAG, "CMD: $name (0x${cmd.toUByte().toString(16).uppercase()})")
        DroneControlService.sendFrame(DroneCommProtocol.encodeSimple(cmd))
        log("已发送指令：$name")
    }

    // ── 机载状态上报 ─────────────────────────────────────────
    fun updateState(newState: Int, index: Int, total: Int) {
        val changed = state != newState
        state = newState
        wpIndex = index
        wpTotal = total
        if (changed) {
            Log.i(TAG, "state -> ${DroneCommProtocol.MissionState.name(newState)} ($index/$total)")
        }
        mainHandler.post { onStateChanged?.invoke(newState, index, total) }
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        mainHandler.post { onLogMessage?.invoke(msg) }
    }
}
