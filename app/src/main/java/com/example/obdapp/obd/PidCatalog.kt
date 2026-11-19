package com.example.obdapp.obd

import com.github.eltonvs.obd.command.ObdCommand
import com.github.eltonvs.obd.command.control.ModuleVoltageCommand
import com.github.eltonvs.obd.command.control.TimingAdvanceCommand
import com.github.eltonvs.obd.command.engine.AbsoluteLoadCommand
import com.github.eltonvs.obd.command.engine.LoadCommand
import com.github.eltonvs.obd.command.engine.MassAirFlowCommand
import com.github.eltonvs.obd.command.engine.RPMCommand
import com.github.eltonvs.obd.command.engine.RelativeThrottlePositionCommand
import com.github.eltonvs.obd.command.engine.RuntimeCommand
import com.github.eltonvs.obd.command.engine.SpeedCommand
import com.github.eltonvs.obd.command.engine.ThrottlePositionCommand
import com.github.eltonvs.obd.command.fuel.FuelConsumptionRateCommand
import com.github.eltonvs.obd.command.fuel.FuelLevelCommand
import com.github.eltonvs.obd.command.fuel.FuelTrimCommand
import com.github.eltonvs.obd.command.pressure.BarometricPressureCommand
import com.github.eltonvs.obd.command.pressure.FuelPressureCommand
import com.github.eltonvs.obd.command.pressure.FuelRailGaugePressureCommand
import com.github.eltonvs.obd.command.pressure.IntakeManifoldPressureCommand
import com.github.eltonvs.obd.command.temperature.AirIntakeTemperatureCommand
import com.github.eltonvs.obd.command.temperature.AmbientAirTemperatureCommand
import com.github.eltonvs.obd.command.temperature.EngineCoolantTemperatureCommand
import com.github.eltonvs.obd.command.temperature.OilTemperatureCommand

/** One live-data PID we know how to poll and display. */
data class PidDef(
    val key: String,          // Mode 01 PID in hex, e.g. "0C"
    val label: String,
    val unit: String,
    val chartable: Boolean = true,
    val commandFactory: () -> ObdCommand
)

/** One polled reading. [value] is null for non-numeric responses (e.g. runtime hh:mm:ss). */
data class PidSample(
    val pidKey: String,
    val label: String,
    val value: Float?,
    val display: String,
    val unit: String,
    val timestamp: Long = System.currentTimeMillis()
)

object PidCatalog {

    val all: List<PidDef> = listOf(
        PidDef("04", "Engine load", "%") { LoadCommand() },
        PidDef("05", "Coolant temp", "°C") { EngineCoolantTemperatureCommand() },
        PidDef("06", "ST fuel trim B1", "%") { FuelTrimCommand(FuelTrimCommand.FuelTrimBank.SHORT_TERM_BANK_1) },
        PidDef("08", "LT fuel trim B1", "%") { FuelTrimCommand(FuelTrimCommand.FuelTrimBank.LONG_TERM_BANK_1) },
        PidDef("0A", "Fuel pressure", "kPa") { FuelPressureCommand() },
        PidDef("0B", "Intake manifold", "kPa") { IntakeManifoldPressureCommand() },
        PidDef("0C", "Engine RPM", "rpm") { RPMCommand() },
        PidDef("0D", "Speed", "km/h") { SpeedCommand() },
        PidDef("0E", "Timing advance", "°") { TimingAdvanceCommand() },
        PidDef("0F", "Intake air temp", "°C") { AirIntakeTemperatureCommand() },
        PidDef("10", "MAF", "g/s") { MassAirFlowCommand() },
        PidDef("11", "Throttle", "%") { ThrottlePositionCommand() },
        PidDef("1F", "Engine runtime", "", chartable = false) { RuntimeCommand() },
        PidDef("23", "Fuel rail gauge", "kPa") { FuelRailGaugePressureCommand() },
        PidDef("2F", "Fuel level", "%") { FuelLevelCommand() },
        PidDef("33", "Barometric", "kPa") { BarometricPressureCommand() },
        PidDef("42", "Module voltage", "V") { ModuleVoltageCommand() },
        PidDef("43", "Absolute load", "%") { AbsoluteLoadCommand() },
        PidDef("45", "Relative throttle", "%") { RelativeThrottlePositionCommand() },
        PidDef("46", "Ambient temp", "°C") { AmbientAirTemperatureCommand() },
        PidDef("5C", "Oil temp", "°C") { OilTemperatureCommand() },
        PidDef("5E", "Fuel rate", "L/h") { FuelConsumptionRateCommand() }
    )

    private val byKey: Map<String, PidDef> = all.associateBy { it.key }

    fun get(key: String): PidDef? = byKey[key]

    /** Sensible defaults for a Mazda 3 BL — filtered against supported PIDs after discovery. */
    val defaultSelection = listOf("0C", "0D", "05", "11")

    /**
     * kotlin-obd-api formats some values with the JVM default locale
     * ("%.2f".format(...)), so on comma-decimal locales we may get "12,34".
     */
    fun parseNumeric(raw: String): Float? =
        raw.trim().replace(',', '.').toFloatOrNull()
}
