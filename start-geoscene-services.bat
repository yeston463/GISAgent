@echo off
setlocal EnableExtensions EnableDelayedExpansion
chcp 65001 >nul

rem GeoScene Windows services require administrator privileges.
fltmc >nul 2>&1
if errorlevel 1 (
  echo Requesting administrator privileges ...
  powershell.exe -NoProfile -ExecutionPolicy Bypass -Command ^
    "Start-Process -FilePath $env:ComSpec -ArgumentList '/d','/c','""%~f0""' -Verb RunAs"
  exit /b
)

set "WAIT_SECONDS=180"
set "FAILED=0"

echo.
echo Starting GeoScene services ...
echo.

call :start_service "GeoScene Data Store"
call :start_service "GeoScene Portal"
call :start_service "GeoScene Server"

echo.
echo GeoScene service status:
call :show_status "GeoScene Data Store"
call :show_status "GeoScene Portal"
call :show_status "GeoScene Server"
echo.

if not "!FAILED!"=="0" (
  echo [ERROR] !FAILED! GeoScene service^(s^) failed to reach RUNNING state.
  echo         Check Windows Event Viewer and the GeoScene service logs.
  pause
  exit /b 1
)

echo All requested GeoScene services are running.
pause
exit /b 0

:start_service
set "SERVICE_NAME=%~1"

sc.exe query "!SERVICE_NAME!" >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Service not found: !SERVICE_NAME!
  set /a FAILED+=1
  exit /b 0
)

sc.exe query "!SERVICE_NAME!" | findstr /C:"RUNNING" >nul
if not errorlevel 1 (
  echo [OK]    !SERVICE_NAME! is already running.
  exit /b 0
)

echo [START] !SERVICE_NAME! ...
sc.exe start "!SERVICE_NAME!" >nul 2>nul

for /l %%I in (1,1,!WAIT_SECONDS!) do (
  sc.exe query "!SERVICE_NAME!" | findstr /C:"RUNNING" >nul
  if not errorlevel 1 (
    echo [OK]    !SERVICE_NAME! is running.
    exit /b 0
  )

  sc.exe query "!SERVICE_NAME!" | findstr /C:"STOPPED" >nul
  if not errorlevel 1 if %%I GTR 5 (
    echo [ERROR] !SERVICE_NAME! stopped during startup.
    set /a FAILED+=1
    exit /b 0
  )

  timeout /t 1 /nobreak >nul
)

echo [ERROR] Timed out waiting for !SERVICE_NAME!.
set /a FAILED+=1
exit /b 0

:show_status
set "SERVICE_NAME=%~1"
set "SERVICE_STATE=NOT_FOUND"
for /f "tokens=4" %%S in ('sc.exe query "!SERVICE_NAME!" 2^>nul ^| findstr /R /C:"STATE *:"') do set "SERVICE_STATE=%%S"
echo   !SERVICE_NAME!: !SERVICE_STATE!
exit /b 0
