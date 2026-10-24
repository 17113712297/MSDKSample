# MSDK 直播服务部署文档

这份文档用于把当前直播链路部署到另一台 Windows 电脑上，并且让你可以按步骤完成首启、联通验证和常见问题排查。

当前方案的链路如下：

`遥控器 / Android App RTMP 推流 -> Windows 上的 SRS -> 前端页面通过 WHEP / WebRTC 拉流播放`

## 1. 要部署的内容

`obs_low_latency` 目录里已经包含了部署需要的核心内容：

- `docker-compose.yml`
- `start-srs.bat`
- `stop-srs.bat`
- `watch-srs.bat`
- `diagnose-srs.bat`
- `start-frontend.bat`
- `start-frontend.ps1`
- `frontend\`
- `srs\conf\obs-local.conf`

## 2. 新电脑的环境要求

目标电脑建议满足以下条件：

- Windows 10 22H2 或 Windows 11
- 已安装并可正常启动 `Docker Desktop` 
- Docker Desktop 已启用 `WSL2`
- 安装了最新版 `Chrome` 或 `Edge`
- PowerShell 5.1 及以上

WSL2安装:
以管理员身份打开 PowerShell，直接执行：wsl --install
执行后自动完成：
1. 启用虚拟机平台、WSL 功能
2. 下载并安装默认 Ubuntu 发行版

这个前端页面不依赖 Node.js，不需要额外安装前端运行环境。

## 3. 默认端口和作用

当前部署默认使用这些端口：

- `1935/tcp`：RTMP 推流入口
- `1985/tcp`：SRS HTTP API / WHEP
- `8080/tcp`：SRS HTTP 服务
- `8000/udp`：WebRTC 媒体端口
- `7001/tcp`：用于测试的前端静态页面访问端口

如果这些端口被占用，需要同步修改配置，文档后面有说明。

## 4. 在新电脑上准备目录

关键点只有两个：
obs_low_latency 目录内部结构要保持不变
也就是这些相对位置不要拆散：
docker-compose.yml、start-srs.bat、start-frontend.bat、frontend\、srs\conf\obs-local.conf

启动时要在 obs_low_latency 目录里执行脚本
因为现在这些脚本基本都是按“相对当前目录”工作的，所以只要整个 obs_low_latency 原样复制过去，就能跑。

## 5. 安装并检查 Docker Desktop

Docker Desktop 下载地址：

- [Docker Desktop](https://www.docker.com/products/docker-desktop/)
注意下载ARM64版本
安装完成后，先确认以下几点：

1. 能正常打开 Docker Desktop。
2. Docker Desktop 状态已经变成 Running。
3. PowerShell 里执行下面两个命令都能成功返回版本信息。

```powershell
docker version
docker compose version
```

如果这里失败，不要继续后面的部署，先把 Docker Desktop 运行起来。

## 6. 启动 SRS

直接双击：

- obs_low_latency\start-srs.bat`

启动成功后，执行：

```powershell
docker compose ps
```

你应该能看到容器：

- `msdk-srs`

并且状态应该是：

- `running`

停止 SRS双击：

- `obs_low_latency\stop-srs.bat`

## 7. 启动测试前端页面

前端页面的默认启动脚本是：

- `obs_low_latency\start-frontend.bat`

它实际会把页面服务启动在 `7001` 端口。



启动后，在部署这台电脑本机浏览器打开：

