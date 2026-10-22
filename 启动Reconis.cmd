@echo off
where.exe pwsh.exe >nul 2>nul
if errorlevel 1 (
  "%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start.ps1" %*
) else (
  pwsh -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start.ps1" %*
)
if errorlevel 1 pause
