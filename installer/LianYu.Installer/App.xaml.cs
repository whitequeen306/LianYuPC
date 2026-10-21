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
    ///
    /// 2026-10-21 起 GUI 模式也写同一份日志（见 InstallerLog）：那次线上「双击没反应」
    /// 排查时，GUI 模式什么都没留下，只能靠枚举进程猜。
    /// </summary>
    protected override void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);

        InstallerLog.Start();
        InstallerLog.Write($"base        = {AppContext.BaseDirectory}");
        InstallerLog.Write($"exe         = {Environment.ProcessPath}");
        InstallerLog.Write($"version     = {InstallerEngine.GetInstallerVersion()}");
        InstallerLog.Write($"payload ok  = {InstallerEngine.PayloadAvailable}");
        InstallerLog.Write($"temp        = {Path.GetTempPath()}");
        InstallerLog.Write($"64bit proc  = {Environment.Is64BitProcess}");

        var silent = Array.Exists(e.Args,
            a => string.Equals(a, "--install-silent", StringComparison.OrdinalIgnoreCase));
        if (!silent)
        {
            InstallerLog.Write("mode        = gui");
            new MainWindow().Show();
            return;
        }

        var dir = ReadArg(e.Args, "--dir") ?? InstallerEngine.GetDefaultInstallDirectory(false);
        RunSilentInstall(dir);
    }

    private void RunSilentInstall(string installDirectory)
    {
        var exitCode = 1;
        try
        {
            InstallerLog.Write("mode        = silent");
            InstallerLog.Write($"target dir  = {installDirectory}");

            InstallerEngine.Trace = InstallerLog.Write;

            var options = new InstallOptions(
                installDirectory,
                AllUsers: false,
                DesktopShortcut: false,
                StartMenuShortcut: false,
                AutoStart: false,
                LaunchAfterInstall: false);
            // 版本必须取真实值：之前这里硬编码 "silent-probe"，控制面板里显示的版本号
            // 就变成了这个字符串（真机上已出现，属于缺陷）。
            var engine = new InstallerEngine(InstallerEngine.GetInstallerVersion());
            var lastStage = string.Empty;
            // 用同步实现而不是 Progress<T>：Progress<T> 会把回调 post 到被阻塞的 UI 线程上，
            // 结果日志在「calling InstallAsync...」之后就断了，看着像卡死其实是日志被堵住。
            var progress = new SyncProgress(p =>
            {
                if (p.Stage == lastStage) return;
                lastStage = p.Stage;
                InstallerLog.Write($"stage       = {p.Percent}% {p.Stage} ({p.CurrentFile})");
            });

            InstallerLog.Write("calling InstallAsync (on thread pool)...");
            var task = System.Threading.Tasks.Task.Run(() =>
                engine.InstallAsync(options, progress, CancellationToken.None));
            if (!task.Wait(TimeSpan.FromMinutes(8)))
            {
                InstallerLog.Write("TIMEOUT after 8 minutes — 安装卡住，上面的最后一条 stage 就是卡住的位置");
                exitCode = 2;
            }
            else
            {
                InstallerLog.Write("InstallAsync returned normally — OK");
                exitCode = 0;
            }
        }
        catch (Exception ex)
        {
            InstallerLog.Write($"FAILED:{Environment.NewLine}{ex}");
        }

        if (File.Exists(InstallerLog.Path)) Console.Error.WriteLine(InstallerLog.Path);
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
