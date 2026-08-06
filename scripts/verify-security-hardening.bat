@echo off
setlocal
set "ROOT=%~dp0.."
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%ROOT%\scripts\verify-security-hardening.ps1" %*
exit /b %ERRORLEVEL%