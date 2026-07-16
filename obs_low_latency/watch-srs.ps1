param(
    [string]$OutputDirectory = "$PSScriptRoot\diagnostics",
    [int]$PollIntervalSeconds = 5,
    [int]$StallThresholdSeconds = 20,
    [double]$MinForwardKbps = 8
)

$ErrorActionPreference = "Stop"
$apiBaseUrl = "http://127.0.0.1:1985/api/v1"
$restartCooldownSeconds = 30
$lastHealthyAt = $null
$lastRestartAt = $null
$lastCounters = $null

function Invoke-SrsApi {
    param([string]$Path)

    try {
        return Invoke-RestMethod -Uri "$apiBaseUrl/$Path/" -TimeoutSec 3
    } catch {
        return $null
    }
}

function Get-NestedValue {
    param(
        [object]$Object,
        [string[]]$Path
    )

    $current = $Object
    foreach ($segment in $Path) {
        if ($null -eq $current) {
            return $null
        }

        $property = $current.PSObject.Properties[$segment]
        if ($null -eq $property) {
            return $null
        }

        $current = $property.Value
    }

    return $current
}

function Get-NumericMetric {
    param(
        [object]$Object,
        [object[][]]$CandidatePaths
    )

    foreach ($path in $CandidatePaths) {
        $value = Get-NestedValue -Object $Object -Path $path
        if ($null -eq $value) {
            continue
        }

        $number = 0.0
        if ([double]::TryParse($value.ToString(), [ref]$number)) {
            return $number
        }
    }

    return $null
}

function Get-MetricOrDefault {
    param(
        [object]$Object,
        [object[][]]$CandidatePaths,
        [double]$DefaultValue = 0
    )

    $value = Get-NumericMetric -Object $Object -CandidatePaths $CandidatePaths
    if ($null -eq $value) {
        return $DefaultValue
    }

    return [double]$value
}

function Get-StreamSnapshot {
    $streamsResponse = Invoke-SrsApi -Path "streams"
    $clientsResponse = Invoke-SrsApi -Path "clients"
    if ($null -eq $streamsResponse) {
        return $null
    }

    $streams = @($streamsResponse.streams)
    $clients = if ($null -eq $clientsResponse) { @() } else { @($clientsResponse.clients) }

    $aggregate = [ordered]@{
        streamCount = $streams.Count
        clientCount = $clients.Count
        recvBytes = 0.0
        sendBytes = 0.0
        frames = 0.0
        forwardKbps = 0.0
        publishKbps = 0.0
    }

    foreach ($stream in $streams) {
        $aggregate.recvBytes += Get-MetricOrDefault -Object $stream -CandidatePaths @(
            @("recv_bytes"),
            @("publish", "recv_bytes")
        )
        $aggregate.sendBytes += Get-MetricOrDefault -Object $stream -CandidatePaths @(
            @("send_bytes")
        )
        $aggregate.frames += Get-MetricOrDefault -Object $stream -CandidatePaths @(
            @("frames")
        )
        $aggregate.forwardKbps += Get-MetricOrDefault -Object $stream -CandidatePaths @(
            @("kbps", "send_30s"),
            @("kbps", "send_5m"),
            @("kbps", "send_5s")
        )
        $aggregate.publishKbps += Get-MetricOrDefault -Object $stream -CandidatePaths @(
            @("kbps", "recv_30s"),
            @("kbps", "recv_5m"),
            @("kbps", "recv_5s")
        )
    }

    return [ordered]@{
        capturedAt = Get-Date
        streams = $streams
        clients = $clients
        aggregate = $aggregate
    }
}

function Invoke-Diagnose {
    & powershell -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "diagnose-srs.ps1") `
        -OutputDirectory $OutputDirectory `
        -SampleCount 5 `
        -SampleIntervalSeconds 1 | Out-Host
}

function Restart-Srs {
    Write-Host ""
    Write-Host "[WARN] Stream appears stalled. Capturing diagnostics and restarting SRS..."
    Invoke-Diagnose
    & docker compose --project-directory $PSScriptRoot restart srs
    $script:lastRestartAt = Get-Date
    $script:lastHealthyAt = $null
    $script:lastCounters = $null
    Write-Host "[OK] Restart command sent to SRS."
    Write-Host ""
}

Write-Host "[INFO] Watching SRS stream health via $apiBaseUrl"
Write-Host "[INFO] Poll interval: $PollIntervalSeconds s | Stall threshold: $StallThresholdSeconds s | Min forward kbps: $MinForwardKbps"
Write-Host "[INFO] Press Ctrl+C to stop."
Write-Host ""

while ($true) {
    $snapshot = Get-StreamSnapshot
    $now = Get-Date

    if ($null -eq $snapshot) {
        Write-Host "[$($now.ToString('HH:mm:ss'))] SRS API unavailable."
        if ($lastRestartAt -and (($now - $lastRestartAt).TotalSeconds -lt $restartCooldownSeconds)) {
            Start-Sleep -Seconds $PollIntervalSeconds
            continue
        }

        if ($lastHealthyAt -and (($now - $lastHealthyAt).TotalSeconds -ge $StallThresholdSeconds)) {
            Restart-Srs
        }

        Start-Sleep -Seconds $PollIntervalSeconds
        continue
    }

    $aggregate = $snapshot.aggregate
    $hasActiveStream = $aggregate.streamCount -gt 0
    $currentCounters = [ordered]@{
        recvBytes = [double]$aggregate.recvBytes
        sendBytes = [double]$aggregate.sendBytes
        frames = [double]$aggregate.frames
    }

    $progressed = $true
    if ($lastCounters) {
        $progressed =
            $currentCounters.recvBytes -gt $lastCounters.recvBytes -or
            $currentCounters.sendBytes -gt $lastCounters.sendBytes -or
            $currentCounters.frames -gt $lastCounters.frames
    }

    $forwardKbps = [double]$aggregate.forwardKbps
    $isHealthy = -not $hasActiveStream -or $progressed -or $forwardKbps -ge $MinForwardKbps

    if ($isHealthy) {
        $lastHealthyAt = $now
    } elseif (-not $lastHealthyAt) {
        $lastHealthyAt = $now
    }

    $status = if ($hasActiveStream) { "active" } else { "idle" }
    Write-Host ("[{0}] status={1} streams={2} clients={3} forwardKbps={4:N1} progressed={5}" -f `
        $now.ToString('HH:mm:ss'),
        $status,
        $aggregate.streamCount,
        $aggregate.clientCount,
        $forwardKbps,
        $progressed)

    if (
        $hasActiveStream -and
        -not $isHealthy -and
        $lastHealthyAt -and
        (($now - $lastHealthyAt).TotalSeconds -ge $StallThresholdSeconds) -and
        (
            -not $lastRestartAt -or
            (($now - $lastRestartAt).TotalSeconds -ge $restartCooldownSeconds)
        )
    ) {
        Restart-Srs
    }

    $lastCounters = $currentCounters
    Start-Sleep -Seconds $PollIntervalSeconds
}
