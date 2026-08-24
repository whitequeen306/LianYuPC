using Microsoft.Win32;
using System;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Reflection;
using System.Runtime.InteropServices;

namespace LianYu.Installer;

public sealed class InstallerEngine
{
    private const string PayloadResource = "LianYu.Payload.zip";
    private readonly string _version;

    public static bool PayloadAvailable => Assembly.GetExecutingAssembly()
        .GetManifestResourceNames()
        .Any(name => name.EndsWith(PayloadResource, StringComparison.OrdinalIgnoreCase));

    public InstallerEngine(string version) => _version = version;

    public async Task InstallAsync(InstallOptions options, IProgress<InstallProgress> progress, CancellationToken cancellationToken)
    {
        EnsureElevationIfNeeded(options);
        Directory.CreateDirectory(options.InstallDirectory);

        var stageDirectory = Path.Combine(Path.GetTempPath(), $"lianyu-install-{Guid.NewGuid():N}");
        var backupDirectory = options.InstallDirectory + ".installer-backup";
        Directory.CreateDirectory(stageDirectory);

        try
        {
            await ExtractPayloadAsync(stageDirectory, progress, cancellationToken);
            StopRunningApplication();

            if (Directory.Exists(backupDirectory)) Directory.Delete(backupDirectory, true);
            if (Directory.Exists(options.InstallDirectory) && Directory.EnumerateFileSystemEntries(options.InstallDirectory).Any())
                CopyDirectory(options.InstallDirectory, backupDirectory);

            CopyDirectory(stageDirectory, options.InstallDirectory, progress, cancellationToken);
            ConfigureShellIntegration(options);
            WriteUninstallEntry(options);
            progress.Report(new InstallProgress(100, "安装完成", "LianYu.exe", 1, 1, TimeSpan.Zero));
        }
        catch
        {
            if (Directory.Exists(backupDirectory))
            {
                if (Directory.Exists(options.InstallDirectory)) Directory.Delete(options.InstallDirectory, true);
                Directory.Move(backupDirectory, options.InstallDirectory);
            }
            throw;
        }
        finally
        {
            TryDelete(stageDirectory);
            TryDelete(backupDirectory);
        }
    }

