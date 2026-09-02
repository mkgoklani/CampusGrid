@echo off
echo ===================================================
echo   Starting CampusGrid Master Node Center
echo ===================================================

cd /d "%~dp0"
java -cp "bin;master-node/lib/*" MasterNodeApplication
pause
