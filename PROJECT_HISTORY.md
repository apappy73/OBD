# OBDApp — Project History & Handover Notes

Living reference for future sessions. Update this file when the status of any
section below changes.

Last refreshed: 2026-11-23

---

## 1. Purpose

Personal OBD-II app for a **Mazda 3 (BL) 2010**, Android 15 (API 35), talking to a
cheap **BLE ELM327** dongle over a GATT UART. Single user, no backend, no accounts.

- Package / namespace: `com.example.obdapp`
- Root project name: `OBDApp`
- Repo root: `f:\TraePjt\OBD`

## 2. Actual build stack (verified from files, NOT from README)

| Piece | Version | Source |
|---|---|---|
| AGP | **9.5.1** | `build.gradle` (root) |
| Gradle wrapper | **9.6.0** | `gradle/wrapper/gradle-wrapper.properties` |
| Kotlin Android plugin | **2.3.10** | root `build.gradle` (matches kotlin-obd-api's compiler) |
| compileSdk / targetSdk / minSdk | 35 / 35 / 31 | `app/build.gradle` |
| OBD library | `com.github.eltonvs:kotlin-obd-api:1.4.1` (JitPack) | `app/build.gradle` |
| Charts | `com.github.PhilJay:MPAndroidChart:v3.1.0` (JitPack) | `app/build.gradle` |
| Storage | raw SQLite (`SQLiteOpenHelper`), no Room | `data/ObdDatabase.kt` |

> The README "Stack" table still says **AGP 8.7.3 / Gradle 8.9** — stale.
> `gradle.properties` carries AGP-9-era flags (`android.newDsl=false`,
> `android.builtInKotlin=false`, `android.enableAppCompileTimeRClass=false`, …).

## 3. Source map (all under `app/src/main/java/com/example/obdapp/`)

| File | Responsibility |
|---|---|
| `ObdAppApplication.kt` | Creates the `connectedDevice` notification channel (`CHANNEL_OBD`). |
| `ble/BleScanner.kt` | Thin BLE scan wrapper. No filters (clones advertise nothing). `start()/stop()/isBluetoothEnabled`. |
| `ble/BleObdTransport.kt` | **Core transport.** GATT UART → `InputStream`/`OutputStream` for the OBD lib. UART spec list (FFF0 / Nordic 6E400001 / 18F0 / FFE0) + generic notify+write fallback. MTU 247, per-write `CountDownLatch`, RX via `LinkedBlockingQueue` + EOF poison pill. |
| `obd/ObdManager.kt` | Wraps `ObdDeviceConnection`: ELM init, PID discovery, polling read, DTC read/clear, **Mode 02 freeze frame**, **Mode 06 monitors**, VIN/info. |
| `obd/PidCatalog.kt` | `PidDef` / `PidSample` + the 22 supported Mode 01 PIDs + `defaultSelection` (`0C 0D 05 11`) + `parseNumeric` (comma-locale fix). |
| `obd/DtcDescriptions.kt` | `genericMap` (common P0), **`mazdaMap`** (full Mazda P1xxx table), `describe()`. |
| `obd/Mode06.kt` | Mode 06: SAE J1979 UAS table, MID names, 9-byte record parser. |
| `service/ObdBleService.kt` | Foreground service owning BLE + session. `ConnState`, flows (`state`/`samples`/`supportedPids`), `selectedPids`, `recording`, poll loop (250 ms, flush 4 s), reconnect (≤3, backoff 2 s), one-shot ops delegating to `ObdManager` + `ObdDatabase`. |
| `data/ObdDatabase.kt` | Tables `samples`, `dtc_events`, `vehicle_info`. |
| `ui/MainActivity.kt` | 3-tab host (fragment hide/show), connect/disconnect, permission flow, scan dialog, manual MAC entry, edge-to-edge insets. |
| `ui/ObdViewModel.kt` | Activity-scoped; binds to service, mirrors flows, `DtcUiState` machine incl. `Detail` (freeze frame / Mode 06 rows). |
| `ui/LiveDataFragment.kt` | Chart (1 charted PID dropdown) + grid of polled values; in-memory per-PID history (600 pts, 60 s window). |
| `ui/DtcFragment.kt` | Read stored/pending, **Freeze frame**, **Monitor tests (Mode 06)**, clear; swaps `DtcAdapter` ⇄ `KeyValueAdapter` for detail rows. |
| `ui/VehicleInfoFragment.kt` | Cached + refresh vehicle info; auto-fetches once per process on first connect. |
| `ui/DtcAdapter.kt` / `KeyValueAdapter.kt` / `PidValueAdapter.kt` | RecyclerView adapters. |

## 4. Wire-format notes (hard-won; do not re-derive)

- **Mode 01**: `41 <PID> <data…>` → value bytes start at index **2**.
- **Mode 02 freeze frame**: `42 <PID> <frame#>` → value bytes start at index **3**
  (one extra frame-number byte). Command sent as `02 <PID> 00`.
  `ObdManager.syntheticMode01()` re-labels the Mode 02 payload as `41 <PID> <data>`
  so the existing Mode 01 decoders are reused unchanged.
- **Mode 06 (CAN / ISO 15765-4)**: response `46 <MID>` then N × **9-byte** records:
  `[MID, TID, UAS, valHi, valLo, minHi, minLo, maxHi, maxLo]`. **No CID field.**
  Supported-MID bitmaps requested at `00/20/40/60/80/A0`; bit set ⇒ `base+bit+1`.
- **DTC bytes** (Mode 02 PID 02): first char = `"PCBU"[(b0 shr 6) and 0x3]`.

## 5. Status

- **Phase 1 (done):** BLE link, ELM init + ATSP0 auto-detect, PID discovery,
  polling + SQLite recording, live chart/grid, DTC read/clear, vehicle info.
- **Phase 2 (done, 2026-11-23):** Mode 02 freeze frame, Mode 06 monitor tests,
  full Mazda P1xxx description table — all inside the existing DTC tab,
  session-only (no new tables).

## 6. Known issues / tech debt

1. ~~**Never compiled here.**~~ **Resolved 2026-11-23:** built clean in Android
   Studio (AGP 9.5.1 + Gradle 9.6.0 confirmed good) and installed on the phone.
   (Still not buildable from this agent environment — no SDK/`local.properties`.)
2. **No tests.** `app/src/test` does not exist.
3. **Recorded samples are write-only.** `ObdDatabase.recentSamples()` is defined
   but never called; `dtc_events` is written but never read. "Record" accumulates
   data nothing in the UI consumes.
4. **Destructive migration** in `ObdDatabase.onUpgrade` (drops all tables). `DB_VERSION`
   is still 1, so it has never fired. Deliberate + documented; low risk.
5. **Write-latch aliasing in `BleObdTransport`** — a late `onCharacteristicWrite`
   from a *timed-out* write can count down the *next* write's latch. No NPE (the
   callback uses `writeLatch?.countDown()`). Low practical impact (timeout throws →
   session teardown). Optional hardening.
