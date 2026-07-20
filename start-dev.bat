@echo off
setlocal EnableExtensions EnableDelayedExpansion
chcp 65001 >nul

rem One-click development launcher for Java + Python GIS + Vue.
set "ROOT=%~dp0"
set "FRONTEND=%ROOT%share\gis-agent-source-share-20260616-1321\frontend-arcgis1"

if exist "!ROOT!.env" (
  for /f "usebackq eol=# tokens=1,* delims==" %%A in ("!ROOT!.env") do (
    if not "%%A"=="" set "%%A=%%B"
  )
)

set "PYTHON_REQUEST=python"
if defined GIS_PYTHON_EXE set "PYTHON_REQUEST=%GIS_PYTHON_EXE%"
set "PYTHON_REQUEST=%PYTHON_REQUEST:"=%"

if not defined CITYENGINE_RUNTIME_ROOT set "CITYENGINE_RUNTIME_ROOT=C:\GISAgentCityEngine"
powershell.exe -NoProfile -Command "$p=$env:CITYENGINE_RUNTIME_ROOT; try {$null=[IO.Path]::GetFullPath($p)} catch {exit 1}; foreach($c in $p.ToCharArray()){if([int]$c -gt 127){exit 2}}" >nul
if errorlevel 1 (
  echo [ERROR] CITYENGINE_RUNTIME_ROOT must be a valid ASCII-only path.
  exit /b 1
)

if not defined QWEN-APIKEY (
  echo [ERROR] Missing QWEN-APIKEY.
  echo         Copy .env.example to .env and set the DashScope key.
  exit /b 1
)
if not exist "!ROOT!pom.xml" (
  echo [ERROR] pom.xml not found: "!ROOT!"
  exit /b 1
)
if not exist "!ROOT!main.py" (
  echo [ERROR] main.py not found: "!ROOT!"
  exit /b 1
)
if not exist "!FRONTEND!\package.json" (
  echo [ERROR] Frontend package.json not found: "!FRONTEND!"
  exit /b 1
)

if not exist "!ROOT!start-java-backend.ps1" (echo [ERROR] Java backend launcher not found.& exit /b 1)
set "PYTHON_EXE="
if exist "!PYTHON_REQUEST!" set "PYTHON_EXE=!PYTHON_REQUEST!"
if not defined PYTHON_EXE (
  for /f "delims=" %%P in ('where "!PYTHON_REQUEST!" 2^>nul') do if not defined PYTHON_EXE set "PYTHON_EXE=%%P"
)
if not defined PYTHON_EXE (echo [ERROR] Python not found: !PYTHON_REQUEST!& exit /b 1)
where npm >nul 2>nul || (echo [ERROR] npm not found.& exit /b 1)

if not exist "!FRONTEND!\node_modules" (
  echo Installing frontend dependencies ...
  pushd "!FRONTEND!"
  call npm install
  if errorlevel 1 (popd& echo [ERROR] npm install failed.& exit /b 1)
  popd
)

call "!PYTHON_EXE!" -c "import fastapi,uvicorn" >nul 2>nul
if errorlevel 1 if exist "!ROOT!requirements.txt" (
  echo Installing Python GIS dependencies ...
  call "!PYTHON_EXE!" -m pip install -r "!ROOT!requirements.txt"
  if errorlevel 1 (echo [WARN] Python dependency install failed; GIS service may not start.)
)

set "DOCKER_READY=0"
where docker >nul 2>nul
if not errorlevel 1 (
  docker info >nul 2>nul
  if not errorlevel 1 set "DOCKER_READY=1"
  if "!DOCKER_READY!"=="0" (
    echo Docker engine is not running. Trying to start Docker Desktop ...
    if exist "%ProgramFiles%\Docker\Docker\Docker Desktop.exe" start "" "%ProgramFiles%\Docker\Docker\Docker Desktop.exe"
    if exist "%LocalAppData%\Docker\Docker Desktop.exe" start "" "%LocalAppData%\Docker\Docker Desktop.exe"
    for /l %%I in (1,1,60) do (
      docker info >nul 2>nul
      if not errorlevel 1 (set "DOCKER_READY=1"& goto docker_ready)
      timeout /t 2 /nobreak >nul
    )
  )
) else (
  echo [WARN] Docker CLI not found; continuing with local service fallback.
)

