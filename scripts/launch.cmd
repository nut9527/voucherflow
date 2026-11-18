@echo off
setlocal
"%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0app.ps1" %*
set "VF_EXIT_CODE=%errorlevel%"
echo.
if "%VF_EXIT_CODE%"=="0" (
    echo VoucherFlow %1 completed.
) else (
    echo VoucherFlow %1 failed. Exit code: %VF_EXIT_CODE%. 1>&2
)
if not defined VF_NO_PAUSE pause
exit /b %VF_EXIT_CODE%
