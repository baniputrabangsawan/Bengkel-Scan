# Basis pengetahuan diagnosis

## Batas penggunaan dan keselamatan

Basis pengetahuan membantu menyusun **dugaan dan urutan pemeriksaan**, bukan memberikan kepastian diagnosis, persentase keyakinan, spesifikasi pabrikan, atau perintah mengganti komponen. Skor adalah bobot aturan yang dapat diedit, bukan peluang statistik. Keluhan yang sama dapat memiliki penyebab berbeda menurut mesin, sistem bahan bakar, dan kondisi kendaraan.

Berkas bawaan berada di `androbd/src/main/assets/diagnosis/knowledge-base.json`. Versi awal sengaja hanya memuat satu keluhan `cold_start`, tiga pertanyaan, dua dugaan penyebab, dan satu uraian bunyi `slow_cranking`. Semua penyebab awal memiliki `verified: false`: materi masih berupa draf dan belum dinyatakan telah ditelaah ahli. Jangan mengubahnya menjadi `true` hanya karena JSON lolos pemeriksaan aplikasi.

- Gunakan manual servis kendaraan untuk prosedur, alat, dan nilai ukur. Jangan menambahkan angka tekanan, tegangan, torsi, atau batas lain dengan menebak.
- Mulai dengan pemeriksaan aman; tuliskan kapan pengoperasian harus dihentikan dan kapan perlu teknisi kompeten.
- Jangan menyarankan membuka saluran injeksi bertekanan tinggi, menjembatani terminal starter, mendekati bagian bergerak, atau menyalakan mesin yang bocor bahan bakar.
- `work_item` adalah usulan pekerjaan, bukan pembelian atau penggantian otomatis. Utamakan pemeriksaan (`LABOR`) sampai ada bukti dan persetujuan pemilik kendaraan.
- Validasi aplikasi hanya memeriksa struktur, nilai, dan rujukan. Aplikasi tidak dapat memastikan kebenaran mekanis atau keamanan setiap kalimat yang diimpor. Impor hanya materi dari sumber yang dipercaya dan telah ditelaah.

## Tiga jenis status yang berbeda

| Data | Arti |
| --- | --- |
| `verified` pada penyebab | Status penelaahan **isi basis pengetahuan**, bukan hasil pemeriksaan kendaraan. Wajib berupa `true` atau `false`. |
| `CheckStatus.NOT_TESTED`, `CONFIRMED`, `RULED_OUT` | Status pemeriksaan manusia pada **satu kasus**. Pemeriksaan dan buktinya tidak mengubah `verified`. |
| Diagnosis akhir dan konfirmasi mekanik | Kesimpulan kasus setelah pemeriksaan; memilih dugaan teratas saja bukan konfirmasi. |

Aturan memberi bobot dasar ditambah `delta` untuk jawaban yang cocok. Status pemeriksaan manusia ditangani terpisah oleh penilaian kasus. Jawaban “belum diperiksa” sebaiknya tersedia pada setiap pertanyaan; jangan menganggap jawaban tersebut sebagai bukti positif maupun negatif. Berkas contoh tidak memberi aturan untuk pilihan itu.

## Format berkas

Gunakan JSON teks biasa dengan pengodean **UTF-8**, bukan dokumen Word, spreadsheet, atau JSON yang dibungkus blok Markdown. Ukuran berkas yang dibaca atau diimpor paling besar **1 MiB (1.048.576 byte)**. Susunan JSON dibatasi hingga 64 tingkat. Gunakan tanda kutip ganda biasa (`"`), angka tanpa tanda kutip, serta `true`/`false` huruf kecil. Jangan menulis komentar di dalam JSON. Nama properti peka huruf besar/kecil; properti yang tidak dikenal ditolak agar salah ketik tidak diam-diam diabaikan.

Semua properti di bawah wajib ada, kecuali `warning` dan `audio_file`. Semua teks wajib tidak kosong, kecuali `warning` boleh `""`. Daftar wajib tetap ditulis walaupun kosong diperbolehkan. `null` tidak menggantikan daftar kosong. Semua teks yang akan dibaca pengguna harus dalam Bahasa Indonesia. ID dan nama properti adalah penanda teknis; jangan terjemahkan ID yang sudah digunakan kasus atau rujukan lain.

