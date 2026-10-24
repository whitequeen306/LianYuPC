/**
 * Windows 执行器（常驻 PowerShell actor）。
 *
 * ## 为什么不是 koffi / 原生 addon
 *
 * 方案评审时的首选是 `koffi`（prebuilt N-API，直接调 user32）。但在 Electron 42 + asar +
 * electronFuses 的打包形态下，原生 `.node` 需要 `asarUnpack` + 真机包验证才能确认可用，
 * 这一步在开发机上做不了。按计划的退路顺序，P1 先用**常驻 PowerShell actor**：
 *
 * - 零原生依赖、零编译，`powershell.exe` 在所有受支持的 Windows 上都有；
 * - 只在启动时 `Add-Type` 编译一次 P/Invoke，之后每条命令走 stdin/stdout（实测 10–30ms）；
 * - 进程崩溃/被杀时可重建，不会把主进程带走。
 *
 * 待 P1 验收项「koffi 在打包产物中可用」通过后，再把它接成可选后端
 * （`input.js` 已把后端隔离在这一层）。
 *
 * ## 协议（避免 JSON 解析依赖）
 *
 * 请求：`{id}\u0001{op}\u0001{base64 arg}…`（PowerShell 5.1 没有 System.Text.Json，不用 JSON 传输）
 * 响应：一行 JSON（由 C# 手工拼，转义只做必要的 \\ " 与换行）
 *
 * ## 安全边界
 *
 * - 脚本文本是**固定的**：actor 只认 `op` 白名单，不接受任意命令/脚本；
 * - 所有自由文本参数走 base64，不参与任何字符串拼接求值；
 * - 键盘只认白名单键名（`KEY_MAP`），未知键直接报错而不是「猜一个 VK」。
 */

import { spawn as nodeSpawn } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'

export const ACTOR_SEPARATOR = '\u0001'

export const ACTOR_OPS = [
  'foreground', 'windows', 'focus', 'click', 'move', 'key', 'type', 'scroll', 'media', 'volume',
  // UIA 元素收割在 PowerShell 侧单独实现（见 ACTOR_SCRIPT 尾部）：
  // 这样 UIAutomation 程序集缺失时只降级这一个 op，不会让整个 actor 编译失败。
  'elements',
]

/** 允许的按键名 → Windows 虚拟键码（与 `policy.js` 的白名单一致） */
export const KEY_MAP = {
  ctrl: 0x11, control: 0x11, alt: 0x12, shift: 0x10, win: 0x5b, meta: 0x5b,
  enter: 0x0d, return: 0x0d, esc: 0x1b, escape: 0x1b, tab: 0x09, space: 0x20,
  backspace: 0x08, delete: 0x2e, del: 0x2e, insert: 0x2d, home: 0x24, end: 0x23,
  pageup: 0x21, pagedown: 0x22, up: 0x26, down: 0x28, left: 0x25, right: 0x27,
  f1: 0x70, f2: 0x71, f3: 0x72, f4: 0x73, f5: 0x74, f6: 0x75,
  f7: 0x76, f8: 0x77, f9: 0x78, f10: 0x79, f11: 0x7a, f12: 0x7b,
  capslock: 0x14, minus: 0xbd, plus: 0xbb, comma: 0xbc, period: 0xbe, slash: 0xbf,
}

export function resolveVirtualKey(name) {
  const key = String(name || '').trim().toLowerCase()
  if (!key) return null
  if (KEY_MAP[key] != null) return KEY_MAP[key]
  if (/^[a-z]$/.test(key)) return key.toUpperCase().charCodeAt(0)
  if (/^[0-9]$/.test(key)) return key.charCodeAt(0)
  return null
}

/** 把按键序列解析成 VK 列表；任一键不认识就整体失败（不猜）。 */
export function resolveKeySequence(keys) {
  if (!Array.isArray(keys) || keys.length === 0) {
    throw new Error('keys 不能为空')
  }
  return keys.map((name) => {
    const vk = resolveVirtualKey(name)
    if (vk == null) throw new Error(`不支持的按键：${name}`)
    return vk
  })
}

function encodeArg(value) {
  return Buffer.from(String(value ?? ''), 'utf8').toString('base64')
}

function decodeArg(value) {
  if (!value) return ''
  return Buffer.from(value, 'base64').toString('utf8')
}

/** 生成 actor 的命令行（只读 op 可在测试里断言）。 */
export function buildActorCommand(id, op, args = []) {
  if (!ACTOR_OPS.includes(op)) {
    throw new Error(`不支持的 op：${op}`)
  }
  return [id, op, ...args.map(encodeArg)].join(ACTOR_SEPARATOR)
}

