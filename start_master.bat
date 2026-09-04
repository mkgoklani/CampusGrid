@echo off
setlocal enabledelayedexpansion
title CampusGrid - Master Node

echo ===================================================
echo   CampusGrid - Master Node Launcher
echo ===================================================
echo.
echo Detecting your local LAN / Wi-Fi IP address...
echo ---------------------------------------------------
for /f "tokens=4" %%a in ('route print ^| findstr 0.0.0.0 ^| findstr /v "0.0.0.0.*0.0.0.0"') do (
    echo [LAN IP] Your Master IP address is likely: %%a
)
echo.
echo Use the IP above when starting Agent nodes on other computers:
echo   java -jar Agent.jar <YOUR_IP>
echo ---------------------------------------------------
echo.
echo Starting Master Node...
echo Dashboard will be available at:
echo   Local: http://localhost:8081/
echo   LAN:   http://<YOUR_IP>:8081/
echo.

REM Check if Master.jar exists, if not build it
if not exist "Master.jar" (
    echo Master.jar not found, building first...
    call build.bat
)

REM Open dashboard in default browser after 2 seconds in background
start "" /b cmd /c "timeout /t 2 >nul & start http://localhost:8081/"

REM Run Master
java -jar Master.jar
pause
