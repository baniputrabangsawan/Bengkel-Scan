# Panduan pengguna Bengkel Scan

Panduan singkat untuk mekanik. Semua pekerjaan berjalan tanpa internet; internet hanya dipakai bila Anda
sengaja mengirim laporan lewat WhatsApp.

## 1. Sebelum mulai

1. Buka aplikasi **Bengkel Scan**.
2. Isi identitas bengkel satu kali: **Data bengkel → ⋮ → Profil bengkel**. Nama bengkel wajib; alamat,
   telepon, logo, dan catatan penutup muncul di laporan cetak.
3. Bila ingin mencoba tanpa kendaraan, pakai **Mode Latihan** di beranda. Data contoh **selalu** diberi label;
   jangan dipakai untuk keputusan servis.

## 2. Pelanggan dan kendaraan

1. **Buka data bengkel → Tambah pelanggan**. Nama wajib, nomor HP opsional.
2. Ketuk pelanggan → **Tambah kendaraan**: plat wajib, lalu model, tahun, bahan bakar, nomor rangka/mesin,
   odometer.
3. Salah ketik? **⋮ → Ubah pelanggan** atau **⋮ → Ubah kendaraan**. Nomor baris tidak berubah, jadi riwayat
   servis tetap tersambung.
4. Tanggal atau odometer servis berikutnya: buka kendaraan → **⋮ → Atur pengingat**.

## 3. Servis baru

1. Ketuk kendaraan → **Servis baru** → tulis keluhan pelanggan.
2. Layar servis berisi tombol: **Diagnosis & panduan**, **Diagnosis Tanpa Alat Scan**, **Bagian diperiksa**,
   **Simpan pindai**, **Bandingkan sebelum/sesudah**, **Paket pekerjaan**, **Laporan dan nota**.
3. Status: geser **Menunggu → Sedang dikerjakan → Selesai → Lunas**. **Lunas** menandai selesai dan menerima
   pembayaran sekaligus. Bila salah, geser kembali; pembayaran ikut dibatalkan.

## 4. Kendaraan dengan OBD

1. Sambungkan alat scan, lalu buka **Kode Kerusakan** atau **Data Langsung** sampai data muncul.
2. Kembali ke layar servis → **Simpan pindai** → pilih **Sebelum perbaikan** atau **Sesudah perbaikan** →
   **Simpan pindai**. Asal data ditulis jelas: pindai nyata, data contoh, atau berkas tersimpan.
3. Sesudah perbaikan, simpan pindai kedua lalu buka **Bandingkan sebelum/sesudah** untuk melihat kode yang
   hilang, baru, dan tetap ada. Kode yang hilang **bukan** bukti perbaikan berhasil.
4. **Hapus Kode Kerusakan** hanya bila memang diminta pemilik kendaraan. Baca peringatannya: data emisi ikut
   hilang, kendaraan bisa berjalan kurang baik sementara ECU menyesuaikan diri.

## 5. Kendaraan tanpa ECU

1. Layar servis → **Diagnosis Tanpa Alat Scan**.
2. Pilih kelompok keluhan (bunyi, asap, sulit hidup, tenaga berkurang, panas, bocor, getaran, kelistrikan,
   rem dan kemudi, lainnya), lalu keluhan, lalu jawab pertanyaan. Jawaban "belum diperiksa" tidak dianggap
   kondisi normal.
3. Daftar **Kemungkinan penyebab** diurutkan dari aturan yang tertulis, bukan peluang. Setiap sebab memuat
   tanda, cara memeriksa, tingkat bahaya, dan alasan urutannya.
4. Setelah memeriksa, tandai **terbukti** (wajib menulis bukti) atau **disingkirkan** (wajib menulis alasan).
   Pilih penilaian akhir, boleh tulisan sendiri, lalu **Simpan**. ***Hasil ini hanya saran. Keputusan akhir
   ada pada mekanik.***
5. **Panduan Bunyi**: dengarkan contoh bunyi atau rekam bunyi kendaraan (maksimal 15 detik) pada kasus yang
   sudah dikonfirmasi.

## 6. Bagian yang diperiksa dan catatan suara

1. Layar servis → **Bagian diperiksa** → **Tambah bagian diperiksa**: nama, kondisi (Baik / Perlu perhatian /
   Rusak), catatan.
2. Ketuk barisnya untuk mengubah, mengambil foto (**Ambil foto** atau **Pilih foto**), atau merekam
   **Catatan suara**.
3. Ketuk lama untuk menghapus baris. Foto yang sudah tersimpan tetap dipakai di laporan.

## 7. Estimasi dan persetujuan

1. Layar servis → **Tambah item**: jenis (jasa/sparepart), keterangan, jumlah, harga, diskon bila ada, dan
   bagian yang diperiksa bila terkait.
2. Ketuk baris untuk mengubah, ketuk lama untuk menyetujui, menolak, melihat riwayat, atau menghapus.
3. Hanya baris **Disetujui** yang dihitung ke total disetujui dan ke nota.

## 8. Laporan, nota, dan WhatsApp

1. Layar servis → **Laporan dan nota**.
2. **Laporan diagnosis** memuat identitas bengkel, pelanggan, kendaraan, keluhan, kode sebelum perbaikan,
   bagian yang diperiksa beserta foto, dan estimasi. **Nota** hanya memuat baris yang disetujui.
3. **Kirim lewat WhatsApp atau aplikasi lain** membuka menu berbagi; pilih WhatsApp. **Kirim sebagai teks
   pesan** mengirim ringkasan tanpa lampiran.

## 9. Pembayaran

1. Layar servis → **Catat pembayaran**: jumlah yang benar-benar diterima dan cara bayar.
2. Bila jumlahnya kurang dari total disetujui, status tetap **belum lunas** dan **sisa tagihan** terlihat.
3. **Pekerjaan servis → Belum lunas** menampilkan semua yang masih menunggak.

## 10. Cadangan dan ekspor

1. **Data bengkel → Cadangkan data** membuat satu berkas ZIP (database, foto, rekaman, panduan tambahan).
   Bagikan berkas itu ke Google Drive atau komputer.
2. **⋮ → Pulihkan dari berkas** mengganti seluruh data setelah konfirmasi. Data lama disimpan sebagai
   `workshop.db.before-restore`.
3. Aplikasi mengingatkan bila sudah lama tidak mencadangkan. Jangan menunda: satu ponsel menyimpan seluruh
   catatan bengkel.
4. **⋮ → Ekspor CSV** untuk pelanggan, servis, atau nota belum lunas. Berkas CSV dibuka di spreadsheet.

## 11. Jatuh tempo servis

1. **Data bengkel → Jatuh tempo servis** menampilkan kendaraan yang terlewat, hari ini, atau akan jatuh tempo.
2. Ketuk barisnya untuk **Kirim pengingat** (teks siap kirim) atau **Tandai selesai**.
3. Aplikasi **tidak** mengirim pesan atau notifikasi sendiri.

## 12. Bila ada masalah

- Data tidak muncul: pastikan alat scan tersambung dan **Kode Kerusakan** dibuka lebih dulu.
- Ponsel berganti: pulihkan dari berkas ZIP terakhir, lalu periksa **Profil bengkel** dan **Paket pekerjaan**.
- Aplikasi lambat: tutup layar grafik lewat **Pengaturan → Umum → Mode lanjutan** (matikan) bila tidak
  diperlukan.
