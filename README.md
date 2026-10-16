# ESP32-C3 Bluetooth Smart Tag - Android App

> *Special Project built with dedication for my friend **Vetri**!*

Complete Android application developed for the ESP32-C3 Bluetooth Smart Tag project (Native Kotlin & Flutter options).

## Hardware Requirements
- **ESP32-C3 Super Mini development board** (4 MB flash)
- **MPU6050** accelerometer & gyroscope
- **Buzzer module board**
- **Push button** for monitoring ON/OFF state
- ESP32 firmware running Arduino C++ (PlatformIO) configured with BLE service UUID `6e400001-b5a3-f393-e0a9-e50e24dcca9e` and device name `SmartTag-C3`.

---

## BLE Communication Protocol
- **Service UUID**: `6e400001-b5a3-f393-e0a9-e50e24dcca9e`
- **Control Characteristic UUID**: `6e400002-b5a3-f393-e0a9-e50e24dcca9e` (Write: `ARM`, `DISARM`, `ALARM_ON`, `ALARM_OFF`)
- **Status Characteristic UUID**: `6e400003-b5a3-f393-e0a9-e50e24dcca9e` (Read + Notify, format: `MONITOR=ON;BLE=CONNECTED;ALARM=OFF;MPU=OK`)
- **BLE Device Name**: `SmartTag-C3`

---

## Option A: Native Kotlin Android App (Android Studio)

### Setup & Build
1. Open Android Studio and open project `SmartTag`.
2. Ensure Gradle sync completes successfully (`gradle.properties` configured with `android.useAndroidX=true`).
3. Build project via **Build > Make Project** or `app:assembleDebug`.

### Connecting Physical Android Device & USB Debugging
1. Enable **USB Debugging** in Developer Options on your Android phone.
2. Connect via USB and click **Run** (▶) in Android Studio to install and launch.

---

## Option B: Minimal Flutter App (`flutter_blue_plus`)

### Build and Install Instructions (Terminal)
Run these commands in your Flutter project terminal:

```bash
# Get dependencies
flutter pub get

# Build release APK
flutter build apk --release

# Connect your phone via USB with USB Debugging enabled and install the APK
flutter install
```

---

## Testing Instructions

1. **Power on ESP32-C3 Smart Tag**: Ensure your ESP32 board is running firmware broadcasting `SmartTag-C3`.
2. **Scan & Connect**:
   - Tap **Connect**. The app will scan for `SmartTag-C3`, connect automatically, request MTU 247, and subscribe to status notifications.
3. **Control & Status**:
   - Use the 2x2 grid buttons (`ARM`, `DISARM`, `ALARM ON`, `ALARM OFF`) to send commands to the ESP32.
   - View real-time status updates (`Monitoring`, `Alarm`, `Sensor`).
4. **Disconnection**:
   - Disconnect manually or turn off the ESP32. The app resets state to Disconnected.