    public static string GetDefaultInstallDirectory(bool allUsers) => allUsers
        ? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "LianYu")
        : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Programs", "LianYu");

    public static void Launch(string installDirectory)
    {
        var exe = Path.Combine(installDirectory, "LianYu.exe");
        if (!File.Exists(exe)) throw new FileNotFoundException("安装完成，但没有找到 LianYu.exe。", exe);
        Process.Start(new ProcessStartInfo(exe) { UseShellExecute = true, WorkingDirectory = installDirectory });
    }

    private static void EnsureElevationIfNeeded(InstallOptions options)
    {
        if (!options.AllUsers || IsAdministrator()) return;
        throw new UnauthorizedAccessException("所有用户安装需要管理员权限，请返回后重新确认系统权限提示。");
    }

    private static bool IsAdministrator()
    {
        using var identity = System.Security.Principal.WindowsIdentity.GetCurrent();
        return new System.Security.Principal.WindowsPrincipal(identity)
            .IsInRole(System.Security.Principal.WindowsBuiltInRole.Administrator);
    }

    private static async Task ExtractPayloadAsync(string destination, IProgress<InstallProgress> progress, CancellationToken cancellationToken)
    {
        var assembly = Assembly.GetExecutingAssembly();
        await using var payload = assembly.GetManifestResourceStream(PayloadResource)
            ?? throw new InvalidOperationException("安装包未包含 LianYu 离线程序资源。请先运行 branded-installer 构建脚本。");
        using var archive = new ZipArchive(payload, ZipArchiveMode.Read);
        var total = archive.Entries.Sum(entry => entry.Length);
        long completed = 0;
        var lastReportedPercent = -1;
        var started = Stopwatch.StartNew();

        foreach (var entry in archive.Entries)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var outputPath = Path.GetFullPath(Path.Combine(destination, entry.FullName));
            if (!outputPath.StartsWith(Path.GetFullPath(destination), StringComparison.OrdinalIgnoreCase))
                throw new InvalidDataException("安装资源包含无效路径。");
            if (string.IsNullOrEmpty(entry.Name)) { Directory.CreateDirectory(outputPath); continue; }
            Directory.CreateDirectory(Path.GetDirectoryName(outputPath)!);
            await using var source = entry.Open();
            await using var target = File.Create(outputPath);
            var buffer = new byte[1024 * 256];
            int read;
            while ((read = await source.ReadAsync(buffer, cancellationToken)) > 0)
            {
                await target.WriteAsync(buffer.AsMemory(0, read), cancellationToken);
                completed += read;
                TimeSpan? remaining = completed > 0
                    ? TimeSpan.FromSeconds(started.Elapsed.TotalSeconds * (total - completed) / completed)
                    : null;
                var percent = total == 0 ? 0 : (int)(completed * 82 / total);
                if (percent == lastReportedPercent) continue;
                lastReportedPercent = percent;
                progress.Report(new InstallProgress(percent, "正在解压应用文件", entry.FullName, completed, total, remaining));
            }
        }
    }

    private static void CopyDirectory(string source, string destination, IProgress<InstallProgress>? progress = null, CancellationToken cancellationToken = default)
    {
        Directory.CreateDirectory(destination);
        var files = Directory.GetFiles(source, "*", SearchOption.AllDirectories);
        var lastReportedPercent = -1;
        for (var index = 0; index < files.Length; index++)
        {
            cancellationToken.ThrowIfCancellationRequested();
            var relative = Path.GetRelativePath(source, files[index]);
            var target = Path.Combine(destination, relative);
            Directory.CreateDirectory(Path.GetDirectoryName(target)!);
            File.Copy(files[index], target, true);
            var percent = 82 + (files.Length == 0 ? 0 : (index + 1) * 15 / files.Length);
            if (percent == lastReportedPercent) continue;
            lastReportedPercent = percent;
            progress?.Report(new InstallProgress(percent, "正在写入安装目录", relative, index + 1, files.Length, null));
        }
    }

    private static void ConfigureShellIntegration(InstallOptions options)
    {
        var exe = Path.Combine(options.InstallDirectory, "LianYu.exe");
        if (options.DesktopShortcut)
            CreateShortcut(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), "LianYu.lnk"), exe);
        if (options.StartMenuShortcut)
        {
            var menu = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "LianYu");
            Directory.CreateDirectory(menu);
            CreateShortcut(Path.Combine(menu, "LianYu.lnk"), exe);
        }

        using var runKey = Registry.CurrentUser.CreateSubKey(@"Software\Microsoft\Windows\CurrentVersion\Run");
        if (options.AutoStart) runKey.SetValue("LianYu", $"\"{exe}\"");
        else runKey.DeleteValue("LianYu", false);
    }

    private void WriteUninstallEntry(InstallOptions options)
    {
        var root = options.AllUsers ? Registry.LocalMachine : Registry.CurrentUser;
        using var key = root.CreateSubKey(@"Software\Microsoft\Windows\CurrentVersion\Uninstall\LianYu");
        key.SetValue("DisplayName", "LianYu");
        key.SetValue("DisplayVersion", _version);
        key.SetValue("Publisher", "LianYu");
        key.SetValue("InstallLocation", options.InstallDirectory);
        key.SetValue("DisplayIcon", Path.Combine(options.InstallDirectory, "LianYu.exe"));
        key.SetValue("NoModify", 1, RegistryValueKind.DWord);
        key.SetValue("NoRepair", 1, RegistryValueKind.DWord);
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
        foreach (var process in Process.GetProcessesByName("LianYu"))
        {
            try { process.Kill(true); process.WaitForExit(4000); } catch { }
        }
    }

    private static void TryDelete(string path)
    {
        try { if (Directory.Exists(path)) Directory.Delete(path, true); } catch { }
    }
}
