package com.octalide.niky

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.SharedPreferences
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Connects to the niky firmware (Nordic UART Service) and writes NMEA bytes
 * to its RX characteristic.
 *
 * Background-friendly behavior:
 *   - On first ever connect, scan for the NUS service UUID, save the firmware
 *     MAC to SharedPreferences after services are discovered.
 *   - On every subsequent start, skip the scan and call connectGatt with
 *     autoConnect=true against the saved MAC. The OS keeps a low-power
 *     reconnect attempt going indefinitely, which works with the screen off
 *     and the phone in a pocket.
 *   - On disconnect, do NOT close the gatt — let autoConnect bring it back.
 *     The gatt is only closed in stop().
 *
 * Requires BLUETOOTH_SCAN + BLUETOOTH_CONNECT, granted via the UI.
 */
@SuppressLint("MissingPermission")
class NusClient(private val ctx: Context) {

    enum class State { IDLE, SCANNING, CONNECTING, READY }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state

    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val prefs: SharedPreferences =
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private var gatt: BluetoothGatt? = null
    private var rxChar: BluetoothGattCharacteristic? = null
    private var stopped = false
    private var currentMtu: Int = 23

    fun start() {
        stopped = false
        val mac = prefs.getString(KEY_MAC, null)
        if (mac != null && BluetoothAdapter.checkBluetoothAddress(mac)) {
            connectByMac(mac)
        } else {
            startScan()
        }
    }

    fun stop() {
        stopped = true
        try { adapter?.bluetoothLeScanner?.stopScan(scanCb) } catch (_: Exception) {}
        try { gatt?.disconnect() } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        rxChar = null
        _state.value = State.IDLE
    }

    fun send(bytes: ByteArray): Boolean {
        val g = gatt ?: return false
        val c = rxChar ?: return false
        val mtu = currentMtu - 3
        var i = 0
        while (i < bytes.size) {
            val n = minOf(mtu, bytes.size - i)
            val chunk = bytes.copyOfRange(i, i + n)
            val rc = g.writeCharacteristic(c, chunk, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
            if (rc != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "writeCharacteristic rc=$rc")
                return false
            }
            i += n
        }
        return true
    }

    private fun connectByMac(mac: String) {
        Log.i(TAG, "direct connect (autoConnect=true) to $mac")
        val device = try {
            adapter?.getRemoteDevice(mac)
        } catch (e: IllegalArgumentException) {
            null
        }
        if (device == null) {
            prefs.edit().remove(KEY_MAC).apply()
            startScan()
            return
        }
        _state.value = State.CONNECTING
        gatt = device.connectGatt(ctx, /*autoConnect*/ true, gattCb, BluetoothDevice.TRANSPORT_LE)
    }

    private fun startScan() {
        val scanner = adapter?.bluetoothLeScanner ?: return
        _state.value = State.SCANNING
        val filter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(NUS_SERVICE_UUID))
            .build()
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(listOf(filter), settings, scanCb)
    }

    private val scanCb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            try { adapter?.bluetoothLeScanner?.stopScan(this) } catch (_: Exception) {}
            // Save MAC and switch to autoConnect for stable background behavior.
            prefs.edit().putString(KEY_MAC, device.address).apply()
            _state.value = State.CONNECTING
            gatt = device.connectGatt(ctx, /*autoConnect*/ true, gattCb, BluetoothDevice.TRANSPORT_LE)
        }
        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed $errorCode")
        }
    }

    private val gattCb = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    Log.i(TAG, "connected (status=$status), requesting MTU")
                    g.requestMtu(247)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    Log.i(TAG, "disconnected (status=$status); autoConnect will retry")
                    rxChar = null
                    if (!stopped) _state.value = State.CONNECTING
                    // Do NOT close gatt: autoConnect is responsible for reconnect.
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            currentMtu = if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23
            Log.i(TAG, "MTU=$currentMtu, discovering services")
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val svc = g.getService(NUS_SERVICE_UUID)
            if (svc == null) {
                Log.w(TAG, "NUS service missing")
                return
            }
            rxChar = svc.getCharacteristic(NUS_RX_UUID)
            if (rxChar == null) {
                Log.w(TAG, "NUS RX char missing")
                return
            }
            _state.value = State.READY
        }
    }

    companion object {
        private const val TAG = "NusClient"
        private const val PREFS_NAME = "niky"
        private const val KEY_MAC = "device_mac"
        val NUS_SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val NUS_RX_UUID: UUID      = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    }
}
