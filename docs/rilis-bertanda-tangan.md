# Membangun APK rilis bertanda tangan

APK rilis tanpa tanda tangan (`assembleRelease`) tidak bisa dipasang. Ikuti langkah ini **sekali**, lalu simpan
keystore dan kata sandinya dengan aman.

> **Jangan pernah** menaruh berkas keystore, `keystore.properties`, atau kata sandi di Git. `.gitignore` sudah
> memblokir `*.jks`, `*.keystore`, dan `local.properties`; tetap periksa `git status` sebelum commit.

## 1. Buat keystore

```bash
keytool -genkeypair -v \
  -keystore ~/bengkel-release.jks \
  -alias bengkel \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -storetype JKS
```

- `-validity 10000` hari (sekitar 27 tahun). Google Play mensyaratkan masa berlaku panjang; untuk pemasangan
  internal pun keystore yang sama sebaiknya dipakai selamanya.
- Simpan **kata sandi keystore**, **kata sandi kunci**, dan **alias**. Tanpa ketiganya APK tidak bisa
  diperbarui di ponsel yang sudah memasang versi sebelumnya.
- Salin berkas `.jks` ke dua tempat aman (misalnya arsip terenkripsi dan USB yang disimpan terpisah).
  Kehilangan keystore berarti pemilik bengkel harus mencopot aplikasi (data hilang) sebelum memasang build baru.

## 2. Beri tahu Gradle (jangan di-commit)

Buat berkas `~/.gradle/gradle.properties` **atau** `keystore.properties` di root proyek (sudah di-`gitignore`):

```properties
BENGKEL_STORE_FILE=/home/USER/bengkel-release.jks
BENGKEL_STORE_PASSWORD=isi-sendiri
BENGKEL_KEY_ALIAS=bengkel
BENGKEL_KEY_PASSWORD=isi-sendiri
```

## 3. Tambahkan blok signing ke `androbd/build.gradle`

Tambahkan **di dalam** blok `android { }`, sebelum `buildTypes`:

```groovy
    signingConfigs {
        release {
            storeFile file(System.getenv("BENGKEL_STORE_FILE") ?: "missing.jks")
            storePassword System.getenv("BENGKEL_STORE_PASSWORD")
            keyAlias System.getenv("BENGKEL_KEY_ALIAS")
            keyPassword System.getenv("BENGKEL_KEY_PASSWORD")
        }
    }
```

lalu pada `buildTypes { release { ... } }` tambahkan:

```groovy
            signingConfig signingConfigs.release
```

Blok ini sengaja **belum** ada di repositori: nilainya berasal dari lingkungan, bukan dari kode. Ekspor variabel
sebelum membangun:

```bash
export BENGKEL_STORE_FILE="$HOME/bengkel-release.jks"
export BENGKEL_STORE_PASSWORD='…'
export BENGKEL_KEY_ALIAS=bengkel
export BENGKEL_KEY_PASSWORD='…'
```

## 4. Bangun dan pasang

```bash
./gradlew :androbd:assembleRelease
ls -l androbd/build/outputs/apk/release/
adb -s <serial> install -r androbd/build/outputs/apk/release/androbd-release.apk
```

Nama berkas dapat berakhiran `-unsigned.apk` bila blok signing belum diisi; dalam hal itu APK **tidak** bisa
dipasang dan langkah 3 belum selesai.

## 5. Verifikasi tanda tangan

```bash
apksigner verify --print-certs androbd/build/outputs/apk/release/androbd-release.apk
```

Pastikan sidik jari sertifikat sama dengan keystore Anda. Untuk memperbarui aplikasi yang sudah terpasang,
tanda tangannya **harus** sama; bila berbeda, Android menolak dengan `INSTALL_FAILED_UPDATE_INCOMPATIBLE` dan
satu-satunya jalan adalah mencopot aplikasi (data hilang) — cadangkan ZIP lebih dulu.

## 6. Yang tidak boleh dilakukan

- Jangan menaruh kata sandi di `build.gradle`, README, atau commit message.
- Jangan membagikan APK bertanda tangan ke publik; lisensi GPL-3.0 mewajibkan penyediaan source bila build
  didistribusikan.
- Jangan menimpa keystore lama saat "membuat ulang" tanda tangan.
