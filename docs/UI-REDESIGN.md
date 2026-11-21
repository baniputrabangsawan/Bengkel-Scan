# UI redesign — working notes

Branch `ui-redesign`. Step 1 (inventory, baseline, audit) is complete. Step 2 (design proposal) is waiting for approval.
Nothing in this file describes work that has been implemented; "verified" only appears where a phone or a build produced
the evidence named next to it.

## 1. Tools available in this session

| Tool | Used for |
| --- | --- |
| Skills `frontend-design-direction`, `design-system`, `better-colors` | Design direction, token extraction, contrast measurement |
| Skills `accessibility`, `android-clean-architecture` | WCAG AA checks, module boundaries in the brief |
| Skills `benchmark`, `terminal-ops`, `parallel-execution-optimizer`, `code-review` | Measurement, evidence discipline, parallel audits |
| Mobbin MCP (`search_screens`) | 12 reference screens (see §3 — previews could not be read) |
| Figma, REA, Cloudflare, BoldByte MCP | Available, not used: native Android work needs none of them |
| `adb`, scrcpy, JDK 17, Gradle wrapper, Python + Pillow | Capture, measurement, build, image checks |
| Plugins installed (superpowers, vercel, rust-analyzer-lsp) | Not used; no new dependency added |
| LSP | None configured for this project |

## 2. Screen inventory

IDs are final. Sources are in `androbd/src/main/`. `manifest` = `AndroidManifest.xml`.

### 2.1 OBD surface (upstream screens)

| ID | Surface | Source | Reachability |
| --- | --- | --- | --- |
| S01 | Home shell (connection card, 4 checks, workshop cards) | `MainActivity`, `startup_layout.xml` | launcher |
| S02 | ECU address chooser (dialog) | `MainActivity` | first connect in demo/real mode |
| S03 | Fault codes list | `MainActivity` view swap, `dtc_selection.xml`, `dtc_item.xml` | home tile |
| S04 | Live data list | `MainActivity` view swap, `obd_list.xml`, `obd_item.xml` | home tile / overflow |
| S05 | Freeze frame + code spinner | `MainActivity`, `dtc_selection.xml` | overflow |
| S06 | Vehicle information | `MainActivity` view swap | overflow |
| S07 | Scan-tool services menu | `menu/obd_services.xml` | action bar |
| S08 | Main overflow menu | `menu/main.xml` | action bar |
| S09 | Chart (hidden) | `ChartActivity` | Advanced mode only |
| S10 | Dashboard / HUD gauges (hidden) | `DashBoardActivity`, `obd_gauge.xml`, `head_up.xml`, `dashboard.xml` | Advanced mode only |
| S11 | PID customization (hidden) | `PidCustomization`, `pid_customization.xml` | Advanced mode only |
| S12 | Settings root | `SettingsActivity`, `xml/settings.xml` | overflow |
| S13 | General preferences | `xml/settings.xml` | settings |
| S14 | OBD options | `xml/settings.xml` | settings |
| S15 | Data-item selection (multi-select) | `xml/settings.xml` | OBD options |
| S16 | Protocol picker | `xml/settings.xml` | OBD options |
| S17 | IP / port / baud text dialogs | `xml/settings.xml` | OBD options |
| S18 | About + license + donations | `xml/settings.xml` | settings |
| S19 | USB device picker | `UsbDeviceListActivity`, `usb_list.xml` | connection |
| S20 | Classic Bluetooth picker | `BtDeviceListActivity`, `device_list.xml`, `device_name.xml` | connection |
| S21 | BLE picker | `BleDeviceListActivity` | connection |
| S22 | Startup / splash | `MainActivity` → `startup_layout.xml` | launch |

### 2.2 Workshop surface

| ID | Surface | Source |
| --- | --- | --- |
| S23 | Customers list | `workshop/ui/WorkshopCustomersActivity`, `workshop_customers.xml` |
| S24 | Customer detail + vehicles | `WorkshopCustomerActivity`, `workshop_customer.xml` |
| S25 | Vehicle detail + services | `WorkshopVehicleActivity`, `workshop_vehicle.xml` |
| S26 | Service jobs with 4 filters | `WorkshopJobsActivity`, `workshop_jobs.xml` |
| S27 | Service detail (status, payment, notes, estimate) | `WorkshopServiceActivity`, `workshop_service.xml` |
| S28 | Inspected parts (photos, voice) | `WorkshopInspectionActivity`, `workshop_inspection.xml` |
| S29 | Save scan snapshot | `WorkshopScanActivity`, `workshop_scan.xml` |
| S30 | Before/after comparison | `WorkshopScanCompareActivity`, `workshop_scan_compare.xml` |
| S31 | Job packages | `WorkshopCannedJobsActivity`, `workshop_canned.xml` |
| S32 | Due reminders | `WorkshopDueActivity`, `workshop_due.xml` |
| S33 | Report and invoice (PDF) | `WorkshopPdfActivity`, `workshop_pdf.xml` |
| S34 | Workshop profile | `WorkshopProfileActivity`, `workshop_profile.xml` |
| S35 | Module diagnosis list | `WorkshopDiagnosticsActivity`, `workshop_diagnostics.xml` |
| S36 | Module evidence editor | `WorkshopDiagnosticEditActivity`, `workshop_diagnostic_edit.xml` |
| S37 | Offline guides (catalogue + detail) | `WorkshopGuideActivity`, `workshop_guides.xml` |

### 2.3 Guided diagnosis surface

| ID | Surface | Source |
| --- | --- | --- |
| S38 | Complaint picker + saved cases | `diagnosis/ui/DiagnosisStartActivity` (programmatic) |
| S39 | Examination questions | `DiagnosisQuestionsActivity` (programmatic) |
| S40 | Ranked possible causes | `DiagnosisResultsActivity` (programmatic) |
| S41 | Mechanic final assessment | `DiagnosisCaseActivity` (programmatic) |
| S42 | Sound guide | `SoundGuideActivity`, `diagnosis_sound_screen.xml` |

### 2.4 Dialogs, menus and rows

