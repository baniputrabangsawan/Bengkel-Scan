# Progress Bengkel Scan

Sumber kebenaran status fitur. Jika konteks gagal, baca file ini lalu `docs/ARCHITECTURE.md`, dan lanjutkan dari
item pertama yang belum selesai. Status hanya dinaikkan setelah build, tes, dan pemeriksaan di ponsel nyata.

Legenda: `[ ]` belum, `[~]` sebagian, `[x]` selesai.

## Status awal (audit 2026-11-20, HEAD `a5bd68c3`)

Repo sudah memuat lebih banyak daripada yang diasumsikan daftar fitur. Yang sudah ada dipakai ulang, bukan ditulis
ulang.

| # | Fitur | Status awal | Catatan |
| --- | --- | --- | --- |
| F1 | Lapisan database | `[~]` | `WorkshopDatabase` v3: customers, vehicles, services, estimate_items, diagnostics, diagnosis_cases/checks/photos. Belum ada `inspection_items`, `photos`, `scan_snapshots`, `canned_jobs`, `reminders`, `audit_log`, `settings`, soft-delete. |
| F2 | Profil & pengaturan bengkel | `[ ]` | Belum ada nama/alamat/telepon bengkel, footer PDF, format mata uang. |
| F3 | Pelanggan & kendaraan | `[x]` | Tambah/ubah/cari, validasi plat longgar (`normalizePlate`), riwayat servis per kendaraan. |
| F4 | Servis & status | `[~]` | `WAITING→IN_PROGRESS→DONE` + `UNPAID/PAID`. Belum `Lunas`, belum kembali mundur dengan aman, belum daftar "hari ini"/terbuka. |
| F5 | Pindai OBD terkait servis | `[ ]` | Belum ada snapshot, belum ada sebelum/sesudah, belum ada audit hapus kode. |
| F6 | Item pemeriksaan + foto | `[ ]` | Belum ada. |
| F7 | Estimasi | `[~]` | Tambah/hapus baris, total Rupiah. Belum diskon, belum ubah baris, belum tautan ke item pemeriksaan. |
| F8 | Paket pekerjaan | `[ ]` | Belum ada. |
| F9 | Persetujuan estimasi | `[ ]` | Belum ada status/riwayat persetujuan per baris. |
| F10 | PDF + WhatsApp | `[ ]` | Belum ada `PdfDocument`, belum ada nota. `provider_paths.xml` masih mengekspos `external-path .`. |
| F11 | Pembayaran | `[~]` | Hanya `PAID`/`UNPAID`. Belum sebagian, metode, tanggal, daftar belum lunas. |
| F12 | Diagnosis tanpa ECU | `[x]` | 16 keluhan, 65 sebab, skor aturan, validasi JSON, impor ekstra, kasus serupa. |
| F13 | Panduan bunyi | `[x]` | Pemutaran, impor, rekaman ≤15 s terkait kasus terkonfirmasi. |
| F14 | Catatan suara | `[ ]` | Belum ada. |
| F15 | Riwayat kasus | `[x]` | Hitungan kasus serupa dengan identitas kendaraan yang sama. |
| F16 | Pengingat servis | `[ ]` | Belum ada. |
| F17 | Cadangkan/pulihkan/ekspor | `[~]` | ZIP database+foto+audio dan pulihkan tervalidasi sudah ada. Belum CSV, belum pengingat cadangan. |
| F18 | Layar OBD lanjutan | `[~]` | `SHOW_ADVANCED_FEATURES` ada (default `false`), belum ada sakelar "Mode Lanjutan" di pengaturan. |
| F19 | Ukur & optimalkan | `[ ]` | Belum. |
| F20 | Audit akhir & rilis | `[ ]` | Belum. |

## Rencana yang menunggu persetujuan

Struktur paket: `workshop/` tetap berisi model + repository + aktivitas, ditambah subpaket baru agar aktivitas tidak
membesar.

```
workshop/
├── WorkshopDatabase, WorkshopRepository, DiagnosticRepository      (yang ada, dipertahankan)
├── model/     model baru: ScanSnapshot, InspectionItem, PhotoRef, CannedJob, Reminder,
│              EstimateApproval, AuditEntry, WorkshopProfile                       (baru)
├── data/      ScanSnapshotRepository, InspectionRepository, CannedJobRepository,
│              ReminderRepository, AuditLogRepository, WorkshopSettingsRepository  (baru)
├── export/    CsvExporter                                                          (baru)
├── pdf/       PdfDocumentWriter, PdfReportBuilder, PdfInvoiceBuilder               (baru)
└── ui/        aktivitas yang ada + layar baru
diagnosis/     strukturnya tidak diubah; F6/F14 menuangkannya ke tabel bersama
```

Skema `workshop.db` naik **3 → 4** sekali saja saat F1 (satu `onUpgrade`, bukan migrasi per fitur):

