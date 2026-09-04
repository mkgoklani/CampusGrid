# CampusGrid - PowerShell Build Script for Agent.jar and Master.jar
$ErrorActionPreference = "Stop"

Write-Host "===================================================" -ForegroundColor Cyan
Write-Host "  CampusGrid - Building Executable JAR Packages" -ForegroundColor Cyan
Write-Host "===================================================" -ForegroundColor Cyan

$javac = "javac"
$jar = "jar"
$defaultJdkPath = "C:\Program Files\OpenLogic\jdk-17.0.19.10-hotspot\bin"
if (Test-Path "$defaultJdkPath\javac.exe") {
    $javac = "$defaultJdkPath\javac.exe"
    $jar = "$defaultJdkPath\jar.exe"
}

# 1. Staging directories
Remove-Item -Recurse -Force out/agent-build, out/master-build -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path out/agent-build, out/master-build | Out-Null
New-Item -ItemType Directory -Force -Path out/master-build/web | Out-Null

# 2. Compile Agent Node + Common Library
Write-Host "[BUILD] Compiling Agent Node and Common Library..." -ForegroundColor Yellow
$libJars = (Get-ChildItem -Path "agent-node/lib/*.jar" | ForEach-Object { $_.FullName }) -join ";"
& $javac -encoding UTF-8 -d out/agent-build -cp "common-lib/src;$libJars" -sourcepath "agent-node/src;common-lib/src" agent-node/src/com/campusgrid/agent/Agent.java

# 3. Extract libraries for fat Agent.jar
Write-Host "[BUILD] Packaging hardware telemetry & libraries into Agent.jar..." -ForegroundColor Yellow
Push-Location out/agent-build
Get-ChildItem -Path "../../agent-node/lib/*.jar" | ForEach-Object {
    & $jar xf $_.FullName
}
Remove-Item -Recurse -Force META-INF -ErrorAction SilentlyContinue
Pop-Location

Set-Content -Path "out/agent-manifest.txt" -Value "Manifest-Version: 1.0`nMain-Class: com.campusgrid.agent.Agent`n"
& $jar cfm Agent.jar out/agent-manifest.txt -C out/agent-build .

# 4. Compile Master Node (with full common and agent DTOs included)
Write-Host "[BUILD] Compiling Master Node and Dashboard..." -ForegroundColor Yellow
& $javac -encoding UTF-8 -d out/master-build -cp "out/agent-build;$libJars" -sourcepath "master-node;common-lib/src;agent-node/src" master-node/*.java

# Copy all compiled agent/common classes to master-build to guarantee complete deserialization support
if (Test-Path "out/agent-build/com") {
    Copy-Item "out/agent-build/com" -Destination "out/master-build" -Recurse -Force
}

# Bundle web dashboard assets
Copy-Item "master-node/web/*" -Destination "out/master-build/web" -Recurse -Force

Set-Content -Path "out/master-manifest.txt" -Value "Manifest-Version: 1.0`nMain-Class: MasterNodeApplication`n"
& $jar cfm Master.jar out/master-manifest.txt -C out/master-build .

Write-Host "===================================================" -ForegroundColor Green
Write-Host "[SUCCESS] Artifacts created:" -ForegroundColor Green
Get-Item Agent.jar, Master.jar | Select-Object Name, @{Name="Size (MB)"; Expression={[math]::Round($_.Length / 1MB, 2)}}, LastWriteTime | Format-Table -AutoSize
Write-Host "===================================================" -ForegroundColor Green
