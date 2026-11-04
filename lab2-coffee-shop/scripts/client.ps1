# Starts a client:  scripts\client.ps1 [-Server localhost] [-Port 5000]
param([string]$Server = "localhost", [int]$Port = 5000)
Set-Location (Split-Path -Parent $PSScriptRoot)
. "$PSScriptRoot\jdk.ps1" | Out-Null
java -cp out Client $Server $Port
