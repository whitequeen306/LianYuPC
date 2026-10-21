using Microsoft.Win32;
using System;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Runtime.InteropServices;

namespace LianYu.Installer;

/// <summary>
/// 安装引擎的壳：**自己不落地任何文件**，只负责把内嵌的 NSIS 安装器解出来、静默跑起来。
///
/// 为什么改成这样（2026-10-21）：
/// 以前这里内嵌的是 218MB 原始负载 zip，由 C# 自己解压 + 拷贝，于是
/// - 体积 282MB（原始负载比 NSIS 的 166MB 更大）
/// - **不生成卸载器**：只写注册表 Uninstall 项，没有 UninstallString，
///   「应用和功能」里点卸载直接失败；再被 NSIS 升级覆盖时就撞
///   `不能打开要写入的文件: uninstallerIcon.ico`
/// 现在内嵌的是 electron-builder 打好的 NSIS 安装器（约 166MB），界面仍由 WPF 出，
/// 安装/卸载/注册表/快捷方式全部沿用 NSIS 的成熟逻辑，两个老问题一起消失。
///
/// 早先"282MB 单文件被 Windows 拦"的结论也是错的：真因是仓库目录带 Low 完整性
/// 标签，产物继承了它（见 frontend/scripts/electron-pack.mjs 与
/// docs/refactor-2026-10/client-release.md 第九节）。
/// </summary>
public sealed class InstallerEngine
{
    private const string PayloadResource = "LianYu.Engine.exe";
    /// <summary>内嵌 NSIS 认识的标记：带上它就老老实实静默安装，不强制弹自己的界面。</summary>
    private const string EmbeddedSwitch = "/LIANYU_EMBEDDED";
    private static readonly TimeSpan EngineTimeout = TimeSpan.FromMinutes(10);
    /// <summary>安装后目录大约 505MB；只用来算进度，不必精确。</summary>
    private const long ExpectedInstallBytes = 520L * 1024 * 1024;

    private readonly string _version;

    public InstallerEngine(string version) => _version = version;

    public static bool PayloadAvailable => Assembly.GetExecutingAssembly()
        .GetManifestResourceNames()
        .Any(name => name.EndsWith(PayloadResource, StringComparison.OrdinalIgnoreCase));

    /// <summary>诊断钩子：设置后 InstallerEngine 会把关键步骤写出去（无界面/gui 共用）。</summary>
    public static Action<string>? Trace { get; set; }

    public async Task InstallAsync(InstallOptions options, IProgress<InstallProgress> progress, CancellationToken cancellationToken)
    {
        Directory.CreateDirectory(options.InstallDirectory);

        var stageDirectory = Path.Combine(Path.GetTempPath(), $"lianyu-install-{Guid.NewGuid():N}");
        Directory.CreateDirectory(stageDirectory);

        try
        {
            var enginePath = await ExtractEngineAsync(stageDirectory, progress, cancellationToken);
            StopRunningApplication();

            progress.Report(new InstallProgress(15, "正在安装应用文件", "YuNian.exe", 0, ExpectedInstallBytes, null));
            var exitCode = await RunEngineAsync(enginePath, options, progress, cancellationToken);
            if (exitCode != 0)
            {
                // NSIS 的退出码没有公开语义，只知道非 0 = 没装成。
                throw new InvalidOperationException(
                    $"安装没有完成（安装引擎返回 {exitCode}）。请确认予念已完全退出后重试。");
            }

            ApplyShellOptions(options);
            progress.Report(new InstallProgress(100, "安装完成", "YuNian.exe", 1, 1, TimeSpan.Zero));
        }
        finally
        {
            TryDelete(stageDirectory);
        }
    }

