# Compiles the program into .\out (needs JDK 17 or newer; found automatically).
$ErrorActionPreference = "Stop"
Set-Location (Split-Path -Parent $PSScriptRoot)
. "$PSScriptRoot\jdk.ps1"
New-Item -ItemType Directory -Force out | Out-Null
javac -encoding UTF-8 -d out (Get-ChildItem src\*.java).FullName
if ($LASTEXITCODE -ne 0) { throw "Compilation failed" }
Write-Host "Built into .\out"