### Akar dokumen

| Properti | Jenis dan aturan |
| --- | --- |
| `schema_version` | Bilangan bulat, tepat `1`. Versi lain ditolak, tidak dikonversi diam-diam. `"1"` bukan angka yang valid. |
| `revision` | Teks revisi yang tidak kosong, misalnya `bengkel-2026-11-18-01`. Ubah ketika isi berubah. |
| `complaints` | Daftar keluhan lengkap. Basis mandiri wajib memuat sekurangnya satu keluhan; berkas tambahan boleh `[]`. |
| `sounds` | Daftar uraian bunyi. Boleh `[]` bila tidak diperlukan atau bunyi yang dirujuk tersedia dari bawaan saat penggabungan tambahan. |

### Keluhan (`complaints[]`)

| Properti | Jenis dan aturan |
| --- | --- |
| `id` | Teks ID unik di seluruh keluhan dalam satu sumber. Hindari spasi; jangan gunakan spasi awal/akhir. |
| `category` | Tepat salah satu: `noise`, `smoke`, `starting`, `power`, `overheating`, `leaks`, `vibration`, `electrical`, `brakes_steering`, `other`. |
| `title` | Judul keluhan untuk pengguna. |
| `questions` | Daftar **3–6** pertanyaan. |
| `causes` | Daftar sekurangnya satu dugaan penyebab. |

### Pertanyaan dan pilihan

Setiap pertanyaan memiliki `id`, `text`, dan `options`. ID pertanyaan unik **di dalam keluhan itu**, bukan harus unik di seluruh dokumen. `text` berisi pertanyaan. `options` wajib memuat sekurangnya satu pilihan; tiap pilihan memiliki `id` dan `text`. ID pilihan unik **di dalam pertanyaan itu**. Pertanyaan berbeda boleh sama-sama memiliki pilihan `unknown`. Hindari pertanyaan gabungan yang tidak dapat dijawab dengan satu pilihan; sediakan pilihan belum diperiksa/tidak yakin.

### Penyebab (`causes[]`)

| Properti | Jenis dan aturan |
| --- | --- |
| `id` | ID unik **di seluruh penyebab semua keluhan**. Pola yang disarankan: `id_keluhan.nama_penyebab`. |
| `name` | Nama dugaan, jangan ditulis sebagai kepastian. |
| `signs` | Daftar teks, sekurangnya satu tanda/gejala yang mendukung. |
| `verification_steps` | Daftar teks, sekurangnya satu langkah pemeriksaan aman dan konkret. |
| `danger_level` | Tepat `LOW`, `MEDIUM`, atau `HIGH`; ini tingkat perhatian keselamatan, bukan keyakinan diagnosis. |
| `warning` | Teks peringatan; boleh tidak ditulis atau `""`. `null` tidak diterima. |
| `work_item` | Objek dengan `kind` dan `description`. `kind` tepat `PART`, `LABOR`, atau `OTHER`; `description` teks pekerjaan. Tidak memuat harga atau jumlah. |
| `base_weight` | Bilangan bulat **0–10**. |
| `score_rules` | Daftar aturan; boleh `[]`. |
| `verified` | Boolean wajib. `"false"` sebagai teks tidak diterima. Gunakan `false` untuk draf atau materi belum ditelaah. |
| `sound_ids` | Daftar ID bunyi yang berkaitan; boleh `[]`. Tidak boleh memuat ID berulang. |

Setiap aturan dalam `score_rules` memiliki:

- `question_id`: ID pertanyaan pada **keluhan yang memuat penyebab ini**;
- `option_id`: ID pilihan milik pertanyaan tersebut;
- `delta`: bilangan bulat **−5 sampai 5**;
- `reason`: alasan Bahasa Indonesia yang menjelaskan mengapa jawaban mendukung atau mengurangi dukungan.

