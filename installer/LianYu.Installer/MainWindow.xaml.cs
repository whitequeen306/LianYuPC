using System.Reflection;
using System.IO;
using System.Windows;
using System.Windows.Input;
using System.Windows.Controls;
using System.Windows.Threading;
using System.Windows.Media.Animation;
using System.Windows.Media.Imaging;
using System.Windows.Media;

namespace LianYu.Installer;

public partial class MainWindow : Window
{
    private readonly InstallerEngine _engine = new(GetInstallerVersion());
    private bool _installing;
    private bool _demoCompleted;
    private Border? _progressCard;
    private Border? _progressFill;
    private TextBlock? _progressStage;
    private TextBlock? _progressPercent;
    private Border? _progressTrack;
    private TextBlock? _progressMeta;
    private TextBlock? _progressDetail;
    private Border? _installStagePanel;
    private TextBlock? _installStageTitle;
    private TextBlock? _installStageSummary;
    private readonly List<TextBlock> _installStepLines = [];
    private bool _settingsExpanded;
    private readonly string[] _images = ["Assets/mahiru.jpg", "Assets/mika.jpg", "Assets/character-a.jpg"];
    private readonly Dictionary<string, BitmapImage> _imageCache = new(StringComparer.OrdinalIgnoreCase);
    private int _imageIndex;
    private DispatcherTimer? _imageTimer;
    private bool _transitioning;
    private readonly TranslateTransform _rightContentShift = new();

    private static string GetInstallerVersion()
    {
        var informationalVersion = Assembly.GetExecutingAssembly()
            .GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion;
        if (!string.IsNullOrWhiteSpace(informationalVersion))
            return informationalVersion.Split('+', 2)[0];

        var assemblyVersion = Assembly.GetExecutingAssembly().GetName().Version;
        return assemblyVersion is null
            ? "0.0.0"
            : $"{assemblyVersion.Major}.{assemblyVersion.Minor}.{assemblyVersion.Build}";
    }

