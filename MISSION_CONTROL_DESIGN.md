# M4T 任务控制方案：暂停 / 继续 / 返航

> 场景：Matrice 4T + 遥控器 Android App(MSDK V5) + Jetson 机载计算机(PSDK 3.x)  
> 飞控执行层在 Jetson（视觉追踪航点），操控意图在遥控器侧下发，双方经 PSDK 低速数据通道通信。

---

## v2 修订（已落地到 indooruav_core / indooruav_waypoint）

> 初版曾计划"RC 调 `KeyStartGoHome` 执行飞控级返航"。**该方案已废弃。**

实测确认机载侧航点是**室内局部坐标系**（Fast-LIO2 + 局部地图），飞控的自动返航依赖 GPS/视觉定位且会直线爬升穿越障碍，在室内不可用。因此返航改为：

> **反方向重放航点序列** —— 沿原路退回第一个航点，再由状态机切入原有的 `Land` 状态做视觉降落。

带来的三个好处：

1. 返航路径**必然可通行**（来时飞过，且是同一批航点）
2. 不需要 RC 介入飞控，`VirtualStick` 无需关闭重开，控制权不交接
3. 返航全程走 tracker 同一套限幅/到位判定，速度曲线与去程一致

对应地，**"返航"这条链路完全不碰 `KeyStartGoHome`**，RC 侧只是把三个意图转发成三条命令。

---

## 0. 结论先行

