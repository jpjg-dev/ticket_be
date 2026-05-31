$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $repoRoot ".env.dev"
$sqlPath = Join-Path $PSScriptRoot "sql\reset-load-test-seats.sql"

if (-not (Test-Path $envPath)) {
    throw ".env.dev was not found: $envPath"
}

if (-not (Test-Path $sqlPath)) {
    throw "reset SQL was not found: $sqlPath"
}

$envMap = @{}
Get-Content $envPath | ForEach-Object {
    if ($_ -match '^\s*#' -or $_ -notmatch '=') {
        return
    }

    $parts = $_ -split '=', 2
    $envMap[$parts[0].Trim()] = $parts[1].Trim()
}

if (-not ($envMap["DB_URL"] -match 'jdbc:postgresql://([^:/]+):(\d+)/(.+)$')) {
    throw "DB_URL parse failed: $($envMap["DB_URL"])"
}

$hostName = $Matches[1]
$port = $Matches[2]
$dbName = $Matches[3]

$env:PGPASSWORD = $envMap["DB_PASSWORD"]
psql -h $hostName -p $port -U $envMap["DB_USERNAME"] -d $dbName -f $sqlPath
