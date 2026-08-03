package com.example.ooler.data

import android.annotation.SuppressLint
import com.example.ooler.domain.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

@SuppressLint("NewApi")
class MockOolerRepository : OolerRepository {
    private val _oolerState = MutableStateFlow(
        OolerState(
            isConnected = true,
            powerOn = true,
            mode = OolerMode.REGULAR,
            setTemperatureF = 68,
            actualTemperature = 70,
            waterLevel = 85,
            humidity = 45,
            ambientTemperatureF = 72,
            deviceTime = java.time.LocalDateTime.now()
        )
    )
    override val oolerState: Flow<OolerState> = _oolerState.asStateFlow()

    private val _schedule = MutableStateFlow(
        OolerSchedule(
            rows = listOf(
                ScheduleRow(
                    days = setOf(0, 1, 2, 3, 4), // Mon-Fri
                    steps = listOf(
                        ScheduleStep(timeMinutes = 22 * 60, temperatureF = 64), // 10 PM: Sleep
                        ScheduleStep(timeMinutes = 2 * 60, temperatureF = 68),  // 2 AM: Deep sleep adjustment
                        ScheduleStep(timeMinutes = 6 * 60 + 30, temperatureF = 110), // 6:30 AM: Warm Wake
                        ScheduleStep(timeMinutes = 7 * 60, temperatureF = 0)    // 7 AM: OFF
                    )
                ),
                ScheduleRow(
                    days = setOf(5, 6), // Sat-Sun
                    steps = listOf(
                        ScheduleStep(timeMinutes = 23 * 60, temperatureF = 68), // 11 PM
                        ScheduleStep(timeMinutes = 8 * 60, temperatureF = 0)    // 8 AM
                    )
                )
            )
        )
    )
    override val schedule: Flow<OolerSchedule> = _schedule.asStateFlow()

    private val _deviceSchedule = MutableStateFlow(OolerSchedule())
    override val deviceSchedule: Flow<OolerSchedule> = _deviceSchedule.asStateFlow()

    override suspend fun connect() {
        delay(500)
        _oolerState.update { it.copy(isConnected = true) }
    }

    override suspend fun disconnect() {
        _oolerState.update { it.copy(isConnected = false) }
    }

    override suspend fun setPower(on: Boolean) {
        _oolerState.update { it.copy(powerOn = on) }
    }

    override suspend fun setMode(mode: OolerMode) {
        if (_oolerState.value.powerOn) {
            _oolerState.update { it.copy(mode = mode) }
        }
    }

    override suspend fun setTemperature(fahrenheit: Int) {
        if (_oolerState.value.powerOn) {
            _oolerState.update { it.copy(setTemperatureF = fahrenheit) }
        }
    }

    override suspend fun setCleaning(on: Boolean) {
        if (_oolerState.value.powerOn) {
            _oolerState.update { it.copy(cleaning = on) }
        }
    }

    override suspend fun setDisplayUnit(unit: TemperatureUnit) {
        _oolerState.update { it.copy(displayUnit = unit) }
    }

    override suspend fun updateSchedule(schedule: OolerSchedule) {
        _schedule.value = schedule
    }

    override suspend fun syncClock() {
        // No-op in mock
    }
}
