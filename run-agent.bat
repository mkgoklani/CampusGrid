@echo off
setlocal enabledelayedexpansion

title CampusGrid Agent Node
cd /d "%~dp0"

echo ================================================================
echo           CAMPUSGRID DISTRIBUTED COMPUTE AGENT NODE             
echo ================================================================
echo.

:: 1. Verify agent.jar exists in current directory
if not exist "%~dp0agent.jar" (
    echo [ERROR] agent.jar not found in: %~dp0
    echo Please make sure run-agent.bat is in the same folder as agent.jar.
    echo.
    pause
    exit /b 1
)

:: 2. Check if Java is installed and in PATH
where java >nul 2>nul
if %errorlevel% neq 0 (
    echo [WARNING] Java command not found in system PATH.
    echo Checking standard installation directories...
    
    set "FOUND_JAVA="
    for /d %%D in ("C:\Program Files\Eclipse Adoptium\jdk-*" "C:\Program Files\Eclipse Adoptium\jre-*" "C:\Program Files\Java\jdk-*" "C:\Program Files\Microsoft\jdk-*") do (
        if exist "%%D\bin\java.exe" (
            set "FOUND_JAVA=%%D\bin\java.exe"
        )
    )

    if defined FOUND_JAVA (
        echo [OK] Found Java at: !FOUND_JAVA!
        set "JAVA_CMD=!FOUND_JAVA!"
    ) else (
        echo.
        echo [ERROR] Java (Version 17+) is required to run CampusGrid Agent.
        echo.
        echo Would you like to automatically install Java 17 using Winget? (Y/N)
        set /p "INSTALL_CHOICE=Choice [Y/N]: "
        if /i "!INSTALL_CHOICE!"=="Y" (
            echo.
            echo [INSTALL] Installing Eclipse Adoptium OpenJDK 17...
            winget install EclipseAdoptium.Temurin.17.JRE
            echo.
            echo [NOTE] Java installed! Please restart this .bat file.
            pause
            exit /b 0
        ) else (
            echo.
            echo Please manually download and install Java 17 from: https://adoptium.net
            pause
            exit /b 1
        )
    )
) else (
    set "JAVA_CMD=java"
)

:: 3. Check Java version
echo [CHECK] Verifying Java Environment...
"!JAVA_CMD!" -version
echo.

:: 4. Resolve Master Node Address
set "CONFIG_FILE=%~dp0master_node.txt"
set "DEFAULT_IP=100.66.175.104:8080"

:: If an address is passed directly as an argument (e.g. run-agent.bat 100.66.175.104:8080)
if not "%~1"=="" (
    set "MASTER_ADDR=%~1"
) else (
    if exist "%CONFIG_FILE%" (
        set /p SAVED_IP=<"%CONFIG_FILE%"
        if not "!SAVED_IP!"=="" set "DEFAULT_IP=!SAVED_IP!"
    )

    echo ----------------------------------------------------------------
    echo Enter the Master Node IP:Port (Tailscale IP or Local Network IP)
    echo Default: [!DEFAULT_IP!]
    echo ----------------------------------------------------------------
    set /p "USER_INPUT=Master Address (Press Enter for default): "
    
    if "!USER_INPUT!"=="" (
        set "MASTER_ADDR=!DEFAULT_IP!"
    ) else (
        set "MASTER_ADDR=!USER_INPUT!"
    )
    
    :: Save chosen IP for next time
    echo !MASTER_ADDR!>"%CONFIG_FILE%"
)

:: Ensure port 8080 is appended if user only typed IP
echo !MASTER_ADDR! | findstr /c:":" >nul
if %errorlevel% neq 0 (
    set "MASTER_ADDR=!MASTER_ADDR!:8080"
)

echo.
echo ================================================================
echo Starting CampusGrid Agent Node connecting to: !MASTER_ADDR!
echo ================================================================
echo.

"!JAVA_CMD!" -jar "%~dp0agent.jar" !MASTER_ADDR!

echo.
echo [AGENT] Process exited.
pause
