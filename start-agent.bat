@echo off
setlocal enabledelayedexpansion
title CampusGrid Distributed Worker Node (Auto-Sync)

cd /d "%~dp0"
set MASTER_IP=%1

:LOOP
cls
echo ===================================================
echo   CampusGrid Distributed Worker Agent (Auto-Sync)
echo ===================================================

:: 1. Auto-pull latest code from Git if repository exists
if exist ".git" (
    echo [SYNC] Checking for cluster updates from GitHub...
    git pull --quiet
)

:: 2. Auto-compile common-lib and agent-node
if not exist "bin" mkdir bin
javac -d bin -cp "common-lib/src;agent-node/src" common-lib/src/*.java agent-node/src/com/campusgrid/agent/*.java agent-node/src/com/campusgrid/agent/blender/*.java agent-node/src/com/campusgrid/agent/network/*.java agent-node/src/com/campusgrid/agent/os/*.java > nul 2>&1

:: 3. Launch Agent Node
if "%MASTER_IP%"=="" (
    echo [NETWORK] Mode: Zero-Config LAN Auto-Discovery (UDP Broadcast)
    java -Xmx1024m -cp "bin" com.campusgrid.agent.Agent
) else (
    echo [NETWORK] Mode: Direct Master Connection to: %MASTER_IP%
    java -Xmx1024m -cp "bin" com.campusgrid.agent.Agent %MASTER_IP%
)

echo.
echo ===================================================
echo [RELOAD] Agent stopped. Pulling latest code and restarting in 3s...
echo ===================================================
timeout /t 3 /nobreak > nul
goto LOOP

