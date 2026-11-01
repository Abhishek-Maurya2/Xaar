using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml;

// To learn more about WinUI, the WinUI project structure,
// and more about our project templates, see: http://aka.ms/winui-project-info.

namespace WinUiApp;

/// <summary>
/// The main content page displayed inside the application window.
/// Add your UI logic, event handlers, and data binding here.
/// </summary>
public sealed partial class MainPage : Page
{
    private readonly LanConnectionService _service = new();

    public MainPage()
    {
        InitializeComponent();
        _service.DevicesChanged += devices => DispatcherQueue.TryEnqueue(() => ShowDevices(devices));
        _service.ConnectionChanged += device => DispatcherQueue.TryEnqueue(() => ShowConnection(device));
        _ = _service.StartAsync();
    }

    private void ShowDevices(IReadOnlyCollection<LanDevice> devices)
    {
        if (_service.ConnectedDevice is not null)
            return;

        DevicePanel.Children.Clear();
        foreach (var device in devices)
        {
            var button = new Button { Content = $"{device.Name} ({device.Address})", HorizontalContentAlignment = HorizontalAlignment.Stretch };
            button.Click += async (_, _) => await _service.ConnectAsync(device);
            DevicePanel.Children.Add(button);
        }

        StatusText.Text = devices.Count == 0 ? "Searching on this Wi-Fi network..." : "Select a device to connect.";
    }

    private void ShowConnection(LanDevice? device)
    {
        DevicePanel.Children.Clear();
        if (device is null)
        {
            StatusText.Text = "Disconnected. Searching on this Wi-Fi network...";
            return;
        }

        StatusText.Text = "Connected device";
        DevicePanel.Children.Add(new TextBlock { Text = device.Name, FontSize = 22 });
        var disconnect = new Button { Content = "Disconnect", Margin = new Thickness(0, 12, 0, 0) };
        disconnect.Click += async (_, _) => await _service.DisconnectAsync();
        DevicePanel.Children.Add(disconnect);
    }
}
