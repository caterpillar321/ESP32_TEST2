# BleManager 사용 가이드

> **목표** : Jetpack Compose + Hilt(KSP) 기반 프로젝트에서 **BleManager** 를 이용하여 BLE 스캔‧연결‧데이터 송수신 기능을 손쉽게 통합하는 방법을 단계별로 설명합니다.

---

## 1. 환경 설정

### 1‑1. Gradle 의존성 및 KSP 활성화

```gradle
// app 모듈 build.gradle.kts
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")     // Hilt 플러그인
    id("com.google.devtools.ksp")             // KSP 플러그인 추가
}

dependencies {
    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.1")

    // Hilt & KSP
    implementation("com.google.dagger:hilt-android:2.51")
    ksp("com.google.dagger:hilt-compiler:2.51")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
```

### 1‑2. Android 권한

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

* **Android 13 이상** : `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` 런타임 권한 요청 필요.
* 위치 권한은 스캔 시 필수입니다. (OS 요구사항)

---

## 2. Hilt DI 설정

### 2‑1. Application 클래스

```kotlin
@HiltAndroidApp
class BleServerTestApp : Application()
```

### 2‑2. BleModule

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object BleModule {
    @Provides
    @Singleton
    fun provideBluetoothAdapter(@ApplicationContext context: Context): BluetoothAdapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    @Provides
    @Singleton
    fun provideBleManager(
        @ApplicationContext context: Context,
        bluetoothAdapter: BluetoothAdapter
    ): BleManager = BleManager(context, bluetoothAdapter)
}
```

BleManager는 `@Singleton`으로 제공되므로 여러 화면에서 동일 인스턴스를 공유합니다.

---

## 3. ViewModel 예시

```kotlin
@HiltViewModel
class BleViewModel @Inject constructor(
    private val bleManager: BleManager
) : ViewModel() {
    val connectionState = bleManager.connectionState
    val scannedDevices = bleManager.scannedDevices
    val received = bleManager.receivedDataFlow

    fun startScan() = bleManager.startBleScan()
    fun stopScan() = bleManager.stopBleScan()
    fun connect(address: String) = bleManager.connectToDevice(address)
    fun send(text: String) = bleManager.sendStringToDevice(text)

    override fun onCleared() {
        super.onCleared()
        bleManager.cleanup()
    }
}
```

---

## 4. Jetpack Compose 화면 구성

### 4‑1. 디바이스 목록 LazyColumn

```kotlin
@Composable
fun DeviceListScreen(viewModel: BleViewModel = hiltViewModel()) {
    val devices by viewModel.scannedDevices.collectAsState()
    val state by viewModel.connectionState.collectAsState()

    LaunchedEffect(Unit) { viewModel.startScan() }

    Column(modifier = Modifier.fillMaxSize()) {
        Text("BLE 디바이스", style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(16.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(devices) { result ->
                DeviceRow(result) { viewModel.connect(result.device.address) }
            }
        }

        ConnectionStatusBar(state)
    }
}

@Composable
private fun DeviceRow(result: ScanResult, onClick: () -> Unit) {
    val name = result.device.name ?: "이름 없음"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge)
            Text(text = result.device.address, style = MaterialTheme.typography.bodySmall)
        }
        Text(text = "RSSI ${result.rssi}")
    }
}
```

### 4‑2. 연결 상태 표시

```kotlin
@Composable
private fun ConnectionStatusBar(state: BleConnectionState) {
    val text = when (state) {
        is BleConnectionState.Idle               -> "대기 중"
        is BleConnectionState.Scanning           -> "스캔 중"
        is BleConnectionState.Connecting        -> "연결 시도: ${state.device?.address}"
        is BleConnectionState.Connected         -> "연결됨: ${state.device?.address}"
        is BleConnectionState.ServicesDiscovered -> "서비스 발견 완료"
        is BleConnectionState.NotificationEnabled-> "알림 구독 완료"
        is BleConnectionState.Disconnected      -> "연결 해제"
        is BleConnectionState.Error             -> "오류: ${state.message}"
    }
    Surface(color = MaterialTheme.colorScheme.primaryContainer) {
        Text(text, modifier = Modifier.padding(8.dp))
    }
}
```

---

## 5. 데이터 송수신 예제

```kotlin
// 연결된 후 버튼을 눌렀을 때 문자열 전송
Button(onClick = { viewModel.send("PING") }) { Text("Send PING") }

// 수신 데이터 표시
LaunchedEffect(Unit) {
    viewModel.received.collect { msg -> Log.d("BLE", "수신: $msg") }
}
```

---

## 6. Lifecycle & 자원 정리

* **Activity 기준** : `onResume` → 스캔 재개, `onPause` → `bleManager.stopBleScan()` 호출 권장.
* ViewModel `onCleared()` 에서 `bleManager.cleanup()` 을 호출해 메모리 누수를 방지하십시오.

---

## 7. Troubleshooting

| 현상           | 원인·대처                                   |
| ------------ | --------------------------------------- |
| 스캔이 시작되지 않음  | Bluetooth OFF 또는 권한 거부. 설정/권한 확인 후 재시도  |
| 서비스/특성 찾기 실패 | 펌웨어 UUID 불일치 가능성 → UUID 재검토             |
| 알림이 수신되지 않음  | CCCD 쓰기 실패 로그 확인, 보안 레벨 또는 페어링 필요 여부 확인 |

---

