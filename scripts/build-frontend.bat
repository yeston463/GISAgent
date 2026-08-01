@echo off
setlocal EnableExtensions EnableDelayedExpansion
chcp 65001 >nul

rem ---------------------------------------------------------------------------
rem Build the Vue / ArcGIS frontend and sync the output into the Spring Boot
rem static folder (src/main/resources/static).
rem
rem Source of truth: share/<snapshot>/frontend-arcgis1  (discovered automatically
rem so the dated snapshot folder name does not matter).
rem
rem Usage: scripts\build-frontend.bat
rem ---------------------------------------------------------------------------

for %%R in ("%~dp0..") do set "ROOT=%%~fR"
set "STATIC=%ROOT%\src\main\resources\static"
set "FRONTEND="

for /d %%S in ("%ROOT%\share\*") do (
    if exist "%%S\frontend-arcgis1\package.json" set "FRONTEND=%%S\frontend-arcgis1"
)

if not defined FRONTEND (
    echo [ERROR] frontend-arcgis1 not found under "!ROOT!\share"
    exit /b 1
)

echo Using frontend source: "!FRONTEND!"
pushd "!FRONTEND!"

if not exist node_modules (
    echo Installing frontend dependencies ...
    call npm install || (popd & exit /b 1)
)

echo Building frontend ...
call npm run build || (popd & echo [ERROR] Frontend build failed. & exit /b 1)
popd

if not exist "!FRONTEND!\dist" (
    echo [ERROR] Build output missing: "!FRONTEND!\dist"
    exit /b 1
)

echo Syncing dist -^> "!STATIC!"
robocopy "!FRONTEND!\dist" "!STATIC!" /E /PURGE /NFL /NDL /NJS
if %ERRORLEVEL% GEQ 8 (
    echo [ERROR] robocopy failed with exit code %ERRORLEVEL%.
    exit /b %ERRORLEVEL%
)
echo [OK] Frontend built and synced to "!STATIC!"
exit /b 0
