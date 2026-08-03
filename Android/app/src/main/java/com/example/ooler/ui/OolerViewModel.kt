package com.example.ooler.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ooler.data.local.AppDatabase
import com.example.ooler.data.local.ScheduleEntity
import com.example.ooler.domain.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OolerViewModel @Inject constructor(
    private val repository: OolerRepository,
    private val database: AppDatabase
) : ViewModel() {

    init {
        loadSavedSchedule()
    }

    val oolerState: StateFlow<OolerState> = repository.oolerState
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = OolerState()
        )

    val schedule: StateFlow<OolerSchedule> = repository.schedule
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = OolerSchedule()
        )

    fun togglePower() {
        viewModelScope.launch {
            repository.setPower(!oolerState.value.powerOn)
        }
    }

    fun setMode(mode: OolerMode) {
        viewModelScope.launch {
            repository.setMode(mode)
        }
    }

    fun setTemperature(temp: Int) {
        viewModelScope.launch {
            val fValue = if (oolerState.value.displayUnit == TemperatureUnit.CELSIUS) {
                celsiusToFahrenheit(temp)
            } else {
                temp
            }
            repository.setTemperature(fValue)
        }
    }

    fun connect() {
        viewModelScope.launch {
            repository.connect()
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            repository.disconnect()
        }
    }

    fun saveSchedule(newSchedule: OolerSchedule) {
        viewModelScope.launch {
            // Compile rows into events before saving
            val compiledEvents = convertRowsToEvents(newSchedule.rows)
            val updatedSchedule = newSchedule.copy(events = compiledEvents)
            
            database.scheduleDao().saveSchedule(ScheduleEntity(schedule = updatedSchedule))
            try {
                repository.updateSchedule(updatedSchedule)
            } catch (e: Exception) {
                // Log or handle sync failure gracefully
                e.printStackTrace()
            }
        }
    }

    private fun convertRowsToEvents(rows: List<ScheduleRow>): List<ScheduleEvent> {
        val events = mutableListOf<ScheduleEvent>()
        rows.filter { it.enabled }.forEach { row ->
            row.days.forEach { dayIndex ->
                // Start Event
                val startMinute = (dayIndex * 1440) + row.onTimeMinutes
                events.add(ScheduleEvent(startMinute, row.temperatureF))
                
                // End Event
                var endMinute = (dayIndex * 1440) + row.offTimeMinutes
                if (row.offTimeMinutes <= row.onTimeMinutes) {
                    // Wraps to next day
                    endMinute += 1440
                }
                events.add(ScheduleEvent(endMinute % OolerConstants.MINUTES_IN_WEEK, OolerConstants.OFF_MARKER))
            }
        }
        // Deduplicate events at the same minute, taking the one with non-zero temp if possible
        return events.groupBy { it.minuteOfWeek }
            .map { (_, grp) -> grp.maxBy { it.temperatureF } }
            .sortedBy { it.minuteOfWeek }
    }

    fun loadSavedSchedule() {
        viewModelScope.launch {
            database.scheduleDao().getSchedule().firstOrNull()?.let { entity ->
                if (entity.schedule.rows.isNotEmpty()) {
                    repository.updateSchedule(entity.schedule)
                }
            }
        }
    }

    /**
     * Converts a Fahrenheit temperature to the user's preferred unit (Celsius or Fahrenheit).
     */
    fun getTemperatureInPreferredUnit(fahrenheit: Int): Int {
        return if (oolerState.value.displayUnit == TemperatureUnit.CELSIUS) {
            fahrenheitToCelsius(fahrenheit)
        } else {
            fahrenheit
        }
    }

    private fun fahrenheitToCelsius(f: Int): Int = ((f - 32) * 5.0 / 9.0).toInt()
    private fun celsiusToFahrenheit(c: Int): Int = (c * 9.0 / 5.0 + 32).toInt()
}
