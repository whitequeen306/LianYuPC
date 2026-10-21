# Which installers trigger Windows' "Open File - Security Warning" on THIS machine?
# Same folder for all cases, so the only variable is the artifact itself.
# The dialog is auto-dismissed (WM_CLOSE = cancel) so nothing is left on screen.
# ASCII only on purpose: PS 5.1 misreads UTF-8 without BOM.
$ErrorActionPreference = 'Continue'

Add-Type -Namespace Native -Name Win -MemberDefinition @'
[DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
public static extern IntPtr FindWindow(string lpClassName, string lpWindowName);
[DllImport("user32.dll", SetLastError = true)]
public static extern bool PostMessage(IntPtr hWnd, uint Msg, IntPtr wParam, IntPtr lParam);
'@

function Find-SecurityDialog {
  return [Native.Win]::FindWindow("#32770", "Open File - Security Warning")
}

function Close-Dialog {
  param([IntPtr]$h)
  [void][Native.Win]::PostMessage($h, 0x0010, [IntPtr]::Zero, [IntPtr]::Zero)
}

function Test-Installer {
  param([string]$Label, [string]$ExePath, [int]$Seconds = 25)

  Get-Process -Name "YuNian-Setup*" -ErrorAction SilentlyContinue | ForEach-Object { $_.Kill() }
  Start-Sleep -Milliseconds 800

  $sizeMB = [math]::Round((Get-Item $ExePath).Length / 1MB, 1)
  "[$Label]  $sizeMB MB"
  "   $ExePath"

  $sw = [Diagnostics.Stopwatch]::StartNew()
  cmd.exe /c "start `"`" `"$ExePath`"" | Out-Null

  $promptedAt = $null
  $startedAt = $null
  $winAt = $null
  $dialogSeen = $false

  while ($sw.Elapsed.TotalSeconds -lt $Seconds) {
    Start-Sleep -Milliseconds 150
    $t = [math]::Round($sw.Elapsed.TotalSeconds, 1)

    $dlg = Find-SecurityDialog
    if ($dlg -ne [IntPtr]::Zero -and -not $dialogSeen) {
      $dialogSeen = $true
      $promptedAt = $t
      "   >>> [t=${t}s] SECURITY DIALOG appeared (auto-cancelling)"
      Close-Dialog -h $dlg
    }

    $p = Get-Process -Name "YuNian-Setup*" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($p -and -not $startedAt) {
      $startedAt = $t
      "   >>> [t=${t}s] process appeared pid=$($p.Id)"
    }
    if ($p) {
      $p.Refresh()
      if ($p.MainWindowHandle -ne 0 -and -not $winAt) {
        $winAt = $t
        "   >>> [t=${t}s] window appeared title=[$($p.MainWindowTitle)]"
        break
      }
    }
  }

  if ($dialogSeen) { $verdict = 'PROMPTED' } else { $verdict = 'NO_PROMPT' }
  if ($winAt) { $windowText = "$winAt s" }
  elseif ($startedAt) { $windowText = "process at $startedAt s, no window" }
  else { $windowText = "none" }
  "   RESULT = $verdict   window=$windowText"

  Get-Process -Name "YuNian-Setup*" -ErrorAction SilentlyContinue | ForEach-Object {
    try { $_.CloseMainWindow() | Out-Null } catch {}
  }
  Start-Sleep -Milliseconds 800
  Get-Process -Name "YuNian-Setup*" -ErrorAction SilentlyContinue | ForEach-Object { $_.Kill() }
  Start-Sleep -Milliseconds 500
  ""
}

Write-Host "=== same folder, three artifacts ==="
Write-Host ""

Test-Installer -Label "0.2.367  165MB  older build" -ExePath "C:\Users\hp\Desktop\LianYu-PC\frontend\release\v0.2.367\YuNian-Setup-0.2.367.exe"
Test-Installer -Label "0.2.368  282MB  first big build" -ExePath "C:\Users\hp\Desktop\LianYu-PC\frontend\release\v0.2.368\YuNian-Setup-0.2.368.exe"
Test-Installer -Label "0.3.1    282MB  shipped" -ExePath "C:\Users\hp\Desktop\LianYu-PC\frontend\release\v0.3.1\YuNian-Setup-0.3.1.exe"

Write-Host "MATRIX_DONE"
