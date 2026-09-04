$ErrorActionPreference = "Stop"

$scriptPath = Join-Path $PSScriptRoot "Wait-PerformanceEnvironmentReady.ps1"
$fixtureDirectory = Join-Path ([System.IO.Path]::GetTempPath()) ("ticket-ledger-readiness-" + [guid]::NewGuid())
New-Item -ItemType Directory -Path $fixtureDirectory | Out-Null

function New-MetricsText {
    param([double]$SystemCpu = 0.20, [double]$ProcessCpu = 0.10, [double]$HikariPending = 0, [double]$PendingOutbox = 0, [double]$HoldManualOutbox = 0)

    return @"
system_cpu_usage $SystemCpu
process_cpu_usage $ProcessCpu
hikaricp_connections_pending $HikariPending
payment_outbox_backlog{status="pending"} $PendingOutbox
payment_outbox_backlog{status="hold_manual"} $HoldManualOutbox
"@
}

function Invoke-ReadinessCase {
    param([string]$Name, [object[]]$Samples, [int]$TimeoutSeconds, [int]$ExpectedExitCode)

    $fixturePath = Join-Path $fixtureDirectory "$Name.json"
    $stdoutPath = Join-Path $fixtureDirectory "$Name.out"
    $stderrPath = Join-Path $fixtureDirectory "$Name.err"
    $Samples | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath $fixturePath -Encoding utf8
    $process = Start-Process -FilePath "pwsh" -ArgumentList @(
        "-NoProfile",
        "-File",
        $scriptPath,
        "-MetricsFixturePath",
        $fixturePath,
        "-PollIntervalSeconds",
        "1",
        "-TimeoutSeconds",
        "$TimeoutSeconds"
    ) -NoNewWindow -Wait -PassThru -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath

    if ($process.ExitCode -ne $ExpectedExitCode) {
        $output = Get-Content -LiteralPath $stdoutPath -Raw -ErrorAction SilentlyContinue
        $errorOutput = Get-Content -LiteralPath $stderrPath -Raw -ErrorAction SilentlyContinue
        throw "$Name expected exit code $ExpectedExitCode but got $($process.ExitCode). Output: $output $errorOutput"
    }
}

function Invoke-LiveModeFailureCase {
    $stdoutPath = Join-Path $fixtureDirectory "live-mode.out"
    $stderrPath = Join-Path $fixtureDirectory "live-mode.err"
    $process = Start-Process -FilePath "pwsh" -ArgumentList @(
        "-NoProfile",
        "-File",
        $scriptPath,
        "-BackendBaseUrl",
        "http://127.0.0.1:1",
        "-PollIntervalSeconds",
        "1",
        "-TimeoutSeconds",
        "0"
    ) -NoNewWindow -Wait -PassThru -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath

    $output = Get-Content -LiteralPath $stdoutPath -Raw -ErrorAction SilentlyContinue
    $errorOutput = Get-Content -LiteralPath $stderrPath -Raw -ErrorAction SilentlyContinue
    if ($process.ExitCode -eq 0) {
        throw "live-mode expected a connection failure but exited successfully. Output: $output $errorOutput"
    }
    if ("$output`n$errorOutput" -match "Object reference not set") {
        throw "live-mode treated an empty fixture array as fixture input. Output: $output $errorOutput"
    }
}

try {
    $stable = @{ livenessStatus = "UP"; prometheus = New-MetricsText }
    Invoke-ReadinessCase -Name "success" -Samples @($stable, $stable, $stable) -TimeoutSeconds 10 -ExpectedExitCode 0

    $unstable = @{ livenessStatus = "UP"; prometheus = New-MetricsText -SystemCpu 0.71 }
    Invoke-ReadinessCase -Name "unstable-reset" -Samples @($stable, $unstable, $stable, $stable, $stable) -TimeoutSeconds 10 -ExpectedExitCode 0

    $missingMetric = @{ livenessStatus = "UP"; prometheus = "system_cpu_usage 0.20`nprocess_cpu_usage 0.10`nhikaricp_connections_pending 0`npayment_outbox_backlog{status=`"pending`"} 0" }
    Invoke-ReadinessCase -Name "missing-metric" -Samples @($missingMetric) -TimeoutSeconds 0 -ExpectedExitCode 1

    $notReady = @{ livenessStatus = "UP"; prometheus = New-MetricsText -PendingOutbox 1 }
    Invoke-ReadinessCase -Name "timeout" -Samples @($notReady) -TimeoutSeconds 2 -ExpectedExitCode 1

    Invoke-LiveModeFailureCase
} finally {
    Remove-Item -LiteralPath $fixtureDirectory -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Output "Wait-PerformanceEnvironmentReady tests passed."