Pasangan `question_id` + `option_id` tidak boleh berulang pada penyebab yang sama. Pasangan itu boleh dipakai pada penyebab berbeda. Aturan tidak boleh menunjuk pertanyaan dari keluhan lain. Jangan menambah aturan “duplikat” untuk menaikkan bobot; ubah satu aturan yang bersangkutan, tetap dalam batasnya. Skor tidak boleh ditampilkan sebagai persentase diagnosis.

### Bunyi (`sounds[]`)

| Properti | Jenis dan aturan |
| --- | --- |
| `id` | ID bunyi unik di seluruh daftar bunyi dalam satu sumber. |
| `title` | Nama bunyi. |
| `description` | Uraian bunyi dan keterbatasan pembandingnya. |
| `when` | Kondisi saat bunyi terdengar; jangan meminta pengguna mengulangi kondisi berbahaya untuk merekam. |
| `audio_file` | Opsional; `null` atau path relatif lokal yang aman, misalnya `audio/contoh-starter.m4a`. Nilai kosong tidak diterima. |

Path tidak boleh absolut, berisi segmen `.`/`..`, segmen kosong, garis miring terbalik, titik dua, karakter kontrol, `%`, `?`, atau `#`. URL jaringan dan URI `content://` tidak diterima. Validator memeriksa bentuk path, **bukan keberadaan, mutu, format, atau kebenaran rekaman**.

**Penting tentang rekaman:** mengimpor JSON tidak menyalin rekaman. Panduan bunyi mencari `audio_file` terlebih dahulu di folder privat aplikasi `getFilesDir()/diagnosis/`, lalu di aset bawaan `assets/diagnosis/`. Mengisi nama berkas dari folder Unduhan saja tidak membuatnya dapat diputar. Rekaman pribadi yang diimpor atau direkam melalui panduan bunyi disimpan terpisah dengan metadata dan hubungan `sound.id`; rekaman itu bukan otomatis rekaman acuan yang telah ditelaah. Jangan menulis ID klip pribadi ke `audio_file`, jangan mengubah `verified` karena ada rekaman, dan jangan menganggap bunyi yang mirip membuktikan penyebab. `slow_cranking` bawaan sengaja memiliki `audio_file: null`: tidak ada rekaman acuan yang dipalsukan atau disediakan secara diam-diam.

## Tambahan dan penggantian

Berkas tambahan aktif disalin ke:

```text
getExternalFilesDir(null)/diagnosis/extra.json
```

Ini folder khusus aplikasi, bukan `getFilesDir()/diagnosis/` tempat media privat. Lokasi fisiknya bergantung perangkat; jangan menebak path bersama atau meminta izin penyimpanan umum. Berkas bawaan dalam APK tidak diubah.

Urutan pemuatan:

1. Baca dan validasi bawaan. Kegagalan bawaan adalah kesalahan pemuatan, bukan alasan menghasilkan data diagnosis palsu.
2. Jika `extra.json` tersedia, baca dengan batas ukuran dan UTF-8 yang sama. Validasi ID duplikat dan struktur **sumber tambahan sebelum digabungkan**.
3. Gabungkan keluhan menurut `complaint.id` dan bunyi menurut `sound.id`. ID sama **mengganti seluruh entri**, bukan menggabungkan properti, pertanyaan, aturan, atau penyebab di dalamnya. ID baru menambah entri. ID berulang di dalam satu berkas tetap kesalahan, bukan perintah penggantian.
4. Periksa kembali seluruh gabungan: ID penyebab harus unik secara global dan semua rujukan bunyi harus dapat ditemukan. Tambahan boleh merujuk `slow_cranking` tanpa menyalin objek bunyinya karena objek tersebut ada di bawaan. Rujukan yang tidak ada di kedua sumber ditolak.
5. Revisi hasil digabung menjadi `revisi-bawaan + revisi-tambahan`; urutan entri bawaan tetap, entri baru ditambahkan di belakang.

