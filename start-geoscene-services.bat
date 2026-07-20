@echo off
setlocal EnableExtensions
chcp 65001 >nul

set "ROOT=%~dp0"
set "HELPER=%ROOT%start-geoscene-services.ps1"
set "LOG_FILE=%ROOT%geoscene-services.log"

>"%LOG_FILE%" echo [%date% %time%] GeoScene launcher started.

if not exist "%HELPER%" (
  echo [ERROR] Missing helper: "%HELPER%"
  >>"%LOG_FILE%" echo [%date% %time%] Missing PowerShell helper.
  pause
  exit /b 1
)

powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%HELPER%"
set "EXIT_CODE=%ERRORLEVEL%"

if not "%EXIT_CODE%"=="0" (
  echo.
  echo [ERROR] GeoScene service startup failed. Exit code: %EXIT_CODE%
  echo         Log: "%LOG_FILE%"
  >>"%LOG_FILE%" echo [%date% %time%] Launcher failed with exit code %EXIT_CODE%.
  pause
  exit /b %EXIT_CODE%
)

echo.
echo [OK] GeoScene services and C:\apache-tomcat-9.0.119 startup completed.
echo      Log: "%LOG_FILE%"
pause
exit /b 0
