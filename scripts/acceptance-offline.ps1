[CmdletBinding()]
param(
    [switch]$SkipFrontendBuild,
    [switch]$KeepServices,
    [int]$WaitSeconds = 120
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$frontend = Join-Path $root 'frontend'
$logsDir = Join-Path $root 'logs'
if (-not (Test-Path $logsDir)) { New-Item -ItemType Directory -Path $logsDir | Out-Null }

$gisLog = Join-Path $logsDir 'gis-python-offline.log'
$javaLog = Join-Path $logsDir 'java-offline.log'

function Wait-Port([int]$port, [int]$timeoutSec) {
    $sw = [Diagnostics.Stopwatch]::StartNew()
    while ($sw.Elapsed.TotalSeconds -lt $timeoutSec) {
        try {
            $c = New-Object Net.Sockets.TcpClient
            $c.Connect('127.0.0.1', $port)
            $c.Close()
            return $true
        } catch { Start-Sleep -Milliseconds 500 }
    }
    return $false
}

function Stop-ServiceProcess([int]$port) {
    Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue | ForEach-Object {
        Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue
    }
}

Write-Host "== GIS Agent offline acceptance ==" -ForegroundColor Cyan
Write-Host "Root: $root"

# --- Frontend build (optional) ---
if (-not $SkipFrontendBuild) {
    Write-Host "`n[1/5] Verifying frontend builds ..." -ForegroundColor Yellow
    Push-Location $frontend
    try {
        if (-not (Test-Path 'node_modules')) { cmd /c 'npm install' }
        $buildOutput = cmd /c 'npx vite build 2>&1'
        if ($LASTEXITCODE -ne 0) { throw "frontend build failed: $buildOutput" }
        if (-not (Test-Path 'dist/index.html')) { throw "frontend build failed: dist/index.html missing" }
        Write-Host "  frontend build OK"
    } finally { Pop-Location }
} else {
    Write-Host "`n[1/5] Skipping frontend build (use existing dist/)"
}

# --- Python GIS service on :8000 ---
Write-Host "`n[2/5] Starting Python GIS service on :8000 ..." -ForegroundColor Yellow
if (Wait-Port 8000 2) { Write-Host "  :8000 already in use, reusing existing service" }
else {
    $pyExe = if (Test-Path (Join-Path $root '.venv\Scripts\python.exe')) { Join-Path $root '.venv\Scripts\python.exe' } else { 'python' }
    $pyArgs = (Join-Path $root 'main.py')
    Start-Process -FilePath $pyExe -ArgumentList $pyArgs -RedirectStandardOutput $gisLog -RedirectStandardError "$gisLog.err" -WorkingDirectory $root -WindowStyle Hidden
    if (-not (Wait-Port 8000 60)) { throw "Python GIS did not start on :8000. Log: $gisLog" }
}
$runtime = Invoke-RestMethod -Uri 'http://127.0.0.1:8000/analysis/runtime'
Write-Host "  Python GIS ready (backend: $($runtime.backend))"

# --- Java backend on :8080 ---
Write-Host "`n[3/5] Starting Java backend on :8080 ..." -ForegroundColor Yellow
Push-Location $root
try {
    # Resolve runtime classpath if missing
    if (-not (Test-Path 'target\runtime-classpath.txt')) {
        Write-Host "  resolving Maven runtime classpath ..."
        cmd /c 'mvnw.cmd -q dependency:build-classpath "-Dmdep.outputFile=target/runtime-classpath.txt" "-Dmdep.includeScope=runtime"'
        if ($LASTEXITCODE -ne 0) { throw "Maven classpath resolution failed" }
    }
    if (-not (Test-Path 'target\classes\org\example\Lc4j1Application.class')) {
        Write-Host "  compiling Java backend ..."
        cmd /c 'mvnw.cmd -q -DskipTests compile'
        if ($LASTEXITCODE -ne 0) { throw "Java compilation failed" }
    }

    $env:SPATIAL_DEMO_ENABLED = 'true'
    $cp = "target\classes;$(Get-Content 'target\runtime-classpath.txt' -Raw -Encoding UTF8)".Trim()
    $javaExe = Get-Command java -ErrorAction SilentlyContinue | Select-Object -ExpandProperty Source
    if (-not $javaExe) { $javaExe = Join-Path $env:JAVA_HOME 'bin\java.exe' }
    $javaProc = Start-Process -FilePath $javaExe -ArgumentList @('-cp', $cp, 'org.example.Lc4j1Application') -RedirectStandardOutput $javaLog -RedirectStandardError "$javaLog.err" -WorkingDirectory $root -WindowStyle Hidden -PassThru

    if (-not (Wait-Port 8080 $WaitSeconds)) { throw "Java backend did not start on :8080. Log: $javaLog" }
    Write-Host "  Java backend ready"
} finally { Pop-Location }

# --- Offline case contract ---
Write-Host "`n[4/5] Verifying offline demo contract ..." -ForegroundColor Yellow
$offlineCase = Invoke-RestMethod -Uri 'http://127.0.0.1:8080/api/gis/offline-case'
if ($offlineCase.status -ne 'Success') { throw "offline-case status: $($offlineCase.status)" }
if ($offlineCase.case.buildings.features.Count -ne 6) { throw "expected 6 buildings, got $($offlineCase.case.buildings.features.Count)" }
if ($offlineCase.rules.effective -ne $false) { throw "rules.effective should be false for demo" }
Write-Host "  offline-case OK: 6 buildings, greenSpaces=$($offlineCase.case.greenSpaces.features.Count), rules.effective=false"

# --- Offline urban metrics (no network) ---
Write-Host "`n[5/5] Running offline urban_metrics against baseline ..." -ForegroundColor Yellow
$metricsBody = @{
    aoi = $offlineCase.case.aoi
    buildings = $offlineCase.case.buildings
} | ConvertTo-Json -Depth 10
$metrics = Invoke-RestMethod -Uri 'http://127.0.0.1:8000/analysis/urban_metrics' -Method POST -ContentType 'application/json' -Body ([Text.Encoding]::UTF8.GetBytes($metricsBody))

$baseline = @{
    building_count        = 6
    site_area_sqm         = 126264.63
    total_const_area_sqm  = 479807.48
    far                   = 3.8
    building_density      = 24.0
}
$failed = $false
foreach ($k in $baseline.Keys) {
    $actual = $metrics.$k
    $expected = $baseline[$k]
    if ($null -eq $actual) { Write-Host "  FAIL: metric $k missing"; $failed = $true; continue }
    $diff = [Math]::Abs($actual - $expected) / $expected
    $status = if ($diff -le 0.05) { 'OK' } else { 'FAIL'; $failed = $true }
    Write-Host "  $status $k = $actual (expected $expected, $([int]($diff*100))% off)"
}
if ($failed) { throw "One or more metrics deviated >5% from baseline" }
Write-Host "  urban_metrics: all baseline values matched within 5%"

# --- Summary ---
Write-Host ""
Write-Host "== OFFLINE ACCEPTANCE PASSED ==" -ForegroundColor Green
Write-Host "Frontend : run 'npm run dev' in frontend/ -> http://127.0.0.1:5173"
Write-Host "Backend  : http://127.0.0.1:8080"
Write-Host "GIS API  : http://127.0.0.1:8000/analysis/runtime"
Write-Host "Offline  : demo-context auto-loads 6-building case, no OSM/network required"

if (-not $KeepServices) {
    Write-Host "`nStopping acceptance services ..."
    Stop-ServiceProcess 8000
    Stop-ServiceProcess 8080
    Write-Host "  services stopped."
}
