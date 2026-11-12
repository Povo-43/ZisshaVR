package dev.zissha.remote

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import dev.zissha.protocol.BleProtocol
import dev.zissha.protocol.StatusPayload
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * BLE Central client (spec 10) for the remote controller app. Discovers the ZisshaVR peripheral,
 * connects, authorizes with the pairing token and sends control commands.
 */
class BleCentral(private val context: Context) {

    companion object {
        private const val TAG = "BleCentral"
    }

    enum class ConnectionState { IDLE, SCANNING, CONNECTING, CONNECTED, AUTHORIZED, FAILED }

    private val _state = MutableStateFlow(ConnectionState.IDLE)
    val state: StateFlow<ConnectionState> = _state

    private val _status = MutableStateFlow<StatusPayload?>(null)
    val status: StateFlow<StatusPayload?> = _status

    private val _deviceName = MutableStateFlow<String?>(null)
    val deviceName: StateFlow<String?> = _deviceName

    var token: String = ""
        set(value) {
            field = value
            if (_state.value == ConnectionState.CONNECTED) authorize()
        }

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var gatt: BluetoothGatt? = null
    private var commandChar: BluetoothGattCharacteristic? = null
    private var statusChar: BluetoothGattCharacteristic? = null
    private val writeQueue = ConcurrentLinkedQueue<ByteArray>()

    private val serviceUuid: UUID = UUID.fromString(BleProtocol.SERVICE_UUID)
    private val commandUuid: UUID = UUID.fromString(BleProtocol.COMMAND_CHAR_UUID)
    private val statusUuid: UUID = UUID.fromString(BleProtocol.STATUS_CHAR_UUID)
    private val cccdUuid: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    @SuppressLint("MissingPermission")
    fun startScan() {
        val a = adapter ?: run { _state.value = ConnectionState.FAILED; return }
        if (!a.isEnabled) {
            _state.value = ConnectionState.FAILED
            return
        }
        if (!hasScanPermissions()) {
            Log.w(TAG, "BLUETOOTH_SCAN/CONNECT not granted; scan deferred")
            _state.value = ConnectionState.FAILED
            return
        }
        val scanner = a.bluetoothLeScanner ?: run { _state.value = ConnectionState.FAILED; return }
        _state.value = ConnectionState.SCANNING
        val filters = listOf(
            ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(serviceUuid))
                .build(),
        )
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .build()
        scanner.startScan(filters, settings, scanCallback)
    }

    @SuppressLint("MissingPermission")
    fun stopScan() {
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        if (_state.value == ConnectionState.SCANNING) _state.value = ConnectionState.IDLE
    }

    private fun hasScanPermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true // legacy: ACCESS_FINE_LOCATION requested by the activity
        }
        val ctx = context
        return androidx.core.content.ContextCompat.checkSelfPermission(
            ctx, android.Manifest.permission.BLUETOOTH_SCAN,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            androidx.core.content.ContextCompat.checkSelfPermission(
                ctx, android.Manifest.permission.BLUETOOTH_CONNECT,
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            Log.i(TAG, "found ZisshaVR device: ${result.device.name}")
            stopScan()
            connect(result.device)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "scan failed: $errorCode")
            _state.value = ConnectionState.FAILED
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        _state.value = ConnectionState.CONNECTING
        _deviceName.value = device.name
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        _state.value = ConnectionState.IDLE
        _status.value = null
    }

    private val gattCallback = object : BluetoothGattCallback() {

        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    _state.value = ConnectionState.CONNECTED
                    g.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _state.value = ConnectionState.IDLE
                    _status.value = null
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.value = ConnectionState.FAILED
                return
            }
            val service = g.getService(serviceUuid)
            if (service == null) {
                Log.e(TAG, "service not found")
                _state.value = ConnectionState.FAILED
                return
            }
            commandChar = service.getCharacteristic(commandUuid)
            statusChar = service.getCharacteristic(statusUuid)
            // subscribe to status notifications
            statusChar?.let { c ->
                g.setCharacteristicNotification(c, true)
                c.getDescriptor(cccdUuid)?.let { d ->
                    d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    g.writeDescriptor(d)
                }
            }
            authorize()
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            pumpQueue(g)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            pumpQueue(g)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleNotification(value)
        }

        @Deprecated("pre-API 33 path")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val value = characteristic.value ?: return
            handleNotification(value)
        }
    }

    private fun handleNotification(value: ByteArray) {
        val frame = BleProtocol.decode(value) ?: return
        when (frame.command) {
            BleProtocol.Command.STATUS -> {
                BleProtocol.decodeStatus(frame)?.let { _status.value = it }
            }
            BleProtocol.Command.AUTH_OK -> _state.value = ConnectionState.AUTHORIZED
            BleProtocol.Command.AUTH_FAIL -> _state.value = ConnectionState.CONNECTED
            else -> Unit
        }
    }

    private fun authorize() {
        sendCommand(BleProtocol.Command.AUTH, token.toByteArray(Charsets.UTF_8))
    }

    @Synchronized
    @SuppressLint("MissingPermission")
    private fun sendCommand(command: BleProtocol.Command, payload: ByteArray = ByteArray(0)) {
        val g = gatt ?: return
        val c = commandChar ?: return
        val frame = BleProtocol.encode(nextSeq(), command, payload)
        writeQueue.offer(frame)
        pumpQueue(g)
    }

    private val seqCounter = java.util.concurrent.atomic.AtomicInteger(0)
    private fun nextSeq(): Int = seqCounter.incrementAndGet() and 0xFF

    @SuppressLint("MissingPermission")
    private fun pumpQueue(g: BluetoothGatt) {
        while (true) {
            val frame = writeQueue.peek() ?: return
            val c = commandChar ?: return
            c.value = frame
            val result = g.writeCharacteristic(c)
            if (result) {
                writeQueue.poll()
                return // one write in flight; continue on callback
            } else {
                return // retry on next callback
            }
        }
    }

    // ---------------------------------------------------------------- public commands

    fun setMode(mode: dev.zissha.protocol.OutputMode) =
        sendCommand(BleProtocol.Command.SET_MODE, mode.name.toByteArray(Charsets.UTF_8))

    fun recordStart() = sendCommand(BleProtocol.Command.RECORD_START)
    fun recordStop() = sendCommand(BleProtocol.Command.RECORD_STOP)
    fun streamStart() = sendCommand(BleProtocol.Command.STREAM_START)
    fun streamStop() = sendCommand(BleProtocol.Command.STREAM_STOP)
    fun requestStatus() = sendCommand(BleProtocol.Command.GET_STATUS)
    fun ping() = sendCommand(BleProtocol.Command.PING)

    fun setBitrate(kbps: Int) {
        val payload = byteArrayOf(
            (kbps and 0xFF).toByte(),
            ((kbps shr 8) and 0xFF).toByte(),
            ((kbps shr 16) and 0xFF).toByte(),
            ((kbps shr 24) and 0xFF).toByte(),
        )
        sendCommand(BleProtocol.Command.SET_BITRATE, payload)
    }
}
