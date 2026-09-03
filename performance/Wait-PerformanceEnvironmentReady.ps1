param(
    [string]$BackendBaseUrl = "http://127.0.0.1:8080",
    [ValidateRange(1, 3600)]
    [int]$PollIntervalSeconds = 5,
    [ValidateRange(0, 86400)]
    [int]$TimeoutSeconds = 180,
    [ValidateRange(1, 100)]
    [int]$RequiredConsecutiveSamples = 3,
    [ValidateRange(0.0, 1.0)]
    [double]$MaximumSystemCpuUsage = 0.70,
    [ValidateRange(0.0, 1.0)]
    [double]$MaximumProcessCpuUsage = 0.50,
    [ValidateRange(0.0, [double]::MaxValue)]
    [double]$MaximumHikariPendingConnections = 0,
    [ValidateRange(0.0, [double]::MaxValue)]
    [double]$MaximumPendingOutboxBacklog = 0,
    [ValidateRange(0.0, [double]::MaxValue)]
    [double]$MaximumHoldManualOutboxBacklog = 0,
    [string]$MetricsFixturePath
)

$ErrorActionPreference = "Stop"

function ConvertTo-ReadinessNumber {
    param(
        [string]$Value,
        [string]$MetricName
    )

    $number = 0.0
    if (-not [double]::TryParse($Value, [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$number) -or [double]::IsNaN($number) -or [double]::IsInfinity($number)) {
        throw "Required metric '$MetricName' has a non-finite value."
    }

    return $number
}

function Get-RequiredMetricValues {
    param([string]$PrometheusText)

    $systemCpu = $null
    $processCpu = $null
    $hikariPending = @()
    $outboxBacklog = @{}

    foreach ($line in ($PrometheusText -split "`r?`n")) {
        if ($line -match '^system_cpu_usage\s+(?<value>\S+)') {
            $systemCpu = ConvertTo-ReadinessNumber $Matches.value "system_cpu_usage"
            continue
        }
        if ($line -match '^process_cpu_usage\s+(?<value>\S+)') {
            $processCpu = ConvertTo-ReadinessNumber $Matches.value "process_cpu_usage"
            continue
        }
        if ($line -match '^hikaricp_connections_pending(?:\{[^}]*\})?\s+(?<value>\S+)') {
            $hikariPending += ConvertTo-ReadinessNumber $Matches.value "hikaricp_connections_pending"
            continue
        }
        if ($line -match '^payment_outbox_backlog\{(?<labels>[^}]*)\}\s+(?<value>\S+)') {
            $labels = $Matches.labels
            $value = $Matches.value
            if ($labels -match '(?:^|,)\s*status="(?<status>[^"]+)"') {
                $outboxBacklog[$Matches.status] = ConvertTo-ReadinessNumber $value "payment_outbox_backlog"
            }
        }
    }

    $missing = @()
    if ($null -eq $systemCpu) { $missing += "system_cpu_usage" }
    if ($null -eq $processCpu) { $missing += "process_cpu_usage" }
    if ($hikariPending.Count -eq 0) { $missing += "hikaricp_connections_pending" }
    if (-not $outboxBacklog.ContainsKey("pending")) { $missing += 'payment_outbox_backlog{status="pending"}' }
    if (-not $outboxBacklog.ContainsKey("hold_manual")) { $missing += 'payment_outbox_backlog{status="hold_manual"}' }
    if ($missing.Count -gt 0) {
        throw "Missing required metrics: $($missing -join ', ')"
    }

    return [pscustomobject]@{
        SystemCpu = $systemCpu
        ProcessCpu = $processCpu
        HikariPending = ($hikariPending | Measure-Object -Sum).Sum
        PendingOutboxBacklog = $outboxBacklog["pending"]
        HoldManualOutboxBacklog = $outboxBacklog["hold_manual"]
    }
}

function Get-ReadinessSample {
    param(
        [object[]]$FixtureSamples,
        [ref]$FixtureIndex
    )

    if ($null -ne $FixtureSamples) {
        $sample = $FixtureSamples[[Math]::Min($FixtureIndex.Value, $FixtureSamples.Count - 1)]
        $FixtureIndex.Value++
        return [pscustomobject]@{
            LivenessStatus = [string]($sample.livenessStatus)
            PrometheusText = [string]($sample.prometheus)
        }
    }

    $health = Invoke-RestMethod -Uri "$($BackendBaseUrl.TrimEnd('/'))/actuator/health/liveness" -Method Get -TimeoutSec $PollIntervalSeconds
    $prometheus = Invoke-WebRequest -Uri "$($BackendBaseUrl.TrimEnd('/'))/actuator/prometheus" -Method Get -TimeoutSec $PollIntervalSeconds
    return [pscustomobject]@{
        LivenessStatus = [string]$health.status
        PrometheusText = $prometheus.Content
    }
}

$fixtureSamples = $null
if ($MetricsFixturePath) {
    $fixtureSamples = @(Get-Content -LiteralPath $MetricsFixturePath -Raw | ConvertFrom-Json)
    if ($fixtureSamples.Count -eq 0) {
        throw "Metrics fixture must contain at least one sample."
    }
}

$fixtureIndex = 0
$sampleNumber = 0
$stableSamples = 0
$elapsedSeconds = 0
$startedAt = Get-Date

while ($true) {
    $sampleNumber++
    try {
        $sample = Get-ReadinessSample -FixtureSamples $fixtureSamples -FixtureIndex ([ref]$fixtureIndex)
        if ($sample.LivenessStatus -ne "UP") {
            throw "Liveness is not UP."
        }

        $metrics = Get-RequiredMetricValues $sample.PrometheusText
        $stable = $metrics.SystemCpu -le $MaximumSystemCpuUsage -and
            $metrics.ProcessCpu -le $MaximumProcessCpuUsage -and
            $metrics.HikariPending -le $MaximumHikariPendingConnections -and
            $metrics.PendingOutboxBacklog -le $MaximumPendingOutboxBacklog -and
            $metrics.HoldManualOutboxBacklog -le $MaximumHoldManualOutboxBacklog

        if ($stable) {
            $stableSamples++
        } else {
            $stableSamples = 0
        }

        Write-Output ("readiness sample={0} liveness={1} system_cpu_usage={2:F3} process_cpu_usage={3:F3} hikaricp_connections_pending={4:F0} payment_outbox_backlog_pending={5:F0} payment_outbox_backlog_hold_manual={6:F0} stable_samples={7}/{8}" -f $sampleNumber, $sample.LivenessStatus, $metrics.SystemCpu, $metrics.ProcessCpu, $metrics.HikariPending, $metrics.PendingOutboxBacklog, $metrics.HoldManualOutboxBacklog, $stableSamples, $RequiredConsecutiveSamples)

        if ($stableSamples -ge $RequiredConsecutiveSamples) {
            Write-Output "readiness result=ready"
            exit 0
        }
    } catch {
        $stableSamples = 0
        Write-Output "readiness sample=$sampleNumber result=unstable stable_samples=0/$RequiredConsecutiveSamples reason=$($_.Exception.Message)"
    }

    if ($fixtureSamples) {
        if ($elapsedSeconds -ge $TimeoutSeconds) {
            throw "Performance environment did not become ready within $TimeoutSeconds seconds."
        }
        $elapsedSeconds += $PollIntervalSeconds
    } else {
        if (((Get-Date) - $startedAt).TotalSeconds -ge $TimeoutSeconds) {
            throw "Performance environment did not become ready within $TimeoutSeconds seconds."
        }
        Start-Sleep -Seconds $PollIntervalSeconds
    }
}
