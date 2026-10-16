import 'dart:async';
import 'dart:convert';
import 'package:flutter/material.dart';
import 'package:flutter_blue_plus/flutter_blue_plus.dart';
import 'package:permission_handler/permission_handler.dart';

void main() {
  runApp(const SmartTagApp());
}

class SmartTagApp extends StatelessWidget {
  const SmartTagApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'SmartTag',
      theme: ThemeData(primarySwatch: Colors.blue),
      home: const SmartTagScreen(),
      debugShowCheckedModeBanner: false,
    );
  }
}

class SmartTagScreen extends StatefulWidget {
  const SmartTagScreen({super.key});

  @override
  State<SmartTagScreen> createState() => _SmartTagScreenState();
}

class _SmartTagScreenState extends State<SmartTagScreen> {
  // BLE UUIDs
  static final Guid serviceUuid = Guid("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
  static final Guid controlCharUuid = Guid("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
  static final Guid statusCharUuid = Guid("6e400003-b5a3-f393-e0a9-e50e24dcca9e");

  BluetoothDevice? _targetDevice;
  BluetoothCharacteristic? _controlCharacteristic;
  BluetoothCharacteristic? _statusCharacteristic;
  StreamSubscription<List<ScanResult>>? _scanSubscription;
  StreamSubscription<BluetoothConnectionState>? _connectionStateSubscription;
  StreamSubscription<List<int>>? _notifySubscription;

  String _connectionState = "Disconnected";
  bool _isConnectingOrScanning = false;

  // Status variables parsed from ESP32
  String _monitor = "--";
  String _alarm = "--";
  String _mpu = "--";

  @override
  void dispose() {
    _scanSubscription?.cancel();
    _connectionStateSubscription?.cancel();
    _notifySubscription?.cancel();
    _targetDevice?.disconnect();
    super.dispose();
  }

  Future<void> _requestPermissions() async {
    Map<Permission, PermissionStatus> statuses = await [
      Permission.bluetoothScan,
      Permission.bluetoothConnect,
      Permission.location,
    ].request();

    bool scanGranted = statuses[Permission.bluetoothScan]?.isGranted ?? false;
    bool connectGranted = statuses[Permission.bluetoothConnect]?.isGranted ?? false;
    bool locationGranted = statuses[Permission.location]?.isGranted ?? false;

    if (!scanGranted || !connectGranted || !locationGranted) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text("Bluetooth and Location permissions are required.")),
        );
      }
    }
  }

  Future<void> _toggleConnection() async {
    if (_connectionState == "Connected") {
      await _targetDevice?.disconnect();
      return;
    }

    await _requestPermissions();

    setState(() {
      _isConnectingOrScanning = true;
      _connectionState = "Scanning...";
    });

    bool found = false;

    _scanSubscription = FlutterBluePlus.scanResults.listen((results) {
      for (ScanResult r in results) {
        if (r.device.platformName == "SmartTag-C3" ||
            r.advertisementData.serviceUuids.contains(serviceUuid)) {
          found = true;
          FlutterBluePlus.stopScan();
          _scanSubscription?.cancel();
          _connectToDevice(r.device);
          break;
        }
      }
    });

    try {
      await FlutterBluePlus.startScan(timeout: const Duration(seconds: 10));
    } catch (e) {
      print("Scan error: $e");
    }

    await Future.delayed(const Duration(seconds: 10));
    if (!found && _connectionState == "Scanning...") {
      FlutterBluePlus.stopScan();
      _scanSubscription?.cancel();
      setState(() {
        _isConnectingOrScanning = false;
        _connectionState = "Disconnected";
      });
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text("Device not found (Scan timed out)")),
        );
      }
    }
  }

  Future<void> _connectToDevice(BluetoothDevice device) async {
    setState(() {
      _connectionState = "Connecting...";
    });

    _targetDevice = device;

    _connectionStateSubscription = device.connectionState.listen((state) async {
      if (state == BluetoothConnectionState.disconnected) {
        _handleDisconnection();
      }
    });

    try {
      await device.connect(autoConnect: false);
      setState(() {
        _connectionState = "Connected";
        _isConnectingOrScanning = false;
      });

      // Request MTU 247
      try {
        await device.requestMtu(247);
      } catch (e) {
        print("Request MTU error: $e");
      }

      // Discover services
      List<BluetoothService> services = await device.discoverServices();
      for (BluetoothService service in services) {
        if (service.uuid == serviceUuid) {
          for (BluetoothCharacteristic c in service.characteristics) {
            if (c.uuid == controlCharUuid) {
              _controlCharacteristic = c;
            } else if (c.uuid == statusCharUuid) {
              _statusCharacteristic = c;
            }
          }
        }
      }

      if (_statusCharacteristic != null) {
        // Read once immediately
        try {
          List<int> value = await _statusCharacteristic!.read();
          _parseStatus(utf8.decode(value));
        } catch (e) {
          print("Read status error: $e");
        }

        // Subscribe to notifications
        await _statusCharacteristic!.setNotifyValue(true);
        _notifySubscription = _statusCharacteristic!.lastValueStream.listen((value) {
          if (value.isNotEmpty) {
            _parseStatus(utf8.decode(value));
          }
        });
      }
    } catch (e) {
      print("Connection error: $e");
      _handleDisconnection();
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text("Failed to connect: $e")),
        );
      }
    }
  }

  void _handleDisconnection() {
    _notifySubscription?.cancel();
    setState(() {
      _connectionState = "Disconnected";
      _isConnectingOrScanning = false;
      _monitor = "--";
      _alarm = "--";
      _mpu = "--";
      _controlCharacteristic = null;
      _statusCharacteristic = null;
    });
  }

  void _parseStatus(String raw) {
    // MONITOR=ON;BLE=CONNECTED;ALARM=OFF;MPU=OK
    Map<String, String> parsed = {};
    for (String part in raw.split(';')) {
      List<String> kv = part.split('=');
      if (kv.length == 2) {
        parsed[kv[0].trim()] = kv[1].trim();
      }
    }
    setState(() {
      _monitor = parsed['MONITOR'] ?? '--';
      _alarm = parsed['ALARM'] ?? '--';
      _mpu = parsed['MPU'] ?? '--';
    });
  }

  Future<void> _sendCommand(String command) async {
    if (_controlCharacteristic == null) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text("Not connected to control characteristic")),
      );
      return;
    }
    try {
      await _controlCharacteristic!.write(utf8.encode(command), withoutResponse: false);
    } catch (e) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text("Failed to send command: $e")),
        );
      }
    }
  }

  Color _getMonitorColor(String val) {
    if (_connectionState != "Connected") return Colors.grey;
    if (val == "ON") return Colors.green;
    return Colors.grey;
  }

  Color _getAlarmColor(String val) {
    if (_connectionState != "Connected") return Colors.grey;
    if (val == "OFF") return Colors.green;
    if (val == "ON" || val == "DISCONNECT" || val == "ON+DISCONNECT") return Colors.red;
    return Colors.grey;
  }

  Color _getMpuColor(String val) {
    if (_connectionState != "Connected") return Colors.grey;
    if (val == "OK") return Colors.green;
    if (val == "ERROR") return Colors.red;
    return Colors.grey;
  }

  @override
  Widget build(BuildContext context) {
    bool isConnected = (_connectionState == "Connected");

    return Scaffold(
      appBar: AppBar(
        title: const Text('SmartTag'),
      ),
      body: Padding(
        padding: const EdgeInsets.all(16.0),
        child: Column(
          children: [
            ElevatedButton(
              onPressed: _isConnectingOrScanning ? null : _toggleConnection,
              style: ElevatedButton.styleFrom(
                backgroundColor: isConnected ? Colors.red : Colors.blue,
                foregroundColor: Colors.white,
                minimumSize: const Size.fromHeight(48),
              ),
              child: Text(isConnected ? 'Disconnect' : 'Connect'),
            ),
            const SizedBox(height: 8),
            Text(
              _connectionState,
              style: const TextStyle(fontSize: 16, fontWeight: FontWeight.bold),
            ),
            const SizedBox(height: 24),
            Card(
              elevation: 4,
              child: Padding(
                padding: const EdgeInsets.all(16.0),
                child: Column(
                  children: [
                    _buildStatusRow('Monitoring', _monitor, _getMonitorColor(_monitor)),
                    const Divider(),
                    _buildStatusRow('Alarm', _alarm, _getAlarmColor(_alarm)),
                    const Divider(),
                    _buildStatusRow('Sensor (MPU)', _mpu, _getMpuColor(_mpu)),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 24),
            Expanded(
              child: GridView.count(
                crossAxisCount: 2,
                crossAxisSpacing: 16,
                mainAxisSpacing: 16,
                childAspectRatio: 2.0,
                children: [
                  ElevatedButton(
                    onPressed: isConnected ? () => _sendCommand('ARM') : null,
                    child: const Text('ARM', style: TextStyle(fontSize: 18)),
                  ),
                  ElevatedButton(
                    onPressed: isConnected ? () => _sendCommand('DISARM') : null,
                    child: const Text('DISARM', style: TextStyle(fontSize: 18)),
                  ),
                  ElevatedButton(
                    onPressed: isConnected ? () => _sendCommand('ALARM_ON') : null,
                    style: ElevatedButton.styleFrom(backgroundColor: Colors.orange, foregroundColor: Colors.white),
                    child: const Text('ALARM ON', style: TextStyle(fontSize: 18)),
                  ),
                  ElevatedButton(
                    onPressed: isConnected ? () => _sendCommand('ALARM_OFF') : null,
                    style: ElevatedButton.styleFrom(backgroundColor: Colors.green, foregroundColor: Colors.white),
                    child: const Text('ALARM OFF', style: TextStyle(fontSize: 18)),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildStatusRow(String label, String value, Color color) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceBetween,
      children: [
        Text(label, style: const TextStyle(fontSize: 16)),
        Text(
          value,
          style: TextStyle(fontSize: 16, fontWeight: FontWeight.bold, color: color),
        ),
      ],
    );
  }
}
