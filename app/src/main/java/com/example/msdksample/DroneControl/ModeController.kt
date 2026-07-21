package com.example.msdksample

import android.util.Log

/**
 * ModeController — 模式管理器
 *
 * 管理建图/采点/巡航三种模式的指令发送和状态跟踪。
 * 所有指令通过 PSDK 低速通道发送到 Jetson ROS 端。
 */
class ModeController {

    // 建图模式状态
    enum class MappingState {
        IDLE,       // 未开始
        RUNNING,    // 建图中
        SAVED       // 已保存
    }

    // 采点模式状态
    enum class CollectState {
        IDLE,          // 未开始
        RUNNING,       // 采点中（雷达+定位+记录器已启动）
        MAP_2D_DONE,   // 2D地图已生成
        PIXEL_DONE,    // 像素坐标已生成
    }

    // 巡航模式状态
    enum class CruiseState {
        IDLE,       // 未开始
        MAP_SET,    // 地图已应用
        WP_SET,     // 航线已应用
        READY,      // 地图+航线都配好，可以起飞
    }

    // 设置模式状态
    enum class SettingsState {
        IDLE,       // 未开始
        UPDATED,    // 已更新
    }

    companion object {
        private const val TAG = "ModeController"
    }

    // ── 状态跟踪 ────────────────────────────────────────────
    @Volatile
    var mappingState: MappingState = MappingState.IDLE
        private set

    @Volatile
    var collectState: CollectState = CollectState.IDLE
        private set

    @Volatile
    var cruiseState: CruiseState = CruiseState.IDLE
        private set

    @Volatile
    var settingsState: SettingsState = SettingsState.IDLE
        private set

    // 文件列表缓存
    var mapFileList: List<String> = emptyList()
        private set
    var waypointFileList: List<String> = emptyList()
        private set

    // ── 回调 ────────────────────────────────────────────────
    var onMappingStateChanged: ((MappingState) -> Unit)? = null
    var onCollectStateChanged: ((CollectState) -> Unit)? = null
    var onCruiseStateChanged: ((CruiseState) -> Unit)? = null
    var onSettingsStateChanged: ((SettingsState) -> Unit)? = null
    var onLogMessage: ((String) -> Unit)? = null
    var onFileListUpdated: (() -> Unit)? = null
    var onSettingsResponse: ((Map<String, String>) -> Unit)? = null  // 设置配置回读

    // ============================================================
    //  建图模式指令
    // ============================================================

    fun mappingSetName(mapName: String) {
        val payload = mapName.toByteArray(Charsets.UTF_8)
        val frame = DroneCommProtocol.encodePayload(DroneCommProtocol.CMD_MAPPING_SET_NAME, payload)
        DroneControlService.sendFrame(frame)
        Log.i(TAG, "mappingSetName: $mapName")
        onLogMessage?.invoke("设置地图名称: $mapName")
    }

    fun mappingStart() {
        val frame = DroneCommProtocol.encodeSimple(DroneCommProtocol.CMD_MAPPING_START)
        DroneControlService.sendFrame(frame)
        Log.i(TAG, "mappingStart")
        onLogMessage?.invoke("开始建图...")
    }

    fun mappingSaveMap(mapName: String) {
        val payload = mapName.toByteArray(Charsets.UTF_8)
        val frame = DroneCommProtocol.encodePayload(DroneCommProtocol.CMD_MAPPING_SAVE_MAP, payload)
        DroneControlService.sendFrame(frame)
        Log.i(TAG, "mappingSaveMap: $mapName")
        onLogMessage?.invoke("保存地图: $mapName")
    }

    fun mappingStop() {
        val frame = DroneCommProtocol.encodeSimple(DroneCommProtocol.CMD_MAPPING_STOP)
        DroneControlService.sendFrame(frame)
        Log.i(TAG, "mappingStop")
        onLogMessage?.invoke("结束建图...")
    }

    fun listMaps() {
        val frame = DroneCommProtocol.encodeSimple(DroneCommProtocol.CMD_LIST_MAPS)
        DroneControlService.sendFrame(frame)
        Log.i(TAG, "listMaps")
    }

    fun listWaypoints() {
        val frame = DroneCommProtocol.encodeSimple(DroneCommProtocol.CMD_LIST_WAYPOINTS)
        DroneControlService.sendFrame(frame)
        Log.i(TAG, "listWaypoints")
    }

