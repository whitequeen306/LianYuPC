param(
  [string]$WindowTitle = 'LianYu',
  [string]$OutPath,
  [int]$WaitMs = 28000
)

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

Add-Type @"
using System;
using System.Drawing;
using System.Runtime.InteropServices;
using System.Text;
public static class LianYuWinCap {
  public delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);
  [DllImport("user32.dll")] public static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);
  [DllImport("user32.dll")] public static extern int GetWindowText(IntPtr hWnd, StringBuilder lpString, int nMaxCount);
  [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
  [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);
  [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
  public struct RECT { public int Left, Top, Right, Bottom; }
  public static IntPtr FindByTitlePart(string part) {
    IntPtr found = IntPtr.Zero;
    EnumWindows((hWnd, _) => {
      if (!IsWindowVisible(hWnd)) return true;
      var sb = new StringBuilder(512);
      GetWindowText(hWnd, sb, sb.Capacity);
      if (sb.ToString().IndexOf(part, StringComparison.OrdinalIgnoreCase) >= 0) {
        found = hWnd;
        return false;
      }
      return true;
    }, IntPtr.Zero);
    return found;
  }
  public static void Capture(IntPtr hWnd, string path) {
    SetForegroundWindow(hWnd);
    System.Threading.Thread.Sleep(800);
    RECT r; GetWindowRect(hWnd, out r);
    int w = Math.Max(1, r.Right - r.Left);
    int h = Math.Max(1, r.Bottom - r.Top);
    using (var bmp = new Bitmap(w, h)) {
      using (var g = Graphics.FromImage(bmp)) {
        g.CopyFromScreen(r.Left, r.Top, 0, 0, new Size(w, h));
      }
      bmp.Save(path, System.Drawing.Imaging.ImageFormat.Png);
    }
  }
}
"@

Start-Sleep -Milliseconds $WaitMs
$hwnd = [LianYuWinCap]::FindByTitlePart($WindowTitle)
if ($hwnd -eq [IntPtr]::Zero) {
  Write-Error "Window not found containing title: $WindowTitle"
  exit 2
}
[LianYuWinCap]::Capture($hwnd, $OutPath)
Write-Output "Captured: $OutPath"
