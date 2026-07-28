@echo off
setlocal EnableExtensions
chcp 65001 >nul

rem Start only the FastAPI GIS engine. Keep this window open to retain logs.
set "ROOT=%~dp0"
set "PYTHON_EXE=%GIS_PYTHON_EXE%"
if not defined PYTHON_EXE set "PYTHON_EXE=%ROOT%.venv\Scripts\python.exe"
if not exist "%PYTHON_EXE%" set "PYTHON_EXE=python"

echo [GIS] Checking http://127.0.0.1:8000 ...
curl.exe -fsS --max-time 2 http://127.0.0.1:8000/analysis/runtime >nul 2>nul
if not errorlevel 1 (
  echo [GIS] Service is already running. This window can be closed.
  pause
  exit /b 0
)

echo [GIS] Starting Python service at http://127.0.0.1:8000 ...
echo [GIS] Python: %PYTHON_EXE%
"%PYTHON_EXE%" "%ROOT%main.py"

echo.
echo [GIS] Service stopped with exit code %ERRORLEVEL%.
pause
