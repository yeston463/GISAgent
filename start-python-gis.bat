@echo off
setlocal EnableExtensions EnableDelayedExpansion
chcp 65001 >nul

rem Start only the FastAPI GIS engine. Keep this window open to retain logs.
set "ROOT=%~dp0"
rem Load .env if present so GIS_PYTHON_EXE can be read from it.
if exist "!ROOT!.env" (
  for /f "usebackq eol=# tokens=1,* delims==" %%A in ("!ROOT!.env") do (
    if not "%%A"=="" set "%%A=%%B"
  )
)

rem Resolve the Python interpreter using a validated candidate chain:
rem   1) GIS_PYTHON_EXE    explicit override (used only if it can import fastapi/uvicorn)
rem   2) GeoScene ArcPy    arcpy-backed computation preferred first
rem   3) local .venv       open-source fallback
rem   4) system python
set "PYTHON_EXE="
if defined GIS_PYTHON_EXE call :try_python "%GIS_PYTHON_EXE%"
if not defined PYTHON_EXE call :try_python "C:\Program Files\GeoScene\Pro\bin\Python\envs\arcgispro-py3\python.exe"
if not defined PYTHON_EXE call :try_python "!ROOT!.venv\Scripts\python.exe"
if not defined PYTHON_EXE (
  for /f "delims=" %%p in ('where python 2^>nul') do (
    if not defined PYTHON_EXE call :try_python "%%p"
  )
)
if not defined PYTHON_EXE (
  echo [ERROR] No usable Python runtime found.
  echo         Set GIS_PYTHON_EXE in .env or install the project .venv.
  pause
  exit /b 1
)

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
exit /b 0

:try_python
if not defined PYTHON_EXE if exist "%~1" (
  "%~1" -c "import fastapi,uvicorn,rasterio" >nul 2>nul
  if not errorlevel 1 set "PYTHON_EXE=%~1"
)
goto :eof
