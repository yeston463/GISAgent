@echo off
setlocal EnableExtensions EnableDelayedExpansion
chcp 65001 >nul

set "ROOT=%~dp0"
set "COMPOSE_FILE=%ROOT%compose.yaml"

if not exist "%COMPOSE_FILE%" (
  echo [ERROR] compose.yaml not found: %COMPOSE_FILE%
  pause
  exit /b 1
)

where docker >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Docker CLI not found.
  pause
  exit /b 1
)

docker info >nul 2>nul
if errorlevel 1 (
  set "DOCKER_DESKTOP_EXE=C:\Program Files\Docker\Docker\Docker Desktop.exe"
  if exist "!DOCKER_DESKTOP_EXE!" (
    echo [INFO] Starting Docker Desktop ...
    start "Docker Desktop" "!DOCKER_DESKTOP_EXE!"
    call :wait_docker 90
  )
)

docker info >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Docker Desktop did not become ready within 90 seconds.
  echo         Wait for "Engine running", then run this script again.
  pause
  exit /b 1
)

echo [INFO] Starting Redis and pgvector ...
docker compose -p lc4j -f "%COMPOSE_FILE%" up -d --wait
if errorlevel 1 (
  echo [ERROR] Redis or pgvector failed to become healthy.
  pause
  exit /b 1
)

docker compose -p lc4j -f "%COMPOSE_FILE%" exec -T redis redis-cli ping >nul
if errorlevel 1 (
  echo [ERROR] Redis health verification failed.
  pause
  exit /b 1
)

docker compose -p lc4j -f "%COMPOSE_FILE%" exec -T pgvector pg_isready -U postgres -d vectordb >nul
if errorlevel 1 (
  echo [ERROR] pgvector health verification failed.
  pause
  exit /b 1
)

echo.
echo [READY] Redis:    127.0.0.1:6379
echo [READY] pgvector: 127.0.0.1:5432
pause
exit /b 0

:wait_docker
set /a DOCKER_WAIT_LIMIT=%~1
for /l %%I in (1,1,!DOCKER_WAIT_LIMIT!) do (
  docker info >nul 2>nul
  if not errorlevel 1 exit /b 0
  ping 127.0.0.1 -n 2 >nul
)
exit /b 1