Berkas tambahan baru **menggantikan berkas tambahan lama**, bukan menumpuk di atasnya. Saat mengimpor versi baru, sertakan kembali setiap perubahan tambahan lama yang masih diperlukan. Tidak ada perintah penghapusan entri bawaan dalam skema ini. `complaints: []` dapat dipakai untuk tambahan bunyi saja; `sounds: []` dapat dipakai bila semua bunyi yang dirujuk disediakan bawaan. Kedua daftar kosong dengan revisi valid tidak menambah apa-apa.

Jika tambahan rusak, tidak terbaca, memiliki versi skema lain, atau membuat gabungan tidak valid, pemuatan memakai bawaan dan menyediakan peringatan Bahasa Indonesia melalui `warning()`. Layar pemanggil wajib menampilkan peringatan ini. Berkas rusak tidak dihapus diam-diam. Bila penyimpanan tambahan tidak tersedia, bawaan tetap dapat digunakan dengan peringatan. Impor yang tidak valid melempar `IOException` **sebelum** berkas aktif disentuh. Penulisan yang valid menggunakan `AtomicFile`, sinkronisasi, dan pemulihan berkas lama bila penulisan gagal; jangan mengedit berkas aktif saat aplikasi sedang membacanya.

Kasus tersimpan memakai salinan pengetahuan saat kasus dibuat. Mengimpor revisi baru tidak menulis ulang jawaban, hasil pemeriksaan, bukti, atau salinan pengetahuan kasus lama.

## Alur penyuntingan untuk pengelola nonprogrammer

Tahap fondasi ini menyediakan codec, validator, aset contoh, dan API impor; **belum menambahkan tombol atau layar impor tersendiri**. Pemilihan dokumen adalah tanggung jawab layar aplikasi yang menghubungkan API di bagian integrasi. Jangan menganggap sudah ada menu dengan nama tertentu. Setelah tindakan impor tersedia pada layar tersebut, pengguna tidak perlu mengubah kode Java atau membangun APK untuk memperbarui tambahan.

1. Simpan salinan berkas JSON tambahan lama di tempat aman. Untuk awal, salin contoh lengkap di bawah atau minta pengelola menyalin aset bawaan. Jangan menyunting satu-satunya salinan aktif.
2. Buka salinan dengan editor teks biasa yang dapat menyimpan UTF-8. Simpan dengan akhiran `.json`, bukan `.json.txt`. Jangan gunakan kutip melengkung dari pengolah kata.
3. Tentukan apakah ingin **mengganti keluhan lama** atau **menambah keluhan baru**. Untuk mengganti, pertahankan ID keluhan dan sertakan seluruh pertanyaan serta penyebab yang masih diperlukan. Untuk menambah, gunakan ID keluhan baru dan ID penyebab baru yang unik secara global.
4. Ubah kalimat dan langkah pemeriksaan dengan penelaah teknis. Pertahankan `verified: false` sampai penelaahan nyata selesai. Catat tanggal, nama penelaah, sumber manual yang relevan, dan alasan perubahan dalam catatan pengelolaan terpisah; skema ini tidak mempunyai properti catatan sumber, jadi jangan menambah properti baru sembarangan.
5. Periksa kembali setiap aturan setelah mengubah ID pertanyaan/pilihan. Mengubah teks pilihan saja tidak memerlukan pengubahan ID. Jika mengganti ID bunyi, perbarui semua `sound_ids` yang terkait.
6. Naikkan `revision`, simpan UTF-8, lalu gunakan tindakan impor di aplikasi. Pemilih dokumen Android membuka berkas dari lokasi yang dipilih pengguna; nama atau jenis MIME saja tidak membuktikan isinya valid.
7. Impor harus selesai tanpa kesalahan. Muat kembali daftar keluhan, periksa judul, pilihan, peringatan keselamatan, dan panduan bunyi. Ini pemeriksaan isi oleh pengelola, bukan pembuktian diagnosis.
8. Jika impor ditolak, berkas tambahan lama tetap aktif. Perbaiki salinan sumber lalu impor ulang; jangan menonaktifkan validasi atau mengganti `.json` aktif secara paksa.
9. Bila pembaruan valid ternyata salah secara isi, impor kembali salinan tambahan lama. Ini mengembalikan isi aktif, bukan mengubah kasus yang sudah tersimpan. Untuk kembali tanpa tambahan, pengelola dapat memulihkan berkas tambahan kosong dengan kedua daftar `[]`, atau menghapus tambahan melalui mekanisme pengelolaan yang memang tersedia; tahap ini tidak menyediakan tombol hapus khusus.

