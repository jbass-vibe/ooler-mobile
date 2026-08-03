package com.example.ooler.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.example.ooler.domain.OolerSchedule

@Entity(tableName = "schedules")
data class ScheduleEntity(
    @PrimaryKey val id: Int = 0, // We only store one active schedule for now
    val schedule: OolerSchedule
)