| Tabel | Untuk | Kolom kunci |
| --- | --- | --- |
| `workshop_profile` | F2 | satu baris: `name`, `address`, `phone`, `footer`, `logo_path`, `updated_at` |
| `service_items` | F7/F9 | `service_id`, `kind`, `description`, `quantity`, `unit_price`, `discount`, `inspection_item_id`, `diagnosis_case_id`, `approval`, `position` |
| `inspection_items` | F6/F14 | `service_id`, `name`, `state` (BAIK/PERHATIAN/RUSAK), `note`, `voice_path`, `updated_at`, `deleted_at` |
| `photos` | F6 | `owner_kind` (INSPECTION/DIAGNOSIS/SERVICE), `owner_id`, `relative_path`, `caption` |
| `scan_snapshots` | F5 | `service_id`, `phase` (BEFORE/AFTER), `source` (REAL/DEMO/FILE), `codes_json`, `live_json`, `freeze_json`, `created_at` |
| `estimate_approvals` | F9 | `service_item_id`, `state`, `note`, `changed_at` (riwayat, bukan satu kolom) |
| `canned_jobs` + `canned_job_items` | F8 | template + baris contoh |
| `reminders` | F16 | `vehicle_id`, `due_date`, `due_odometer_km`, `note`, `dismissed_at` |
| `audit_log` | F5/F11/F17 | `action`, `target_kind`, `target_id`, `detail`, `actor`, `created_at` |
| `settings` | F2/F17 | `key`, `value` (nilai aplikasi, bukan SharedPreferences) |

Soft-delete (`deleted_at`) hanya untuk `inspection_items` dan `service_items`; sisanya hapus keras dengan cascade.
`estimate_items` tetap dipakai, lalu diperluas di v4 (kolom `discount`, `inspection_item_id`, `diagnosis_case_id`,
`approval`) supaya `DiagnosisServiceLink` tidak perlu ditulis ulang; `service_items` tidak dibuat sebagai tabel kedua.
F14/F16 boleh menambah migrasi `4 → 5` bila memang perlu kolom baru.

Urutan kerja mengikuti F1..F20; F1 mengerjakan seluruh tabel yang dibutuhkan F5, F6, F8, F9, F11, F16, F17 agar
migrasi tidak berulang.

## Fitur

### F0. Pemeriksaan dasar
- [x] Build, ponsel, Phase 2 diverifikasi
- [x] `docs/PROGRESS.md`, `docs/ARCHITECTURE.md`
- [ ] Rencana disetujui pemilik

### F1. Lapisan database
- [ ] Tabel baru + migrasi v4 + repository + tes

### F2. Profil dan pengaturan bengkel
- [x] Layar profil (nama, alamat, telepon, catatan penutup, logo) — commit `F2`
- [x] Disimpan di tabel `workshop_profile`; logo masuk folder media privat
- [ ] Format mata uang: `WorkshopUi.money` sudah Rupiah `id-ID`; pengaturan format ditunda sampai F10 ada kebutuhan nyata

### F3. Pelanggan dan kendaraan
- [x] Daftar/cari pelanggan, tambah pelanggan, daftar kendaraan per pelanggan
- [x] Tambah kendaraan: plat, model, tahun, bahan bakar, nomor rangka/mesin, odometer
- [x] Ubah pelanggan dan ubah kendaraan lewat menu ⋮ (id baris dipertahankan)
- [x] Riwayat servis per kendaraan; pencarian plat longgar (`plateKey`), validasi tahun/odometer
- [x] Tanggal: commit `9224a872` (profil) lalu commit F3

### F4. Servis dan alur status
- [x] Empat langkah di layar servis: Menunggu → Sedang dikerjakan → Selesai → Lunas
- [x] Lunas menandai selesai + dibayar + waktu bayar; mundur ke status belum selesai membatalkan pembayaran
- [x] Setiap perubahan status/pembayaran menulis satu baris `audit_log`
- [x] Layar **Pekerjaan servis** dengan saringan Semua / Belum selesai / Hari ini / Belum lunas
- [x] Kartu beranda menampilkan "N belum selesai · M hari ini" (hanya baca)
- [ ] Bekas uji pada `services` id 43 mengembalikan audit_log tambahan; bukan data pelanggan, boleh dibiarkan

### F5. Pindai OBD terkait servis
- [x] Layar **Simpan pindai** menyalin kode kerusakan + data langsung saat dibuka (sebelum daftar bisa terhapus)
- [x] Asal data ditulis: pindai nyata / data contoh / berkas tersimpan; mode tak dikenal selalu dianggap contoh
- [x] Daftar pindai tersimpan tampil di layar servis (posisi, waktu, asal, jumlah kode)
- [x] **Sebelum/sesudah**: simpan dua posisi, bandingkan kode yang hilang/baru/sama
- [x] **Hapus Kode Kerusakan** aktif lagi, tetap di balik dialog peringatan destruktif, kode yang akan hilang disalin dulu, dan setiap penghapusan menulis `audit_log`
- [x] Tes: `ScanSnapshotInstrumentedTest` (5) + `ScanComparisonInstrumentedTest` (6) lulus di ponsel
- [ ] Belum diuji dengan alat OBD nyata; hanya mode Latihan + data sintetis

