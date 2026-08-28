param(
    [string]$DatabaseUrl,
    [string]$DatabaseUsername,
    [string]$DatabasePassword
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

$hostName = $Matches[1]
$port = $Matches[2]
$dbName = $Matches[3]

$env:PGPASSWORD = $resolvedDatabasePassword
psql -h $hostName -p $port -U $resolvedDatabaseUsername -d $dbName -v ON_ERROR_STOP=1 -f $sqlPath

if ($LASTEXITCODE -ne 0) {
    throw "Performance fixture reset failed."
}
