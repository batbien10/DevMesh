@echo off
setlocal
set "BUNDLE_DIR=%~dp0.."
"%BUNDLE_DIR%\runtime\bin\java.exe" -jar "%BUNDLE_DIR%\devmesh.jar" %*