6. **`BleScanner.stop()`** swallows `SecurityException`/`IllegalStateException`
   silently (observability only; failure modes mean scanning is already dead).
7. README stack table is stale (see §2). README "limitations" correctly notes
   CSV export still pending.

## 7. Conventions

- All OBD/db work on `Dispatchers.IO`; UI observes `StateFlow`/`SharedFlow`.
- One shared `ObdDeviceConnection` — it serializes internally with a Mutex, so the
  poll loop and one-shot reads can interleave safely.
- Constants live as `private const val` in each class's `companion object`
  (deliberately not centralized).
- No emojis in code except existing user-facing strings.

## 8. Backlog (candidate next steps)

- [x] **Verify build** in Android Studio — done 2026-11-23 (clean + installed).
- [ ] **CSV export** of `samples` (README already lists it as pending).
- [ ] **History / trip screen** reading `recentSamples()` + `dtc_events`
      (closes the write-only gap; alternative to CSV export).
- [ ] **Unit tests** for pure logic: `DtcDescriptions.describe`, `encodeDtc`,
      `Mode06.parse`, `PidCatalog.parseNumeric`.
- [ ] **Readiness monitors** (Mode 01 PID 01: MIL + since-cleared monitors) in DTC tab.
- [ ] Multiple PIDs charted at once; multiple freeze-frame indices.
- [ ] Fix stale README stack table.

## 9. Change log

- 2026-11-23 — **Theme: on-brand tonal colours** (`values/themes.xml`,
  `values/colors.xml`, new `values-night/colors.xml`). The theme previously
  overrode only `colorPrimary`/`colorSecondary`, so every tonal button and the
  bottom-nav active indicator fell back to Material 3's baseline
  `secondaryContainer` tint. Now `colorPrimaryContainer` /
  `colorSecondaryContainer` (and their `on…` pairs) are set to Soul Red tints:
  light `#FFDADB` on `#41000A`, dark `#93000F` on `#FFDADB`.
  Open question: `colorPrimary` stays `#A6192E` in dark mode, which is
  low-contrast for primary-coloured text on dark surfaces (e.g. the big value
  on the Live tab) — not addressed.

- 2026-11-23 — **Vehicle Info: ECU hardware number + calibration ID**
  (`obd/ObdManager.kt`). `readVehicleInfo()` now also fetches Mode 09 PID 09
  (ECU Hardware Number) and PID 04 (Calibration ID). A new `parseMode09Ascii()`
  splits the CAN multi-ECU response on `49 <PID>` markers — the Mazda 3 BL has
  two answering ECUs (PCM + TCM), so the screen shows both hardware numbers
  (e.g. `LFBL-18881 • LFBL-188K2`). Confirmed real values via a reference OBD
  app; protocol auto-detect also confirmed as ISO 15765-4 (11-bit, 500 kbaud).
  New private `Mode09AsciiCommand`. `VehicleInfoFragment` needs no change — it
  renders the map generically.

- 2026-11-23 — **BLE scan list improved** (`ui/MainActivity.kt`): the picker is
  now sectioned — "Paired devices" (bonded, alphabetical) then "Nearby devices";
  discovered OBD-looking names sort up within the Nearby section but never above
  the Paired block. Name resolution falls back to `scanRecord.deviceName` and
  `device.alias` before "Unknown". Implemented with an inner `ScanAdapter`
  (2 view types) + new `layout/item_scan_header.xml`; headers are non-clickable.
  New strings: `scan_section_paired`, `scan_section_nearby`.
  Prompted by the adapter showing up unrecognisable in scan.