| 问题        | 答案                                                                                                  |     |     |         |                           |
| --------- | --------------------------------------------------------------------------------------------------- | --- | --- | ------- | ------------------------- |
| 需要新起服务端吗？ | **不需要。** 仓库里 `PayloadCenter` UP 通道 + `DroneCommProtocol` 0xAA 帧协议已跑通，直接扩 3 条命令即可                    |     |     |         |                           |
| 机载端需要什么？  | 一个 PSDK 主程序（常驻）+ 一个 ROS 命令仲裁节点（状态机），**不需要 HTTP server**                                             |     |     |         |                           |
| 协议用什么？    | 沿用现有二进制帧：\`0xAA                                                                                     | CMD | LEN | PAYLOAD | XOR`，新增 `0x75/0x76/0x77\` |
| 返航怎么实现？   | **机载反方向重放航点**，回到第一个航点后切 `Land`；不是飞控自动返航                                                             |     |     |         |                           |
| 最关键的设计点？  | **状态机是唯一仲裁者**：`Cruise →(暂停)→ Paused →(继续)→ Cruise` / `Paused →(返航)→ Return →(到位)→ Land`；飞行中直接返航会被拒绝 |     |     |         |                           |

---

## 1. 现状盘点（已核对源码）

### 1.1 链路已通

- **RC → 机载**：`DroneControlService.sendFrame()` → `PayloadCenter.getInstance().payloadManager[PayloadIndexType.UP].sendDataToPayload()`
- **机载 → RC**：`PayloadDataListener` → `DroneCommProtocol.decode()` → `dispatchFrame()`

这两条路已经在跑 `CMD_RECORD_WAYPOINT(0x41)`、`CMD_CRUISE_START(0x6D)`、`CMD_SETTINGS_GET(0x73)` 等，**通道本身不需要任何改造**。

### 1.2 指令段占用情况

| 段           | 用途          | 空闲          |
| ----------- | ----------- | ----------- |
| `0x01-0x04` | 飞控          | 满           |
| `0x11-0x25` | 云台/相机       | 满           |
| `0x31`      | 配件          | 有           |
| `0x41-0x43` | 航点          | 有           |
| `0x50-0x51` | 任务/自检       | 有           |
| `0x60-0x74` | 建图/采点/巡航/设置 | 满（0x75 起空闲） |
| `0x80-0x85` | ACK         | `0x86+` 空闲  |
| `0x90-0x92` | 响应          | `0x93+` 空闲  |

→ 新增命令取 **`0x75 / 0x76 / 0x77`**，机载回执取 **`0x86 / 0x87 / 0x88`**，状态上报取 **`0x93`**。语义清晰、不破坏兼容。

### 1.3 现有设计哲学（要沿用）

现有协议是**双段反馈**：

- `CMD_ACK(0x80)` + status：**指令被接受**（协议层快反馈，<50ms）
- `CMD_ACK_*_COMPLETE(0x81-0x85)`：**物理动作真正完成**（延后发出）

暂停/继续/返航必须沿用这套，不要发明新的应答机制。

---

## 2. 角色与职责划分

| 角色                    | 持有能力                                                    | 职责                              |
| --------------------- | ------------------------------------------------------- | ------------------------------- |
| **RC App (MSDK V5)**  | VirtualStick 控制权、`KeyStartGoHome`、`KeyStartAutoLanding` | 飞行权威。速度指令的实际执行者、RTH 的实际发起者、安全兜底 |
| **Jetson (PSDK 3.x)** | 感知、路径规划、航点索引                                            | 任务权威。决定"飞哪里"，不决定"停不停"           |
| **低速通道**              | 双向字节流                                                   | 唯一信令通道，带宽极小，只传控制字不传数据           |

### 2.1 为什么返航由机载 tracker 执行

返航的本质是"沿原路退回"，而原路只在机载侧存在（局部地图航点序列）。飞控的 RTH 在室内无法用，且会与 `VirtualStick` 抢控制权。

所以返航由 `indooruav_core` 状态机切换、`waypoint_tracker` 执行：

| 环节 | 谁做                 | 做什么                                        |
| -- | ------------------ | ------------------------------------------ |
| 意图 | RC                 | 发 `0x77`（等价于 `/mission/command: "return"`） |
| 仲裁 | `indooruav_core`   | 只在 `Paused` 时接受，切 `State::Return`          |
| 执行 | `waypoint_tracker` | `State::RETURNING`，索引逐个递减                  |
| 收尾 | `waypoint_tracker` | 到 index 0 发 `return_complete`              |
| 切换 | `indooruav_core`   | `Return → Land`，复用原有降落/回传流程                |

**副产品**：因为不碰飞控，返航中途随时可以原地悬停等待，也不需要处理"RTH 与虚拟摇杆打架"。

### 2.2 为什么"继续"必须依赖机载确认

暂停 = 从"飞行"退到"悬停"，出错最坏也就是悬停，安全。  
继续 = 把速度控制权重新交给 Jetson，如果 Jetson 其实已经挂了/状态错乱，飞机就会带着错误的指令飞出去。

→ **不对称设计：暂停和返航可以单方面执行，继续必须握手。**

---

## 3. 协议扩展

### 3.1 帧格式（不变）

```
+------+-----+-----+-----------+-----+
| 0xAA | CMD | LEN | PAYLOAD   | XOR |
+------+-----+-----+-----------+-----+
  1B     1B    1B     N B         1B
