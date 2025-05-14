
@HiltViewModel
class BleViewModel @Inject constructor(
    private val bleManager: BleManager
) : ViewModel() {

    val scannedDevices: StateFlow<List<ScanResult>> = bleManager.scannedDevices
    val connectionState: StateFlow<BleConnectionState> = bleManager.connectionState
    val receivedData: SharedFlow<String> = bleManager.receivedDataFlow

    // 필요한 런타임 권한 목록 (예시)
    val requiredPermissions: List<String> = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
        listOf(
            android.Manifest.permission.BLUETOOTH_SCAN,
            android.Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        listOf(
            android.Manifest.permission.BLUETOOTH, // AndroidManifest에 maxSdkVersion="30" 이 있으므로 S 미만에서는 이것으로 충분할 수 있음
            android.Manifest.permission.ACCESS_FINE_LOCATION
        )
    }


    fun startScan() {
        // 권한이 부여되었는지 확인 후 호출하는 로직이 Activity/Fragment에 필요
        Log.d("BleViewModel", "Attempting to start scan...")
        bleManager.startBleScan()
    }

    fun stopScan() {
        Log.d("BleViewModel", "Attempting to stop scan.")
        bleManager.stopBleScan()
    }

    fun connectDevice(deviceAddress: String) {
        Log.d("BleViewModel", "Attempting to connect to $deviceAddress")
        bleManager.connectToDevice(deviceAddress)
    }

    fun disconnect() {
        Log.d("BleViewModel", "Attempting to disconnect.")
        bleManager.disconnectGatt()
    }

    fun sendData(message: String) {
        Log.d("BleViewModel", "Attempting to send data: $message")
        val success = bleManager.sendStringToDevice(message)
        if (!success) {
            Log.e("BleViewModel", "Failed to initiate send data command.")
            // UI에 오류 표시 또는 재시도 로직
        }
    }

    override fun onCleared() {
        super.onCleared()
        Log.d("BleViewModel", "ViewModel cleared. Cleaning up BleManager.")
        bleManager.cleanup() // ViewModel이 소멸될 때 BleManager 리소스 정리
    }
}
