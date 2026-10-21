# Pre-publish gate: can a freshly built installer actually be launched on this box?
#
# Three possible outcomes:
#   PASS     installer window appears, no OS interception
#   BLOCKED  Windows SmartScreen ("Windows protected your PC") intercepted it
#   DIALOG   legacy "Open File - Security Warning" (unknown publisher) appeared
#
# Why both checks: the 2026-10-21 gate only looked for the legacy #32770 dialog and
# therefore reported "no warning" while SmartScreen was silently blocking the app.
# SmartScreen renders in a separate process (Smartscreen.exe), so process presence
# is the reliable signal; the legacy dialog is a window of class #32770.
#
# Nothing is installed: the wizard is closed as soon as it is detected.
# ASCII only: PS 5.1 misreads UTF-8 without BOM.
param(
  [Parameter(Mandatory = $true)][string]$Exe,
  [int]$TimeoutSec = 40
)

Add-Type -Namespace N -Name U -MemberDefinition @'
[DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
public static extern IntPtr FindWindow(string c, string t);
[DllImport("user32.dll", SetLastError = true)]
public static extern bool PostMessage(IntPtr h, uint m, IntPtr w, IntPtr l);
'@

function Close-Window {
  param([IntPtr]$h)
  if ($h -ne [IntPtr]::Zero) { [void][N.U]::PostMessage($h, 0x0010, [IntPtr]::Zero, [IntPtr]::Zero) }
}

Get-Process -Name "YuNian-Setup*" -ErrorAction SilentlyContinue | ForEach-Object { $_.Kill() }
Start-Sleep -Milliseconds 800

$sizeMB = [math]::Round((Get-Item $Exe).Length / 1MB, 1)
"exe       = $Exe"
"size      = $sizeMB MB"
"sha256    = " + (Get-FileHash $Exe -Algorithm SHA256).Hash.Substring(0, 16)

$verdict = 'UNKNOWN'
$detail = ''

$sw = [Diagnostics.Stopwatch]::StartNew()
cmd.exe /c "start `"`" `"$Exe`"" | Out-Null

while ($sw.Elapsed.TotalSeconds -lt $TimeoutSec) {
  Start-Sleep -Milliseconds 200

  $ss = Get-Process -Name "Smartscreen" -ErrorAction SilentlyContinue |
        Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
  if ($ss) {
    $verdict = 'BLOCKED'
    $detail = "SmartScreen pid=$($ss.Id) title=[$($ss.MainWindowTitle)]"
    try { $ss.CloseMainWindow() | Out-Null } catch {}
    break
  }

  $dlg = [N.U]::FindWindow("#32770", "Open File - Security Warning")
  if ($dlg -ne [IntPtr]::Zero) {
    $verdict = 'DIALOG'
    $detail = 'legacy unknown-publisher dialog'
    Close-Window -h $dlg
    break
  }

  $p = Get-Process -Name "YuNian-Setup*" -ErrorAction SilentlyContinue |
       Where-Object { $_.MainWindowHandle -ne 0 } | Select-Object -First 1
  if ($p) {
    $verdict = 'PASS'
    $detail = "title=[$($p.MainWindowTitle)] at $([math]::Round($sw.Elapsed.TotalSeconds,1))s"
    break
  }
}

"VERDICT   = $verdict"
"detail    = $detail"

Get-Process -Name "YuNian-Setup*" -ErrorAction SilentlyContinue | ForEach-Object {
  try { $_.CloseMainWindow() | Out-Null } catch {}
}
Start-Sleep -Seconds 2
Get-Process -Name "YuNian-Setup*" -ErrorAction SilentlyContinue | ForEach-Object { $_.Kill() }
Get-Process -Name "Smartscreen" -ErrorAction SilentlyContinue | ForEach-Object {
  try { $_.CloseMainWindow() | Out-Null } catch {}
}
"cleaned up"

if ($verdict -eq 'PASS') { exit 0 } else { exit 2 }
