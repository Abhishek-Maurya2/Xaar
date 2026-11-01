package com.example.androidapp

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

// Must extend AppCompatActivity for Material 3 and Night Mode support
class MainActivity : AppCompatActivity() {
    companion object {
        private const val discoveryPort = 45454
        private const val connectionPort = 45455
    }

    private val deviceId = "${Build.SERIAL}-${System.nanoTime()}"
    private val deviceName = Build.MODEL
    private val devices = ConcurrentHashMap<String, Device>()
    private val executor: ExecutorService = Executors.newCachedThreadPool()
    private lateinit var devicePanel: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var homeView: LinearLayout
    private lateinit var settingsView: LinearLayout
    @Volatile private var connection: Socket? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply Material 3 Dynamic Colors (wallpaper-based theming)
        DynamicColors.applyToActivityIfAvailable(this)
        
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        devicePanel = findViewById(R.id.device_panel)
        statusText = findViewById(R.id.status_text)
        homeView = findViewById(R.id.home_view)
        settingsView = findViewById(R.id.settings_view)
        findViewById<TextView>(R.id.device_name).text = deviceName

        findViewById<BottomNavigationView>(R.id.bottom_navigation)
            .setOnItemSelectedListener { item ->
                showPage(item.itemId == R.id.nav_home)
                true
            }

        setupThemeControls()

        executor.execute(::listenForDiscovery)
        executor.execute(::listenForConnections)
        executor.execute(::broadcastPresence)
        executor.execute(::expireDevices)
    }

    private fun showPage(home: Boolean) {
        homeView.visibility = if (home) View.VISIBLE else View.GONE
        settingsView.visibility = if (home) View.GONE else View.VISIBLE
    }

    // Generate dynamic theme controls for the settings view
    private fun setupThemeControls() {
        val themeToggleBtn = MaterialButton(this).apply {
            text = "Toggle Light / Dark Mode"
            cornerRadius = 28
            setOnClickListener {
                val currentMode = AppCompatDelegate.getDefaultNightMode()
                if (currentMode == AppCompatDelegate.MODE_NIGHT_YES) {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                } else {
                    AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                }
            }
        }
        
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        
        content.addView(TextView(this).apply {
            text = "Appearance"
            textSize = 20f
            setPadding(0, 0, 0, 20)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnBackground, Color.BLACK))
        })
        
        content.addView(themeToggleBtn)
        settingsView.addView(content)
    }

    private fun showConnected(device: Device) {
        runOnUiThread {
            devicePanel.removeAllViews()
            statusText.text = "Connected"
            
            val card = MaterialCardView(this).apply {
                radius = 28f
                // Removed hardcoded RGB; using M3 semantic attributes
                setCardBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceVariant, Color.DKGRAY))
                setStrokeColor(getColor(R.color.primary))
                strokeWidth = 1
            }
            
            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 40, 40, 40)
            }
            
            content.addView(TextView(this).apply {
                text = device.name
                textSize = 20f
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.WHITE))
            })
            
            content.addView(TextView(this).apply {
                text = "Connected over Wi-Fi at ${device.address.hostAddress}"
                setPadding(0, 8, 0, 0)
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.LTGRAY))
            })
            
            card.addView(content)
            devicePanel.addView(card)

            devicePanel.addView(MaterialButton(this).apply {
                text = "Disconnect"
                setIconResource(android.R.drawable.ic_menu_close_clear_cancel)
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                isAllCaps = false
                cornerRadius = 28
                val marginParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 20, 0, 0) }
                layoutParams = marginParams
                
                setOnClickListener {
                    closeConnection(connection)
                    connection = null
                    showDevices()
                }
            })
        }
    }

    private fun listenForDiscovery() {
        try {
            DatagramSocket(discoveryPort).use { socket ->
                val buffer = ByteArray(512)
                while (!Thread.currentThread().isInterrupted) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val parts = String(packet.data, 0, packet.length, StandardCharsets.UTF_8).split("|")
                    if (parts.size != 3 || parts[0] != "DEVICE") continue
                    val identity = parts[1].split("~", limit = 2)
                    if (identity.size != 2 || identity[0] == deviceId) continue
                    val port = parts[2].toIntOrNull() ?: continue
                    val endpoint = packet.address.hostAddress ?: continue
                    devices[endpoint] = Device(identity[0], identity[1], packet.address, port, System.currentTimeMillis())
                    removeExpiredDevices()
                    runOnUiThread(::showDevices)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun broadcastPresence() {
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                val payload = "DEVICE|$deviceId~$deviceName|$connectionPort".toByteArray(StandardCharsets.UTF_8)
                while (!Thread.currentThread().isInterrupted) {
                    socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName("255.255.255.255"), discoveryPort))
                    Thread.sleep(2000)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun listenForConnections() {
        try {
            ServerSocket(connectionPort).use { server ->
                while (!Thread.currentThread().isInterrupted) {
                    val socket = server.accept()
                    executor.execute { acceptConnection(socket) }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun acceptConnection(socket: Socket) {
        try {
            val hello = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                .readLine().split("|", limit = 2)
            PrintWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)
                .println("HELLO|$deviceName")
            if (hello.size == 2 && hello[0] == "HELLO") {
                closeConnection(connection)
                connection = socket
                showConnected(Device(socket.inetAddress.toString(), hello[1], socket.inetAddress, connectionPort, System.currentTimeMillis()))
                monitorConnection(socket)
            } else {
                socket.close()
            }
        } catch (_: Exception) {
            closeConnection(socket)
        }
    }

    private fun connect(device: Device) {
        executor.execute {
            try {
                closeConnection(connection)
                val socket = Socket(device.address, device.port)
                PrintWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)
                    .println("HELLO|$deviceName")
                val hello = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                    .readLine().split("|", limit = 2)
                if (hello.size == 2 && hello[0] == "HELLO") {
                    connection = socket
                    showConnected(device.copy(name = hello[1], lastSeen = System.currentTimeMillis()))
                    monitorConnection(socket)
                } else {
                    socket.close()
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun showDevices() {
        if (connection != null) return
        devicePanel.removeAllViews()
        statusText.text = if (devices.isEmpty()) "Searching on this Wi-Fi network..." else "Select a device to connect."
        devices.values.forEach { device ->
            val button = MaterialButton(this).apply {
                text = "${device.name} (${device.address.hostAddress})"
                setIconResource(android.R.drawable.ic_menu_share)
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                cornerRadius = 28
                textSize = 16f
                isAllCaps = false
                minHeight = 64
                setOnClickListener { connect(device) }
            }
            devicePanel.addView(button)
        }
    }

    private fun monitorConnection(socket: Socket) {
        executor.execute {
            try {
                socket.getInputStream().read()
            } catch (_: Exception) {
            }
            if (connection === socket) {
                connection = null
                runOnUiThread(::showDevices)
            }
        }
    }

    private fun removeExpiredDevices() {
        val cutoff = System.currentTimeMillis() - 6000
        devices.entries.removeIf { it.value.lastSeen < cutoff }
    }

    private fun expireDevices() {
        try {
            while (!Thread.currentThread().isInterrupted) {
                Thread.sleep(2000)
                removeExpiredDevices()
                if (connection == null) runOnUiThread(::showDevices)
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun closeConnection(socket: Socket?) {
        try {
            socket?.close()
        } catch (_: Exception) {
        }
    }

    private data class Device(
        val id: String,
        val name: String,
        val address: InetAddress,
        val port: Int,
        val lastSeen: Long
    )
}