### F6. Item pemeriksaan + foto
- [x] Layar **Pemeriksaan** per servis: tambah/ubah/hapus (hapus lunak), nama, kondisi Baik/Perlu perhatian/Rusak, catatan
- [x] Foto: kamera (`TakePicture` ke cache lewat FileProvider) atau pilih dari penyimpanan; disalin ke media privat lalu dirujuk
- [x] `PhotoRepository` menolak path absolut, traversal, dan karakter kontrol (bukan hanya di layar)
- [x] `provider_paths.xml` tidak lagi mengekspos seluruh storage eksternal
- [x] Tes `InspectionInstrumentedTest` (5) lulus; layar servis menampilkan jumlah foto
- [ ] Belum diuji dengan kamera fisik; emulator tanpa kamera memakai galeri

### F7. Estimasi
- [x] Satu dialog untuk tambah dan ubah baris (ketuk baris); hapus lewat ketuk lama
- [x] Diskon per baris dalam Rupiah, ditolak bila melebihi total baris
- [x] Tautan opsional ke bagian yang diperiksa pada servis itu; ditampilkan pada baris
- [x] Pemisah Indonesia "1,5" dan format "Rp 150.000" diurai di satu tempat (`parseQuantity`/`parseRupiah`)
- [x] Tes JVM `WorkshopServiceActivityParseTest` (2) + alur estimasi diagnosis lulus di ponsel

### F8. Paket pekerjaan
- [x] Layar **Paket pekerjaan**: buat/ubah nama, tambah/hapus baris, hapus paket
- [x] Ketuk paket dari layar servis = menyalin barisnya ke estimasi (`applyTo`), salinan tidak mengubah paket
- [x] Seed 4 paket contoh saat daftar masih kosong, nama berakhiran `[contoh]`, harga 0 dan catatan "harga masih contoh"
- [x] Tes `CannedJobInstrumentedTest` (4) lulus di ponsel

### F9. Persetujuan estimasi
- [x] Per baris: **Setujui** / **Tolak** dengan catatan ("disetujui lewat WhatsApp")
- [x] Riwayat jawaban per baris (waktu, jawaban, catatan), ditambah satu baris `audit_log`
- [x] Hanya baris **disetujui** dihitung ke total disetujui; total keseluruhan tetap terlihat
- [x] `EstimateApprovalSummary` meringkas disetujui/ditolak/belum, termasuk diskon
- [x] Tes JVM `EstimateApprovalSummaryTest` (4); total 52 tes JVM lulus

### F10. PDF + WhatsApp
- [x] `PdfPageWriter`: A4 595×842, teks dibungkus per baris, halaman baru bila tidak cukup, footer bernomor
- [x] **Laporan diagnosis**: identitas bengkel dari profil, pelanggan, kendaraan, keluhan, kode sebelum perbaikan (asal data ditulis), bagian diperiksa + foto, estimasi, catatan, disclaimer
- [x] **Nota**: hanya baris **disetujui**, total disetujui, status/pembayaran
- [x] Berkas di `Android/data/<pkg>/files/exports/`, dibagikan lewat chooser (`application/pdf`, WhatsApp bisa mengambilnya) + opsi teks pesan
- [x] `provider_paths.xml` hanya membuka folder aplikasi (bukan seluruh storage)
- [x] Tes `WorkshopPdfInstrumentedTest` (3): header `%PDF-`/trailer `%%EOF`, paginasi 400 kalimat + 6 foto, nota hanya baris disetujui
- [ ] Tampilan PDF belum diperiksa mata (tidak ada pembaca PDF di lingkungan ini)

### F11. Pembayaran
- [ ] Sebagian, metode, tanggal, daftar belum lunas

### F12. Diagnosis tanpa ECU
- [x] Selesai sebelum fase ini (commit `c470958d`..`c7b8fcb5`)

### F13. Panduan bunyi
- [x] Selesai sebelum fase ini (commit `4ed5e436`)

### F14. Catatan suara
- [ ] Rekaman per item/kasus + pemutaran

### F15. Riwayat kasus
- [x] Selesai sebelum fase ini (commit `c7b8fcb5`)

### F16. Pengingat servis
- [ ] Tanggal/odometer, layar jatuh tempo, teks WhatsApp

### F17. Cadangkan, pulihkan, ekspor
- [ ] CSV + pengingat cadangan (ZIP sudah ada)

### F18. Layar OBD lanjutan
- [ ] Sakelar "Mode Lanjutan" dengan konfirmasi

### F19. Ukur dan optimalkan
- [ ] Angka sebelum/sesudah

### F20. Audit akhir dan rilis
- [ ] Audit, README, panduan pengguna, checklist mekanik, panduan keystore