:docker_ready
if "!DOCKER_READY!"=="1" if exist "!ROOT!compose.yaml" (
  echo Starting Redis and pgvector ...
  docker compose -p lc4j -f "!ROOT!compose.yaml" up -d --wait --wait-timeout 90
  if errorlevel 1 echo [WARN] Database containers did not become ready; application fallback remains available.
) else (
  echo [WARN] Redis/pgvector not started. PostgreSQL memory and Redis features may be degraded.
)

echo Verifying Java backend classes ...
pushd "!ROOT!"
call mvnw.cmd -q -DskipTests compile
if errorlevel 1 (
  popd
  echo [ERROR] Java backend compilation failed.
  pause
  exit /b 1
)

javap -classpath "!ROOT!target\classes" org.example.Lc4j1Application >nul 2>nul
if errorlevel 1 (
  echo Java main class is missing. Rebuilding compiler output ...
  call mvnw.cmd -q -DskipTests clean compile
  if errorlevel 1 (
    popd
    echo [ERROR] Java backend clean rebuild failed.
    pause
    exit /b 1
  )
)

javap -classpath "!ROOT!target\classes" org.example.Lc4j1Application >nul 2>nul
if errorlevel 1 (
  popd
  echo [ERROR] Java main class is still missing after rebuild.
  echo         Expected: target\classes\org\example\Lc4j1Application.class
  pause
  exit /b 1
)

echo Resolving Java runtime dependencies ...
call mvnw.cmd -q dependency:build-classpath "-Dmdep.outputFile=target/runtime-classpath.txt" "-Dmdep.includeScope=runtime"
if errorlevel 1 (
  popd
  echo [ERROR] Java runtime classpath generation failed.
  pause
  exit /b 1
)
if not exist "!ROOT!target\runtime-classpath.txt" (
  popd
  echo [ERROR] Java runtime classpath file was not generated.
  pause
  exit /b 1
)
popd

echo Starting Java backend on http://127.0.0.1:8080 ...
start "GIS Agent - Java" /D "%ROOT%" powershell.exe -NoLogo -NoExit -ExecutionPolicy Bypass -File "%ROOT%start-java-backend.ps1" -SkipBuild

echo Starting Python GIS service on http://127.0.0.1:8000 ...
start "GIS Agent - Python" /D "%ROOT%" cmd /k ""%PYTHON_EXE%" -m uvicorn main:app --host 127.0.0.1 --port 8000"

echo Starting Vue frontend on http://127.0.0.1:5173 ...
start "GIS Agent - Vue" /D "%FRONTEND%" cmd /k "call npm run dev -- --host 127.0.0.1"

echo Waiting for services ...
call :wait_url http://127.0.0.1:8000/analysis/runtime 60
call :wait_url http://127.0.0.1:5173 60
call :wait_url http://127.0.0.1:8080 90

echo.
echo GIS Agent is ready:
echo   Frontend: http://127.0.0.1:5173
echo   Backend:  http://127.0.0.1:8080
echo   GIS API:  http://127.0.0.1:8000/analysis/runtime
start "" "http://127.0.0.1:5173"
exit /b 0

:wait_url
set "WAIT_URL=%~1"
set /a WAIT_LIMIT=%~2
for /l %%I in (1,1,!WAIT_LIMIT!) do (
  curl.exe -fsS --max-time 2 "!WAIT_URL!" >nul 2>nul
  if not errorlevel 1 exit /b 0
  timeout /t 1 /nobreak >nul
)
echo [WARN] Service did not answer in time: !WAIT_URL!
exit /b 0
