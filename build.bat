@echo off
echo ===================================================
echo   Compiling CampusGrid (Master, Agent, Common)
echo ===================================================

cd /d "%~dp0"

if not exist "bin" mkdir "bin"

echo [1/3] Compiling Common Library and Master Node...
javac -d bin -cp "master-node/lib/*;master-node;bin" master-node/*.java master-node/src/com/campusgrid/master/*.java

if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Master Node compilation failed!
    pause
    exit /b 1
)

echo [2/3] Compiling Agent Node...
javac -d bin -cp "bin;common-lib/src;agent-node/src" agent-node/src/com/campusgrid/agent/*.java agent-node/src/com/campusgrid/agent/network/*.java agent-node/src/com/campusgrid/agent/blender/*.java agent-node/src/com/campusgrid/agent/os/*.java

if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Agent Node compilation failed!
    pause
    exit /b 1
)

echo [3/3] Compiling Common-lib DTOs...
javac -d bin -cp "bin" common-lib/src/*.java common-lib/src/com/campusgrid/core/*.java common-lib/src/com/campusgrid/agent/blender/*.java

echo ===================================================
echo   ✔ BUILD SUCCESSFUL! All classes compiled to /bin
echo ===================================================
pause
