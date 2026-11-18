@echo off
call "%~dp0scripts\launch.cmd" stop %*
exit /b %errorlevel%
