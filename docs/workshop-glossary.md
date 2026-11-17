# Workshop Diagnostic Language

Canonical Indonesian terms for the AndrOBD workshop interface. Definitions and editorial guidance are in English; the bold terms are the approved user-facing wording.

## Connection and inspection

**Alat Scan**:
The diagnostic tool or ELM-compatible adapter connected to the vehicle. Keep this product noun even though the action verb is **Pindai**.
_Avoid_: Pemindai, alat pemindai, scanner

**Pindai**:
The scan action verb, such as searching for available devices. It does not mean connecting to a tool or confirming a failed component.
_Avoid_: Scan as an action label

**Hubungkan Alat Scan**:
The action that establishes communication with the diagnostic tool.
_Avoid_: Hubungkan without an object, Pindai for connecting

**Mode Latihan**:
The demonstration mode that uses simulated data rather than measurements from a connected vehicle.
_Avoid_: Treating demonstration readings as actual inspection results

## Fault diagnosis

**Kode Kerusakan**:
A diagnostic trouble code (DTC), which is a clue for diagnosis rather than proof that a particular component has failed. Keep the code itself unchanged.
_Avoid_: Kode kesalahan, kode masalah, DFC in user-facing copy

**Kode Kerusakan Tertunda**:
A pending DTC: a detected fault that has not yet met the criteria for a confirmed code. “Tertunda” describes code status, not a repair waiting to be done.
_Avoid_: Kesalahan kode, perbaikan tertunda

**Kode Kerusakan Permanen**:
A permanent DTC retained by the ECU until its required verification conditions are met. “Permanen” does not mean the component can never be repaired.
_Avoid_: Promising that ordinary code clearing removes permanent codes

**Hapus Kode Kerusakan**:
The action that clears supported diagnostic information and resets the malfunction indicator lamp (MIL). Clearing can also remove freeze-frame data, test results, and monitoring history; it does not repair the cause of the fault.
_Avoid_: Perbaiki kerusakan, reset mesin

**Uji Aktuator OBD**:
An OBD-commanded control test that can change vehicle behavior. Keep the full safety warning, including vehicle movement, exhaust smoke and heat, engine noise, fuel vapor, and the requirement for a safe test environment.
_Avoid_: Language suggesting that the test is harmless or performs a repair

## Diagnostic data

**Data Langsung**:
Current sensor readings and ECU values obtained during communication with the vehicle. Interpret readings against the vehicle specifications and operating conditions.
_Avoid_: Data OBD as the specific live-data screen title

**Data Saat Kerusakan**:
Freeze-frame data: recorded vehicle conditions associated with a detected fault. This is not a screenshot and is not a current live reading.
_Avoid_: Bingkai pembekuan, bekukan data bingkai, Tangkapan Layar

**Parameter Data**:
An individual diagnostic reading or value selected for display, such as engine speed or coolant temperature.
_Avoid_: Item data

**Grafik**:
A chart of diagnostic readings over time.
_Avoid_: Bagan for a time-series chart

**Tekanan MAP (Absolut)**:
Absolute intake-manifold pressure, referenced to a vacuum. MAP is not interchangeable with turbo boost pressure.
_Avoid_: Tekanan Turbo as a translation of MAP

**Tekanan Turbo**:
A turbo/boost pressure reading whose absolute or gauge reference must follow the parameter definition and vehicle specification. It must remain distinct from **Tekanan MAP (Absolut)**.
_Avoid_: Assuming that every boost reading is gauge pressure, relabeling MAP as turbo pressure

## Saved inspection results

**Simpan Hasil Pemeriksaan**:
The action that saves diagnostic measurements for later use.
_Avoid_: Hemat pengukuran, simpan pengukuran

**Buka Hasil Pemeriksaan**:
The action that opens previously saved diagnostic measurements.
_Avoid_: Pengukuran beban, beban pengukuran

**Tangkapan Layar**:
An image captured from the display. Keep this separate from ECU freeze-frame data.
_Avoid_: Foto, Data Saat Kerusakan

## Workshop visibility

`WorkshopFeatures.java` contains build-time switches, never user preferences:

- `SHOW_ADVANCED_FEATURES`: charts, gauges, HUD, customization, legacy recording menus, and advanced settings. Hidden by default. Existing data and protocol implementations remain.
- `SHOW_ACTUATOR_TEST_CONTROLS`: actuator UI, independent of advanced displays. Hidden by default; do not enable for normal workshop use.
- `SHOW_CLEAR_FAULT_CODES`: hidden until service-linked audit logging is available.

Hidden displays are also blocked when restoring old UI selections. DTC long-press web search remains available and needs an internet connection. Database/service recording is not implemented by this menu cleanup.

## Placeholder branding

Rename the app only through `app_name` in `res/values/strings.xml`.
The active vector placeholders retain the resource names `logo`, `ic_launcher`,
`ic_launcher_background`, `ic_launcher_foreground`, and `ic_launcher_monochrome`
in the `drawable-anydpi` and `mipmap-anydpi` directories. The existing adaptive
icon definition remains in `mipmap-anydpi-v26`. Replace these resources with
workshop artwork later. Original raster assets and upstream credits remain.

`AppTheme` still uses `Theme.Material3.DayNight`. Light and dark color resources
define readable surfaces; the Check Engine state uses a text banner rather than
a diagnostic-list background image. No measurement values or ECU requests change.
