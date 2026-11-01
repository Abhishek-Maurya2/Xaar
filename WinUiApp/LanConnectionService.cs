using System.Collections.Concurrent;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Text.Json;
using System.Runtime.InteropServices;

namespace WinUiApp;

public sealed record LanDevice(string Id, string Name, IPAddress Address, int Port);

public sealed class LanConnectionService : IAsyncDisposable
{
    private const int DiscoveryPort = 45454;
    private const int ConnectionPort = 45455;
    private readonly string _id = Guid.NewGuid().ToString("N");
    private readonly string _name = Environment.MachineName;
    private readonly ConcurrentDictionary<string, LanDevice> _devices = new();
    private readonly ConcurrentDictionary<string, DateTime> _lastSeen = new();
    private readonly CancellationTokenSource _stop = new();
    private TcpListener? _listener;
    private TcpClient? _connection;

    public event Action<IReadOnlyCollection<LanDevice>>? DevicesChanged;
    public event Action<LanDevice?>? ConnectionChanged;
    public event Action<PhoneStatus>? PhoneStatusReceived;
    public event Action<PcStatus>? PcStatusReceived;
    public event Action<byte[]>? PhoneWallpaperReceived;
    public event Action<byte[]>? PcWallpaperReceived;
    public LanDevice? ConnectedDevice { get; private set; }

    public Task StartAsync()
    {
        _ = ListenForDiscoveryAsync(_stop.Token);
        _ = ListenForConnectionsAsync(_stop.Token);
        _ = BroadcastPresenceAsync(_stop.Token);
        _ = RemoveExpiredDevicesAsync(_stop.Token);
        return Task.CompletedTask;
    }

    public async Task ConnectAsync(LanDevice device)
    {
        await DisconnectAsync();
        try
        {
            var client = new TcpClient();
            await client.ConnectAsync(device.Address, device.Port);
            await SendHelloAsync(client.GetStream(), _stop.Token);
            var remoteName = await ReadHelloAsync(client.GetStream(), _stop.Token);
            _connection = client;
            ConnectedDevice = device with { Name = remoteName };
            ConnectionChanged?.Invoke(ConnectedDevice);
            _ = SendPcStatusAsync(client);
            _ = SendPcWallpaperAsync(client);
            _ = MonitorConnectionAsync(client);
        }
        catch
        {
            await DisconnectAsync();
        }
    }

    public async Task DisconnectAsync()
    {
        _connection?.Dispose();
        _connection = null;
        if (ConnectedDevice is not null)
        {
            ConnectedDevice = null;
            ConnectionChanged?.Invoke(null);
        }

        await Task.CompletedTask;
    }

