# Many clients at once:
#   scripts\demo.ps1 -Mode many -Clients 15            15 clients order 1 coffee each at the same moment
#   scripts\demo.ps1 -Mode rush -Clients 10            10 clients race for the croissants
param([string]$Mode = "many", [int]$Clients = 15, [string]$Product = "", [string]$Server = "localhost", [int]$Port = 5000)
Set-Location (Split-Path -Parent $PSScriptRoot)
. "$PSScriptRoot\jdk.ps1" | Out-Null
if ($Product) { java -cp out Demo $Server $Port $Mode $Clients $Product }
else          { java -cp out Demo $Server $Port $Mode $Clients }
