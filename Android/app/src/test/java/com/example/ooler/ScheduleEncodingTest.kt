package com.example.ooler

import com.example.ooler.domain.OolerSchedule
import com.example.ooler.domain.ScheduleEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ScheduleEncodingTest {

    @Test
    fun testScheduleEncodingAndByteSwap() {
        val events = listOf(
            ScheduleEvent(1320, 68), // Mon 22:00 -> 68F
            ScheduleEvent(1800, 0)   // Tue 06:00 -> OFF
        )
        val schedule = OolerSchedule(events)

        val times = ByteArray(140)
        val temps = ByteArray(70) { 0xFF.toByte() }

        schedule.events.take(70).forEachIndexed { i, event ->
            val minute = event.minuteOfWeek.toShort()
            // Byte-swap quirk: write as Big Endian to swap from standard BLE Little Endian
            // The Ooler device expects Big Endian bytes because it swaps them internally upon receipt
            ByteBuffer.wrap(times, i * 2, 2).order(ByteOrder.BIG_ENDIAN).putShort(minute)
            temps[i] = event.temperatureF.toByte()
        }

        // Verify first event time (1320 = 0x0528)
        // Big Endian bytes should be [0x05, 0x28]
        assertEquals(0x05.toByte(), times[0])
        assertEquals(0x28.toByte(), times[1])

        // Verify second event time (1800 = 0x0708)
        // Big Endian bytes should be [0x07, 0x08]
        assertEquals(0x07.toByte(), times[2])
        assertEquals(0x08.toByte(), times[3])

        // Verify temps
        assertEquals(68.toByte(), temps[0])
        assertEquals(0.toByte(), temps[1])
        assertEquals(0xFF.toByte(), temps[2])
    }
}
