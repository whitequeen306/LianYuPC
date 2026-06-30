; NSIS hooks for LianYu — close running app before upgrade so files are not locked.
!macro customInit
  nsExec::ExecToStack 'taskkill /F /IM "LianYu.exe" /T'
  Pop $0
  Pop $1
  Sleep 800
  ; In-place upgrades to the same folder may refresh app.asar but leave an older electron shell.
  nsExec::ExecToStack 'powershell -NoProfile -ExecutionPolicy Bypass -Command "foreach ($$k in Get-ChildItem ''HKCU:\Software\Microsoft\Windows\CurrentVersion\Uninstall'') { $$p = Get-ItemProperty $$k.PSPath -ErrorAction SilentlyContinue; if ($$null -eq $$p -or $$p.DisplayName -notlike ''LianYu*'') { continue }; if ($$p.UninstallString -match ''\"(.+)\\Uninstall LianYu.exe\"'') { $$exe = Join-Path $$matches[1] ''LianYu.exe''; if (Test-Path -LiteralPath $$exe) { Remove-Item -LiteralPath $$exe -Force -ErrorAction SilentlyContinue } } }"'
  Pop $0
  Pop $1
!macroend

; 卸载时处理恋语装的 napcat：检测 napcat 目录存在 → 杀加载其 hook 的 bot QQ.exe
; （释放 DLL 占用），让 deleteAppDataOnUninstall=true 的模板 RMDir 能删净 napcat。
;
; 只检测恋语 userData 下的 napcat（恋语自管托管下载的），天然不碰用户自己装在别处
; 的 napcat。用户没启用自管托管时 napcat 目录不存在，跳过杀进程。userData 整体删除
; 交给 electron-builder 模板（uninstaller.nsh 删 $APPDATA\<app package name>，时机在
; 删安装文件之后、句柄已释放，比本宏内 RMDir 更可靠——本宏在删文件之前执行，太早）。
!macro customUnInstall
  ; 1. 保险杀主进程（卸载是独立流程，customInit 只在安装前跑）
  nsExec::ExecToStack 'taskkill /F /IM "LianYu.exe" /T'
  Pop $0
  Pop $1
  ; 2. 检测恋语是否装了 napcat（userData\napcat 存在 = 自管托管下载过）。
  ;    /allusers 安装下 NSIS 默认 shell context=all，$APPDATA 指向 C:\ProgramData 而非
  ;    当前用户；模板在删 appData 前才 SetShellVarContext current（在本宏之后执行），
  ;    故此处若用 $APPDATA 会检测 C:\ProgramData\lianyu-pc\napcat 误判不存在而跳过。
  ;    改用 ReadEnvStr 读环境变量 APPDATA——不受 shell context 影响，elevated 下仍是
  ;    当前用户的 %APPDATA%（已验证 = C:\Users\<user>\AppData\Roaming）。
  ReadEnvStr $R0 "APPDATA"
  IfFileExists "$R0\lianyu-pc\napcat" 0 skipNapcatKill
    ; 恋语装的 napcat 通过 launcher 注入 QQ.exe 运行，launcher 以 code=0 退出后 bot
    ; QQ.exe 成孤儿进程，加载着 napcat\NapCatWinBootHook.dll。该 QQ.exe 是恋语拉起
    ; 的 bot，加载了恋语 napcat 模块；用户自己的 QQ 客户端不加载此模块，不会被误杀。
    ; 卸载时 bot QQ 仍锁着 DLL，须先停它，模板 RMDir 才能删净 napcat。
    ;
    ; 杀进程脚本写到 $TEMP 再用 64 位 powershell -File 执行：内联 -Command 经
    ; nsExec（cmd /c）传给 powershell 时，引号嵌套与 | 管道会被 cmd 解析破坏
    ; （0.2.200 实测退出码1、Out-File 第一行都没执行）。FileWrite 写出的 .ps1
    ; 内容不经 shell 解析，-File 模式也不重新解析命令行引号，最稳。
    FileOpen $1 "$TEMP\lianyu_kill_bot.ps1" w
    FileWrite $1 "$$b = Get-Process QQ -ErrorAction SilentlyContinue | Where-Object { try { $$_.Modules.FileName -match 'napcat' } catch { $$false } }$\r$\n"
    FileWrite $1 "$$b | Stop-Process -Force -ErrorAction SilentlyContinue$\r$\n"
    FileClose $1
    ; 64 位 powershell：NSIS 是 32 位，默认 powershell 被 WoW64 重定向到 32 位，
    ; 访问 64 位 QQ.exe 的 .Modules 会失败；SysNative 绕过重定向跑 64 位（已验证可见）。
    nsExec::ExecToStack '$WINDIR\SysNative\WindowsPowerShell\v1.0\powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$TEMP\lianyu_kill_bot.ps1"'
    Pop $0
    Pop $1
    Sleep 2000
    Delete "$TEMP\lianyu_kill_bot.ps1"
  skipNapcatKill:
!macroend
