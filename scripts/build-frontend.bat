@echo off
setlocal EnableExtensions EnableDelayedExpansion
chcp 65001 >nul

rem ---------------------------------------------------------------------------
rem Build the Vue / ArcGIS frontend and sync the output into the Spring Boot
rem static folder (src/main/resources/static).
rem
rem Source of truth: frontend/ (Vue 3 + Vite + @arcgis/core).
rem This script only builds the frontend into frontend/dist/ — it does NOT
rem sync into Spring Boot's static/ folder (backend no longer hosts the UI).
rem
rem Usage: scripts\build-frontend.bat
rem ---------------------------------------------------------------------------

for %%R in ("%~dp0..") do set "ROOT=%%~fR"
set "FRONTEND=%ROOT%frontend"

if not exist "!FRONTEND!\package.json" (
    echo [ERROR] frontend/package.json not found at "!FRONTEND!"
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

echo [OK] Frontend built at "!FRONTEND!\dist" (backend no longer hosts static assets).
exit /b 0
