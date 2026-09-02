@echo off
setlocal EnableDelayedExpansion

REM ==============================================================================
REM CampusGrid Agent Node - Windows Automated Deployment Script
REM ==============================================================================

set MASTER_IP=%1
if "%MASTER_IP%"=="" set MASTER_IP=auto

set INSTALL_DIR=%USERPROFILE%\CampusGrid
set JAR_NAME=agent.jar

echo ======================================================================
echo   CAMPUSGRID AGENT NODE - WINDOWS DEPLOYMENT
echo ======================================================================

REM 1. Check Java Runtime
where java >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    echo [DEPLOY-ERR] Java not found on system PATH.
    echo [DEPLOY-ERR] Please install Java 17+ (e.g. from https://adoptium.net/)
    pause
    exit /b 1
)

echo [DEPLOY] Java runtime detected.

REM 2. Create Directory
if not exist "%INSTALL_DIR%" mkdir "%INSTALL_DIR%"

REM 3. Copy/Download Agent JAR if in current dir
if exist "agent.jar" (
    copy /Y "agent.jar" "%INSTALL_DIR%\%JAR_NAME%" >nul
    echo [DEPLOY] Copied local agent.jar to %INSTALL_DIR%
) else if exist "..\agent.jar" (
    copy /Y "..\agent.jar" "%INSTALL_DIR%\%JAR_NAME%" >nul
    echo [DEPLOY] Copied parent agent.jar to %INSTALL_DIR%
)

REM 4. Check Blender
where blender.exe >nul 2>&1
if %ERRORLEVEL% EQU 0 (
    echo [BLENDER] Blender executable found on system PATH.
) else (
    if exist "C:\Program Files\Blender Foundation" (
        echo [BLENDER] Blender Foundation directory detected under Program Files.
    ) else (
        echo [BLENDER] Blender not found on system PATH. Agent will automatically download portable bundle if needed.
    )
)

REM 5. Launch Agent Node with Auto-Discovery or specified IP
cd /d "%INSTALL_DIR%"
echo [DEPLOY] Launching CampusGrid Agent Node (%MASTER_IP%)...
start "CampusGrid Agent Node" java -jar "%JAR_NAME%" %MASTER_IP%

echo [DEPLOY] Agent node process started successfully.
endlocal