Jangan masukkan nama pelanggan, nomor kendaraan, foto, bukti kasus, atau informasi pribadi ke basis pengetahuan umum. Data kasus dan rekaman pribadi bukan bagian dokumen JSON ini.

## Contoh kerja: mengganti keluhan tanpa menyalin bunyi bawaan

Contoh ini merupakan **berkas tambahan lengkap yang bisa diimpor**, bukan potongan untuk ditempel di tengah JSON. ID `cold_start` sengaja sama dengan bawaan sehingga seluruh keluhan bawaan diganti. Dua penyebab dalam contoh ini menjadi daftar lengkap keluhan tersebut. `sounds` kosong karena `slow_cranking` dipenuhi dari bawaan. Menjalankan `parse()` mandiri terhadap contoh ini akan menolak rujukan bunyi yang belum terpenuhi; jalur `importExtra()` menggabungkan dengan bawaan terlebih dahulu sebelum memeriksa rujukan akhir.

```json
{
  "schema_version": 1,
  "revision": "contoh-bengkel-2026-11-18-01",
  "complaints": [
    {
      "id": "cold_start",
      "category": "starting",
      "title": "Mesin sulit hidup setelah lama berhenti",
      "questions": [
        {
          "id": "cranking",
          "text": "Bagaimana putaran saat starter?",
          "options": [
            {"id": "slow", "text": "Lebih lambat dari biasanya"},
            {"id": "normal", "text": "Seperti biasanya"},
            {"id": "unknown", "text": "Belum diperiksa"}
          ]
        },
        {
          "id": "standing",
          "text": "Apakah keluhan terutama muncul setelah lama berhenti?",
          "options": [
            {"id": "yes", "text": "Ya"},
            {"id": "no", "text": "Tidak"},
            {"id": "unknown", "text": "Belum dibandingkan"}
          ]
        },
        {
          "id": "supply_checked",
          "text": "Apa hasil pemeriksaan teknisi pada suplai bahan bakar sisi tekanan rendah?",
          "options": [
            {"id": "leak", "text": "Ditemukan ketidakrapatan yang relevan"},
            {"id": "clear", "text": "Belum ditemukan gangguan sesuai prosedur manual"},
            {"id": "unknown", "text": "Belum diperiksa atau sistem belum dikenali"}
          ]
        }
      ],
      "causes": [
        {
          "id": "cold_start.weak_starting_supply",
          "name": "Pasokan listrik starter perlu diperiksa",
          "signs": ["Putaran starter lambat atau semakin melemah."],
          "verification_steps": [
            "Amankan kendaraan dan matikan kontak sebelum inspeksi visual baterai, terminal, serta sambungan massa.",
            "Minta teknisi menguji kondisi baterai dan jatuh tegangan sesuai manual sebelum menentukan perbaikan."
          ],
          "danger_level": "MEDIUM",
          "warning": "Hentikan percobaan bila baterai bengkak, bocor, atau kabel panas. Jangan menjembatani terminal; jauhkan api dan percikan.",
          "work_item": {
            "kind": "LABOR",
            "description": "Pemeriksaan baterai dan rangkaian starter, bukan penggantian otomatis."
          },
          "base_weight": 2,
          "score_rules": [
            {
              "question_id": "cranking",
              "option_id": "slow",
              "delta": 4,
              "reason": "Putaran lambat mendukung pemeriksaan pasokan starter, tetapi belum menentukan komponen rusak."
            },
            {
              "question_id": "cranking",
              "option_id": "normal",
              "delta": -2,
              "reason": "Putaran normal mengurangi dukungan terhadap pasokan lemah, tanpa menggantikan pengukuran."
            }
          ],
          "verified": false,
          "sound_ids": ["slow_cranking"]
        },
        {
          "id": "cold_start.air_in_fuel_supply",
          "name": "Kekedapan suplai bahan bakar perlu diperiksa",
          "signs": ["Pada sistem yang sesuai, mesin sulit hidup setelah lama berhenti meski starter berputar normal."],
          "verification_steps": [
            "Kenali sistem dari manual; dengan mesin mati dan dingin, lakukan inspeksi visual sisi tekanan rendah yang aman diakses.",
            "Minta teknisi menguji kekedapan sesuai manual. Jangan membuka pipa injeksi bertekanan tinggi atau mencari kebocoran dengan tangan."
          ],
          "danger_level": "HIGH",
          "warning": "Jika ada kebocoran atau bau bahan bakar, hentikan pengoperasian, jauhkan api, dan minta penanganan teknisi.",
          "work_item": {
            "kind": "LABOR",
            "description": "Pemeriksaan suplai bahan bakar sesuai jenis sistem dan manual kendaraan."
          },
          "base_weight": 1,
          "score_rules": [
            {
              "question_id": "standing",
              "option_id": "yes",
              "delta": 2,
              "reason": "Keluhan setelah lama berhenti sesuai kemungkinan suplai tidak terjaga, tetapi belum membuktikannya."
            },
            {
              "question_id": "supply_checked",
              "option_id": "leak",
              "delta": 4,
              "reason": "Temuan teknisi mendukung pemeriksaan ketidakrapatan; hubungan dengan keluhan harus dibuktikan."
            },
            {
              "question_id": "supply_checked",
              "option_id": "clear",
              "delta": -2,
              "reason": "Pemeriksaan tanpa temuan mengurangi dukungan terhadap dugaan ini."
            }
          ],
          "verified": false,
          "sound_ids": []
        }
      ]
    }
  ],
  "sounds": []
}
```

