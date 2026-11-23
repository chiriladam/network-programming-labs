# Run ONCE in an ADMINISTRATOR PowerShell: lets the other computers' UDP datagrams in
# (to the chat port and to each peer's own private port) for the Java of this JDK.
. "$PSScriptRoot\jdk.ps1"
$java = Join-Path $env:JAVA_HOME "bin\java.exe"
netsh advfirewall firewall add rule name="LAN chat (Java UDP)" dir=in action=allow program="$java" protocol=UDP profile=any
Write-Host "Allowed incoming UDP for $java"
