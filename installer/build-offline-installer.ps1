param(
  [string]$Version = "0.2.363",
  [string]$ElectronUnpacked = "",
  [string]$Output = ""
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$project = Join-Path $root 'LianYu.Installer'
$payloadDir = Join-Path $project 'Payload'
$payload = Join-Path $payloadDir 'LianYu-payload.zip'

if ([string]::IsNullOrWhiteSpace($ElectronUnpacked)) {
  $ElectronUnpacked = "..\frontend\release\v$Version\win-unpacked"
}
if ([string]::IsNullOrWhiteSpace($Output)) {
  $Output = "..\frontend\release\v$Version\LianYu-Setup-$Version.exe"
}

function Resolve-BuildPath([string]$Path) {
  if ([IO.Path]::IsPathRooted($Path)) {
    return [IO.Path]::GetFullPath($Path)
  }
  return [IO.Path]::GetFullPath((Join-Path $root $Path))
}

$unpacked = Resolve-BuildPath $ElectronUnpacked
$outputFull = Resolve-BuildPath $Output

if (!(Test-Path (Join-Path $unpacked 'LianYu.exe'))) {
  $temporaryUnpacked = Join-Path $env:TEMP "lianyu-electron-release\v$Version\win-unpacked"
  if (Test-Path (Join-Path $temporaryUnpacked 'LianYu.exe')) {
    $unpacked = $temporaryUnpacked
  }
}

if (!(Test-Path (Join-Path $unpacked 'LianYu.exe'))) {
  throw "找不到 Electron win-unpacked：$unpacked。请先运行 npm run electron:build。"
}
New-Item -ItemType Directory -Force -Path $payloadDir,(Split-Path $outputFull) | Out-Null
Remove-Item -LiteralPath $payload -Force -ErrorAction SilentlyContinue
Compress-Archive -Path (Join-Path $unpacked '*') -DestinationPath $payload -CompressionLevel Optimal
dotnet publish (Join-Path $project 'LianYu.Installer.csproj') -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:LianYuVersion=$Version
$published = Join-Path $project "bin\Release\net10.0-windows\win-x64\publish\LianYu-Setup.exe"
Copy-Item -LiteralPath $published -Destination $outputFull -Force
Write-Host "离线安装包已生成：$outputFull"
