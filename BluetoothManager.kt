package com.klstudio.bleservertest.manager

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import com.klstudio.bleservertest.data.BleConnectionState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BleManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val bluetoothAdapter: BluetoothAdapter
) {
    companion object {
        private const val TAG = "BleManager"
        val SERVICE_UUID: UUID = parseUuid("0000feed-0000-1000-8000-00805f9b34fb")
        val WRITE_UUID: UUID = parseUuid("0000feed-0001-1000-8000-00805f9b34fb")
        val NOTIFY_UUID: UUID = parseUuid("0000feed-0002-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = parseUuid("00002902-0000-1000-8000-00805f9b34fb")

        private fun parseUuid(raw: String): UUID {
            return try {
                UUID.fromString(raw)
            } catch (e: Exception) {
                Log.e(TAG, "UUID parse error: $raw", e)
                throw e
            }
        }
    }

    private var bleScanner: BluetoothLeScanner? = bluetoothAdapter.bluetoothLeScanner
    private var bluetoothGatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var notifyCharacteristic: BluetoothGattCharacteristic? = null

    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Idle)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<ScanResult>>(emptyList())
    val scannedDevices: StateFlow<List<ScanResult>> = _scannedDevices.asStateFlow()

    private val _receivedDataFlow = MutableSharedFlow<String>()
    val receivedDataFlow: SharedFlow<String> = _receivedDataFlow.asSharedFlow()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val coroutineScope = CoroutineScope(Dispatchers.IO + Job())

    private var pendingAddress: String? = null

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)

            if (!_scannedDevices.value.any { it.device.address == result.device.address }) {
                _scannedDevices.value += result
                Log.d(TAG, "Device found: ${result.device.name ?: "Unknown"} (${result.device.address})")
            }

            val target = pendingAddress
            if (target != null && result.device.address == target) {
                Log.i(TAG, "Target device found: $target — 스캔 중단하고 연결합니다.")
                bleScanner?.stopScan(this)
                pendingAddress = null
                _connectionState.value = BleConnectionState.Connecting(result.device)
                bluetoothGatt = result.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            }
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            super.onBatchScanResults(results)
            results.forEach { result ->
                if (!_scannedDevices.value.any { it.device.address == result.device.address }) {
                    _scannedDevices.value += result
                }
            }
            Log.d(TAG, "Batch scan results: ${results.size} devices")
        }

        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            Log.e(TAG, "Scan failed with error code: $errorCode")
            _connectionState.value = BleConnectionState.Error("스캔 실패: $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            val deviceAddress = gatt?.device?.address
            Log.d(TAG, "onConnectionStateChange: Device=$deviceAddress, Status=$status, NewState=$newState")

            if (status == BluetoothGatt.GATT_SUCCESS) {
                when (newState) {
                    BluetoothProfile.STATE_CONNECTED -> {
                        bluetoothGatt = gatt
                        _connectionState.value = BleConnectionState.Connected(gatt?.device)
                        Log.i(TAG, "Successfully connected to $deviceAddress")
                        mainHandler.post {
                            val discoveryInitiated = bluetoothGatt?.discoverServices()
                            if (discoveryInitiated == true) {
                                Log.i(TAG, "Service discovery initiated for $deviceAddress.")
                            } else {
                                Log.w(TAG, "Failed to start service discovery for $deviceAddress.")
                                _connectionState.value = BleConnectionState.Error("서비스 검색 시작 실패", deviceAddress)
                                disconnectGatt()
                            }
                        }
                    }
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        Log.i(TAG, "Disconnected from $deviceAddress.")
                        _connectionState.value = BleConnectionState.Disconnected(deviceAddress, "연결 해제됨")
                        closeGatt()
                    }
                }
            } else {
                Log.e(TAG, "GATT Error onConnectionStateChange for $deviceAddress. Status: $status")
                _connectionState.value = BleConnectionState.Error("GATT 연결 오류: status $status", deviceAddress)
                closeGatt()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "Services discovered for ${gatt?.device?.address}.")
                val service = gatt?.getService(SERVICE_UUID)
                if (service == null) {
                    Log.w(TAG, "Service UUID $SERVICE_UUID not found.")
                    _connectionState.value = BleConnectionState.Error("서비스($SERVICE_UUID) 없음", gatt?.device?.address)
                    disconnectGatt()
                    return
                }
                writeCharacteristic = service.getCharacteristic(WRITE_UUID)
                notifyCharacteristic = service.getCharacteristic(NOTIFY_UUID)

                var foundAll = true
                if (writeCharacteristic == null) {
                    Log.w(TAG, "Write characteristic $WRITE_UUID not found.")
                    foundAll = false
                }
                if (notifyCharacteristic == null) {
                    Log.w(TAG, "Notify characteristic $NOTIFY_UUID not found.")
                    foundAll = false
                }

                if (foundAll && notifyCharacteristic != null) {
                    _connectionState.value = BleConnectionState.ServicesDiscovered(gatt?.device)
                    enableNotifications(gatt, notifyCharacteristic!!)
                } else {
                    Log.e(TAG, "One or more characteristics not found.")
                    _connectionState.value = BleConnectionState.Error("필수 특성 없음", gatt?.device?.address)
                    disconnectGatt()
                }
            } else {
                Log.w(TAG, "onServicesDiscovered received error status: $status for ${gatt?.device?.address}")
                _connectionState.value = BleConnectionState.Error("서비스 검색 실패: status $status", gatt?.device?.address)
                disconnectGatt()
            }
        }

        @SuppressLint("MissingPermission")
        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            if (descriptor?.characteristic?.uuid == NOTIFY_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "Notifications enabled successfully for ${descriptor.characteristic.uuid}")
                    _connectionState.value = BleConnectionState.NotificationEnabled(gatt?.device)
                } else {
                    Log.e(TAG, "Failed to write descriptor for ${descriptor.characteristic.uuid}. Status: $status")
                    _connectionState.value = BleConnectionState.Error("알림 활성화 실패: status $status", gatt?.device?.address)
                }
            }
        }

        @Deprecated("Used for API < 33", ReplaceWith("onCharacteristicChanged(gatt, characteristic, value)"))
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                handleCharacteristicChanged(gatt, characteristic, characteristic.value)
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleCharacteristicChanged(gatt, characteristic, value)
        }

        private fun handleCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray){
            if (characteristic.uuid == NOTIFY_UUID) {
                val receivedString = value.toString(Charsets.UTF_8)
                Log.i(TAG, "Characteristic <${characteristic.uuid}> changed. Received: \"$receivedString\"")
                coroutineScope.launch {
                    _receivedDataFlow.emit(receivedString)
                }
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            if (characteristic?.uuid == WRITE_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "Characteristic <${characteristic.uuid}> write successful.")
                } else {
                    Log.e(TAG, "Characteristic <${characteristic.uuid}> write failed. Status: $status")
                    _connectionState.value = BleConnectionState.Error("데이터 쓰기 실패: status $status", gatt?.device?.address)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startBleScan() {
        if (_connectionState.value == BleConnectionState.Scanning) {
            Log.d(TAG, "Scan already in progress.")
            return
        }
        _scannedDevices.value = emptyList()
        val scanFilters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(SERVICE_UUID)).build()
        )
        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        try {
            bleScanner?.startScan(scanFilters, scanSettings, scanCallback)
            _connectionState.value = BleConnectionState.Scanning
            Log.i(TAG, "BLE scan started.")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException on startBleScan: ${e.message}")
            _connectionState.value = BleConnectionState.Error("스캔 시작 보안 오류: ${e.message}")
        }  catch (e: IllegalStateException) {
            Log.e(TAG, "IllegalStateException on startBleScan (Bluetooth off?): ${e.message}")
            _connectionState.value = BleConnectionState.Error("블루투스가 꺼져있거나 스캔 시작 불가: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun stopBleScan() {
        try {
            bleScanner?.stopScan(scanCallback)
            if(_connectionState.value == BleConnectionState.Scanning) {
                _connectionState.value = BleConnectionState.Idle
            }
            Log.i(TAG, "BLE scan stopped.")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException on stopBleScan: ${e.message}")
            _connectionState.value = BleConnectionState.Error("스캔 중지 보안 오류: ${e.message}")
        } catch (e: IllegalStateException) {
            Log.e(TAG, "IllegalStateException on stopBleScan (Bluetooth off?): ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(deviceAddress: String) {
        val device = bluetoothAdapter.getRemoteDevice(deviceAddress)
        if (device == null) {
            Log.e(TAG, "Device not found with address: $deviceAddress")
            _connectionState.value = BleConnectionState.Error("기기($deviceAddress)를 찾을 수 없음")
            return
        }

        pendingAddress = deviceAddress
        if (bluetoothAdapter.bondedDevices.any { it.address == deviceAddress }) {
            Log.i(TAG, "Bonded device, connecting directly: $deviceAddress")
            _connectionState.value = BleConnectionState.Connecting(device)
            bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            return
        }

        if (bluetoothGatt != null && (bluetoothGatt?.device?.address == deviceAddress &&
                    (_connectionState.value is BleConnectionState.Connected || _connectionState.value is BleConnectionState.Connecting))) {
            Log.w(TAG, "Already connected or connecting to $deviceAddress.")
            return
        }

        if (bluetoothGatt != null) {
            Log.d(TAG, "Closing existing GATT connection before new one to $deviceAddress")
            disconnectGatt()
        }

        _connectionState.value = BleConnectionState.Connecting(device)
        Log.i(TAG, "Attempting to connect to GATT server of device: ${device.name ?: device.address}")
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        if (bluetoothGatt == null) {
            Log.e(TAG, "device.connectGatt returned null for $deviceAddress. Connection attempt failed locally.")
            _connectionState.value = BleConnectionState.Error("GATT 연결 초기화 실패", deviceAddress)
        }
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic) {
        if (gatt == null) {
            Log.e(TAG, "BluetoothGatt not initialized for enabling notifications.")
            _connectionState.value = BleConnectionState.Error("GATT null, 알림 활성화 불가", gatt?.device?.address)
            return
        }

        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            Log.e(TAG, "Failed to enable local notification for ${characteristic.uuid}")
            _connectionState.value = BleConnectionState.Error("로컬 알림 설정 실패", gatt.device.address)
            return
        }
        Log.d(TAG, "Local notification set successfully for ${characteristic.uuid}")

        val descriptor = characteristic.getDescriptor(CCCD_UUID)
        if (descriptor == null) {
            Log.e(TAG, "CCCD not found for characteristic ${characteristic.uuid}")
            _connectionState.value = BleConnectionState.Error("CCCD 없음 (${characteristic.uuid})", gatt.device.address)
            return
        }
        Log.d(TAG, "Found CCCD ${descriptor.uuid} for characteristic ${characteristic.uuid}")

        val enableValue = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        var writeInitiated = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val resultStatus = gatt.writeDescriptor(descriptor, enableValue)
            if (resultStatus == BluetoothStatusCodes.SUCCESS) {
                Log.i(TAG, "writeDescriptor (API 33+) initiated for CCCD on ${characteristic.uuid}")
                writeInitiated = true
            } else {
                Log.e(TAG, "writeDescriptor (API 33+) failed to initiate. Status: $resultStatus")
                _connectionState.value = BleConnectionState.Error("CCCD 쓰기 시작 실패 (API 33+): $resultStatus", gatt.device.address)
            }
        } else {
            @Suppress("DEPRECATION")
            descriptor.value = enableValue
            @Suppress("DEPRECATION")
            if (gatt.writeDescriptor(descriptor)) {
                Log.i(TAG, "writeDescriptor (Legacy) initiated for CCCD on ${characteristic.uuid}")
                writeInitiated = true
            } else {
                Log.e(TAG, "writeDescriptor (Legacy) failed to initiate for CCCD on ${characteristic.uuid}")
                _connectionState.value = BleConnectionState.Error("CCCD 쓰기 시작 실패 (Legacy)", gatt.device.address)
            }
        }
        if(writeInitiated){
            Log.i(TAG, "Notification enable request sent. Waiting for onDescriptorWrite.")
        }
    }

    @SuppressLint("MissingPermission")
    fun sendDataToDevice(data: ByteArray, writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT): Boolean {
        val gatt = bluetoothGatt
        val charToWrite = writeCharacteristic

        if (gatt == null) {
            Log.e(TAG, "BluetoothGatt not initialized. Cannot send data.")
            return false
        }
        if (charToWrite == null) {
            Log.e(TAG, "Write characteristic not found. Cannot send data.")
            return false
        }

        var success = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val resultStatus = gatt.writeCharacteristic(charToWrite, data, writeType)
                success = (resultStatus == BluetoothStatusCodes.SUCCESS)
                if (success) {
                    Log.i(TAG, "Write request (API 33+) initiated for <${charToWrite.uuid}>. Data: ${data.contentToString()}")
                } else {
                    Log.e(TAG, "Failed to initiate write request (API 33+) for <${charToWrite.uuid}>. Status code: $resultStatus")
                }
            } else {
                @Suppress("DEPRECATION")
                charToWrite.value = data
                charToWrite.writeType = writeType
                @Suppress("DEPRECATION")
                success = gatt.writeCharacteristic(charToWrite)
                if (success) {
                    Log.i(TAG, "Write request (Legacy) initiated for <${charToWrite.uuid}>. Data: ${data.contentToString()}")
                } else {
                    Log.e(TAG, "Failed to initiate write request (Legacy) for <${charToWrite.uuid}>.")
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException on sendDataToDevice: ${e.message}")
            _connectionState.value = BleConnectionState.Error("데이터 전송 보안 오류", gatt.device.address)
            return false
        }
        return success
    }

    fun sendStringToDevice(message: String, writeType: Int = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT): Boolean {
        return sendDataToDevice(message.toByteArray(Charsets.UTF_8), writeType)
    }

    @SuppressLint("MissingPermission")
    fun disconnectGatt() {
        val currentGatt = bluetoothGatt
        if (currentGatt != null) {
            Log.i(TAG, "Disconnecting GATT from ${currentGatt.device?.address}")
            currentGatt.disconnect() // onConnectionStateChange(STATE_DISCONNECTED)가 호출되고, 거기서 closeGatt() 호출
        } else {
            // 이미 GATT가 null이면, Disconnected 상태로 업데이트 (예: 이전에 이미 close 되었을 수 있음)
            if (_connectionState.value !is BleConnectionState.Disconnected && _connectionState.value !is BleConnectionState.Idle) {
                // _connectionState.value = BleConnectionState.Disconnected(null, "GATT 이미 null, 연결 해제됨")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun closeGatt() {
        val gattToClose = bluetoothGatt
        if (gattToClose != null) {
            Log.d(TAG, "Closing GATT client for ${gattToClose.device?.address}")
            try {
                gattToClose.close()
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException on gatt.close(): ${e.message}")
            }
        }
        bluetoothGatt = null
        writeCharacteristic = null
        notifyCharacteristic = null
        if (_connectionState.value !is BleConnectionState.Disconnected && _connectionState.value !is BleConnectionState.Error) {
            // _connectionState.value = BleConnectionState.Disconnected(gattToClose?.device?.address, "GATT Closed")
        } else if (_connectionState.value is BleConnectionState.Error && (bluetoothGatt?.device?.address == (_connectionState.value as BleConnectionState.Error).deviceAddress)) {
            // 오류로 인해 종료된 경우, Disconnected 상태로 재설정하지 않도록 함
        }
    }

    fun cleanup() {
        Log.d(TAG, "BleManager cleanup called.")
        stopBleScan()
        disconnectGatt()
    }
}
