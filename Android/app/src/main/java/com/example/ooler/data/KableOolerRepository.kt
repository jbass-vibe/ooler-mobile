package com.example.ooler.data

import android.annotation.SuppressLint
import android.util.Log
import com.example.ooler.domain.*
import com.juul.kable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
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
    private val scanner = Scanner { }
    private var scheduleSequence: Int = 0

    private val connectionMutex = Mutex()
    private var pollJob: Job? = null
    private var stateJob: Job? = null
    private val activeObservers = MutableStateFlow(0)

    override suspend fun connect() {
        connectionMutex.withLock {
            if (peripheral?.state?.first() is State.Connected) {
                Log.d("OolerRepo", "Already connected")
                return
            }
            
            try {
                Log.d("OolerRepo", "Starting scan for OOLER...")
                val advertisement = withTimeoutOrNull(10000) {
                    scanner.advertisements.first { it.name?.contains("OOLER") == true }
                } ?: throw Exception("OOLER device not found in 10s")
                
                Log.d("OolerRepo", "Device found: ${advertisement.name}. Connecting...")
                val p = scope.peripheral(advertisement)
                
                // connect() suspends until services are discovered in Kable
                p.connect()
                Log.d("OolerRepo", "Connected and services discovered")
                
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

                // Restart polling if we have observers after a reconnection
                if (activeObservers.value > 0) {
                    startPollingJob()
                }
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

    override fun forceDisconnect() {
        scope.launch { disconnect() }
    }

    override fun startPolling() {
        activeObservers.update { it + 1 }
        startPollingJob()
    }

    override fun stopPolling() {
        activeObservers.update { (it - 1).coerceAtLeast(0) }
    }

    private fun startPollingJob() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                if (activeObservers.value <= 0) break
                
                if (peripheral?.state?.first() is State.Connected) {
                    pollAll()
                } else {
                    Log.d("OolerRepo", "Polling skipped: Not connected")
                }
                delay(10000)
            }
            pollJob = null
        }
    }

    @SuppressLint("NewApi")
    override suspend fun pollAll() {
        val p = peripheral ?: return
        if (p.state.first() !is State.Connected) return
        
        try {
            val powerByte = p.read(OolerUuids.MAIN_CONTROL_SERVICE, OolerUuids.POWER)[0]
            val power = powerByte.toInt() != 0
            
            val modeByte = p.read(OolerUuids.MAIN_CONTROL_SERVICE, OolerUuids.MODE)[0]
            val mode = OolerMode.fromInt(modeByte.toInt())
            
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
            val dayOfWeek = timeBytes[7].toInt() and 0xFF
            
            val deviceTime = try {
                LocalDateTime.of(year, month, day, hour, minute, second)
            } catch (e: Exception) {
                null
            }

            Log.d("OolerRepo", "Telemetry - Power: $power, Mode: $mode, Set: $setTempF°F, Actual: $actualTempF°F (Raw: $actualRaw), Water: $water%, Humidity: $humidity%, Ambient: $ambientTempF°F, DeviceTime: $deviceTime (DoW: $dayOfWeek)")

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
            if (e !is CancellationException) {
                Log.e("OolerRepo", "Poll failed", e)
                if (e is IllegalStateException && e.message?.contains("Services have not been discovered") == true) {
                    forceDisconnect()
                }
            }
        }
    }

    override suspend fun readSchedule() {
        val p = peripheral ?: return
        Log.d("OolerRepo", "Reading schedule from hardware...")
        val timesBytes = p.read(OolerUuids.SLEEP_SCHEDULE_SERVICE, OolerUuids.SCHEDULE_TIMES)
        val tempsBytes = p.read(OolerUuids.SLEEP_SCHEDULE_SERVICE, OolerUuids.SCHEDULE_TEMPS)
        val headerBytes = p.read(OolerUuids.SLEEP_SCHEDULE_SERVICE, OolerUuids.SCHEDULE_HEADER)
        
        val sequence = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
        scheduleSequence = sequence
        Log.d("OolerRepo", "Schedule read - Header Raw: ${headerBytes.toHex()}, Sequence: $sequence")
        
        val events = mutableListOf<ScheduleEvent>()
        val buffer = ByteBuffer.wrap(timesBytes).order(ByteOrder.LITTLE_ENDIAN)
        
        for (i in 0 until 70) {
            val minute = buffer.short.toInt() and 0xFFFF
            val temp = tempsBytes[i].toInt() and 0xFF
            if (temp == 0xFF) break
            
            events.add(ScheduleEvent(minute, temp))
            Log.d("OolerRepo", "  Event $i: ${formatMinuteOfWeek(minute)}, Temp: $temp°F (Raw Minute: ${"%04x".format(minute)}, Temp: ${"%02x".format(temp)})")
        }
        
        Log.d("OolerRepo", "Schedule read complete. Total events: ${events.size}")
        _deviceSchedule.value = OolerSchedule(events)
    }

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
            Log.d("OolerRepo", "Schedule write - Header Raw (Swapped): ${header.toHex()}")
            
            schedule.events.take(70).forEachIndexed { i, event ->
                Log.d("OolerRepo", "  Writing Event $i: ${formatMinuteOfWeek(event.minuteOfWeek)}, Temp: ${event.temperatureF}°F")
            }

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

    override suspend fun runSniffer() {
        val p = peripheral ?: return
        Log.d("OolerSniffer", "=== STARTING FULL PROTOCOL SCAN ===")

        val targets = mapOf(
            "Schedule Enable Candidate" to UUID.fromString("7aa73db1-1c2d-4c8c-9195-36c0a4b6acb2"),
            "Schedule Meta" to UUID.fromString("fa242bc0-bf85-41f7-8dbb-53ba2e8b08a3"),
            "Unknown (F30D)" to UUID.fromString("f30d875a-7297-43ac-9f5b-1d7eed4446eb"),
            "Unknown (9234)" to UUID.fromString("923445f2-9438-4d81-98c9-904b69b94eca"),
            "Unknown (AF8D)" to UUID.fromString("af8d892b-693d-495d-ac95-eb849a5ac40c")
        )

        targets.forEach { (name, uuid) ->
            try {
                // Try Main Control Service
                val value = p.read(characteristicOf(OolerUuids.MAIN_CONTROL_SERVICE.toString(), uuid.toString()))
                Log.d("OolerSniffer", "$name ($uuid) on Main: ${value.toHex()}")
            } catch (e: Exception) {
                try {
                    // Try Sleep Schedule Service
                    val value = p.read(characteristicOf(OolerUuids.SLEEP_SCHEDULE_SERVICE.toString(), uuid.toString()))
                    Log.d("OolerSniffer", "$name ($uuid) on Schedule: ${value.toHex()}")
                } catch (e2: Exception) {
                    Log.d("OolerSniffer", "$name ($uuid): Read Failed (Not on Main or Schedule service)")
                }
            }
        }
        Log.d("OolerSniffer", "=== SCAN COMPLETE ===")
    }

    override suspend fun syncClock() {
        val zone = ZoneId.systemDefault()
        val zonedDateTime = ZonedDateTime.now(zone)
        val isDst = zone.rules.isDaylightSavings(zonedDateTime.toInstant())
        
        // Protocol Spec §5.1: Current Time payload (10 bytes).
        // Standard Bluetooth CTS: Mon=1, Tue=2, Wed=3, Thu=4, Fri=5, Sat=6, Sun=7.
        val standardDayOfWeek = zonedDateTime.dayOfWeek.value
        
        val currentTime = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(zonedDateTime.year.toShort())
            .put(zonedDateTime.monthValue.toByte())
            .put(zonedDateTime.dayOfMonth.toByte())
            .put(zonedDateTime.hour.toByte())
            .put(zonedDateTime.minute.toByte())
            .put(zonedDateTime.second.toByte())
            .put(standardDayOfWeek.toByte())
            .put(0.toByte()) // Fractions
            .put(1.toByte()) // Adjust reason: manual update
            .array()
        
        Log.d("OolerRepo", "Syncing clock (Standard CTS): $zonedDateTime (DoW: $standardDayOfWeek)")
        Log.d("OolerRepo", "Clock Raw: ${currentTime.toHex()}")
        
        writeWithRetry(OolerUuids.CURRENT_TIME_SERVICE, OolerUuids.CURRENT_TIME, currentTime)
        
        val dstSeconds = if (isDst) 3600 else 0
        val standardOffsetSeconds = zonedDateTime.offset.totalSeconds - dstSeconds
        val localTimeInfo = byteArrayOf((standardOffsetSeconds / 900).toByte(), if (isDst) 4 else 0)
        Log.d("OolerRepo", "LocalTimeInfo Raw: ${localTimeInfo.toHex()}")
        writeWithRetry(OolerUuids.CURRENT_TIME_SERVICE, OolerUuids.LOCAL_TIME_INFO, localTimeInfo)
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

    private suspend fun writeWithRetry(service: UUID, characteristic: UUID, data: ByteArray) {
        val p = peripheral ?: throw IllegalStateException("Not connected")
        try {
            p.write(Characteristic(service, characteristic), data, WriteType.WithResponse)
        } catch (e: Exception) {
            if (e is ConnectionLostException || e.message?.contains("not connected", ignoreCase = true) == true) {
                _oolerState.update { it.copy(isConnected = false) }
                peripheral = null
                throw e
            }
            delay(100)
            try {
                p.write(Characteristic(service, characteristic), data, WriteType.WithResponse)
            } catch (e2: Exception) {
                if (e2 is ConnectionLostException || e2.message?.contains("not connected", ignoreCase = true) == true) {
                    _oolerState.update { it.copy(isConnected = false) }
                    peripheral = null
                }
                throw e2
            }
        }
    }

    private fun Characteristic(service: UUID, characteristic: UUID) = characteristicOf(service.toString(), characteristic.toString())
    private suspend fun Peripheral.read(s: UUID, c: UUID): ByteArray = read(Characteristic(s, c))
    private suspend fun Peripheral.write(s: Characteristic, d: ByteArray, t: WriteType) = write(s, d, t)
    private fun Peripheral.observe(s: UUID, c: UUID): Flow<ByteArray> = observe(Characteristic(s, c))

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun formatMinuteOfWeek(minuteOfWeek: Int): String {
        // Hardware Schedule: Day 0 is Sunday
        val days = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
        val dayIndex = (minuteOfWeek / 1440) % 7
        val minuteOfDay = minuteOfWeek % 1440
        val hour = minuteOfDay / 60
        val minute = minuteOfDay % 60
        val ampm = if (hour >= 12) "PM" else "AM"
        val displayHour = if (hour % 12 == 0) 12 else hour % 12
        return "${days[dayIndex]} %d:%02d %s".format(displayHour, minute, ampm)
    }
}
