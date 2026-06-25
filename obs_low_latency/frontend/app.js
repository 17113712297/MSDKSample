(function () {
  const defaultConfig = {
    PORT: "7000",
    WHEP_URL: "http://127.0.0.1:1985/rtc/v1/whep/?app=live&stream=obs1",
    AUTO_PLAY: true,
    SHOW_RECONNECT: true
  };

  const config = Object.assign({}, defaultConfig, window.APP_CONFIG || {});
  const stateLabel = document.getElementById("stateLabel");
  const stateBadge = document.getElementById("stateBadge");
  const overlay = document.getElementById("overlay");
  const overlayTitle = document.getElementById("overlayTitle");
  const overlayCopy = document.getElementById("overlayCopy");
  const reconnectButton = document.getElementById("reconnectButton");
  const startButton = document.getElementById("startButton");
  const stopButton = document.getElementById("stopButton");
  const video = document.getElementById("liveVideo");
  const whepValue = document.getElementById("whepValue");
  const autoplayValue = document.getElementById("autoplayValue");
  const reconnectValue = document.getElementById("reconnectValue");
  const portValue = document.getElementById("portValue");

  let peer = null;
  let sessionUrl = null;
  let reconnectTimer = null;
  let manuallyStopped = false;

  const states = {
    idle: {
      label: "等待推流",
      title: "等待安卓 RTMP 推流",
      copy: "请先启动 SRS，再让安卓设备推到默认 RTMP 地址。",
      dotState: "idle"
    },
    connecting: {
      label: "连接中",
      title: "正在建立 WebRTC 连接",
      copy: "页面已开始拉取 WHEP 流，通常会在 1 到 3 秒内进入播放状态。",
      dotState: "connecting"
    },
    playing: {
      label: "播放中",
      title: "直播已连接",
      copy: "当前页面正通过 WHEP / WebRTC 播放 obs1。",
      dotState: "playing"
    },
    failed: {
      label: "连接失败",
      title: "暂时无法拉到直播",
      copy: "请检查 SRS、安卓 RTMP 地址和 Windows 防火墙，然后点击手动重连。",
      dotState: "failed"
    }
  };

  function renderConfig() {
    whepValue.textContent = config.WHEP_URL;
    autoplayValue.textContent = String(Boolean(config.AUTO_PLAY));
    reconnectValue.textContent = String(Boolean(config.SHOW_RECONNECT));
    portValue.textContent = String(config.PORT);
    reconnectButton.hidden = !config.SHOW_RECONNECT;
  }

  function setUiState(nextState, detail) {
    const current = states[nextState];
    stateLabel.textContent = current.label;
    stateBadge.dataset.state = current.dotState;
    overlayTitle.textContent = current.title;
    overlayCopy.textContent = detail || current.copy;
    overlay.hidden = nextState === "playing";
    stopButton.disabled = nextState === "idle" || nextState === "failed";
  }

  function clearReconnectTimer() {
    if (reconnectTimer) {
      window.clearTimeout(reconnectTimer);
      reconnectTimer = null;
    }
  }

  function waitForIceGatheringComplete(nextPeer) {
    if (nextPeer.iceGatheringState === "complete") {
      return Promise.resolve();
    }

    return new Promise((resolve) => {
      function handleStateChange() {
        if (nextPeer.iceGatheringState === "complete") {
          nextPeer.removeEventListener("icegatheringstatechange", handleStateChange);
          resolve();
        }
      }

      nextPeer.addEventListener("icegatheringstatechange", handleStateChange);
      window.setTimeout(() => {
        nextPeer.removeEventListener("icegatheringstatechange", handleStateChange);
        resolve();
      }, 1200);
    });
  }

  async function closeSession() {
    clearReconnectTimer();
    if (peer) {
      peer.ontrack = null;
      peer.onconnectionstatechange = null;
      peer.oniceconnectionstatechange = null;
      peer.getSenders().forEach((sender) => {
        try {
          if (sender.track) {
            sender.track.stop();
          }
        } catch (error) {
        }
      });
      peer.close();
      peer = null;
    }

    if (sessionUrl) {
      try {
        await fetch(sessionUrl, { method: "DELETE" });
      } catch (error) {
      }
      sessionUrl = null;
    }

    video.srcObject = null;
  }

  function scheduleReconnect(reason) {
    if (!config.SHOW_RECONNECT || manuallyStopped) {
      return;
    }
    clearReconnectTimer();
    setUiState("failed", reason || states.failed.copy);
    reconnectTimer = window.setTimeout(() => {
      startPlayback(true);
    }, 1500);
  }

  async function startPlayback(isRetry) {
    manuallyStopped = false;
    clearReconnectTimer();
    await closeSession();
    setUiState("connecting");

    try {
      const nextPeer = new RTCPeerConnection({
        bundlePolicy: "max-bundle",
        iceServers: []
      });

      nextPeer.addTransceiver("video", { direction: "recvonly" });
      nextPeer.addTransceiver("audio", { direction: "recvonly" });

      nextPeer.ontrack = (event) => {
        if (event.streams && event.streams[0]) {
          video.srcObject = event.streams[0];
        } else {
          const fallback = new MediaStream([event.track]);
          video.srcObject = fallback;
        }
      };

      nextPeer.onconnectionstatechange = function () {
        const connectionState = nextPeer.connectionState;
        if (connectionState === "connected") {
          setUiState("playing");
        } else if (connectionState === "failed" || connectionState === "disconnected") {
          scheduleReconnect("WebRTC 连接中断，正在尝试重连。");
        }
      };

      nextPeer.oniceconnectionstatechange = function () {
        const iceState = nextPeer.iceConnectionState;
        if (iceState === "failed") {
          scheduleReconnect("ICE 建连失败，请检查 8000/udp 是否被拦截。");
        }
      };

      const offer = await nextPeer.createOffer();
      await nextPeer.setLocalDescription(offer);
      await waitForIceGatheringComplete(nextPeer);

      const response = await fetch(config.WHEP_URL, {
        method: "POST",
        headers: {
          "Content-Type": "application/sdp"
        },
        body: nextPeer.localDescription.sdp
      });

      if (!response.ok) {
        throw new Error("WHEP 响应失败: HTTP " + response.status);
      }

      const answerSdp = await response.text();
      const locationHeader = response.headers.get("Location");
      sessionUrl = locationHeader
        ? new URL(locationHeader, config.WHEP_URL).toString()
        : null;

      await nextPeer.setRemoteDescription({
        type: "answer",
        sdp: answerSdp
      });

      peer = nextPeer;

      try {
        await video.play();
      } catch (error) {
        setUiState("failed", "浏览器拦截了自动播放，请点击“开始播放”后重试。");
        throw error;
      }
    } catch (error) {
      await closeSession();
      const message = error && error.message ? error.message : "未知错误";
      if (isRetry) {
        setUiState("failed", "重连失败: " + message);
      } else {
        scheduleReconnect("首次连接失败: " + message);
      }
    }
  }

  async function stopPlayback() {
    manuallyStopped = true;
    await closeSession();
    setUiState("idle");
  }

  reconnectButton.addEventListener("click", function () {
    startPlayback(true);
  });

  startButton.addEventListener("click", function () {
    startPlayback(false);
  });

  stopButton.addEventListener("click", function () {
    stopPlayback();
  });

  video.addEventListener("pause", function () {
    if (!manuallyStopped && peer && peer.connectionState === "connected") {
      video.play().catch(function () {
      });
    }
  });

  renderConfig();
  setUiState("idle");

  if (config.AUTO_PLAY) {
    startPlayback(false);
  }
})();
