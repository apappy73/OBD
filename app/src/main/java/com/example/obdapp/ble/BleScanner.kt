package com.example.obdapp.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context

/**
 * Thin wrapper around BluetoothLeScanner. No scan filters: many ELM327 clones
 * advertise no service UUIDs and inconsistent names, so we show everything and
 * sort likely adapters first in the UI.
 */
@SuppressLint("MissingPermission") // BLUETOOTH_SCAN is granted in MainActivity before scanning
class BleScanner(context: Context) {

    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var callback: ScanCallback? = null

    val isBluetoothEnabled: Boolean get() = adapter?.isEnabled == true

    fun start(onResult: (ScanResult) -> Unit, onError: (String) -> Unit): Boolean {
        val scanner = adapter?.bluetoothLeScanner ?: run {
            onError("Bluetooth is off or unavailable")
            return false
        }
        stop()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) = onResult(result)

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(onResult)
            }

            override fun onScanFailed(errorCode: Int) {
                onError("BLE scan failed (code $errorCode)")
            }
        }
        callback = cb
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        return try {
            scanner.startScan(null, settings, cb)
            true
        } catch (e: SecurityException) {
            onError("Scan permission missing")
            false
        } catch (e: IllegalStateException) {
            onError("Bluetooth is off")
            false
        }
    }

    fun stop() {
        val cb = callback ?: return
        callback = null
        try {
            adapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (_: SecurityException) {
        } catch (_: IllegalStateException) {
        }
    }
}
