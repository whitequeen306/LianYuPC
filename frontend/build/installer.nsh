; ===== 予念安装程序 — NSIS hooks =====
; hooks：安装前杀进程、更新入口强制显示安装界面、卸载时清理 napcat
; 安装界面使用 electron-builder 默认原生 NSIS 向导

; ================================================================
;  MUI 完成页 —— 自定义标题与正文（避免文字偏中）
; ================================================================
!define MUI_FINISHPAGE_TITLE "予念安装完成"
!define MUI_FINISHPAGE_TEXT "感谢您选择予念，点击“完成”退出安装向导。"

; ================================================================
;  NSIS hooks — 安装前杀进程 / 更新入口强制显示安装界面 / 卸载时清理 napcat
; ================================================================
!macro customInit
  ; 带 /LIANYU_EMBEDDED 说明是被 WPF 安装器壳内嵌调用的：那边自己负责界面与进度，
  ; 这里必须**老老实实静默**，否则会自己弹一个向导出来盖住 WPF 窗口。
  ; 其它情况（用户直接双击 NSIS 包、或走更新入口）保持旧行为：强制显示界面。
  ${GetParameters} $R0
  ${GetOptions} $R0 "/LIANYU_EMBEDDED" $R1
  ${If} ${Errors}
    IfSilent 0 doneForceVisibleInstall
      SetSilent normal
    doneForceVisibleInstall:
  ${EndIf}
  nsExec::ExecToStack 'taskkill /F /IM "YuNian.exe" /T'
  Pop $0
  Pop $1
  Sleep 800
  ; In-place upgrades to the same folder may refresh app.asar but leave an older electron shell.
  nsExec::ExecToStack 'powershell -NoProfile -ExecutionPolicy Bypass -Command "foreach ($$k in Get-ChildItem ''HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall'') { $$p = Get-ItemProperty $$k.PSPath -ErrorAction SilentlyContinue; if ($$null -eq $$p -or $$p.DisplayName -notlike ''YuNian*'') { continue }; if ($$p.UninstallString -match ''\"(.+)\\Uninstall YuNian.exe\"'') { $$exe = Join-Path $$matches[1] ''YuNian.exe''; if (Test-Path -LiteralPath $$exe) { Remove-Item -LiteralPath $$exe -Force -ErrorAction SilentlyContinue } } }"'
  Pop $0
  Pop $1
!macroend

; 卸载时处理予念装的 napcat
!macro customUnInstall
  nsExec::ExecToStack 'taskkill /F /IM "YuNian.exe" /T'
  Pop $0
  Pop $1
  ReadEnvStr $R0 "APPDATA"
  IfFileExists "$R0\lianyu-pc\napcat" 0 skipNapcatKill
    FileOpen $1 "$TEMP\lianyu_kill_bot.ps1" w
    FileWrite $1 "$$b = Get-Process QQ -ErrorAction SilentlyContinue | Where-Object { try { $$_.Modules.FileName -match 'napcat' } catch { $$false } }$\r$\n"
    FileWrite $1 "$$b | Stop-Process -Force -ErrorAction SilentlyContinue$\r$\n"
    FileClose $1
    nsExec::ExecToStack '$WINDIR\SysNative\WindowsPowerShell\v1.0\powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$TEMP\lianyu_kill_bot.ps1"'
    Pop $0
    Pop $1
    Sleep 2000
    Delete "$TEMP\lianyu_kill_bot.ps1"
  skipNapcatKill:
!macroend
