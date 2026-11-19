package com.example.obdapp.obd

/**
 * Descriptions for common generic (P0xxx) trouble codes, biased toward
 * codes frequently seen on MZR LF/L5 engines of the Mazda 3 BL.
 * Manufacturer-specific codes (P1xxx for Mazda) fall back to a generic label.
 */
object DtcDescriptions {

    private val map = mapOf(
        "P0010" to "Camshaft position actuator circuit (Bank 1 intake)",
        "P0011" to "Camshaft position - timing over-advanced (Bank 1 intake)",
        "P0012" to "Camshaft position - timing over-retarded (Bank 1 intake)",
        "P0016" to "Crank/cam correlation mismatch (Bank 1 sensor A)",
        "P0030" to "HO2S heater control circuit (Bank 1 Sensor 1)",
        "P0031" to "HO2S heater control circuit low (Bank 1 Sensor 1)",
        "P0032" to "HO2S heater control circuit high (Bank 1 Sensor 1)",
        "P0037" to "HO2S heater control circuit low (Bank 1 Sensor 2)",
        "P0038" to "HO2S heater control circuit high (Bank 1 Sensor 2)",
        "P0101" to "MAF sensor circuit range/performance",
        "P0102" to "MAF sensor circuit low input",
        "P0103" to "MAF sensor circuit high input",
        "P0106" to "MAP sensor circuit range/performance",
        "P0107" to "MAP sensor circuit low input",
        "P0108" to "MAP sensor circuit high input",
        "P0111" to "IAT sensor circuit range/performance",
        "P0112" to "IAT sensor circuit low input",
        "P0113" to "IAT sensor circuit high input",
        "P0117" to "ECT sensor circuit low input",
        "P0118" to "ECT sensor circuit high input",
        "P0121" to "Throttle position sensor circuit range/performance",
        "P0122" to "Throttle position sensor circuit low input",
        "P0123" to "Throttle position sensor circuit high input",
        "P0125" to "Insufficient coolant temp for closed loop fuel control",
        "P0128" to "Coolant thermostat - temp below regulating temperature",
        "P0130" to "O2 sensor circuit (Bank 1 Sensor 1)",
        "P0131" to "O2 sensor circuit low voltage (Bank 1 Sensor 1)",
        "P0132" to "O2 sensor circuit high voltage (Bank 1 Sensor 1)",
        "P0133" to "O2 sensor circuit slow response (Bank 1 Sensor 1)",
        "P0134" to "O2 sensor circuit no activity detected (Bank 1 Sensor 1)",
        "P0135" to "O2 sensor heater circuit (Bank 1 Sensor 1)",
        "P0137" to "O2 sensor circuit low voltage (Bank 1 Sensor 2)",
        "P0138" to "O2 sensor circuit high voltage (Bank 1 Sensor 2)",
        "P0139" to "O2 sensor circuit slow response (Bank 1 Sensor 2)",
        "P0141" to "O2 sensor heater circuit (Bank 1 Sensor 2)",
        "P0171" to "System too lean (Bank 1)",
        "P0172" to "System too rich (Bank 1)",
        "P0201" to "Injector circuit - cylinder 1",
        "P0202" to "Injector circuit - cylinder 2",
        "P0203" to "Injector circuit - cylinder 3",
        "P0204" to "Injector circuit - cylinder 4",
        "P0300" to "Random/multiple cylinder misfire detected",
        "P0301" to "Cylinder 1 misfire detected",
        "P0302" to "Cylinder 2 misfire detected",
        "P0303" to "Cylinder 3 misfire detected",
        "P0304" to "Cylinder 4 misfire detected",
        "P0327" to "Knock sensor circuit low input (Bank 1)",
        "P0328" to "Knock sensor circuit high input (Bank 1)",
        "P0335" to "Crankshaft position sensor A circuit",
        "P0340" to "Camshaft position sensor A circuit (Bank 1)",
        "P0401" to "EGR flow insufficient",
        "P0402" to "EGR flow excessive",
        "P0420" to "Catalyst system efficiency below threshold (Bank 1)",
        "P0442" to "EVAP system small leak detected",
        "P0443" to "EVAP purge control valve circuit",
        "P0455" to "EVAP system gross leak detected",
        "P0456" to "EVAP system very small leak detected",
        "P0480" to "Cooling fan 1 control circuit",
        "P0500" to "Vehicle speed sensor A",
        "P0505" to "Idle air control system",
        "P0506" to "Idle control system RPM lower than expected",
        "P0507" to "Idle control system RPM higher than expected",
        "P0562" to "System voltage low",
        "P0563" to "System voltage high",
        "P0606" to "ECM/PCM processor fault",
        "P0661" to "Intake manifold tuning valve circuit low (Bank 1)",
        "P0700" to "Transmission control system - MIL request",
        "P2009" to "Intake manifold runner control circuit low (Bank 1)",
        "P2096" to "Post catalyst fuel trim system too lean (Bank 1)",
        "P2097" to "Post catalyst fuel trim system too rich (Bank 1)",
        "P2101" to "Throttle actuator control motor circuit range/performance",
        "P2135" to "Throttle/pedal position sensor A/B voltage correlation",
        "P2187" to "System too lean at idle (Bank 1)",
        "P2188" to "System too rich at idle (Bank 1)",
        "P2195" to "O2 sensor signal stuck lean (Bank 1 Sensor 1)",
        "P2196" to "O2 sensor signal stuck rich (Bank 1 Sensor 1)"
    )

    fun describe(code: String): String {
        map[code]?.let { return it }
        return when {
            code.startsWith("P1") -> "Manufacturer-specific powertrain code (Mazda) — check service manual"
            code.startsWith("P2") || code.startsWith("P3") -> "Generic powertrain code — check service manual"
            code.startsWith("C") -> "Chassis code (ABS/DSC etc.)"
            code.startsWith("B") -> "Body code"
            code.startsWith("U") -> "Network/communication code"
            else -> "Unknown code"
        }
    }
}
