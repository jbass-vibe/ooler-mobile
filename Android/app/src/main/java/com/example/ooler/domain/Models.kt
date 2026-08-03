package com.example.ooler.domain

import java.time.LocalDateTime

enum class OolerMode(val value: Int) {
    SILENT(0x00),
    REGULAR(0x01),
    BOOST(0x02);

    companion object {
        fun fromInt(value: Int) = entries.find { it.value == value } ?: REGULAR
    }
}

enum class TemperatureUnit(val value: Int) {
    FAHRENHEIT(0x00),
    CELSIUS(0x01);

    companion object {
        fun fromInt(value: Int) = entries.find { it.value == value } ?: FAHRENHEIT
    }
}

data class OolerState(
    val powerOn: Boolean = false,
    val mode: OolerMode = OolerMode.REGULAR,
    val setTemperatureF: Int = 72,
    val actualTemperature: Int = 72,
    val waterLevel: Int = 100,
    val cleaning: Boolean = false,
    val displayUnit: TemperatureUnit = TemperatureUnit.FAHRENHEIT,
    val ambientTemperatureF: Int = 72,
    val humidity: Int = 0,
    val isConnected: Boolean = false,
    val deviceTime: LocalDateTime? = null
)

data class ScheduleEvent(
    val minuteOfWeek: Int, // 0 (Mon 00:00) to 10079 (Sun 23:59)
    val temperatureF: Int // 0x00 for OFF, 1-120 for target, 0xFE for warm wake
) {
    val isOff: Boolean get() = temperatureF == 0x00
    val isWarmWake: Boolean get() = temperatureF == 0xFE
}

data class OolerSchedule(
    val events: List<ScheduleEvent> = emptyList(),
    val rows: List<ScheduleRow> = emptyList() // High-level UI groupings
)

data class ScheduleRow(
    val id: String = java.util.UUID.randomUUID().toString(),
    val days: Set<Int> = emptySet(), // 0=Mon, 6=Sun
    val onTimeMinutes: Int = 1320, // Default 10:00 PM
    val offTimeMinutes: Int = 420,  // Default 7:00 AM
    val temperatureF: Int = 68,
    val enabled: Boolean = true
)

object OolerConstants {
    const val TEMP_LO_F = 45
    const val TEMP_HI_F = 120
    const val TEMP_MIN_F = 55
    const val TEMP_MAX_F = 115

    const val WARM_WAKE_MARKER = 0xFE
    const val OFF_MARKER = 0x00
    const val UNUSED_MARKER = 0xFF

    const val MAX_SCHEDULE_EVENTS = 70
    const val MINUTES_IN_WEEK = 10080
}
