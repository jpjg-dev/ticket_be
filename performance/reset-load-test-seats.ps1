param(
    [string]$DatabaseUrl,
    [string]$DatabaseUsername,
    [string]$DatabasePassword,
    [int]$PipelineWaitTimeoutSeconds = 300,
    [int]$PipelinePollIntervalMilliseconds = 1000,
    [switch]$SkipPipelineWait
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $repoRoot ".env.dev"
$sqlPath = Join-Path $PSScriptRoot "sql\reset-load-test-seats.sql"

if (-not (Test-Path $envPath) -and (-not $DatabaseUrl -or -not $DatabaseUsername -or -not $DatabasePassword)) {
    throw ".env.dev was not found: $envPath"
}

if (-not (Test-Path $sqlPath)) {
    throw "reset SQL was not found: $sqlPath"
}

$envMap = @{}
if (Test-Path $envPath) {
    Get-Content $envPath | ForEach-Object {
        if ($_ -match '^\s*#' -or $_ -notmatch '=') {
            return
        }

        $parts = $_ -split '=', 2
        $envMap[$parts[0].Trim()] = $parts[1].Trim()
    }
}

$resolvedDatabaseUrl = if ($DatabaseUrl) { $DatabaseUrl } else { $envMap["DB_URL"] }
$resolvedDatabaseUsername = if ($DatabaseUsername) { $DatabaseUsername } else { $envMap["DB_USERNAME"] }
$resolvedDatabasePassword = if ($DatabasePassword) { $DatabasePassword } else { $envMap["DB_PASSWORD"] }

if (-not ($resolvedDatabaseUrl -match 'jdbc:postgresql://([^:/]+):(\d+)/(.+)$')) {
    throw "DB_URL parse failed: $resolvedDatabaseUrl"
}

if (-not $resolvedDatabaseUsername -or -not $resolvedDatabasePassword) {
    throw "Database credentials are required."
}

if ($PipelineWaitTimeoutSeconds -lt 0 -or $PipelinePollIntervalMilliseconds -le 0) {
    throw "Pipeline wait timeout must be zero or positive, and poll interval must be positive."
}

$hostName = $Matches[1]
$port = $Matches[2]
$dbName = $Matches[3]

$env:PGPASSWORD = $resolvedDatabasePassword

function Get-UnsettledPaymentEventCount {
    $query = @"
SELECT COUNT(*)
FROM payment_outbox_events outbox
JOIN payments payment ON payment.id = outbox.payment_id
WHERE EXISTS (
    SELECT 1
    FROM reservations reservation
    JOIN seats seat ON seat.id = reservation.seat_id
    JOIN schedules schedule ON schedule.id = seat.schedule_id
    JOIN events event ON event.id = schedule.event_id
    WHERE reservation.reservation_group_id = payment.reservation_group_id
      AND event.title = 'PERF_LOAD_TEST_EVENT'
      AND seat.seat_number LIKE 'LOAD-%'
)
AND (
    outbox.status <> 'PUBLISHED'
    OR NOT EXISTS (
        SELECT 1
        FROM payment_event_audit audit
        WHERE audit.event_id = outbox.event_id
          AND audit.payload_hash = outbox.payload_hash
    )
);
"@

    $result = psql `
        -h $hostName `
        -p $port `
        -U $resolvedDatabaseUsername `
        -d $dbName `
        -t `
        -A `
        -v ON_ERROR_STOP=1 `
        -c $query

    if ($LASTEXITCODE -ne 0) {
        throw "Payment event pipeline preflight query failed."
    }

    return [int]($result | Select-Object -Last 1)
}

if (-not $SkipPipelineWait) {
    $deadline = (Get-Date).AddSeconds($PipelineWaitTimeoutSeconds)
    while ($true) {
        $unsettledCount = Get-UnsettledPaymentEventCount
        if ($unsettledCount -eq 0) {
            break
        }
        if ((Get-Date) -ge $deadline) {
            throw "Payment event pipeline did not converge within $PipelineWaitTimeoutSeconds seconds. unsettledCount=$unsettledCount"
        }
        Write-Output "Waiting for payment event pipeline convergence. unsettledCount=$unsettledCount"
        Start-Sleep -Milliseconds $PipelinePollIntervalMilliseconds
    }
}

psql -h $hostName -p $port -U $resolvedDatabaseUsername -d $dbName -v ON_ERROR_STOP=1 -f $sqlPath

if ($LASTEXITCODE -ne 0) {
    throw "Performance fixture reset failed."
}