Untuk menjadikan contoh ini basis mandiri yang dapat diproses `parse()`, sertakan objek `slow_cranking` dari aset pada `sounds`, atau hapus rujukan itu bila memang tidak relevan. Untuk menambah keluhan alih-alih mengganti, ubah `cold_start` menjadi ID baru dan ubah **kedua ID penyebab** dengan awalan baru. ID pertanyaan dan pilihan boleh tetap karena lingkupnya lokal. Contoh ini tetap draf, bukan rekomendasi servis untuk semua kendaraan.

## Integrasi Java dan pelaporan kesalahan

Paket model: `com.fr3ts0n.ecu.gui.androbd.diagnosis.core`.

- `DiagnosisKnowledge` mempunyai field `public final`: `schemaVersion`, `revision`, `complaints`, `sounds`; semua daftar disalin secara defensif dan tidak dapat dimutasi.
- Kelas bersarang: `Complaint`, `Question`, `Option`, `Cause`, `Rule`, `Sound`. Nama field Java memakai camelCase sesuai padanan JSON. `Cause.workKind`/`workDescription` memetakan `work_item`.
- `findComplaint(id)`, `findCause(id)`, `findSound(id)` mengembalikan `null` jika tidak ditemukan; `Complaint.findCause(id)` mencari dalam satu keluhan.
- `DiagnosisKnowledgeValidator.validate(knowledge)` memeriksa model lengkap dan melempar `IllegalArgumentException` dengan kunci alasan stabil, misalnya `knowledge.schema_version`, `knowledge.duplicate_cause_id`, `knowledge.rule_question_reference`, `knowledge.rule_option_reference`, `knowledge.sound_reference`, atau `knowledge.audio_path`. Kunci tersebut untuk program/pengujian, bukan pesan yang ditampilkan mentah kepada pengguna.
- `validateExtra(knowledge)` hanya untuk sumber tambahan sebelum penggabungan: mengizinkan daftar keluhan kosong dan menunda keberadaan rujukan bunyi. Validasi struktur, ID duplikat, dan rujukan pertanyaan/pilihan tetap berjalan. Jangan menggunakan hasil ini sebagai pengetahuan siap ditampilkan tanpa validasi gabungan lengkap.

Paket pemuatan: `com.fr3ts0n.ecu.gui.androbd.diagnosis.data`.

