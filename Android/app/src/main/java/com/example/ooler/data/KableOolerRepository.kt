package com.example.ooler.data

import com.example.ooler.domain.*
import com.juul.kable.*
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
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

    private var peripheral: Peripheral? = null
    private var scanner = Scanner {
        // No filters for discovery debugging
    }

    // Cached values to re-send when powering on
    private var pendingMode: OolerMode? = null
    private var pendingSetTemp: Int? = null

    // Sequence counter for schedule
    private var scheduleSequence: Int = 0

    override suspend fun connect() {
        Log.d("OolerRepo", "Connecting...")
        if (peripheral?.state?.first() is State.Connected) {
            Log.d("OolerRepo", "Already connected")
            return
        }

        try {
            Log.d("OolerRepo", "Scanning for OOLER...")
            val advertisement = scanner.advertisements
                .onEach { Log.d("OolerRepo", "Found advertisement: ${it.name} (${it.address})") }
                .first { it.name?.contains("OOLER", ignoreCase = true) == true }
            Log.d("OolerRepo", "Found OOLER: ${advertisement.name} (${advertisement.address})")
            val p = scope.peripheral(advertisement)
            peripheral = p

            p.connect()
            Log.d("OolerRepo", "Connected successfully")
            _oolerState.update { it.copy(isConnected = true) }

            // 1. Sync clock on every connection
            syncClock()

            // 2. Read Display Temperature Unit once
            val unitByte = p.read(OolerUuids.MAIN_CONTROL_SERVICE, OolerUuids.DISPLAY_UNIT)
            val unit = TemperatureUnit.fromInt(unitByte[0].toInt())
            Log.d("OolerRepo", "Display unit read: $unit")
            _oolerState.update { it.copy(displayUnit = unit) }

            // 3. Poll Power, Mode, Set Temp, Actual Temp, Water Level, Clean once on connect
            pollAll()

            // 4. Subscribe to notifications
            subscribeToNotifications(p)

            // 5. Periodic polling for telemetry
            scope.launch {
                while (isActive && peripheral?.state?.first() is State.Connected) {
                    delay(10000) // Poll every 10 seconds
                    try {
                        pollAll()
                    } catch (e: Exception) {
                        Log.w("OolerRepo", "Periodic poll failed", e)
                    }
                }
            }

            // Keep track of connection state
            scope.launch {
                p.state.collect { state ->
                    Log.d("OolerRepo", "Connection state changed: $state")
                    _oolerState.update { it.copy(isConnected = state is State.Connected) }
                }
            }

        } catch (e: Exception) {
            Log.e("OolerRepo", "Connection failed", e)
            _oolerState.update { it.copy(isConnected = false) }
            throw e
        }
    }

    override suspend fun disconnect() {
        peripheral?.disconnect()
        peripheral = null
        _oolerState.update { it.copy(isConnected = false) }
    }

    @SuppressLint("NewApi")
    private suspend fun pollAll() {
        val p = peripheral ?: return
        Log.d("OolerRepo", "Polling all characteristics...")
        
        val power = p.read(OolerUuids.MAIN_CONTROL_SERVICE, OolerUuids.POWER)[0].toInt() != 0
        val mode = OolerMode.fromInt(p.read(OolerUuids.MAIN_CONTROL_SERVICE, OolerUuids.MODE)[0].toInt())
        val setTemp = p.read(OolerUuids.MAIN_CONTROL_SERVICE, OolerUuids.SET_TEMP_F)[0].toInt() and 0xFF
        val actualTemp = p.read(OolerUuids.MAIN_CONTROL_SERVICE, OolerUuids.ACTUAL_TEMP)[0].toInt() and 0xFF
        val waterLevel = p.read(OolerUuids.MAIN_CONTROL_SERVICE, OolerUuids.WATER_LEVEL)[0].toInt() and 0xFF
        val clean = p.read(OolerUuids.MAIN_CONTROL_SERVICE, OolerUuids.CLEAN)[0].toInt() != 0
        val humidity = p.read(OolerUuids.MAIN_CONTROL_SERVICE, OolerUuids.RELATIVE_HUMIDITY)[0].toInt() and 0xFF
        val ambientTemp = p.read(OolerUuids.MAIN_CONTROL_SERVICE, OolerUuids.AMBIENT_TEMP_F)[0].toInt() and 0xFF

        val deviceTime = try {
            val timeBytes = p.read(OolerUuids.CURRENT_TIME_SERVICE, OolerUuids.CURRENT_TIME)
            val buffer = ByteBuffer.wrap(timeBytes).order(ByteOrder.LITTLE_ENDIAN)
            val year = buffer.short.toInt()
            val month = buffer.get().toInt()
            val day = buffer.get().toInt()
            val hour = buffer.get().toInt()
            val minute = buffer.get().toInt()
            val second = buffer.get().toInt()
            // month is 1-based in BLE, 0-based in Calendar
            LocalDateTime.of(year, month.coerceIn(1, 12), day.coerceIn(1, 31), hour.coerceIn(0, 23), minute.coerceIn(0, 59), second.coerceIn(0, 59))
        } catch (e: Exception) {
            Log.w("OolerRepo", "Failed to read device time", e)
            null
        }

        Log.d("OolerRepo", "Poll result: power=$power, mode=$mode, set=$setTemp, actual=$actualTemp, water=$waterLevel, clean=$clean, humidity=$humidity, ambient=$ambientTemp, time=$deviceTime")

        _oolerState.update { 
            it.copy(
                powerOn = power,
                mode = mode,
                setTemperatureF = setTemp,
                actualTemperature = actualTemp,
                waterLevel = waterLevel,
                cleaning = clean,
                humidity = humidity,
                ambientTemperatureF = ambientTemp,
                deviceTime = deviceTime
            )
        }
        
        // Also read schedule
        readSchedule()
    }

    private suspend fun readSchedule() {
        val p = peripheral ?: return
        val timesBytes = p.read(OolerUuids.SLEEP_SCHEDULE_SERVICE, OolerUuids.SCHEDULE_TIMES)
        val tempsBytes = p.read(OolerUuids.SLEEP_SCHEDULE_SERVICE, OolerUuids.SCHEDULE_TEMPS)
        val headerBytes = p.read(OolerUuids.SLEEP_SCHEDULE_SERVICE, OolerUuids.SCHEDULE_HEADER)

        scheduleSequence = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

        val events = mutableListOf<ScheduleEvent>()
        val buffer = ByteBuffer.wrap(timesBytes).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 70) {
            val minute = buffer.short.toInt() and 0xFFFF
            val temp = tempsBytes[i].toInt() and 0xFF
            if (temp == 0xFF) break
            events.add(ScheduleEvent(minute, temp))
        }
        _schedule.value = OolerSchedule(events)
    }

    private fun subscribeToNotifications(p: Peripheral) {
        scope.launch {
            p.observe(OolerUuids.MAIN_CONTROL_SERVICE, OolerUuids.POWER).collect { data ->
                _oolerState.update { it.copy(powerOn = data[0].toInt() != 0) }
            }
        }
        scope.launch {
            p.observe(OolerUuids.MAIN_CONTROL_SERVICE, OolerUuids.MODE).collect { data ->
                _oolerState.update { it.copy(mode = OolerMode.fromInt(data[0].toInt())) }
            }
        }
        scope.launch {
            p.observe(OolerUuids.MAIN_CONTROL_SERVICE, OolerUuids.SET_TEMP_F).collect { data ->
                _oolerState.update { it.copy(setTemperatureF = data[0].toInt() and 0xFF) }
            }
        }
        scope.launch {
            p.observe(OolerUuids.MAIN_CONTROL_SERVICE, OolerUuids.ACTUAL_TEMP).collect { data ->
                _oolerState.update { it.copy(actualTemperature = data[0].toInt() and 0xFF) }
            }
        }
    }

    override suspend fun setPower(on: Boolean) {
        writeWithRetry(OolerUuids.MAIN_CONTROL_SERVICE, OolerUuids.POWER, byteArrayOf(if (on) 1 else 0))
        _oolerState.update { it.copy(powerOn = on) }
        
        if (on) {
            // Re-send pending mode and temp after power on
            pendingMode?.let { setMode(it) }
            pendingSetTemp?.let { setTemperature(it) }
            pendingMode = null
            pendingSetTemp = null
        }
    }

    override suspend fun setMode(mode: OolerMode) {
        if (!_oolerState.value.powerOn) {
            pendingMode = mode
            return
        }
        writeWithRetry(OolerUuids.MAIN_CONTROL_SERVICE, OolerUuids.MODE, byteArrayOf(mode.value.toByte()))
        _oolerState.update { it.copy(mode = mode) }
    }

    override suspend fun setTemperature(fahrenheit: Int) {
        val clamped = when {
            fahrenheit == OolerConstants.TEMP_LO_F -> OolerConstants.TEMP_LO_F
            fahrenheit == OolerConstants.TEMP_HI_F -> OolerConstants.TEMP_HI_F
            fahrenheit < OolerConstants.TEMP_MIN_F -> OolerConstants.TEMP_LO_F
            fahrenheit > OolerConstants.TEMP_MAX_F -> OolerConstants.TEMP_HI_F
            else -> fahrenheit
        }

        if (!_oolerState.value.powerOn) {
            pendingSetTemp = clamped
            return
        }
        writeWithRetry(OolerUuids.MAIN_CONTROL_SERVICE, OolerUuids.SET_TEMP_F, byteArrayOf(clamped.toByte()))
        _oolerState.update { it.copy(setTemperatureF = clamped) }
    }

    override suspend fun setCleaning(on: Boolean) {
        if (!_oolerState.value.powerOn && on) {
            // Spec says cleaning requires power to be on
            return
        }
        writeWithRetry(OolerUuids.MAIN_CONTROL_SERVICE, OolerUuids.CLEAN, byteArrayOf(if (on) 1 else 0))
        _oolerState.update { it.copy(cleaning = on) }
    }

    override suspend fun setDisplayUnit(unit: TemperatureUnit) {
        writeWithRetry(OolerUuids.MAIN_CONTROL_SERVICE, OolerUuids.DISPLAY_UNIT, byteArrayOf(unit.value.toByte()))
        _oolerState.update { it.copy(displayUnit = unit) }
    }

    override suspend fun updateSchedule(schedule: OolerSchedule) {
        // Update local state first so UI is responsive
        _schedule.value = schedule

        val p = peripheral
        if (p == null || p.state.first() !is State.Connected) {
            Log.d("OolerRepo", "Not connected, schedule saved locally only")
            return
        }

        val times = ByteArray(140)
        val temps = ByteArray(70) { 0xFF.toByte() }

        schedule.events.take(70).forEachIndexed { i, event ->
            val minute = event.minuteOfWeek.toShort()
            // Byte-swap quirk: write as Big Endian to swap from standard BLE Little Endian
            ByteBuffer.wrap(times, i * 2, 2).order(ByteOrder.BIG_ENDIAN).putShort(minute)
            temps[i] = event.temperatureF.toByte()
        }

        writeWithRetry(OolerUuids.SLEEP_SCHEDULE_SERVICE, OolerUuids.SCHEDULE_TIMES, times)
        writeWithRetry(OolerUuids.SLEEP_SCHEDULE_SERVICE, OolerUuids.SCHEDULE_TEMPS, temps)

        scheduleSequence++
        val seq = scheduleSequence.toShort()
        // Byte-swap quirk: write as Big Endian
        val header = ByteBuffer.allocate(2).order(ByteOrder.BIG_ENDIAN).putShort(seq).array()
        
        writeWithRetry(OolerUuids.SLEEP_SCHEDULE_SERVICE, OolerUuids.SCHEDULE_HEADER, header)
        _schedule.value = schedule
    }

    override suspend fun syncClock() {
        val now = LocalDateTime.now()
        val zone = ZoneId.systemDefault()
        val offset = zone.rules.getOffset(now)
        val offsetMinutes = offset.totalSeconds / 60
        val tzOffset15 = (offsetMinutes / 15).toByte()

        val dayOfWeek = now.dayOfWeek.value // 1 (Mon) to 7 (Sun)
        
        val currentTime = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
            .putShort(now.year.toShort())
            .put(now.monthValue.toByte())
            .put(now.dayOfMonth.toByte())
            .put(now.hour.toByte())
            .put(now.minute.toByte())
            .put(now.second.toByte())
            .put(dayOfWeek.toByte())
            .put(0.toByte()) // fractions
            .put(1.toByte()) // reason: manual update
            .array()

        val localTimeInfo = byteArrayOf(tzOffset15, 0) // 0 = no DST offset for simplicity, or decode it

        writeWithRetry(OolerUuids.CURRENT_TIME_SERVICE, OolerUuids.CURRENT_TIME, currentTime)
        writeWithRetry(OolerUuids.CURRENT_TIME_SERVICE, OolerUuids.LOCAL_TIME_INFO, localTimeInfo)
    }

    private suspend fun writeWithRetry(service: UUID, characteristic: UUID, data: ByteArray) {
        val p = peripheral ?: throw IllegalStateException("Not connected")
        Log.d("OolerRepo", "Writing to $characteristic: ${data.joinToString { it.toString(16) }}")
        
        try {
            p.write(service, characteristic, data, WriteType.WithResponse)
            Log.d("OolerRepo", "Write successful")
        } catch (e: Exception) {
            Log.w("OolerRepo", "Write failed, retrying...", e)
            // Immediate retry
            try {
                p.write(service, characteristic, data, WriteType.WithResponse)
                Log.d("OolerRepo", "Retry successful")
            } catch (e2: Exception) {
                Log.e("OolerRepo", "Retry failed, attempting reconnect...", e2)
                // Force reconnect and retry once more
                reconnectAndRetry(service, characteristic, data)
            }
        }
    }

    private suspend fun reconnectAndRetry(service: UUID, characteristic: UUID, data: ByteArray) {
        disconnect()
        connect()
        val p = peripheral ?: throw IllegalStateException("Failed to reconnect")
        p.write(service, characteristic, data, WriteType.WithResponse)
    }

    private fun Characteristic(service: UUID, characteristic: UUID) = characteristicOf(
        service = service.toString(),
        characteristic = characteristic.toString()
    )

    private suspend fun Peripheral.read(service: UUID, characteristic: UUID): ByteArray {
        return read(Characteristic(service, characteristic))
    }

    private suspend fun Peripheral.write(service: UUID, characteristic: UUID, data: ByteArray, writeType: WriteType) {
        write(Characteristic(service, characteristic), data, writeType)
    }

    private fun Peripheral.observe(service: UUID, characteristic: UUID): Flow<ByteArray> {
        return observe(Characteristic(service, characteristic))
    }
}
