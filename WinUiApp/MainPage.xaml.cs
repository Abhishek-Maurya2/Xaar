using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace WinUiApp;

public sealed partial class MainPage : Page
{
    private readonly LanConnectionService _service = new();
    private TextBlock? _remoteStatus;
    public string DeviceName { get; } = Environment.MachineName;

    public MainPage()
    {
        InitializeComponent();
        AppNavigation.SelectedItem = AppNavigation.MenuItems[0];
        _service.DevicesChanged += devices => DispatcherQueue.TryEnqueue(() => ShowDevices(devices));
        _service.ConnectionChanged += device => DispatcherQueue.TryEnqueue(() => ShowConnection(device));
        _service.PhoneStatusReceived += status => DispatcherQueue.TryEnqueue(() =>
        {
            if (_remoteStatus is not null)
                _remoteStatus.Text = $"Phone: {status.Battery}% battery • {(status.Wifi ? "Wi-Fi" : "offline")} • {status.Sound}";
        });
        _ = _service.StartAsync();
    }

    private void OnNavigationSelectionChanged(NavigationView sender, NavigationViewSelectionChangedEventArgs args)
    {
        var isSettings = args.SelectedItemContainer?.Tag?.ToString() == "settings";
        HomeView.Visibility = isSettings ? Visibility.Collapsed : Visibility.Visible;
        SettingsView.Visibility = isSettings ? Visibility.Visible : Visibility.Collapsed;
    }

    private void ShowDevices(IReadOnlyCollection<LanDevice> devices)
    {
        if (_service.ConnectedDevice is not null)
            return;

        DevicePanel.Children.Clear();
        if (devices.Count == 0)
        {
            DevicePanel.Children.Add(new InfoBar
            {
                IsOpen = true,
                Severity = InfoBarSeverity.Informational,
                Title = "Looking for devices",
                Message = "Keep both devices on the same Wi-Fi network."
            });
        }
        else
        {
            foreach (var device in devices)
            {
                var connect = new Button
                {
                    HorizontalAlignment = HorizontalAlignment.Stretch,
                    HorizontalContentAlignment = HorizontalAlignment.Left,
                    Padding = new Thickness(16, 12, 16, 12),
                    Content = new StackPanel
                    {
                        Orientation = Orientation.Horizontal,
                        Spacing = 12,
                        Children =
                        {
                            new SymbolIcon { Symbol = Symbol.Contact },
                            new StackPanel
                            {
                                Children =
                                {
                                    new TextBlock { Text = device.Name, FontSize = 17, FontWeight = Microsoft.UI.Text.FontWeights.SemiBold },
                                    new TextBlock { Text = device.Address.ToString(), Opacity = 0.65 }
                                }
                            }
                        }
                    }
                };
                connect.Click += async (_, _) => await _service.ConnectAsync(device);
                DevicePanel.Children.Add(connect);
            }
        }

        StatusText.Text = devices.Count == 0 ? "Searching on this Wi-Fi network..." : "Select a device to connect.";
    }

    private void ShowConnection(LanDevice? device)
    {
        DevicePanel.Children.Clear();
        if (device is null)
        {
            _remoteStatus = null;
            StatusText.Text = "Disconnected. Searching on this Wi-Fi network...";
            return;
        }

        StatusText.Text = "Connected";
        DevicePanel.Children.Add(new InfoBar
        {
            IsOpen = true,
            Severity = InfoBarSeverity.Success,
            Title = device.Name,
            Message = $"Connected over Wi-Fi at {device.Address}"
        });
        _remoteStatus = new TextBlock
        {
            Text = "Waiting for phone status...",
            Opacity = 0.75,
            Margin = new Thickness(0, 12, 0, 0)
        };
        DevicePanel.Children.Add(_remoteStatus);
        var disconnect = new Button
        {
            Content = new StackPanel
            {
                Orientation = Orientation.Horizontal,
                Spacing = 8,
                Children = { new SymbolIcon { Symbol = Symbol.UnFavorite }, new TextBlock { Text = "Disconnect" } }
            }
        };
        disconnect.Click += async (_, _) => await _service.DisconnectAsync();
        DevicePanel.Children.Add(disconnect);
    }
}