/**
 * C# 侧实现（经 `Add-Type` 编译一次）。
 *
 * 注意：这里是**单引号 here-string**（`@' … '@`），PowerShell 不做变量插值，
 * 因此 C# 里的 `$` 与花括号都安全。
 */
const ACTOR_SCRIPT = String.raw`
$ErrorActionPreference = 'Stop'
try { [void][User32Dpi] } catch {}
Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;

public static class LianYuDesktop
{
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
    [StructLayout(LayoutKind.Sequential)] public struct POINT { public int X, Y; }
    [StructLayout(LayoutKind.Sequential)] public struct MOUSEINPUT { public int dx, dy; public uint mouseData, dwFlags, time; public IntPtr dwExtraInfo; }
    [StructLayout(LayoutKind.Sequential)] public struct KEYBDINPUT { public ushort wVk, wScan; public uint dwFlags, time; public IntPtr dwExtraInfo; }
    [StructLayout(LayoutKind.Explicit)] public struct INPUTUNION { [FieldOffset(0)] public MOUSEINPUT mi; [FieldOffset(0)] public KEYBDINPUT ki; }
    [StructLayout(LayoutKind.Sequential)] public struct INPUT { public uint type; public INPUTUNION u; }

    [DllImport("user32.dll")] static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] static extern void mouse_event(uint flags, int dx, int dy, uint data, UIntPtr extra);
    [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] static extern bool BringWindowToTop(IntPtr hWnd);
    [DllImport("user32.dll")] static extern bool ShowWindow(IntPtr hWnd, int cmd);
    [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll")] static extern bool IsIconic(IntPtr hWnd);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetWindowText(IntPtr hWnd, StringBuilder text, int count);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetWindowTextLength(IntPtr hWnd);
    [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr hWnd, out RECT rect);
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
    [DllImport("user32.dll")] static extern bool AttachThreadInput(uint attach, uint attachTo, bool fAttach);
    [DllImport("kernel32.dll")] static extern uint GetCurrentThreadId();
    [DllImport("user32.dll", SetLastError = true)] static extern uint SendInput(uint count, INPUT[] inputs, int size);
    [DllImport("user32.dll")] static extern bool SetProcessDPIAware();
    [DllImport("user32.dll")] static extern bool SetProcessDpiAwarenessContext(IntPtr value);

    delegate bool EnumProc(IntPtr hWnd, IntPtr lParam);
    [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc callback, IntPtr lParam);

    const uint KEYEVENTF_KEYUP = 0x0002;
    const uint KEYEVENTF_UNICODE = 0x0004;
    const uint MOUSEEVENTF_LEFTDOWN = 0x0002, MOUSEEVENTF_LEFTUP = 0x0004;
    const uint MOUSEEVENTF_RIGHTDOWN = 0x0008, MOUSEEVENTF_RIGHTUP = 0x0010;
    const uint MOUSEEVENTF_MIDDLEDOWN = 0x0020, MOUSEEVENTF_MIDDLEUP = 0x0040;
    const uint MOUSEEVENTF_WHEEL = 0x0800;

    static bool ready = false;
    static void EnsureDpi()
    {
        if (ready) return;
        ready = true;
        try { SetProcessDpiAwarenessContext(new IntPtr(-4)); }
        catch { try { SetProcessDPIAware(); } catch { } }
    }

    static string Esc(string s)
    {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.Length + 8);
        foreach (char c in s)
        {
            switch (c)
            {
                case '"': sb.Append("\\\""); break;
                case '\\': sb.Append("\\\\"); break;
                case '\n': sb.Append("\\n"); break;
                case '\r': sb.Append("\\r"); break;
                case '\t': sb.Append("\\t"); break;
                default:
                    if (c < ' ') sb.Append(' ');
                    else sb.Append(c);
                    break;
            }
        }
        return sb.ToString();
    }

    static string ProcName(uint pid)
    {
        try { return Process.GetProcessById((int)pid).ProcessName.ToLowerInvariant() + ".exe"; }
        catch { return ""; }
    }

    static string Title(IntPtr hwnd)
    {
        int len = GetWindowTextLength(hwnd);
        if (len <= 0 || len > 512) return "";
        StringBuilder sb = new StringBuilder(len + 1);
        GetWindowText(hwnd, sb, sb.Capacity);
        return sb.ToString();
    }

    sealed class WinInfo
    {
        public IntPtr Handle;
        public string Title;
        public string Process;
        public int X, Y, W, H;
        public bool Foreground;
    }

    static List<WinInfo> ListWindows()
    {
        IntPtr fg = GetForegroundWindow();
        List<WinInfo> list = new List<WinInfo>();
        EnumWindows(delegate(IntPtr hwnd, IntPtr lparam)
        {
            if (!IsWindowVisible(hwnd)) return true;
            string title = Title(hwnd);
            if (title.Length == 0) return true;
            RECT r;
            if (!GetWindowRect(hwnd, out r)) return true;
            int w = r.Right - r.Left, h = r.Bottom - r.Top;
            if (w < 120 || h < 80) return true;
            uint pid;
            GetWindowThreadProcessId(hwnd, out pid);
            WinInfo info = new WinInfo();
            info.Handle = hwnd;
            info.Title = title;
            info.Process = ProcName(pid);
            info.X = r.Left; info.Y = r.Top; info.W = w; info.H = h;
            info.Foreground = hwnd == fg;
            list.Add(info);
            return true;
        }, IntPtr.Zero);
        list.Sort(delegate(WinInfo a, WinInfo b) { return (b.W * b.H) - (a.W * a.H); });
        if (list.Count > 40) list.RemoveRange(40, list.Count - 40);
        return list;
    }

    static string WindowsJson()
    {
        List<WinInfo> list = ListWindows();
        StringBuilder sb = new StringBuilder();
        sb.Append("\"windows\":[");
        for (int i = 0; i < list.Count; i++)
        {
            WinInfo w = list[i];
            if (i > 0) sb.Append(',');
            sb.Append("{\"hwnd\":").Append(w.Handle.ToInt64())
              .Append(",\"title\":\"").Append(Esc(w.Title))
              .Append("\",\"process\":\"").Append(Esc(w.Process))
              .Append("\",\"x\":").Append(w.X)
              .Append(",\"y\":").Append(w.Y)
              .Append(",\"w\":").Append(w.W)
              .Append(",\"h\":").Append(w.H)
              .Append(",\"fg\":").Append(w.Foreground ? "true" : "false")
              .Append('}');
        }
        sb.Append(']');
        return sb.ToString();
    }

    static string ForegroundJson()
    {
        IntPtr fg = GetForegroundWindow();
        RECT r; GetWindowRect(fg, out r);
        uint pid; GetWindowThreadProcessId(fg, out pid);
        return "\"hwnd\":" + fg.ToInt64()
             + ",\"title\":\"" + Esc(Title(fg)) + "\""
             + ",\"process\":\"" + Esc(ProcName(pid)) + "\""
             + ",\"x\":" + r.Left + ",\"y\":" + r.Top
             + ",\"w\":" + (r.Right - r.Left) + ",\"h\":" + (r.Bottom - r.Top);
    }

    static bool FocusWindow(string titleNeedle, string processNeedle, bool maximize)
    {
        List<WinInfo> list = ListWindows();
        string t = (titleNeedle ?? "").ToLowerInvariant();
        string p = (processNeedle ?? "").ToLowerInvariant();
        WinInfo best = null;
        foreach (WinInfo w in list)
        {
            bool titleHit = t.Length == 0 || w.Title.ToLowerInvariant().Contains(t);
            bool procHit = p.Length == 0 || w.Process.Contains(p);
            if (!titleHit || !procHit) continue;
            if (t.Length > 0 && w.Title.ToLowerInvariant() == t) { best = w; break; }
            if (best == null) best = w;
        }
        if (best == null) return false;
        if (IsIconic(best.Handle)) ShowWindow(best.Handle, 9);
        if (maximize) ShowWindow(best.Handle, 3);
        BringWindowToTop(best.Handle);
        bool ok = SetForegroundWindow(best.Handle);
        if (!ok)
        {
            // Windows 的前台锁：调用方"最近没有输入"时会被拒。
            // 轻敲一次 Alt 就能解除（这是各家自动化工具的标准做法）。
            KeyTap(0x12, false);
            KeyTap(0x12, true);
            ok = SetForegroundWindow(best.Handle);
        }
        if (!ok)
        {
            uint ignored = 0;
            uint fgThread = GetWindowThreadProcessId(GetForegroundWindow(), out ignored);
            uint myThread = GetCurrentThreadId();
            uint pid2 = 0;
            uint targetThread = GetWindowThreadProcessId(best.Handle, out pid2);
            AttachThreadInput(myThread, fgThread, true);
            AttachThreadInput(myThread, targetThread, true);
            ok = SetForegroundWindow(best.Handle);
            AttachThreadInput(myThread, targetThread, false);
            AttachThreadInput(myThread, fgThread, false);
        }
        return ok;
    }

    static void KeyTap(ushort vk, bool up)
    {
        INPUT[] inputs = new INPUT[1];
        inputs[0].type = 1;
        inputs[0].u.ki.wVk = vk;
        inputs[0].u.ki.wScan = 0;
        inputs[0].u.ki.dwFlags = up ? KEYEVENTF_KEYUP : 0;
        SendInput(1, inputs, Marshal.SizeOf(typeof(INPUT)));
    }

    static void SendKeys(string vkList)
    {
        string[] parts = vkList.Split(',');
        List<ushort> vks = new List<ushort>();
        foreach (string s in parts)
        {
            int v;
            if (int.TryParse(s.Trim(), out v) && v > 0 && v < 256) vks.Add((ushort)v);
        }
        foreach (ushort vk in vks) KeyTap(vk, false);
        for (int i = vks.Count - 1; i >= 0; i--) KeyTap(vks[i], true);
    }

    static void SendUnicode(string text)
    {
        foreach (char c in text)
        {
            INPUT[] down = new INPUT[1];
            down[0].type = 1;
            down[0].u.ki.wVk = 0;
            down[0].u.ki.wScan = (ushort)c;
            down[0].u.ki.dwFlags = KEYEVENTF_UNICODE;
            SendInput(1, down, Marshal.SizeOf(typeof(INPUT)));
            INPUT[] up = new INPUT[1];
            up[0].type = 1;
            up[0].u.ki.wVk = 0;
            up[0].u.ki.wScan = (ushort)c;
            up[0].u.ki.dwFlags = KEYEVENTF_UNICODE | KEYEVENTF_KEYUP;
            SendInput(1, up, Marshal.SizeOf(typeof(INPUT)));
        }
    }

    static void Click(int x, int y, string button, int clicks)
    {
        EnsureDpi();
        SetCursorPos(x, y);
        System.Threading.Thread.Sleep(15);
        uint down = MOUSEEVENTF_LEFTDOWN, up = MOUSEEVENTF_LEFTUP;
        if (button == "right") { down = MOUSEEVENTF_RIGHTDOWN; up = MOUSEEVENTF_RIGHTUP; }
        else if (button == "middle") { down = MOUSEEVENTF_MIDDLEDOWN; up = MOUSEEVENTF_MIDDLEUP; }
        for (int i = 0; i < Math.Max(1, clicks); i++)
        {
            mouse_event(down, 0, 0, 0, UIntPtr.Zero);
            System.Threading.Thread.Sleep(10);
            mouse_event(up, 0, 0, 0, UIntPtr.Zero);
            System.Threading.Thread.Sleep(30);
        }
    }

    static void Scroll(int dy)
    {
        mouse_event(MOUSEEVENTF_WHEEL, 0, 0, unchecked((uint)dy), UIntPtr.Zero);
    }

    static void Media(string action)
    {
        ushort vk = 0xB3;
        if (action == "media_play" || action == "media_pause") vk = 0xB3;
        else if (action == "media_next") vk = 0xB0;
        else if (action == "media_prev") vk = 0xB1;
        else if (action == "media_stop") vk = 0xB2;
        else if (action == "volume_mute" || action == "dnd_off" || action == "dnd_on") vk = 0xAD;
        if (vk == 0) throw new ArgumentException("unsupported media action: " + action);
        KeyTap(vk, false);
        KeyTap(vk, true);
    }

    static void VolumeSet(int percent)
    {
        int target = Math.Max(0, Math.Min(100, percent));
        // 先按到底（50 次 VK_VOLUME_DOWN 一定到 0），再按 2% 一档升回去。
        // 精度 ±2%，不依赖 CoreAudio COM —— 换来的是零额外依赖与可预测的行为。
        for (int i = 0; i < 50; i++) { KeyTap(0xAE, false); KeyTap(0xAE, true); }
        int steps = (int)Math.Round(target / 2.0);
        for (int i = 0; i < steps; i++) { KeyTap(0xAF, false); KeyTap(0xAF, true); }
    }

    static string Decode(string b64)
    {
        if (string.IsNullOrEmpty(b64)) return "";
        try { return Encoding.UTF8.GetString(Convert.FromBase64String(b64)); }
        catch { return ""; }
    }

    /// <summary>暴露前台窗口句柄给 PowerShell 侧的 UIA 收割（C# 里不引 UIAutomation）。</summary>
    public static long ForegroundHandle()
    {
        return GetForegroundWindow().ToInt64();
    }

    public static string Handle(string line)
    {        string[] parts = line.Split('\x01');
        string id = parts.Length > 0 ? parts[0] : "";
        string op = parts.Length > 1 ? parts[1] : "";
        string payload;
        try
        {
            switch (op)
            {
                case "foreground":
                    payload = ForegroundJson();
                    break;
                case "windows":
                    payload = WindowsJson();
                    break;
                case "focus":
                    {
                        bool ok = FocusWindow(Decode(parts.Length > 2 ? parts[2] : ""),
                                              Decode(parts.Length > 3 ? parts[3] : ""),
                                              (parts.Length > 4 ? Decode(parts[4]) : "") == "1");
                        if (!ok) return "{\"id\":\"" + Esc(id) + "\",\"ok\":false,\"error\":\"window_not_found\"}";
                        payload = ForegroundJson();
                        break;
                    }
                case "click":
                    {
                        int x = int.Parse(Decode(parts[2]));
                        int y = int.Parse(Decode(parts[3]));
                        string button = Decode(parts[4]);
                        int clicks = parts.Length > 5 ? int.Parse(Decode(parts[5])) : 1;
                        Click(x, y, button, clicks);
                        payload = "\"x\":" + x + ",\"y\":" + y;
                        break;
                    }
                case "move":
                    {
                        int x = int.Parse(Decode(parts[2]));
                        int y = int.Parse(Decode(parts[3]));
                        SetCursorPos(x, y);
                        payload = "\"x\":" + x + ",\"y\":" + y;
                        break;
                    }
                case "key":
                    {
                        SendKeys(Decode(parts[2]));
                        payload = "\"keys\":\"" + Esc(Decode(parts[2])) + "\"";
                        break;
                    }
                case "type":
                    {
                        string text = Decode(parts[2]);
                        SendUnicode(text);
                        if ((parts.Length > 3 ? Decode(parts[3]) : "") == "1")
                        {
                            KeyTap(0x0D, false);
                            KeyTap(0x0D, true);
                        }
                        payload = "\"chars\":" + text.Length;
                        break;
                    }
                case "scroll":
                    {
                        int dy = int.Parse(Decode(parts[2]));
                        Scroll(dy);
                        payload = "\"dy\":" + dy;
                        break;
                    }
                case "media":
                    {
                        string action = Decode(parts[2]);
                        Media(action);
                        payload = "\"action\":\"" + Esc(action) + "\"";
                        break;
                    }
                case "volume":
                    {
                        string action = Decode(parts[2]);
                        if (action == "volume_set")
                        {
                            int percent = int.Parse(Decode(parts[3]));
                            VolumeSet(percent);
                            payload = "\"value\":" + percent;
                        }
                        else
                        {
                            Media(action);
                            payload = "\"action\":\"" + Esc(action) + "\"";
                        }
                        break;
                    }
                default:
                    return "{\"id\":\"" + Esc(id) + "\",\"ok\":false,\"error\":\"unsupported_op\"}";
            }
        }
        catch (Exception ex)
        {
            return "{\"id\":\"" + Esc(id) + "\",\"ok\":false,\"error\":\"" + Esc(ex.GetType().Name + ": " + ex.Message) + "\"}";
        }
        return "{\"id\":\"" + Esc(id) + "\",\"ok\":true," + payload + "}";
    }
}
'@

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$out = [Console]::Out

# ── UIA 元素收割（PowerShell 侧） ───────────────────────────────────────────
# 放在 PS 而不是 C#，是为了让「UIAutomation 程序集缺失」只影响 elements 这一个 op：
# C# 侧一旦引用 UIAutomationClient，缺程序集会导致整个 actor 编译失败 → 全部能力不可用。
function Get-UiaElements {
  Add-Type -AssemblyName UIAutomationClient -ErrorAction Stop
  Add-Type -AssemblyName UIAutomationTypes -ErrorAction Stop
  $hwnd = [LianYuDesktop]::ForegroundHandle()
  if ($hwnd -eq 0) { return @() }
  $root = [System.Windows.Automation.AutomationElement]::FromHandle([IntPtr]$hwnd)
  if ($null -eq $root) { return @() }
  $cond = [System.Windows.Automation.Condition]::TrueCondition
  $stack = New-Object System.Collections.Stack
  $stack.Push($root)
  $found = New-Object System.Collections.ArrayList
  $visited = 0
  # 广度受限的树遍历：真实应用（Chromium/自绘）全树可达上万节点，
  # 不设上限会让「看一眼屏幕」变成几秒级操作。
  while ($stack.Count -gt 0 -and $found.Count -lt 80 -and $visited -lt 600) {
    $el = $stack.Pop()
    $visited++
    try { $kids = $el.FindAll([System.Windows.Automation.TreeScope]::Children, $cond) } catch { $kids = $null }
    if ($kids) { for ($i = 0; $i -lt $kids.Count; $i++) { $stack.Push($kids[$i]) } }
    if ($el -eq $root) { continue }
    try { $name = $el.Current.Name } catch { continue }
    if ([string]::IsNullOrWhiteSpace($name)) { continue }
    try { $rect = $el.Current.BoundingRectangle } catch { continue }
    if ($rect.IsEmpty -or $rect.Width -lt 8 -or $rect.Height -lt 6) { continue }
    try { $offscreen = $el.Current.IsOffscreen } catch { $offscreen = $false }
    if ($offscreen) { continue }
    try { $role = ($el.Current.ControlType.ProgrammaticName -replace '^ControlType\.', '') } catch { $role = '' }
    [void]$found.Add([pscustomobject]@{
      label = $(if ($name.Length -gt 60) { $name.Substring(0, 60) } else { $name })
      role = $role
      x = [int]$rect.Left
      y = [int]$rect.Top
      w = [int]$rect.Width
      h = [int]$rect.Height
    })
  }
  $items = @()
  for ($i = 0; $i -lt $found.Count; $i++) {
    $it = $found[$i]
    $items += [pscustomobject]@{ id = $i + 1; label = $it.label; role = $it.role; x = $it.x; y = $it.y; w = $it.w; h = $it.h }
  }
  return $items
}

# ── MSAA(IAccessible) 兜底 ───────────────────────────────────────────────────
# Chromium / Electron / 自绘界面常常没有 UIA 树：这类应用只在「检测到辅助技术」时
# 才构建无障碍树，而通过 oleacc 走 IAccessible(MSAA) 访问本身就是那个触发信号。
# 产出元素与 UIA 同构（label/role/bbox），所以上层 desk_find / desk_click 不用改。
function Get-MsaaElements {
  $items = New-Object System.Collections.ArrayList
  try { Add-Type -AssemblyName Accessibility -ErrorAction Stop } catch { return $items }
  try {
    if (-not ('LianYuMsaa' -as [type])) {
      Add-Type -Name 'LianYuMsaa' -Namespace '' -MemberDefinition @"
[DllImport("oleacc.dll")]
public static extern int AccessibleObjectFromWindow(IntPtr hwnd, uint dwId, ref Guid riid, [MarshalAs(UnmanagedType.Interface)] out object ppvObject);

[DllImport("user32.dll")]
public static extern IntPtr SendMessage(IntPtr hWnd, uint Msg, IntPtr wParam, IntPtr lParam);

// 给 Chromium 系应用发 WM_GETOBJECT：这正是屏幕阅读器"上线"时 Chromium 用来
// 判断「有辅助技术连接」的信号，收到后它才会异步构建无障碍树（需要等几百毫秒）。
public static void Nudge(IntPtr hwnd) {
    SendMessage(hwnd, 0x003D, IntPtr.Zero, IntPtr.Zero);
}
"@ -ErrorAction Stop
    }
  } catch { return $items }

  $hwnd = [LianYuDesktop]::ForegroundHandle()
  if ($hwnd -eq 0) { return $items }

  $iid = New-Object Guid '618736E0-3C3D-11CF-810C-00AA00389B71'
  $obj = $null
  $hr = -1
  try { $hr = [LianYuMsaa]::AccessibleObjectFromWindow([IntPtr]$hwnd, [uint32]4294967292, [ref]$iid, [ref]$obj) } catch { return $items }
  if ($hr -ne 0 -or $null -eq $obj) { return $items }
  $root = $obj -as [Accessibility.IAccessible]
  if ($null -eq $root) { return $items }

  $queue = New-Object System.Collections.Queue
  $queue.Enqueue(@{ acc = $root; depth = 0 })
  $visited = 0
  while ($queue.Count -gt 0 -and $items.Count -lt 80 -and $visited -lt 500) {
    $node = $queue.Dequeue()
    $acc = $node.acc
    $visited++
    if ($null -eq $acc) { continue }
    $count = 0
    try { $count = $acc.accChildCount } catch { $count = 0 }
    for ($i = 1; $i -le $count; $i++) {
      $child = $null
      try { $child = $acc.get_accChild($i) } catch { $child = $null }
      if ($null -ne $child) {
        if ($node.depth -lt 6) { $queue.Enqueue(@{ acc = ($child -as [Accessibility.IAccessible]); depth = ($node.depth + 1) }) }
        continue
      }
      $label = ''
      try { $label = [string]$acc.get_accName($i) } catch { $label = '' }
      if ([string]::IsNullOrWhiteSpace($label)) { continue }
      $x = 0; $y = 0; $w = 0; $h = 0
      try { $acc.accLocation([ref]$x, [ref]$y, [ref]$w, [ref]$h, $i) } catch { continue }
      if ($w -lt 8 -or $h -lt 6) { continue }
      $role = ''
      try { $role = [string]$acc.get_accRole($i) } catch { $role = '' }
      $role = ($role -replace '^\d+\s*', '')
      if ($label.Length -gt 60) { $label = $label.Substring(0, 60) }
      [void]$items.Add([pscustomobject]@{ label = $label; role = $role; x = [int]$x; y = [int]$y; w = [int]$w; h = [int]$h })
    }
  }
  return $items
}
# ── OCR 兜底（Windows.Media.Ocr）──────────────────────────────────────────
# 为什么需要它：Chromium 系应用（网易云、Electron、CEF）不暴露无障碍树，
# UIA/MSAA 都是 0 个元素，模型拿不到编号也没坐标 → 只能盲点。
# 系统自带 OCR 是离线的、零依赖的，能给出「文字 + bbox」，于是「按歌名找并点击」变得确定。
while ($true) {
  $line = [Console]::In.ReadLine()
  if ($null -eq $line) { break }
  if ($line.Trim().Length -eq 0) { continue }
  $parts = $line.Split([char]1)
  $reqId = if ($parts.Length -gt 0) { $parts[0] } else { '' }
  $op = if ($parts.Length -gt 1) { $parts[1] } else { '' }
  if ($op -eq 'elements') {
    try {
      $els = Get-UiaElements
      $source = 'uia'
      if ($els.Count -lt 1) {
        $msaaErr = ''
        $msaa = @()
        try { $msaa = Get-MsaaElements } catch { $msaaErr = $_.Exception.Message }
        $respExtra = @{ msaaCount = $msaa.Count; msaaError = $msaaErr }
        if ($msaa.Count -lt 1) {
          # Chromium/Electron：无障碍树要"被戳一下"才建，而且异步。戳完等 ~900ms 再来一轮。
          try {
            $hwnd0 = [LianYuDesktop]::ForegroundHandle()
            if ($hwnd0 -ne 0) {
              [LianYuMsaa]::Nudge([IntPtr]$hwnd0)
              Start-Sleep -Milliseconds 900
              $els2 = Get-UiaElements
              if ($els2.Count -gt 0) { $els = $els2; $source = 'uia-after-nudge' }
              else {
                $msaa2 = Get-MsaaElements
                if ($msaa2.Count -gt 0) { $els = $msaa2; $source = 'msaa-after-nudge'; $msaa = $msaa2 }
              }
            }
          } catch { }
        }
        if ($msaa.Count -gt 0) {
          $els = $msaa
          $source = 'msaa'
          # MSAA 访问会触发 Chromium 构建无障碍树：再试一次 UIA，可能拿到更完整的树
          try { $again = Get-UiaElements; if ($again.Count -gt $msaa.Count) { $els = $again; $source = 'uia-after-msaa' } } catch { }
        }
      }
      $ocrCount = -1
      $ocrError = 'pending-integration'
      $resp = @{ id = $reqId; ok = $true; elements = $els; source = $source; msaaCount = $(if ($null -ne $respExtra) { $respExtra.msaaCount } else { -1 }); msaaError = $(if ($null -ne $respExtra) { $respExtra.msaaError } else { "" }); ocrCount = $ocrCount; ocrError = $ocrError }
    } catch {
      $resp = @{ id = $reqId; ok = $false; error = 'uia_unavailable'; detail = $_.Exception.Message }
    }
    $out.WriteLine(($resp | ConvertTo-Json -Compress -Depth 6))
    $out.Flush()
    continue
  }
  try { $out.WriteLine([LianYuDesktop]::Handle($line)) }
  catch { $out.WriteLine('{"ok":false,"error":"host_exception"}') }
  $out.Flush()
}
`

