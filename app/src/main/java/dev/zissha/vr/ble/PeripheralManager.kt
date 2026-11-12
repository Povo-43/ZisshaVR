package dev.zissha.vr.ble

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import dev.zissha.protocol.BleProtocol
import dev.zissha.protocol.StatusPayload
import dev.zissha.vr.core.AppLog
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * BLE Peripheral (spec 10): the streaming device runs a GATT server; the remote controller app
 * connects as Central. Access is protected by platform LE pairing (encrypted characteristic
 * access) plus a 6-digit session token displayed on the main screen.
 */
class PeripheralManager(private val context: Context) {

    companion object {
        private const val TAG = "BlePeripheral"
    }

    interface CommandHandler {
        fun onSetMode(mode: dev.zissha.protocol.OutputMode)
        fun onRecordStart()
        fun onRecordStop()
        fun onStreamStart()
        fun onStreamStop()
        fun onSetBitrate(kbps: Int)
        fun onStatusRequest(): StatusPayload
    }

    var commandHandler: CommandHandler? = null

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private val _connectedDevice = MutableStateFlow<String?>(null)
    val connectedDevice: StateFlow<String?> = _connectedDevice

    private val _authorized = MutableStateFlow(false)
    val authorized: StateFlow<Boolean> = _authorized

    /** 6-digit pairing token; regenerated per app start (or per disconnect when rotating). */
    private val _token = MutableStateFlow<String>("")
    val token: StateFlow<String> = _token

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null

    private var statusCharacteristic: BluetoothGattCharacteristic? = null
    private var eventCharacteristic: BluetoothGattCharacteristic? = null
    private val notifyDevice: MutableSet<BluetoothDevice> = HashSet()
    private val seq = AtomicInteger(0)