    // ============================================================
    //  采点模式指令
    // ============================================================

    /**
     * 设置定位地图文件（指令 0x65）
     * payload = 地图 PCD 文件名，如 "A-B-A-B 833.pcd"
     */
    fun collectSetMap(mapFileName: String) {
        val payload = mapFileName.toByteArray(Charsets.UTF_8)
        val frame = DroneCommProtocol.encodePayload(DroneCommProtocol.CMD_COLLECT_SET_MAP, payload)
        DroneControlService.sendFrame(frame)
        Log.i(TAG, "collectSetMap: $mapFileName")
        onLogMessage?.invoke("设置定位地图: $mapFileName")
    }

    /**
     * 设置航点文件名（指令 0x66）
     * payload = 文件名，如 "waypoints.yaml"
     */
    fun collectSetWpName(filename: String) {
        val payload = filename.toByteArray(Charsets.UTF_8)
        val frame = DroneCommProtocol.encodePayload(DroneCommProtocol.CMD_COLLECT_SET_WP_NAME, payload)
        DroneControlService.sendFrame(frame)
        Log.i(TAG, "collectSetWpName: $filename")
        onLogMessage?.invoke("设置航点文件名: $filename")
    }

    /**
     * 开始采点（指令 0x67）
     * 启动雷达 + 定位 + 航线记录器(direct)
     */
    fun collectStart() {
        val frame = DroneCommProtocol.encodeSimple(DroneCommProtocol.CMD_COLLECT_START)
        DroneControlService.sendFrame(frame)
        Log.i(TAG, "collectStart")
        onLogMessage?.invoke("开始采点...")
    }

    /**
     * 生成2D地图（指令 0x68）
     * 运行 pcd_to_2d.py
     */
    fun collectGen2D() {
        val frame = DroneCommProtocol.encodeSimple(DroneCommProtocol.CMD_COLLECT_GEN_2D)
        DroneControlService.sendFrame(frame)
        Log.i(TAG, "collectGen2D")
        onLogMessage?.invoke("生成2D地图...")
    }

    /**
     * 生成像素坐标（指令 0x69）
     * 运行 odometry_to_pixel_offline.py
     */
    fun collectGenPixel() {
        val frame = DroneCommProtocol.encodeSimple(DroneCommProtocol.CMD_COLLECT_GEN_PIXEL)
        DroneControlService.sendFrame(frame)
        Log.i(TAG, "collectGenPixel")
        onLogMessage?.invoke("生成像素坐标...")
    }

    /**
     * 停止采点（指令 0x6A）
     * 停止雷达 + 定位 + 记录器
     */
    fun collectStop() {
        val frame = DroneCommProtocol.encodeSimple(DroneCommProtocol.CMD_COLLECT_STOP)
        DroneControlService.sendFrame(frame)
        Log.i(TAG, "collectStop")
        onLogMessage?.invoke("结束采点...")
    }

    // ============================================================
    //  巡航模式指令
    // ============================================================

    /**
     * 设置定位地图（指令 0x6B）
     * payload = 地图 PCD 文件名
     */
    fun cruiseSetMap(mapFileName: String) {
        val payload = mapFileName.toByteArray(Charsets.UTF_8)
        val frame = DroneCommProtocol.encodePayload(DroneCommProtocol.CMD_CRUISE_SET_MAP, payload)
        DroneControlService.sendFrame(frame)
        Log.i(TAG, "cruiseSetMap: $mapFileName")
        cruiseState = CruiseState.MAP_SET
        onCruiseStateChanged?.invoke(cruiseState)
        onLogMessage?.invoke("设置定位地图: $mapFileName")
    }

    /**
     * 设置航线文件（指令 0x6C）
     * payload = 航线文件名，如 "waypoints823.yaml"
     */
    fun cruiseSetWp(wpFileName: String) {
        val payload = wpFileName.toByteArray(Charsets.UTF_8)
        val frame = DroneCommProtocol.encodePayload(DroneCommProtocol.CMD_CRUISE_SET_WP, payload)
        DroneControlService.sendFrame(frame)
        Log.i(TAG, "cruiseSetWp: $wpFileName")
        cruiseState = CruiseState.WP_SET
        onCruiseStateChanged?.invoke(cruiseState)
        onLogMessage?.invoke("设置航线文件: $wpFileName")
    }

