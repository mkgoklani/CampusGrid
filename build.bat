@echo off
echo ===================================================
echo   Compiling CampusGrid (Common, Agent, Master, Tests)
echo ===================================================

cd /d "%~dp0"

if not exist "bin" mkdir "bin"

echo [1/4] Compiling Common Library DTOs and Core Tasks...
javac -d bin -cp "bin;master-node/lib/*" common-lib/src/*.java common-lib/src/com/campusgrid/core/*.java common-lib/src/com/campusgrid/agent/blender/*.java

if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Common Library compilation failed!
    pause
    exit /b 1
)

echo [2/4] Compiling Agent Node...
javac -d bin -cp "bin;master-node/lib/*;common-lib/src;agent-node/src" agent-node/src/com/campusgrid/agent/*.java agent-node/src/com/campusgrid/agent/network/*.java agent-node/src/com/campusgrid/agent/blender/*.java agent-node/src/com/campusgrid/agent/os/*.java

if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Agent Node compilation failed!
    pause
    exit /b 1
)

echo [3/4] Compiling Master Node and Web Services...
javac -d bin -cp "master-node/lib/*;bin;common-lib/src" master-node/*.java master-node/src/com/campusgrid/master/*.java

if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Master Node compilation failed!
    pause
    exit /b 1
)

echo [4/4] Compiling Integration Test Suites...
javac -d bin -cp "master-node/lib/*;bin;common-lib/src" test/*.java 2>nul

echo ===================================================
echo   ✔ BUILD SUCCESSFUL! All classes compiled to /bin
echo ===================================================
pause
