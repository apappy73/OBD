package com.example.obdapp.obd

import com.github.eltonvs.obd.command.AdaptiveTimingMode
import com.github.eltonvs.obd.command.BadResponseException
import com.github.eltonvs.obd.command.NoDataException
import com.github.eltonvs.obd.command.ObdCommand
import com.github.eltonvs.obd.command.ObdProtocols
import com.github.eltonvs.obd.command.ObdRawResponse
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

/** Mode 02 freeze-frame snapshot: the DTC that stored it plus the captured PIDs. */
data class FreezeFrame(val dtc: String?, val samples: List<PidSample>)


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
        // Mode 09 ASCII identification fields. Multiple ECUs (PCM, TCM, …) can
        // each respond; their values are joined with " • ".
        tryPut("ECU hardware number") {
            val bytes = connection.run(Mode09AsciiCommand("09")).rawResponse.bufferedValue
            parseMode09Ascii(bytes, pidByte = 0x09).joinToString(" • ")
        }
        tryPut("Calibration ID") {
            val bytes = connection.run(Mode09AsciiCommand("04")).rawResponse.bufferedValue
            parseMode09Ascii(bytes, pidByte = 0x04).joinToString(" • ")
        }
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

    // ---- Mode 02: freeze frame ----

    /**
     * Reads the freeze-frame (Mode 02) snapshot stored with the first fault.
     * Returns an empty snapshot when the ECU reports no stored frame.
     */
    suspend fun readFreezeFrame(): FreezeFrame {
        val dtc = readFreezeFrameDtc()
        val samples = ArrayList<PidSample>()
        for (pid in FREEZE_FRAME_PIDS) {
            val def = PidCatalog.get(pid.key) ?: continue
            val data = readMode02Data(pid.key) ?: continue
            if (data.isEmpty()) continue
            val bytes = data.copyOf(pid.bytes)
            val command = def.commandFactory()
            val display = runCatching { command.handler(syntheticMode01(pid.key, bytes)) }
                .getOrNull() ?: continue
            samples.add(
                PidSample(
                    pidKey = def.key,
                    label = def.label,
                    value = null,
                    display = display,
                    unit = command.defaultUnit.ifBlank { def.unit }
                )
            )
        }
        return FreezeFrame(dtc, samples)
    }

    /** Mode 02 PID 02 returns the two bytes of the DTC that triggered the freeze frame. */
    private suspend fun readFreezeFrameDtc(): String? {
        val data = readMode02Data("02") ?: return null
        if (data.size < 2) return null
        val code = encodeDtc(data[0], data[1])
        return if (code == "P0000") null else code
    }

    /** Raw Mode 02 data bytes for one PID (frame 0), or null when the ECU has no value. */
    private suspend fun readMode02Data(pid: String): IntArray? {
        val response = try {
            connection.run(FreezeFrameCommand(pid), maxRetries = 2)
        } catch (e: BadResponseException) {
            return null
        }
        // bytes = [0x42, PID, frame #, data…]
        val bytes = response.rawResponse.bufferedValue
        if (bytes.size < 3) return null
        return bytes.copyOfRange(3, bytes.size)
    }

    /**
     * Re-labels a Mode 02 payload as a Mode 01 payload so the existing PID decoders
     * (which expect `41 <PID> <data>` starting at index 2) can be reused unchanged.
     */
    private fun syntheticMode01(pid: String, data: IntArray): ObdRawResponse {
        val hex = buildString(data.size * 2 + 4) {
            append("41").append(pid)
            for (b in data) append("%02X".format(b and 0xFF))
        }
        return ObdRawResponse(hex, 0L)
    }

    // ---- Mode 06: on-board monitoring test results ----

    /** Reads supported on-board monitors and their test results (Mode 06). */
    suspend fun readMode06(): List<Mode06Record> {
        val mids = discoverMode06Mids().ifEmpty { MODE06_FALLBACK_MIDS }
        val records = ArrayList<Mode06Record>()
        for (mid in mids.take(MAX_MODE06_MIDS)) {
            val response = try {
                connection.run(Mode06Command(mid), maxRetries = 2)
            } catch (e: BadResponseException) {
                continue
            }
            records.addAll(Mode06.parse(response.rawResponse.bufferedValue))
        }
        return records
    }

    /** Walks the Mode 06 supported-MID bitmaps (00, 20, 40 …). */
    private suspend fun discoverMode06Mids(): List<Int> {
        val found = sortedSetOf<Int>()
        for (base in MODE06_MID_BASES) {
            val response = try {
                connection.run(Mode06Command(base), maxRetries = 2)
            } catch (e: BadResponseException) {
                break
            }
            val bytes = response.rawResponse.bufferedValue
            // bytes = [0x46, MID, b0, b1, b2, b3]
            if (bytes.size < 6 || bytes[1] != base) break
            for (bit in 0 until 32) {
                if ((bytes[2 + bit / 8] and (0x80 shr (bit % 8))) != 0) found.add(base + bit + 1)
            }
            if ((bytes[5] and 0x01) == 0) break
        }
        return found.toList()
    }

    private class FreezeFrameCommand(private val framePid: String) : ObdCommand() {
        override val tag = "MODE02_PID_$framePid"
        override val name = "Freeze frame PID $framePid"
        override val mode = "02"
        override val pid = "$framePid 00"
    }

    private class Mode06Command(mid: Int) : ObdCommand() {
        override val tag = "MODE06_MID_${"%02X".format(mid)}"
        override val name = "Monitor test MID ${"%02X".format(mid)}"
        override val mode = "06"
        override val pid = "%02X".format(mid)
    }

    /** Mode 09 PID that returns ASCII data (e.g. 04 calibration ID, 09 hardware number). */
    private class Mode09AsciiCommand(pid: String) : ObdCommand() {
        override val tag = "MODE09_PID_$pid"
        override val name = "Mode 09 PID $pid"
        override val mode = "09"
        override val pid = pid
    }

    private companion object {
        /** (Mode 02 PID, byte length) pairs, ordered for display. */
        val FREEZE_FRAME_PIDS = listOf(
            FfPid("04", 1), FfPid("05", 1), FfPid("06", 1), FfPid("08", 1),
            FfPid("0B", 1), FfPid("0C", 2), FfPid("0D", 1), FfPid("0E", 1),
            FfPid("0F", 1), FfPid("10", 2), FfPid("11", 1), FfPid("1F", 2),
            FfPid("2F", 1), FfPid("33", 1), FfPid("42", 2), FfPid("43", 2),
            FfPid("45", 1), FfPid("46", 1), FfPid("5C", 1)
        )

        val MODE06_MID_BASES = listOf(0x00, 0x20, 0x40, 0x60, 0x80, 0xA0)

        /** Queried only when the ECU does not answer the supported-MID bitmaps. */
        val MODE06_FALLBACK_MIDS = listOf(0x01, 0x02, 0x21, 0x31, 0x39, 0x41, 0xA1)

        const val MAX_MODE06_MIDS = 40
    }
}

