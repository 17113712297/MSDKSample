@echo off
setlocal
cd /d "%~dp0"
docker compose up -d
if errorlevel 1 (
  echo.
  echo [ERROR] SRS failed to start. Check Docker Desktop and the port usage first.
  exit /b 1
)
echo.
echo [OK] SRS started. RTMP ingest is on rtmp://127.0.0.1:1935/live/obs1
echo [OK] WHEP/API is on http://127.0.0.1:1985
echo [OK] HTTP service is on http://127.0.0.1:8080
echo [OK] WebRTC UDP port is 8000/udp
echo [INFO] If video stalls, run diagnose-srs.bat before restarting SRS.