    /**
     * 开始巡航（指令 0x6D）
     * 触发 ROS 端 HTTP 发送起飞指令到状态机
     */
    fun cruiseStart() {
        val frame = DroneCommProtocol.encodeSimple(DroneCommProtocol.CMD_CRUISE_START)
        DroneControlService.sendFrame(frame)
        Log.i(TAG, "cruiseStart")
        onLogMessage?.invoke("开始巡航...")
    }

    /**
     * 选择航线（指令 0x6F）
     * payload = 航线文件名，如 "waypoints823.yaml"
     */
    fun cruiseSelectWp(wpFileName: String) {
        val payload = wpFileName.toByteArray(Charsets.UTF_8)
        val frame = DroneCommProtocol.encodePayload(DroneCommProtocol.CMD_CRUISE_SELECT_WP, payload)
        DroneControlService.sendFrame(frame)
        Log.i(TAG, "cruiseSelectWp: $wpFileName")
        onLogMessage?.invoke("选择航线: $wpFileName")
    }

    /**
     * 设置巡航服务器地址（指令 0x70）
     * payload = "ip:port"
     */
    fun cruiseSetServer(addr: String) {
        val payload = addr.toByteArray(Charsets.UTF_8)
        val frame = DroneCommProtocol.encodePayload(DroneCommProtocol.CMD_CRUISE_SET_SERVER, payload)
        DroneControlService.sendFrame(frame)
        Log.i(TAG, "cruiseSetServer: $addr")
        onLogMessage?.invoke("设置服务器: $addr")
    }

    /**
     * 设置云台俯仰角（指令 0x71）
     * payload = pitch 角度值字符串，如 "-45.0"
     */
    fun cruiseSetGimbalPitch(pitch: String) {
        val payload = pitch.toByteArray(Charsets.UTF_8)
        val frame = DroneCommProtocol.encodePayload(DroneCommProtocol.CMD_CRUISE_SET_GIMBAL_PITCH, payload)
        DroneControlService.sendFrame(frame)
        Log.i(TAG, "cruiseSetGimbalPitch: $pitch")
        onLogMessage?.invoke("设置云台俯仰角: ${pitch}°")
    }

    // ============================================================
    //  设置模式指令
    // ============================================================

    /**
     * 修改 HTTP 配置参数（指令 0x72）
     * payload = JSON: {"key":"server_ip","value":"10.29.3.171"}
     * 支持的 key: server_ip, server_port, remote_controller_ip,
     *             remote_controller_port, ftp_server_ip, ftp_server_port, local_port
     */
    fun settingsUpdate(key: String, value: String) {
        val json = "{\"key\":\"$key\",\"value\":\"$value\"}"
        val payload = json.toByteArray(Charsets.UTF_8)
        val frame = DroneCommProtocol.encodePayload(DroneCommProtocol.CMD_SETTINGS_UPDATE, payload)
        DroneControlService.sendFrame(frame)
        Log.i(TAG, "settingsUpdate: $key = $value")
        onLogMessage?.invoke("设置 $key = $value")
    }

    /**
     * 获取 HTTP 配置参数（指令 0x73）
     * 结果通过 onSettingsResponse 回调返回
     */
    fun settingsGet() {
        val frame = DroneCommProtocol.encodeSimple(DroneCommProtocol.CMD_SETTINGS_GET)
        DroneControlService.sendFrame(frame)
        Log.i(TAG, "settingsGet")
        onLogMessage?.invoke("正在获取配置...")
    }

    /**
     * 重启 HTTP 服务（指令 0x74）
     */
    fun settingsRestartHttp() {
        val frame = DroneCommProtocol.encodeSimple(DroneCommProtocol.CMD_SETTINGS_RESTART_HTTP)
        DroneControlService.sendFrame(frame)
        Log.i(TAG, "settingsRestartHttp")
        onLogMessage?.invoke("正在重启 HTTP 服务...")
    }

    fun onSettingsResponsePayload(payload: ByteArray) {
        val jsonStr = String(payload, Charsets.UTF_8)
        Log.i(TAG, "onSettingsResponsePayload: $jsonStr")
        try {
            // 简单 JSON 解析（不用引入完整 JSON 库）
            val config = mutableMapOf<String, String>()
            val cleaned = jsonStr.trim().removeSurrounding("{", "}")
            cleaned.split(",").forEach { pair ->
                val parts = pair.split(":", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim().removeSurrounding("\"")
                    val value = parts[1].trim().removeSurrounding("\"")
                    config[key] = value
                }
            }
            onSettingsResponse?.invoke(config)
        } catch (e: Exception) {
            Log.w(TAG, "onSettingsResponsePayload parse error: ${e.message}")
        }
    }

