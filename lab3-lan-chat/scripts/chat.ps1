# Starts one peer of the chat:
#   scripts\chat.ps1 -Name Ana                          port 6000, passphrase "lab3-secret", multicast
#   scripts\chat.ps1 -Name Ana -Broadcast               the general channel by broadcast instead
#   scripts\chat.ps1 -Name Ana -Iface 192.168.8.115     choose the network card by its IP
param(
    [Parameter(Mandatory = $true)][string]$Name,
    [int]$Port = 6000,
    [string]$Passphrase = "lab3-secret",
    [switch]$Broadcast,
    [string]$Iface = ""
)
Set-Location (Split-Path -Parent $PSScriptRoot)
if (-not (Test-Path out\Chat.class)) { & "$PSScriptRoot\build.ps1" }
. "$PSScriptRoot\jdk.ps1" | Out-Null
$extra = @()
if ($Broadcast) { $extra += "--broadcast" }
if ($Iface) { $extra += "--iface", $Iface }
java -cp out Chat $Name $Port $Passphrase @extra