    public static string GetDefaultInstallDirectory(bool allUsers) => allUsers
        ? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "YuNian")
        : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "YuNian");

    /// <summary>
    /// 安装包版本号：优先 <see cref="AssemblyInformationalVersionAttribute"/>（构建脚本传入的
    /// `LianYuVersion`），退到 <see cref="Version"/>。
    ///
    /// 之所以提到这里、让 GUI 与无界面模式共用：无界面模式以前传的是硬编码字符串
    /// `"silent-probe"`，于是「应用和功能」里显示的版本就是 `silent-probe`
    /// （2026-10-21 在真机上看到，属于缺陷）。
    /// </summary>
    public static string GetInstallerVersion()
    {
        var informational = Assembly.GetExecutingAssembly()
            .GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion;
        if (!string.IsNullOrWhiteSpace(informational))
            return informational.Split('+', 2)[0];

        var assemblyVersion = Assembly.GetExecutingAssembly().GetName().Version;
        return assemblyVersion is null
            ? "0.0.0"
            : $"{assemblyVersion.Major}.{assemblyVersion.Minor}.{assemblyVersion.Build}";
    }

    public static void Launch(string installDirectory)
    {
        var exe = Path.Combine(installDirectory, "YuNian.exe");
        if (!File.Exists(exe)) throw new FileNotFoundException("安装完成，但没有找到 YuNian.exe。", exe);
        Process.Start(new ProcessStartInfo(exe) { UseShellExecute = true, WorkingDirectory = installDirectory });
    }

    /// <summary>把内嵌的 NSIS 安装器落到临时文件（单文件包里的资源不可 seek，必须先落盘再执行）。</summary>
    private static async Task<string> ExtractEngineAsync(string destination, IProgress<InstallProgress> progress, CancellationToken cancellationToken)
    {
        var assembly = Assembly.GetExecutingAssembly();
        await using var payload = assembly.GetManifestResourceStream(PayloadResource)
            ?? throw new InvalidOperationException("安装包未包含 予念 安装引擎。请先运行 installer/build-offline-installer.ps1。");

        var enginePath = Path.Combine(destination, "YuNian-Engine.exe");
        Trace?.Invoke($"engine stream opened: canSeek={payload.CanSeek}, length={(payload.CanSeek ? payload.Length : -1)}");

        var started = Stopwatch.StartNew();
        await using (var file = new FileStream(enginePath, FileMode.CreateNew, FileAccess.Write, FileShare.None, 1 << 20, useAsync: true))
        {
            var expected = payload.CanSeek ? payload.Length : 0;
            var buffer = new byte[1024 * 256];
            long copied = 0;
            var lastPercent = -1;
            int read;
            while ((read = await payload.ReadAsync(buffer, cancellationToken)) > 0)
            {
                await file.WriteAsync(buffer.AsMemory(0, read), cancellationToken);
                copied += read;
                if (expected <= 0) continue;
                var percent = (int)(copied * 12 / expected); // 解包占整体进度的前 12%
                if (percent == lastPercent) continue;
                lastPercent = percent;
                progress.Report(new InstallProgress(percent, "正在准备安装组件", "YuNian-Engine.exe", copied, expected,
                    copied > 0 ? TimeSpan.FromSeconds(started.Elapsed.TotalSeconds * (expected - copied) / copied) : null));
            }
        }
        Trace?.Invoke($"engine extracted to {enginePath} in {started.ElapsedMilliseconds}ms ({new FileInfo(enginePath).Length} bytes)");
        return enginePath;
    }

    /// <summary>
    /// 静默跑 NSIS 安装器，同时按目标目录的体积估算进度。
    ///
    /// 参数必须拼成一个字符串（`Arguments`）而不是 `ArgumentList`：
    /// NSIS 的 `/D=` 要求是**命令行最后一项且不加引号**，会让带空格的路径被当成
    /// 引号的一部分而解错目录。
    /// </summary>
    private static async Task<int> RunEngineAsync(string enginePath, InstallOptions options, IProgress<InstallProgress> progress, CancellationToken cancellationToken)
    {
        var arguments = $"/S {EmbeddedSwitch} /currentuser /D={options.InstallDirectory}";
        Trace?.Invoke($"running engine: \"{enginePath}\" {arguments}");

        var startInfo = new ProcessStartInfo(enginePath)
        {
            UseShellExecute = false,
            Arguments = arguments,
            WorkingDirectory = Path.GetDirectoryName(enginePath)!,
        };

        using var process = Process.Start(startInfo)
            ?? throw new InvalidOperationException("无法启动安装引擎。");

        var started = Stopwatch.StartNew();
        while (!process.HasExited)
        {
            cancellationToken.ThrowIfCancellationRequested();
            if (started.Elapsed > EngineTimeout)
            {
                try { process.Kill(true); } catch { /* 已经退出 */ }
                throw new TimeoutException($"安装超过 {EngineTimeout.TotalMinutes:0} 分钟仍未完成，已中止。");
            }

            var installed = MeasureDirectoryBytes(options.InstallDirectory);
            var ratio = Math.Clamp(installed / (double)ExpectedInstallBytes, 0, 1);
            var percent = 15 + (int)(ratio * 80);
            var remaining = ratio > 0.02
                ? TimeSpan.FromSeconds(started.Elapsed.TotalSeconds * (1 - ratio) / ratio)
                : (TimeSpan?)null;
            progress.Report(new InstallProgress(percent, "正在安装应用文件", "YuNian.exe", installed, ExpectedInstallBytes, remaining));

            await Task.Delay(400, cancellationToken);
        }

        process.WaitForExit();
        Trace?.Invoke($"engine exited with {process.ExitCode} after {started.ElapsedMilliseconds}ms");
        return process.ExitCode;
    }

    private static long MeasureDirectoryBytes(string directory)
    {
        try
        {
            if (!Directory.Exists(directory)) return 0;
            long total = 0;
            foreach (var file in Directory.EnumerateFiles(directory, "*", SearchOption.AllDirectories))
            {
                try { total += new FileInfo(file).Length; } catch { /* 正在被写，跳过 */ }
            }
            return total;
        }
        catch
        {
            return 0;
        }
    }

    /// <summary>
    /// NSIS 已经处理了卸载登记、开始菜单、桌面快捷方式与文件落地；
    /// 这里只补它不认识的三个开关：桌面快捷方式「取消勾选」、开机自启、装完启动。
    /// </summary>
    private static void ApplyShellOptions(InstallOptions options)
    {
        var exe = Path.Combine(options.InstallDirectory, "YuNian.exe");

        if (options.DesktopShortcut)
            CreateShortcut(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), "YuNian.lnk"), exe);
        else
            TryDeleteFile(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), "YuNian.lnk"));

        if (!options.StartMenuShortcut)
            TryDeleteFile(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "YuNian", "YuNian.lnk"));

        using var runKey = Registry.CurrentUser.CreateSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run");
        if (options.AutoStart) runKey.SetValue("YuNian", $"\"{exe}\"");
        else runKey.DeleteValue("YuNian", false);

        if (options.LaunchAfterInstall) Launch(options.InstallDirectory);
    }

    private static void CreateShortcut(string shortcutPath, string targetPath)
    {
        var shellType = Type.GetTypeFromProgID("WScript.Shell") ?? throw new COMException("系统快捷方式组件不可用。");
        dynamic shell = Activator.CreateInstance(shellType)!;
        dynamic shortcut = shell.CreateShortcut(shortcutPath);
        shortcut.TargetPath = targetPath;
        shortcut.WorkingDirectory = Path.GetDirectoryName(targetPath);
        shortcut.IconLocation = targetPath;
        shortcut.Save();
        Marshal.FinalReleaseComObject(shortcut);
        Marshal.FinalReleaseComObject(shell);
    }

    private static void StopRunningApplication()
    {
        foreach (var process in Process.GetProcessesByName("YuNian"))
        {
            try { process.Kill(true); process.WaitForExit(4000); } catch { }
        }
    }

    private static void TryDeleteFile(string path)
    {
        try { if (File.Exists(path)) File.Delete(path); } catch { }
    }

    private static void TryDelete(string path)
    {
        try { if (Directory.Exists(path)) Directory.Delete(path, true); } catch { }
    }
}
