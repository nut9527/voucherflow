@echo off
call "%~dp0scripts\launch.cmd" start %*
exit /b %errorlevel%