- [http://127.0.0.1:7001](http://127.0.0.1:7001)

说明：

- `frontend/config.js` 里的 `PORT` 目前还是显示值 `7000`
- 但 `start-frontend.bat` 实际启动端口是 `7001`
- 部署和访问时请以脚本实际启动端口 `7001` 为准

## 8. 查出新电脑的局域网 IP

在新电脑上执行：

```powershell
ipconfig
```

找到当前网卡的 IPv4 地址，例如：

- `192.168.1.20`

后面文档里用 `<WINDOWS_IP>` 表示这台新电脑的局域网 IP。

## 9. 修改 Android / 遥控器推流地址

把遥控器或 Android App 里的 RTMP 推流地址改成：

```text
rtmp://<WINDOWS_IP>:1935/live/obs1
```

例如新电脑 IP 是 `192.168.1.20`，那就填：

```text
rtmp://192.168.1.20:1935/live/obs1
```

如果这里没改，推流仍然会打到旧电脑，前端当然看不到新电脑上的流。

## 10. 部署场景

### 场景 ：只在部署这台电脑本机看直播

如果你只在这台新电脑本机浏览器里看流，当前默认配置基本可以直接用，不需要改下面两个文件：

- `obs_low_latency\srs\conf\obs-local.conf`
- `obs_low_latency\frontend\config.js`

也就是说，下面这两个默认值可以先保留：

- `rtc_server.candidate = 127.0.0.1`
- `WHEP_URL = http://127.0.0.1:1985/rtc/v1/whep/?app=live&stream=obs1`


## 11. 首次部署后的联通验证

建议按下面顺序检查。

### 第一步：确认 SRS 正常运行

```powershell
docker compose ps
```

预期结果：

- 能看到 `msdk-srs`
- 状态是 `running`

### 第二步：确认端口已经监听

```powershell
netstat -ano | findstr 1935
netstat -ano | findstr 1985
netstat -ano | findstr 8080
netstat -ano | findstr 7001
```

如果你要查 WebRTC UDP 端口：

```powershell
netstat -ano -p udp | findstr 8000
```

### 第三步：确认前端页面能打开

本机浏览器打开：

- [http://127.0.0.1:7001](http://127.0.0.1:7001)

如果是其他局域网设备访问，就打开：

- `http://<WINDOWS_IP>:7001`

页面打开但还没推流时，属于正常现象：

- 页面能打开
- 视频区显示等待或连接中

### 第四步：开始 RTMP 推流

在遥控器或 Android App 中：

1. 填好 RTMP 地址。
2. 点击开始 RTMP 推流。
3. 确认遥控器端显示正在推流。

### 第五步：观察 SRS 是否收到流

执行：

```powershell
docker logs --since 2m msdk-srs
```

预期应该能看到和 `live/obs1` 相关的连接或会话信息。

### 第六步：确认页面开始播放

如果一切正常，前端页面会从等待状态进入播放状态。

## 12. 防火墙要求

如果新电脑开着 Windows Defender 防火墙，至少要确保这些端口没有被拦住：

- `1935/tcp`
- `1985/tcp`
- `8080/tcp`
- `8000/udp`
- `7001/tcp`

最容易出问题的是两种情况：

- 遥控器推流到 `1935/tcp` 被拦截
- 其他设备访问 `1985/tcp` 或 `8000/udp` 时被拦截，导致页面能开但就是播不出来

## 13. 如果端口冲突，应该改哪里

如果目标电脑上默认端口被占用，需要同步修改下面几处。

### RTMP 端口冲突

比如把 `1935` 改成 `11935`，需要同时改：

1. `D:\MSDKSample\obs_low_latency\docker-compose.yml`
2. 遥控器 / Android App 里的 RTMP 推流地址

新的推流地址会变成：

```text
rtmp://<WINDOWS_IP>:11935/live/obs1
```

### WHEP / API 端口冲突

如果把 `1985` 改掉，需要同时改：

1. `obs_low_latency\docker-compose.yml`
2. `\obs_low_latency\srs\conf\obs-local.conf`
3. `obs_low_latency\frontend\config.js`

### Web 页面端口冲突

如果把页面从 `7001` 改成别的端口，需要改：

1. `obs_low_latency\start-frontend.bat`
2. 你访问页面时使用的地址
3. 如果你希望页面上显示的 `PORT` 也一致，再改 `frontend/config.js`



## 14. 最常见的故障和处理方法

### 1. 遥控器显示在推流，但 `docker compose ps` 正常，页面没画面

先检查：

- RTMP 地址是不是还指向旧电脑
- 新电脑和遥控器是不是在同一个局域网
- 新电脑防火墙有没有拦 `1935/tcp`

### 2. 页面能打开，但一直连接不上

先检查：

- `msdk-srs` 是否正常运行
- `WHEP_URL` 是否写对
- 如果是其他电脑访问页面，`candidate` 是否仍然是 `127.0.0.1`
- `8000/udp` 是否被防火墙拦截

### 3. 只有本机能看，其他电脑看不到

基本就是下面两项没有改：

- `obs-local.conf` 里的 `candidate`
- `frontend/config.js` 里的 `WHEP_URL`

它们不能继续写 `127.0.0.1`，必须改成部署机的真实局域网 IP。

### 4. 推流后画面很卡，重启 SRS 才恢复

先执行：

```powershell
.\diagnose-srs.bat
```

再查看：

```powershell
docker logs --since 5m msdk-srs
```

如果需要持续观察，可以执行：

```powershell
.\watch-srs.bat
```

这样可以先留住现场，再决定是否重启 SRS。

## 16. 新电脑首次部署的最短执行清单

如果你只想照着做一遍，按这个顺序执行就可以：

1. 把 `D:\MSDKSample\obs_low_latency\` 整个复制到新电脑。
2. 安装并启动 Docker Desktop。
3. 在 PowerShell 执行 `docker version` 和 `docker compose version` 确认 Docker 正常。
4. 进入 `D:\MSDKSample\obs_low_latency` 执行 `docker compose up -d`。
5. 执行 `docker compose ps`，确认 `msdk-srs` 是 `running`。
6. 双击 `D:\MSDKSample\obs_low_latency\start-frontend.bat`。
7. 在新电脑执行 `ipconfig`，记下 IPv4。
8. 把遥控器的 RTMP 地址改成 `rtmp://<WINDOWS_IP>:1935/live/obs1`。
9. 本机浏览器打开 `http://127.0.0.1:7001`。
10. 开始推流并观察页面是否出画面。
11. 如果要让其他设备看流，再把 `candidate` 和 `WHEP_URL` 里的 `127.0.0.1` 改成新电脑 IP。

