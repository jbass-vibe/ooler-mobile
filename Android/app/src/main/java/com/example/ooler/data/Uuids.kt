package com.example.ooler.data

import java.util.UUID

object OolerUuids {
    // Services
    val MAIN_CONTROL_SERVICE = UUID.fromString("5c293993-d039-4225-92f6-31fa62101e96")
    val DEVICE_INFO_SERVICE = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val CURRENT_TIME_SERVICE = UUID.fromString("00001805-0000-1000-8000-00805f9b34fb")
    val DIAGNOSTICS_SERVICE = UUID.fromString("28dfbeff-61e0-4aa2-9eea-ede0b86f3f65")
    val DEVICE_CONFIG_SERVICE = UUID.fromString("dc5e0473-d2ec-4f23-9b61-cd7bae046f76")
    val SLEEP_SCHEDULE_SERVICE = UUID.fromString("b430cd72-3a7f-4720-86fd-66ae8f6f3493")

    // Main Control Characteristics
    val POWER = UUID.fromString("7a2623ff-bd92-4c13-be9f-7023aa4ecb85")
    val MODE = UUID.fromString("cafe2421-d04c-458f-b1c0-253c6c97e8e8")
    val SET_TEMP_F = UUID.fromString("6aa46711-a29d-4f8a-88e2-044ca1fd03ff")
    val ACTUAL_TEMP = UUID.fromString("e8ebded3-9dca-45c2-a2d8-ceffb901474d")
    val WATER_LEVEL = UUID.fromString("8db5b9db-dbf6-47e6-a9dd-0612a1349a5b")
    val CLEAN = UUID.fromString("e9bf509a-b1c5-4243-9514-352ad2d851f6")
    val DISPLAY_UNIT = UUID.fromString("2c988613-fe15-4067-85bc-8e59d5e0b1e3")
    val RELATIVE_HUMIDITY = UUID.fromString("654b8162-7090-4084-8d94-4eb33e917e9c")
    val AMBIENT_TEMP_F = UUID.fromString("7c0ea228-2616-4765-a726-beb5f4a0fa71")

    // Current Time Characteristics
    val CURRENT_TIME = UUID.fromString("00002a2b-0000-1000-8000-00805f9b34fb")
    val LOCAL_TIME_INFO = UUID.fromString("00002a0f-0000-1000-8000-00805f9b34fb")

    // Sleep Schedule Characteristics
    val SCHEDULE_HEADER = UUID.fromString("8cb4ec90-cd94-4f69-b963-5473fbd94ec8")
    val SCHEDULE_TIMES = UUID.fromString("8cb4ec90-cd94-4f69-b963-5473fbd94ea9")
    val SCHEDULE_TEMPS = UUID.fromString("fa242bc0-bf85-41f7-8dbb-53ba2e8b0895")
}