    // ============================================================
    //  ACK 处理 — 由 DroneControlService 调用
    // ============================================================

    fun onCommandAck(cmd: Byte, success: Boolean) {
        Log.i(TAG, "onCommandAck: cmd=0x${cmd.toUByte().toString(16)} success=$success")
        when (cmd) {
            DroneCommProtocol.CMD_MAPPING_START -> {
                if (success) {
                    mappingState = MappingState.RUNNING
                    onMappingStateChanged?.invoke(mappingState)
                    onLogMessage?.invoke("✅ 建图已启动")
                } else {
                    onLogMessage?.invoke("❌ 启动建图失败")
                }
            }
            DroneCommProtocol.CMD_MAPPING_SAVE_MAP -> {
                if (success) {
                    mappingState = MappingState.SAVED
                    onMappingStateChanged?.invoke(mappingState)
                    onLogMessage?.invoke("✅ 地图已保存")
                } else {
                    onLogMessage?.invoke("❌ 保存地图失败")
                }
            }
            DroneCommProtocol.CMD_MAPPING_STOP -> {
                if (success) {
                    mappingState = MappingState.IDLE
                    onMappingStateChanged?.invoke(mappingState)
                    onLogMessage?.invoke("✅ 建图已停止")
                } else {
                    onLogMessage?.invoke("⚠️ 停止建图时出现问题")
                }
            }
            // ── 采点模式 ACK ─────────────────────────────────
            DroneCommProtocol.CMD_COLLECT_START -> {
                if (success) {
                    collectState = CollectState.RUNNING
                    onCollectStateChanged?.invoke(collectState)
                    onLogMessage?.invoke("✅ 采点已启动")
                } else {
                    onLogMessage?.invoke("❌ 启动采点失败")
                }
            }
            DroneCommProtocol.CMD_COLLECT_GEN_2D -> {
                if (success) {
                    collectState = CollectState.MAP_2D_DONE
                    onCollectStateChanged?.invoke(collectState)
                    onLogMessage?.invoke("✅ 2D地图已生成")
                } else {
                    onLogMessage?.invoke("❌ 生成2D地图失败")
                }
            }
            DroneCommProtocol.CMD_COLLECT_GEN_PIXEL -> {
                if (success) {
                    collectState = CollectState.PIXEL_DONE
                    onCollectStateChanged?.invoke(collectState)
                    onLogMessage?.invoke("✅ 像素坐标已生成")
                } else {
                    onLogMessage?.invoke("❌ 生成像素坐标失败")
                }
            }
            DroneCommProtocol.CMD_COLLECT_STOP -> {
                if (success) {
                    collectState = CollectState.IDLE
                    onCollectStateChanged?.invoke(collectState)
                    onLogMessage?.invoke("✅ 采点已停止")
                } else {
                    onLogMessage?.invoke("⚠️ 停止采点时出现问题")
                }
            }
            // ── 巡航模式 ACK ─────────────────────────────────
            DroneCommProtocol.CMD_CRUISE_START -> {
                if (success) {
                    cruiseState = CruiseState.READY
                    onCruiseStateChanged?.invoke(cruiseState)
                    onLogMessage?.invoke("✅ 巡航已启动（起飞指令已发送）")
                } else {
                    onLogMessage?.invoke("❌ 启动巡航失败")
                }
            }
            // ── 设置模式 ACK ─────────────────────────────────
            DroneCommProtocol.CMD_SETTINGS_UPDATE -> {
                if (success) {
                    settingsState = SettingsState.UPDATED
                    onSettingsStateChanged?.invoke(settingsState)
                    onLogMessage?.invoke("✅ 配置已更新")
                } else {
                    onLogMessage?.invoke("❌ 配置更新失败")
                }
            }
        }
    }

    fun onFileListResponse(fileList: String, isMap: Boolean) {
        Log.i(TAG, "onFileListResponse: $fileList isMap=$isMap")
        val files = fileList.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (isMap) {
            mapFileList = files
        } else {
            waypointFileList = files
        }
        onFileListUpdated?.invoke()
    }
}