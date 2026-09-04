@echo off
setlocal enabledelayedexpansion
title CampusGrid - Agent Node

echo ===================================================
echo   CampusGrid - Worker Agent Launcher
echo ===================================================
echo.

set "MASTER_IP=%~1"

if "%MASTER_IP%"=="" (
    set /p "MASTER_IP=Enter the Master Node IP address (e.g. 192.168.1.15): "
)

if "%MASTER_IP%"=="" (
    echo [ERROR] No IP address provided. Exiting.
    pause
    exit /b 1
)

echo.
echo [AGENT] Connecting to Master Node at %MASTER_IP%:8080 ...
echo [AGENT] Press Ctrl+C at any time to stop this worker.
echo [AGENT] If Blender is missing, it will auto-install to "C:\Program Files\Blender Foundation\Blender 5.1"
echo         (or "C:\Blender\Blender 5.1" if running without Administrator elevation).
echo.

if not exist "Agent.jar" (
    echo [ERROR] Agent.jar not found in this folder!
    echo Please ensure Agent.jar is in the same folder as this script.
    pause
    exit /b 1
)

java -jar Agent.jar %MASTER_IP%
pause
