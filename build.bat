@echo off
echo ===================================================
echo   Compiling CampusGrid (Common, Agent, Master, Tests)
echo ===================================================

cd /d "%~dp0"

if not exist "bin" mkdir "bin"

echo [1/4] Compiling Common Library DTOs and Core Tasks (Java 8 Compatible)...
javac --release 8 -encoding UTF-8 -d bin -cp "bin;master-node/lib/*" common-lib/src/*.java common-lib/src/com/campusgrid/core/*.java common-lib/src/com/campusgrid/agent/blender/*.java

if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Common Library compilation failed!
    pause
    exit /b 1
)

echo [2/4] Compiling Agent Node (Java 8 Compatible)...
javac --release 8 -encoding UTF-8 -d bin -cp "bin;master-node/lib/*;common-lib/src;agent-node/src" agent-node/src/com/campusgrid/agent/*.java agent-node/src/com/campusgrid/agent/network/*.java agent-node/src/com/campusgrid/agent/blender/*.java agent-node/src/com/campusgrid/agent/os/*.java

if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Agent Node compilation failed!
    pause
    exit /b 1
)

echo [3/4] Compiling Master Node and Web Services...
javac -encoding UTF-8 -d bin -cp "master-node/lib/*;bin" master-node/*.java master-node/src/com/campusgrid/master/*.java

if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Master Node compilation failed!
    pause
    exit /b 1
)

echo [4/5] Compiling Integration Test Suites...
javac -encoding UTF-8 -d bin -cp "master-node/lib/*;bin;master-node" test/*.java

echo [5/5] Packaging Standalone Runnable agent.jar...
jar cvfe bin/agent.jar com.campusgrid.agent.Agent -C bin . > nul 2>&1

echo ===================================================
echo   ✔ BUILD SUCCESSFUL! All classes compiled to /bin
echo   ✔ Packaged bin/agent.jar ready for distribution
echo ===================================================
pause
