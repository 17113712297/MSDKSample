package com.example.msdksample

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * ModeController — 模式管理器
 *
 * 管理建图/采点/巡航三种模式的指令发送和状态跟踪。
 * 所有指令通过 PSDK 低速通道发送到 Jetson ROS 端。
 *
 * ── 文件列表改为分页拉取 ──────────────────────────────────────────
 * 机载一次只能回 240 B 文本（PSDK 低速通道单帧 payload 上限），
 * 文件一多就被截断。现在改成：
 *
 *   1. 发 0x64 / 0x6E，payload = [offset:u16]（offset=0 表示第一页）；
 *   2. 机载回 0x94 / 0x95，payload = [offset][next][name\nname...]；
 *   3. next != 0xFFFF 就继续按 next 请求下一页，直到收齐；
 *   4. 每页 1.5 s 超时，最多重试 2 次，超时后把已收到的部分交出去。
 *
 * 老固件会忽略请求里的 offset，仍回旧的 0x90/0x91 单帧格式，
 * 这时走 [onFileListResponse] 的兼容路径。
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

        /** 单页回包超时（毫秒）。1 KB/s 带宽下 240 B 约 0.25 s，留足余量 */
        private const val PAGE_TIMEOUT_MS = 1500L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val pager = FileListPager()
    private var pageIsMap: Boolean = true
    private var autoLoadUntilEnd: Boolean = false
    private val pageTimeoutRunnable = Runnable { onPageTimeout() }

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

    /** 分页状态回调（手动翻页 UI 用）：当前页条目、页码、是否还有上/下一页等 */
    var onFileListPageState: ((FileListPager.State) -> Unit)? = null
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
        startPaging(isMap = true)
    }

    fun listWaypoints() {
        startPaging(isMap = false)
    }

    // ============================================================
    //  文件列表分页（手动翻页）
    // ============================================================
    //
    // 每次只取一页：打开对话框取第 1 页；用户点"下一页"才取第 2 页；
    // 已经取过的页直接从缓存切（不再发请求）。"全部"按钮才连续取到末页。
    //
    // 协议没有 total 字段，所以"共 N 条 / 共 y 页"只有走到末页后才知道。

    /** 开始一轮新的拉取（第 1 页） */
    private fun startPaging(isMap: Boolean) {
        pageIsMap = isMap
        autoLoadUntilEnd = false
        mainHandler.removeCallbacks(pageTimeoutRunnable)
        val offset = pager.begin(isMap)
        Log.i(TAG, "请求文件列表第 1 页 (${if (isMap) "map" else "wp"}, offset=$offset)")
        sendPageRequest(offset, isMap)
        armPageTimeout()
        emitPageState()
    }

    /** 下一页（已缓存则直接切，否则发请求） */
    fun nextPage() = navigate { pager.next() }

    /** 上一页（只走缓存） */
    fun prevPage() = navigate { pager.prev() }

    /** 超时后重试当前页 */
    fun retryPage() = navigate { pager.retry() }

    /** 连续取到末页（"全部"按钮） */
    fun loadAllPages() {
        if (pager.isLoading) return
        autoLoadUntilEnd = true
        navigate { pager.next() }
    }

    private fun navigate(action: () -> FileListPager.Nav) {
        if (pager.isLoading) return
        when (action()) {
            FileListPager.Nav.FETCH -> {
                mainHandler.removeCallbacks(pageTimeoutRunnable)
                Log.i(TAG, "请求第 ${pager.state().pageIndex + 2} 页 offset=${pager.pendingOffset}")
                sendPageRequest(pager.pendingOffset, pageIsMap)
                armPageTimeout()
                emitPageState()
            }
            FileListPager.Nav.CACHED, FileListPager.Nav.NONE -> emitPageState()
        }
    }

    private fun sendPageRequest(offset: Int, isMap: Boolean) {
        val cmd = if (isMap) DroneCommProtocol.CMD_LIST_MAPS
                  else DroneCommProtocol.CMD_LIST_WAYPOINTS
        DroneControlService.sendFrame(DroneCommProtocol.encodeListRequest(cmd, offset))
    }

    private fun armPageTimeout() {
        mainHandler.removeCallbacks(pageTimeoutRunnable)
        mainHandler.postDelayed(pageTimeoutRunnable, PAGE_TIMEOUT_MS)
    }

    /**
     * 机载分页回包（0x94 / 0x95）→ 由 DroneControlService 调用（主线程）。
     */
    fun onFileListPage(offset: Int, nextOffset: Int, names: List<String>, isMap: Boolean) {
        if (isMap != pageIsMap) {
            Log.w(TAG, "忽略类型不符的分页回包 isMap=$isMap 期望=$pageIsMap")
            return
        }
        if (!pager.onPage(offset, nextOffset, names)) {
            Log.w(TAG, "忽略异常分页回包 offset=$offset next=$nextOffset " +
                    "pending=${pager.pendingOffset} loading=${pager.isLoading}")
            return
        }

        mainHandler.removeCallbacks(pageTimeoutRunnable)
        Log.i(TAG, "收到第 ${offset} 页 ${names.size} 条 next=$nextOffset")
        emitPageState()
        if (autoLoadUntilEnd) {
            if (pager.state().hasNext) navigate { pager.next() }
            else autoLoadUntilEnd = false
        }
    }

    private fun onPageTimeout() {
        if (!pager.onTimeout()) return
        autoLoadUntilEnd = false
        Log.w(TAG, "第 ${pager.state().pageIndex + 1} 页超时，等用户重试")
        emitPageState()
    }

    private fun emitPageState() {
        val state = pager.state()
        if (state.isMap) mapFileList = state.items else waypointFileList = state.items
        mainHandler.post {
            onFileListPageState?.invoke(state)
            onFileListUpdated?.invoke()
        }
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
        val raw = String(payload, Charsets.UTF_8)
        Log.i(TAG, "onSettingsResponsePayload: $raw")
        try {
            // 紧凑格式：a=val|b=val|c=val|d=val|e=val|f=val|g=val
            // a=server_ip, b=server_port, c=remote_controller_ip,
            // d=remote_controller_port, e=ftp_server_ip, f=ftp_server_port,
            // g=local_port
            val config = mutableMapOf<String, String>()
            val keyMap = mapOf(
                "a" to "server_ip",
                "b" to "server_port",
                "c" to "remote_controller_ip",
                "d" to "remote_controller_port",
                "e" to "ftp_server_ip",
                "f" to "ftp_server_port",
                "g" to "local_port",
            )
            raw.trim().split("|").forEach { pair ->
                val parts = pair.split("=", limit = 2)
                if (parts.size == 2) {
                    val fullKey = keyMap[parts[0]] ?: parts[0]
                    config[fullKey] = parts[1]
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

    /**
     * 旧格式文件列表（0x90 / 0x91）——老固件不认识分页请求时会走这里。
     *
     * 注意：这种单帧最多 240 B，文件多时**本身就是截断的**，
     * 只能显示前若干条（遥控器侧已在 Service 里丢掉半个文件名）。
     * 想要完整列表必须升级机载到支持 0x94/0x95 分页的版本。
     */
    fun onFileListResponse(fileList: String, isMap: Boolean) {
        mainHandler.removeCallbacks(pageTimeoutRunnable)
        autoLoadUntilEnd = false
        pageIsMap = isMap
        Log.w(TAG, "收到旧格式列表（不分页，可能被截断）: ${fileList.length}B isMap=$isMap")
        val files = fileList.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }
        pager.onLegacy(files.distinct())
        emitPageState()
    }
}
