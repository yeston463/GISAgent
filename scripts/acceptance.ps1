[CmdletBinding()]
param(
    [switch]$SkipFrontendE2E,
    [switch]$IncludeCityEngine,
    [switch]$IncludeNavigationAgent,
    [switch]$SkipAgentLoad,
    [int]$LoadConcurrency = 3,
    [int]$LoadRequests = 9
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$frontend = Join-Path $root 'frontend'

function Invoke-Check([string]$name, [scriptblock]$action) {
    Write-Host "`n== $name ==" -ForegroundColor Cyan
    & $action
    $exitCode = $LASTEXITCODE
    if ($null -ne $exitCode -and $exitCode -ne 0) { throw "$name failed with exit code $exitCode" }
}

Invoke-Check 'Service smoke' { & (Join-Path $PSScriptRoot 'smoke-test.ps1') -SkipCityEngine:(-not $IncludeCityEngine) -SkipAgent:(-not $IncludeNavigationAgent) }
Invoke-Check 'Spatial contract' { & (Join-Path $PSScriptRoot 'verify-spatial-analysis.ps1') }
if (-not $SkipAgentLoad) {
    Invoke-Check 'Agent route load' { & (Join-Path $PSScriptRoot 'verify-agent-load.ps1') -Concurrency $LoadConcurrency -Requests $LoadRequests }
}
if (-not $SkipFrontendE2E) {
    Invoke-Check 'Frontend Playwright E2E' { Push-Location $frontend; try { cmd /c npm run test:e2e } finally { Pop-Location } }
}

Write-Host "`nAcceptance passed." -ForegroundColor Green
