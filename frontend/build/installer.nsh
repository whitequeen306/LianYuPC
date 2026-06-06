; NSIS hooks for LianYu — close running app before upgrade so files are not locked.
!macro customInit
  nsExec::ExecToStack 'taskkill /F /IM "LianYu.exe" /T'
  Pop $0
  Pop $1
  Sleep 500
!macroend
