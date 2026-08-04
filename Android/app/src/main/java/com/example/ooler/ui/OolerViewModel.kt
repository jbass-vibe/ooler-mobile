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
import android.util.Log

@HiltViewModel
class OolerViewModel @Inject constructor(
    private val repository: OolerRepository,
    private val database: AppDatabase
) : ViewModel() {

    private val _hasPendingChanges = MutableStateFlow(false)
    val hasPendingChanges: StateFlow<Boolean> = _hasPendingChanges.asStateFlow()

    private val _isOutOfSync = MutableStateFlow(false)
    val isOutOfSync: StateFlow<Boolean> = _isOutOfSync.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _draftRows = MutableStateFlow<List<ScheduleRow>>(emptyList())
    val draftRows: StateFlow<List<ScheduleRow>> = _draftRows.asStateFlow()

    private val _errorMessage = MutableSharedFlow<String>()
    val errorMessage: SharedFlow<String> = _errorMessage.asSharedFlow()

    val oolerState: StateFlow<OolerState> = repository.oolerState
        .stateIn(scope = viewModelScope, started = SharingStarted.WhileSubscribed(5000), initialValue = OolerState())

    val schedule: StateFlow<OolerSchedule> = repository.schedule
        .stateIn(scope = viewModelScope, started = SharingStarted.WhileSubscribed(5000), initialValue = OolerSchedule())

    init {
        loadSavedSchedule()
        observeDeviceSync()
        
        // Sync draft with persisted schedule once on load
        schedule.onEach { 
            if (_draftRows.value.isEmpty() && it.rows.isNotEmpty()) {
                _draftRows.value = it.rows
            }
        }.launchIn(viewModelScope)
    }

    private fun observeDeviceSync() {
        combine(schedule, repository.deviceSchedule, isSyncing) { local, device, syncing ->
            if (syncing || device.events.isEmpty()) return@combine false
            val localEvents = convertRowsToEvents(local.rows)
            !areSchedulesEquivalent(localEvents, device.events)
        }.onEach { outOfSync ->
            _isOutOfSync.value = outOfSync
        }.launchIn(viewModelScope)
    }

    private fun areSchedulesEquivalent(local: List<ScheduleEvent>, device: List<ScheduleEvent>): Boolean {
        val localSet = local.filter { it.temperatureF != 0xFF }.toSet()
        val deviceSet = device.filter { it.temperatureF != 0xFF }.toSet()
        return localSet == deviceSet
    }

    fun onScheduleChanged(updatedRows: List<ScheduleRow>) {
        _draftRows.value = updatedRows
        _hasPendingChanges.value = true
    }

    fun togglePower() { viewModelScope.launch { repository.setPower(!oolerState.value.powerOn) } }
    fun setMode(mode: OolerMode) { viewModelScope.launch { repository.setMode(mode) } }
    fun setTemperature(temp: Int) {
        viewModelScope.launch {
            val fValue = if (oolerState.value.displayUnit == TemperatureUnit.CELSIUS) celsiusToFahrenheit(temp) else temp
            repository.setTemperature(fValue)
        }
    }

    fun connect() {
        viewModelScope.launch {
            try { repository.connect() } catch (e: Exception) { _errorMessage.emit("Connection failed") }
        }
    }

    fun disconnect() { viewModelScope.launch { repository.disconnect() } }
    
    fun startPolling() = repository.startPolling()
    fun stopPolling() = repository.stopPolling()

    fun readScheduleFromHardware() {
        viewModelScope.launch {
            _isSyncing.value = true
            try {
                repository.readSchedule()
            } catch (e: Exception) {
                _errorMessage.emit("Failed to read device schedule")
            } finally {
                _isSyncing.value = false
            }
        }
    }

    override fun onCleared() { super.onCleared(); repository.forceDisconnect() }

    fun saveSchedule() {
        viewModelScope.launch {
            _isSyncing.value = true
            val rowsToSave = _draftRows.value
            val compiledEvents = convertRowsToEvents(rowsToSave)
            val updatedSchedule = OolerSchedule(events = compiledEvents, rows = rowsToSave)
            
            database.scheduleDao().saveSchedule(ScheduleEntity(schedule = updatedSchedule))
            
            try {
                repository.updateSchedule(updatedSchedule)
                _hasPendingChanges.value = false
            } catch (e: Exception) {
                _errorMessage.emit("Sync failed")
            } finally {
                _isSyncing.value = false
            }
        }
    }

    fun clearDatabase() {
        viewModelScope.launch {
            val emptySchedule = OolerSchedule()
            database.scheduleDao().saveSchedule(ScheduleEntity(schedule = emptySchedule))
            repository.restoreLocalSchedule(emptySchedule)
            _draftRows.value = emptyList()
            _hasPendingChanges.value = false
        }
    }

    private fun convertRowsToEvents(rows: List<ScheduleRow>): List<ScheduleEvent> {
        val events = mutableListOf<ScheduleEvent>()
        rows.filter { it.enabled }.forEach { row ->
            if (row.steps.isEmpty()) return@forEach
            row.days.forEach { dayIndex ->
                var currentDay = dayIndex
                val steps = row.steps
                steps.forEachIndexed { i, step ->
                    if (i > 0 && steps[i].timeMinutes < steps[i-1].timeMinutes) {
                        currentDay = (currentDay + 1) % 7
                    }
                    val minuteOfWeek = (currentDay * 1440 + step.timeMinutes) % OolerConstants.MINUTES_IN_WEEK
                    events.add(ScheduleEvent(minuteOfWeek, step.temperatureF))
                }
            }
        }
        return events.distinctBy { it.minuteOfWeek }.sortedBy { it.minuteOfWeek }
    }

    fun loadSavedSchedule() {
        viewModelScope.launch {
            database.scheduleDao().getSchedule().firstOrNull()?.let { entity ->
                if (entity.schedule.rows.isNotEmpty()) {
                    repository.restoreLocalSchedule(entity.schedule)
                }
            }
        }
    }

    fun getTemperatureInPreferredUnit(fahrenheit: Int): Int {
        return if (oolerState.value.displayUnit == TemperatureUnit.CELSIUS) fahrenheitToCelsius(fahrenheit) else fahrenheit
    }
    private fun fahrenheitToCelsius(f: Int): Int = ((f - 32) * 5.0 / 9.0).toInt()
    private fun celsiusToFahrenheit(c: Int): Int = (c * 9.0 / 5.0 + 32).toInt()
}
