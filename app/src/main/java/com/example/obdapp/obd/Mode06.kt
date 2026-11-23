package com.example.obdapp.obd

/** One Mode 06 on-board monitor test result, already decoded for display. */
data class Mode06Record(
    val mid: Int,
    val tid: Int,
    val uasId: Int,
    val valueText: String,
    val limitText: String
)

/**
 * Mode 06 (on-board monitoring test results) support: SAE J1979 Unit-and-Scaling
 * (UAS) table, MID names and the 9-byte record parser.
 *
 * Wire format (CAN / ISO 15765-4): `46 <MID> <MID TID UAS vH vL minH minL maxH maxL> …`
 * i.e. after the mode byte the payload is a sequence of 9-byte test records whose
 * first byte is the MID. All test values / limits are 16-bit quantities.
 */
object Mode06 {

    private data class Uas(
        val scale: Double,
        val unit: String,
        val signed: Boolean = false,
        val offset: Double = 0.0,
        val boolean: Boolean = false
    )

    private val table: Map<Int, Uas> = buildMap {
        // unsigned
        put(0x01, Uas(1.0, "count"))
        put(0x02, Uas(0.1, "count"))
        put(0x03, Uas(0.01, "count"))
        put(0x04, Uas(0.001, "count"))
        put(0x05, Uas(0.0000305, "count"))
        put(0x06, Uas(0.000305, "count"))
        put(0x07, Uas(0.25, "rpm"))
        put(0x08, Uas(0.01, "km/h"))
        put(0x09, Uas(1.0, "km/h"))
        put(0x0A, Uas(0.122, "mV"))
        put(0x0B, Uas(0.001, "V"))
        put(0x0C, Uas(0.01, "V"))
        put(0x0D, Uas(0.00390625, "mA"))
        put(0x0E, Uas(0.001, "A"))
        put(0x0F, Uas(0.01, "A"))
        put(0x10, Uas(1.0, "ms"))
        put(0x11, Uas(100.0, "ms"))
        put(0x12, Uas(1.0, "s"))
        put(0x13, Uas(1.0, "mΩ"))
        put(0x14, Uas(1.0, "Ω"))
        put(0x15, Uas(1.0, "kΩ"))
        put(0x16, Uas(0.1, "°C", offset = -40.0))
        put(0x17, Uas(0.01, "kPa"))
        put(0x18, Uas(0.0117, "kPa"))
        put(0x19, Uas(0.079, "kPa"))
        put(0x1A, Uas(1.0, "kPa"))
        put(0x1B, Uas(10.0, "kPa"))
        put(0x1C, Uas(0.01, "°"))
        put(0x1D, Uas(0.5, "°"))
        put(0x1E, Uas(0.0000305, ""))
        put(0x1F, Uas(0.05, ""))
        put(0x20, Uas(0.00390625, ""))
        put(0x21, Uas(1.0, "mHz"))
        put(0x22, Uas(1.0, "Hz"))
        put(0x23, Uas(1.0, "kHz"))
        put(0x24, Uas(1.0, "count"))
        put(0x25, Uas(1.0, "km"))
        put(0x26, Uas(0.1, "mV/ms"))
        put(0x27, Uas(0.01, "g/s"))
        put(0x28, Uas(1.0, "g/s"))
        put(0x29, Uas(0.25, "Pa/s"))
        put(0x2A, Uas(0.001, "kg/h"))
        put(0x2B, Uas(1.0, "count"))
        put(0x2C, Uas(0.01, "g/cyl"))
        put(0x2D, Uas(0.01, "mg/stroke"))
        put(0x2E, Uas(1.0, "", boolean = true))
        put(0x2F, Uas(0.01, "%"))
        put(0x30, Uas(0.001526, "%"))
        put(0x31, Uas(0.001, "L"))
        put(0x32, Uas(0.0000305, "in"))
        put(0x33, Uas(0.00024414, ""))
        put(0x34, Uas(1.0, "min"))
        put(0x35, Uas(10.0, "ms"))
        put(0x36, Uas(0.01, "g"))
        put(0x37, Uas(0.1, "g"))
        put(0x38, Uas(1.0, "g"))
        put(0x39, Uas(0.01, "%", offset = -327.68))
        put(0x3A, Uas(0.001, "g"))
        put(0x3B, Uas(0.0001, "g"))
        put(0x3C, Uas(0.1, "µs"))
        put(0x3D, Uas(0.01, "mA"))
        put(0x3E, Uas(0.00006103516, "mm²"))
        put(0x3F, Uas(0.01, "L"))
        put(0x40, Uas(1.0, "ppm"))
        put(0x41, Uas(0.01, "µA"))
        // signed (two's complement)
        put(0x81, Uas(1.0, "count", signed = true))
        put(0x82, Uas(0.1, "count", signed = true))
        put(0x83, Uas(0.01, "count", signed = true))
        put(0x84, Uas(0.001, "count", signed = true))
        put(0x85, Uas(0.0000305, "count", signed = true))
        put(0x86, Uas(0.000305, "count", signed = true))
        put(0x87, Uas(1.0, "ppm", signed = true))
        put(0x8A, Uas(0.122, "mV", signed = true))
        put(0x8B, Uas(0.001, "V", signed = true))
        put(0x8C, Uas(0.01, "V", signed = true))
        put(0x8D, Uas(0.00390625, "mA", signed = true))
        put(0x8E, Uas(0.001, "A", signed = true))
        put(0x90, Uas(1.0, "ms", signed = true))
        put(0x96, Uas(0.1, "°C", signed = true))
        put(0x99, Uas(0.1, "kPa", signed = true))
        put(0x9C, Uas(0.01, "°", signed = true))
        put(0x9D, Uas(0.5, "°", signed = true))
        put(0xA8, Uas(1.0, "g/s", signed = true))
        put(0xA9, Uas(0.25, "Pa/s", signed = true))
        put(0xAD, Uas(0.01, "mg/stroke", signed = true))
        put(0xAE, Uas(0.1, "mg/stroke", signed = true))
        put(0xAF, Uas(0.01, "%", signed = true))
        put(0xB0, Uas(0.003052, "%", signed = true))
        put(0xB1, Uas(2.0, "mV/s", signed = true))
        put(0xFC, Uas(0.01, "kPa", signed = true))
        put(0xFD, Uas(0.001, "kPa", signed = true))
        put(0xFE, Uas(0.25, "Pa", signed = true))
    }

