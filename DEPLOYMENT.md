# MSDK 低延迟本地直播部署说明

本文档对应方案：`安卓设备 DJI 原生 RTMP -> 本机 SRS -> WHEP/WebRTC -> 前端页面`

部署目标固定为两台设备：

- 一台安卓设备：运行 `MSDK_merge`
- 一台 Windows 电脑：同时运行 `SRS + 前端页面`，也是观看端

## 运行在本机上的配置是否方便移植

方便移植。当前本机侧依赖很少，核心是把 `obs_low_latency` 整个目录带走，然后在新电脑上补齐运行环境即可。

本机侧实际依赖分为两部分：

- `Docker Desktop + WSL2`：用于启动 `SRS`
- `PowerShell + 浏览器`：用于启动前端静态页面并在本机播放

其中：

- `SRS` 本身没有额外安装到系统里，而是通过 Docker 镜像 `ossrs/srs:5` 启动
- 前端不依赖 Node.js，只依赖 [start-frontend.ps1](/D:/MSDK_merge/obs_low_latency/start-frontend.ps1) 启一个本地静态服务
- 安卓端继续通过 `RTMP` 推到 Windows 主机，不需要单独迁移 SRS 配置文件到系统目录

## 移植环境必备配置

如果要迁移到另一台 Windows 电脑，至少需要准备：

- `WSL2`
- `Docker Desktop`
- Chrome 或 Edge
- Windows 自带 PowerShell 5.1 或更高版本

Docker Desktop 官方下载地址：

