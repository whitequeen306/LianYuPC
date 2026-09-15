param(
  [string]$Version = "",
  [string]$ElectronUnpacked = "",
  [string]$Output = ""
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$project = Join-Path $root 'LianYu.Installer'
$payloadDir = Join-Path $project 'Payload'
$payload = Join-Path $payloadDir 'LianYu-payload.zip'

if ([string]::IsNullOrWhiteSpace($Version)) {
  # 版本单一来源是 frontend/package.json（发版链路总是显式传 -Version）
  throw "Version is required. Pass -Version (from frontend/package.json)."
}

if ([string]::IsNullOrWhiteSpace($ElectronUnpacked)) {
  $ElectronUnpacked = "..\frontend\release\v$Version\win-unpacked"
}
if ([string]::IsNullOrWhiteSpace($Output)) {
  $Output = "..\frontend\release\v$Version\YuNian-Setup-$Version.exe"
}

function Resolve-BuildPath([string]$Path) {
  if ([IO.Path]::IsPathRooted($Path)) {
    return [IO.Path]::GetFullPath($Path)
  }
  return [IO.Path]::GetFullPath((Join-Path $root $Path))
}

$unpacked = Resolve-BuildPath $ElectronUnpacked
$outputFull = Resolve-BuildPath $Output

if (!(Test-Path (Join-Path $unpacked 'YuNian.exe'))) {
  $temporaryUnpacked = Join-Path $env:TEMP "lianyu-electron-release\v$Version\win-unpacked"
  if (Test-Path (Join-Path $temporaryUnpacked 'YuNian.exe')) {
    $unpacked = $temporaryUnpacked
  }
}

if (!(Test-Path (Join-Path $unpacked 'YuNian.exe'))) {
  throw "Electron win-unpacked not found: $unpacked. Run npm run electron:build first."
}

function Resolve-Dotnet {
  $cmd = Get-Command dotnet -ErrorAction SilentlyContinue
  if ($cmd -and $cmd.Source) { return $cmd.Source }
  $candidates = @(
    (Join-Path $env:ProgramFiles 'dotnet\dotnet.exe'),
    (Join-Path ${env:ProgramFiles(x86)} 'dotnet\dotnet.exe'),
    (Join-Path $env:LocalAppData 'Microsoft\dotnet\dotnet.exe'),
    (Join-Path $env:USERPROFILE '.dotnet\dotnet.exe')
  )
  foreach ($c in $candidates) {
    if ($c -and (Test-Path -LiteralPath $c)) { return $c }
  }
  throw 'dotnet SDK not found. Install .NET 10 SDK to build the offline installer.'
}

$dotnet = Resolve-Dotnet
New-Item -ItemType Directory -Force -Path $payloadDir,(Split-Path $outputFull) | Out-Null
Remove-Item -LiteralPath $payload -Force -ErrorAction SilentlyContinue
Compress-Archive -Path (Join-Path $unpacked '*') -DestinationPath $payload -CompressionLevel Optimal
& $dotnet publish (Join-Path $project 'LianYu.Installer.csproj') -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:LianYuVersion=$Version
$published = Join-Path $project "bin\Release\net10.0-windows\win-x64\publish\YuNian-Setup.exe"
Copy-Item -LiteralPath $published -Destination $outputFull -Force
Write-Host "Offline installer written: $outputFull"
