# Runs every configuration of Task 1a, 1b and 1c and writes results.csv.
#
#   powershell -ExecutionPolicy Bypass -File scripts\run_task1.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\run_task1.ps1 -Repeats 3 -Extras
#
# -Repeats  how many times each configuration is run (the charts use the median)
# -Extras   also runs the experiments for question 6 (batch of 1 line, unbounded queue)
param(
    [int]$Repeats = 3,
    [switch]$Extras,
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

$threads = 1, 2, 4, 8, 16, 32, 64
$logical = [Environment]::ProcessorCount
Write-Host "Logical processors: $logical"

# Data
if (-not (Test-Path numbers.txt) -or -not (Test-Path numbers.txt.counts)) { Lab gen }
if (-not (Test-Path parts\part8.txt)) { Lab split }

# Keep earlier results instead of mixing them
if (Test-Path results.csv) {
    $stamp = Get-Date -Format "yyyyMMdd_HHmmss"
    Move-Item results.csv "results_old_$stamp.csv"
}

# Warm-up (not recorded): puts numbers.txt in the OS file cache so every
# measured run reads it under the same conditions.
Lab 1a --threads 1 --csv warmup.csv | Out-Null

$start = Get-Date
for ($r = 1; $r -le $Repeats; $r++) {
    Write-Host "`n===== Round $r of $Repeats =====" -ForegroundColor Cyan

    foreach ($t in $threads) {
        Write-Host "-- 1a, $t threads" -ForegroundColor Yellow
        Lab 1a --threads $t --run $r
    }

    # 1b: version 1 must be run three times in total
    $v1Runs = if ($Repeats -ge 3) { 1 } else { [math]::Ceiling(3 / $Repeats) }
    for ($k = 1; $k -le $v1Runs; $k++) {
        Write-Host "-- 1b, version 1 (unsynchronised)" -ForegroundColor Yellow
        Lab 1b --version 1 --threads $logical --run "$r.$k"
    }
    Write-Host "-- 1b, version 2 (lock)" -ForegroundColor Yellow
    Lab 1b --version 2 --threads $logical --run $r
    Write-Host "-- 1b, version 3 (per-thread counters)" -ForegroundColor Yellow
    Lab 1b --version 3 --threads $logical --run $r

    foreach ($w in $threads) {
        Write-Host "-- 1c, $w workers" -ForegroundColor Yellow
        Lab 1c --workers $w --run $r
    }
}

if ($Extras) {
    Write-Host "`n===== Extra experiments for question 6 =====" -ForegroundColor Cyan
    Write-Host "-- 1c, batch of 1 line, 4 workers (slow: may take a minute)" -ForegroundColor Yellow
    Lab 1c --workers 4 --batch 1
    Write-Host "-- 1c, unbounded queue, 1 worker" -ForegroundColor Yellow
    Lab 1c --workers 1 --capacity 0
    Write-Host "-- 1c, unbounded queue, 4 workers" -ForegroundColor Yellow
    Lab 1c --workers 4 --capacity 0
}

$wrong = Import-Csv results.csv | Where-Object { $_.check -ne 'CORRECT' -and $_.variant -ne 'v1-unsync' }
Write-Host "`nDone in $([math]::Round(((Get-Date) - $start).TotalMinutes, 1)) min. Results: results.csv" -ForegroundColor Green
if ($wrong) { Write-Host "WARNING: some runs that should be correct are not!" -ForegroundColor Red; $wrong | Format-Table }
