# Remove all release artifacts except the latest installer.
# Run after closing YuNian / Electron. Locked app.asar may require reboot (see Mark-RebootDelete).

param(
    [string]$ReleaseRoot = (Join-Path $PSScriptRoot "..\frontend\release"),
    [string]$KeepVersion = "0.2.54"
)

$ErrorActionPreference = "SilentlyContinue"
$ReleaseRoot = (Resolve-Path $ReleaseRoot).Path
$keepExe = Join-Path $ReleaseRoot "v$KeepVersion\YuNian Setup $KeepVersion.exe"

if (-not (Test-Path $keepExe)) {
    Write-Error "Latest installer not found: $keepExe"
    exit 1
}

Add-Type @"
using System;
using System.Runtime.InteropServices;
public class RebootDelete {
    [DllImport("kernel32.dll", SetLastError=true, CharSet=CharSet.Unicode)]
    public static extern bool MoveFileEx(string lpExistingFileName, string lpNewFileName, int dwFlags);
}
"@

function Mark-RebootDelete([string]$Path) {
    if (-not (Test-Path $Path)) { return $false }
    $ok = [RebootDelete]::MoveFileEx($Path, $null, 4)
    if ($ok) { Write-Host "Scheduled delete on reboot: $Path" }
    return $ok
}

$backup = Join-Path $env:TEMP "YuNian-Setup-$KeepVersion.exe"
Copy-Item -Force $keepExe $backup

Get-ChildItem $ReleaseRoot -Force | ForEach-Object {
    if ($_.Name -eq "v$KeepVersion") { return }
    if (-not (Remove-Item $_.FullName -Recurse -Force)) {
        Mark-RebootDelete $_.FullName | Out-Null
    }
}

$winUnpacked = Join-Path $ReleaseRoot "v$KeepVersion\win-unpacked"
if (Test-Path $winUnpacked) {
    if (-not (Remove-Item $winUnpacked -Recurse -Force)) {
        Mark-RebootDelete $winUnpacked | Out-Null
    }
}

if (-not (Test-Path $keepExe)) {
    New-Item -ItemType Directory -Path (Split-Path $keepExe) -Force | Out-Null
    Copy-Item -Force $backup $keepExe
}

Write-Host "Done. Kept: $keepExe"
Get-ChildItem $ReleaseRoot -Recurse -File | Select-Object FullName, @{N='MB';E={[math]::Round($_.Length/1MB,2)}}
