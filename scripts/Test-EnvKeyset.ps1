[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [ValidateNotNullOrEmpty()]
    [string]$EnvFile,

    [string]$ExampleFile
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

if ([string]::IsNullOrWhiteSpace($ExampleFile)) {
    $ExampleFile = Join-Path $PSScriptRoot "..\.env.example"
}

function Get-EnvKeyNames {
    param(
        [Parameter(Mandatory)]
        [string]$Path
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Environment file was not found: $Path"
    }

    $keys = [System.Collections.Generic.List[string]]::new()
    $lineNumber = 0

    foreach ($line in [System.IO.File]::ReadLines((Resolve-Path -LiteralPath $Path))) {
        $lineNumber++
        $trimmed = $line.Trim()

        if ([string]::IsNullOrWhiteSpace($trimmed) -or $trimmed.StartsWith("#")) {
            continue
        }

        if ($trimmed -notmatch "^(?:export\s+)?(?<key>[A-Za-z_][A-Za-z0-9_]*)\s*=") {
            throw "Unparseable environment assignment at line $lineNumber in $Path"
        }

        $keys.Add($Matches.key)
    }

    return $keys
}

try {
    $expectedKeys = @(Get-EnvKeyNames -Path $ExampleFile)
    $actualKeys = @(Get-EnvKeyNames -Path $EnvFile)

    $expectedSet = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal
    )
    $actualSet = [System.Collections.Generic.HashSet[string]]::new(
        [System.StringComparer]::Ordinal
    )

    $duplicateExpected = @($expectedKeys | Group-Object | Where-Object Count -gt 1 | ForEach-Object Name)
    $duplicateActual = @($actualKeys | Group-Object | Where-Object Count -gt 1 | ForEach-Object Name)
    $expectedKeys | ForEach-Object { [void]$expectedSet.Add($_) }
    $actualKeys | ForEach-Object { [void]$actualSet.Add($_) }

    $missingKeys = @($expectedKeys | Where-Object { -not $actualSet.Contains($_) })
    $extraKeys = @($actualKeys | Where-Object { -not $expectedSet.Contains($_) })

    if ($missingKeys.Count -eq 0 -and $extraKeys.Count -eq 0 -and
        $duplicateExpected.Count -eq 0 -and $duplicateActual.Count -eq 0) {
        Write-Output "Environment keyset matches .env.example."
        exit 0
    }

    if ($missingKeys.Count -gt 0) {
        Write-Output "Missing keys:"
        $missingKeys | ForEach-Object { Write-Output "  $_" }
    }
    if ($extraKeys.Count -gt 0) {
        Write-Output "Extra keys:"
        $extraKeys | ForEach-Object { Write-Output "  $_" }
    }
    if ($duplicateExpected.Count -gt 0) {
        Write-Output "Duplicate keys in .env.example:"
        $duplicateExpected | ForEach-Object { Write-Output "  $_" }
    }
    if ($duplicateActual.Count -gt 0) {
        Write-Output "Duplicate keys in supplied environment file:"
        $duplicateActual | ForEach-Object { Write-Output "  $_" }
    }

    exit 1
}
catch {
    Write-Error $_.Exception.Message
    exit 2
}
