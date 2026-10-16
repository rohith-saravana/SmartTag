package com.example.smarttag

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import com.example.smarttag.databinding.ActivityMainBinding
import java.util.Locale
import java.util.UUID
import kotlin.math.pow

private const val TARGET_DEVICE_NAME = "SmartTag-C3"
private const val COLOR_GREEN = "#198754"
private const val COLOR_RED = "#DC3545"
private const val COLOR_GREY = "#6C757D"
private const val COLOR_BLUE = "#0D6EFD"
private const val COLOR_ORANGE = "#FD7E14"

@SuppressLint("MissingPermission")
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var bluetoothLeScanner: BluetoothLeScanner? = null
    private var bluetoothGatt: BluetoothGatt? = null

    private var isScanning = false
    private var isConnected = false
    private var isUpdatingSwitchFromNotification = false

    private val handler = Handler(Looper.getMainLooper())
    private var rssiRunnable: Runnable? = null

    // Distance & RSSI Constants and Buffers
    private var rssiAt1M = -59
    private val pathLossN = 2.5
    private val rssiBuffer = ArrayList<Int>()
    private val maxBufferSize = 8

    // Calibration State
    private var isCalibrating = false
    private val calibrationBuffer = ArrayList<Int>()
    private val calibrationTargetCount = 10

    private lateinit var sharedPrefs: SharedPreferences

    // BLE UUIDs for ESP32-C3 Smart Tag
    private val serviceUuid = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")
    private val controlCharUuid = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
    private val statusCharUuid = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
    private val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    companion object {
        private const val PERMISSION_REQUEST_CODE = 1001
        private const val SCAN_PERIOD: Long = 10000 // 10 seconds scan timeout
        private const val PREFS_NAME = "SmartTagPrefs"
        private const val KEY_RSSI_AT_1M = "rssi_at_1m"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sharedPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        rssiAt1M = sharedPrefs.getInt(KEY_RSSI_AT_1M, -59)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        setupUI()
        checkPermissions()
    }

    private fun setupUI() {
        binding.btnConnectToggle.setOnClickListener {
            if (!hasRequiredPermissions()) {
                requestPermissions()
                return@setOnClickListener
            }
            if (isConnected) {
                disconnectGatt()
            } else if (isScanning) {
                stopBleScan()
            } else {
                startBleScan()
            }
        }

        binding.switchArm.setOnCheckedChangeListener { _, isChecked ->
            if (isUpdatingSwitchFromNotification) return@setOnCheckedChangeListener
            if (isConnected) {
                if (isChecked) {
                    logMessage("Arming system...")
                    sendControlCommand("ARM")
                } else {
                    logMessage("Disarming system...")
                    sendControlCommand("DISARM")
                }
            }
        }

        binding.btnSoundAlarm.setOnClickListener {
            if (isConnected) {
                logMessage("Sounding alarm...")
                sendControlCommand("ALARM_ON")
            }
        }

        binding.btnStopAlarm.setOnClickListener {
            if (isConnected) {
                logMessage("Stopping alarm...")
                sendControlCommand("ALARM_OFF")
            }
        }

        binding.btnCalibrate.setOnClickListener {
            if (isConnected && !isCalibrating) {
                isCalibrating = true
                calibrationBuffer.clear()
                binding.btnCalibrate.text = "Calibrating..."
                binding.btnCalibrate.isEnabled = false
                logMessage("Calibrating... hold tag 1 m away for 10 seconds")
                Toast.makeText(this, "Calibrating... hold tag 1 m away", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkPermissions() {
        if (!hasRequiredPermissions()) {
            requestPermissions()
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
        ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                logMessage("Bluetooth & Location permissions granted.")
            } else {
                logMessage("Error: Required Bluetooth permissions denied.")
                Toast.makeText(this, "Permissions are required for BLE scanning and connection.", Toast.LENGTH_LONG).show()
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun startBleScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            logMessage("Bluetooth is disabled.")
            Toast.makeText(this, "Please enable Bluetooth", Toast.LENGTH_SHORT).show()
            return
        }

        bluetoothLeScanner = bluetoothAdapter!!.bluetoothLeScanner
        if (bluetoothLeScanner == null) {
            logMessage("Bluetooth LeScanner is null.")
            return
        }

        isScanning = true
        binding.btnConnectToggle.text = "Cancel"
        binding.btnConnectToggle.setBackgroundColor(COLOR_RED.toColorInt())
        binding.tvConnectionState.text = "Scanning..."
        logMessage("Scanning for SmartTag-C3...")

        handler.postDelayed({
            if (isScanning) {
                stopBleScan()
                logMessage("Device not found")
                binding.tvConnectionState.text = "Disconnected"
                binding.btnConnectToggle.text = "Connect"
                binding.btnConnectToggle.setBackgroundColor(COLOR_BLUE.toColorInt())
            }
        }, SCAN_PERIOD)

        bluetoothLeScanner?.startScan(scanCallback)
    }

    private fun stopBleScan() {
        if (isScanning) {
            isScanning = false
            bluetoothLeScanner?.stopScan(scanCallback)
            if (!isConnected) {
                binding.btnConnectToggle.text = "Connect"
                binding.btnConnectToggle.setBackgroundColor(COLOR_BLUE.toColorInt())
                binding.tvConnectionState.text = "Disconnected"
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val deviceName = result.scanRecord?.deviceName ?: device.name
            if (deviceName == TARGET_DEVICE_NAME) {
                stopBleScan()
                logMessage("Found SmartTag-C3. Connecting...")
                connectToDevice(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            logMessage("Device not found")
            binding.btnConnectToggle.text = "Connect"
            binding.btnConnectToggle.setBackgroundColor(COLOR_BLUE.toColorInt())
            binding.tvConnectionState.text = "Disconnected"
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        binding.tvConnectionState.text = "Connecting..."
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        isConnected = true
                        runOnUiThread {
                            binding.tvConnectionState.text = "Connected"
                            binding.btnConnectToggle.text = "Disconnect"
                            binding.btnConnectToggle.setBackgroundColor(COLOR_RED.toColorInt())
                            binding.switchArm.isEnabled = true
                            binding.btnSoundAlarm.isEnabled = true
                            binding.btnStopAlarm.isEnabled = true
                            binding.btnCalibrate.isEnabled = true
                            binding.btnCalibrate.setBackgroundColor(COLOR_BLUE.toColorInt())
                            logMessage("Connected")
                        }
                        gatt.discoverServices()
                        startRssiPolling()
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        isConnected = false
                        runOnUiThread {
                            handleDisconnectionDueToDrop()
                        }
                        gatt.close()
                    }
                }
            } else {
                isConnected = false
                runOnUiThread {
                    handleDisconnectionDueToDrop()
                }
                gatt.close()
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                gatt.requestMtu(247)

                val service = gatt.getService(serviceUuid)
                if (service != null) {
                    val statusChar = service.getCharacteristic(statusCharUuid)
                    if (statusChar != null) {
                        gatt.readCharacteristic(statusChar)
                        enableNotification(gatt, statusChar)
                    }
                }
            }
        }

        override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && characteristic.uuid == statusCharUuid) {
                val value = characteristic.value
                if (value != null) {
                    parseStatus(String(value, Charsets.UTF_8))
                }
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == statusCharUuid) {
                val value = characteristic.value
                if (value != null) {
                    parseStatus(String(value, Charsets.UTF_8))
                }
            }
        }

        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                handleRssiReading(rssi)
            }
        }
    }

    private fun startRssiPolling() {
        stopRssiPolling()
        rssiRunnable = object : Runnable {
            override fun run() {
                if (isConnected && bluetoothGatt != null) {
                    bluetoothGatt?.readRemoteRssi()
                    handler.postDelayed(this, 1000) // Poll every 1 second
                }
            }
        }
        handler.post(rssiRunnable!!)
    }

    private fun stopRssiPolling() {
        rssiRunnable?.let { handler.removeCallbacks(it) }
        rssiRunnable = null
    }

    private fun handleRssiReading(rssi: Int) {
        if (isCalibrating) {
            calibrationBuffer.add(rssi)
            if (calibrationBuffer.size >= calibrationTargetCount) {
                val avg = calibrationBuffer.sum().toDouble() / calibrationBuffer.size
                rssiAt1M = avg.toInt()
                sharedPrefs.edit {
                    putInt(KEY_RSSI_AT_1M, rssiAt1M)
                }
                isCalibrating = false
                calibrationBuffer.clear()
                runOnUiThread {
                    binding.btnCalibrate.text = "Calibrate (hold tag 1 m away)"
                    binding.btnCalibrate.isEnabled = true
                    binding.btnCalibrate.setBackgroundColor(COLOR_BLUE.toColorInt())
                    logMessage("Calibration complete (RSSI at 1m: $rssiAt1M dBm)")
                    Toast.makeText(this, "Calibrated! RSSI at 1m: $rssiAt1M dBm", Toast.LENGTH_SHORT).show()
                }
            }
            return
        }

        // Add to sliding window buffer (last 8 readings)
        rssiBuffer.add(rssi)
        if (rssiBuffer.size > maxBufferSize) {
            rssiBuffer.removeAt(0)
        }

        val smoothedRssi = rssiBuffer.sum().toDouble() / rssiBuffer.size
        val distanceM = 10.0.pow((rssiAt1M - smoothedRssi) / (10.0 * pathLossN))

        runOnUiThread {
            binding.tvSmoothedRssi.text = "Signal: ${smoothedRssi.toInt()} dBm"
            if (distanceM > 15.0) {
                binding.tvApproxDistance.text = "> 15 m"
            } else {
                binding.tvApproxDistance.text = String.format(Locale.getDefault(), "≈ %.1f m", distanceM)
            }

            val label: String
            val colorHex: String
            when {
                distanceM < 1.0 -> {
                    label = "Very close"
                    colorHex = COLOR_GREEN
                }
                distanceM < 3.0 -> {
                    label = "Near"
                    colorHex = COLOR_GREEN
                }
                distanceM < 8.0 -> {
                    label = "Far"
                    colorHex = COLOR_ORANGE
                }
                else -> {
                    label = "Very far"
                    colorHex = COLOR_RED
                }
            }
            binding.tvDistanceLabel.text = label
            binding.tvDistanceLabel.setTextColor(colorHex.toColorInt())
            binding.tvApproxDistance.setTextColor(colorHex.toColorInt())
        }
    }

    private fun enableNotification(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        val success = gatt.setCharacteristicNotification(characteristic, true)
        if (success) {
            val descriptor = characteristic.getDescriptor(cccdUuid)
            if (descriptor != null) {
                descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                gatt.writeDescriptor(descriptor)
            }
        }
    }

    private fun parseStatus(raw: String) {
        var monitor = "--"
        var alarmRaw = "--"
        var mpu = "--"

        for (part in raw.split(";")) {
            val kv = part.split("=")
            if (kv.size == 2) {
                val key = kv[0].trim()
                val value = kv[1].trim()
                when (key) {
                    "MONITOR" -> monitor = value
                    "ALARM" -> alarmRaw = value
                    "MPU" -> mpu = value
                }
            }
        }

        runOnUiThread {
            if (monitor == "ON") {
                binding.tvProtectionValue.text = "ON"
                binding.tvProtectionValue.setTextColor(COLOR_GREEN.toColorInt())
            } else {
                binding.tvProtectionValue.text = "OFF"
                binding.tvProtectionValue.setTextColor(COLOR_GREY.toColorInt())
            }

            isUpdatingSwitchFromNotification = true
            binding.switchArm.isChecked = (monitor == "ON")
            isUpdatingSwitchFromNotification = false

            when (alarmRaw) {
                "OFF" -> {
                    binding.tvAlarmValue.text = "Quiet"
                    binding.tvAlarmValue.setTextColor(COLOR_GREEN.toColorInt())
                }
                "ON" -> {
                    binding.tvAlarmValue.text = "Ringing"
                    binding.tvAlarmValue.setTextColor(COLOR_RED.toColorInt())
                }
                "DISCONNECT" -> {
                    binding.tvAlarmValue.text = "Disconnect alarm"
                    binding.tvAlarmValue.setTextColor(COLOR_RED.toColorInt())
                }
                "ON+DISCONNECT" -> {
                    binding.tvAlarmValue.text = "Ringing & Disconnect"
                    binding.tvAlarmValue.setTextColor(COLOR_RED.toColorInt())
                }
                else -> {
                    binding.tvAlarmValue.text = alarmRaw
                    binding.tvAlarmValue.setTextColor(COLOR_GREY.toColorInt())
                }
            }

            when (mpu) {
                "OK" -> {
                    binding.tvSensorValue.text = "OK"
                    binding.tvSensorValue.setTextColor(COLOR_GREEN.toColorInt())
                }
                "ERROR" -> {
                    binding.tvSensorValue.text = "Error"
                    binding.tvSensorValue.setTextColor(COLOR_RED.toColorInt())
                }
                else -> {
                    binding.tvSensorValue.text = mpu
                    binding.tvSensorValue.setTextColor(COLOR_GREY.toColorInt())
                }
            }
        }
    }

    private fun sendControlCommand(command: String) {
        if (!isConnected || bluetoothGatt == null) {
            logMessage("Not connected")
            return
        }
        val service = bluetoothGatt?.getService(serviceUuid)
        val char = service?.getCharacteristic(controlCharUuid)
        if (char != null) {
            char.setValue(command.toByteArray(Charsets.UTF_8))
            val success = bluetoothGatt?.writeCharacteristic(char) ?: false
            if (success) {
                when (command) {
                    "ARM" -> logMessage("Armed")
                    "DISARM" -> logMessage("Disarmed")
                    "ALARM_ON" -> logMessage("Alarm sounded")
                    "ALARM_OFF" -> logMessage("Alarm stopped")
                    else -> logMessage("Command sent: $command")
                }
            } else {
                logMessage("Failed to send command")
            }
        }
    }

    private fun disconnectGatt() {
        stopRssiPolling()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        bluetoothGatt = null
        handleDisconnectionNormal()
    }

    private fun handleDisconnectionNormal() {
        isConnected = false
        stopRssiPolling()
        binding.btnConnectToggle.text = "Connect"
        binding.btnConnectToggle.setBackgroundColor(COLOR_BLUE.toColorInt())
        binding.tvConnectionState.text = "Disconnected"
        resetStatusValues()
        logMessage("Disconnected")
    }

    private fun handleDisconnectionDueToDrop() {
        isConnected = false
        stopRssiPolling()
        binding.btnConnectToggle.text = "Connect"
        binding.btnConnectToggle.setBackgroundColor(COLOR_BLUE.toColorInt())
        binding.tvConnectionState.text = "Disconnected"
        resetStatusValues()
        logMessage("Tag disconnected!")
    }

    private fun resetStatusValues() {
        binding.tvProtectionValue.text = "--"
        binding.tvProtectionValue.setTextColor(COLOR_GREY.toColorInt())
        binding.tvAlarmValue.text = "--"
        binding.tvAlarmValue.setTextColor(COLOR_GREY.toColorInt())
        binding.tvSensorValue.text = "--"
        binding.tvSensorValue.setTextColor(COLOR_GREY.toColorInt())

        binding.tvApproxDistance.text = "--"
        binding.tvApproxDistance.setTextColor(COLOR_GREY.toColorInt())
        binding.tvSmoothedRssi.text = "Signal: -- dBm"
        binding.tvDistanceLabel.text = "--"
        binding.tvDistanceLabel.setTextColor(COLOR_GREY.toColorInt())

        isUpdatingSwitchFromNotification = true
        binding.switchArm.isChecked = false
        isUpdatingSwitchFromNotification = false

        binding.switchArm.isEnabled = false
        binding.btnSoundAlarm.isEnabled = false
        binding.btnStopAlarm.isEnabled = false
        binding.btnCalibrate.isEnabled = false
        binding.btnCalibrate.setBackgroundColor(COLOR_GREY.toColorInt())

        rssiBuffer.clear()
        isCalibrating = false
        calibrationBuffer.clear()
    }

    private fun logMessage(msg: String) {
        runOnUiThread {
            binding.tvMessageLine.text = msg
            if (msg == "Tag disconnected!") {
                binding.tvMessageLine.setTextColor(COLOR_RED.toColorInt())
            } else {
                binding.tvMessageLine.setTextColor(COLOR_GREY.toColorInt())
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRssiPolling()
        stopBleScan()
        bluetoothGatt?.close()
        bluetoothGatt = null
    }
}
