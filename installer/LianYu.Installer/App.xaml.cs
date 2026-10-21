using System.IO;
using System.Windows;

namespace LianYu.Installer;

public partial class App : Application
{
    /// <summary>
    /// 无界面安装模式：`YuNian-Setup-x.y.z.exe --install-silent [--dir D:\path]`。
    ///
    /// 存在的理由：安装失败时 UI 只把 `ex.Message` 打到一行小字上，经常被截断，
    /// 远程排查拿不到完整堆栈。这个模式把**完整异常 + 每一步阶段**写进
    /// `%TEMP%\lianyu-installer-YYYYMMDD-HHmmss.log`，并用退出码表达成败
    /// （0 = 成功，1 = 失败）：既给用户自救手段，也给自动化验证用。
    /// </summary>
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        var silent = Array.Exists(e.Args,
            a => string.Equals(a, "--install-silent", StringComparison.OrdinalIgnoreCase));
        if (!silent)
        {
            new MainWindow().Show();
            return;
        }

        var dir = ReadArg(e.Args, "--dir") ?? InstallerEngine.GetDefaultInstallDirectory(false);
        RunSilentInstall(dir);
    }

    private void RunSilentInstall(string installDirectory)
    {
        var logPath = Path.Combine(Path.GetTempPath(), $"lianyu-installer-{DateTime.Now:yyyyMMdd-HHmmss}.log");
        var exitCode = 1;
        StreamWriter? log = null;
        try
        {
            log = new StreamWriter(logPath, append: false) { AutoFlush = true };
            var sink = log;
            void Write(string message) => sink.WriteLine($"[{DateTime.Now:HH:mm:ss.fff}] {message}");

            Write("=== LianYu installer (silent) ===");
            Write($"base        = {AppContext.BaseDirectory}");
            Write($"exe         = {Environment.ProcessPath}");
            Write($"payload ok  = {InstallerEngine.PayloadAvailable}");
            Write($"target dir  = {installDirectory}");
            Write($"temp        = {Path.GetTempPath()}");
            Write($"64bit proc  = {Environment.Is64BitProcess}");

            InstallerEngine.Trace = Write;

            var options = new InstallOptions(
                installDirectory,
                AllUsers: false,
                DesktopShortcut: false,
                StartMenuShortcut: false,
                AutoStart: false,
                LaunchAfterInstall: false);
            var engine = new InstallerEngine("silent-probe");
            var lastStage = string.Empty;
            // 用同步实现而不是 Progress<T>：Progress<T> 会把回调 post 到被阻塞的 UI 线程上，
            // 结果日志在「calling InstallAsync...」之后就断了，看着像卡死其实是日志被堵住。
            var progress = new SyncProgress(p =>
            {
                if (p.Stage == lastStage) return;
                lastStage = p.Stage;
                Write($"stage       = {p.Percent}% {p.Stage} ({p.CurrentFile})");
            });

            Write("calling InstallAsync (on thread pool)...");
            var task = System.Threading.Tasks.Task.Run(() =>
                engine.InstallAsync(options, progress, CancellationToken.None));
            if (!task.Wait(TimeSpan.FromMinutes(8)))
            {
                Write("TIMEOUT after 8 minutes — 安装卡住，上面的最后一条 stage 就是卡住的位置");
                exitCode = 2;
            }
            else
            {
                Write("InstallAsync returned normally — OK");
                exitCode = 0;
            }
        }
        catch (Exception ex)
        {
            try
            {
                log?.WriteLine($"[{DateTime.Now:HH:mm:ss.fff}] FAILED:{Environment.NewLine}{ex}");
            }
            catch
            {
                // 连日志都写不了就只能靠退出码
            }
        }
        finally
        {
            try { log?.Dispose(); } catch { }
        }

        if (File.Exists(logPath))
        {
            Console.Error.WriteLine(logPath);
        }
        Environment.Exit(exitCode);
    }

    /// <summary>同步版的 IProgress：不切线程、不排队，日志能实时落盘。</summary>
    private sealed class SyncProgress(Action<InstallProgress> onReport) : IProgress<InstallProgress>
    {
        public void Report(InstallProgress value) => onReport(value);
    }

    private static string? ReadArg(string[] args, string name)
    {
        for (var i = 0; i < args.Length - 1; i++)
        {
            if (string.Equals(args[i], name, StringComparison.OrdinalIgnoreCase)) return args[i + 1];
        }
        return null;
    }
}
