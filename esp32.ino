#include <Wire.h>
#include <Adafruit_GFX.h>
#include <Adafruit_SSD1306.h>
#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// OLED 설정
#define SCREEN_WIDTH 128
#define SCREEN_HEIGHT 64
#define OLED_RESET    -1

Adafruit_SSD1306 display(SCREEN_WIDTH, SCREEN_HEIGHT, &Wire, OLED_RESET);

// BLE UUID
#define SERVICE_UUID        "0000feed-0000-1000-8000-00805f9b34fb"
#define WRITE_CHAR_UUID     "0000feed-0001-1000-8000-00805f9b34fb"
#define NOTIFY_CHAR_UUID    "0000feed-0002-1000-8000-00805f9b34fb"

BLEServer*           pServer;
BLEAdvertising*      pAdv;
BLECharacteristic*   pWriteCharacteristic;
BLECharacteristic*   pNotifyCharacteristic;
bool                  deviceConnected = false;

// 서버 콜백: 연결/해제 시 동작
class MyServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* pServer) override {
    deviceConnected = true;
    Serial.println("Central connected");
  }
  void onDisconnect(BLEServer* pServer) override {
    deviceConnected = false;
    Serial.println("Central disconnected, restarting advertising...");
    // 재광고
    pAdv->start();
  }
};

// 쓰기 콜백: Arduino String 으로 받아 Serial과 OLED에 출력
class WriteCallback : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* pCharacteristic) override {
    String value = String(pCharacteristic->getValue().c_str());
    if (value.length() > 0) {
      Serial.print("Write received: ");
      for (size_t i = 0; i < value.length(); i++) {
        Serial.printf("0x%02X ", (uint8_t)value[i]);
      }
      Serial.println();

      display.clearDisplay();
      display.setCursor(0, 0);
      display.setTextSize(1);
      display.setTextColor(SSD1306_WHITE);
      display.print(value);
      display.display();
    }
  }
};

unsigned long lastNotify = 0;

void setup() {
  Serial.begin(115200);
  delay(1000);

  // OLED 초기화
  if (!display.begin(SSD1306_SWITCHCAPVCC, 0x3C)) {
    Serial.println("SSD1306 allocation failed");
    while (true);
  }
  display.clearDisplay();
  display.setTextSize(1);
  display.setTextColor(SSD1306_WHITE);
  display.setCursor(0, 0);
  display.println("BLE Peripheral");
  display.display();

  // BLE 초기화
  BLEDevice::init("ESP32_OLED_BLE");
  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());

  BLEService* pService = pServer->createService(SERVICE_UUID);

  // WRITE 특성
  pWriteCharacteristic = pService->createCharacteristic(
    WRITE_CHAR_UUID,
    BLECharacteristic::PROPERTY_WRITE
  );
  pWriteCharacteristic->setCallbacks(new WriteCallback());

  // NOTIFY 특성
  pNotifyCharacteristic = pService->createCharacteristic(
    NOTIFY_CHAR_UUID,
    BLECharacteristic::PROPERTY_NOTIFY
  );
  pNotifyCharacteristic->addDescriptor(new BLE2902());

  pService->start();

  // 광고 설정
  pAdv = pServer->getAdvertising();
  pAdv->addServiceUUID(SERVICE_UUID);
  pAdv->setScanResponse(false);
  pAdv->start();  // 최초 광고 시작

  Serial.println("BLE Peripheral 시작, 연결 대기 중...");
}

void loop() {
  // 연결된 상태에서만 notify
  if (deviceConnected) {
    unsigned long now = millis();
    if (now - lastNotify >= 1000) {
      lastNotify = now;
      uint8_t rnd = random(0, 256);
      pNotifyCharacteristic->setValue(&rnd, 1);
      pNotifyCharacteristic->notify();
      Serial.printf("Notified random byte: 0x%02X\n", rnd);
    }
  }
}
