package com.example.ooler.data

import com.example.ooler.domain.*
import com.juul.kable.*
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.annotation.SuppressLint
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID

class KableOolerRepository(
    private val scope: CoroutineScope
) : OolerRepository {

    private val _oolerState = MutableStateFlow(OolerState())
    override val oolerState: Flow<OolerState> = _oolerState.asStateFlow()

    private val _schedule = MutableStateFlow(OolerSchedule())
    override val schedule: Flow<OolerSchedule> = _schedule.asStateFlow()

    private val _deviceSchedule = MutableStateFlow(OolerSchedule())
    override val deviceSchedule: Flow<OolerSchedule> = _deviceSchedule.asStateFlow()

    private var peripheral: Peripheral? = null
    private var scanner = Scanner { }
    private var scheduleSequence: Int = 0

    private val connectionMutex = Mutex()
    private var pollJob: Job? = null
    private var stateJob: Job? = null
    private var isPollingEnabled = false

    override suspend fun connect() {
        connectionMutex.withLock {
            if (peripheral?.state?.first() is State.Connected) return
            
            try {
                val advertisement = scanner.advertisements.first { it.name?.contains("OOLER") == true }
                val p = scope.peripheral(advertisement)
                
                // connect() suspends until services are discovered in Kable
                p.connect()
                
                // Only after successful connection and discovery, set the peripheral
                peripheral = p
                
                _oolerState.update { it.copy(isConnected = true) }
                syncClock()
                
                val unitByte = p.read(OolerUuids.MAIN_CONTROL_SERVICE, OolerUuids.DISPLAY_UNIT)
                _oolerState.update { it.copy(displayUnit = TemperatureUnit.fromInt(unitByte[0].toInt())) }
                
                pollAll()
                subscribeToNotifications(p)

                stateJob?.cancel()
                stateJob = scope.launch {
                    p.state.collect { state ->
                        _oolerState.update { it.copy(isConnected = state is State.Connected) }
                    }
                }

                if (isPollingEnabled) startPollingJob()
            } catch (e: Exception) {
                Log.e("OolerRepo", "Connection failed", e)
                _oolerState.update { it.copy(isConnected = false) }
                throw e
            }
        }
    }

    override suspend fun disconnect() {
        connectionMutex.withLock {
            stopPolling()
            stateJob?.cancel()
            stateJob = null
            peripheral?.disconnect()
            peripheral = null
            _oolerState.update { it.copy(isConnected = false) }
        }
    }
    override fun forceDisconnect() { scope.launch { disconnect() } }

    override fun startPolling() {
        if (isPollingEnabled) return
        isPollingEnabled = true
        startPollingJob()
    }

    override fun stopPolling() {
        isPollingEnabled = false
        pollJob?.cancel()
        pollJob = null
    }

    private fun startPollingJob() {
        pollJob?.cancel()
        pollJob = scope.launch {
            while (isActive && isPollingEnabled) {
                pollAll()
                delay(10000)
            }
        }
    }

    @SuppressLint("NewApi")
    private suspend fun pollAll() {
        val p = peripheral ?: return
        if (p.state.first() !is State.Connected) return
        
        try {
            val power = p.read(OolerUuids.MAIN_CONTROL_SERVICE, OolerUuids.POWER)[0].toInt() != 0
            val mode = OolerMode.fromInt(p.read(OolerUuids.MAIN_CONTROL_SERVICE, OolerUuids.MODE)[0].toInt())
            val setTempF = p.read(OolerUuids.MAIN_CONTROL_SERVICE, OolerUuids.SET_TEMP_F)[0].toInt() and 0xFF
            
            // Actual temp is unit-dependent per spec §1.2
            val displayUnit = _oolerState.value.displayUnit
            val actualRaw = p.read(OolerUuids.MAIN_CONTROL_SERVICE, OolerUuids.ACTUAL_TEMP)[0].toInt() and 0xFF
            val actualTempF = if (displayUnit == TemperatureUnit.CELSIUS) {
                (actualRaw * 9.0 / 5.0 + 32).toInt()
            } else {
                actualRaw
            }

            val water = p.read(OolerUuids.MAIN_CONTROL_SERVICE, OolerUuids.WATER_LEVEL)[0].toInt() and 0xFF
            val humidity = p.read(OolerUuids.MAIN_CONTROL_SERVICE, OolerUuids.RELATIVE_HUMIDITY)[0].toInt() and 0xFF
            val ambientTempF = p.read(OolerUuids.MAIN_CONTROL_SERVICE, OolerUuids.AMBIENT_TEMP_F)[0].toInt() and 0xFF
            
            val timeBytes = p.read(OolerUuids.CURRENT_TIME_SERVICE, OolerUuids.CURRENT_TIME)
            val timeBuffer = ByteBuffer.wrap(timeBytes).order(ByteOrder.LITTLE_ENDIAN)
            val year = timeBuffer.short.toInt() and 0xFFFF
            val month = timeBytes[2].toInt() and 0xFF
            val day = timeBytes[3].toInt() and 0xFF
            val hour = timeBytes[4].toInt() and 0xFF
            val minute = timeBytes[5].toInt() and 0xFF
            val second = timeBytes[6].toInt() and 0xFF
            val deviceTime = try {
                LocalDateTime.of(year, month, day, hour, minute, second)
            } catch (e: Exception) {
                null
            }

            _oolerState.update { it.copy(
                powerOn = power, 
                mode = mode, 
                setTemperatureF = setTempF, 
                actualTemperature = actualTempF, 
                waterLevel = water,
                humidity = humidity,
                ambientTemperatureF = ambientTempF,
                deviceTime = deviceTime
            ) }
        } catch (e: Exception) {
            Log.e("OolerRepo", "Poll failed", e)
            if (e is IllegalStateException && e.message?.contains("Services have not been discovered") == true) {
                // This shouldn't happen with the new connect() logic, but if it does, we should probably disconnect
                forceDisconnect()
            }
        }
    }

    override suspend fun readSchedule() {
        val p = peripheral ?: return
        Log.d("OolerRepo", "Reading schedule from hardware...")
        val timesBytes = p.read(OolerUuids.SLEEP_SCHEDULE_SERVICE, OolerUuids.SCHEDULE_TIMES)
        val tempsBytes = p.read(OolerUuids.SLEEP_SCHEDULE_SERVICE, OolerUuids.SCHEDULE_TEMPS)
        val headerBytes = p.read(OolerUuids.SLEEP_SCHEDULE_SERVICE, OolerUuids.SCHEDULE_HEADER)
        
        Log.d("OolerRepo", "Schedule bytes read - Header: ${headerBytes.joinToString("") { "%02x".format(it) }}")
        
        // EVERYTHING IS LITTLE ENDIAN
        scheduleSequence = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        val events = mutableListOf<ScheduleEvent>()
        val buffer = ByteBuffer.wrap(timesBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 70) {
            val minute = buffer.short.toInt() and 0xFFFF
            val temp = tempsBytes[i].toInt() and 0xFF
            if (temp == 0xFF) break
            events.add(ScheduleEvent(minute, temp))
        }
        _deviceSchedule.value = OolerSchedule(events)
    }

    private fun subscribeToNotifications(p: Peripheral) {
        scope.launch { p.observe(OolerUuids.MAIN_CONTROL_SERVICE, OolerUuids.POWER).collect { d -> _oolerState.update { it.copy(powerOn = d[0].toInt() != 0) } } }
        scope.launch { p.observe(OolerUuids.MAIN_CONTROL_SERVICE, OolerUuids.MODE).collect { d -> _oolerState.update { it.copy(mode = OolerMode.fromInt(d[0].toInt())) } } }
    }

    override suspend fun setPower(on: Boolean) { writeWithRetry(OolerUuids.MAIN_CONTROL_SERVICE, OolerUuids.POWER, byteArrayOf(if (on) 1 else 0)); _oolerState.update { it.copy(powerOn = on) } }
    override suspend fun setMode(mode: OolerMode) { writeWithRetry(OolerUuids.MAIN_CONTROL_SERVICE, OolerUuids.MODE, byteArrayOf(mode.value.toByte())); _oolerState.update { it.copy(mode = mode) } }
    override suspend fun setTemperature(f: Int) { val c = f.coerceIn(OolerConstants.TEMP_LO_F, OolerConstants.TEMP_HI_F); writeWithRetry(OolerUuids.MAIN_CONTROL_SERVICE, OolerUuids.SET_TEMP_F, byteArrayOf(c.toByte())); _oolerState.update { it.copy(setTemperatureF = c) } }
    override suspend fun setCleaning(on: Boolean) { }
    override suspend fun setDisplayUnit(unit: TemperatureUnit) { }

    override suspend fun updateSchedule(schedule: OolerSchedule) {
        val p = peripheral ?: return
        if (p.state.first() !is State.Connected) return

        // Per protocol spec §6.1, Times are padded with 0x00, Temps with 0xFF
        val times = ByteArray(140) { 0x00.toByte() }
        val temps = ByteArray(70) { 0xFF.toByte() }

        schedule.events.take(70).forEachIndexed { i, event ->
            val minute = event.minuteOfWeek.toShort()
            // Per protocol spec §6.1, the device swaps bytes of uint16 on write.
            // Using BIG_ENDIAN here effectively "pre-swaps" the bytes so the device stores LE.
            ByteBuffer.wrap(times, i * 2, 2).order(ByteOrder.BIG_ENDIAN).putShort(minute)
            temps[i] = event.temperatureF.toByte()
        }

        // Increment sequence and write header per §6.2
        scheduleSequence = (scheduleSequence + 1) % 0xFFFF
        val header = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(scheduleSequence.toShort()).array()
        
        try {
            Log.d("OolerRepo", "Writing schedule to hardware. Sequence: $scheduleSequence")
            // Recommended write order: Times -> Temps -> Header (Commit)
            writeWithRetry(OolerUuids.SLEEP_SCHEDULE_SERVICE, OolerUuids.SCHEDULE_TIMES, times)
            delay(50)
            writeWithRetry(OolerUuids.SLEEP_SCHEDULE_SERVICE, OolerUuids.SCHEDULE_TEMPS, temps)
            delay(50)
            writeWithRetry(OolerUuids.SLEEP_SCHEDULE_SERVICE, OolerUuids.SCHEDULE_HEADER, header)
            Log.d("OolerRepo", "Schedule write complete")
            delay(200) 
            readSchedule()
            _schedule.value = schedule
        } catch (e: Exception) {
            Log.e("OolerRepo", "Schedule update failed", e)
            throw e
        }
    }

    override fun restoreLocalSchedule(schedule: OolerSchedule) { _schedule.value = schedule }

    override suspend fun syncClock() {
        val now = LocalDateTime.now()
        val zone = ZoneId.systemDefault()
        val zonedDateTime = now.atZone(zone)
        val isDst = zone.rules.isDaylightSavings(zonedDateTime.toInstant())
        val currentTime = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN).putShort(now.year.toShort()).put(now.monthValue.toByte()).put(now.dayOfMonth.toByte()).put(now.hour.toByte()).put(now.minute.toByte()).put(now.second.toByte()).put(now.dayOfWeek.value.toByte()).put(0.toByte()).put(1.toByte()).array()
        writeWithRetry(OolerUuids.CURRENT_TIME_SERVICE, OolerUuids.CURRENT_TIME, currentTime)
        writeWithRetry(OolerUuids.CURRENT_TIME_SERVICE, OolerUuids.LOCAL_TIME_INFO, byteArrayOf((zonedDateTime.offset.totalSeconds / 900).toByte(), if (isDst) 4 else 0))
    }

    private suspend fun writeWithRetry(service: UUID, characteristic: UUID, data: ByteArray) {
        val p = peripheral ?: throw IllegalStateException("Not connected")
        try { p.write(service, characteristic, data, WriteType.WithResponse) }
        catch (e: Exception) { 
            delay(100)
            p.write(service, characteristic, data, WriteType.WithResponse) 
        }
    }

    private fun Characteristic(service: UUID, characteristic: UUID) = characteristicOf(service.toString(), characteristic.toString())
    private suspend fun Peripheral.read(s: UUID, c: UUID): ByteArray = read(Characteristic(s, c))
    private suspend fun Peripheral.write(s: UUID, c: UUID, d: ByteArray, t: WriteType) = write(Characteristic(s, c), d, t)
    private fun Peripheral.observe(s: UUID, c: UUID): Flow<ByteArray> = observe(Characteristic(s, c))
}
