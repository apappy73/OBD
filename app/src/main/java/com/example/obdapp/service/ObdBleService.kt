package com.example.obdapp.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.obdapp.ObdAppApplication
import com.example.obdapp.R
import com.example.obdapp.ble.BleObdTransport
import com.example.obdapp.data.ObdDatabase
import com.example.obdapp.obd.DtcKind
import com.example.obdapp.obd.FreezeFrame
import com.example.obdapp.obd.Mode06Record
import com.example.obdapp.obd.ObdManager
import com.example.obdapp.obd.PidCatalog
import com.example.obdapp.obd.PidDef
import com.example.obdapp.obd.PidSample
import com.example.obdapp.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Foreground service (connectedDevice) owning the BLE link and the OBD session.
 * Keeps polling while the UI is backgrounded; the Activity binds to it and
 * mirrors its flows through ObdViewModel.
 */
class ObdBleService : Service() {

    sealed interface ConnState {
        data object Disconnected : ConnState
        data class Connecting(val deviceName: String) : ConnState
        data object Initializing : ConnState
        data class Connected(val deviceName: String, val protocol: String) : ConnState
        data class Failed(val message: String) : ConnState
    }

    inner class LocalBinder : Binder() {
        val service: ObdBleService get() = this@ObdBleService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<ConnState>(ConnState.Disconnected)
    val state: StateFlow<ConnState> = _state

    private val _samples = MutableSharedFlow<PidSample>(extraBufferCapacity = 256)
    val samples: SharedFlow<PidSample> = _samples

    private val _supportedPids = MutableStateFlow<List<PidDef>>(emptyList())
    val supportedPids: StateFlow<List<PidDef>> = _supportedPids

    /** PIDs the polling loop reads each cycle. Set from the live-data screen. */
    val selectedPids = MutableStateFlow(PidCatalog.defaultSelection)

    /** When true, polled samples are persisted to SQLite. */
    val recording = MutableStateFlow(true)

    val db: ObdDatabase by lazy { ObdDatabase(this) }

    private var transport: BleObdTransport? = null
    private var manager: ObdManager? = null
    private var pollJob: Job? = null
    private var connectJob: Job? = null
    private var currentAddress: String? = null
    private var currentName: String = "Adapter"

    @Volatile private var userDisconnect = false

    @Volatile private var linkLostHandled = false
    private var stateWatcherStarted = false
    private var reconnectAttempts = 0

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val address = intent?.getStringExtra(EXTRA_ADDRESS)
        if (address == null) {
            if (_state.value is ConnState.Disconnected) stopSelf()
            return START_NOT_STICKY
        }
        startInForeground(getString(R.string.notif_connecting))
        connect(address, intent.getStringExtra(EXTRA_NAME) ?: "Adapter")
        return START_NOT_STICKY
    }

    // ---- Connection lifecycle ----

    fun connect(address: String, name: String) {
        if (connectJob?.isActive == true) return
        userDisconnect = false
        reconnectAttempts = 0
        currentAddress = address
        currentName = name
        connectJob = scope.launch { doConnect(address, name) }
    }

    private suspend fun doConnect(address: String, name: String) {
        cleanupSession()
        linkLostHandled = false
        _state.value = ConnState.Connecting(name)
        try {
            val adapter = getSystemService(BluetoothManager::class.java)?.adapter
                ?: throw IOException("Bluetooth unavailable")
            val device = adapter.getRemoteDevice(address)

            val t = BleObdTransport(this, device)
            transport = t
            withTimeout(CONNECT_TIMEOUT_MS) {
                suspendCancellableCoroutine { cont ->
                    t.listener = object : BleObdTransport.Listener {
                        override fun onReady() {
                            if (cont.isActive) cont.resume(Unit)
                        }

                        override fun onDisconnected(error: String?) {
                            if (cont.isActive) {
                                cont.resumeWithException(IOException(error ?: "Disconnected during setup"))
                            } else {
                                onLinkLost(error)
                            }
                        }
                    }
                    t.connect()
                    cont.invokeOnCancellation { t.close() }
                }
            }

            _state.value = ConnState.Initializing
            val m = ObdManager(t.inputStream, t.outputStream)
            manager = m
            m.initialize()
            val supported = m.discoverSupportedPids()
            _supportedPids.value = PidCatalog.all.filter { it.key in supported }
            // Keep the selection meaningful for this car.
            val usableDefaults = PidCatalog.defaultSelection.filter { it in supported }
            selectedPids.value = usableDefaults.ifEmpty {
                _supportedPids.value.take(4).map { it.key }
            }

            reconnectAttempts = 0
            _state.value = ConnState.Connected(name, m.protocolName)
            startPolling()
        } catch (e: Exception) {
            Log.w(TAG, "connect failed", e)
            linkLostHandled = true // this failure path already handles reconnects
            cleanupSession()
            if (!userDisconnect) maybeReconnect(e.message ?: "Connection failed")
        }
    }

    private fun onLinkLost(error: String?) {
        if (userDisconnect) return
        synchronized(this) {
            if (linkLostHandled) return
            linkLostHandled = true
        }
        scope.launch {
            cleanupSession()
            maybeReconnect(error ?: getString(R.string.notif_link_lost))
        }
    }