    private async Task ListenForDiscoveryAsync(CancellationToken token)
    {
        using var udp = new UdpClient(DiscoveryPort);
        while (!token.IsCancellationRequested)
        {
            try
            {
                var result = await udp.ReceiveAsync(token);
                var parts = Encoding.UTF8.GetString(result.Buffer).Split('|');
                var nameParts = parts.Length > 1 ? parts[1].Split('~', 2) : [];
                if (parts.Length != 3 || parts[0] != "DEVICE" || nameParts.Length != 2 || nameParts[0] == _id || !int.TryParse(parts[2], out var port))
                    continue;

                var endpoint = $"{result.RemoteEndPoint.Address}:{port}";
                _devices[endpoint] = new LanDevice(nameParts[0], nameParts[1], result.RemoteEndPoint.Address, port);
                _lastSeen[endpoint] = DateTime.UtcNow;
                DevicesChanged?.Invoke(_devices.Values.ToArray());
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (SocketException)
            {
            }
        }
    }

    private async Task RemoveExpiredDevicesAsync(CancellationToken token)
        {
            while (!token.IsCancellationRequested)
            {
                try
                {
                    await Task.Delay(TimeSpan.FromSeconds(2), token);
                    var cutoff = DateTime.UtcNow.AddSeconds(-6);
                    foreach (var item in _lastSeen)
                    {
                        if (item.Value < cutoff && _lastSeen.TryRemove(item.Key, out _))
                            _devices.TryRemove(item.Key, out _);
                    }

                    DevicesChanged?.Invoke(_devices.Values.ToArray());
                }
                catch (OperationCanceledException)
                {
                    break;
            }
        }
    }

    private async Task BroadcastPresenceAsync(CancellationToken token)
    {
        using var udp = new UdpClient { EnableBroadcast = true };
        var payload = Encoding.UTF8.GetBytes($"DEVICE|{_id}~{_name}|{ConnectionPort}");
        while (!token.IsCancellationRequested)
        {
            try
            {
                await udp.SendAsync(payload, new IPEndPoint(IPAddress.Broadcast, DiscoveryPort), token);
                await Task.Delay(TimeSpan.FromSeconds(2), token);
            }
            catch (OperationCanceledException)
            {
                break;
            }
        }
    }

    private async Task ListenForConnectionsAsync(CancellationToken token)
    {
        _listener = new TcpListener(IPAddress.Any, ConnectionPort);
        _listener.Start();
        while (!token.IsCancellationRequested)
        {
            try
            {
                var client = await _listener.AcceptTcpClientAsync(token);
                _ = HandleIncomingConnectionAsync(client, token);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (SocketException)
            {
            }
        }
    }

    private async Task HandleIncomingConnectionAsync(TcpClient client, CancellationToken token)
    {
        try
        {
            var stream = client.GetStream();
            var remoteName = await ReadHelloAsync(stream, token);
            await SendHelloAsync(stream, token);
            await DisconnectAsync();
            _connection = client;
            var address = ((IPEndPoint)client.Client.RemoteEndPoint!).Address;
            ConnectedDevice = new LanDevice(client.Client.RemoteEndPoint!.ToString()!, remoteName, address, ConnectionPort);
            ConnectionChanged?.Invoke(ConnectedDevice);
            await SendPcStatusAsync(client);
            await SendPcWallpaperAsync(client);
            await MonitorConnectionAsync(client);
        }
        catch
        {
            client.Dispose();
        }
    }

    private async Task MonitorConnectionAsync(TcpClient client)
    {
        try
        {
            using var reader = new StreamReader(client.GetStream(), Encoding.UTF8, leaveOpen: true);
            while (!_stop.IsCancellationRequested)
            {
                var line = await reader.ReadLineAsync(_stop.Token);
                if (line is null)
                    break;
                ProcessMessage(line);
            }
        }
        catch
        {
        }

        if (ReferenceEquals(_connection, client))
            await DisconnectAsync();
    }

    private void ProcessMessage(string message)
        {
            var parts = message.Split('|', 3);
            if (parts.Length != 3 || parts[0] != "MSG")
                return;

            try
            {
                switch (parts[1])
                {
                    case "STATUS_PHONE":
                        var phone = JsonSerializer.Deserialize<PhoneStatus>(parts[2]);
                        if (phone is not null) PhoneStatusReceived?.Invoke(phone);
                        break;
                    case "WALLPAPER_PHONE":
                        PhoneWallpaperReceived?.Invoke(Convert.FromBase64String(parts[2]));
                        break;
                    case "STATUS_PC":
                        var pc = JsonSerializer.Deserialize<PcStatus>(parts[2]);
                        if (pc is not null) PcStatusReceived?.Invoke(pc);
                        break;
                    case "WALLPAPER_PC":
                        PcWallpaperReceived?.Invoke(Convert.FromBase64String(parts[2]));
                        break;
                }
            }
            catch (JsonException)
            {
            }
            catch (FormatException)
            {
            }
        }

    private async Task SendPcStatusAsync(TcpClient client)
        {
            var battery = 100;
            var charging = false;
            if (GetSystemPowerStatus(out var power))
            {
                battery = power.BatteryLifePercent;
                charging = power.ACLineStatus == 1;
            }

            var wifi = NetworkInterface.GetAllNetworkInterfaces()
                .Any(network => network.NetworkInterfaceType == NetworkInterfaceType.Wireless80211 &&
                               network.OperationalStatus == OperationalStatus.Up);
            await SendMessageAsync(client, "STATUS_PC", JsonSerializer.Serialize(new PcStatus(battery, charging, wifi)));
        }

    private async Task SendPcWallpaperAsync(TcpClient client)
        {
            var path = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),
                @"Microsoft\Windows\Themes\TranscodedWallpaper");
            if (File.Exists(path))
                await SendMessageAsync(client, "WALLPAPER_PC", Convert.ToBase64String(await File.ReadAllBytesAsync(path)));
        }

    private async Task SendMessageAsync(TcpClient client, string type, string payload)
        {
            await StreamWriteAsync(client, $"MSG|{type}|{payload}\n");
        }

    private static readonly SemaphoreSlim WriteLock = new(1, 1);
    private static async Task StreamWriteAsync(TcpClient client, string message)
        {
            await WriteLock.WaitAsync();
            try
            {
                await using var writer = new StreamWriter(client.GetStream(), Encoding.UTF8, leaveOpen: true);
                await writer.WriteLineAsync(message.AsMemory());
                await writer.FlushAsync();
            }
            finally
            {
                WriteLock.Release();
            }
        }

    [StructLayout(LayoutKind.Sequential)]
    private struct SYSTEM_POWER_STATUS
        {
            public byte ACLineStatus;
            public byte BatteryFlag;
            public byte BatteryLifePercent;
            public byte SystemStatusFlag;
            public int BatteryLifeTime;
            public int BatteryFullLifeTime;
        }

    [DllImport("kernel32.dll")]
    private static extern bool GetSystemPowerStatus(out SYSTEM_POWER_STATUS status);

    private Task SendHelloAsync(NetworkStream stream, CancellationToken token)
    {
        var bytes = Encoding.UTF8.GetBytes($"HELLO|{_name}\n");
        return stream.WriteAsync(bytes, token).AsTask();
    }

    private static async Task<string> ReadHelloAsync(NetworkStream stream, CancellationToken token)
    {
        var data = new List<byte>();
        var buffer = new byte[1];
        while (data.Count < 256 && await stream.ReadAsync(buffer, token) > 0)
        {
            if (buffer[0] == '\n')
                break;
            data.Add(buffer[0]);
        }

        var message = Encoding.UTF8.GetString(data.ToArray()).Split('|', 2);
        return message.Length == 2 && message[0] == "HELLO" ? message[1] : "Unknown device";
    }

    public async ValueTask DisposeAsync()
    {
        _stop.Cancel();
        _listener?.Stop();
        await DisconnectAsync();
        _stop.Dispose();
    }
}
