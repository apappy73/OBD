package com.example.obdapp.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * BLE transport for ELM327 dongles.
 *
 * Cheap BLE ELM327 adapters do NOT expose classic Bluetooth SPP. They expose a
 * GATT "UART": one notify characteristic (adapter -> phone) and one write
 * characteristic (phone -> adapter). This class bridges that to the
 * InputStream/OutputStream pair expected by kotlin-obd-api's ObdDeviceConnection.
 *
 * Supported UART layouts (tried in order, then a generic notify+write fallback):
 *  - FFF0 service: FFF1 notify / FFF2 write   (most "ELM327 BLE" / Vgate iCar style)
 *  - Nordic UART 6E400001: TX 6E400003 notify / RX 6E400002 write
 *  - 18F0 service: 2AF0 notify / 2AF1 write   (some Veepeak / LeLink units)
 *  - FFE0 service: FFE1 notify+write          (HM-10 style modules)
 */
@SuppressLint("MissingPermission") // BLUETOOTH_CONNECT is granted in MainActivity before we get here
class BleObdTransport(
    private val context: Context,
    private val device: BluetoothDevice
) {

    interface Listener {
        /** GATT connected, UART found, notifications enabled: streams are usable. */
        fun onReady()

        /** Link lost or setup failed. [error] is null for a clean remote disconnect. */
        fun onDisconnected(error: String?)
    }

    var listener: Listener? = null

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private var writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

    @Volatile private var ready = false
    @Volatile private var closed = false
    @Volatile private var payloadSize = 20 // ATT MTU 23 - 3 until negotiated

    // ---- RX: GATT notifications -> InputStream ----
    private val rxChunks = LinkedBlockingQueue<ByteArray>()
    private val rxBuffered = AtomicInteger(0)
    private var current: ByteArray = EMPTY
    private var currentPos = 0

    @Volatile private var eof = false

    // ---- TX: OutputStream -> GATT writes, one in flight at a time ----
    private val writeLock = Any()

    @Volatile private var writeLatch: CountDownLatch? = null

    @Volatile private var writeStatus = BluetoothGatt.GATT_SUCCESS

    val inputStream: InputStream = object : InputStream() {
        override fun read(): Int {
            if (!fill()) return -1
            val b = current[currentPos].toInt() and 0xFF
            currentPos++
            rxBuffered.decrementAndGet()
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val n = minOf(len, current.size - currentPos)
            System.arraycopy(current, currentPos, b, off, n)
            currentPos += n
            rxBuffered.addAndGet(-n)
            return n
        }

        override fun available(): Int = rxBuffered.get()

        override fun close() { /* transport lifetime is managed by close() */ }

        /** Blocks until at least one byte is buffered; false on EOF (poison pill). */
        private fun fill(): Boolean {
            if (eof) return false
            while (currentPos >= current.size) {
                val chunk = try {
                    rxChunks.take()
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
                if (chunk === EOF || chunk.isEmpty()) {
                    eof = true
                    return false
                }
                current = chunk
                currentPos = 0
            }
            return true
        }
    }

    val outputStream: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            val g = gatt ?: throw IOException("GATT not connected")
            val ch = writeChar ?: throw IOException("UART characteristic not found")
            if (closed) throw IOException("Transport closed")
            var pos = off
            val end = off + len
            while (pos < end) {
                val n = minOf(payloadSize, end - pos)
                val chunk = b.copyOfRange(pos, end.coerceAtMost(pos + n))
                writeChunk(g, ch, chunk)
                pos += n
            }
        }

        override fun flush() { /* writes are synchronous */ }

        override fun close() { /* transport lifetime is managed by close() */ }
    }

    private fun writeChunk(g: BluetoothGatt, ch: BluetoothGattCharacteristic, chunk: ByteArray) {
        synchronized(writeLock) {
            val latch = CountDownLatch(1)
            writeLatch = latch
            writeStatus = BluetoothGatt.GATT_FAILURE
            val started = if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(ch, chunk, writeType) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                ch.value = chunk
                ch.writeType = writeType
                @Suppress("DEPRECATION")
                g.writeCharacteristic(ch)
            }
            if (!started) {
                writeLatch = null
                throw IOException("writeCharacteristic() failed to start")
            }
            if (!latch.await(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                writeLatch = null
                throw IOException("BLE write timed out")
            }
            writeLatch = null
            if (writeStatus != BluetoothGatt.GATT_SUCCESS) {
                throw IOException("BLE write failed with status $writeStatus")
            }
        }
    }

    // ---- Connection setup ----

    fun connect() {
        closed = false
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            ?: throw IOException("connectGatt() returned null")
    }

    fun close() {
        closed = true
        ready = false
        writeLatch?.countDown()
        rxChunks.offer(EOF)
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (e: SecurityException) {
            Log.w(TAG, "close: permission revoked", e)
        }
        gatt = null
    }

    private fun fail(error: String) {
        if (closed) return
        closed = true
        ready = false
        writeLatch?.countDown()
        rxChunks.offer(EOF)
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: SecurityException) {
        }
        gatt = null
        listener?.onDisconnected(error)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (closed) return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    // Negotiate a bigger MTU first; fall through to discovery either way.
                    if (!g.requestMtu(REQUESTED_MTU)) {
                        g.discoverServices()
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (ready) {
                        ready = false
                        closed = true
                        writeLatch?.countDown()
                        rxChunks.offer(EOF)
                        try { g.close() } catch (_: SecurityException) {}
                        gatt = null
                        listener?.onDisconnected(null)
                    } else {
                        fail("Connection failed (status $status)")
                    }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (closed) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                payloadSize = (mtu - 3).coerceAtLeast(20)
            }
            g.discoverServices()
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (closed) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Service discovery failed (status $status)")
                return
            }
            val uart = findUart(g)
            if (uart == null) {
                fail("No UART-like GATT service found on this device")
                return
            }
            val (notifyCh, writeCh) = uart
            writeChar = writeCh
            writeType = if (writeCh.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }

            g.setCharacteristicNotification(notifyCh, true)
            val cccd = notifyCh.getDescriptor(CCCD_UUID)
            if (cccd == null) {
                // Some clones omit the CCCD; notifications may still work.
                markReady()
                return
            }
            val value = if (notifyCh.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            }
            val started = if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(cccd, value) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
            if (!started) fail("Failed to enable notifications")
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (closed) return
            if (descriptor.uuid == CCCD_UUID) {
                if (status == BluetoothGatt.GATT_SUCCESS) markReady()
                else fail("Failed to enable notifications (status $status)")
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            writeStatus = status
            writeLatch?.countDown()
        }

        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            ingest(characteristic.value)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            ingest(value)
        }
    }

    private fun ingest(value: ByteArray?) {
        if (value == null || value.isEmpty() || closed) return
        rxChunks.offer(value.copyOf())
        rxBuffered.addAndGet(value.size)
    }

    private fun markReady() {
        ready = true
        listener?.onReady()
    }

    /** Returns (notify, write) characteristics of the first matching UART service. */
    private fun findUart(g: BluetoothGatt): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        for (spec in UART_SPECS) {
            val service = g.getService(spec.service) ?: continue
            val notifyCh = service.getCharacteristic(spec.notify) ?: continue
            val writeCh = service.getCharacteristic(spec.write) ?: continue
            if (!isNotifiable(notifyCh) || !isWritable(writeCh)) continue
            return notifyCh to writeCh
        }
        // Generic fallback: any service exposing a notifiable and a writable characteristic.
        for (service in g.services) {
            if (service.uuid == GENERIC_ACCESS || service.uuid == GENERIC_ATTRIBUTE) continue
            val notifyCh = service.characteristics.firstOrNull { isNotifiable(it) } ?: continue
            val writeCh = service.characteristics.firstOrNull { isWritable(it) } ?: continue
            return notifyCh to writeCh
        }
        return null
    }

    private fun isNotifiable(c: BluetoothGattCharacteristic): Boolean =
        c.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0

    private fun isWritable(c: BluetoothGattCharacteristic): Boolean =
        c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0

    private data class UartSpec(val service: UUID, val notify: UUID, val write: UUID)

    companion object {
        private const val TAG = "BleObdTransport"
        private const val REQUESTED_MTU = 247
        private const val WRITE_TIMEOUT_MS = 3000L
        private val EMPTY = ByteArray(0)
        private val EOF = ByteArray(0) // identity-compared poison pill

        private fun uuid(s: String): UUID = UUID.fromString(s)

        private val CCCD_UUID = uuid("00002902-0000-1000-8000-00805f9b34fb")
        private val GENERIC_ACCESS = uuid("00001800-0000-1000-8000-00805f9b34fb")
        private val GENERIC_ATTRIBUTE = uuid("00001801-0000-1000-8000-00805f9b34fb")

        private val UART_SPECS = listOf(
            UartSpec(
                uuid("0000fff0-0000-1000-8000-00805f9b34fb"),
                uuid("0000fff1-0000-1000-8000-00805f9b34fb"),
                uuid("0000fff2-0000-1000-8000-00805f9b34fb")
            ),
            UartSpec(
                uuid("6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
                uuid("6e400003-b5a3-f393-e0a9-e50e24dcca9e"),
                uuid("6e400002-b5a3-f393-e0a9-e50e24dcca9e")
            ),
            UartSpec(
                uuid("000018f0-0000-1000-8000-00805f9b34fb"),
                uuid("00002af0-0000-1000-8000-00805f9b34fb"),
                uuid("00002af1-0000-1000-8000-00805f9b34fb")
            ),
            UartSpec(
                uuid("0000ffe0-0000-1000-8000-00805f9b34fb"),
                uuid("0000ffe1-0000-1000-8000-00805f9b34fb"),
                uuid("0000ffe1-0000-1000-8000-00805f9b34fb")
            )
        )
    }
}
