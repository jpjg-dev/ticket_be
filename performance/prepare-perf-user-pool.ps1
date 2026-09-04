param(
    [string]$DatabaseUrl,
    [string]$DatabaseUsername,
    [string]$DatabasePassword,
    [string]$JwtSecret
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $repoRoot ".env.dev"
$sqlPath = Join-Path $PSScriptRoot "sql\seed-perf-users.sql"
$generatorPath = Join-Path $PSScriptRoot "scripts\generate-perf-user-tokens.js"
$dataDirectory = Join-Path $PSScriptRoot "data"
$outputPath = Join-Path $dataDirectory "perf-users.json"

if (-not (Test-Path $envPath) -and (-not $DatabaseUrl -or -not $DatabaseUsername -or -not $DatabasePassword -or -not $JwtSecret)) {
    throw ".env.dev was not found: $envPath"
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
$resolvedJwtSecret = if ($JwtSecret) { $JwtSecret } else { $envMap["JWT_SECRET"] }

if (-not ($resolvedDatabaseUrl -match 'jdbc:postgresql://([^:/]+):(\d+)/(.+)$')) {
    throw "DB_URL parse failed: $resolvedDatabaseUrl"
}

if (-not $resolvedDatabaseUsername -or -not $resolvedDatabasePassword -or -not $resolvedJwtSecret) {
    throw "Database credentials and JWT secret are required."
}

$hostName = $Matches[1]
$port = $Matches[2]
$dbName = $Matches[3]

New-Item -ItemType Directory -Force -Path $dataDirectory | Out-Null

$env:PGPASSWORD = $resolvedDatabasePassword
$env:JWT_SECRET = $resolvedJwtSecret
$env:PERF_ACCESS_TOKEN_EXPIRATION_SECONDS = "3600"

$userIds = psql `
    -h $hostName `
    -p $port `
    -U $resolvedDatabaseUsername `
    -d $dbName `
    -t `
    -A `
    -f $sqlPath

if ($LASTEXITCODE -ne 0) {
    throw "Performance user seed failed."
}

$generatedUsersJson = $userIds | node $generatorPath | Out-String

if ($LASTEXITCODE -ne 0) {
    throw "Performance token generation failed."
}

[System.IO.File]::WriteAllText(
    $outputPath,
    $generatedUsersJson,
    [System.Text.UTF8Encoding]::new($false)
)

$users = Get-Content -LiteralPath $outputPath -Raw | ConvertFrom-Json
if ($users.Count -ne 10000) {
    throw "Expected 10000 performance users but generated $($users.Count)."
}

Write-Output "Generated $($users.Count) performance users: $outputPath"
