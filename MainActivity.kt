package com.amir.distance

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val bluetoothAdapter: BluetoothAdapter? by lazy { BluetoothAdapter.getDefaultAdapter() }
    private var socket: BluetoothSocket? = null
    @Volatile private var running = false
    private lateinit var statusText: TextView
    private lateinit var distanceText: TextView
    private lateinit var spinner: Spinner
    private lateinit var connectButton: Button
    private lateinit var disconnectButton: Button
    private var devices: List<BluetoothDevice> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        statusText = findViewById(R.id.statusText)
        distanceText = findViewById(R.id.distanceText)
        spinner = findViewById(R.id.deviceSpinner)
        connectButton = findViewById(R.id.connectButton)
        disconnectButton = findViewById(R.id.disconnectButton)

        connectButton.setOnClickListener {\n            if (devices.isEmpty()) checkPermissionAndLoadDevices() else connect()\n        }
        disconnectButton.setOnClickListener { disconnect() }
        checkPermissionAndLoadDevices()
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun checkPermissionAndLoadDevices() {
        if (Build.VERSION.SDK_INT >= 31 && !hasConnectPermission()) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 100)
            return
        }
        loadPairedDevices()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadPairedDevices()
        } else {
            statusText.text = "مجوز بلوتوث داده نشد"
        }
    }

    @Suppress("MissingPermission")
    private fun loadPairedDevices() {
        val adapter = bluetoothAdapter
        if (adapter == null) {
            statusText.text = "این گوشی بلوتوث ندارد"
            return
        }
        if (!adapter.isEnabled) {
            statusText.text = "بلوتوث گوشی را روشن کنید"
            return
        }
        devices = try { adapter.bondedDevices?.toList()?.sortedBy { it.name ?: it.address } ?: emptyList() }
        catch (_: SecurityException) { emptyList() }

        val labels = devices.map { (it.name ?: "Bluetooth device") + "  •  " + it.address }
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        if (devices.isEmpty()) statusText.text = "ابتدا ESP32 را در تنظیمات گوشی Pair کنید"
        else statusText.text = "دستگاه‌های Pair شده آماده‌اند"
    }

    @Suppress("MissingPermission")
    private fun connect() {
        if (!hasConnectPermission()) { checkPermissionAndLoadDevices(); return }
        if (devices.isEmpty() || spinner.selectedItemPosition !in devices.indices) {
            toast("اول ESP32 را Pair و انتخاب کنید")
            return
        }
        val device = devices[spinner.selectedItemPosition]
        statusText.text = "در حال اتصال..."
        thread {
            try {
                bluetoothAdapter?.cancelDiscovery()
                val newSocket = device.createRfcommSocketToServiceRecord(sppUuid)
                newSocket.connect()
                socket = newSocket
                running = true
                runOnUiThread {
                    statusText.text = "متصل به ${device.name ?: "ESP32"}"
                    statusText.setTextColor(0xFF35D6A0.toInt())
                }
                val reader = BufferedReader(InputStreamReader(newSocket.inputStream))
                while (running) {
                    val line = reader.readLine() ?: break
                    val number = Regex("-?\\d+(?:\\.\\d+)?").find(line)?.value
                    if (number != null) runOnUiThread { distanceText.text = number }
                }
            } catch (e: Exception) {
                runOnUiThread { statusText.text = "اتصال ناموفق: ${e.message ?: "خطا"}" }
            } finally {
                running = false
                try { socket?.close() } catch (_: Exception) {}
                socket = null
                runOnUiThread {
                    if (statusText.text.toString().startsWith("متصل")) statusText.text = "ارتباط قطع شد"
                }
            }
        }
    }

    private fun disconnect() {
        running = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        statusText.text = "وضعیت: قطع"
        distanceText.text = "---"
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        disconnect()
        super.onDestroy()
    }
}
