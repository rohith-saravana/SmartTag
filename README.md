# SmartTag

Android clients for an ESP32-C3 Bluetooth Low Energy smart tag. This repository includes a native Kotlin app and a Flutter client. The ESP32 firmware is a separate project and is not included here.

## Features

- Scan for and connect to a tag named `SmartTag-C3` or advertising the SmartTag BLE service.
- Send arm, disarm, and alarm commands.
- Read and receive live monitoring, alarm, and sensor status.
- The native Android client also estimates proximity from BLE signal strength and supports one-meter RSSI calibration.

## Repository layout

| Path | Description |
| --- | --- |
| `app/` | Native Android application (Kotlin, Android Studio, Gradle) |
| `lib/main.dart` | Flutter application using `flutter_blue_plus` |
| `pubspec.yaml` | Flutter dependencies and SDK constraints |

## Requirements

- Android phone or tablet with Bluetooth Low Energy support
- For the native app: Android Studio, Android SDK 34, and JDK 17
- For the Flutter app: Flutter SDK with Dart 3, and Android build tools
- A separately built ESP32-C3 SmartTag firmware implementing the BLE protocol below

## Run the native Android app

Open this repository in Android Studio, allow Gradle sync to finish, then run the `app` configuration on an Android device. To build a debug APK from a terminal:

```bash
./gradlew :app:assembleDebug
```

On Windows, use `gradlew.bat :app:assembleDebug`.

## Run the Flutter app

From the repository root, fetch dependencies and run on a connected Android device:

```bash
flutter pub get
flutter run
```

To build a release APK:

```bash
flutter build apk --release
```

Grant the requested Bluetooth permissions. On older Android versions, also grant location permission for BLE scanning. Enable Bluetooth on the phone and power on the ESP32 tag before connecting.

## BLE protocol

Both clients expect the ESP32 firmware to expose this GATT service:

| Attribute | UUID | Access / value |
| --- | --- | --- |
| Service | `6e400001-b5a3-f393-e0a9-e50e24dcca9e` | SmartTag service |
| Control characteristic | `6e400002-b5a3-f393-e0a9-e50e24dcca9e` | Write UTF-8 commands: `ARM`, `DISARM`, `ALARM_ON`, `ALARM_OFF` |
| Status characteristic | `6e400003-b5a3-f393-e0a9-e50e24dcca9e` | Read and notify; semicolon-separated key/value fields |

The status value is expected in a format such as:

```text
MONITOR=ON;BLE=CONNECTED;ALARM=OFF;MPU=OK
```

The device should advertise as `SmartTag-C3` or include the SmartTag service UUID in its advertisement.

## Troubleshooting

- **The tag is not found:** Confirm the ESP32 is powered, advertising the expected name or service UUID, and within range. Check Bluetooth and runtime permissions on the phone.
- **Connection succeeds but controls do not work:** Verify that the firmware exposes the control characteristic with write access and accepts the command strings above.
- **Status does not update:** Verify that the status characteristic supports reads and notifications and sends the documented status format.
- **Native proximity estimate is inaccurate:** Keep the phone and tag at a measured one-meter distance during calibration, then recalibrate in a stable environment. RSSI distance is an approximation and varies with surroundings.
