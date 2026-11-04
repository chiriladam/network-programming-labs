# Finds the newest installed JDK (17 or newer) and puts its bin folder first in
# PATH for this PowerShell session, so "java" and "javac" are that JDK even if an
# old Java 8 is also installed and listed earlier in PATH.
# Used by build.ps1 and hardware.ps1:   . "$PSScriptRoot\jdk.ps1"

function Get-JavacMajor([string]$javac) {
    $ErrorActionPreference = 'Continue'
    try {
        $v = (& $javac -version 2>&1 | Out-String).Trim()   # "javac 21.0.5" / "javac 1.8.0_401"
    } catch { return 0 }
    if ($v -match 'javac\s+(\d+)(?:\.(\d+))?') {
        $major = [int]$Matches[1]
        if ($major -eq 1 -and $Matches[2]) { $major = [int]$Matches[2] }
        return $major
    }
    return 0
}

$exe = if ($IsLinux -or $IsMacOS) { 'javac' } else { 'javac.exe' }
$candidates = @()
if ($env:JAVA_HOME) { $candidates += Join-Path (Join-Path $env:JAVA_HOME 'bin') $exe }
$roots = @("$env:ProgramFiles\Eclipse Adoptium", "$env:ProgramFiles\Java", "$env:ProgramFiles\Microsoft",
           "$env:ProgramFiles\Zulu", "$env:ProgramFiles\Amazon Corretto", "$env:ProgramFiles\BellSoft",
           "$env:LOCALAPPDATA\Programs\Eclipse Adoptium", "$env:USERPROFILE\.jdks")
foreach ($root in $roots) {
    if ($env:ProgramFiles -and (Test-Path $root)) {
        $candidates += Get-ChildItem $root -Directory -ErrorAction SilentlyContinue |
            ForEach-Object { Join-Path (Join-Path $_.FullName 'bin') $exe }
    }
}
$found = Get-Command javac -ErrorAction SilentlyContinue
if ($found) { $candidates += $found.Source }

$best = $null; $bestMajor = 0
foreach ($c in ($candidates | Where-Object { $_ -and (Test-Path $_) } | Select-Object -Unique)) {
    $m = Get-JavacMajor $c
    if ($m -gt $bestMajor) { $best = $c; $bestMajor = $m }
}

if (-not $best -or $bestMajor -lt 17) {
    throw ("No JDK 17+ found" + $(if ($best) { " (newest is $bestMajor)" } else { "" }) +
           ". Install one with:  winget install EclipseAdoptium.Temurin.21.JDK  then open a NEW PowerShell window.")
}

$jdkBin = Split-Path -Parent $best
$env:JAVA_HOME = Split-Path -Parent $jdkBin
$env:PATH = $jdkBin + [IO.Path]::PathSeparator + $env:PATH
Write-Host "Using JDK $bestMajor from $env:JAVA_HOME"
