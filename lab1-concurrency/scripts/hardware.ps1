# Prints the hardware description for the report and saves it to hardware.txt.
Set-Location (Split-Path -Parent $PSScriptRoot)
try { . "$PSScriptRoot\jdk.ps1" } catch { Write-Host $_ -ForegroundColor Yellow }

& {
    $cpu = Get-CimInstance Win32_Processor
    $cs  = Get-CimInstance Win32_ComputerSystem
    $os  = Get-CimInstance Win32_OperatingSystem
    "=== CPU ==="
    foreach ($c in $cpu) {
        "Model:              $($c.Name.Trim())"
        "Physical cores:     $($c.NumberOfCores)"
        "Logical processors: $($c.NumberOfLogicalProcessors)"
        "Max clock:          $($c.MaxClockSpeed) MHz"
    }
    ""
    "=== RAM ==="
    "Total:              {0:N1} GB" -f ($cs.TotalPhysicalMemory / 1GB)
    Get-CimInstance Win32_PhysicalMemory |
        ForEach-Object { "  module: {0:N0} GB @ {1} MT/s" -f ($_.Capacity / 1GB), $_.Speed }
    ""
    "=== Disks ==="
    Get-PhysicalDisk | Sort-Object DeviceId |
        Format-Table -AutoSize FriendlyName, MediaType, BusType, @{n='Size(GB)'; e={[math]::Round($_.Size / 1GB)}} |
        Out-String
    "=== USB drives ==="
    Get-Disk | Where-Object BusType -eq 'USB' |
        Format-Table -AutoSize Number, FriendlyName, @{n='Size(GB)'; e={[math]::Round($_.Size / 1GB)}} |
        Out-String
    "(USB version: check the drive's label/box; USB 3.x connectors are usually blue inside)"
    ""
    "=== OS ==="
    "$($os.Caption) $($os.Version) (build $($os.BuildNumber)), $($os.OSArchitecture)"
    ""
    "=== Java ==="
    (cmd /c "java -version 2>&1") -join "`n"
} | Tee-Object -FilePath hardware.txt

Write-Host "`nSaved to hardware.txt"
