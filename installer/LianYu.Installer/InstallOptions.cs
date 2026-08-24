namespace LianYu.Installer;

public sealed record InstallOptions(
    string InstallDirectory,
    bool AllUsers,
    bool DesktopShortcut,
    bool StartMenuShortcut,
    bool AutoStart,
    bool LaunchAfterInstall);

public sealed record InstallProgress(
    int Percent,
    string Stage,
    string CurrentFile,
    long CompletedBytes,
    long TotalBytes,
    TimeSpan? Remaining);
