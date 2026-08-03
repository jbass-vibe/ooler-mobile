package com.example.ooler.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ooler.data.local.AppDatabase
import com.example.ooler.data.local.ScheduleEntity
import com.example.ooler.domain.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OolerViewModel @Inject constructor(
    private val repository: OolerRepository,
    private val database: AppDatabase
) : ViewModel() {

    private val _hasPendingChanges = MutableStateFlow(false)
    val hasPendingChanges: StateFlow<Boolean> = _hasPendingChanges.asStateFlow()

    private val _isOutOfSync = MutableStateFlow(false)
    val isOutOfSync: StateFlow<Boolean> = _isOutOfSync.asStateFlow()

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

    init {
        loadSavedSchedule()
        observeDeviceSync()
    }

    private fun observeDeviceSync() {
        combine(schedule, repository.deviceSchedule) { local, device ->
            if (device.events.isEmpty()) return@combine false
            val localEvents = convertRowsToEvents(local.rows)
            !areSchedulesEquivalent(localEvents, device.events)
        }.onEach { outOfSync ->
            _isOutOfSync.value = outOfSync
        }.launchIn(viewModelScope)
    }

    private fun areSchedulesEquivalent(local: List<ScheduleEvent>, device: List<ScheduleEvent>): Boolean {
        if (local.size != device.size) return false
        return local.zip(device).all { (l, d) ->
            l.minuteOfWeek == d.minuteOfWeek && l.temperatureF == d.temperatureF
        }
    }

    fun onScheduleChanged() {
        _hasPendingChanges.value = true
    }

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
                _hasPendingChanges.value = false
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
                row.steps.forEach { step ->
                    val totalMinutes = (dayIndex * 1440) + step.timeMinutes
                    events.add(ScheduleEvent(totalMinutes % OolerConstants.MINUTES_IN_WEEK, step.temperatureF))
                }
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
