package com.example.skyplug

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.skyplug.databinding.ActivityMainBinding
import com.example.skyplug.databinding.DevicePanelBinding
import java.security.MessageDigest
import java.util.UUID

/** Two independent NUS sessions. All session state is confined to the main thread. */
@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
        val WRITE_UUID: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
        val NOTIFY_UUID: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val CMD_AUTH: Byte = -1
        const val CMD_ON: Byte = 3
        const val CMD_OFF: Byte = 4
        const val CMD_STATE: Byte = 6
        const val STATE_POLL_MS = 2000L
        const val STATE_BYTE_INDEX = 11
        const val COLOR_ON = 0xFF10B981.toInt()
        const val COLOR_OFF = 0xFFF43F5E.toInt()
        const val COLOR_UNKNOWN = 0xFF94A3B8.toInt()
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var panels: List<DeviceController>
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("skyplug", MODE_PRIVATE) }
    private val btAdapter: BluetoothAdapter? by lazy {
        (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
    }
    private val pendingConnections = linkedSetOf<Int>()
    private var permissionRequestPending = false
    private var destroyed = false
    private var scanOwner: DeviceController? = null

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionRequestPending = false
        val requested = pendingConnections.toList()
        pendingConnections.clear()
        if (!destroyed) requested.forEach { index ->
            if (hasPermissions()) panels[index].startConnect()
            else panels[index].status("Нужен доступ к Bluetooth")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Migrate the old single-device configuration only once.
        if (!prefs.contains("device_0_mac")) {
            prefs.edit().putString("device_0_mac", prefs.getString("mac", ""))
                .putString("device_0_pass", prefs.getString("pass", "")).apply()
        }
        panels = listOf(
            DeviceController(0, binding.leftPanel),
            DeviceController(1, binding.rightPanel)
        )
        binding.copyLogButton.setOnClickListener {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("SkyPlug log", binding.logText.text))
            Toast.makeText(this, "Лог скопирован", Toast.LENGTH_SHORT).show()
        }
        // Empty slots wait for an explicit choice instead of grabbing the first nearby device.
        panels.filter { it.ui.macInput.text.toString().isNotBlank() }
            .forEach { ensurePermsThenConnect(it.index) }
    }

    override fun onDestroy() {
        destroyed = true
        panels.forEach { it.close() }
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun requiredPerms(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    private fun hasPermissions() = requiredPerms().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun ensurePermsThenConnect(index: Int) {
        if (hasPermissions()) panels[index].startConnect()
        else {
            pendingConnections.add(index)
            if (!permissionRequestPending) {
                permissionRequestPending = true
                permLauncher.launch(requiredPerms())
            }
        }
    }

    private fun addressInUse(address: String, owner: DeviceController): Boolean =
        panels.any { it !== owner && (it.address.equals(address, true) ||
            it.ui.macInput.text.toString().trim().equals(address, true)) }

    private fun log(index: Int, message: String) {
        if (destroyed) return
        val sv = binding.logScroll
        val atBottom = binding.logText.bottom <= sv.height + sv.scrollY + 24
        val old = binding.logText.text.toString()
        binding.logText.text = (old + "[${index + 1}] $message\n").takeLast(24000)
        if (atBottom) sv.post { sv.smoothScrollTo(0, binding.logText.bottom) }
    }

    private inner class DeviceController(val index: Int, val ui: DevicePanelBinding) {
        var address: String? = null
            private set
        private var gatt: BluetoothGatt? = null
        private var writeChar: BluetoothGattCharacteristic? = null
        private var counter = 0
        private var pairToken = ByteArray(8) { 1 }
        private var authenticated = false
        private var isOn: Boolean? = null
        private var scanCallback: ScanCallback? = null
        private var scanDialog: AlertDialog? = null
        private var scanTimeout: Runnable? = null
        private var connectionTimeout: Runnable? = null
        private var requestTimeout: Runnable? = null
        private var pendingFrame: ByteArray? = null
        private var writeCompleted = false
        private var responseReceived = false
        private val commands = java.util.ArrayDeque<Pair<Byte, ByteArray?>>()
        private val poller = object : Runnable {
            override fun run() {
                if (authenticated) {
                    send(CMD_STATE)
                    handler.postDelayed(this, STATE_POLL_MS)
                }
            }
        }

        init {
            ui.panelTitle.text = "Устройство ${index + 1}"
            ui.macInput.setText(prefs.getString("device_${index}_mac", ""))
            ui.keyInput.setText(prefs.getString("device_${index}_pass", ""))
            ui.connectButton.setOnClickListener { ensurePermsThenConnect(index) }
            ui.toggleButton.setOnClickListener {
                val state = isOn ?: return@setOnClickListener
                ui.toggleButton.isEnabled = false
                send(if (state) CMD_OFF else CMD_ON)
                send(CMD_STATE)
            }
        }

        fun status(text: String) { ui.statusText.text = text }
        private fun log(text: String) = this@MainActivity.log(index, text)

        fun startConnect() {
            val adapter = btAdapter
            if (adapter == null || !adapter.isEnabled) {
                close()
                status("Включите Bluetooth")
                return
            }
            val mac = ui.macInput.text.toString().trim().uppercase()
            if (mac.isNotEmpty() && !BluetoothAdapter.checkBluetoothAddress(mac)) {
                status("Проверьте MAC-адрес")
                return
            }
            if (mac.isNotEmpty() && addressInUse(mac, this)) {
                status("Уже выбрано в другой панели")
                return
            }
            close()
            val pass = ui.keyInput.text.toString().trim()
            pairToken = if (pass.isEmpty()) ByteArray(8) { 1 }
                else MessageDigest.getInstance("SHA-256").digest(pass.toByteArray(Charsets.UTF_8)).copyOf(8)
            prefs.edit().putString("device_${index}_mac", mac)
                .putString("device_${index}_pass", pass).apply()
            if (mac.isEmpty()) scanForDevice() else connectTo(adapter.getRemoteDevice(mac))
        }

        private fun scanForDevice() {
            if (scanOwner != null) {
                status("Сначала завершите поиск в другой панели")
                return
            }
            val scanner = btAdapter?.bluetoothLeScanner
            if (scanner == null) { status("Bluetooth-поиск недоступен"); return }
            scanOwner = this
            val devices = mutableListOf<BluetoothDevice>()
            val rows = ArrayAdapter<String>(this@MainActivity, android.R.layout.simple_list_item_1)
            status("Поиск устройств…")
            val dialog = AlertDialog.Builder(this@MainActivity)
                .setTitle("Выберите устройство ${index + 1}")
                .setAdapter(rows) { _, position ->
                    val device = devices[position]
                    stopScan()
                    if (addressInUse(device.address, this)) {
                        status("Уже выбрано в другой панели")
                    } else {
                        ui.macInput.setText(device.address)
                        prefs.edit().putString("device_${index}_mac", device.address).apply()
                        connectTo(device)
                    }
                }
                .setNegativeButton("Отмена", null).create()
            scanDialog = dialog
            dialog.setOnDismissListener {
                stopScan()
                scanDialog = null
                if (gatt == null && ui.statusText.text == "Поиск устройств…") status("Поиск завершён")
            }
            val cb = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    handler.post {
                        if (scanCallback !== this || destroyed) return@post
                        val device = result.device
                        val name = result.scanRecord?.deviceName ?: device.name.orEmpty()
                        val matches = result.scanRecord?.serviceUuids?.any { it.uuid == SERVICE_UUID } == true ||
                            name.contains("RSP", true) || name.contains("RSL", true)
                        if (matches && !addressInUse(device.address, this@DeviceController) &&
                            devices.none { it.address == device.address }) {
                            devices.add(device)
                            rows.add("${name.ifBlank { "Redmond / NUS" }}\n${device.address}")
                        }
                    }
                }
                override fun onScanFailed(errorCode: Int) {
                    handler.post {
                        if (scanCallback !== this) return@post
                        stopScan()
                        status("Ошибка поиска: $errorCode")
                        dialog.setTitle("Ошибка поиска: $errorCode")
                    }
                }
            }
            scanCallback = cb
            dialog.show()
            try {
                scanner.startScan(cb)
                scanTimeout = Runnable {
                    stopScan()
                    status(if (devices.isEmpty()) "Устройства не найдены" else "Выберите устройство в списке")
                    dialog.setTitle(if (devices.isEmpty()) "Не найдено. Проверьте Bluetooth" else "Выберите устройство ${index + 1}")
                }.also { handler.postDelayed(it, 15000) }
            } catch (_: SecurityException) {
                stopScan()
                dialog.dismiss()
                status("Нужен доступ к Bluetooth")
            }
        }

        private fun stopScan() {
            scanTimeout?.let { handler.removeCallbacks(it) }
            scanTimeout = null
            val cb = scanCallback
            scanCallback = null
            if (cb != null) try { btAdapter?.bluetoothLeScanner?.stopScan(cb) } catch (_: SecurityException) { }
            if (scanOwner === this) scanOwner = null
        }

        private fun connectTo(device: BluetoothDevice) {
            address = device.address
            status("Подключение…")
            log("Подключение к ${device.address}")
            try {
                gatt = device.connectGatt(this@MainActivity, false, callback, BluetoothDevice.TRANSPORT_LE)
                connectionTimeout = Runnable { fail("Таймаут подключения") }
                    .also { handler.postDelayed(it, 20000) }
            } catch (_: SecurityException) { fail("Нужен доступ к Bluetooth") }
        }

        // Every callback checks its GATT identity after entering the main thread.
        // Events from a closed connection can never alter a new session or the other panel.
        private fun dispatch(g: BluetoothGatt, action: () -> Unit) {
            handler.post { if (!destroyed && gatt === g) action() }
        }

        private val callback = object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, result: Int, newState: Int) = dispatch(g) {
                when {
                    result != BluetoothGatt.GATT_SUCCESS -> fail("Ошибка соединения: $result")
                    newState == BluetoothGatt.STATE_CONNECTED -> {
                        status("Поиск сервиса…")
                        if (!g.discoverServices()) fail("Не удалось найти сервис")
                    }
                    newState == BluetoothGatt.STATE_DISCONNECTED -> fail("Отключено")
                }
            }
            override fun onServicesDiscovered(g: BluetoothGatt, result: Int) = dispatch(g) {
                if (result != BluetoothGatt.GATT_SUCCESS) { fail("Ошибка поиска сервиса"); return@dispatch }
                val service = g.getService(SERVICE_UUID)
                writeChar = service?.getCharacteristic(WRITE_UUID)
                val notify = service?.getCharacteristic(NOTIFY_UUID)
                val cccd = notify?.getDescriptor(CCCD_UUID)
                if (writeChar == null || notify == null || cccd == null) {
                    fail("Сервис NUS не найден"); return@dispatch
                }
                if (!g.setCharacteristicNotification(notify, true)) {
                    fail("Не удалось включить уведомления"); return@dispatch
                }
                @Suppress("DEPRECATION")
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                if (!g.writeDescriptor(cccd)) fail("Ошибка подписки на состояние")
            }
            override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, result: Int) = dispatch(g) {
                if (d.uuid != CCCD_UUID) return@dispatch
                if (result != BluetoothGatt.GATT_SUCCESS) { fail("Ошибка уведомлений: $result"); return@dispatch }
                status("Авторизация…")
                send(CMD_AUTH, pairToken)
            }
            override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, result: Int) = dispatch(g) {
                if (result != BluetoothGatt.GATT_SUCCESS) { fail("Ошибка отправки: $result"); return@dispatch }
                writeCompleted = true
                completeRequestIfReady()
            }
            override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
                val copy = value.copyOf()
                dispatch(g) { if (ch.uuid == NOTIFY_UUID) handleResponse(copy) }
            }
            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
                @Suppress("DEPRECATION")
                val copy = ch.value?.copyOf() ?: return
                dispatch(g) { if (ch.uuid == NOTIFY_UUID) handleResponse(copy) }
            }
        }

        private fun handleResponse(value: ByteArray) {
            if (value.size < 4 || value.first() != 0x55.toByte() || value.last() != 0xAA.toByte()) return
            val pending = pendingFrame ?: return
            if (value[1] != pending[1] || value[2] != pending[2]) return
            val cmd = value[2]
            val ok = value.size >= 5 && value[3] != 0.toByte()
            log("← ${value.toHex()}")
            when (cmd) {
                CMD_AUTH -> {
                    if (!ok) { fail("Отказ авторизации: проверьте ключ / сопряжение"); return }
                    authenticated = true
                    connectionTimeout?.let { handler.removeCallbacks(it) }
                    connectionTimeout = null
                    status("Подключено, чтение состояния…")
                    handler.removeCallbacks(poller)
                    handler.post(poller)
                }
                CMD_ON, CMD_OFF -> {
                    if (!ok) log("Команда отклонена")
                }
                CMD_STATE -> {
                    if (value.size <= STATE_BYTE_INDEX + 1) {
                        fail("Неполный ответ состояния"); return
                    }
                    isOn = value[STATE_BYTE_INDEX].toInt() == 2
                    showState()
                    status("Подключено ✓")
                }
            }
            responseReceived = true
            completeRequestIfReady()
        }

        private fun completeRequestIfReady() {
            if (!writeCompleted || !responseReceived) return
            requestTimeout?.let { handler.removeCallbacks(it) }
            requestTimeout = null
            pendingFrame = null
            writeNext()
        }

        private fun send(cmd: Byte, payload: ByteArray? = null) {
            if (cmd != CMD_AUTH && !authenticated) return
            if (cmd == CMD_STATE && (pendingFrame?.get(2) == CMD_STATE || commands.any { it.first == CMD_STATE })) return
            commands.addLast(cmd to payload)
            writeNext()
        }

        private fun writeNext() {
            if (pendingFrame != null || commands.isEmpty()) return
            val g = gatt ?: return
            val ch = writeChar ?: return
            val (cmd, data) = commands.removeFirst()
            val payload = data ?: ByteArray(0)
            val frame = byteArrayOf(0x55, counter.toByte(), cmd) + payload + byteArrayOf(0xAA.toByte())
            counter = (counter + 1) % 101
            pendingFrame = frame
            writeCompleted = false
            responseReceived = false
            val started = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeCharacteristic(ch, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    ch.value = frame
                    ch.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION")
                    g.writeCharacteristic(ch)
                }
            } catch (_: SecurityException) { false }
            if (!started) { fail("Не удалось отправить команду"); return }
            // Do not put the pairing key into the clipboard/debug log.
            log(if (cmd == CMD_AUTH) "→ Авторизация" else "→ ${frame.toHex()}")
            requestTimeout = Runnable { fail("Устройство не отвечает. Подключитесь снова") }
                .also { handler.postDelayed(it, 6000) }
        }

        private fun showState() {
            ui.toggleButton.isEnabled = authenticated && isOn != null
            ui.toggleButton.text = when (isOn) { true -> "ВКЛ"; false -> "ВЫКЛ"; null -> "—" }
            ui.toggleButton.contentDescription = when (isOn) {
                true -> "Устройство ${index + 1} включено. Нажмите, чтобы выключить"
                false -> "Устройство ${index + 1} выключено. Нажмите, чтобы включить"
                null -> "Состояние устройства ${index + 1} неизвестно"
            }
            ui.toggleButton.backgroundTintList = ColorStateList.valueOf(
                when (isOn) { true -> COLOR_ON; false -> COLOR_OFF; null -> COLOR_UNKNOWN })
        }

        private fun fail(message: String) {
            close()
            status(message)
            log(message)
        }

        fun close() {
            stopScan()
            scanDialog?.dismiss()
            scanDialog = null
            handler.removeCallbacks(poller)
            connectionTimeout?.let { handler.removeCallbacks(it) }
            requestTimeout?.let { handler.removeCallbacks(it) }
            connectionTimeout = null
            requestTimeout = null
            val old = gatt
            gatt = null
            try { old?.disconnect(); old?.close() } catch (_: SecurityException) { }
            writeChar = null
            address = null
            counter = 0
            authenticated = false
            pendingFrame = null
            commands.clear()
            isOn = null
            showState()
        }
    }

    private fun ByteArray.toHex(): String = joinToString(" ") { "%02X".format(it) }
}
