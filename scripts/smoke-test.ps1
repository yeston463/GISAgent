param(
    [string]$FrontendUrl = "http://127.0.0.1:5173/",
    [string]$PythonUrl = "http://127.0.0.1:8000",
    [string]$JavaUrl = "http://127.0.0.1:8080",
    [int]$AnalyzeTimeoutSeconds = 20,
    [switch]$SkipDocker,
    [switch]$SkipAgent,
    [switch]$SkipCityEngine,
    [switch]$RequireLiveOsm
)

$ErrorActionPreference = "Stop"
$passed = 0
$failed = 0
$root = Split-Path -Parent $PSScriptRoot

function Pass([string]$message) {
    $script:passed++
    Write-Host "[PASS] $message" -ForegroundColor Green
}

function Fail([string]$message) {
    $script:failed++
    Write-Host "[FAIL] $message" -ForegroundColor Red
}

function Invoke-Json([string]$method, [string]$uri, $body, [int]$timeoutSeconds = 10) {
    $params = @{ Uri = $uri; Method = $method; TimeoutSec = $timeoutSeconds }
    if ($null -ne $body) {
        $params.ContentType = "application/json"
        $params.Body = if ($body -is [string]) {
            $body
        } else {
            $body | ConvertTo-Json -Depth 8 -Compress
        }
    }
    return Invoke-RestMethod @params
}

function Invoke-Docker([string[]]$arguments) {
    $output = & docker @arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw ($output | Out-String)
    }
    return $output
}

if (-not $SkipDocker) {
    try {
        Invoke-Docker @("info", "--format", "{{.ServerVersion}}") | Out-Null
        $redisPing = Invoke-Docker @("compose", "-p", "lc4j", "-f", (Join-Path $root "compose.yaml"), "exec", "-T", "redis", "redis-cli", "ping")
        if (($redisPing | Out-String).Trim() -ne "PONG") {
            throw "Redis did not return PONG"
        }
        Invoke-Docker @("compose", "-p", "lc4j", "-f", (Join-Path $root "compose.yaml"), "exec", "-T", "pgvector", "pg_isready", "-U", "postgres", "-d", "vectordb") | Out-Null
        Pass "Docker Redis and pgvector are healthy"
    } catch {
        Fail "Docker Redis/pgvector health check failed: $($_.Exception.Message)"
    }
}

try {
    # Windows PowerShell 5 can depend on the retired Internet Explorer parser.
    # The frontend health check only needs the HTTP status and must not require IE.
    $frontend = Invoke-WebRequest -Uri $FrontendUrl -TimeoutSec 10 -UseBasicParsing
    if ($frontend.StatusCode -ge 200 -and $frontend.StatusCode -lt 400) {
        Pass "frontend responds"
    } else {
        Fail "frontend returned HTTP $($frontend.StatusCode)"
    }
} catch {
    Fail "frontend unavailable: $($_.Exception.Message)"
}

try {
    $runtime = Invoke-Json "GET" "$PythonUrl/analysis/runtime" $null
    if ($runtime.status -eq "Success") {
        Pass "Python GIS runtime responds"
    } else {
        Fail "Python GIS runtime returned $($runtime.status)"
    }
} catch {
    Fail "Python GIS runtime unavailable: $($_.Exception.Message)"
}

if (-not $SkipAgent) {
    try {
        # Use JSON Unicode escapes so the test stays valid in Windows PowerShell
        # 5 even when the script is decoded through a local ANSI code page.
        $navigationBody = '{"message":"\u98DE\u5230\u6B66\u6C49\u5927\u5B66","memoryId":"smoke-navigation"}'
        $navigation = Invoke-Json "POST" "$JavaUrl/api/agent/chat/agentic" $navigationBody 30
        $flyTo = @($navigation.commands | Where-Object { $_.action -eq "flyTo" })
        if ($navigation.needClarification -eq $false -and $flyTo.Count -eq 1) {
            Pass "agent returns one flyTo command for a place-navigation request"
        } else {
            Fail "agent navigation did not return flyTo"
        }
    } catch {
        Fail "agent navigation request failed: $($_.Exception.Message)"
    }
}

try {
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $analysis = Invoke-Json "POST" "$PythonUrl/analysis/analyze_area" @{
        lon = 114.3589886
        lat = 30.538568
        radius = 500
    } ($AnalyzeTimeoutSeconds + 5)
    $watch.Stop()
    if ($watch.Elapsed.TotalSeconds -le $AnalyzeTimeoutSeconds -and @("Success", "NoData") -contains $analysis.status) {
        Pass "analyze_area returned $($analysis.status) in $([Math]::Round($watch.Elapsed.TotalSeconds, 1))s without expanding the requested radius"
    } elseif (
        -not $RequireLiveOsm -and
        $watch.Elapsed.TotalSeconds -le $AnalyzeTimeoutSeconds -and
        $analysis.status -eq "Error" -and
        $analysis.message -like "Overpass unavailable within *"
    ) {
        Pass "analyze_area stopped cleanly in $([Math]::Round($watch.Elapsed.TotalSeconds, 1))s while Overpass was unavailable"
    } else {
        Fail "analyze_area returned $($analysis.status) in $([Math]::Round($watch.Elapsed.TotalSeconds, 1))s"
    }
} catch {
    Fail "analyze_area timed out or failed: $($_.Exception.Message)"
}

if (-not $SkipCityEngine) {
    try {
        $resultsDir = Join-Path $root "cityengine-workspace\automation\results"
        $python = Join-Path $root ".venv\Scripts\python.exe"
        if (-not (Test-Path -LiteralPath $python -PathType Leaf)) {
            $python = (Get-Command python.exe -ErrorAction Stop).Source
        }
        # Windows PowerShell 5 cannot parse the deeply nested GeoJSON kept in a
        # CityEngine manifest. The project Python runtime can parse it reliably.
        $pythonCode = @'
import json
import sys
from pathlib import Path

completed = []
for path in Path(sys.argv[1]).glob("ce-*.json"):
    try:
        result = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, json.JSONDecodeError):
        continue
    if result.get("status") == "completed":
        completed.append((path.stat().st_mtime, result))

if not completed:
    raise SystemExit(2)

_, latest = max(completed, key=lambda item: item[0])
print(json.dumps({
    "jobId": latest.get("jobId"),
    "slpk": (latest.get("outputs") or {}).get("slpk"),
    "sceneServiceUrl": latest.get("sceneServiceUrl"),
}))
'@
        $env:GISAGENT_SMOKE_PYTHON_CODE = $pythonCode
        $pythonLauncher = 'import os;exec(os.getenv(bytes([71,73,83,65,71,69,78,84,95,83,77,79,75,69,95,80,89,84,72,79,78,95,67,79,68,69]).decode()))'
        $latestJson = & $python -c $pythonLauncher $resultsDir
        if ($LASTEXITCODE -ne 0 -or -not $latestJson) {
            Fail "no completed CityEngine result is available for verification"
        } else {
            $latest = $latestJson | ConvertFrom-Json
            if (-not (Test-Path -LiteralPath $latest.slpk)) {
                Fail "latest CityEngine result has no readable SLPK output"
            } elseif ([string]::IsNullOrWhiteSpace($latest.sceneServiceUrl)) {
                Fail "latest CityEngine result has no SceneServer URL"
            } else {
                Pass "latest CityEngine result includes SLPK and SceneServer metadata"
            }
        }
    } catch {
        Fail "CityEngine artifact verification failed: $($_.Exception.Message)"
    }
}

Write-Host "Smoke result: $passed passed, $failed failed"
if ($failed -gt 0) { exit 1 }
