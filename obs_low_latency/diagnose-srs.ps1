param(
    [string]$OutputDirectory = "$PSScriptRoot\diagnostics",
    [int]$SampleCount = 3,
    [int]$SampleIntervalSeconds = 2
)

$ErrorActionPreference = "Stop"
$apiBaseUrl = "http://127.0.0.1:1985/api/v1"
$containerName = "msdk-srs"

function Invoke-SrsApi {
    param([string]$Path)

    try {
        return Invoke-RestMethod -Uri "$apiBaseUrl/$Path/" -TimeoutSec 3
    } catch {
        return [ordered]@{
            error = $_.Exception.Message
        }
    }
}

function Invoke-SrsApiSample {
    return [ordered]@{
        capturedAt = (Get-Date).ToString("o")
        summaries = Invoke-SrsApi -Path "summaries"
        streams = Invoke-SrsApi -Path "streams"
        clients = Invoke-SrsApi -Path "clients"
        rusages = Invoke-SrsApi -Path "rusages"
        selfProcStats = Invoke-SrsApi -Path "self_proc_stats"
        systemProcStats = Invoke-SrsApi -Path "system_proc_stats"
        memInfos = Invoke-SrsApi -Path "meminfos"
    }
}

function Invoke-DockerText {
    param([string[]]$Arguments)

    try {
        $ErrorActionPreference = "Continue"
        return ((& docker @Arguments 2>&1) | Out-String).Trim()
    } catch {
        return "ERROR: $($_.Exception.Message)"
    }
}

function Get-TcpPortState {
    try {
        return Get-NetTCPConnection -LocalPort 1935, 1985, 8080 -ErrorAction Stop |
            Select-Object LocalAddress, LocalPort, RemoteAddress, RemotePort, State, OwningProcess
    } catch {
        return ((netstat -ano -p tcp | Select-String -Pattern ':(1935|1985|8080)\s') | ForEach-Object {
            $_.Line.Trim()
        })
    }
}

function Get-UdpPortState {
    try {
        return Get-NetUDPEndpoint -LocalPort 8000 -ErrorAction Stop |
            Select-Object LocalAddress, LocalPort, OwningProcess
    } catch {
        return ((netstat -ano -p udp | Select-String -Pattern ':(8000)\s') | ForEach-Object {
            $_.Line.Trim()
        })
    }
}

New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

$samples = @()
for ($index = 0; $index -lt $SampleCount; $index += 1) {
    $samples += Invoke-SrsApiSample

    if ($index + 1 -lt $SampleCount) {
        Start-Sleep -Seconds $SampleIntervalSeconds
    }
}

$report = [ordered]@{
    capturedAt = (Get-Date).ToString("o")
    machine = $env:COMPUTERNAME
    apiBaseUrl = $apiBaseUrl
    apiRoot = Invoke-SrsApi -Path ""
    srsApiSamples = $samples
    dockerComposePs = Invoke-DockerText -Arguments @(
        "compose",
        "--project-directory",
        $PSScriptRoot,
        "ps"
    )
    dockerStats = Invoke-DockerText -Arguments @(
        "stats",
        $containerName,
        "--no-stream",
        "--format",
        "{{json .}}"
    )
    dockerInspect = Invoke-DockerText -Arguments @("inspect", $containerName)
    dockerLogs = Invoke-DockerText -Arguments @(
        "logs",
        "--timestamps",
        "--tail",
        "500",
        $containerName
    )
    tcpPorts = Get-TcpPortState
    udpPorts = Get-UdpPortState
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$outputPath = Join-Path $OutputDirectory "srs-diagnostic-$timestamp.json"
$json = $report | ConvertTo-Json -Depth 30
$utf8WithoutBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText($outputPath, $json, $utf8WithoutBom)

Write-Host ""
Write-Host "[OK] Diagnostic report saved before SRS restart:"
Write-Host $outputPath
Write-Host ""