    private val adapter: BluetoothAdapter?
        get() = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    fun start() {
        if (_running.value) return
        if (!hasBlePermissions()) {
            AppLog.w("BLE permissions (CONNECT/ADVERTISE) not granted yet; peripheral start deferred")
            return
        }
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return
        val bluetoothAdapter = bluetoothManager.adapter
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            AppLog.w("BLE adapter unavailable/disabled; peripheral not started")
            return
        }
        generateToken()
        try {
            openServer(bluetoothManager)
            startAdvertising(bluetoothAdapter)
        } catch (e: SecurityException) {
            AppLog.e("BLE peripheral start rejected: ${e.message}")
            return
        } catch (e: Exception) {
            AppLog.e("BLE peripheral start failed: ${e.message}")
            return
        }
        _running.value = true
        AppLog.i("BLE peripheral started (token ${_token.value})")
    }

    /** Runtime BLE permissions required by this device's API level. */
    private fun hasBlePermissions(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            // legacy BLUETOOTH/BLUETOOTH_ADMIN are normal permissions; location only needed for scanning
            return true
        }
        return ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_ADVERTISE) ==
            PackageManager.PERMISSION_GRANTED
    }

    fun stop() {
        _running.value = false
        _authorized.value = false
        _connectedDevice.value = null
        try {
            advertiser?.stopAdvertising(advCallback)
        } catch (e: Exception) {
            Log.w(TAG, "stopAdvertising", e)
        }
        advertiser = null
        try {
            gattServer?.close()
        } catch (e: Exception) {
            Log.w(TAG, "close gatt server", e)
        }
        gattServer = null
        notifyDevice.clear()
    }

    fun rotateToken() {
        generateToken()
        _authorized.value = false
        AppLog.i("BLE token rotated")
    }

    private fun generateToken() {
        _token.value = (100000..999999).random().toString()
    }

    private fun openServer(manager: BluetoothManager) {
        val server = manager.openGattServer(context, gattCallback) ?: run {
            AppLog.e("openGattServer returned null")
            return
        }
        val service = BluetoothGattService(
            UUID.fromString(BleProtocol.SERVICE_UUID),
            BluetoothGattService.SERVICE_TYPE_PRIMARY,
        )
        val command = BluetoothGattCharacteristic(
            UUID.fromString(BleProtocol.COMMAND_CHAR_UUID),
            BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED,
        )
        val status = BluetoothGattCharacteristic(
            UUID.fromString(BleProtocol.STATUS_CHAR_UUID),
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
        )
        val event = BluetoothGattCharacteristic(
            UUID.fromString(BleProtocol.EVENT_CHAR_UUID),
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED,
        )
        service.addCharacteristic(command)
        service.addCharacteristic(status)
        service.addCharacteristic(event)
        server.addService(service)
        statusCharacteristic = status
        eventCharacteristic = event
        gattServer = server
    }

    private fun startAdvertising(btAdapter: BluetoothAdapter) {
        val advertiser = btAdapter.bluetoothLeAdvertiser ?: run {
            AppLog.w("advertising unsupported on this device")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(UUID.fromString(BleProtocol.SERVICE_UUID)))
            .build()
        this.advertiser = advertiser
        advertiser.startAdvertising(settings, data, advCallback)
    }

    private val advCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            AppLog.i("BLE advertising started")
        }

        override fun onStartFailure(errorCode: Int) {
            AppLog.e("BLE advertising failed: $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    _connectedDevice.value = device.name ?: device.address
                    AppLog.i("BLE central connected: ${_connectedDevice.value}")
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    notifyDevice.remove(device)
                    if (_connectedDevice.value == (device.name ?: device.address)) {
                        _connectedDevice.value = null
                        _authorized.value = false
                    }
                    AppLog.i("BLE central disconnected")
                }
            }
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            AppLog.i("BLE service added: $status")
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (value == null) {
                if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                return
            }
            val frame = BleProtocol.decode(value)
            if (frame == null) {
                if (responseNeeded) gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED, offset, null)
                return
            }
            var responseStatus = BluetoothGatt.GATT_SUCCESS
            when (frame.command) {
                BleProtocol.Command.AUTH -> {
                    val token = String(frame.payload, Charsets.UTF_8)
                    if (token == _token.value) {
                        _authorized.value = true
                        sendEvent(device, BleProtocol.Command.AUTH_OK, "ok".toByteArray())
                        AppLog.i("BLE central authorized")
                    } else {
                        _authorized.value = false
                        sendEvent(device, BleProtocol.Command.AUTH_FAIL, "bad token".toByteArray())
                        AppLog.w("BLE auth failed (wrong token)")
                    }
                }
                else -> {
                    if (!_authorized.value) {
                        responseStatus = BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION
                        AppLog.w("BLE command rejected (not authorized): ${frame.command}")
                    } else {
                        handleCommand(device, frame)
                    }
                }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, responseStatus, offset, null)
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: android.bluetooth.BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            if (value?.contentEquals(android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == true) {
                synchronized(notifyDevice) { notifyDevice.add(device) }
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val payload = when (characteristic.uuid) {
                UUID.fromString(BleProtocol.STATUS_CHAR_UUID) ->
                    BleProtocol.encodeStatus(0, commandHandler?.onStatusRequest() ?: dummyStatus()).let {
                        it.copyOfRange(BleProtocol.HEADER_SIZE, it.size - BleProtocol.CRC_SIZE)
                    }
                else -> ByteArray(0)
            }
            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, payload)
        }
    }

    private fun dummyStatus() = StatusPayload(
        dev.zissha.protocol.OutputMode.LIVE, false, false, false, 1, 0f, 0f, 0, "IDLE", 0, "",
    )

    private fun handleCommand(device: BluetoothDevice, frame: BleProtocol.Frame) {
        val handler = commandHandler ?: return
        when (frame.command) {
            BleProtocol.Command.SET_MODE -> {
                if (frame.payload.isNotEmpty()) {
                    val mode = dev.zissha.protocol.OutputMode.entries.firstOrNull {
                        it.name == String(frame.payload, Charsets.UTF_8)
                    } ?: dev.zissha.protocol.OutputMode.entries.firstOrNull { it.ordinal == frame.payload[0].toInt() }
                    if (mode != null) handler.onSetMode(mode)
                }
            }
            BleProtocol.Command.RECORD_START -> handler.onRecordStart()
            BleProtocol.Command.RECORD_STOP -> handler.onRecordStop()
            BleProtocol.Command.STREAM_START -> handler.onStreamStart()
            BleProtocol.Command.STREAM_STOP -> handler.onStreamStop()
            BleProtocol.Command.GET_STATUS -> {
                val payload = BleProtocol.encodeStatus(frame.seq, handler.onStatusRequest())
                sendEvent(device, BleProtocol.Command.STATUS, payload.copyOfRange(BleProtocol.HEADER_SIZE, payload.size - BleProtocol.CRC_SIZE))
            }
            BleProtocol.Command.SET_BITRATE -> {
                if (frame.payload.size >= 4) {
                    val kbps = (frame.payload[0].toInt() and 0xFF) or
                        ((frame.payload[1].toInt() and 0xFF) shl 8) or
                        ((frame.payload[2].toInt() and 0xFF) shl 16) or
                        ((frame.payload[3].toInt() and 0xFF) shl 24)
                    handler.onSetBitrate(kbps)
                }
            }
            BleProtocol.Command.PING -> sendEvent(device, BleProtocol.Command.PONG)
            else -> Unit
        }
    }

    /** Push status to all subscribed centrals. */
    fun notifyStatus(status: StatusPayload) {
        if (notifyDevice.isEmpty()) return
        val full = BleProtocol.encodeStatus(seq.incrementAndGet(), status)
        val payload = full.copyOfRange(BleProtocol.HEADER_SIZE, full.size - BleProtocol.CRC_SIZE)
        synchronized(notifyDevice) {
            for (device in notifyDevice) {
                statusCharacteristic?.let {
                    it.value = payload
                    runCatching { gattServer?.notifyCharacteristicChanged(device, it, false) }
                }
            }
        }
    }

    private fun sendEvent(device: BluetoothDevice, command: BleProtocol.Command, payload: ByteArray = ByteArray(0)) {
        val frame = BleProtocol.encode(seq.incrementAndGet(), command, payload)
        val body = frame.copyOfRange(BleProtocol.HEADER_SIZE, frame.size - BleProtocol.CRC_SIZE)
        eventCharacteristic?.let { c ->
            c.value = body
            runCatching { gattServer?.notifyCharacteristicChanged(device, c, false) }
        }
    }
}
