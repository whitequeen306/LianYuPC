/**
 * OCR 兜底脚本（以**子进程**方式运行，避免污染常驻 actor 的作用域）。
 *
 * 为什么需要它：Chromium/CEF 应用（网易云音乐、部分 Electron 应用）不向 UIA/MSAA
 * 暴露无障碍树（真机实测：UIA 0 个元素、MSAA 0 个、连 --force-renderer-accessibility
 * 也只有 4 个空节点）。此时唯一能拿到「文字 + 位置」的离线手段就是系统自带 OCR。
 *
 * 关键实现点（都是踩出来的）：
 *  1. 用 **PrintWindow(PW_RENDERFULLCONTENT=2)** 渲染窗口自身内容 —— 不受任何置顶窗口遮挡
 *     （用 CopyFromScreen 会被置顶窗口盖住，截到别人的像素）；
 *  2. 目标是 **hwnd** 而不是"当前前台窗口" —— 子进程的控制台一抢焦点就会截错；
 *  3. 结果以 **UTF-8 JSON 文件** 回传 —— PowerShell 5.1 的控制台输出走本地代码页，中文会乱码；
 *  4. 脚本里不出现反引号（它会被 JS 模板字符串吃掉）。
 */

export const OCR_SCRIPTS_FILENAME = 'ocr-fallback.ps1'

export const OCR_SCRIPTS_SOURCE = String.raw`param([long]$Hwnd = 0, [string]$OutFile = '')
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Runtime.WindowsRuntime
Add-Type -TypeDefinition @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public static class LianYuOcrNative {
  [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr h, out RECT r);
  [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr h, IntPtr hdc, uint flags);
  [DllImport("user32.dll")] public static extern IntPtr GetForegroundWindow();
}
"@
$asTaskName = 'IAsyncOperation' + [char]96 + '1'
$gen = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
    $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq $asTaskName
  })[0]
function Await($t, $ty) { $nt = $gen.MakeGenericMethod($ty).Invoke($null, @($t)); $nt.Wait(-1) | Out-Null; $nt.Result }
[Windows.Storage.Streams.InMemoryRandomAccessStream, Windows.Storage.Streams, ContentType = WindowsRuntime] | Out-Null
[Windows.Graphics.Imaging.BitmapDecoder, Windows.Graphics.Imaging, ContentType = WindowsRuntime] | Out-Null
[Windows.Media.Ocr.OcrEngine, Windows.Foundation, ContentType = WindowsRuntime] | Out-Null

$h = [IntPtr]$Hwnd
if ($h -eq [IntPtr]::Zero) { $h = [LianYuOcrNative]::GetForegroundWindow() }
if ($h -eq [IntPtr]::Zero) { throw 'no_hwnd' }
$rect = New-Object 'LianYuOcrNative+RECT'
[void][LianYuOcrNative]::GetWindowRect($h, [ref]$rect)
$w = $rect.Right - $rect.Left
$hh = $rect.Bottom - $rect.Top
if ($w -le 16 -or $hh -le 16) { throw 'bad_rect' }

$bmp = New-Object System.Drawing.Bitmap $w, $hh
$g = [System.Drawing.Graphics]::FromImage($bmp)
$hdc = $g.GetHdc()
try { [void][LianYuOcrNative]::PrintWindow($h, $hdc, 2) } catch { }
$g.ReleaseHdc($hdc)
$g.Dispose()

$ms = New-Object System.IO.MemoryStream
$bmp.Save($ms, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()
$ras = New-Object Windows.Storage.Streams.InMemoryRandomAccessStream
$wr = New-Object Windows.Storage.Streams.DataWriter($ras)
$wr.WriteBytes($ms.ToArray())
Await ($wr.StoreAsync()) ([UInt32]) | Out-Null
[void]$wr.DetachStream()
$ras.Seek(0)
$dec = Await ([Windows.Graphics.Imaging.BitmapDecoder]::CreateAsync($ras)) ([Windows.Graphics.Imaging.BitmapDecoder])
$sb = Await ($dec.GetSoftwareBitmapAsync()) ([Windows.Graphics.Imaging.SoftwareBitmap])
$engine = [Windows.Media.Ocr.OcrEngine]::TryCreateFromUserProfileLanguages()
if ($null -eq $engine) { throw 'no_ocr_engine' }
$res = Await ($engine.RecognizeAsync($sb)) ([Windows.Media.Ocr.OcrResult])

$items = New-Object System.Collections.ArrayList
foreach ($line in @($res.Lines)) {
  $ws = @($line.Words)
  if ($ws.Count -eq 0) { continue }
  $txt = (([string]$line.Text) -replace '\s+', ' ').Trim()
  if ($txt.Length -lt 1) { continue }
  $f = $ws[0].BoundingRect
  $l = $ws[$ws.Count - 1].BoundingRect
  [void]$items.Add([pscustomobject]@{
      label = $txt
      x = [int]($f.X + $rect.Left)
      y = [int]($f.Y + $rect.Top)
      w = [int]($l.X + $l.Width - $f.X)
      h = [int]$f.Height
    })
  if ($items.Count -ge 120) { break }
}
$payload = [pscustomobject]@{
  ok = $true
  window = @{ left = $rect.Left; top = $rect.Top; w = $w; h = $hh }
  items = $items
}
$json = $payload | ConvertTo-Json -Compress -Depth 5
if ([string]::IsNullOrWhiteSpace($OutFile)) {
  [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
  Write-Output $json
} else {
  [System.IO.File]::WriteAllText($OutFile, $json, (New-Object System.Text.UTF8Encoding($false)))
  Write-Output 'OCR_OK'
}
`
