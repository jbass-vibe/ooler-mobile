# Ooler Android App Implementation Plan

This plan outlines the development of an Android application to control an Ooler sleep system using the provided BLE protocol specification.

## User Review Required

> [!IMPORTANT]
> - **BLE Permissions:** The app will require `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, and `ACCESS_FINE_LOCATION` (depending on Android version).
> - **Kable Library:** I will use the [Kable](https://github.com/JuulLabs/kable) library for BLE communication as it is modern, coroutine-based, and highly stable.
> - **Mock Mode:** A toggle will be provided to switch between "Live Device" and "Mock Device" for development without a physical unit.

## Proposed Changes

### [Project Setup]

#### [NEW] [build.gradle.kts](file:///C:/Users/joeba/scratch/ooler-mobile/ooler-mobile/Android/build.gradle.kts)
#### [NEW] [settings.gradle.kts](file:///C:/Users/joeba/scratch/ooler-mobile/ooler-mobile/Android/settings.gradle.kts)
Initial project configuration with Compose, Kable, Hilt, and Room dependencies.

### [Domain Layer]

#### [NEW] [Models.kt](file:///C:/Users/joeba/scratch/ooler-mobile/ooler-mobile/Android/app/src/main/java/com/example/ooler/domain/Models.kt)
Definitions for `OolerState`, `OolerMode`, `TemperatureUnit`, and `OolerSchedule`.

#### [NEW] [OolerRepository.kt](file:///C:/Users/joeba/scratch/ooler-mobile/ooler-mobile/Android/app/src/main/java/com/example/ooler/domain/OolerRepository.kt)
Interface for controlling the device and managing schedules.

### [Data Layer]

#### [NEW] [KableOolerRepository.kt](file:///C:/Users/joeba/scratch/ooler-mobile/ooler-mobile/Android/app/src/main/java/com/example/ooler/data/KableOolerRepository.kt)
Implementation of the Ooler control logic using the Kable BLE library.

#### [NEW] [MockOolerRepository.kt](file:///C:/Users/joeba/scratch/ooler-mobile/ooler-mobile/Android/app/src/main/java/com/example/ooler/data/MockOolerRepository.kt)
Mock implementation for testing and development.

#### [NEW] [AppDatabase.kt](file:///C:/Users/joeba/scratch/ooler-mobile/ooler-mobile/Android/app/src/main/java/com/example/ooler/data/local/AppDatabase.kt)
Room database for storing device settings and schedules.

### [UI Layer]

#### [NEW] [OolerViewModel.kt](file:///C:/Users/joeba/scratch/ooler-mobile/ooler-mobile/Android/app/src/main/java/com/example/ooler/ui/OolerViewModel.kt)
Manages app state, handles unit conversions, and queues commands when the device is off.

#### [NEW] [DashboardScreen.kt](file:///C:/Users/joeba/scratch/ooler-mobile/ooler-mobile/Android/app/src/main/java/com/example/ooler/ui/DashboardScreen.kt)
Main remote control UI (Power, Mode, Temperature).

#### [NEW] [ScheduleScreen.kt](file:///C:/Users/joeba/scratch/ooler-mobile/ooler-mobile/Android/app/src/main/java/com/example/ooler/ui/ScheduleScreen.kt)
UI for managing the weekly schedule.

## Verification Plan

### Automated Tests
- Unit tests for `Schedule` encoding/decoding logic.
- Unit tests for `OolerViewModel` command queuing and unit conversions.

### Manual Verification
- Verify BLE scanning and connection with the physical Ooler device.
- Test Power, Mode, and Temperature control.
- Verify schedule sync and persistence in Room.
- Test Mock mode to ensure UI behaves correctly without hardware.
