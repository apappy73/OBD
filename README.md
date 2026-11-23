# Mazda OBD — Phase 2

Personal OBD-II app for a **Mazda 3 (BL) 2010**, targeting **Android 15 (API 35)**,
talking to a **BLE ELM327** adapter with protocol auto-detect.

Phase 1 scope (per the original plan): live data + charts, DTC read/clear,
vehicle info, SQLite history. **No maps, no notifications feature** (the ongoing
"adapter connection" notification is an Android system requirement for the
foreground service, not an app feature).

Phase 2 adds, inside the existing **DTC tab** (no new navigation item):
freeze-frame snapshots (**Mode 02**), on-board monitoring test results
(**Mode 06**), and the full **Mazda P1xxx** DTC description table. These are
read-only and session-only — nothing new is persisted to SQLite.

## Stack

| Piece | Choice | Notes |
|---|---|---|
| Language | Kotlin 2.3.10 | JVM target 17; matches kotlin-obd-api's own compiler |
| Build | AGP 8.7.3 / Gradle 8.9 | wrapper included |
| SDK | compile/target 35, min 31 | min 31 = modern BLE permission model, no legacy code paths |
| OBD | `com.github.eltonvs:kotlin-obd-api:1.4.1` (JitPack) | replaces the nonexistent "obdii" library from the plan |
| Charts | `com.github.PhilJay:MPAndroidChart:v3.1.0` (JitPack) | |
| Storage | raw SQLite via `SQLiteOpenHelper` | `samples`, `dtc_events`, `vehicle_info` tables |
| UI | Material 3, ViewBinding, 3 tabs | edge-to-edge (mandatory on API 35) |

## Architecture

```
ui/MainActivity ── binds ──> service/ObdBleService (foreground, connectedDevice)
ui/ObdViewModel (activity-scoped, mirrors service flows)
     │                            │
     ├─ LiveDataFragment          ├─ ble/BleObdTransport  (GATT UART ↔ InputStream/OutputStream)
     ├─ DtcFragment               ├─ ble/BleScanner
     └─ VehicleInfoFragment       ├─ obd/ObdManager       (wraps ObdDeviceConnection)
                                  └─ data/ObdDatabase     (SQLite)
```

- **BLE transport**: cheap BLE ELM327 dongles are *not* Bluetooth SPP — they expose a
  GATT UART. The transport tries FFF0 (FFF1 notify/FFF2 write), Nordic UART,
  18F0 (2AF0/2AF1) and FFE0/FFE1, then falls back to any notify+write pair.
  It negotiates MTU 247, serializes writes, and bridges notifications into a
  blocking `InputStream` for the OBD library.
- **Session**: `ATZ → ATE0 → ATL0 → ATS0 → ATH0 → ATAT1 → ATSP0` (auto protocol),
  then walks PID-support bitmaps (01 00 / 20 / 40 …). The Mazda 3 BL should land on
  **ISO 15765-4 CAN 11-bit 500 kbaud**.
- **Polling**: ~4 PIDs per 250 ms cycle (defaults: RPM, speed, coolant, throttle;
  plus whichever PID is charted). Samples are batch-written to SQLite every 4 s
  while "Record" is on.
- **Reconnect**: up to 3 automatic attempts with backoff after link loss.
- All OBD commands share one `ObdDeviceConnection` (internally mutex-serialized),
  so DTC reads/clears interleave safely with the polling loop.

## Permissions (Android 12+ / 15)

- `BLUETOOTH_SCAN` with `neverForLocation` → **no location permission needed**
- `BLUETOOTH_CONNECT`
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CONNECTED_DEVICE`
- Both Bluetooth permissions are requested at runtime before scanning.

## Build & run

1. Open the `OBDApp/` folder in Android Studio (Ladybug or newer).
2. Let Gradle sync (needs network for JitPack: kotlin-obd-api + MPAndroidChart).
3. Run on a device with BLE, Android 12+ (developed for Android 15).
4. Ignition ON (engine can be off), adapter seated in the OBD port
   (driver side, under the dash near the hood release).
5. Tap **Connect** → pick your adapter. The picker is sectioned: **Paired devices**
   first (pair the dongle once in system Bluetooth settings and it's easy to find),
   then **Nearby devices** (OBD-like names sorted to the top). "Enter address
   manually" works for dongles that don't advertise a name.

Command line: `./gradlew assembleDebug` (requires Android SDK; set `sdk.dir`
in `local.properties` or `ANDROID_HOME`).

## Known limitations / next steps

- Chart shows one PID at a time (dropdown); grid shows the polled set.
- No trip model yet — `samples` is one continuous time series per PID.
- DTC descriptions cover common generic P0 codes plus the full Mazda P1xxx
  table (`DtcDescriptions.mazdaMap`); anything else falls back to a
  prefix-based label.
- Freeze-frame (Mode 02) and Mode 06 monitor results are read-only and
  session-only (shown in the DTC tab; not persisted). Mode 06 decoding
  supports the SAE J1979 Unit-and-Scaling table; unknown UAS IDs show raw hex.
- CSV export is still pending.
- If your dongle uses a different GATT layout, add its UUIDs to
  `BleObdTransport.UART_SPECS`.
