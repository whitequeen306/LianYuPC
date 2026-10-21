using System.IO;

namespace LianYu.Installer;

/// <summary>
/// 安装器日志：GUI 与无界面两种模式共用同一份实现。
///
/// 存在的理由：GUI 模式以前**完全不记日志**。用户反馈「双击没反应/装不上」时，
/// 远程只能靠枚举进程、看窗口句柄去猜，抓到的事实只有「进程活着、WPF 没加载、
/// 没有窗口」——再多就查不下去了。现在两种模式都写
/// %TEMP%\lianyu-installer-YYYYMMDD-HHmmss.log，失败时界面上直接给出路径。
///
/// 纪律：日志自身出问题绝不能拖垮安装，所以所有 IO 都吞异常。
/// </summary>
public static class InstallerLog
{
    private static StreamWriter? _writer;

    /// <summary>日志文件路径；写不了日志时为空字符串。</summary>
    public static string Path { get; private set; } = string.Empty;

    public static void Start()
    {
        if (_writer is not null) return;
        Path = System.IO.Path.Combine(
            System.IO.Path.GetTempPath(), $"lianyu-installer-{DateTime.Now:yyyyMMdd-HHmmss}.log");
        try
        {
            _writer = new StreamWriter(Path, append: false) { AutoFlush = true };
        }
        catch
        {
            Path = string.Empty; // 连日志都建不了，就只靠退出码
            return;
        }
        Write("=== LianYu installer ===");
    }

    public static void Write(string message)
    {
        if (_writer is null) return;
        try
        {
            _writer.WriteLine($"[{DateTime.Now:HH:mm:ss.fff}] {message}");
        }
        catch
        {
            // 忽略：日志失败不影响安装
        }
    }
}
