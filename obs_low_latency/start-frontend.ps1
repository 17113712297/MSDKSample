param(
    [int]$Port = 7000,
    [string]$Root = "$PSScriptRoot\frontend"
)

$resolvedRoot = (Resolve-Path -LiteralPath $Root).Path
$listener = New-Object System.Net.HttpListener
$prefix = "http://127.0.0.1:$Port/"
$listener.Prefixes.Add($prefix)

try {
    $listener.Start()
} catch {
    Write-Host ""
    Write-Host "[ERROR] Failed to listen on $prefix"
    Write-Host "Check whether port $Port is already occupied, or run PowerShell as Administrator if needed."
    throw
}

Write-Host ""
Write-Host "[OK] Frontend server started"
Write-Host "[OK] Open $prefix in your browser"
Write-Host "[INFO] Root: $resolvedRoot"
Write-Host "[INFO] Press Ctrl+C to stop"
Write-Host ""

$contentTypes = @{
    ".html" = "text/html; charset=utf-8"
    ".js"   = "application/javascript; charset=utf-8"
    ".css"  = "text/css; charset=utf-8"
    ".json" = "application/json; charset=utf-8"
    ".svg"  = "image/svg+xml"
    ".png"  = "image/png"
    ".jpg"  = "image/jpeg"
    ".jpeg" = "image/jpeg"
    ".ico"  = "image/x-icon"
}

try {
    while ($listener.IsListening) {
        $context = $listener.GetContext()
        $requestPath = $context.Request.Url.AbsolutePath.TrimStart("/")
        if ([string]::IsNullOrWhiteSpace($requestPath)) {
            $requestPath = "index.html"
        }

        $safeRelativePath = $requestPath.Replace("/", "\")
        $targetPath = Join-Path $resolvedRoot $safeRelativePath
        $targetPath = [System.IO.Path]::GetFullPath($targetPath)

        if (-not $targetPath.StartsWith($resolvedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
            $context.Response.StatusCode = 403
            $context.Response.Close()
            continue
        }

        if (-not (Test-Path -LiteralPath $targetPath -PathType Leaf)) {
            $context.Response.StatusCode = 404
            $bytes = [System.Text.Encoding]::UTF8.GetBytes("404 Not Found")
            $context.Response.OutputStream.Write($bytes, 0, $bytes.Length)
            $context.Response.Close()
            continue
        }

        $extension = [System.IO.Path]::GetExtension($targetPath).ToLowerInvariant()
        if ($contentTypes.ContainsKey($extension)) {
            $context.Response.ContentType = $contentTypes[$extension]
        } else {
            $context.Response.ContentType = "application/octet-stream"
        }

        $buffer = [System.IO.File]::ReadAllBytes($targetPath)
        $context.Response.ContentLength64 = $buffer.Length
        $context.Response.OutputStream.Write($buffer, 0, $buffer.Length)
        $context.Response.Close()
    }
} finally {
    $listener.Stop()
    $listener.Close()
}
