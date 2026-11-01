package com.example.androidapp;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Iterator;

public final class MainActivity extends Activity {
    private static final int DISCOVERY_PORT = 45454;
    private static final int CONNECTION_PORT = 45455;
    private final String deviceId = Build.SERIAL + "-" + System.nanoTime();
    private final String deviceName = Build.MODEL;
    private final Map<String, Device> devices = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private LinearLayout devicePanel;
    private TextView statusText;
    private volatile Socket connection;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        devicePanel = findViewById(R.id.device_panel);
        statusText = findViewById(R.id.status_text);
        executor.execute(this::listenForDiscovery);
        executor.execute(this::listenForConnections);
        executor.execute(this::broadcastPresence);
        executor.execute(this::expireDevices);
    }

    private void listenForDiscovery() {
        try (DatagramSocket socket = new DatagramSocket(DISCOVERY_PORT)) {
            byte[] buffer = new byte[512];
            while (true) {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                String[] parts = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8).split("\\|");
                if (parts.length != 3 || !parts[0].equals("DEVICE")) continue;
                String[] identity = parts[1].split("~", 2);
                if (identity.length != 2 || identity[0].equals(deviceId)) continue;
                String endpoint = packet.getAddress().getHostAddress();
                devices.put(endpoint, new Device(identity[0], identity[1], packet.getAddress(), Integer.parseInt(parts[2]), System.currentTimeMillis()));
                removeExpiredDevices();
                runOnUiThread(this::showDevices);
            }
        } catch (Exception ignored) {
        }
    }

    private void removeExpiredDevices() {
        long cutoff = System.currentTimeMillis() - 6000;
        Iterator<Map.Entry<String, Device>> iterator = devices.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Device> entry = iterator.next();
            if (entry.getValue().lastSeen < cutoff) devices.remove(entry.getKey(), entry.getValue());
        }
    }

    private void expireDevices() {
        try {
            while (true) {
                Thread.sleep(2000);
                removeExpiredDevices();
                if (connection == null) runOnUiThread(this::showDevices);
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void broadcastPresence() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            byte[] payload = ("DEVICE|" + deviceId + "~" + deviceName + "|" + CONNECTION_PORT).getBytes(StandardCharsets.UTF_8);
            while (true) {
                socket.send(new DatagramPacket(payload, payload.length, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT));
                Thread.sleep(2000);
            }
        } catch (Exception ignored) {
        }
    }

    private void listenForConnections() {
        try (ServerSocket server = new ServerSocket(CONNECTION_PORT)) {
            while (true) {
                Socket socket = server.accept();
                executor.execute(() -> acceptConnection(socket));
            }
        } catch (Exception ignored) {
        }
    }

    private void acceptConnection(Socket socket) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String[] hello = reader.readLine().split("\\|", 2);
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
            writer.println("HELLO|" + deviceName);
            if (hello.length == 2 && hello[0].equals("HELLO")) {
                connection = socket;
                Device connected = new Device(socket.getInetAddress().toString(), hello[1], socket.getInetAddress(), CONNECTION_PORT, System.currentTimeMillis());
                showConnected(connected);
                monitorConnection(socket);
            }
        } catch (Exception ignored) {
        }
    }

    private void connect(Device device) {
        executor.execute(() -> {
            try {
                closeConnection(connection);
                Socket socket = new Socket(device.address, device.port);
                PrintWriter writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true);
                writer.println("HELLO|" + deviceName);
                String[] hello = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine().split("\\|", 2);
                if (hello.length == 2 && hello[0].equals("HELLO")) {
                    connection = socket;
                    showConnected(new Device(device.id, hello[1], device.address, device.port, System.currentTimeMillis()));
                    monitorConnection(socket);
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void showDevices() {
        if (connection != null) return;
        devicePanel.removeAllViews();
        statusText.setText(devices.isEmpty() ? "Searching on this Wi-Fi network..." : "Select a device to connect.");
        for (Device device : devices.values()) {
            Button button = new Button(this);
            button.setText(device.name + " (" + device.address.getHostAddress() + ")");
            button.setOnClickListener(v -> connect(device));
            devicePanel.addView(button);
        }
    }

    private void showConnected(Device device) {
        runOnUiThread(() -> {
            devicePanel.removeAllViews();
            statusText.setText("Connected device");
            TextView name = new TextView(this);
            name.setText(device.name);
            name.setTextSize(22);
            name.setTextColor(Color.WHITE);
            devicePanel.addView(name);
            Button disconnect = new Button(this);
            disconnect.setText("Disconnect");
            disconnect.setOnClickListener(v -> {
                closeConnection(connection);
                connection = null;
                showDevices();
            });
            devicePanel.addView(disconnect);
        });
    }

    private static void closeConnection(Socket socket) {
        if (socket != null) try { socket.close(); } catch (Exception ignored) { }
    }

    private void monitorConnection(Socket socket) {
        executor.execute(() -> {
            try {
                socket.getInputStream().read();
            } catch (Exception ignored) {
            }
            if (connection == socket) {
                connection = null;
                runOnUiThread(this::showDevices);
            }
        });
    }

    private static final class Device {
        final String id;
        final String name;
        final InetAddress address;
        final int port;
        final long lastSeen;

        Device(String id, String name, InetAddress address, int port, long lastSeen) {
            this.id = id;
            this.name = name;
            this.address = address;
            this.port = port;
            this.lastSeen = lastSeen;
        }
    }
}
