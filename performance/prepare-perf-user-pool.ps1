$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $PSScriptRoot
$envPath = Join-Path $repoRoot ".env.dev"
$sqlPath = Join-Path $PSScriptRoot "sql\seed-perf-users.sql"
$generatorPath = Join-Path $PSScriptRoot "scripts\generate-perf-user-tokens.js"
$dataDirectory = Join-Path $PSScriptRoot "data"
$outputPath = Join-Path $dataDirectory "perf-users.json"

if (-not (Test-Path $envPath)) {
    throw ".env.dev was not found: $envPath"
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

if (-not $envMap["JWT_SECRET"]) {
    throw "JWT_SECRET was not found in .env.dev"
}

$hostName = $Matches[1]
$port = $Matches[2]
$dbName = $Matches[3]

New-Item -ItemType Directory -Force -Path $dataDirectory | Out-Null

$env:PGPASSWORD = $envMap["DB_PASSWORD"]
$env:JWT_SECRET = $envMap["JWT_SECRET"]
$env:PERF_ACCESS_TOKEN_EXPIRATION_SECONDS = "3600"

$userIds = psql `
    -h $hostName `
    -p $port `
    -U $envMap["DB_USERNAME"] `
    -d $dbName `
    -t `
    -A `
    -f $sqlPath

if ($LASTEXITCODE -ne 0) {
    throw "Performance user seed failed."
}

$userIds | node $generatorPath | Set-Content -LiteralPath $outputPath -Encoding utf8NoBOM

if ($LASTEXITCODE -ne 0) {
    throw "Performance token generation failed."
}

$users = Get-Content -LiteralPath $outputPath -Raw | ConvertFrom-Json
if ($users.Count -ne 10000) {
    throw "Expected 10000 performance users but generated $($users.Count)."
}

Write-Output "Generated $($users.Count) performance users: $outputPath"
