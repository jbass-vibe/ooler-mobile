package com.example.ooler.data.local

import androidx.room.TypeConverter
import com.example.ooler.domain.OolerSchedule
import com.example.ooler.domain.ScheduleEvent
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class Converters {
    @TypeConverter
    fun fromSchedule(schedule: OolerSchedule): String {
        return Gson().toJson(schedule)
    }

    @TypeConverter
    fun toSchedule(json: String): OolerSchedule {
        return Gson().fromJson(json, OolerSchedule::class.java)
    }
}