```java
DiagnosisKnowledgeLoader loader = new DiagnosisKnowledgeLoader(context);
DiagnosisKnowledge knowledge = loader.load(); // throws IOException
String warning = loader.warning();           // display when nonempty

// Uri returned by ACTION_OPEN_DOCUMENT, CATEGORY_OPENABLE, application/json.
// Use the document result's read grant; perform I/O off the UI thread.
loader.importExtra(uri);                       // throws IOException
knowledge = loader.load();

String snapshot = DiagnosisKnowledgeLoader.encode(knowledge);
DiagnosisKnowledge restored = DiagnosisKnowledgeLoader.parse(snapshot);
```

`importExtra(Uri)` menerima URI `content://` dari pemilih dokumen Android; tidak menerima path atau URL jaringan. Implementasi menyalin isi segera sehingga tidak memerlukan izin URI persisten untuk pemuatan berikutnya. Impor adalah operasi sinkron dan harus dipanggil di luar utas UI. Keberhasilan metode bukan jaminan isi medis/mekanis, bukan persetujuan biaya, dan bukan konfirmasi kasus.

Untuk memeriksa tambahan di dalam arsip sebelum memulihkan cadangan, gunakan `DiagnosisKnowledge bundled = loader.loadBundled()` lalu `DiagnosisKnowledge merged = DiagnosisKnowledgeLoader.mergeExtra(bundled, extraJson)`. `loadBundled()` hanya membaca aset APK dan mengabaikan tambahan aktif. `mergeExtra(...)` memvalidasi bawaan yang diberikan, sumber tambahan, serta gabungannya tanpa membaca atau menulis penyimpanan; tambahan tetap dibatasi 1 MiB. Jangan memakai `load()` sebagai pengganti `loadBundled()` untuk pemeriksaan arsip karena tambahan yang terpasang tidak boleh ikut memengaruhi hasil.

`parse(String)` menerima snapshot gabungan hingga 2 MiB agar gabungan dua sumber yang masing-masing dibatasi 1 MiB dapat dipulihkan. Batas berkas aset, pembacaan tambahan, impor dokumen, dan `mergeExtra` tetap 1 MiB per sumber. `encode` tidak dimaksudkan untuk membuat dokumen tambahan yang melampaui batas tersebut.

`parse(String)` selalu memvalidasi dokumen **mandiri lengkap**; tidak memakai `Context`, tidak membaca bawaan, dan tidak menggabungkan tambahan. `encode(DiagnosisKnowledge)` memvalidasi model lengkap, mengeluarkan semua properti termasuk status `verified`, serta tidak mengubah model. Jangan memakai `encode` untuk tambahan yang sengaja memiliki rujukan belum terpenuhi. `IOException` pemuatan/impor menyediakan pesan Bahasa Indonesia; sebab internal menyimpan rincian struktur untuk pengembang. Peringatan `warning()` diperbarui pada pemuatan, kosong setelah impor yang berhasil, dan tidak boleh dipakai menggantikan penanganan kegagalan impor yang dilempar.

## Cakupan pengujian perangkat

`androbd/src/androidTest/java/com/fr3ts0n/ecu/gui/androbd/diagnosis/DiagnosisKnowledgeInstrumentedTest.java` memuat aset nyata melalui platform `org.json`, memeriksa perjalanan pulang-pergi model, tambahan pengganti/penambah/bunyi-saja, rujukan bunyi yang disediakan bawaan, ID duplikat di tiap lingkup, rujukan pertanyaan/pilihan/bunyi yang tidak ada, versi skema dan tipe data yang salah, path audio tidak aman, UTF-8 rusak, batas ukuran, peringatan fallback, dan bahwa impor tidak valid tidak menimpa tambahan valid. Berkas impor pengujian dibuka melalui URI `FileProvider` nyata dengan folder khusus sementara, bukan penyimpanan pengguna yang aktif. Pengujian ini tidak mengotomatiskan tampilan pemilih dokumen, tidak membuktikan ketahanan terhadap mati listrik fisik, dan tidak memvalidasi kebenaran isi diagnosis. Penambahan pengujian tidak berarti pengujian sudah dijalankan.