/** One freeze-frame PID to attempt, with the number of data bytes the decoder needs. */
private data class FfPid(val key: String, val bytes: Int)

/** Encodes the two DTC bytes returned by Mode 02 PID 02 into "Pxxxx" form. */
private fun encodeDtc(b0: Int, b1: Int): String {
    val letter = "PCBU"[(b0 shr 6) and 0x3]
    val d1 = (b0 shr 4) and 0x3
    val rest = "%X%X%X".format(b0 and 0xF, (b1 shr 4) and 0xF, b1 and 0xF)
    return "$letter$d1$rest"
}

/**
 * Splits a Mode 09 ASCII response into one string per ECU.
 *
 * On CAN (ISO 15765) with headers off, each answering ECU appends its own
 * `49 <PID> <len> <ASCII bytes>` block. We walk the byte stream, splitting on
 * the `49 <pidByte>` marker and reading the length byte that follows.
 */
private fun parseMode09Ascii(bytes: IntArray, pidByte: Int): List<String> {
    val out = ArrayList<String>()
    var i = 0
    while (i + 2 < bytes.size) {
        if (bytes[i] != 0x49 || bytes[i + 1] != pidByte) break
        val len = bytes[i + 2]
        i += 3
        val end = minOf(i + len, bytes.size)
        val ascii = bytes.copyOfRange(i, end)
            .map { it.toChar() }
            .joinToString("")
            .filter { it in ' '..'~' }
            .trim()
        if (ascii.isNotEmpty()) out.add(ascii)
        i += len
    }
    return out
}