    public MainWindow()
    {
        InitializeComponent();
        InstallDirectoryBox.Text = InstallerEngine.GetDefaultInstallDirectory(false);
        SettingsPanel.Width = 300;
        foreach (var image in _images) _imageCache[image] = LoadImage(image);
        ImageFront.Source = _imageCache[_images[0]];
        ImageBack.Source = _imageCache[_images[1]];
        BuildProgressCard();
        _imageTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(8) };
        _imageTimer.Tick += (_, _) => RotateImages();
        _imageTimer.Start();
    }

    private void Window_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
    {
        if (e.ButtonState == MouseButtonState.Pressed) DragMove();
    }

    private void Window_SizeChanged(object sender, SizeChangedEventArgs e)
    {
        if (RootBorder.ActualWidth <= 0 || RootBorder.ActualHeight <= 0) return;
        RootBorder.Clip = new RectangleGeometry(
            new Rect(0, 0, RootBorder.ActualWidth, RootBorder.ActualHeight), 30, 30);
    }

    private static BitmapImage LoadImage(string relativePath)
    {
        var image = new BitmapImage();
        image.BeginInit();
        image.UriSource = new Uri($"pack://application:,,,/{relativePath}", UriKind.Absolute);
        image.CacheOption = BitmapCacheOption.OnLoad;
        image.CreateOptions = BitmapCreateOptions.PreservePixelFormat;
        image.EndInit();
        image.Freeze();
        return image;
    }

    private void Minimize_Click(object sender, RoutedEventArgs e) => WindowState = WindowState.Minimized;
    private void Close_Click(object sender, RoutedEventArgs e) => Close();

    private void ToggleSettings_Click(object sender, RoutedEventArgs e)
    {
        _settingsExpanded = !_settingsExpanded;
        CustomButton.Content = _settingsExpanded ? "收起自定义安装" : "自定义安装";
        AnimateCustomLayout(_settingsExpanded);
    }

    private void AnimateCustomLayout(bool opening)
    {
        var duration = TimeSpan.FromMilliseconds(1050);
        var easing = new CubicEase { EasingMode = EasingMode.EaseInOut };
        SettingsPanel.BeginAnimation(HeightProperty, new DoubleAnimation
        {
            To = opening ? 188 : 0,
            Duration = duration,
            EasingFunction = easing,
        }, HandoffBehavior.SnapshotAndReplace);
        SettingsPanel.BeginAnimation(OpacityProperty, new DoubleAnimation
        {
            To = opening ? 1 : 0,
            Duration = TimeSpan.FromMilliseconds(opening ? 780 : 520),
            BeginTime = opening ? TimeSpan.FromMilliseconds(250) : TimeSpan.Zero,
            EasingFunction = new CubicEase { EasingMode = opening ? EasingMode.EaseOut : EasingMode.EaseIn },
        }, HandoffBehavior.SnapshotAndReplace);

        HeroDescription.BeginAnimation(MaxHeightProperty, new DoubleAnimation
        {
            To = opening ? 0 : 60,
            Duration = duration,
            EasingFunction = easing,
        }, HandoffBehavior.SnapshotAndReplace);
        HeroDescription.BeginAnimation(OpacityProperty, new DoubleAnimation
        {
            To = opening ? 0 : 1,
            Duration = TimeSpan.FromMilliseconds(650),
            EasingFunction = new CubicEase { EasingMode = opening ? EasingMode.EaseIn : EasingMode.EaseOut },
        }, HandoffBehavior.SnapshotAndReplace);
        PackageInfo.BeginAnimation(MaxHeightProperty, new DoubleAnimation
        {
            To = opening ? 0 : 70,
            Duration = duration,
            EasingFunction = easing,
        }, HandoffBehavior.SnapshotAndReplace);
        PackageInfo.BeginAnimation(OpacityProperty, new DoubleAnimation
        {
            To = opening ? 0 : 1,
            Duration = TimeSpan.FromMilliseconds(650),
            EasingFunction = new CubicEase { EasingMode = opening ? EasingMode.EaseIn : EasingMode.EaseOut },
        }, HandoffBehavior.SnapshotAndReplace);
        PackageInfo.BeginAnimation(MarginProperty, new ThicknessAnimation
        {
            To = opening ? new Thickness(0) : new Thickness(0, 20, 0, 0),
            Duration = duration,
            EasingFunction = easing,
        }, HandoffBehavior.SnapshotAndReplace);
        HeroTitle.BeginAnimation(FontSizeProperty, new DoubleAnimation
        {
            To = opening ? 25 : 30,
            Duration = duration,
            EasingFunction = easing,
        }, HandoffBehavior.SnapshotAndReplace);
        HeroTitle.BeginAnimation(TextBlock.LineHeightProperty, new DoubleAnimation
        {
            To = opening ? 32 : 40,
            Duration = duration,
            EasingFunction = easing,
        }, HandoffBehavior.SnapshotAndReplace);
        HeroTitle.BeginAnimation(MarginProperty, new ThicknessAnimation
        {
            To = opening ? new Thickness(0, 8, 0, 6) : new Thickness(0, 16, 0, 12),
            Duration = duration,
            EasingFunction = easing,
        }, HandoffBehavior.SnapshotAndReplace);
    }

    private void BrowseDirectory_Click(object sender, RoutedEventArgs e)
    {
        var dialog = new Microsoft.Win32.OpenFolderDialog
        {
            Title = "选择恋语安装位置",
            InitialDirectory = Directory.Exists(InstallDirectoryBox.Text)
                ? InstallDirectoryBox.Text
                : Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            Multiselect = false
        };
        if (dialog.ShowDialog(this) == true)
            InstallDirectoryBox.Text = Path.Combine(dialog.FolderName, "LianYu");
    }

    private void BuildProgressCard()
    {
        var host = (Panel)InstallButton.Parent;
        var track = new Border
        {
            Height = 8,
            Background = new SolidColorBrush(Color.FromArgb(55, 255, 255, 255)),
            CornerRadius = new CornerRadius(4),
        };
        var fill = new Border
        {
            Height = 8,
            Width = 0,
            HorizontalAlignment = HorizontalAlignment.Left,
            Background = new SolidColorBrush(Color.FromRgb(244, 166, 181)),
            CornerRadius = new CornerRadius(4),
            ClipToBounds = true,
        };
        var trackGrid = new Grid();
        trackGrid.Children.Add(track);
        trackGrid.Children.Add(fill);
        track.SizeChanged += (_, _) => UpdateProgressFill();
        var shine = new Border
        {
            Width = 42,
            Height = 8,
            HorizontalAlignment = HorizontalAlignment.Left,
            Background = new LinearGradientBrush(Color.FromArgb(0, 255, 255, 255), Color.FromArgb(150, 255, 255, 255), 0),
            CornerRadius = new CornerRadius(4),
            Opacity = .65,
            IsHitTestVisible = false,
        };
        trackGrid.Children.Add(shine);
        var shineAnimation = new DoubleAnimation(-42, 330, TimeSpan.FromMilliseconds(1800))
        {
            RepeatBehavior = RepeatBehavior.Forever,
            AutoReverse = false,
            EasingFunction = new CubicEase { EasingMode = EasingMode.EaseInOut },
        };
        shine.BeginAnimation(MarginProperty, new ThicknessAnimationUsingKeyFrames
        {
            RepeatBehavior = RepeatBehavior.Forever,
            KeyFrames = { new LinearThicknessKeyFrame(new Thickness(-42, 0, 0, 0), KeyTime.FromTimeSpan(TimeSpan.Zero)), new LinearThicknessKeyFrame(new Thickness(330, 0, 0, 0), KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(1800))) }
        });

        var stage = new TextBlock { Foreground = new SolidColorBrush(Color.FromRgb(218, 226, 234)), FontSize = 11 };
        var percent = new TextBlock { Foreground = new SolidColorBrush(Color.FromRgb(244, 166, 181)), FontSize = 11, HorizontalAlignment = HorizontalAlignment.Right };
        var header = new Grid();
        header.Children.Add(stage);
        header.Children.Add(percent);

        var meta = new TextBlock
        {
            Text = "本地离线安装 · 正在准备资源",
            Foreground = new SolidColorBrush(Color.FromRgb(137, 149, 162)),
            FontSize = 10,
            Margin = new Thickness(0, 7, 0, 0),
        };
        var detail = new TextBlock
        {
            Text = "当前文件：LianYu.exe    已处理：0 MB    预计剩余：准备中",
            Foreground = new SolidColorBrush(Color.FromRgb(114, 128, 144)),
            FontSize = 10,
            Margin = new Thickness(0, 5, 0, 0),
        };
        var cardContent = new StackPanel();
        cardContent.Children.Add(header);
        cardContent.Children.Add(trackGrid);
        cardContent.Children.Add(meta);
        cardContent.Children.Add(detail);
        var card = new Border
        {
            Visibility = Visibility.Collapsed,
            Margin = new Thickness(0, 14, 0, 0),
            Padding = new Thickness(12, 10, 12, 10),
            CornerRadius = new CornerRadius(16),
            Background = new SolidColorBrush(Color.FromArgb(30, 255, 255, 255)),
            BorderBrush = new SolidColorBrush(Color.FromArgb(35, 244, 166, 181)),
            BorderThickness = new Thickness(1),
            Child = cardContent,
        };
        host.Children.Insert(host.Children.IndexOf(InstallButton) + 1, card);
        _progressCard = card;
        _progressFill = fill;
        _progressStage = stage;
        _progressPercent = percent;
        _progressTrack = track;
        _progressMeta = meta;
        _progressDetail = detail;
    }

    private void BuildInstallStagePanel()
    {
        var panelContent = new StackPanel();
        var title = new TextBlock
        {
            Text = "正在准备安装",
            Foreground = new SolidColorBrush(Color.FromRgb(243, 208, 217)),
            FontSize = 13,
            FontWeight = FontWeights.SemiBold,
        };
        var summary = new TextBlock
        {
            Text = "安装器正在为你的桌面整理陪伴空间",
            Foreground = new SolidColorBrush(Color.FromRgb(155, 167, 179)),
            FontSize = 11,
            Margin = new Thickness(0, 5, 0, 9),
        };
        panelContent.Children.Add(title);
        panelContent.Children.Add(summary);
        foreach (var text in new[] { "检查安装环境", "解压角色资源", "写入桌面应用", "创建快捷方式", "完成安装" })
        {
            var line = new TextBlock
            {
                Text = $"○  {text}",
                Foreground = new SolidColorBrush(Color.FromRgb(114, 128, 144)),
                FontSize = 10,
                Margin = new Thickness(0, 2, 0, 1),
            };
            _installStepLines.Add(line);
            panelContent.Children.Add(line);
        }
        var panel = new Border
        {
            Visibility = Visibility.Collapsed,
            Margin = new Thickness(0, 15, 0, 0),
            Padding = new Thickness(13, 11, 13, 11),
            CornerRadius = new CornerRadius(18),
            Background = new SolidColorBrush(Color.FromArgb(30, 255, 255, 255)),
            BorderBrush = new SolidColorBrush(Color.FromArgb(42, 244, 166, 181)),
            BorderThickness = new Thickness(1),
            Child = panelContent,
        };
        BrandingPanel.Children.Insert(BrandingPanel.Children.IndexOf(PackageInfo), panel);
        _installStagePanel = panel;
        _installStageTitle = title;
        _installStageSummary = summary;
    }

    private void SetInstallStage(int stageIndex, string title, string summary)
    {
        if (_installStagePanel is null || _installStageTitle is null || _installStageSummary is null) return;
        _installStagePanel.Visibility = Visibility.Visible;
        _installStageTitle.Text = title;
        _installStageSummary.Text = summary;
        for (var index = 0; index < _installStepLines.Count; index++)
        {
            var label = _installStepLines[index].Text.Length > 3 ? _installStepLines[index].Text[3..] : _installStepLines[index].Text;
            if (index < stageIndex)
            {
                _installStepLines[index].Text = $"✓  {label}";
                _installStepLines[index].Foreground = new SolidColorBrush(Color.FromRgb(244, 166, 181));
            }
            else if (index == stageIndex)
            {
                _installStepLines[index].Text = $"●  {label}";
                _installStepLines[index].Foreground = new SolidColorBrush(Color.FromRgb(232, 237, 242));
            }
            else
            {
                _installStepLines[index].Text = $"○  {label}";
                _installStepLines[index].Foreground = new SolidColorBrush(Color.FromRgb(114, 128, 144));
            }
        }
        _installStagePanel.BeginAnimation(OpacityProperty, new DoubleAnimation(.45, 1, TimeSpan.FromMilliseconds(520))
        {
            EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut }
        }, HandoffBehavior.SnapshotAndReplace);
    }

    private void SetProgress(int percent, string stage)
    {
        if (_progressCard is null || _progressStage is null || _progressPercent is null ||
            _progressMeta is null || _progressDetail is null) return;
        percent = Math.Clamp(percent, 0, 100);
        var percentChanged = _progressCard.Tag is not int previousPercent || previousPercent != percent;
        _progressCard.Visibility = Visibility.Visible;
        _progressStage.Text = stage;
        _progressPercent.Text = $"{percent}%";
        _progressCard.Tag = percent;
        _progressMeta.Text = percent < 100 ? "本地离线安装 · 正在处理资源" : "本地离线安装 · 即将完成";
        _progressDetail.Text = $"当前文件：{(percent < 40 ? "角色资源" : percent < 80 ? "LianYu.exe" : "快捷方式")}    已处理：{Math.Round(percent * 1.66, 1):0.0} MB    预计剩余：{(percent >= 100 ? "完成" : $"约 {Math.Max(1, (100 - percent) / 7)} 秒")}";
        if (percentChanged) UpdateProgressFill();
    }

    private void SetProgressFrame(int percent, string stage)
    {
        if (_progressCard is null || _progressStage is null || _progressPercent is null || _progressFill is null || _progressTrack is null) return;
        _progressCard.Visibility = Visibility.Visible;
        _progressStage.Text = stage;
        _progressPercent.Text = $"{percent}%";
        _progressCard.Tag = percent;
        _progressMeta!.Text = percent < 100 ? "本地离线安装 · 正在处理资源" : "本地离线安装 · 即将完成";
        _progressDetail!.Text = $"当前文件：{(percent < 40 ? "角色资源" : percent < 80 ? "LianYu.exe" : "快捷方式")}    已处理：{Math.Round(percent * 1.66, 1):0.0} MB    预计剩余：{(percent >= 100 ? "完成" : $"约 {Math.Max(1, (100 - percent) / 7)} 秒")}";
        _progressFill.BeginAnimation(WidthProperty, null);
        _progressFill.Width = Math.Max(0, _progressTrack.ActualWidth * percent / 100d);
    }

    private async Task AnimateDemoProgressAsync(int from, int to, string stage, int durationMs)
    {
        var stopwatch = System.Diagnostics.Stopwatch.StartNew();
        while (stopwatch.ElapsedMilliseconds < durationMs)
        {
            var t = Math.Clamp(stopwatch.Elapsed.TotalMilliseconds / durationMs, 0, 1);
            var eased = 1 - Math.Pow(1 - t, 3);
            var value = (int)Math.Round(from + (to - from) * eased);
            InstallButton.Content = $"正在安装… {value}%";
            SetProgressFrame(value, stage);
            StatusText.Text = $"{stage} · LianYu.exe";
            await Task.Delay(33);
        }
        InstallButton.Content = $"正在安装… {to}%";
        SetProgressFrame(to, stage);
    }

    private void UpdateProgressFill()
    {
        if (_progressCard?.Tag is not int percent || _progressFill is null || _progressTrack is null) return;
        var target = Math.Max(0, _progressTrack.ActualWidth * percent / 100d);
        var from = _progressFill.ActualWidth;
        if (double.IsNaN(from) || double.IsInfinity(from)) from = 0;
        _progressFill.BeginAnimation(WidthProperty, null);
        _progressFill.Width = from;
        var animation = new DoubleAnimationUsingKeyFrames();
        animation.KeyFrames.Add(new SplineDoubleKeyFrame(
            target,
            KeyTime.FromTimeSpan(TimeSpan.FromMilliseconds(280)),
            new KeySpline(.23, 1, .32, 1)));
        _progressFill.BeginAnimation(WidthProperty, animation, HandoffBehavior.SnapshotAndReplace);
    }

    private void RotateImages()
    {
        if (_transitioning) return;
        _transitioning = true;
        _imageIndex = (_imageIndex + 1) % _images.Length;
        // Keep the straight front card visible during the swap. Fading it to zero
        // exposes the decorative tilted card underneath and looks like a jump.
        var fadeOut = new DoubleAnimation(1, .78, TimeSpan.FromMilliseconds(700))
        {
            EasingFunction = new CubicEase { EasingMode = EasingMode.EaseIn }
        };
        fadeOut.Completed += (_, _) =>
        {
            ImageFront.BeginAnimation(OpacityProperty, null);
            ImageFront.Source = _imageCache[_images[_imageIndex]];
            ImageFront.Opacity = .78;
            var fadeIn = new DoubleAnimation(.78, 1, TimeSpan.FromMilliseconds(820))
            {
                EasingFunction = new CubicEase { EasingMode = EasingMode.EaseOut }
            };
            fadeIn.Completed += (_, _) =>
            {
                ImageFront.BeginAnimation(OpacityProperty, null);
                ImageFront.Opacity = 1;
                _transitioning = false;
            };
            ImageFront.BeginAnimation(OpacityProperty, fadeIn, HandoffBehavior.SnapshotAndReplace);
        };
        ImageFront.BeginAnimation(OpacityProperty, fadeOut, HandoffBehavior.SnapshotAndReplace);
    }

    private async void Install_Click(object sender, RoutedEventArgs e)
    {
        if (_demoCompleted)
        {
            Close();
            return;
        }
        if (_installing) return;
        _installing = true;
        InstallButton.IsEnabled = false;
        var allUsers = AllUsersRadio.IsChecked == true;
        if (allUsers) InstallDirectoryBox.Text = InstallerEngine.GetDefaultInstallDirectory(true);
        var options = new InstallOptions(InstallDirectoryBox.Text.Trim(), allUsers, DesktopBox.IsChecked == true, StartMenuBox.IsChecked == true, AutoStartBox.IsChecked == true, LaunchBox.IsChecked == true);
        try
        {
            if (!InstallerEngine.PayloadAvailable)
            {
                await RunDemoInstallAsync();
                return;
            }
            var progress = new Progress<InstallProgress>(UpdateProgress);
            await _engine.InstallAsync(options, progress, CancellationToken.None);
            StatusText.Text = "安装完成 · 正在启动恋语";
            if (options.LaunchAfterInstall) InstallerEngine.Launch(options.InstallDirectory);
            await Task.Delay(900);
            Close();
        }
        catch (Exception ex)
        {
            StatusText.Text = ex.Message;
            InstallButton.Content = "重试安装";
            InstallButton.IsEnabled = true;
            _installing = false;
        }
    }

    private async Task RunDemoInstallAsync()
    {
        PackageInfo.Visibility = Visibility.Collapsed;
        HeroDescription.Text = "角色会记得你的习惯、情绪和那些小小的日常。\n安装期间，你可以在下方查看实时进度。";
        HeroDescription.Visibility = Visibility.Visible;
        SettingsPanel.Visibility = Visibility.Collapsed;
        CustomButton.Visibility = Visibility.Collapsed;
        InstallButton.IsEnabled = false;
        var stages = new[]
        {
            ("正在准备安装环境", "检查磁盘空间与安装目录", 12),
            ("正在解压角色资源", "整理角色图片、语音与界面资源", 38),
            ("正在写入桌面应用", "部署 LianYu 主程序与运行组件", 67),
            ("正在创建快捷方式", "写入桌面和开始菜单入口", 89),
            ("正在完成安装", "保存安装信息并准备首次启动", 100),
        };
        var previousPercent = 0;
        for (var index = 0; index < stages.Length; index++)
        {
            var (stage, summary, percent) = stages[index];
            await AnimateDemoProgressAsync(previousPercent, percent, stage, 1700);
            previousPercent = percent;
            await Task.Delay(280);
        }
        _demoCompleted = true;
        HeroTitle.Text = "恋语已安装，\n等你打开。";
        HeroDescription.Text = "安装完成。你的桌面陪伴已经准备好了。\n点击下面的按钮结束预览。";
        HeroDescription.Visibility = Visibility.Visible;
        InstallButton.Content = "立即体验";
        InstallButton.IsEnabled = true;
        StatusText.Text = "安装完成 · 预览模式未写入系统";
    }

    private void UpdateProgress(InstallProgress value)
    {
        InstallButton.Content = $"正在安装… {value.Percent}%";
        SetProgress(value.Percent, value.Stage);
        StatusText.Text = $"{value.Stage} · {value.CurrentFile}";
    }
}
