package com.example.androidapp

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    companion object {
        private const val DISCOVERY_PORT = 45454
        private const val CONNECTION_PORT = 45455
        private const val PREFS_NAME = "xaar_prefs"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_SELECTED_TAB = "selected_tab"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var deviceId: String
    private val deviceName: String = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    private val devices = ConcurrentHashMap<String, Device>()
    private val executor: ExecutorService = Executors.newCachedThreadPool()

    private lateinit var devicePanel: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var heroTitle: TextView
    private lateinit var discoveryProgress: LinearProgressIndicator
    private lateinit var sectionDevicesLabel: TextView
    private lateinit var homeView: View
    private lateinit var settingsView: View
    private lateinit var bottomNav: BottomNavigationView

    @Volatile private var isRunning = true
    @Volatile private var connection: Socket? = null
    @Volatile private var discoverySocket: DatagramSocket? = null
    @Volatile private var serverSocket: ServerSocket? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        applySavedTheme()

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)

        deviceId = getOrCreateDeviceId()

        bindViews()
        setupWindowInsets()
        setupNavigation(savedInstanceState)
        setupThemeControls()

        startNetworkWorkers()
        showDevices()
    }

    private fun applySavedTheme() {
        val savedNightMode = prefs.getInt(KEY_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        if (AppCompatDelegate.getDefaultNightMode() != savedNightMode) {
            AppCompatDelegate.setDefaultNightMode(savedNightMode)
        }

        val useDynamicColors = prefs.getBoolean(KEY_DYNAMIC_COLOR, true)
        if (useDynamicColors && DynamicColors.isDynamicColorAvailable()) {
            DynamicColors.applyToActivityIfAvailable(this)
        }
    }

    private fun getOrCreateDeviceId(): String {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (existing != null) return existing
        val created = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, created).apply()
        return created
    }

    private fun bindViews() {
        devicePanel = findViewById(R.id.device_panel)
        statusText = findViewById(R.id.status_text)
        heroTitle = findViewById(R.id.hero_title)
        discoveryProgress = findViewById(R.id.discovery_progress)
        sectionDevicesLabel = findViewById(R.id.section_devices_label)
        homeView = findViewById(R.id.home_view)
        settingsView = findViewById(R.id.settings_view)
        bottomNav = findViewById(R.id.bottom_navigation)

        findViewById<TextView>(R.id.device_name).text = deviceName
    }

    private fun setupWindowInsets() {
        val rootLayout = findViewById<View>(R.id.root_layout)
        ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            findViewById<View>(R.id.toolbar).updatePadding(top = systemBars.top)
            bottomNav.updatePadding(bottom = systemBars.bottom)
            insets
        }
    }

    private fun setupNavigation(savedInstanceState: Bundle?) {
        bottomNav.setOnItemSelectedListener { item ->
            showPage(home = item.itemId == R.id.nav_home)
            true
        }
        val initialTab = savedInstanceState?.getInt(KEY_SELECTED_TAB) ?: R.id.nav_home
        bottomNav.selectedItemId = initialTab
        showPage(home = initialTab == R.id.nav_home)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (::bottomNav.isInitialized) {
            outState.putInt(KEY_SELECTED_TAB, bottomNav.selectedItemId)
        }
    }

    private fun setupThemeControls() {
        val toggleGroup = findViewById<MaterialButtonToggleGroup>(R.id.theme_toggle_group)
        val dynamicSwitch = findViewById<MaterialSwitch>(R.id.switch_dynamic_colors)

        val currentMode = prefs.getInt(KEY_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        val checkedButtonId = when (currentMode) {
            AppCompatDelegate.MODE_NIGHT_NO -> R.id.btn_theme_light
            AppCompatDelegate.MODE_NIGHT_YES -> R.id.btn_theme_dark
            else -> R.id.btn_theme_system
        }
        toggleGroup.check(checkedButtonId)

        toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val targetMode = when (checkedId) {
                R.id.btn_theme_light -> AppCompatDelegate.MODE_NIGHT_NO
                R.id.btn_theme_dark -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            if (prefs.getInt(KEY_THEME_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) != targetMode) {
                prefs.edit().putInt(KEY_THEME_MODE, targetMode).apply()
                AppCompatDelegate.setDefaultNightMode(targetMode)
            }
        }

        val supportsDynamic = DynamicColors.isDynamicColorAvailable()
        dynamicSwitch.isEnabled = supportsDynamic
        dynamicSwitch.isChecked = supportsDynamic && prefs.getBoolean(KEY_DYNAMIC_COLOR, true)
        if (!supportsDynamic) {
            dynamicSwitch.text = "Dynamic wallpaper colors (Requires Android 12+)"
        }

        dynamicSwitch.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, isChecked).apply()
            recreate()
        }
    }

    private fun showPage(home: Boolean) {
        homeView.visibility = if (home) View.VISIBLE else View.GONE
        settingsView.visibility = if (home) View.GONE else View.VISIBLE
    }

    private fun startNetworkWorkers() {
        executor.execute(::listenForDiscovery)
        executor.execute(::listenForConnections)
        executor.execute(::broadcastPresence)
        executor.execute(::expireDevices)
    }

    private fun listenForDiscovery() {
        try {
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(DISCOVERY_PORT))
                discoverySocket = socket
                val buffer = ByteArray(512)
                while (isRunning && !Thread.currentThread().isInterrupted) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val message = String(packet.data, 0, packet.length, StandardCharsets.UTF_8)
                    val parts = message.split("|")
                    if (parts.size != 3 || parts[0] != "DEVICE") continue
                    val identity = parts[1].split("~", limit = 2)
                    if (identity.size != 2 || identity[0] == deviceId) continue
                    val port = parts[2].toIntOrNull() ?: continue
                    val endpoint = packet.address.hostAddress ?: continue

                    devices[endpoint] = Device(
                        id = identity[0],
                        name = identity[1],
                        address = packet.address,
                        port = port,
                        lastSeen = System.currentTimeMillis()
                    )
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
                val payload = "DEVICE|$deviceId~$deviceName|$CONNECTION_PORT".toByteArray(StandardCharsets.UTF_8)
                val broadcastAddress = InetAddress.getByName("255.255.255.255")
                while (isRunning && !Thread.currentThread().isInterrupted) {
                    socket.send(DatagramPacket(payload, payload.size, broadcastAddress, DISCOVERY_PORT))
                    Thread.sleep(2000)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun listenForConnections() {
        try {
            ServerSocket().use { server ->
                server.reuseAddress = true
                server.bind(InetSocketAddress(CONNECTION_PORT))
                serverSocket = server
                while (isRunning && !Thread.currentThread().isInterrupted) {
                    val socket = server.accept()
                    executor.execute { acceptConnection(socket) }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun acceptConnection(socket: Socket) {
        try {
            val line = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine() ?: run {
                socket.close()
                return
            }
            val hello = line.split("|", limit = 2)
            PrintWriter(OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)
                .println("HELLO|$deviceName")

            if (hello.size == 2 && hello[0] == "HELLO") {
                closeConnection(connection)
                connection = socket
                val connectedDevice = Device(
                    id = socket.inetAddress.hostAddress ?: "remote",
                    name = hello[1],
                    address = socket.inetAddress,
                    port = CONNECTION_PORT,
                    lastSeen = System.currentTimeMillis()
                )
                showConnected(connectedDevice)
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
                val line = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine()
                val hello = line?.split("|", limit = 2)
                if (hello != null && hello.size == 2 && hello[0] == "HELLO") {
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
        if (connection != null || isFinishing || isDestroyed) return
        devicePanel.removeAllViews()

        heroTitle.text = "Connect devices"
        discoveryProgress.visibility = View.VISIBLE
        sectionDevicesLabel.text = "Available on LAN (${devices.size})"

        if (devices.isEmpty()) {
            statusText.text = "Searching on this Wi-Fi network..."
            val emptyCard = MaterialCardView(this).apply {
                radius = dp(24).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1)
                strokeColor = themeColor(com.google.android.material.R.attr.colorOutlineVariant)
                setCardBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurfaceContainerLow))
            }
            val emptyText = TextView(this).apply {
                text = "No peer devices detected yet.\nEnsure Xaar is open on your PC or another phone on the same Wi-Fi."
                setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
                setPadding(dp(24), dp(32), dp(24), dp(32))
            }
            emptyCard.addView(emptyText)
            devicePanel.addView(emptyCard)
            return
        }

        statusText.text = "Tap a discovered device below to pair."

        devices.values.sortedBy { it.name }.forEach { device ->
            val itemCard = MaterialCardView(this).apply {
                radius = dp(24).toFloat()
                cardElevation = 0f
                strokeWidth = 0
                isClickable = true
                isFocusable = true
                setCardBackgroundColor(themeColor(com.google.android.material.R.attr.colorSurfaceContainerHigh))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dp(12)
                }
                setOnClickListener { connect(device) }
            }

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(18), dp(20), dp(18))
            }

            val textContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val title = TextView(this).apply {
                text = device.name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurface))
            }

            val subtitle = TextView(this).apply {
                text = "${device.address.hostAddress}:${device.port}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setPadding(0, dp(2), 0, 0)
            }

            textContainer.addView(title)
            textContainer.addView(subtitle)

            val connectAction = MaterialButton(this).apply {
                text = "Connect"
                cornerRadius = dp(20)
                isClickable = false
                isFocusable = false
            }

            row.addView(textContainer)
            row.addView(connectAction)
            itemCard.addView(row)
            devicePanel.addView(itemCard)
        }
    }

    private fun showConnected(device: Device) {
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            devicePanel.removeAllViews()

            heroTitle.text = "Active Link"
            statusText.text = "Paired with ${device.name}"
            discoveryProgress.visibility = View.GONE
            sectionDevicesLabel.text = "Connection Details"

            val card = MaterialCardView(this).apply {
                radius = dp(28).toFloat()
                cardElevation = 0f
                strokeWidth = dp(2)
                strokeColor = themeColor(com.google.android.material.R.attr.colorPrimary)
                setCardBackgroundColor(themeColor(com.google.android.material.R.attr.colorSecondaryContainer))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }

            val content = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(24), dp(24), dp(24))
            }

            content.addView(TextView(this).apply {
                text = device.name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(themeColor(com.google.android.material.R.attr.colorOnSecondaryContainer))
            })

            content.addView(TextView(this).apply {
                text = "Connected over Wi-Fi • ${device.address.hostAddress}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(0, dp(6), 0, dp(20))
                setTextColor(themeColor(com.google.android.material.R.attr.colorOnSecondaryContainer))
            })

            val disconnectBtn = MaterialButton(this).apply {
                text = "Disconnect"
                setIconResource(android.R.drawable.ic_menu_close_clear_cancel)
                iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                cornerRadius = dp(24)
                minimumHeight = dp(56)
                setOnClickListener {
                    closeConnection(connection)
                    connection = null
                    showDevices()
                }
            }

            content.addView(disconnectBtn)
            card.addView(content)
            devicePanel.addView(card)
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
            while (isRunning && !Thread.currentThread().isInterrupted) {
                Thread.sleep(2000)
                removeExpiredDevices()
                if (connection == null) {
                    runOnUiThread(::showDevices)
                }
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

    private fun dp(value: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()

    private fun themeColor(attrRes: Int): Int =
        MaterialColors.getColor(this, attrRes, 0)

    override fun onDestroy() {
        isRunning = false
        closeConnection(connection)
        try {
            discoverySocket?.close()
        } catch (_: Exception) {
        }
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        executor.shutdownNow()
        super.onDestroy()
    }

    private data class Device(
        val id: String,
        val name: String,
        val address: InetAddress,
        val port: Int,
        val lastSeen: Long
    )
}