param(
  [string]$Version = "",
  [string]$Output = ""
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$project = Join-Path $root 'LianYu.Installer'
$payloadDir = Join-Path $project 'Payload'
$enginePayload = Join-Path $payloadDir 'YuNian-Engine.exe'
$legacyPayload = Join-Path $payloadDir 'LianYu-payload.zip'

if ([string]::IsNullOrWhiteSpace($Version)) {
  # 版本单一来源是 frontend/package.json（发版链路总是显式传 -Version）
  throw "Version is required. Pass -Version (from frontend/package.json)."
}

function Resolve-BuildPath([string]$Path) {
  if ([IO.Path]::IsPathRooted($Path)) { return [IO.Path]::GetFullPath($Path) }
  return [IO.Path]::GetFullPath((Join-Path $root $Path))
}

if ([string]::IsNullOrWhiteSpace($Output)) {
  $Output = "..\frontend\release\v$Version\YuNian-Setup-$Version.exe"
}
$outputFull = Resolve-BuildPath $Output
$engineSource = Join-Path (Split-Path $outputFull) "YuNian-Setup-$Version.exe"

# ────────────────────────────────────────────────────────────────
#  内嵌对象是 electron-builder 打好的 NSIS 安装器（安装引擎），不是原始负载 zip。
#  所以顺序必须是：先 electron-pack 出 NSIS 包 → 再跑本脚本把它内嵌进 WPF 壳。
# ────────────────────────────────────────────────────────────────
if (!(Test-Path -LiteralPath $engineSource)) {
  throw "找不到安装引擎 $engineSource —— 先跑 node frontend/scripts/electron-pack.mjs 产出 NSIS 包。"
}

$engineBytes = (Get-Item -LiteralPath $engineSource).Length
$engineMB = [math]::Round($engineBytes / 1MB, 1)
Write-Host "engine source: $engineSource ($engineMB MB)"

# 防递归把上一个 WPF 产物再内嵌一遍（会变成 ~340MB 且永远没人发现）。
# 不能只看大小：品牌壳约 170MB、NSIS 引擎约 166MB，两者太接近。
# 直接验证「它是不是 NSIS 安装器」。
# 注意：NullsoftInst 标记在文件**前 2MB 内**，但不在前 64KB（实测 2026-10-21），
# 所以探测窗口取 2MB；品牌壳的特征则是末尾 2MB 里有 .deps.json / YuNian-Setup.dll。
$probeStream = [System.IO.File]::OpenRead($engineSource)
try {
  $probeBuffer = New-Object byte[] (2 * 1024 * 1024)
  $probeRead = $probeStream.Read($probeBuffer, 0, $probeBuffer.Length)
} finally {
  $probeStream.Dispose()
}
$probe = [System.Text.Encoding]::ASCII.GetString($probeBuffer, 0, $probeRead)
if ($probe -notmatch 'NullsoftInst') {
  throw ("$engineSource 看起来不是 NSIS 安装器（前 2MB 里没有 NullsoftInst 签名）。" +
         "很可能上一次已经把品牌壳写到了同一个路径 —— 请先重新跑 electron-pack，再运行本脚本。")
}
if ($engineBytes -lt 100MB) {
  throw "$engineSource 只有 $engineMB MB，不像完整的 NSIS 安装引擎。"
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
New-Item -ItemType Directory -Force -Path $payloadDir | Out-Null

# 先把它挪出输出路径：publish 与 Copy 会覆盖 $outputFull，而它就是 $engineSource。
Copy-Item -LiteralPath $engineSource -Destination $enginePayload -Force
if (Test-Path -LiteralPath $legacyPayload) {
  Remove-Item -LiteralPath $legacyPayload -Force
  Write-Host "removed legacy payload zip (no longer embedded)"
}

# 关键：publish 用 & 调用不会因为 MSBuild 报错而抛异常，$ErrorActionPreference='Stop' 也管不到，
# 于是编译失败时脚本会「成功地」把上一次的旧 publish 产物复制出去 —— 产物 sha512 一模一样、
# 看着一切正常，实际发的是一个旧包（2026-10-21 就这样白跑了一轮）。必须显式查退出码。
$published = Join-Path $project "bin\Release\net10.0-windows\win-x64\publish\YuNian-Setup.exe"
Remove-Item -LiteralPath $published -Force -ErrorAction SilentlyContinue
& $dotnet publish (Join-Path $project 'LianYu.Installer.csproj') -c Release -r win-x64 --self-contained true -p:PublishSingleFile=true -p:IncludeNativeLibrariesForSelfExtract=true -p:LianYuVersion=$Version
if ($LASTEXITCODE -ne 0) {
  throw "dotnet publish 失败（exit $LASTEXITCODE），拒绝复制可能已过期的产物。"
}
if (!(Test-Path -LiteralPath $published)) {
  throw "publish 未产出 $published —— 先删掉的旧文件说明这次没真正编译成功。"
}

Copy-Item -LiteralPath $published -Destination $outputFull -Force
$finalMB = [math]::Round((Get-Item -LiteralPath $outputFull).Length / 1MB, 1)
Write-Host "branded installer written: $outputFull ($finalMB MB)"

# 仓库目录若带 Low 完整性标签，产物会继承它，用户双击会被 Windows 拦。
# 详见 frontend/scripts/electron-pack.mjs 里的说明。
try {
  & icacls $outputFull /setintegritylevel Medium | Out-Null
  Write-Host "integrity level raised to Medium"
} catch {
  Write-Warning "无法提升完整性级别（继续）：$_"
}
