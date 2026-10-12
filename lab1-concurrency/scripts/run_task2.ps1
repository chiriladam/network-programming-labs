# Task 2: reads the 8 part files from a USB flash drive with 1, 2 and 4 threads.
# Before every run the script asks you to eject, unplug and replug the drive,
# so nothing comes from the file cache.
#
#   powershell -ExecutionPolicy Bypass -File scripts\run_task2.ps1 -Drive E: -Copy
#
# -Drive      the USB drive letter, e.g. E:
# -Copy       copies parts\part1..8.txt to <Drive>\parts first (needed once)
# -AlsoLocal  also runs the same 8 files from the local disk, for comparison
param(
    [Parameter(Mandatory = $true)][string]$Drive,
    [switch]$Copy,
    [switch]$AlsoLocal,
    [string]$JavaOpts = "-Xmx2g"
)
$ErrorActionPreference = "Stop"
Set-Location (Split-Path -Parent $PSScriptRoot)
& "$PSScriptRoot\build.ps1"

$jvm = $JavaOpts.Split(' ', [StringSplitOptions]::RemoveEmptyEntries)
function Lab {
    & java @jvm -cp out Lab1 @args
    if ($LASTEXITCODE -ne 0) { throw "Lab1 $args failed" }
}

$usbDir = Join-Path ($Drive.TrimEnd('\') + '\') 'parts'

if (-not (Test-Path parts\part8.txt)) {
    if (-not (Test-Path numbers.txt)) { Lab gen }
    Lab split
}

if ($Copy) {
    Write-Host "Copying parts to $usbDir ..." -ForegroundColor Cyan
    New-Item -ItemType Directory -Force $usbDir | Out-Null
    Copy-Item parts\part*.txt $usbDir -Force
    Write-Host "Copied."
}
if (-not (Test-Path (Join-Path $usbDir 'part8.txt'))) {
    throw "No part files in $usbDir - run again with -Copy"
}

foreach ($t in 1, 2, 4) {
    Write-Host "`n=== Next run: $t thread(s) ===" -ForegroundColor Cyan
    Write-Host "1. Eject the drive (tray icon -> Eject), 2. unplug it, 3. plug it back in."
    Read-Host  "Press Enter when the drive is back"
    while (-not (Test-Path (Join-Path $usbDir 'part1.txt'))) {
        Write-Host "  waiting for $usbDir ..."; Start-Sleep -Seconds 2
    }
    Lab 2 --threads $t --dir $usbDir --label usb
}

if ($AlsoLocal) {
    Write-Host "`n=== Same files from the local disk (for comparison) ===" -ForegroundColor Cyan
    Lab 2 --threads 1 --dir parts --label local --csv warmup.csv | Out-Null   # warm the cache
    foreach ($t in 1, 2, 4) { Lab 2 --threads $t --dir parts --label local }
}

Write-Host "`nDone. Results appended to results.csv" -ForegroundColor Green
