$ErrorActionPreference = "Stop"
$bundleDir = Split-Path -Parent $PSScriptRoot
& "$bundleDir/runtime/bin/java.exe" -jar "$bundleDir/devmesh.jar" @args
exit $LASTEXITCODE
