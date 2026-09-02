@echo off
echo ===================================================
echo   Starting CampusGrid Distributed Worker Agent Node
echo ===================================================

cd /d "%~dp0"
set MASTER_IP=%1

if "%MASTER_IP%"=="" (
    echo No Master IP specified. Starting with Zero-Config LAN Auto-Discovery...
    java -cp "bin" com.campusgrid.agent.Agent
) else (
    echo Connecting to specified Master Node at: %MASTER_IP%
    java -cp "bin" com.campusgrid.agent.Agent %MASTER_IP%
)
pause
