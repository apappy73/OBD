package com.example.obdapp.ui

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.obdapp.obd.DtcDescriptions
import com.example.obdapp.obd.DtcKind
import com.example.obdapp.obd.Mode06
import com.example.obdapp.obd.PidDef
import com.example.obdapp.obd.PidSample
import com.example.obdapp.service.ObdBleService
import com.example.obdapp.service.ObdBleService.ConnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Activity-scoped ViewModel shared by all three fragments. Binds to
 * [ObdBleService] and mirrors its flows so the UI survives fragment switches.
 */
class ObdViewModel(app: Application) : AndroidViewModel(app) {

    sealed interface DtcUiState {
        data object Idle : DtcUiState
        data object Loading : DtcUiState
        data class Result(val kind: DtcKind, val codes: List<String>) : DtcUiState
        data class Detail(val status: String, val rows: List<Pair<String, String>>) : DtcUiState
        data class Cleared(val message: String) : DtcUiState
        data class Error(val message: String) : DtcUiState
    }

    var service: ObdBleService? = null
        private set

    private val _state = MutableStateFlow<ConnState>(ConnState.Disconnected)
    val state: StateFlow<ConnState> = _state

    private val _samples = MutableSharedFlow<PidSample>(extraBufferCapacity = 256)
    val samples: SharedFlow<PidSample> = _samples

    private val _supportedPids = MutableStateFlow<List<PidDef>>(emptyList())
    val supportedPids: StateFlow<List<PidDef>> = _supportedPids

    private val _vehicleInfo = MutableStateFlow<Map<String, String>>(emptyMap())
    val vehicleInfo: StateFlow<Map<String, String>> = _vehicleInfo

    private val _vehicleInfoLoading = MutableStateFlow(false)
    val vehicleInfoLoading: StateFlow<Boolean> = _vehicleInfoLoading

    private val _dtcState = MutableStateFlow<DtcUiState>(DtcUiState.Idle)
    val dtcState: StateFlow<DtcUiState> = _dtcState

    private var mirrorJob: Job? = null

    val isConnected: Boolean get() = _state.value is ConnState.Connected

    fun attach(s: ObdBleService) {
        if (service === s) return
        mirrorJob?.cancel()
        service = s
        mirrorJob = viewModelScope.launch {
            launch(Dispatchers.IO) { _vehicleInfo.value = s.cachedVehicleInfo() }
            launch { s.state.collect { _state.value = it } }
            launch { s.samples.collect { _samples.emit(it) } }
            launch { s.supportedPids.collect { _supportedPids.value = it } }
        }
    }

    fun detach() {
        mirrorJob?.cancel()
        mirrorJob = null
        service = null
    }

    fun connect(address: String, name: String) {
        val intent = Intent(getApplication(), ObdBleService::class.java)
            .putExtra(ObdBleService.EXTRA_ADDRESS, address)
            .putExtra(ObdBleService.EXTRA_NAME, name)
        ContextCompat.startForegroundService(getApplication(), intent)
    }

    fun disconnect() {
        service?.disconnect()
    }

    fun setSelectedPids(keys: List<String>) {
        service?.selectedPids?.value = keys
    }

    fun setRecording(on: Boolean) {
        service?.recording?.value = on
    }

    fun isRecording(): Boolean = service?.recording?.value ?: true

    fun readDtcs(kind: DtcKind) {
        val s = service ?: return
        viewModelScope.launch {
            _dtcState.value = DtcUiState.Loading
            _dtcState.value = try {
                DtcUiState.Result(kind, s.readDtcs(kind))
            } catch (e: Exception) {
                DtcUiState.Error(e.message ?: "Failed to read codes")
            }
        }
    }

    fun clearDtcs() {
        val s = service ?: return
        viewModelScope.launch {
            _dtcState.value = DtcUiState.Loading
            _dtcState.value = try {
                s.clearDtcs()
                DtcUiState.Cleared("Codes cleared. Readiness monitors were reset.")
            } catch (e: Exception) {
                DtcUiState.Error(e.message ?: "Failed to clear codes")
            }
        }
    }

    /** Mode 02: the PID snapshot the ECU captured when the first fault was stored. */
    fun readFreezeFrame() {
        val s = service ?: return
        viewModelScope.launch {
            _dtcState.value = DtcUiState.Loading
            _dtcState.value = try {
                val frame = s.readFreezeFrame()
                if (frame.dtc == null && frame.samples.isEmpty()) {
                    DtcUiState.Detail("No freeze-frame data stored (Mode 02).", emptyList())
                } else {
                    val rows = ArrayList<Pair<String, String>>()
                    frame.dtc?.let {
                        rows.add("Stored DTC" to it)
                        rows.add("Description" to DtcDescriptions.describe(it))
                    }
                    frame.samples.forEach {
                        val unit = if (it.unit.isBlank()) "" else " ${it.unit}"
                        rows.add(it.label to "${it.display}$unit")
                    }
                    val head = frame.dtc?.let { "Freeze frame • $it" } ?: "Freeze frame"
                    DtcUiState.Detail("$head • ${frame.samples.size} value(s)", rows)
                }
            } catch (e: Exception) {
                DtcUiState.Error(e.message ?: "Failed to read freeze frame")
            }
        }
    }

    /** Mode 06: on-board monitoring test results with limits. */
    fun readMode06() {
        val s = service ?: return
        viewModelScope.launch {
            _dtcState.value = DtcUiState.Loading
            _dtcState.value = try {
                val records = s.readMode06()
                if (records.isEmpty()) {
                    DtcUiState.Detail("No Mode 06 monitor results reported by this ECU.", emptyList())
                } else {
                    val rows = records.map { r ->
                        val key = "MID 0x%02X · TID 0x%02X".format(r.mid, r.tid)
                        val value = "%s: %s  (range %s)".format(
                            Mode06.midName(r.mid), r.valueText, r.limitText
                        )
                        key to value
                    }
                    DtcUiState.Detail("${records.size} monitor test result(s)", rows)
                }
            } catch (e: Exception) {
                DtcUiState.Error(e.message ?: "Failed to read monitor tests")
            }
        }
    }

    fun refreshVehicleInfo() {
        val s = service ?: return
        viewModelScope.launch {
            _vehicleInfoLoading.value = true
            try {
                _vehicleInfo.value = s.refreshVehicleInfo()
            } catch (e: Exception) {
                // Keep cached values; surface nothing destructive.
            } finally {
                _vehicleInfoLoading.value = false
            }
        }
    }
}
