# Ooler Sleep System — Bluetooth LE Protocol Specification

**Status:** Reverse-engineered / unofficial. Compiled from two independent open-source clients:
- [`ooler_ble_client`](https://github.com/PostLogical/ooler_ble_client) (Python, `bleak`) — verified against firmware 15.20, model 999
- [`ooler-mqtt-bridge`](https://github.com/turmoni/ooler-mqtt-bridge) (Python, `bleak`)

Both projects independently arrived at the same characteristic UUIDs for all core control functions, which gives high confidence in those values. Where the two disagree or one has information the other lacks, it's called out explicitly.

---

## 1. Overview

The Ooler exposes a standard BLE GATT server. There is no vendor session/auth handshake — a central device connects, optionally subscribes to notifications, and reads/writes plain characteristic values. Most state is exposed as single characteristics rather than framed "packets," so the three transaction types that matter are:

| Transaction type | Direction | Used for |
|---|---|---|
| **Read** | Central → Peripheral → Central | Fetching current state on connect or on demand (poll) |
| **Write** (write-with-response) | Central → Peripheral | Changing state (power, mode, temperature, schedule, clock) |
| **Notify** | Peripheral → Central (unsolicited) | Pushing state changes for 4 high-churn characteristics |

A fourth pattern — **write + indicate** on a dedicated command/response service — exists on the device but its payload semantics have **not** been decoded by either project (see §7).

### 1.1 Discovery

The device advertises with the local name `OOLER`. Both libraries locate it with a standard BLE scan filtered on that name (e.g. `BleakScanner.find_device_by_name("OOLER")`).

### 1.2 Critical device behavior

- **The device silently drops writes to (almost) all characteristics while powered off.** If you write mode/temperature while off, the write is accepted at the GATT layer but has no effect. Client libraries work around this by caching the desired value and re-sending it immediately after the next power-on write.
- **`SET_TEMP` is always stored/reported in Fahrenheit**, regardless of what `DISPLAY_TEMPERATURE_UNIT` is set to. `ACTUAL_TEMP` (the measured water temperature) is reported in whatever unit the display is currently set to. Apps must convert accordingly.
- All numeric characteristics observed so far are **single-byte or small little-endian integers** except the schedule and time-service payloads, which are explicitly structured (see §6, §8).

---

## 2. GATT Services Summary

| Service UUID | Purpose |
|---|---|
| `00001803-...`(180a) `0000180a-0000-1000-8000-00805f9b34fb` | Standard BLE **Device Information Service** |
| `5c293993-d039-4225-92f6-31fa62101e96` | **Main control service** — power, mode, temperature, water, clean, humidity, etc. |
| `1d14d6ee-fd63-4fa1-bfa4-8f47b42119f0` | **Write-only command service** — undecoded |
| `4bf69dcd-412d-494c-9348-f2f364e5c6ce` | **Command/response service** — write + indicate pair, undecoded |
| `00001805-0000-1000-8000-00805f9b34fb` | Standard BLE **Current Time Service** |
| `28dfbeff-61e0-4aa2-9eea-ede0b86f3f65` | **Diagnostics service** — counters, logs, firmware |
| `dc5e0473-d2ec-4f23-9b61-cd7bae046f76` | **Device config service** — serial number, calibration |
| `b430cd72-3a7f-4720-86fd-66ae8f6f3493` | **Sleep schedule service** |
| `4d44eb61-87dd-402c-ad4c-41928e08c8eb` | OTA / vestigial service (repurposes a standard BLE characteristic) |

---

## 3. Main Control Service — `5c293993-d039-4225-92f6-31fa62101e96`

This is the service you need for basic on/off/temperature/mode control. All entries are 1 byte unless noted, little-endian.

| Characteristic | UUID | Properties | Values | Notes |
|---|---|---|---|---|
| **Power** | `7a2623ff-bd92-4c13-be9f-7023aa4ecb85` | read / write / notify | `0x00` off, `0x01` on | Powering on re-triggers mode/temp application on the device |
| **Mode** (pump speed) | `cafe2421-d04c-458f-b1c0-253c6c97e8e8` | read / write / notify | `0x00` Silent, `0x01` Regular, `0x02` Boost | Called `FAN_SPEED` in the mqtt-bridge project |
| **Set Temperature** | `6aa46711-a29d-4f8a-88e2-044ca1fd03ff` | read / write / notify | see §3.1 | **Always Fahrenheit** on the wire |
| **Actual Temperature** | `e8ebded3-9dca-45c2-a2d8-ceffb901474d` | read / notify (read-only) | integer, in current display unit | Live water temperature |
| **Water Level** | `8db5b9db-dbf6-47e6-a9dd-0612a1349a5b` | read / notify | `1`, `50`, or `100` observed | Not subscribed to notifications in practice — polled instead (see §9.2) |
| **Clean** | `e9bf509a-b1c5-4243-9514-352ad2d851f6` | read / write / notify | `0x00` / `0x01` | Starting a clean cycle also forces `Set Temperature` to 75°F on the device while active; writing `Clean=1` requires the device to already be powered on |
| **Display Temperature Unit** | `2c988613-fe15-4067-85bc-8e59d5e0b1e3` | read / write | `0x00` = °F, `0x01` = °C | Only takes effect while the device is on; does not persist a resend-on-power-on the way mode/temp do |
| **Thermal Effort** | `fdff37ff-901d-40c6-b7e0-dd5797bd2989` | read / notify | 2 bytes LE; `0` when off | Undocumented unit — likely a duty-cycle/effort metric |
| **Pump Level** | `5a914d86-9b5e-4a35-ad3d-3e5936d485b2` | read / notify | 2 bytes LE; `0` when off | Called `PUMP_WATTS` in mqtt-bridge (label unconfirmed) |
| **Power Rail** | `acab07ec-fc95-451d-88e5-4565a364a806` | read / notify | constant `23` observed | Likely a supply-rail voltage/current telemetry value |
| **Relative Humidity** | `654b8162-7090-4084-8d94-4eb33e917e9c` | read / notify | percentage, 0–100 | Ambient sensor |
| **Ambient Temperature (F)** | `7c0ea228-2616-4765-a726-beb5f4a0fa71` | read / notify | integer | Always Fahrenheit regardless of display unit |
| Unknown `F30D` | `f30d875a-7297-43ac-9f5b-1d7eed4446eb` | read / notify | always `0x0000` in testing | mqtt-bridge labels this `PUMP_VOLTS` — unconfirmed |
| Unknown `9234` | `923445f2-9438-4d81-98c9-904b69b94eca` | read / notify | `0xFF`/`0xFE` seen on a device with a temperature-revert bug, `0x00` otherwise | Possibly an internal error/fault flag |
| Unknown `AF8D` | `af8d892b-693d-495d-ac95-eb849a5ac40c` | read / notify | always `0x00` | — |
| Warm Wake Enabled *(mqtt-bridge only)* | `7aa73db1-1c2d-4c8c-9195-36c0a4b6acb2` | read / write (assumed) | not decoded | Not present in the `ooler_ble_client` const list; may be an older-firmware or app-level toggle distinct from the schedule-embedded warm-wake events described in §6 |

### 3.1 Set Temperature value rules

```
TEMP_LO_F  = 45          # "LO" sentinel — cool as aggressively as possible
TEMP_MIN_F = 55          # lowest normal set-point
TEMP_MAX_F = 115         # highest normal set-point
TEMP_HI_F  = 120         # "HI" sentinel — heat as aggressively as possible
```
- The device **clamps** values 46–54 down to LO (45) and values 116–119 up to HI (120).
- Client-side write validation therefore treats `45`, `54`–`116`, and `120` as acceptable inputs (54 and 116 are accepted specifically because the device will snap them to LO/HI anyway).
- Values outside `{45} ∪ [54,116] ∪ {120}` should be rejected before writing.

### 3.2 Example — read state (Python / bleak)

```python
power   = await client.read_gatt_char("7a2623ff-bd92-4c13-be9f-7023aa4ecb85")   # b'\x01'
mode    = await client.read_gatt_char("cafe2421-d04c-458f-b1c0-253c6c97e8e8")   # b'\x01' -> Regular
settemp = await client.read_gatt_char("6aa46711-a29d-4f8a-88e2-044ca1fd03ff")   # b'\x48' -> 72°F
```

### 3.3 Example — turn on, set Regular mode, set 68°F

```python
POWER_CHAR   = "7a2623ff-bd92-4c13-be9f-7023aa4ecb85"
MODE_CHAR    = "cafe2421-d04c-458f-b1c0-253c6c97e8e8"
SETTEMP_CHAR = "6aa46711-a29d-4f8a-88e2-044ca1fd03ff"

await client.write_gatt_char(POWER_CHAR, bytes([0x01]), response=True)
await client.write_gatt_char(MODE_CHAR, bytes([0x01]), response=True)     # Regular
await client.write_gatt_char(SETTEMP_CHAR, bytes([68]), response=True)   # 68°F
```
Note the ordering: because writes are dropped while off, `Power` must be written (and take effect) before `Mode`/`Set Temperature` writes will register. Client libraries mitigate transient timing issues by re-sending mode and temperature immediately after every power-on write.

---

## 4. Device Information Service — `0000180a-0000-1000-8000-00805f9b34fb` (standard)

| Characteristic | UUID | Properties | Example value |
|---|---|---|---|
| Manufacturer Name | `00002a29-...` | read | `"Kryo, Inc."` |
| Firmware Revision | `00002a26-...` | read | `"15.20"` |
| Model Number | `00002a24-...` | read | `"999"` |
| Hardware ID | `a2b8f087-c75f-4646-a97a-22db6b748c94` | read | 6 bytes, unique per device |
| Device Name | `00002a00-...` | read | `"OOLER"` |

---

## 5. Current Time Service — `00001805-0000-1000-8000-00805f9b34fb` (standard)

The Ooler has an internal clock used to execute the sleep schedule. Both client libraries write this on every connection.

| Characteristic | UUID | Properties | Layout |
|---|---|---|---|
| Current Time | `00002a2b-...` | read / write / notify | 10 bytes — see below |
| Local Time Information | `00002a0f-...` | read / write | 2 bytes — UTC offset (units of 15 min, signed), DST offset |
| Reference Time Information | `00002a14-...` | read | observed constant `0x04FFFFFF` |

### 5.1 Current Time payload (10 bytes)

```
offset  size  field
0       2     year, uint16 LE
2       1     month (1–12)
3       1     day (1–31)
4       1     hour (0–23)
5       1     minute (0–59)
6       1     second (0–59)
7       1     day of week, 1=Monday ... 7=Sunday
8       1     fractions of a second, 1/256ths (both libraries write 0)
9       1     "adjust reason" bitfield: bit0=manual update, bit1=external reference,
              bit2=timezone change, bit3=DST change
```

### 5.2 Local Time Information payload (2 bytes)

```
offset  size  field
0       1     timezone offset, signed int8, units of 15 minutes
1       1     DST offset, units of 15 minutes (0 = standard time; 255 = unknown)
```

### 5.3 Example — sync clock (Python)

```python
import struct
from datetime import datetime, timezone

now = datetime.now().astimezone()
current_time = struct.pack(
    "<HBBBBBBB",
    now.year, now.month, now.day, now.hour, now.minute, now.second,
    now.isoweekday(), 0,
) + b"\x01"                          # reason: manual time update
await client.write_gatt_char("00002a2b-0000-1000-8000-00805f9b34fb", current_time, response=True)

tz_offset_15 = int(now.utcoffset().total_seconds() / 60 / 15)
local_time_info = struct.pack("bB", tz_offset_15, 0)   # 0 = no DST offset
await client.write_gatt_char("00002a0f-0000-1000-8000-00805f9b34fb", local_time_info, response=True)
```

---

## 6. Sleep Schedule Service — `b430cd72-3a7f-4720-86fd-66ae8f6f3493`

This is the most complex part of the protocol. The device stores **one** active weekly schedule as a flat, chronologically-sorted list of up to 70 "events." Each event says "at this minute of the week, set the water to this temperature," and that setting holds until the next event (set-and-hold semantics) — there's no separate concept of a "night" on the wire; nights, warm wake, and multi-zone temperature steps are all just consecutive events that a client reconstructs into a friendlier structure.

| Characteristic | UUID | Properties | Layout |
|---|---|---|---|
| Schedule Header | `8cb4ec90-cd94-4f69-b963-5473fbd94ec8` | read / write | 2 bytes — uint16 LE sequence counter, incremented on every write |
| Schedule Times | `8cb4ec90-cd94-4f69-b963-5473fbd94ea9` | read / write | 140 bytes — 70 × uint16 LE minute-of-week |
| Schedule Temps | `fa242bc0-bf85-41f7-8dbb-53ba2e8b0895` | read / write | 70 bytes — 1 byte per event, 1:1 aligned with Times |
| Schedule Meta | `fa242bc0-bf85-41f7-8dbb-53ba2e8b08a3` | read-only | 4 bytes — firmware-internal state flag, safe to ignore |

### 6.1 Encoding rules

- **Minute-of-week**: `0` = Monday 00:00, up to `10079` = Sunday 23:59. Values slightly above 10079 are used for a Sunday-night schedule that spills into Monday morning (e.g. `10080` = "Monday 00:00," treated as the wrap-around continuation of Sunday night).
- **Temperature byte**, per event:
  - `0x00` = OFF (device powers down at this event)
  - `1`–`120` = target Fahrenheit temperature
  - `0xFE` = warm-wake marker (see §6.3)
  - `0xFF` = unused / end-of-list padding — a run of `0xFF` in Temps marks the end of real events
- Unused event slots: pad Times with `0x0000` and Temps with `0xFF`.
- **Byte-swap quirk**: the device byte-swaps every uint16 it receives over GATT writes to this service. Clients must pre-swap the two bytes of every uint16 (the 70 time entries *and* the 2-byte header) before writing so the device ends up storing the intended little-endian value. Temperature bytes are single bytes and are not affected.

```python
def _byteswap_uint16s(data: bytes) -> bytes:
    buf = bytearray(data)
    for i in range(0, len(buf) - 1, 2):
        buf[i], buf[i + 1] = buf[i + 1], buf[i]
    return bytes(buf)
```

### 6.2 Write order and sequence counter

1. Write **Times** (byte-swapped).
2. Write **Temps** (not swapped — single bytes).
3. Write **Header** = `previous_seq + 1`, byte-swapped.

The header is a simple monotonically-increasing counter; there's no evidence it needs to match any particular value other than "increment on each write."

### 6.3 Warm wake

Warm wake is encoded as **three consecutive events**, not a separate flag on the wire (though see §3's note about a possibly-separate `WARM_WAKE_ENABLED` characteristic used by the other client):

| Event | Minute of week | Temp byte |
|---|---|---|
| 1 | wake time | target temp (e.g. `116` = HI) |
| 2 | wake time **+ 1 minute** | `0xFE` (marker) |
| 3 | wake time + warm-wake duration | `0x00` (OFF) |

### 6.4 Worked example — simple daily schedule

Goal: every night, turn on at 22:00 to 68°F, turn off at 06:00, all 7 days, no warm wake.

For a Monday-night program (`day=0`), bedtime 22:00 = minute `0*1440 + 22*60 = 1320`; wake 06:00 is *earlier in clock time than bedtime*, so it's treated as falling on the **next calendar day**: `1320 + (1440 - 1320) ... ` more precisely, `off_time` uses the "next calendar day" rule below and lands at minute `1440 + 360 = 1800` (Tuesday 06:00).

```
event[0]: minute=1320 (Mon 22:00), temp=68     # bedtime, cool to 68°F
event[1]: minute=1800 (Tue 06:00), temp=0x00   # OFF / wake
```
Repeating this pattern for all 7 nights (with each night's OFF landing on the following calendar day) produces 14 events total — well under the 70-event cap. A uniform 7-night schedule with warm wake instead uses 7 × 3 = 21 events for the wake portion plus 7 for bedtime = 28 events.

### 6.5 Example — read and decode (Python)

```python
TIMES_CHAR  = "8cb4ec90-cd94-4f69-b963-5473fbd94ea9"
TEMPS_CHAR  = "fa242bc0-bf85-41f7-8dbb-53ba2e8b0895"
HEADER_CHAR = "8cb4ec90-cd94-4f69-b963-5473fbd94ec8"

times_bytes  = await client.read_gatt_char(TIMES_CHAR)   # 140 bytes
temps_bytes  = await client.read_gatt_char(TEMPS_CHAR)   # 70 bytes
seq          = int.from_bytes(await client.read_gatt_char(HEADER_CHAR), "little")

import struct
events = []
for i in range(70):
    minute = struct.unpack_from("<H", times_bytes, i * 2)[0]
    temp   = temps_bytes[i]
    if temp == 0xFF:
        break
    events.append((minute, temp))
```

### 6.6 Example — write a schedule (Python)

```python
import struct

def encode(events):
    times = bytearray(140)
    temps = bytearray([0xFF] * 70)
    for i, (minute, temp) in enumerate(events):
        struct.pack_into("<H", times, i * 2, minute)
        temps[i] = temp
    return bytes(times), bytes(temps)

events = [(1320, 68), (1800, 0x00)]   # Mon 22:00 -> 68F, Tue 06:00 -> OFF
times_bytes, temps_bytes = encode(events)

new_seq = seq + 1
await client.write_gatt_char(TIMES_CHAR,  _byteswap_uint16s(times_bytes), response=True)
await client.write_gatt_char(TEMPS_CHAR,  temps_bytes, response=True)          # not swapped
await client.write_gatt_char(HEADER_CHAR, _byteswap_uint16s(new_seq.to_bytes(2, "little")), response=True)
```

### 6.7 Clearing a schedule

Write an empty event list (times all-zero, temps all `0xFF`), header incremented as usual.

---

## 7. Undecoded Services

Two services exist on the device whose exact command semantics have **not** been reverse-engineered by either project. They're documented here so implementers know they exist and can investigate further; do not assume the byte layouts below without your own verification.

| Service | Characteristic | UUID | Properties | Status |
|---|---|---|---|---|
| Write-only command service (`1d14d6ee-...`) | (unnamed) | `f7bf3564-fb6d-4e53-88a4-5e37e0326063` | write-only | Purpose unknown; likely a legacy or maintenance command channel |
| Command/response service (`4bf69dcd-...`) | Command | `abf9e9a9-058c-46d3-9570-1782d0fd1d5d` | write-only | Likely paired with the Response characteristic below as a request/response command channel |
| Command/response service (`4bf69dcd-...`) | Response | `8b56f100-bed3-4858-89d0-eef0da6168fd` | indicate-only | Peripheral-initiated **indicate** transaction (requires confirmation from the central, unlike notify) — payload format unknown |

If you need to reverse-engineer these, capture GATT traffic from the official Ooler app (e.g. via an Android BLE HCI snoop log) while triggering OTA update checks, firmware version queries, or factory-reset actions, since those are the likeliest uses for a dedicated command/response pair.

---

## 8. Diagnostics Service — `28dfbeff-61e0-4aa2-9eea-ede0b86f3f65`

| Characteristic | UUID | Properties | Layout |
|---|---|---|---|
| Lifetime counter | `5d30781f-1d06-4790-bbb8-5e1d7da96383` | read | 4-byte LE counter |
| Runtime counter | `1a5c6dae-34de-4265-9fa6-0a59f7f683ee` | read | 4-byte LE counter |
| UV runtime counter | `0ab6ff00-8d1b-475e-bcfa-ed3467f1f890` | read | 4-byte LE counter |
| Device logs | `e6a505a4-9f0b-4755-b234-13243240da23` | read | rolling event log, format undecoded |
| Sub-firmware version | `9a5f99ef-4370-4e87-a073-7769cd8dd35c` | read | string, e.g. `"1.58"` |
| Unknown `51B9` | `51b91d16-ff96-459d-aa02-0895044be049` | read / notify | always `0x00` observed |
| Unknown `1A7F` | `1a7f1561-ae85-43a6-956f-a90ede82f623` | read / write | constant `0x0000003C` (60) — possibly a timeout in seconds |
| Unknown `8AB5` | `8ab57bec-d4d2-4d5a-bd55-2f89f5949823` | read / write | constant `0x00000005` (5) — matches `pumpH`/`pumpC` fields in the device config JSON |

---

## 9. Device Config Service — `dc5e0473-d2ec-4f23-9b61-cd7bae046f76`

| Characteristic | UUID | Properties | Notes |
|---|---|---|---|
| Serial Number | `136e24c6-c486-4a74-bb0a-d18b985970a6` | read | zero-padded string |
| Device Config JSON | `a397436e-0927-4029-8ea4-7368c2f08d09` | read | calibration JSON blob (fields include `hi`, `lo`, `deltaH`, `deltaC`, `pumpH`, `pumpC`) |
| Max Temp | `adffd248-9588-427e-a226-aeb96c340be7` | read | matches config JSON `"hi"` (typically 120) |
| Min Temp | `cfcea17c-f46d-491f-94a3-aae40daac395` | read | matches config JSON `"lo"` (typically 51–53) |
| Delta Heat | `3a59cb22-9332-435d-b3b4-74e63477958c` | read | matches config JSON `"deltaH"` |
| Delta Cool | `be83c9a6-462d-43b7-9528-28a87865e565` | read | matches config JSON `"deltaC"` |
| Config Write | `87c9fb8d-f243-4412-98cf-cc0c97b3d106` | write-only | purpose undecoded |

---

## 10. OTA / Vestigial Service — `4d44eb61-87dd-402c-ad4c-41928e08c8eb`

| Characteristic | UUID | Properties | Notes |
|---|---|---|---|
| Unknown `2AAA` | `00002aaa-0000-1000-8000-00805f9b34fb` | read / write / notify | This is the standard BLE **Central Address Resolution** characteristic UUID, repurposed (or left over/vestigial) here; always observed as `0x0000` |

---

## 11. Recommended Connection & Polling Sequence

This is the sequence used by `ooler_ble_client`, and represents a reasonable reference implementation for any new client.

1. **Scan** for a device named `OOLER`.
2. **Connect** (GATT connect + service discovery; a services cache is recommended to speed up reconnects).
3. **Read** `Display Temperature Unit` once and cache it client-side (needed to interpret/convert `Set Temperature` and `Actual Temperature` correctly).
4. **Poll once**: read Power, Mode, Set Temp, Actual Temp, Water Level, Clean.
5. **Subscribe (notify)** to the 4 highest-churn characteristics: Power, Mode, Set Temp, Actual Temp.
   - Water Level and Clean are intentionally **not** subscribed in practice — they're re-read via periodic polling instead, to conserve notification slots on constrained BLE-proxy hardware (see §11.1).
6. On any write (power/mode/temp/clean), use **write-with-response** so failures are observable, and apply a **two-tier retry**: retry the write once immediately, and if that also fails, force a full disconnect/reconnect and retry once more.
7. Periodically re-poll and compare fresh reads against the last known notify-pushed values for Power/Mode/Set Temp/Actual Temp. A mismatch means a notification was silently dropped; recover by (a) re-subscribing in place, and if that doesn't clear the mismatch on the next poll, (b) forcing a full reconnect.

### 11.1 ESP32 BLE proxy constraint (if bridging via ESPHome)

- ESP32 BLE proxies (ESPHome) impose a **global limit of 12 notification registrations** across all connected devices. This library's design of 4 notify subscriptions per Ooler leaves headroom for other BLE peripherals sharing the same proxy.
- ESP32 proxies also support only **3 simultaneous BLE connections** by default — a real constraint if you're running multiple Ooler units or other BLE devices through one proxy.

### 11.2 Handled/expected transient errors

Both client libraries treat these as recoverable and retry rather than failing hard: `BleakError` (generic), `EOFError`, `BrokenPipeError`, `asyncio.TimeoutError`, and the specific message `"Bluetooth is already shutdown"` (seen during proxy blips, worth a longer ~20s backoff since the underlying blip tends to last ~15s).

---

## 12. Quick Reference — All Characteristic UUIDs

```
# Device Information (standard, 0000180a-...)
00002a29-0000-1000-8000-00805f9b34fb  Manufacturer Name        read
00002a26-0000-1000-8000-00805f9b34fb  Firmware Revision        read
00002a24-0000-1000-8000-00805f9b34fb  Model Number             read
00002a00-0000-1000-8000-00805f9b34fb  Device Name              read
a2b8f087-c75f-4646-a97a-22db6b748c94  Hardware ID              read

# Main Control (5c293993-d039-4225-92f6-31fa62101e96)
7a2623ff-bd92-4c13-be9f-7023aa4ecb85  Power                    read/write/notify
cafe2421-d04c-458f-b1c0-253c6c97e8e8  Mode                     read/write/notify
6aa46711-a29d-4f8a-88e2-044ca1fd03ff  Set Temperature (F)      read/write/notify
e8ebded3-9dca-45c2-a2d8-ceffb901474d  Actual Temperature       read/notify
8db5b9db-dbf6-47e6-a9dd-0612a1349a5b  Water Level              read/notify
e9bf509a-b1c5-4243-9514-352ad2d851f6  Clean                    read/write/notify
2c988613-fe15-4067-85bc-8e59d5e0b1e3  Display Temp Unit        read/write
fdff37ff-901d-40c6-b7e0-dd5797bd2989  Thermal Effort           read/notify
5a914d86-9b5e-4a35-ad3d-3e5936d485b2  Pump Level               read/notify
acab07ec-fc95-451d-88e5-4565a364a806  Power Rail               read/notify
654b8162-7090-4084-8d94-4eb33e917e9c  Relative Humidity        read/notify
7c0ea228-2616-4765-a726-beb5f4a0fa71  Ambient Temperature (F)  read/notify
f30d875a-7297-43ac-9f5b-1d7eed4446eb  Unknown (F30D)           read/notify
923445f2-9438-4d81-98c9-904b69b94eca  Unknown (9234)           read/notify
af8d892b-693d-495d-ac95-eb849a5ac40c  Unknown (AF8D)           read/notify
7aa73db1-1c2d-4c8c-9195-36c0a4b6acb2  Warm Wake Enabled(?)     unconfirmed (mqtt-bridge only)

# Write-only Command (1d14d6ee-fd63-4fa1-bfa4-8f47b42119f0)
f7bf3564-fb6d-4e53-88a4-5e37e0326063  Unknown command          write-only

# Command/Response (4bf69dcd-412d-494c-9348-f2f364e5c6ce)
abf9e9a9-058c-46d3-9570-1782d0fd1d5d  Command                  write-only
8b56f100-bed3-4858-89d0-eef0da6168fd  Response                 indicate

# Current Time (standard, 00001805-...)
00002a2b-0000-1000-8000-00805f9b34fb  Current Time             read/write/notify
00002a0f-0000-1000-8000-00805f9b34fb  Local Time Info          read/write
00002a14-0000-1000-8000-00805f9b34fb  Reference Time Info      read

# Diagnostics (28dfbeff-61e0-4aa2-9eea-ede0b86f3f65)
5d30781f-1d06-4790-bbb8-5e1d7da96383  Lifetime Counter         read
1a5c6dae-34de-4265-9fa6-0a59f7f683ee  Runtime Counter          read
0ab6ff00-8d1b-475e-bcfa-ed3467f1f890  UV Runtime Counter       read
e6a505a4-9f0b-4755-b234-13243240da23  Device Logs              read
9a5f99ef-4370-4e87-a073-7769cd8dd35c  Sub-firmware Version     read
51b91d16-ff96-459d-aa02-0895044be049  Unknown (51B9)           read/notify
1a7f1561-ae85-43a6-956f-a90ede82f623  Unknown (1A7F)           read/write
8ab57bec-d4d2-4d5a-bd55-2f89f5949823  Unknown (8AB5)           read/write

# Device Config (dc5e0473-d2ec-4f23-9b61-cd7bae046f76)
136e24c6-c486-4a74-bb0a-d18b985970a6  Serial Number            read
a397436e-0927-4029-8ea4-7368c2f08d09  Device Config JSON       read
adffd248-9588-427e-a226-aeb96c340be7  Max Temp                 read
cfcea17c-f46d-491f-94a3-aae40daac395  Min Temp                 read
3a59cb22-9332-435d-b3b4-74e63477958c  Delta Heat               read
be83c9a6-462d-43b7-9528-28a87865e565  Delta Cool               read
87c9fb8d-f243-4412-98cf-cc0c97b3d106  Config Write             write-only

# Sleep Schedule (b430cd72-3a7f-4720-86fd-66ae8f6f3493)
8cb4ec90-cd94-4f69-b963-5473fbd94ec8  Schedule Header (seq)    read/write
8cb4ec90-cd94-4f69-b963-5473fbd94ea9  Schedule Times           read/write
fa242bc0-bf85-41f7-8dbb-53ba2e8b0895  Schedule Temps           read/write
fa242bc0-bf85-41f7-8dbb-53ba2e8b08a3  Schedule Meta            read-only

# OTA / Vestigial (4d44eb61-87dd-402c-ad4c-41928e08c8eb)
00002aaa-0000-1000-8000-00805f9b34fb  Unknown (2AAA)           read/write/notify
```

---

## 13. Open Questions for Further Reverse Engineering

- Exact payload format for the Command/Response indicate pair (§7).
- Purpose of the write-only characteristic on the dedicated command service (§7).
- Confirming whether `7aa73db1-...` (`WARM_WAKE_ENABLED` in mqtt-bridge) is real and, if so, how it interacts with the schedule-embedded warm-wake events described in §6.3 — it may be an artifact of an older firmware/app version.
- Exact semantics of Thermal Effort, Pump Level, and Power Rail units (likely telemetry/diagnostic values, not something a control client needs to write).
- Device Logs entry format (§8).

---

*This document is an unofficial technical reference derived from open-source reverse-engineering projects. Chili Sleep / Ooler have not published an official protocol specification as of this writing. Verify against your own device/firmware before relying on this for production control software.*
