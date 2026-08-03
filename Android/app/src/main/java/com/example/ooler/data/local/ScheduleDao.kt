package com.example.ooler.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleDao {
    @Query("SELECT * FROM schedules WHERE id = 0")
    fun getSchedule(): Flow<ScheduleEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSchedule(schedule: ScheduleEntity)
}
