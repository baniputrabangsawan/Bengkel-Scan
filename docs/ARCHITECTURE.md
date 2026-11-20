# Arsitektur Bengkel Scan

Dokumen ini menjelaskan struktur kode dan data yang **sudah ada** di `bengkel-custom`. Perubahan rencana ada di
`docs/PROGRESS.md`. Aturan produk dan istilah ada di `docs/workshop-glossary.md`; perilaku UI ada di `README.md`.

## Modul Gradle

| Modul | Jenis | Isi |
| --- | --- | --- |
| `:androbd` | aplikasi Android | semua UI, transport, penyimpanan bengkel |
| `:library` | Java murni | protokol ELM/OBD, model PID, konversi, `pvs` |
| `:plugin` | submodule | framework ekstensi upstream; bukan penyimpanan bengkel |

`library` tidak boleh memakai `android.*`. Android SDK 37, `minSdk 23`, Java 17.

## Paket di `androbd`

```
com.fr3ts0n.ecu.gui.androbd
├── MainActivity, CommService (+ Bt/Ble/Usb/Network), *DeviceListActivity
│   ObdItemAdapter, DfcItemAdapter, ObdUiLabels, ObdUiValues, ModernUiUtils
├── workshop/            data bengkel offline
│   ├── WorkshopDatabase, WorkshopRepository, DiagnosticRepository
│   ├── WorkshopBackup, WorkshopArchive
│   ├── Customer, Vehicle, ServiceRecord, EstimateItem, ... (model + enum)
│   ├── guidance/WorkshopGuideCatalog
│   └── ui/  WorkshopCustomersActivity, WorkshopCustomerActivity, WorkshopVehicleActivity,
│            WorkshopServiceActivity, WorkshopDiagnosticsActivity, WorkshopDiagnosticEditActivity,
│            WorkshopGuideActivity, WorkshopUi, DiagnosticUi, WorkshopRowAdapter, WorkshopRow
└── diagnosis/           diagnosis tanpa alat scan (kendaraan tanpa ECU)
    ├── core/  DiagnosisKnowledge, DiagnosisKnowledgeValidator, DiagnosisScorer,
    │          CheckStatus, DiagnosisCaseHistory
    ├── data/  DiagnosisKnowledgeLoader, DiagnosisRepository, DiagnosisMediaStore, DiagnosisServiceLink
    └── ui/    DiagnosisStartActivity, DiagnosisQuestionsActivity, DiagnosisResultsActivity,
               DiagnosisCaseActivity, SoundGuideActivity, DiagnosisAudioPlayer, DiagnosisUi,
               DiagnosisHistoryPanel, DiagnosisServicePanel
```

Batas yang dipertahankan: `workshop/` dan `diagnosis/` tidak memanggil `ElmProt`, `ObdProt`, `CommService`, atau
`EcuDataPv`. Hasil pemindaian masuk ke bengkel hanya melalui snapshot eksplisit (F5), bukan dengan membaca daftar
hasil secara langsung.

## Alur data

OBD (upstream): `MainActivity` → `CommService.elm` → transport → `ElmProt`/`ObdProt` → `PvList`/`EcuDataPv`/
`EcuCodeItem` → `ObdItemAdapter`/`DfcItemAdapter` → layout native.

Bengkel: `Activity` → `WorkshopUi.repository(getContext())` → `WorkshopRepository` → `WorkshopDatabase.getInstance()`.
Tidak ada cursor atau `SQLiteDatabase` yang keluar dari repository. Repositori sinkron; pemanggil memindahkan SQL
dari UI thread dan melakukan shutdown pada lifecycle.

Diagnosis: aset JSON → `DiagnosisKnowledgeLoader` (validasi + `AtomicFile` untuk `extra.json`) → jawaban + status
pemeriksaan → `DiagnosisScorer` → mekanik mengisi bukti dan penilaian akhir → `DiagnosisRepository` + berkas privat
`DiagnosisMediaStore` → `WorkshopArchive` saat mencadangkan.

## Database

`WorkshopDatabase` (`SQLiteOpenHelper`), nama `workshop.db`, `SCHEMA_VERSION = 3`, migrasi aditif saja
(`onUpgrade` menambah, tidak pernah menghapus). Foreign key aktif di `onConfigure`. Uang = Rupiah bilangan bulat;
`quantity` pecahan; enum disimpan sebagai nama; semua waktu = epoch milidetik.

Versi 1:

| Tabel | Kolom penting |
| --- | --- |
| `customers` | `name`, `phone`, `notes`, `created_at` |
| `vehicles` | `customer_id`, `plate`, `plate_key`, `model`, `year`, `engine_type`, `vin`, `odometer_km`, `created_at` |
| `services` | `vehicle_id`, `complaint`, `status`, `payment_status`, `notes`, `created_at`, `updated_at`, `completed_at` |
| `estimate_items` | `service_id`, `kind`, `description`, `quantity`, `unit_price`, `position` |

Versi 2 menambah `diagnostics` (satu baris per `service_id`+`module`, `CHECK` wajib bukti untuk `CONFIRMED`).

Versi 3 menambah `diagnosis_cases`, `diagnosis_checks`, `diagnosis_photos` (`diagnosis/data/DiagnosisRepository`),
termasuk `estimate_item_id` dan `CHECK` penilaian akhir yang dikonfirmasi.

Penghapusan pelanggan berantai: kendaraan → servis → item estimasi, diagnosis, dan kasus gejala.

## Cadangan

`WorkshopBackup.export` menulis `.zip` berformat `bengkel-workshop` versi 1 (`manifest.json`, `workshop.db`,
`knowledge/extra.json`, `media/photos/*`, `media/audio/*`) di `getExternalFilesDir(null)/backup/`. Pemulihan
memvalidasi salinan bertahap (`WorkshopArchive`, `DiagnosisRepository.validateDatabase`), menyimpan
`workshop.db.before-restore`, dan mengembalikan data lama bila penggantian gagal. Cadangan `.db` lama tetap diterima.
Cadangan tidak dienkripsi.

## Keputusan yang sudah diambil

- Satu ponsel satu database; tidak ada akun, sinkronisasi, atau server.
- Fitur bengkel berada di `androbd`, bukan `plugin` dan bukan `library`.
- `library` tetap Java murni.
- Penilaian akhir diagnosis selalu diisi mekanik; skor hanya alasan urutan.
- Isi panduan diagnosis berupa draf (`verified: false`) sampai ditinjau mekanik senior.
- Kontrol aktuator dan penghapusan kode disembunyikan; penghapusan kode hanya dibuka dengan konfirmasi dan catatan
  audit (F5).
- Tidak ada dependensi baru tanpa persetujuan; Android built-in lebih dulu.
