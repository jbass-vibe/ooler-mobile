package com.example.ooler.domain

import kotlinx.coroutines.flow.Flow

interface OolerRepository {
    val oolerState: Flow<OolerState>
    val schedule: Flow<OolerSchedule>
    val deviceSchedule: Flow<OolerSchedule>

    suspend fun connect()
    suspend fun disconnect()

    suspend fun setPower(on: Boolean)
    suspend fun setMode(mode: OolerMode)
    suspend fun setTemperature(fahrenheit: Int)
    suspend fun setCleaning(on: Boolean)
    suspend fun setDisplayUnit(unit: TemperatureUnit)

    suspend fun updateSchedule(schedule: OolerSchedule)
    suspend fun syncClock()
}