```

`LEN` = payload 字节数，最大 251。`XOR` = 前 `3+LEN` 字节逐字节异或。

### 3.2 新增指令表

| CMD    | 名称                     | 方向          | Payload     | 语义                |
| ------ | ---------------------- | ----------- | ----------- | ----------------- |
| `0x75` | `CMD_PAUSE`            | RC → Jetson | 1B `reason` | 暂停当前追踪任务，保持现有航点索引 |
| `0x76` | `CMD_RESUME`           | RC → Jetson | 无           | 从保存的航点索引继续        |
| `0x77` | `CMD_RTH`              | RC → Jetson | 1B `policy` | 任务中止，机载停止一切输出     |
| `0x86` | `CMD_ACK_PAUSED`       | Jetson → RC | 4B          | 已进入 PAUSED，附航点索引  |
| `0x87` | `CMD_ACK_RESUMED`      | Jetson → RC | 4B          | 已恢复 TRACKING      |
| `0x88` | `CMD_ACK_RTH_ACCEPTED` | Jetson → RC | 1B `state`  | 机载已丢弃任务，可安全接管     |
| `0x93` | `CMD_MISSION_STATE`    | Jetson → RC | 6B          | 状态心跳，1Hz + 变更时立即发 |

**`reason` / `policy` 枚举**

```
reason: 0x00 操作员暂停   0x01 目标丢失   0x02 低电量   0x03 安全区域
policy: 0x00 标准返航(飞控自动)   0x01 原地爬升后返航   0x02 就地降落
```

**`0x86 / 0x87` Payload（4B，小端）**

| 偏移  | 类型  | 字段              |
| --- | --- | --------------- |
| 0   | u8  | missionState    |
| 1   | u8  | errCode（0=OK）   |
| 2-3 | u16 | wpIndex 当前/恢复航点 |

**`0x93` Payload（6B）**

| 偏移  | 类型  | 字段                                      |
| --- | --- | --------------------------------------- |
| 0   | u8  | missionState                            |
| 1   | u8  | flags（bit0 视觉丢失 / bit1 低电量 / bit2 建图异常） |
| 2-3 | u16 | wpIndex                                 |
| 4-5 | u16 | wpTotal                                 |

**missionState 枚举（RC 与机载共用）**

```
0x00 IDLE        未开始
0x01 TRACKING    追踪中（Jetson 正常输出 CMD_VEL）
0x02 PAUSED      已暂停，索引保留
0x03 RTH_PENDING 收到返航请求，正在收尾
0x04 RETURNING   已交接给飞控 RTH
0x05 ERROR       故障，需人工介入
```

### 3.3 为什么不需要序号和重传

PSDK 数据通道是**有序可靠**的点对点字节流（类似管道），不做丢包。所以：

- 不需要 seq / 重传 / 超时重发
- 幂等性靠机载侧状态机保证：`PAUSED` 状态收到 `PAUSE` 直接回 `ACK_OK`，不改变状态

**但它不保证"单次回调 = 一整帧"** —— 这点见 §7.1，是必须修的已知缺陷。

### 3.4 带宽核算

低速通道可用带宽约 **1 KB/s** 量级（每方向），本项目单帧 <20B：

| 流量                     | 频率    | 带宽占用     |
| ---------------------- | ----- | -------- |
| `CMD_VEL` 上行           | 10 Hz | ~170 B/s |
| `CMD_MISSION_STATE` 下行 | 1 Hz  | ~7 B/s   |
| 按钮指令                   | 事件触发  | 可忽略      |

**余量充足。** 但注意：如果实测 `CMD_VEL` 在低速通道上偶发 >300ms 延迟（会被 `VEL_WATCHDOG_MS` 误判为断流而悬停），应把 `CMD_VEL` 迁到**高速通道**，控制信令留在低速通道。

---

## 4. 机载端要起什么

### 4.1 明`确：不需要 HTTP 服务端`

`RC 与 Jetson 之间`**`只有 E`-Port 这一根物理线**，走的就是 PSDK 数据通道。再起一个 HTTP server 是多余的，且会引入 IP 配置、防火墙、发现等一堆额外故障点。

（`obs_low_latency/` 那套 HTTP/SRS 是给视频回传和地面站用的，与飞行控制信令无关，不要混用。）

### 4.2 进程拓扑

```
┌───────────────────────────────────────────────────────────┐
│                     Jetson (Linux / ROS1)                 │
│                                                           │
│  ┌──────────────────────┐                                 │
│  │ psdk 桥接（已有/待接） │  低速通道 0xAA 帧 → 转成字符串命令 │
│  └──────────┬───────────┘                                 │
│             │ /mission/command  (std_msgs/String)         │
│             ▼                                             │
│  ┌──────────────────────┐    /indooruav_core/state_machine │
│  │ mission_control_node │───►/state_machine_event/*        │
│  │  （新增，入口+预检）   │    pause / resume / return      │
│  └──────────────────────┘    return_complete              │
│             │                                             │
│             ▼                                             │
│  ┌──────────────────────┐                                 │
│  │ indooruav_core       │  State: Await→…→Cruise            │
│  │ （状态机，唯一仲裁者） │        ↕ Paused / Return         │
│  └──────────┬───────────┘                                 │
│             │ action service                              │
│             ▼                                             │
│  ┌──────────────────────┐                                 │
│  │ waypoint_tracker     │  RUNNING / PAUSED / RETURNING     │
│  │ （航点追踪+反向重放）  │──► cmd_vel ──► controller         │
│  └──────────────────────┘                                 │
└───────────────────────────────────────────────────────────┘
```

### 4.3 核心：状态机是唯一仲裁者

初版设计想加一个独立的 `cmd_vel` 闸门（mux）。落地时发现没必要——**跟踪器自己就有状态机**，`RUNNING` 之外的状态一律只发零速，这本身就是最可靠的闸门，而且不会出现"闸门和跟踪器状态不一致"的问题。

所以最终只做两件事：

1. `indooruav_core` 增加 `Paused` / `Return` 两个状态和 4 个事件
2. `waypoint_tracker` 增加 `RETURNING` / `RETURN_FINISHED` 两个状态和 1 个 `return` 服务

```
Cruise ──PauseCommand──► Paused ──ResumeCommand──► Cruise
                           │
                           └──ReturnCommand──► Return ──ReturnComplete──► Land
```

**`Cruise` 收到 `ReturnCommand` 会被显式拒绝**（这就是"不能在飞的时候返航"）：

```cpp
case Event::ReturnCommand:
    // 飞行中不允许直接返航：必须"先暂停、再返航"，
    // 否则返航的起点航点索引不可控，容易在半途掉头穿墙。
    std::cout << "[Reject] ReturnCommand in Cruise: 必须先暂停再返航\n";
    break;
```

### 4.4 线程模型（必踩的坑）

PSDK 数据通道的回调跑在 **SDK 内部线程**上，**绝对不能在里面做阻塞操作**：

```
✗ 错误：receive_cb() → 同步调用 ROS service（阻塞 50~200ms）→ 数据通道缓冲区堆积 → 通道卡死
✓ 正确：receive_cb() → 入无锁队列 → 立即返回 → 独立工作线程取队列 → 调 service → 发 ACK
```

同样的约束在 RC 侧也成立：`PayloadDataListener` 里只能做解码 + 投递，`droneCtrl` 的调用要走 `mainHandler.post`（现有代码大部分已经这样处理了）。

### 4.5 为什么用 Service 而不是 Topic

|      | Topic | Service           |
| ---- | ----- | ----------------- |
| 语义   | 流式广播  | 一次请求-一次响应         |
| 失败反馈 | 无     | 有 success/message |
| 重复投递 | 会     | 不会                |

按钮是"一次性意图"，天然对应 Service。状态广播（`/indooruav_core/state_machine/state`、  
`/indooruav_controller/waypoint_tracker/status`）才用 Topic。

唯一例外是 `mission_control_node` 的入口用 Topic（`/mission/command`），因为桥接层只需要"投递一条字符串"，  
不需要等 ROS 层返回——状态变化统一从 `/indooruav_core/state_machine/state` 观察。

---

## 5. 三个动作的完整时序

### 5.1 暂停

```
操作员        RC App                     Jetson
  │ 点暂停      │                           │
  ├────────────>│ ── 0x75 / "pause" ──────► │ mission_control: 校验 state==Cruise
  │             │                           │ indooruav_core: Cruise → Paused
  │             │                           │   └─ action: waypoint_tracker/pause
  │             │                           │ tracker: RUNNING → PAUSED（保留 index）
  │             │                           │       每 tick 继续发零速
  │             │◄── state="Paused" ────────┤
  │ 显示"已暂停" │                           │
```

关键点：**暂停只是冻结，`current_index_` 不变**。跟踪器在 `PAUSED` 下继续以 30Hz 发零速指令，  
飞机定点悬停；不需要等任何看门狗超时。

RC 侧可选加一道本地闸门（收到 `CMD_VEL` 直接丢弃），作为"Jetson 卡死仍在发速度"的兜底。  
但注意：**返航过程中不能关这道闸**，因为返航是 Jetson 主动发速度走回去的。

### 5.2 继续

```
操作员        RC App                     Jetson
  │ 点继续      │                           │
  ├────────────>│ ── 0x76 / "resume" ─────► │ mission_control: 校验 state==Paused
  │             │                           │ indooruav_core: Paused → Cruise
  │             │                           │   └─ action: waypoint_tracker/start
  │             │                           │ tracker: PAUSED → RUNNING（从原 index 继续）
  │             │◄── state="Cruise" ────────┤
  │ 显示"追踪中" │                           │
```

`resume` 复用 tracker 的 `start` 服务——`start` 在 `PAUSED` 状态下不会重置索引，  
只重置相位和限幅器（从零速起步），这正是"继续执行未完成航点"需要的行为。

### 5.3 返航

```
操作员        RC App                     Jetson
  │ 点返航      │                           │
  ├────────────>│ 二次确认                   │
  │ 确认        │                           │
  ├────────────>│ ── 0x77 / "return" ─────► │ mission_control: 校验 state==Paused
  │             │                           │   （Cruise 下直接拒绝：必须先暂停）
  │             │                           │ indooruav_core: Paused → Return
  │             │                           │   └─ action: waypoint_tracker/return
  │             │                           │ tracker: PAUSED → RETURNING
  │             │                           │   index -= 1，开始反向走
  │             │◄── state="Return" ────────┤
  │ 显示"返航中" │                           │
  │             │◄── CMD_VEL（反向）────────┤ 每到达一个航点 index -= 1
  │             │                           │ 到达 index 0 → RETURN_FINISHED
  │             │                           │   └─ 调 return_complete 事件
  │             │                           │ state_machine_event/return_complete
  │             │                           │ indooruav_core: Return → Land
  │             │◄── state="Land" ──────────┤   └─ 停录像 → 切拍照 → vision_landing
  │ 显示"降落中" │                           │
```


**实现要点：**

1. **反向的第一步回到"上一个已离开的航点"**：`EnterReturningLocked()` 里 `--current_index_`。  
   因为 `current_index_` 表示"正在飞往的目标"，直接用它当返航起点会原地不动。
2. **返航时目标 yaw 翻转 180°**（`reverse_flip_yaw=true`）。航点记录的是去程朝向，  
   不翻转会长距离倒飞。翻转后机头始终朝行进方向。
3. **返航途中不发布 `stop_arrived`**。该话题会触发前端的航点拍照/回传逻辑，  
   反向经过同一个停留点时不应重复触发。
4. **到达 index 0 后不自己降落**，只发 `return_complete`，由状态机切 `Land` 走原有视觉降落流程。
5. **返航中不支持暂停**（状态机显式拒绝）。返航是终止流程，要中断就接管或直接降落。

**返航中的"继续"键必须置灰**，否则操作员会试图在返航途中恢复正向追踪，行为不可预测。

---

## 6. 安全与故障矩阵

| 故障               | 检测方式                  | 系统行为                                | 操作员可见                        |
| ---------------- | --------------------- | ----------------------------------- | ---------------------------- |
| 低速通道断开           | `0x93` 心跳超 2s         | 跟踪器继续按最后状态执行；RC 提示失联                | 红色「机载失联」                     |
| Jetson 进程崩溃      | 心跳停                   | 无新 `CMD_VEL`，RC 看门狗 300ms 后零速悬停     | 同上                           |
| 飞机与 RC 失联        | `KeyConnectionStatus` | 飞控 Failsafe 接管                      | 告警                           |
| 连续点两次暂停          | 状态机                   | 幂等（`Paused` 收到 `PauseCommand` 只打日志） | 无                            |
| 误触返航             | UI                    | 二次确认弹窗 + 必须先暂停                      | 确认框                          |
| 未暂停就点返航          | 状态机                   | 拒绝，`state` 保持 `Cruise`              | 提示"请先暂停"                     |
| 返航中又点返航          | 状态机                   | 幂等忽略                                | 无                            |
| 返航中点暂停           | 状态机                   | 拒绝                                  | 提示"返航中不支持暂停"                 |
| 状态机与 tracker 不同步 | tracker 自己也会拒绝        | 飞机保持悬停（安全侧）                         | `/mission/command_result` 报错 |

**状态优先级（高 → 低）**：`Land` > `Return` > `Paused` > `Cruise` > 其他

---

## 7. 必须一并修的已知缺陷

### 7.1 `decode()` 的粘包问题（必修）

```kotlin
fun decode(data: ByteArray): ParsedFrame {
    if (data[0] != FRAME_HEADER) return invalid
    val payloadLen = data[2].toInt() and 0xFF
    if (data.size < 4 + payloadLen) return invalid   // ← 只判下界，没判上界
    ...
    val payload = data.copyOfRange(3, 3 + payloadLen) // ← 剩余字节被静默丢弃
}
```

数据通道是字节流，可能一次回调带来 1.5 帧或 3 帧。当前实现里：

- `data.size > 4 + payloadLen` 时，XOR 校验**恰好能通过**（校验位就在 `data[3+payloadLen]`）
- 所以**不会报错，但第二帧及之后的数据被静默丢弃**

后果：快速连点两次暂停，第二次可能被吞。虽然有幂等状态机兜底不至于出事，但属于不可接受的静默失败。

**修复方案**：加一个接收环形缓冲，按 `0xAA` 同步头 + `LEN` 逐帧扫描，处理粘包与半包：

```kotlin
private val rxBuf = ByteArrayOutputStream()

private fun feed(bytes: ByteArray) {
    synchronized(rxBuf) {
        rxBuf.write(bytes)
        var buf = rxBuf.toByteArray()
        var off = 0
        while (true) {
            // 找同步头
            while (off < buf.size && buf[off] != FRAME_HEADER) off++
            if (buf.size - off < 4) break
            val len = buf[off + 2].toInt() and 0xFF
            if (buf.size - off < 4 + len) break          // 半包，等下次
            val frame = buf.copyOfRange(off, off + 4 + len)
            val parsed = DroneCommProtocol.decode(frame)
            if (parsed.valid) dispatchFrame(parsed)
            off += 4 + len
        }
        rxBuf.reset()
        if (off < buf.size) rxBuf.write(buf, off, buf.size - off)
    }
}
```

### 7.2 `CMD_VEL` 拦截逻辑要合并

现有代码里 `CMD_VEL` 已有 3 处拦截（视觉降落中、速度面板激活、`IS_LANDING`），再加"暂停闸门"会变成 4 个分散的 `if`。

建议抽成一个统一的判定函数，避免以后漏加：

```kotlin
private fun shouldRejectVel(): Boolean =
    landingController?.getTaskState() == TaskState.LANDING ||
    isVelocityPanelActive ||
    missionGateClosed            // ← 新增：暂停/返航闸门
```

### 7.3 建议补一个链路心跳

目前没有看到 RC 与 Jetson 之间的心跳机制。`0x93` 以 1Hz 周期上报即为心跳，无需额外命令。

RC 侧维护 `lastMissionStateRxMs`，超过 2000ms 未更新即判定机载失联。

---

## 8. 实施状态

| 阶段             | 内容                                                  | 状态    |
| -------------- | --------------------------------------------------- | ----- |
| **P1 机载状态机**   | `indooruav_core` 增 `Paused`/`Return` + 4 个事件        | ✅ 已完成 |
| **P2 机载返航**    | `waypoint_tracker` 增 `RETURNING` 反向重放 + `return` 服务 | ✅ 已完成 |
| **P3 机载入口**    | `mission_control_node` + `/mission/command` + 状态上报  | ✅ 已完成 |
| **P4 RC 按钮**   | 三个按键 → `0x75/0x76/0x77` → 桥接层转 `/mission/command`   | ✅ 已完成 |
| **P5 PSDK 桥接** | 低速通道 0xAA 帧 → ROS 命令（`indooruav_controller`）                      | ✅ 已完成 |
| **P6 故障注入**    | 拔线、Kill 进程、快速连点、返航中点暂停                              | ⬜ 待做  |

**机载侧已经可以脱离遥控器独立验证**：

```bash
rostopic pub -1 /mission/command std_msgs/String "data: 'pause'"
rostopic echo /indooruav_core/state_machine/state
rostopic echo /indooruav_controller/waypoint_tracker/status
rostopic echo /mission/command_result
```

---

## 9. 待确认事项

1. ~~**PSDK 桥接层在哪**~~：已确认在 **`indooruav_controller`**（`controller_hardware.cpp`），
   走 `DjiLowSpeedDataChannel_RegRecvDataCallback` 收帧、`DjiLowSpeedDataChannel_SendData(DJI_CHANNEL_ADDRESS_MASTER_RC_APP, …)` 发帧。
   本次已在该文件补上 `0x75/0x76/0x77` 分发与 `0x93` 上报。
2. ~~**`ModeCommand.srv` 缺失**~~：已确认在 `indooruav_msgs/srv/ModeCommand.srv`，字段为
   `string command / string payload --- bool success / string message`，
   与 `mode_manager.py`、`controller_hardware.cpp` 的用法一致，`CMakeLists.txt` 也已登记。**编译无阻断。**
3. **`reverse_flip_yaw` 取哪个**：默认 `true`（机头朝行进方向）。如果返航时希望镜头仍然朝原方向拍，  
   改成 `false`，但那样是倒飞，室内要谨慎。
4. **返航速度**：目前沿用去程的 `xy_speed_mps`。返航一般可以快一点，是否需要单独一组参数？
5. **M4T 的 PSDK 授权**：确认 E-Port 的 PSDK 权限已开通、App ID 已绑定 M4T 机型。这步不通，数据通道起不来。

---

## 附录 A：RC 侧改动（三行即可）

返航不再碰飞控，所以 RC 侧比初版简单得多——**不需要关 VirtualStick、不需要 `KeyStartGoHome`、不需要等 ACK 再动作**：

**已落地的文件：**

| 文件 | 改动 |
| --- | --- |
| `DroneControl/DroneCommProtocol.kt` | 新增 `CMD_PAUSE/RESUME/RETURN`、`CMD_MISSION_STATE`、`MissionState` 状态码表、`parseMissionStatePayload()` |
| `DroneControl/MissionController.kt` | **新增**，三个指令发送 + 状态跟踪 + `canPause/canResume/canReturn` |
| `DroneControl/DroneControlService.kt` | 新增 `missionController` 静态引用；`dispatchFrame` 处理 `0x93` |
| `res/layout/activity_main.xml` | `preflightControlLayout` 里加「暂停 / 继续 / 返航」三个按钮 |
| `MainActivity.kt` | 控件绑定、点击（返航带二次确认）、按状态置灰 |

```kotlin
// DroneCommProtocol.kt 追加
const val CMD_PAUSE:  Byte = 0x75
const val CMD_RESUME: Byte = 0x76
const val CMD_RETURN: Byte = 0x77

// 机载 → RC：状态上报（payload 里的 state 直接用状态机字符串映射）
const val CMD_MISSION_STATE: Byte = 0x93.toByte()
```

```kotlin
// DroneControlService.sendFrame 直接发，载荷可以为空
fun pause()  = sendFrame(DroneCommProtocol.encodeSimple(DroneCommProtocol.CMD_PAUSE))
fun resume() = sendFrame(DroneCommProtocol.encodeSimple(DroneCommProtocol.CMD_RESUME))
fun goReturn() = sendFrame(DroneCommProtocol.encodeSimple(DroneCommProtocol.CMD_RETURN))
```

UI 置灰规则（与机载状态机保持一致）：

| 机载 state | 暂停 | 继续 | 返航            |
| -------- | -- | -- | ------------- |
| `Cruise` | 可点 | 置灰 | **置灰**（必须先暂停） |
| `Paused` | 置灰 | 可点 | 可点（二次确认）      |
| `Return` | 置灰 | 置灰 | 置灰            |
| `Land`   | 置灰 | 置灰 | 置灰            |
| 其他       | 置灰 | 置灰 | 置灰            |

## 附录 B：机载端入口（已实现）

`mission_control_node.py` 订阅 `/mission/command`（`std_msgs/String`），接受 `pause` / `resume` / `return` / `status`，  
转发给 `indooruav_core` 的状态机事件服务，并把结果发到 `/mission/command_result`（JSON）。

PSDK 桥接层只需要做一件事：把低速通道收到的帧翻译成一条字符串 Topic。用 Topic 而不是 Service 的原因：  
桥接层不用等 ROS 返回，也不会因为 ROS 卡住而阻塞数据通道回调线程。

状态回报读这两个话题即可：

```bash
/indooruav_core/state_machine/state            # std_msgs/String: Await/Cruise/Paused/Return/Land/...
/indooruav_controller/waypoint_tracker/status  # std_msgs/String JSON: {"state":..,"index":..,"total":..}
```

## 附录 C：PSDK 桥接层（indooruav_controller，已实现）

桥接层就在 `indooruav_controller/src/indooruav_controller/controller_hardware.cpp`——
它本来就是 MSDK ↔ ROS 的唯一出入口（`0x01` 起飞、`0x04` 速度、`0x6D` 巡航都走它）。

### 收方向（RC → ROS）

`DjiLowSpeedDataChannel_RegRecvDataCallback` → `OnRecvFromMsdk` → `drone_comm::decode`
→ 按 `frame.cmd` 分发。本次新增一段：

```cpp
if (frame.cmd == CMD_PAUSE || frame.cmd == CMD_RESUME || frame.cmd == CMD_RETURN) {
    const bool ok = HandleMissionCommand(frame.cmd);   // 调状态机事件服务
    SendFrame(ack_frame, sizeof(ack_frame));           // 回 CMD_ACK
    return;
}
```

`HandleMissionCommand` 把三条指令映射到状态机事件：

| 帧 | 服务 | 效果 |
| --- | --- | --- |
| `0x75` | `indooruav_core/state_machine_event/pause` | `Cruise → Paused` |
| `0x76` | `indooruav_core/state_machine_event/resume` | `Paused → Cruise` |
| `0x77` | `indooruav_core/state_machine_event/return` | `Paused → Return` |

**注意**：服务是 `std_srvs/Empty`，`ACK_OK` 只代表"送达"，不代表"被状态机接受"。
巡航中按返航，状态机会打印 `[Reject]` 但服务仍返回 true。真实状态以 `0x93` 上报为准。

### 发方向（ROS → RC）

订阅两个话题，缓存成原子变量，1Hz 打包成 `0x93` 发回遥控器：

| 源话题 | 取什么 |
| --- | --- |
| `/indooruav_core/state_machine/state` | 状态字符串 → 状态码（按 `State` 枚举顺序 0~8）|
| `/indooruav_controller/waypoint_tracker/status` | `"index":N` / `"total":M` |

```
0x93 payload = [state][wp_index][wp_total][reserved]   共 4 字节
```

`state = 0xFF` 表示桥接层还没收到过状态机状态（刚启动 / 状态机没起），
遥控器会把它当作 `UNKNOWN` → 三个按键全放开，避免"待机全置灰"导致无法调试。

遥控器收到后驱动三个按钮的置灰：

```kotlin
CRUISE(3) → 可暂停
PAUSED(5) → 可继续、可返航
RETURN(6) / LAND(7) → 全置灰
UNKNOWN(未收到) → 全放开（便于无状态上报时调试）
```

### 新增参数（`indooruav_controller/config/config.yaml`）

```yaml
services:
  mission_pause:  "indooruav_core/state_machine_event/pause"
  mission_resume: "indooruav_core/state_machine_event/resume"
  mission_return: "indooruav_core/state_machine_event/return"
topics:
  mission_state:  "/indooruav_core/state_machine/state"
  tracker_status: "/indooruav_controller/waypoint_tracker/status"
parameters:
  mission_state_rate_hz: 1.0
```