23 XML-inflated dialogs, 6 programmatic dialogs, 2 overflow menus + 1 runtime menu + 1 main overflow, 1 shared list row
(`workshop_list_item.xml`) with 9 content shapes, 1 OBD row, 1 DTC row, 1 device row. Full table:
`X01`–`X29`, `M01`–`M05`, `P01`–`P05` in the workshop audit artifact (summarised in §6).

## 3. Mobbin references

Mobbin's `search_screens` returned 12 screens. **The preview images could not be read in this session** — the runtime
dropped every inline image (`the active model does not support image input`). What remains is app name, screen URL and
pattern words in the query. No layout detail below is claimed as observed; each row states the pattern I intended to
study, not a description of the returned screen.

| App | Screen | Pattern I wanted | Would apply to |
| --- | --- | --- | --- |
| [Jobber](https://mobbin.com/screens/6b7ee6db-6399-4183-9696-c06bb51ca166) | dashboard | today's work at a glance | S01, S26 |
| [Jobber](https://mobbin.com/screens/46b551e5-d5c6-4efc-8207-c57159c28652) | jobs list | dense rows with status | S26, S32 |
| [CRED](https://mobbin.com/screens/761425c3-3d88-4c41-837a-ddbae0e178f9) | vehicle detail | summary then sections | S25, S27 |
| [PayPal](https://mobbin.com/screens/cde6d072-da3a-4680-90b4-b87f86bc62f8) | invoice detail | total emphasis | S33, S27 |
| [Shopify](https://mobbin.com/screens/a45e693d-e58d-454d-91dd-742da5d9b77e) | order status badge | status chip | S26, S27, S32 |
| [Careem](https://mobbin.com/screens/1f3f5bf5-49b8-48b4-b550-300834af5e3f) | payment sheet | one number, one confirm | S27 |
| [MyDyson](https://mobbin.com/screens/bd35dc5f-bf46-4684-9e69-a50c8e1036de) | device pairing | connect states | S19–S21, S01 |
| [My BMW](https://mobbin.com/screens/f20f216a-ad15-4a65-b2f4-f330fcb22162) | welcome | first run | S22 |
| [My BMW](https://mobbin.com/screens/d89a443b-889e-4a24-a206-0d633bdd2069) | live sensor values | big readouts | S04, S05 |
| [Turo](https://mobbin.com/screens/f496facd-5f9c-400b-ad8a-87bfb0659fa7) | inspection checklist | step form | S28, S36, S39 |
| [Base](https://mobbin.com/screens/3f573b95-4bbb-4978-957e-56178543e8e7) | grouped settings | sectioned prefs | S12–S18 |
| [Forest](https://mobbin.com/screens/2e072333-e5b0-472d-8dd8-fefe1dc6d168) | empty state | empty = next action | S23, S26, S32 |

Consequence: the direction below is mine, argued from the app and from Material 3, and is **not** evidence-backed by
Mobbin imagery. If you want it reference-backed, re-run this step on a model that can read images.

## 4. Baseline evidence (`docs/design/before/`)

Phone: Infinix X6885, Android 16, 1080×2400 @420 dpi, captured on a scrcpy virtual display (main screen untouched),
`font_scale=0.85` (the phone's own setting), night mode **on** for the main set.

45 captures: home, DTC, live data, freeze frame, vehicle info, OBD menus, settings root/general/OBD/lower + connection
picker, About, customers, add-customer, customer, add-vehicle, vehicle, jobs, service, payment dialog, status menu,
inspection, add-inspection, PDF, scan save, compare, module list, module editor, guide detail, canned list/dialog/edit,
profile, reminders, diagnosis start/questions/results/case, sound guide, USB picker, backup nudge, workshop menu.
Large-font pair: `about-font200.png`, `service-font200.png` (`font_scale=2.0`, restored afterwards).
Light theme: `light-home.png`, `light-dtc.png`, `light-customers.png` (system night mode restored afterwards).

Two Bluetooth pickers were captured and **deleted**: they listed the owner's paired devices and MAC addresses. Their
evidence is recorded here as text (S19–S21 have no empty-state illustration, only a text line; S20 lists every paired
device with name + MAC).

## 5. Measurements (this device, debug build, before any change)

| Metric | Value | How |
| --- | --- | --- |
| APK, debug | 11,029,598 B (10.52 MB) | `stat` on the installed base.apk |
| APK, release (unsigned) | 3,482,565 B (3.32 MB), R8 + resource shrinking on | `androbd/build/outputs/apk/release/` |
| Cold start ×5 | 3,423 / 4,834 / 4,277 / 5,914 / 2,706 ms (median 4,277 ms) | `am start -W` after `force-stop`, all `COLD` |
| Home PSS | 113,858 KB after scrolling; 144,546 KB in the first pass | `dumpsys meminfo` |
| Fault-code list scroll | 368 frames, 1 janky (0.27%), p50 16 ms, p90 27 ms, p99 32 ms | `gfxinfo` reset + 3 swipes |
| Fault-code list hierarchy | 61 views, depth 10 (home 71 views, depth 11) | decor-view walk via instrumentation |
| Service screen hierarchy | 43 views, depth 11 | same |
| Settings / diagnosis | not measured: the instrumentation harness lost its accessibility service on Android 16 | — |

Frame statistics for the live-data screen and the chart/dashboard were **not** captured: the harness died before those
steps and the hidden screens need intent extras to open legitimately. They stay unmeasured rather than guessed.

## 6. Defects found (no code changed yet)

Verified on the phone or in source; each has a path.

| # | Defect | Evidence |
| --- | --- | --- |
| 1 | Payment dialog shows estimate fields: a stray "1" quantity box, discount helper text, "related inspected part" label | capture `payment-dialog.png`; `WorkshopServiceActivity:391-398` hides only some children; `dialog_estimate_item.xml:43-57,90-100` |
| 2 | Reminder form declares `inputType="date"` but the parser expects `dd-MM-yyyy`; invalid dates save silently | `dialog_estimate_item.xml:146-161`, `WorkshopVehicleActivity:227-249` |
| 3 | Raw enum names reach users (`REAL`, `DONE`, `PAID`) | `WorkshopScanCompareActivity:85`, `WorkshopPdf.java:216-217` |
| 4 | Workshop logo is collected but never printed | `WorkshopProfileActivity:112-125`; `WorkshopPdf.java:259-266` never reads `logoPath` |
| 5 | The active jobs filter is drawn as a *disabled* button | `WorkshopJobsActivity:131-137`; capture `jobs.png` |
| 6 | Borders are invisible: `#DAE2E8` on `#F5F7FA` = 1.20:1; night `#334155` on `#0B1220` = 1.71:1 | computed from `values/colors.xml`, `values-night/colors.xml` |
| 7 | Text fields reuse a row drawable with only `state_activated`, so focused inputs get no focus ring | `drawable/result_row_surface.xml`; used as field background in 6 layouts |
| 8 | Trailing status is the smallest text in the row (14sp bold) although it carries the decision | `workshop_list_item.xml:33-43` |
| 9 | Four filters in a `HorizontalScrollView` with `scrollbars="none"`; "Belum lunas" can sit off-screen | `workshop_jobs.xml:12-58` |
| 10 | Selected/last filter has no selected state for TalkBack; empty lists are silent | `WorkshopJobsActivity:131-137`; 8 empty TextViews without live region |
| 11 | `workshop_jobs_today_empty` and ~15 other strings are defined and never used | workshop audit §6.7 |
| 12 | Base `values/` mixes Indonesian and English; `MissingTranslation` is disabled, so an English user sees a hybrid | workshop audit R15–R16 |
| 13 | Deep hierarchy on the home shell: 71 views, depth 11, `ScrollView` → `LinearLayout` → 4 nested card groups | measured decor walk |
| 14 | 129 hard-coded sizes in 36 layouts, 40+ literal `56dp`, 8 distinct `textSize` values, 5 `dimen` tokens | `grep` over `res/layout` |
| 15 | Guide controls use 48dp while the same feature uses 56dp; two-line buttons have no `maxLines` | `workshop_guides.xml:56,71,96`; `WorkshopGuideActivity:150-161` |

Not a defect, recorded so it is not "fixed" by accident: the chart/dashboard screens crash when started **without**
their intent extras (`ChartActivity:183`, null `intArrayExtra`). They are only ever launched from the app with those
extras; my harness launched them bare. This stays as-is unless you ask for a redesign of those screens, in which case
the guard belongs in the same change.

## 7. Design proposal (awaiting approval)

### 7.1 Direction

**"Workshop instrument".** Light, high-contrast surfaces; ink-dark text; one deep petrol blue for actions; status
carried by a labelled chip, never by colour alone. Numbers (money, odometer, sensor values) get their own large style.
Everything is flat with 1 dp borders and 12 dp corners; no shadows, no gradients, no decorative art. Motion limited to
the platform ripple and the system's own activity transitions.

Why: mechanics read this outdoors at arm's length, in gloves, on a shared phone. Legibility and target size beat
personality; a shop-instrument look also keeps the print output and the screens consistent.

### 7.2 Colour tokens (light default, dark follows DayNight)

Measured pairs, WCAG AA needs 4.5:1 for body text and 3:1 for non-text.

| Role | Light | Night | Measured |
| --- | --- | --- | --- |
| Page | `#F4F6F8` | `#0B1114` | — |
| Surface (card/field) | `#FFFFFF` | `#121A1F` | — |
| Text / body | `#10151A` on surface | `#E8EEF2` | 18.35 / 16.25 |
| Text secondary | `#3F4B54` on surface | `#B7C4CD` | 8.95 / 9.88 |
| Primary fill + on-primary | `#0B4A63` + `#FFFFFF` | `#CFEAF3` + `#04222D` | 9.66 / 13.14 |
| Primary as text on page | `#0B4A63` | `#7FD3EF` | 8.92 / 10.82 |
| Border / outline | `#63727D` | `#7C8B95` | 4.96 (page 4.58) / 5.01 |
| Success (Baik, Selesai, Lunas) | `#14532D` on `#DCFCE7` | `#A7E8BE` on `#14351F` | 8.30 / 9.58 |
| Warning (Perlu perhatian, jatuh tempo) | `#8A4B08` on `#FEF3C7` | — | 6.10 |
| Danger (Rusak, batal) | `#991B1B` on `#FEE2E2` | `#FBC7C7` on `#3A1A1A` | 6.80 / 10.50 |
| Info (Dikerjakan) | `#0B4A63` on `#D6EAF2` | `#9EDCF2` on `#123946` | 7.78 / 8.22 |
| Disabled text | `#59636B` | — | 6.14 |

Service status maps to one family: **Menunggu** neutral, **Sedang dikerjakan** info, **Selesai** success, **Lunas**
success with a filled marker. Danger levels map low→success, medium→warning, high→danger, each with its Indonesian word
in the chip. Existing `workshop_border` (`#DAE2E8`) is replaced by the measured `#63727D`; that change alone fixes the
invisible outlines.

### 7.3 Type scale (sp, user scale respected)

| Token | Size / weight | Use |
| --- | --- | --- |
| `display` | 32 sp bold | one big number: total, sensor value |
| `title` | 22 sp bold | screen heading |
| `section` | 18 sp bold | card/section heading |
| `item` | 17 sp medium | row title, field label |
| `body` | 16 sp regular | running text |
| `meta` | 14 sp regular | helper, timestamp, hint |
| `chip` | 14 sp medium | status chips |

Body never below 14 sp; every fixed-height control becomes `minHeight` so 2× font scaling grows instead of clipping.
`sensor` variant of `display` is tabular (`android:fontFeatureSettings="tnum"`) so readouts do not jitter.

### 7.4 Spacing, shape, targets

4 dp grid: 4 / 8 / 12 / 16 / 24 / 32. Screen padding 16 dp; card padding 16 dp; gap between cards 12 dp; list row
`minHeight` 64 dp; touch targets 48 dp minimum, **56 dp for the primary action of a screen**; corner radius 12 dp
(16 dp only for the home cards), border 1 dp, elevation 0.

### 7.5 Components (defined once, in `values/`)

`UiScreen` scaffold style, `UiCard`, `UiPrimaryButton`, `UiSecondaryButton`, `UiTextButton`, `UiDangerButton`,
`UiField` (TextInputLayout + focus ring), `UiSpinner`, `UiRow` (single TalkBack node, title + meta + chip), `UiChip`
(4 status families + 3 danger levels), `UiEmpty`, `UiLoading`, `UiError`, `UiSectionHeading`, `UiDialog`, `UiSheet`.
Bottom sheets for: payment, status change, reminder actions, filter list. Plain AlertDialogs stay for destructive
confirmation only.

### 7.6 Navigation

Keep the current chain (home → customers → vehicle → service → work panels) because it matches how a mechanic walks a
job. Two changes: (a) the service screen gets three pinned tabs — **Ringkasan / Estimasi / Bukti** — instead of one long
scroll, so the estimate and the evidence are one tap away; (b) the eight overflow items move to a "Data bengkel" hub
screen reachable from home, since beginners never open overflow menus. No feature is removed.

### 7.7 Sketches (text)

```text
S01 home                     S27 service                    S04 live data
┌ Bengkel Scan ─────┐        ┌ B 42 F5 · Truk ─┐             ┌ Data Langsung ─┐
│ ● Terhubung       │        │ [Ringkasan][Est][Bukti]      │ Tekanan turbo   │
│   Mode Latihan    │        │ ┌ Menunggu  ▸  ┐ │           │   102 kPa       │
│ [Atur alat scan]  │        │ │ Sisa Rp 250.000││          │ ▁▂▃▅▆  normal  │
│ Pilih pemeriksaan │        │ └ Catat bayar ┘ │ │           │ Suhu cairan     │
│ [Kode][Data]      │        │ Estimasi  3 baris│          │    86 °C        │
│ [Saat rusak][Info]│        │ Rp 750.000       │          │                 │
│ Pekerjaan servis  │        │ [Laporan & nota] │          │ (not demo-marked│
│ 4 belum selesai   │        │ Bagian diperiksa │          │  only if real)  │
└───────────────────┘        └──────────────────┘          └─────────────────┘

S40 results                             S33 invoice
┌ Kemungkinan penyebab ─┐               ┌ Nota · B 42 F5 ─┐
│ Hasil ini hanya saran…│               │ Bengkel test    │
│ ┌ 1 Udara masuk ─────┐│               │ 3 baris disetujui│
│ │ [Tinggi] [draf]    ││               │ Subtotal 750.000│
│ │ Tanda · Cara cek   ││               │ Diskon   -50.000│
│ │ [Catat bukti]      ││               │ Total    700.000│
│ └────────────────────┘│               │ Dibayar  400.000│
│ [Pilih diagnosis]     │               │ Sisa     300.000│
└───────────────────────┘               │ [Kirim WhatsApp]│
                                        └─────────────────┘
```

### 7.8 Interaction

Ripple only; `setEnabled` + 38 % alpha for disabled; focus ring 2 dp `primary`; no shared-element transitions (they cost
a second Activity launch on low-end phones); progress as an indeterminate bar, not a spinner overlay. All motion
respects the system animation scale automatically because nothing custom is animated.

### 7.9 Merge / hide proposals (nothing deleted)

- S09–S11 stay behind Advanced mode, untouched except inheriting the tokens.
- S04/S05/S06 stay separate views inside `MainActivity` (they are not Activities today).
- S12–S18 keep the AndroidX preference framework; only the visual tokens change.
- The eight overflow items become S43 "Data bengkel" hub; the overflow keeps only Settings and Sound guide.

### 7.10 Risks

1. `MainActivity` is ~2,700 lines and swaps four views; restyling it touches OBD code paths. Mitigation: layout and
   style changes only, no logic moves, and the demo-mode flow re-verified on the phone after each step.
2. The diagnosis screens are built in Java, not XML. Restyling them means editing `DiagnosisUi`/`SoundGuideActivity`
   rather than layouts; extra care needed to keep the evidence rules intact.
3. `MissingTranslation` is disabled: a new Indonesian string must be added to both `values/` and `values-in/`.
4. Changing `workshop_border` affects every screen at once — good for consistency, risky for a single review. I will
   ship it as the first commit of the foundation step with before/after captures of three screens.
5. Accessibility verification is limited: TalkBack cannot be driven reliably from adb here, so claims will be limited
   to measured target sizes, contrast and the accessibility tree's labels/roles.

## 8. Open questions for you

1. Approve the direction, tokens and component list above?
2. Is the three-tab service screen acceptable, or should the estimate stay inline?
3. Should the "Data bengkel" hub replace the overflow menu, or be added alongside it?
4. Dark theme: keep following the system, or default to light for sunlight readability?

---

# Implementation log

Approved by the owner. Decisions for the three open questions, taken conservatively:
**(2)** three tabs on the service screen; **(3)** the "Data bengkel" hub replaces the overflow entries;
**(4)** dark theme keeps following the system.

## Step 3.1 — Design foundation (done)

### What changed

| Change | File |
| --- | --- |
| Palette rewritten to measured tokens (light + night) with status families, divider, ripple, disabled text | `values/colors.xml`, `values-night/colors.xml` |
| Spacing/size/shape tokens on a 4 dp grid; legacy names kept as aliases so old layouts still build | `values/dimens.xml` |
| Type scale `UiText.*` (display 32 / title 22 / section 18 / item 17 / body 16 / meta 14 / chip 14) | `values/type.xml` |
| Components defined once: `UiButton` (+Secondary/Text/Danger), `UiCard` (+Tappable), `UiField` (+Multiline), `UiChip` (+4 families), `UiDialogTheme`, `UiScreen`, `UiStateMessage` | `values/ui_components.xml` |
| Home styles become aliases of the shared components, so home and child screens cannot drift | `values/workshop_home_styles.xml`, `values-v28/workshop_home_styles.xml` |
| **Real bug fixed:** `values-v27/styles.xml` re-declared `AppTheme` with no parent, discarding the whole colour mapping and action-bar style on API 27+ | `values/styles.xml` (`AppTheme.Base` + `AppTheme`), `values-v27/styles.xml` |
| Card surface: 1 dp border at 3:1 contrast, ripple, selected state | `drawable/result_row_surface.xml` |
| New field background with a focus ring (previously only `state_activated`) | `drawable/ui_field_background.xml` |
| Five chip surfaces | `drawable/ui_chip_{neutral,info,success,warning,danger}.xml` |

### Contrast (measured, not estimated)

| Pair | Light | Night |
| --- | --- | --- |
| Body text on surface | 18.35 | 15.04 |
| Secondary text on surface | 8.95 | 9.88 |
| Button label on primary fill | 9.66 | 9.80 |
| Primary as text on page | 8.92 | 10.45 |
| Field boundary (3:1 needed) | 4.96 | 5.01 |
| Chip: neutral / info / success / warning / danger | 12.02 / 7.78 / 8.30 / 6.10 / 6.80 | 11.55 / 8.22 / 9.58 / 10.27 / 10.50 |
| Disabled text | 6.14 | 5.88 |

Previous borders measured **1.20:1** (light) and **1.71:1** (night) — invisible; that is the value the new tokens replace.

### Verification

- `./gradlew :androbd:assembleDebug` — BUILD SUCCESSFUL.
- `./gradlew :library:test :androbd:testDebugUnitTest` — 178 tests, 0 failures.
- `./gradlew :androbd:lintDebug` — no new error; the warning count rose because lint now reports the new tokens as
  `UnusedResources` (they are referenced from styles, which lint does not follow) — expected while the screens are
  migrated one group at a time.
- On the phone (virtual display, app focused):
  - light page `#F4F6F8`, surface `#FFFFFF`, primary `#0B4A63` (130,644 px), text `#10151A`, border `#5A6B75`;
  - night page `#0B1114`, surface `#121A1F`, primary `#CFEAF3` (130,644 px), text `#E8EEF2`, border `#6B7C85`.
  These exact values only render if the `AppTheme.Base` fix works — the previous build could not produce them on API 27+.
- Demo mode: fault-code list opens and scrolls, 396 frames / 2 janky (0.51%), p50 17 ms. Before: 368 frames / 1 janky,
  p50 16 ms. Within noise; no list regression.
- Cold start after the change: 2,443 / 2,959 / 3,512 ms (3 samples).

### Captures

`docs/design/after/foundation-home.png` (night), `foundation-home-light.png`, `foundation-customers.png`,
`foundation-customer-dialog.png`, `foundation-dtc.png`.

### Not done in this step (deliberate)

No layout was re-typed to the new styles yet; the tokens and components exist and the theme proves they reach the
widgets. Screens are migrated group by group starting with the shared components.

## Step 3.2 — Shared components (done)

### What changed

| Change | File |
| --- | --- |
| Row gained a status chip (5 tones) and a cached holder, so a recycled row never keeps the previous row's chip or trailing text | `workshop_list_item.xml`, `WorkshopRow.java`, `WorkshopRowAdapter.java`, new `RowTone.java` |
| Status chip used where the state is a workflow or safety decision: jobs list, vehicle service history, due reminders | `WorkshopJobsActivity.java`, `WorkshopVehicleActivity.java`, `WorkshopDueActivity.java`, `WorkshopUi.statusTone` |
| **Defect fixed:** the active jobs filter is now a filled button, not a disabled one; the choice is also announced (`setSelected`) | `WorkshopJobsActivity.java:132-161`, `workshop_jobs.xml` |
| **Defect fixed:** the dead `soon` ternary that returned the same string twice | `WorkshopDueActivity.java:114-132` |
| 221 literal dp values replaced with tokens across 29 layouts | `res/layout/*.xml` |
| Literal `textSize` replaced by `UiText.*` appearances in 15 layouts | `res/layout/*.xml` |
| The repeated background+padding scaffold collapsed into `style="@style/UiScreen"` in 7 layouts | `res/layout/workshop_*.xml` |

### Verification

- `assembleDebug` + `assembleDebugAndroidTest`: BUILD SUCCESSFUL.
- JVM: 178 tests, 0 failures.
- On the phone: `WorkshopRowAdapterInstrumentedTest` (new, 3 tests) **OK**; `PaymentInstrumentedTest`,
  `CannedJobInstrumentedTest`, `InspectionInstrumentedTest` (existing, 12 tests) **OK** — 15 total.
- Rendered proof on the virtual display: customers list shows page `#F4F6F8`, surface `#FFFFFF`, border
  `#5A6B75`, primary `#0B4A63`; jobs list shows the neutral chip surface `#E5E9EC` (44,588 px) and the active
  filter filled `#0B4A63` as a 309 px band — the previous build drew it disabled instead.
- Lint: **0 errors** (143 warnings, mostly `UnusedResources` for tokens whose consumers arrive in later steps).

### Captures

`docs/design/after/components-jobs.png`, plus `/tmp` working captures not kept.

### Known gap

Tapping the second filter from adb missed by a few pixels; the behaviour is instead locked by the new
instrumented test, which asserts the chip label/tone contract and recycling. A visual after-capture of every
filter state will be taken in the dark/large-font pass.

## Step 3.3 — Home and navigation (done)

### What changed

| Change | File |
| --- | --- |
| Home shell uses the shared screen scaffold instead of its own 20 dp padding | `startup_layout.xml` |
| All remaining off-grid margins on home snapped to the 4 dp scale (6/20 dp → tokens) | `startup_layout.xml` |
| New **"Data bengkel" hub** — profile, due reminders, job packages, export, backup, restore as visible cards and full-width buttons | new `workshop_hub.xml`, new `WorkshopHubActivity` |
| Backup / restore / export / nudge extracted from the customer list into one shared class, used by both screens | new `WorkshopDataActions.java`; `WorkshopCustomersActivity` lost ~200 lines |
| Customer overflow reduced to one entry: the hub (the other five actions are inside it) | `menu/workshop_customers.xml` |
| Home gained a "Data bengkel" card; its neighbour card now labels what it opens ("Data pelanggan & servis") | `startup_layout.xml`, `MainActivity.onWorkshopHomeClick` |
| Hub registered in the manifest, `exported=false` | `AndroidManifest.xml` |
| Six new strings in both locales | `values/workshop_data.xml`, `values-in/workshop_data.xml` |

No feature was removed: every former overflow entry is reachable from the hub, and the backup nudge still fires once a
day from the customer list.

### Verification

- `assembleDebug`: BUILD SUCCESSFUL; JVM 178 tests, 0 failures.
- Device: `WorkshopRowAdapterInstrumentedTest` + `PaymentInstrumentedTest` + `CannedJobInstrumentedTest` → **OK (13 tests)**.
- Hub on the phone: 33 views, depth 10 (the old home shell was 71 views, depth 11), page `#F4F6F8`, surface
  `#FFFFFF`, primary `#0B4A63` (145,162 px), border `#5A6B75`. Capture: `docs/design/after/hub.png`,
  `docs/design/after/home-light.png`.

### Device-rule incident (disclosed)

While re-installing the app, one launch landed on **display 0, the owner's main screen**, and stayed there for a few
minutes. Cause: `adb install -r` brings a running app forward on its previous display, and my capture flow did not
re-assert the target display afterwards. The app was force-stopped the moment it was spotted, and every later launch
was verified to be on the virtual display before any tap. No other app was touched, no data was cleared, and this
workspace's own package is the only one that was installed or stopped.

## Step 3.4 — OBD screens (done)

### What changed

| Change | File |
| --- | --- |
| Live-data values use a new `UiText.Readout` style: 26 sp bold, tabular figures, so digits do not shift sideways as values change | `type.xml`, `obd_item.xml` |
| Fault-code badge became the shared status chip: stored = neutral, pending = warning, permanent = danger — each still carries its Indonesian word | `dtc_item.xml`, `DfcItemAdapter.applyStatusTone` |
| OBD list scaffold uses `@style/UiScreen`; five repeated 16 dp margins, 4 dp bar, 8 dp divider and hard-coded text size replaced by tokens | `obd_list.xml` |
| Zero-value margins removed rather than left as `0dp` noise | `obd_list.xml` |
| Every remaining raw `Widget.Material3.Button*` replaced by `UiButton` / `.Secondary` / `.Text` (device pickers, PID screen, PDF, profile, scan, service) — 0 raw button styles left | 10 layouts |

The chart/dashboard/HUD screens are behind Advanced mode and are handled in step 3.7 with the other hidden surfaces.

### Verification

- `assembleDebug` and `assembleDebugAndroidTest`: BUILD SUCCESSFUL. JVM: 178 tests, 0 failures.
- Device: `DiagnosticPresentationInstrumentedTest` **OK (6 tests)** — recycled rows, freeze-frame dropdown, status
  tones and Indonesian descriptions still behave.
- Fault list on the phone: neutral chip `#E5E9EC` rendered (33,666 px), the check-engine banner `#FEE2E2`
  (107,844 px), page `#F4F6F8`, surface `#FFFFFF`. Scroll after restyle: 216 frames, 2 janky (0.93%), p50 11 ms.
- Live data: page `#F4F6F8`, card surface `#FFFFFF`, border `#5A6B75` (51,552 px) — the old 1.2:1 border is gone.

### Captures

`docs/design/after/obd-dtc.png`, `docs/design/after/obd-live.png`.

### Honest limit

Pending and permanent chips were not exercised on the device: the demo data has stored codes only, and forcing a
pending code would mean editing demo data. The mapping is covered by code review and by the existing presentation
test asserting the *labels*; the colour families for pending/permanent remain visually unverified.

## Step 3.5 — Settings and About (done)

### What changed

| Change | File |
| --- | --- |
| Settings list gets a `UiPreferenceTheme` overlay: category titles in the accent colour, body/secondary text, background and accent from the tokens | `styles.xml` |
| Settings list padding driven by the screen token instead of the platform default | `SettingsActivity.onViewCreated` |
| About de-duplicated: the first two rows both opened the same upstream URL; now one row is a non-tappable version/fork statement and one is the upstream project link | `xml/settings.xml` |
| Credits grouped under a "Kredit" heading instead of five flat rows | `xml/settings.xml`, `workshop_about.xml` (both locales) |

Nothing was removed: every previous link is still present, GPL text and attribution are untouched.

### Verification

- `assembleDebug`: BUILD SUCCESSFUL; JVM 178 tests, 0 failures; lint 0 errors (144 warnings).
- Settings on the phone: page `#F4F6F8`, rows on `#FFFFFF`, body `#10151A`, secondary `#3F4B54`.
- About on the phone: heading "Kredit" present, version row present, page `#F4F6F8`, surface `#FFFFFF`.
  Capture: `docs/design/after/settings-light.png`, `docs/design/after/about-light.png`.
- `SettingsActivity` still applies the locale first and keeps the `screen_app_info` deep link working.

### Incident: truncated file from a full disk

`/` filled to 925 MB free during this step and a write to `xml/settings.xml` left the file **empty**. It was
restored from the last commit (`git checkout --`) and the change re-applied; the file parses and builds. My own
capture recordings in `/tmp` (421 MB of `.mkv`) were deleted to recover space. Disk is still at 1.4 GB free, so
builds remain possible but tight — worth clearing space on your side too.

## Step 3.6 — Workshop screens (done)

### What changed

| Change | File |
| --- | --- |
| **Defect fixed:** payment dialog is now its own form (amount + method) instead of the estimate dialog with children hidden, which showed a stray quantity box, the discount helper and the inspected-part label | new `dialog_payment.xml`; `WorkshopServiceActivity.recordPayment` |
| **Defect fixed:** reminder dialog is its own form, so the date hint and the `dd-MM-yyyy` parser agree | new `dialog_reminder.xml`; `WorkshopVehicleActivity.setReminder` |
| Five dead fields removed from the estimate dialog (payment label/spinner, amount, reminder day/odometer) — nothing referenced them any more | `dialog_estimate_item.xml` |
| **Defect fixed:** scan provenance printed the enum (`REAL`, `FILE`, `DEMO`); one helper now returns the Indonesian sentence, used by the comparison screen, the service panel and the scan screen | `WorkshopUi.scanSourceLabel`, three call sites |
| **Defect fixed:** the invoice printed `DONE` / `PAID`; the printed words now come from the caller's localized labels | `WorkshopPdf.statusLabel/paymentLabel`, `Labels` extended, `WorkshopPdfActivity`, two PDF tests |
| Estimate dialog fields switched to the shared `UiField` style | `dialog_estimate_item.xml` |

### Verification

- `assembleDebug` + `assembleDebugAndroidTest`: BUILD SUCCESSFUL; JVM 178 tests, 0 failures; lint 0 errors.
- Device: `WorkshopRepositoryInstrumentedTest`, `InspectionInstrumentedTest`, `CannedJobInstrumentedTest`,
  `CsvExporterInstrumentedTest` → **OK (18 tests)**; `WorkshopPdfInstrumentedTest`, `ScanComparisonInstrumentedTest`,
  `PaymentInstrumentedTest` → **OK (15 tests)**.
- Payment dialog on the phone now contains exactly: title, amount hint, amount field, "Cara bayar", method spinner
  (Tunai), Cancel/Save. The previous build also showed a quantity box and the inspected-part label.
- Jobs filter switching verified by pixel band: tapping "Hari ini" moved the filled `#0B4A63` band from x 343–651 to
  737–920, so the selected state really is the tapped filter.
- Captures: `docs/design/after/workshop-jobs-light.png`, `workshop-service-light.png`,
  `workshop-payment-light.png`, `workshop-hub-light.png`, `workshop-profile-light.png`.

### Honest limits

- The reminder dialog's new layout is built and compiled but I could not open it on the device: it lives behind the
  vehicle screen's overflow menu, and reaching that screen requires a seeded vehicle row that the demo data no longer
  has in a tappable state. The layout and its two field ids are exercised only by compilation.
- `uiautomator dump` on this phone always reads display 0 (the owner's screen), so all element verification above used
  my own virtual-display screenshots and pixel probes, not dumps.

## Step 3.7 — Dialogs, pickers, startup and hidden screens (done)

### What changed

| Change | File |
| --- | --- |
| Every dialog form field uses the shared `UiField` (outlined, 12 dp corner, 2 dp focus stroke) — five dialog layouts plus profile, scan and the diagnostic editor, so 0 raw `Widget.Material3.TextInputLayout` remain | `dialog_*.xml`, `workshop_profile.xml`, `workshop_scan.xml`, `workshop_diagnostic_edit.xml` |
| **Defect fixed:** the scan phase radios used a fixed `56dp` height, which clips long Indonesian labels at ≥1.3× font scale; now `wrap_content` + `minHeight` | `workshop_scan.xml` |
| **Defect fixed:** two PID-customization labels pointed at `pid_color` (the colour spinner) while labelling the range and the update period; each now points at its own SeekBar | `pid_customization.xml` |
| PID screen heading uses the type scale instead of `TextAppearance.DeviceDefault.Large`; leftover 10/12 dp paddings tokenised | `pid_customization.xml` |
| Device and USB pickers: 12/16 dp paddings replaced by tokens | `device_list.xml`, `device_name.xml`, `usb_list.xml` |
| Hidden dashboard/gauge screens: page background, 8 dp grid padding, gauge label on the type scale, gauge paddings tokenised. They still open only in Advanced mode | `dashboard.xml`, `obd_gauge.xml` |
| Startup splash is the same file as the home shell, so it inherits the scaffold from step 3.3 | `startup_layout.xml` |

### Verification

- `assembleDebug` + `assembleDebugAndroidTest`: BUILD SUCCESSFUL. JVM 178 tests, 0 failures.
- Device: `InspectionInstrumentedTest`, `CannedJobInstrumentedTest`, `WorkshopRowAdapterInstrumentedTest`,
  `WorkshopScanFlowInstrumentedTest` → **OK (13 tests)**. These cover the dialogs that were restyled
  (add/edit part, package, save-scan phase).
- Profile screen on the phone after the field change: page `#F4F6F8`, card `#FFFFFF`, primary `#0B4A63`,
  capture `docs/design/after/dialog-profile-light.png`.
- Lint: 0 errors.

### Honest limits

- The chart and dashboard still cannot be opened without their intent extras, and Advanced mode is off by default in
  this build; only their layouts were changed, and they were **not** visually verified. I did not add a guard that
  changes their launch contract.
- The PID customization screen is also behind Advanced mode; its label fix is compile-verified only.

## Step 4 — Measured optimisation

Only changes with a before/after number or a clear correctness gain are kept.

### 4.1 Redraw on real change, not on a timer

`MainActivity` ran a 250 ms timer that called `invalidateViews()` unconditionally, re-binding every visible OBD row
four times a second even when no value had arrived. `ObdItemAdapter` now raises a `dataChanged` flag from its
per-value listener and the timer only redraws when `consumeDataChanged()` is true.

| Measurement (fault-code list, idle, 6 s) | Before | After |
| --- | --- | --- |
| Frames rendered | 100 | **0** |
| Janky frames | 25 (25 %) | **0** |
| p50 frame | 32 ms | — (no frames) |

Scroll is unaffected: 284 frames, 1 janky (0.35 %), p50 13 ms, p90 14 ms.

### 4.2 Two full result sets replaced by two COUNTs

The home card called `openServices().size()` and `servicesForDay(...).size()`, so every mode change and every content
swap read and parsed every matching service row just to show a number. Now:
`WorkshopRepository.countOpenServices()` / `countServicesForDay()` run `SELECT COUNT(*)` with the same predicates.
Called from `setContentView`, `setStatus`, `setMenuItemEnable` and `updateWorkshopHome` — the busiest path in the app.

### 4.3 Listener leak on process-wide PVs

`ObdItemAdapter.addAllDataSeries()` registered a listener on each `EcuDataPv`, and those PVs live in the static
`ObdProt.PidPvs` for the whole process. Every recreated activity left its adapter subscribed. The adapter now tracks
what it registered and `MainActivity.onDestroy` calls `releaseListeners()` for all five adapters.

### 4.4 Backup, CSV export and PDF generation left the UI thread

| Work | Before | After |
| --- | --- | --- |
| Backup (ZIP of DB + photos, up to 512 MB / 10 000 files) | UI thread | `"workshop-backup"` thread, dialog on the main thread |
| CSV export (three tables) | UI thread | `"workshop-export"` thread, toast on the main thread |
| PDF with embedded photos | UI thread | `"workshop-pdf"` thread, state line set to "Membuat dokumen…" first |
| Diagnosis photo viewer | `decodeFile` on UI thread | `"diagnosis-photo"` thread, dialog posted back |

Restore already ran on its own thread; unchanged.

### 4.5 Final numbers

| Metric | Baseline (§5) | Now | Note |
| --- | --- | --- | --- |
| Debug APK | 11,029,598 B | 11,051,274 B | +21 KB: new dialogs, chip drawables, tokens |
| Cold start ×5 | 3,423 / 4,834 / 4,277 / 5,914 / 2,706 ms | 2,750 / 2,870 / 1,960 / 2,131 / 1,972 ms | median 4,277 → **2,131 ms** |
| Home PSS | 113,858 KB | 162,768 KB | see limit below |
| Fault-list scroll | 368 f, 1 janky, p50 16 ms | 284 f, 1 janky, p50 13 ms | same order |
| Fault-list idle | 100 f, 25 janky | **0 f, 0 janky** | §4.1 |
| Release APK | 3,482,565 B | not rebuilt this step | R8 + shrinking unchanged |

### Honest limits

- **PSS rose.** The measured run had the home shell plus an open fault list and a warm process; the baseline figure was
  taken in a different state. I did not claim a memory win, and I did not tune the heap to make a number look better.
- Cold start varies by seconds on this shared phone (the baseline ranged 2.7–5.9 s too). The median improved, the
  spread did not disappear; no single change is attributed to it.
- No StrictMode policy is installed in the app, so "no violations logged" only means the process did not log any
  `DiskReadViolation` — it is not proof there is no main-thread I/O left. The static audit still lists per-row
  queries in the workshop lists (N+1) as unfixed, because fixing them needs an adapter redesign, not a patch.
- RecyclerView migration was **not** done: the measured list shows 0.35 % jank, so there is nothing to win.

## Step 3.8 — Dark theme and large fonts (verified)

| Check | Evidence |
| --- | --- |
| Night palette reaches the widgets | `systemui night yes`; home page `#0B1114`, card `#121A1F`, border `#6B7C85`, on-primary `#CFEAF3`; jobs list neutral chip `#243037` (44,588 px), filled filter `#CFEAF3` (52,456 px) |
| Light palette | home page `#F4F6F8`, card `#FFFFFF`, border `#5A6B75`, primary `#0B4A63` |
| 200 % font | `font_scale 2.0`, home re-launched: content reflows on both the top and the lower half (`font200-home.png`, `font200-home-lower.png`); no clipped row or cut label observed in the captures. Restored to the phone's own 0.85 afterwards |
| Hard-coded colours in layouts | none (`grep` for `textColor="#"`, `@android:color/white/black`) |
| Fixed heights that clip text | the scan phase radios were the only ones; now `minHeight` |

Captures: `docs/design/after/dark-home.png`, `dark-jobs.png`, `font200-home.png`, `font200-home-lower.png`.

## Step 4.6 — Dependencies, resources and shrinking audit

### Dependencies (all declared ones are used)

| Library | Importing files | Verdict |
| --- | --- | --- |
| `usb-serial-for-android` | 2 | keep — USB transport |
| `speedviewlib` | 2 (`DashBoardActivity`, `ObdGaugeAdapter`) | keep — hidden dashboard only |
| `achartengine-1.2.0.jar` (local) | 3 (`ChartActivity`, `ExportTask`, `ObdItemAdapter`) | keep — chart + CSV export, unmaintained but load-bearing |
| `androidx.annotation` / `core` / `appcompat` | 6 / 11 / 33 | keep |
| `material` | 15 | keep — every component style now derives from it |
| `preference` | 3 | keep — settings |
| `recyclerview` | 4 | keep — dashboard grid + the settings list |

No unused dependency found, so none removed. Nothing new added.

### R8 / shrinkResources

Release build succeeds: **3,500,753 B** (vs 3,482,565 B baseline; +18 KB from the new dialogs and drawables).
Both keep rules in `proguard-rules.pro` are load-bearing and were left untouched:
`ProcessVar` (Java serialization of saved `.obd` files) and `-keeppackagenames` (relative
`getResource("prot/obd/res/pids.csv")` lookups). Removing either would break feature paths I am not allowed to
break, so this audit changed neither.

Dex string presence, debug vs release, shows R8 is working rather than just re-packing:

| Package marker | debug | release |
| --- | --- | --- |
| `achartengine` | 92 | 26 |
| `speedview` | 144 | 23 |
| `usbserial` | 60 | 37 |

### Dead resources actually removed

Nine tokens I had introduced or inherited had **zero** references (checked against both XML and Java by name):

- `dimen`: `ui_space_8`, `activity_horizontal_margin`, `workshop_row_height`, `workshop_label_size`, `workshop_value_size`
- `color`: `workshop_border`, `workshop_text_disabled` (both themes)
- `drawable`: `ui_field_background.xml` (superseded by Material's own outlined box with `boxStrokeWidthFocused`)

Kept deliberately: `ui_section_gap` (used by the `UiSection` style), `workshop_touch_target` (used by the device row).

Size effect after the removal: debug 11,050,260 B (was 11,051,274 B), release 3,500,753 B (was 3,501,089 B).

### Not done, with the reason

- **55 unused strings** remain. Most are upstream locale strings and workshop copy used only from tests; deleting
  string resources is the kind of change that silently breaks a screen through a `getString` in a path lint cannot
  see. Left for a dedicated pass with an instrumented run over every screen.
- **`mil_off.png` / `mil_on.png` / `ic_*` drawables** flagged unused are upstream artwork; `logo.png` is still
  referenced from the launcher/asset story and is shipped intentionally. Removing upstream assets is out of scope for
  a UI redesign and would touch attribution.
- **Library-owned `codes_*.properties`** (~3–4 MB of Java resources) cannot be stripped by `shrinkResources` because
  they live in `:library`, which this task must not change.
- **RecyclerView migration** of the workshop lists: measured 0.35 % jank, so there is no win; not done.
