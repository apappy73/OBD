package com.example.obdapp.obd

import com.github.eltonvs.obd.command.AdaptiveTimingMode
import com.github.eltonvs.obd.command.BadResponseException
import com.github.eltonvs.obd.command.NoDataException
import com.github.eltonvs.obd.command.ObdProtocols
import com.github.eltonvs.obd.command.Switcher
import com.github.eltonvs.obd.command.at.AdapterVoltageCommand
import com.github.eltonvs.obd.command.at.DescribeProtocolCommand
import com.github.eltonvs.obd.command.at.DescribeProtocolNumberCommand
import com.github.eltonvs.obd.command.at.ResetAdapterCommand
import com.github.eltonvs.obd.command.at.SelectProtocolCommand
import com.github.eltonvs.obd.command.at.SetAdaptiveTimingCommand
import com.github.eltonvs.obd.command.at.SetEchoCommand
import com.github.eltonvs.obd.command.at.SetHeadersCommand
import com.github.eltonvs.obd.command.at.SetLineFeedCommand
import com.github.eltonvs.obd.command.at.SetSpacesCommand
import com.github.eltonvs.obd.command.control.AvailablePIDsCommand
import com.github.eltonvs.obd.command.control.AvailablePIDsCommand.AvailablePIDsRanges
import com.github.eltonvs.obd.command.control.DTCNumberCommand
import com.github.eltonvs.obd.command.control.MILOnCommand
import com.github.eltonvs.obd.command.control.PendingTroubleCodesCommand
import com.github.eltonvs.obd.command.control.PermanentTroubleCodesCommand
import com.github.eltonvs.obd.command.control.ResetTroubleCodesCommand
import com.github.eltonvs.obd.command.control.TroubleCodesCommand
import com.github.eltonvs.obd.command.control.VINCommand
import com.github.eltonvs.obd.connection.ObdDeviceConnection
import java.io.InputStream
import java.io.OutputStream

enum class DtcKind { STORED, PENDING, PERMANENT }

/**
 * Wraps kotlin-obd-api's ObdDeviceConnection with the app's session logic:
 * ELM327 init, protocol auto-detect, PID discovery, polling and DTC access.
 *
 * All methods are suspend and must be called from Dispatchers.IO.
 * ObdDeviceConnection serializes commands internally with a Mutex, so the
 * polling loop and one-shot DTC/VIN reads can safely share this instance.
 */
class ObdManager(inputStream: InputStream, outputStream: OutputStream) {

    private val connection = ObdDeviceConnection(inputStream, outputStream)

    var protocolName: String = "Unknown"
        private set

    var supportedPids: Set<String> = emptySet()
        private set

    /**
     * ELM327 setup + protocol auto-detect (ATSP0). The first Mode 01 query
     * forces the actual bus initialization, which is where "UNABLE TO CONNECT"
     * shows up if the ignition is off or the adapter is not seated properly.
     */
    suspend fun initialize() {
        connection.run(ResetAdapterCommand(), maxRetries = 12)
        connection.run(SetEchoCommand(Switcher.OFF))
        connection.run(SetLineFeedCommand(Switcher.OFF))
        connection.run(SetSpacesCommand(Switcher.OFF))
        connection.run(SetHeadersCommand(Switcher.OFF))
        connection.run(SetAdaptiveTimingCommand(AdaptiveTimingMode.AUTO_1))
        connection.run(SelectProtocolCommand(ObdProtocols.AUTO), maxRetries = 12)
        // Force bus init now rather than on the first live-data poll.
        connection.run(AvailablePIDsCommand(AvailablePIDsRanges.PIDS_01_TO_20), maxRetries = 12)
        protocolName = runCatching {
            connection.run(DescribeProtocolCommand(), useCache = true).value
        }.getOrDefault("Unknown")
    }

    /** Walks the Mode 01 PID-support bitmaps (00, 20, 40, ...). */
    suspend fun discoverSupportedPids(): Set<String> {
        val found = linkedSetOf<String>()
        val ranges = listOf(
            AvailablePIDsRanges.PIDS_01_TO_20 to "20",
            AvailablePIDsRanges.PIDS_21_TO_40 to "40",
            AvailablePIDsRanges.PIDS_41_TO_60 to "60",
            AvailablePIDsRanges.PIDS_61_TO_80 to "80",
            AvailablePIDsRanges.PIDS_81_TO_A0 to "A0"
        )
        for ((range, continuationPid) in ranges) {
            val pids = try {
                connection.run(AvailablePIDsCommand(range))
                    .value.split(',').map { it.trim().uppercase() }.filter { it.isNotEmpty() }
            } catch (e: BadResponseException) {
                break
            }
            found.addAll(pids)
            if (continuationPid !in pids) break
        }
        supportedPids = found
        return found
    }

    /** Reads one PID. Returns null on a benign OBD error (NO DATA etc.); IO errors propagate. */
    suspend fun readPid(def: PidDef): PidSample? {
        return try {
            val response = connection.run(def.commandFactory())
            PidSample(
                pidKey = def.key,
                label = def.label,
                value = if (def.chartable) PidCatalog.parseNumeric(response.value) else null,
                display = response.value,
                unit = response.unit.ifBlank { def.unit }
            )
        } catch (e: BadResponseException) {
            null
        }
    }

    /** One-shot vehicle/adapter info. Individual failures are skipped, IO errors propagate. */
    suspend fun readVehicleInfo(): Map<String, String> {
        val info = linkedMapOf<String, String>()
        suspend fun tryPut(key: String, block: suspend () -> String) {
            runCatching { block() }
                .onSuccess { if (it.isNotBlank()) info[key] = it }
        }
        tryPut("VIN") { connection.run(VINCommand(), useCache = true).value }
        tryPut("Protocol") { protocolName }
        tryPut("Protocol number (ELM)") { connection.run(DescribeProtocolNumberCommand(), useCache = true).value }
        tryPut("Adapter voltage") {
            val r = connection.run(AdapterVoltageCommand()); "${r.value} ${r.unit}".trim()
        }
        tryPut("Stored DTC count") { connection.run(DTCNumberCommand()).value }
        tryPut("MIL (check engine)") { connection.run(MILOnCommand()).value }
        return info
    }

    suspend fun readDtcs(kind: DtcKind): List<String> {
        val command = when (kind) {
            DtcKind.STORED -> TroubleCodesCommand()
            DtcKind.PENDING -> PendingTroubleCodesCommand()
            DtcKind.PERMANENT -> PermanentTroubleCodesCommand()
        }
        return try {
            connection.run(command, maxRetries = 10)
                .value.split(',')
                .map { it.trim().uppercase() }
                .filter { it.isNotEmpty() && it != "P0000" }
                .distinct()
        } catch (e: NoDataException) {
            emptyList()
        }
    }

    /** Mode 04: clears stored/pending codes, extinguishes MIL, resets readiness monitors. */
    suspend fun clearDtcs() {
        connection.run(ResetTroubleCodesCommand(), maxRetries = 10)
    }
}
