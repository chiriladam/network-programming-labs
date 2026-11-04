# Starts the server:  scripts\server.ps1 [-Port 5000] [any extra Server options]
#   e.g.  scripts\server.ps1 -Port 5000 --stock coffee=10,tea=10,croissant=1
param([int]$Port = 5000, [Parameter(ValueFromRemainingArguments = $true)][string[]]$Rest)
& "$PSScriptRoot\build.ps1"
java -cp out Server $Port @Rest
