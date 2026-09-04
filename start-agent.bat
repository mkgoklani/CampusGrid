@echo off
setlocal enabledelayedexpansion
title CampusGrid Distributed Worker Node [Auto-Sync]

cd /d "%~dp0"
set MASTER_IP=%1

:: If not passed as argument, check if master_ip.txt exists
if "%MASTER_IP%"=="" (
    if exist "master_ip.txt" (
        set /p MASTER_IP=<master_ip.txt
        set MASTER_IP=!MASTER_IP: =!
    )
)

:: If still empty, prompt the user
if "%MASTER_IP%"=="" (
    echo.
    echo ===================================================
    echo   CampusGrid Distributed Worker Node Setup
    echo ===================================================
    echo  Enter Master Node IP (e.g. 10.12.69.153)
    echo  Or press ENTER to scan via LAN Auto-Discovery:
    echo.
    set /p USER_INPUT="Master IP (optional): "
    if not "!USER_INPUT!"=="" (
        set MASTER_IP=!USER_INPUT!
        echo !USER_INPUT!> master_ip.txt
        echo [SAVED] Master IP saved to master_ip.txt
    )
) else (
    echo %MASTER_IP%> master_ip.txt
)

:LOOP
cls
echo ===================================================
echo   CampusGrid Distributed Worker Agent [Auto-Sync]
echo ===================================================

:: 1. Auto-pull latest code from Git if repository exists
if exist ".git" (
    echo [SYNC] Syncing latest cluster code from GitHub [branch: nilesh]...
    git fetch origin nilesh 2>nul
    git pull origin nilesh 2>nul
)

:: 2. Auto-compile common-lib and agent-node if javac is available
if not exist "bin" mkdir bin

where javac >nul 2>nul
if %ERRORLEVEL% EQU 0 (
    echo [BUILD] Compiling Agent source files...
    javac -d bin -cp "bin;common-lib/src;agent-node/src;master-node/lib/*" common-lib/src/*.java agent-node/src/com/campusgrid/agent/*.java agent-node/src/com/campusgrid/agent/blender/*.java agent-node/src/com/campusgrid/agent/network/*.java agent-node/src/com/campusgrid/agent/os/*.java 2>nul
) else (
    echo [BUILD] JRE detected [javac not in PATH]. Using compiled bin classes.
)

:: 3. Launch Agent Node
if "%MASTER_IP%"=="" (
    echo [NETWORK] Mode: Zero-Config LAN Auto-Discovery [UDP Broadcast]
    java -Xmx1024m -cp "bin;bin/agent.jar" com.campusgrid.agent.Agent
) else (
    echo [NETWORK] Mode: Direct Master Connection to: %MASTER_IP%
    java -Xmx1024m -cp "bin;bin/agent.jar" com.campusgrid.agent.Agent %MASTER_IP%
)

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] Agent exited with an error code: %ERRORLEVEL%
    echo Press any key to retry...
    pause >nul
)

echo.
echo ===================================================
echo [RELOAD] Agent stopped. Restarting in 3s...
echo ===================================================
timeout /t 3 /nobreak > nul
goto LOOP