const ERROR_BY_MESSAGE = (message) => {
  const text = String(message || '')
  if (/window_not_found/.test(text)) return 'window_not_found'
  if (/unsupported_op/.test(text)) return 'unsupported'
  return 'action_failed'
}

/**
 * 创建一个 actor（懒启动，崩溃后下次调用自动重建）。
 *
 * @param {object} [options]
 * @param {Function} [options.spawnImpl]  测试注入
 * @param {number}   [options.startTimeoutMs] 首次编译 `Add-Type` 较慢（约 0.4–1.2s）
 */
export function createWinActor({
  spawnImpl = nodeSpawn,
  log = () => {},
  startTimeoutMs = 15000,
  requestTimeoutMs = 8000,
  scriptPath = '',
  // 可选：把 actor 进程的 TMP/TEMP 指到可写目录。
  // 用途：%TEMP% 被策略/沙箱限制时，`Add-Type` 会因无法写临时文件而失败
  //（它要落 csc 的中间产物），此时整条桌面能力会退化成 actor_down。
  tempDir = process.env.LIANYU_CAPABILITY_TEMP_DIR || '',
} = {}) {
  let child = null
  let buffer = ''
  let seq = 0
  let startedAt = 0
  const pending = new Map()
  // 显式给了 tempDir（企业策略/沙箱把 %TEMP% 锁死）时，脚本也要落在那里，
    // 否则 `writeFileSync` 直接 EPERM → 整个能力层退化 actor_down。
    const scriptRoot = scriptPath ? path.dirname(scriptPath) : (tempDir || os.tmpdir())
    const scriptFile = scriptPath || path.join(scriptRoot, `lianyu-win-actor-${process.pid}.ps1`)

  function failAll(reason) {
    for (const [, entry] of pending) {
      clearTimeout(entry.timer)
      entry.resolve({ ok: false, error: 'actor_down', detail: reason })
    }
    pending.clear()
  }

  function handleLine(line) {
    const text = line.trim()
    if (!text) return
    let payload
    try {
      payload = JSON.parse(text)
    } catch {
      log(`win-actor non-json line ignored: ${text.slice(0, 160)}`)
      return
    }
    const id = payload?.id
    const entry = id != null ? pending.get(String(id)) : null
    if (!entry) return
    pending.delete(String(id))
    clearTimeout(entry.timer)
    entry.resolve(payload.ok ? { ok: true, ...payload } : { ok: false, ...payload })
  }

  function ensureStarted() {
    if (child) return true
    // 必须走 -File：`-Command -` 是逐行交互模式，多行 here-string（C# 源码）会被拆断。
    // **必须写 BOM**：PowerShell 5.1 的 -File 对无 BOM 的 UTF-8 按 ANSI 解释，
    // 中文注释会被按双字节吞掉相邻的 ASCII 字符（实测吞掉 `}` 导致整脚本语法错误）。
    try {
      fs.mkdirSync(path.dirname(scriptFile), { recursive: true })
      fs.writeFileSync(scriptFile, `\uFEFF${ACTOR_SCRIPT}`, 'utf8')
    } catch (e) {
      log(`win-actor script write failed: ${e?.message || e}`)
      return false
    }
    try {
      const spawnEnv = { ...process.env }
      if (tempDir) {
        spawnEnv.TMP = tempDir
        spawnEnv.TEMP = tempDir
      }
      child = spawnImpl('powershell.exe', [
        '-NoLogo', '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass',
        '-File', scriptFile,
      ], { windowsHide: true, stdio: ['pipe', 'pipe', 'pipe'], env: spawnEnv })
    } catch (e) {
      log(`win-actor spawn failed: ${e?.message || e}`)
      child = null
      return false
    }
    startedAt = Date.now()
    child.stdout?.setEncoding('utf8')
    child.stdout?.on('data', (chunk) => {
      buffer += chunk
      let idx
      while ((idx = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, idx)
        buffer = buffer.slice(idx + 1)
        handleLine(line)
      }
    })
    child.stderr?.setEncoding('utf8')
    child.stderr?.on('data', (chunk) => log(`win-actor stderr: ${String(chunk).slice(0, 300)}`))
    child.on('exit', (code) => {
      log(`win-actor exited code=${code}`)
      child = null
      failAll(`exited(${code})`)
    })
    child.on('error', (e) => {
      log(`win-actor error: ${e?.message || e}`)
      child = null
      failAll(e?.message || 'spawn error')
    })
    return true
  }

  function stop() {
    failAll('stopped')
    try {
      child?.stdin?.end()
      child?.kill()
    } catch { /* ignore */ }
    child = null
    buffer = ''
    try { fs.unlinkSync(scriptFile) } catch { /* 临时文件删不掉不影响 */ }
  }

  async function call(op, args = [], timeoutMs = requestTimeoutMs) {
    if (!ensureStarted()) {
      return { ok: false, error: 'actor_down', detail: 'spawn failed' }
    }
    const id = String(++seq)
    const isFirst = Date.now() - startedAt < 2000
    const effectiveTimeout = isFirst ? Math.max(timeoutMs, startTimeoutMs) : timeoutMs
    const line = buildActorCommand(id, op, args)
    return new Promise((resolve) => {
      const timer = setTimeout(() => {
        pending.delete(id)
        resolve({ ok: false, error: 'step_timeout', detail: `${op} timeout` })
      }, effectiveTimeout)
      pending.set(id, { resolve, timer })
      try {
        child.stdin.write(line + '\n')
      } catch (e) {
        pending.delete(id)
        clearTimeout(timer)
        resolve({ ok: false, error: 'actor_down', detail: e?.message || 'write failed' })
      }
    })
  }

  return {
    call,
    stop,
    get running() { return !!child },
    get pendingCount() { return pending.size },
  }
}

export const ACTOR_ERRORS = { ERROR_BY_MESSAGE }

export { ACTOR_SCRIPT }