- [https://www.docker.com/products/docker-desktop/](https://www.docker.com/products/docker-desktop/)

建议按下面顺序准备环境：

1. 安装并启动 `Docker Desktop`
2. 确认 Docker 使用 `WSL2` 后端
3. 确认 `docker version` 和 `docker compose version` 能正常返回
4. 把 `obs_low_latency` 目录和本说明文档复制到目标电脑
5. 在目标电脑上执行 `docker compose up -d`
6. 再启动本地前端页面

## 最小迁移清单

迁移到新电脑时，至少带走这些文件：

- `obs_low_latency\`
- `DEPLOYMENT.md`

推荐直接保留以下目录结构，这样脚本无需修改路径即可运行：

```text
<你的目录>\
├─ DEPLOYMENT.md
└─ obs_low_latency\
   ├─ docker-compose.yml
   ├─ start-srs.bat
   ├─ stop-srs.bat
   ├─ start-frontend.bat
   ├─ start-frontend.ps1
   ├─ frontend\
   └─ srs\conf\obs-local.conf
```

## 哪些配置是可移植的，哪些需要按机器调整

以下内容通常可以直接带走，不需要改：

- `docker-compose.yml`
- `start-srs.bat`
- `stop-srs.bat`
- `start-frontend.ps1`
- `frontend` 页面代码
- `srs\conf\obs-local.conf` 中当前本机播放场景的基础配置

以下内容迁移后需要按目标电脑环境检查：

- Windows 局域网 IP
- 安卓端填写的 `RTMP_URL`
- 前端监听端口，比如 `7000` 或 `7001`
- Windows 防火墙是否放行 `1935/tcp`、`1985/tcp`、`8080/tcp`、`8000/udp`
- Docker Desktop 是否能正常拉取 `ossrs/srs:5`

如果只是“在目标电脑本机浏览器里看直播”，通常只要改：

- 安卓端推流地址里的 `<WINDOWS_IP>`
- 你自己想使用的前端端口

如果要让“局域网其他设备”访问该电脑上的 WebRTC 流，则通常还要改：

- `srs\conf\obs-local.conf` 里的 `rtc_server.candidate`
- 前端里使用的 `WHEP_URL`


移植环境必备配置：
WSL2
docker:https://www.docker.com/products/docker-desktop/下载地址
SRS;作为转发视频，SRS 是通过 Docker 启动的，先启动容器
默认变量如下，后文会把它们当成可替换变量使用：

- `PORT=7001`
- `RTMP_URL=rtmp://<WINDOWS_IP>:1935/live/obs1`    接收遥控器的推流的端口
- `WHEP_URL=http://127.0.0.1:1985/rtc/v1/whep/?app=live&stream=obs1`   前端拉流的地址
- `APP_NAME=live`
- `STREAM_NAME=obs1`

## 最短启动路径

1. 让安卓设备和 Windows 电脑连接同一局域网。
2. 启动 Docker Desktop。
3. 双击 `D:\MSDK_merge\obs_low_latency\start-srs.bat`。   启动SRS转发
4. 双击 `D:\MSDK_merge\obs_low_latency\start-frontend.bat`。
5. 浏览器打开 [http://127.0.0.1:7000](http://127.0.0.1:7000)。
6. 在安卓设备 `MSDK_merge` 直播面板中填写 `RTMP_URL` 并开始推流。
7. 页面看到视频即表示链路打通。

## 目录结构

```text
D:\MSDK_merge\
├─ DEPLOYMENT.md
└─ obs_low_latency\
   ├─ docker-compose.yml
   ├─ .env.example
   ├─ start-srs.bat
   ├─ stop-srs.bat
   ├─ start-frontend.bat
   ├─ start-frontend.ps1
   ├─ frontend\
   │  ├─ index.html
   │  ├─ app.js
   │  ├─ config.js
   │  └─ styles.css
   └─ srs\
      └─ conf\
         └─ obs-local.conf
```

## 依赖要求

### Windows 版本

- 推荐 `Windows 10 22H2` 或 `Windows 11`
- 需要能正常运行 Docker Desktop
- 需要本机浏览器支持 WebRTC，推荐最新版 Chrome 或 Edge

### Docker Desktop 要求

- 安装最新版 Docker Desktop for Windows
- 首次安装后确保 Docker Desktop 已成功启动
- 推荐启用默认 WSL2 引擎
- 需要能拉取公开镜像 `ossrs/srs:5`

### 前端运行时要求

这版前端不依赖 Node.js。  
前端使用交付目录里的 `start-frontend.ps1` 启动本地静态服务，因此目标电脑只需要：

- Windows 自带 PowerShell 5.1 或更高版本
- 一个支持 WebRTC 的浏览器

### 安卓端要求

- 安卓设备上安装并运行 `MSDK_merge`
- 飞机正常连接
- 安卓设备与 Windows 电脑在同一局域网
- Android 侧直播编码需为 `H.264`

## 启动 SRS

在 `obs_low_latency` 目录执行：

```powershell
docker compose up -d
```

等价的双击脚本：

```text
start-srs.bat
```

成功后，本机会开放这些端口：

- `1935/tcp`：RTMP ingest
- `1985/tcp`：WHEP / HTTP API
- `8080/tcp`：SRS HTTP 服务
- `8000/udp`：WebRTC UDP

检查容器状态：

```powershell
docker compose ps
```

查看日志：

```powershell
docker compose logs -f
```

停止 SRS：

```powershell
docker compose down
```

或双击：

```text
stop-srs.bat
```

## 启动前端

在 `obs_low_latency` 目录执行：

```powershell
powershell -ExecutionPolicy Bypass -File .\start-frontend.ps1 -Port 7000
```

或直接双击：

```text
start-frontend.bat
```

启动成功后，浏览器访问：

- [http://127.0.0.1:7000](http://127.0.0.1:7000)

## 前端配置项

默认前端配置写在：

- [config.js](/D:/MSDK_merge/obs_low_latency/frontend/config.js)

默认值：

```js
window.APP_CONFIG = {
  PORT: "7000",
  WHEP_URL: "http://127.0.0.1:1985/rtc/v1/whep/?app=live&stream=obs1",
  AUTO_PLAY: true,
  SHOW_RECONNECT: true
};
```

## 安卓端配置步骤

### Windows 电脑 IP

先在 Windows 电脑上执行：

```powershell
ipconfig
```

找到当前局域网 IPv4，例如：

- `192.168.1.20`

### 安卓端 RTMP 地址

在安卓设备 `MSDK_merge` 的直播面板中填写：

```text
rtmp://192.168.1.20:1935/live/obs1
```

其中：

- `192.168.1.20` 需要替换成 Windows 电脑的局域网 IP
- `1935` 是 SRS 的 RTMP 端口
- `live/obs1` 是固定流名

### 安卓端操作

1. 打开 `MSDK_merge`
2. 找到直播面板
3. 在 `RTMP 地址` 输入框填写 Windows 电脑的 RTMP 地址
4. 点击 `保存推流地址`
5. 点击 `开始 RTMP 推流`

如果直播面板提示当前编码不是 `H.264`，先在无人机相机相关设置中切换到 `H.264`

## 如何验证链路打通

### 1. 验证 Docker / SRS 是否正常

执行：

```powershell
docker compose ps
netstat -ano | findstr 1935
netstat -ano | findstr 1985
netstat -ano | findstr 8080
netstat -ano | findstr 8000
```

应能看到 `msdk-srs` 容器处于 `running` 状态

### 2. 验证前端是否启动

浏览器打开：

- [http://127.0.0.1:7000](http://127.0.0.1:7000)

未推流时应显示：

- `等待推流` 或 `连接中`

### 3. 验证安卓端是否成功推流

在安卓端点击 `开始 RTMP 推流` 后，观察：

- 安卓端状态切到 `Streaming in progress` 或同义状态
- SRS 日志中出现 `live/obs1`
- 前端页面从 `连接中` 进入 `播放中`

### 4. 验证低延迟

用无人机相机画面中的明显动作对比浏览器页面。

目标延迟：

- `300ms ~ 1200ms`

## 页面行为说明

这版页面已经实现：

- 自动拉流
- 状态展示：`连接中 / 播放中 / 连接失败`
- 手动重连按钮
- 断流后自动重试

## 迁移到另一台 Windows 电脑

### 需要安装什么

- Docker Desktop
- Chrome 或 Edge

PowerShell 通常为 Windows 自带，不需要额外安装。

### 需要带过去哪些文件

至少带过去：

- `D:\MSDK_merge\obs_low_latency\`
- `D:\MSDK_merge\DEPLOYMENT.md`

### 哪些端口必须可用

- `7000/tcp`：前端静态页面
- `1935/tcp`：RTMP ingest
- `1985/tcp`：WHEP / API
- `8080/tcp`：SRS HTTP
- `8000/udp`：WebRTC 媒体

### 如果端口冲突如何改

如果 `1935`、`1985`、`8080` 或 `8000` 冲突，需要同时修改：

1. `obs_low_latency\docker-compose.yml`
2. `obs_low_latency\srs\conf\obs-local.conf`
3. 安卓端填写的 `RTMP_URL`
4. `frontend/config.js` 中的 `WHEP_URL`

例如把 `1935` 改成 `11935`：

```text
rtmp://192.168.1.20:11935/live/obs1
```

## 防火墙 / 安全软件影响

重点检查：

- Docker Desktop 是否被允许联网
- `1935/tcp` 是否被拦截
- `1985/tcp`、`8080/tcp` 是否被拦截
- `8000/udp` 是否被拦截

处理建议：

1. 在 Windows Defender 防火墙中允许 Docker Desktop 通信
2. 放行 `1935/tcp`
3. 放行 `1985/tcp`
4. 放行 `8080/tcp`
5. 放行 `8000/udp`

## 常见问题排查

### Docker 未启动

现象：

- `docker compose up -d` 失败

处理：

- 先启动 Docker Desktop

### 安卓 RTMP 地址填写错误

现象：

- 安卓端不能开始直播
- 页面一直拉不到流

处理：

- 确认 RTMP 地址格式：

```text
rtmp://<WINDOWS_IP>:1935/live/obs1
```

- 确认 `<WINDOWS_IP>` 是 Windows 电脑局域网地址，不是 `127.0.0.1`

### 安卓端当前不是 H.264

现象：

- 安卓端提示不支持当前编码格式

处理：

- 将无人机相机直播编码切到 `H.264`

### Windows 防火墙拦截

现象：

- 安卓端开始推流失败
- 页面能打开但不播放

处理：

- 放行 `1935/tcp`
- 放行 `1985/tcp`
- 放行 `8080/tcp`
- 放行 `8000/udp`

## 说明与边界

本版方案只覆盖：

- 两台设备
- 安卓 DJI 原生 RTMP 推流
- Windows 本机 SRS
- 浏览器 WHEP/WebRTC 播放
- 单路直播
- 局域网访问

本版不包含：

- OBS
- 安卓端直推 WHIP
- HTTPS
- 公网分发
- 多路流管理
