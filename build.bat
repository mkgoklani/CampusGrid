@echo off
setlocal enabledelayedexpansion
echo ===================================================
echo   CampusGrid - JAR Build Script
echo ===================================================

REM Locate JDK binaries
set "JAVAC_BIN=javac"
set "JAR_BIN=jar"

if exist "C:\Program Files\OpenLogic\jdk-17.0.19.10-hotspot\bin\javac.exe" (
    set "JAVAC_BIN=C:\Program Files\OpenLogic\jdk-17.0.19.10-hotspot\bin\javac.exe"
    set "JAR_BIN=C:\Program Files\OpenLogic\jdk-17.0.19.10-hotspot\bin\jar.exe"
)

echo [BUILD] Cleaning staging directories...
if exist "out\agent-build" rmdir /s /q "out\agent-build"
if exist "out\master-build" rmdir /s /q "out\master-build"
mkdir "out\agent-build"
mkdir "out\master-build"
mkdir "out\master-build\web"

echo [BUILD] Compiling Agent Node and Common Library...
set "CP=common-lib\src;agent-node\lib\*"
"%JAVAC_BIN%" -encoding UTF-8 -d "out\agent-build" -cp "%CP%" -sourcepath "agent-node\src;common-lib\src" agent-node\src\com\campusgrid\agent\Agent.java
if %errorlevel% neq 0 (
    echo [ERROR] Agent compilation failed!
    exit /b %errorlevel%
)

echo [BUILD] Extracting library dependencies for standalone fat Agent.jar...
cd "out\agent-build"
for %%f in (..\..\agent-node\lib\*.jar) do (
    "%JAR_BIN%" xf "%%f"
)
if exist "META-INF" rmdir /s /q "META-INF"
cd ..\..

echo Manifest-Version: 1.0 > "out\agent-manifest.txt"
echo Main-Class: com.campusgrid.agent.Agent >> "out\agent-manifest.txt"

echo [BUILD] Packaging Agent.jar...
"%JAR_BIN%" cfm Agent.jar out\agent-manifest.txt -C out\agent-build .

echo [BUILD] Compiling Master Node and Common Library...
"%JAVAC_BIN%" -encoding UTF-8 -d "out\master-build" -cp "out\agent-build;agent-node\lib\*" -sourcepath "master-node;common-lib\src;agent-node\src" master-node\*.java
if %errorlevel% neq 0 (
    echo [ERROR] Master compilation failed!
    exit /b %errorlevel%
)

REM Bundle all compiled agent/common classes into master-build for complete deserialization support
if exist "out\agent-build\com" (
    xcopy /s /e /y "out\agent-build\com" "out\master-build\com" >nul
)

echo [BUILD] Bundling dashboard web assets into Master.jar...
xcopy /s /e /y "master-node\web" "out\master-build\web" >nul

echo Manifest-Version: 1.0 > "out\master-manifest.txt"
echo Main-Class: MasterNodeApplication >> "out\master-manifest.txt"

echo [BUILD] Packaging Master.jar...
"%JAR_BIN%" cfm Master.jar out\master-manifest.txt -C out\master-build .

echo ===================================================
echo [SUCCESS] Both JAR files built successfully!
echo   - Agent.jar  (Ready to copy to other computers on LAN)
echo   - Master.jar (Main coordinator for this computer)
echo ===================================================