    private suspend fun maybeReconnect(reason: String) {
        val address = currentAddress ?: run {
            _state.value = ConnState.Failed(reason)
            return
        }
        if (reconnectAttempts >= MAX_RECONNECTS) {
            // The state collector removes the notification and stops the service.
            _state.value = ConnState.Failed(reason)
            return
        }
        reconnectAttempts++
        _state.value = ConnState.Connecting(currentName)
        updateNotification(getString(R.string.notif_reconnecting, reconnectAttempts))
        delay(RECONNECT_DELAY_MS * reconnectAttempts)
        if (!userDisconnect) doConnect(address, currentName)
    }

    fun disconnect() {
        userDisconnect = true
        scope.launch {
            connectJob?.cancelAndJoin()
            cleanupSession()
            _state.value = ConnState.Disconnected
            _supportedPids.value = emptyList()
            ServiceCompat.stopForeground(this@ObdBleService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private suspend fun cleanupSession() {
        withContext(NonCancellable) {
            pollJob?.cancelAndJoin()
            pollJob = null
            flushSampleBuffer()
            transport?.close()
            transport = null
            manager = null
        }
    }

    // ---- Polling ----

    private val sampleBuffer = ArrayList<PidSample>()

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = scope.launch {
            var lastFlush = System.currentTimeMillis()
            while (isActive) {
                val m = manager ?: break
                val keys = selectedPids.value
                for (key in keys) {
                    val def = PidCatalog.get(key) ?: continue
                    if (def.key !in m.supportedPids) continue
                    val sample = try {
                        m.readPid(def)
                    } catch (e: IOException) {
                        onLinkLost("Connection lost")
                        return@launch
                    } catch (e: Exception) {
                        Log.w(TAG, "poll ${def.key} failed", e)
                        null
                    }
                    if (sample != null) {
                        _samples.tryEmit(sample)
                        if (recording.value && sample.value != null) {
                            synchronized(sampleBuffer) { sampleBuffer.add(sample) }
                        }
                    }
                }
                val now = System.currentTimeMillis()
                if (now - lastFlush >= FLUSH_INTERVAL_MS) {
                    flushSampleBuffer()
                    lastFlush = now
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun flushSampleBuffer() {
        val batch: List<PidSample>
        synchronized(sampleBuffer) {
            if (sampleBuffer.isEmpty()) return
            batch = ArrayList(sampleBuffer)
            sampleBuffer.clear()
        }
        runCatching { db.insertSamples(batch) }
    }

    // ---- One-shot operations (called from ViewModel; hop to IO for OBD + SQLite) ----

    suspend fun readDtcs(kind: DtcKind): List<String> = withContext(Dispatchers.IO) {
        val m = manager ?: throw IOException("Not connected")
        m.readDtcs(kind).also { db.logDtcEvent(kind.name, it) }
    }

    suspend fun clearDtcs(): Unit = withContext(Dispatchers.IO) {
        val m = manager ?: throw IOException("Not connected")
        m.clearDtcs()
        db.logDtcEvent("CLEAR", emptyList())
    }

    suspend fun readFreezeFrame(): FreezeFrame = withContext(Dispatchers.IO) {
        val m = manager ?: throw IOException("Not connected")
        m.readFreezeFrame()
    }

    suspend fun readMode06(): List<Mode06Record> = withContext(Dispatchers.IO) {
        val m = manager ?: throw IOException("Not connected")
        m.readMode06()
    }

    suspend fun refreshVehicleInfo(): Map<String, String> = withContext(Dispatchers.IO) {
        val m = manager ?: throw IOException("Not connected")
        m.readVehicleInfo().also { db.saveVehicleInfo(it) }
    }

    fun cachedVehicleInfo(): Map<String, String> = runCatching { db.getVehicleInfo() }.getOrDefault(emptyMap())

    // ---- Foreground notification (system requirement for connectedDevice FGS) ----

    private fun startInForeground(text: String) {
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
        if (stateWatcherStarted) return
        stateWatcherStarted = true
        scope.launch {
            _state.collect { s ->
                when (s) {
                    is ConnState.Connected -> updateNotification(getString(R.string.notif_connected, s.protocol))
                    is ConnState.Connecting -> updateNotification(getString(R.string.notif_connecting))
                    is ConnState.Initializing -> updateNotification(getString(R.string.notif_initializing))
                    is ConnState.Failed -> {
                        // Terminal failure: drop the ongoing notification and let the
                        // system stop the service (a bound UI keeps it alive).
                        ServiceCompat.stopForeground(this@ObdBleService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                    // disconnect() already removed the notification; do not re-post it.
                    is ConnState.Disconnected -> Unit
                }
            }
        }
    }

    private fun buildNotification(text: String): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, ObdAppApplication.CHANNEL_OBD)
            .setSmallIcon(R.drawable.ic_bluetooth)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(text: String) {
        runCatching {
            getSystemService(android.app.NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification(text))
        }
    }

    override fun onDestroy() {
        userDisconnect = true
        transport?.close()
        runCatching { flushSampleBuffer() }
        runCatching { db.close() }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ObdBleService"
        const val EXTRA_ADDRESS = "address"
        const val EXTRA_NAME = "name"
        private const val NOTIF_ID = 1
        private const val CONNECT_TIMEOUT_MS = 25_000L
        private const val POLL_INTERVAL_MS = 250L
        private const val FLUSH_INTERVAL_MS = 4_000L
        private const val MAX_RECONNECTS = 3
        private const val RECONNECT_DELAY_MS = 2_000L
    }
}
