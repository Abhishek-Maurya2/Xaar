namespace WinUiApp;

public sealed record PhoneStatus(int Battery, bool Wifi, bool Bluetooth, string Sound);
public sealed record PcStatus(int Battery, bool IsCharging, bool Wifi);