    /** Human-readable name for a Monitor ID. */
    fun midName(mid: Int): String = when {
        mid in 0x01..0x10 -> "O2 sensor monitor"
        mid in 0x21..0x24 -> "Catalyst monitor"
        mid in 0x31..0x34 -> "EGR monitor"
        mid in 0x35..0x38 -> "VVT monitor"
        mid == 0x39 -> "EVAP monitor (cap off)"
        mid == 0x3A -> "EVAP monitor (0.150\")"
        mid == 0x3B -> "EVAP monitor (0.090\")"
        mid == 0x3C -> "EVAP monitor (0.040\"/0.020\")"
        mid == 0x3D -> "EVAP purge flow monitor"
        mid in 0x41..0x50 -> "O2 sensor heater monitor"
        mid in 0x61..0x64 -> "Heated catalyst monitor"
        mid in 0x71..0x74 -> "Secondary air monitor"
        mid in 0x81..0x84 -> "Fuel system monitor"
        mid in 0x85..0x86 -> "Boost pressure control monitor"
        mid in 0x90..0x91 -> "NOx absorber monitor"
        mid in 0x98..0x99 -> "NOx catalyst monitor"
        mid == 0xA1 -> "Misfire monitor (general)"
        mid in 0xA2..0xAD -> "Misfire monitor (cylinder ${mid - 0xA1})"
        mid in 0xB0..0xB1 -> "PM filter monitor"
        else -> "Monitor"
    }

    fun hasUas(id: Int): Boolean = table.containsKey(id)

    /**
     * Parses Mode 06 response bytes (as emitted by the adapter): `[0x46, MID, …payload]`.
     * The payload is truncated to a whole number of 9-byte records.
     */
    fun parse(bytes: IntArray): List<Mode06Record> {
        if (bytes.size < 2 || bytes[0] != 0x46) return emptyList()
        val body = bytes.copyOfRange(1, bytes.size) // starts with the MID
        val usable = body.size - (body.size % RECORD_BYTES)
        val out = ArrayList<Mode06Record>()
        var i = 0
        while (i + RECORD_BYTES <= usable) {
            val mid = body[i]
            val tid = body[i + 1]
            val uasId = body[i + 2]
            val valueRaw = (body[i + 3] shl 8) or body[i + 4]
            val minRaw = (body[i + 5] shl 8) or body[i + 6]
            val maxRaw = (body[i + 7] shl 8) or body[i + 8]
            val uas = table[uasId]
            if (uas == null) {
                out.add(
                    Mode06Record(
                        mid, tid, uasId,
                        "0x%04X".format(valueRaw),
                        "0x%04X … 0x%04X".format(minRaw, maxRaw)
                    )
                )
            } else {
                out.add(
                    Mode06Record(
                        mid, tid, uasId,
                        decode(uas, valueRaw),
                        "${decode(uas, minRaw)} … ${decode(uas, maxRaw)}"
                    )
                )
            }
            i += RECORD_BYTES
        }
        return out
    }

    private fun decode(uas: Uas, raw: Int): String {
        if (uas.boolean) return if (raw != 0) "true" else "false"
        val value = if (uas.signed && raw >= 0x8000) (raw - 0x10000).toDouble() else raw.toDouble()
        val scaled = value * uas.scale + uas.offset
        val text = formatNumber(scaled)
        return if (uas.unit.isEmpty()) text else "$text ${uas.unit}"
    }

    private fun formatNumber(value: Double): String {
        val magnitude = kotlin.math.abs(value)
        val decimals = when {
            magnitude >= 1000 -> 0
            magnitude >= 10 -> 1
            magnitude >= 1 -> 2
            else -> 3
        }
        return "%.${decimals}f".format(value)
    }

    private const val RECORD_BYTES = 9
}
