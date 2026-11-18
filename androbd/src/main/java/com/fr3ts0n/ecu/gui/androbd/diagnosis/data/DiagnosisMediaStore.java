/* (C) 2026 Bengkel Scan contributors. GPL-2.0-or-later. */
package com.fr3ts0n.ecu.gui.androbd.diagnosis.data;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.AtomicFile;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Private, bounded media imports. All paths are relative to the backed-up diagnosis directory. */
public final class DiagnosisMediaStore {
    private static final Pattern UNSAFE_PATH_CHARACTERS = Pattern.compile("[\\\\:%?#\\p{Cntrl}]");
    private static final long MAX_PHOTO_BYTES = 20L * 1024 * 1024;
    private static final long MAX_AUDIO_BYTES = 32L * 1024 * 1024;
    private static final int MAX_IMAGE_EDGE = 2048;
    private static final int MAX_METADATA_BYTES = 64 * 1024;
    private final Context context;
    private final File root;

    public DiagnosisMediaStore(Context context) {
        this.context = context.getApplicationContext();
        root = new File(this.context.getFilesDir(), "diagnosis");
    }

    public File directory() {
        return root;
    }

    public File resolve(String relativePath) throws IOException {
        if (relativePath == null || relativePath.isEmpty() || relativePath.startsWith("/")
                || UNSAFE_PATH_CHARACTERS.matcher(relativePath).find()) {
            throw new IOException("Jalur media tidak aman.");
        }
        for (String segment : relativePath.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IOException("Jalur media tidak aman.");
            }
        }
        File canonicalRoot = root.getCanonicalFile();
        File expectedRoot = new File(context.getFilesDir().getCanonicalFile(), "diagnosis");
        File result = new File(canonicalRoot, relativePath);
        File canonical = result.getCanonicalFile();
        if (!canonicalRoot.equals(expectedRoot) || !canonical.equals(result.getAbsoluteFile())
                || !canonical.getPath().startsWith(canonicalRoot.getPath() + File.separator)) {
            throw new IOException("Jalur media keluar dari penyimpanan pribadi.");
        }
        return canonical;
    }

    public String relativePath(File file) throws IOException {
        if (file == null) throw new IOException("Berkas media tidak tersedia.");
        String prefix = root.getCanonicalPath() + File.separator;
        String absolute = file.getAbsolutePath();
        if (!absolute.startsWith(prefix)) throw new IOException("Berkas bukan media diagnosis pribadi.");
        String relative = absolute.substring(prefix.length());
        if (!resolve(relative).equals(file.getCanonicalFile())) {
            throw new IOException("Jalur media tidak aman.");
        }
        return relative;
    }

    public File importPhoto(Uri uri) throws IOException {
        File source = File.createTempFile("diagnosis-photo-", ".tmp", context.getCacheDir());
        File destination = null;
        Bitmap image = null;
        boolean complete = false;
        try {
            copyUri(uri, source, MAX_PHOTO_BYTES);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(source.getPath(), options);
            if (options.outWidth <= 0 || options.outHeight <= 0 || options.outMimeType == null
                    || !options.outMimeType.startsWith("image/")
                    || options.outWidth > 100000 || options.outHeight > 100000) {
                throw new IOException("Berkas bukan gambar yang didukung.");
            }
            int sample = 1;
            while ((options.outWidth + sample - 1) / sample > MAX_IMAGE_EDGE
                    || (options.outHeight + sample - 1) / sample > MAX_IMAGE_EDGE) sample *= 2;
            options.inJustDecodeBounds = false;
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            image = BitmapFactory.decodeFile(source.getPath(), options);
            if (image == null) throw new IOException("Gambar rusak atau tidak dapat dibaca.");
            destination = newFile("photos", "photo_", ".jpg");
            try (FileOutputStream output = new FileOutputStream(destination)) {
                if (!image.compress(Bitmap.CompressFormat.JPEG, 88, output)) {
                    throw new IOException("Foto tidak dapat disimpan.");
                }
                output.getFD().sync();
            }
            complete = true;
            return destination;
        } catch (OutOfMemoryError error) {
            throw new IOException("Gambar terlalu besar untuk diproses.", error);
        } finally {
            if (image != null) image.recycle();
            removeTemporary(source);
            if (!complete && destination != null) removeTemporary(destination);
        }
    }

    public File importAudio(Uri uri) throws IOException {
        File destination = newFile("audio", "import_", ".audio");
        boolean complete = false;
        try {
            copyUri(uri, destination, MAX_AUDIO_BYTES);
            validateAudio(destination);
            complete = true;
            return destination;
        } finally {
            if (!complete) removeTemporary(destination);
        }
    }

    public File createRecordingFile() throws IOException {
        return newFile("audio", "recording_", ".m4a");
    }

    public void saveClip(Clip clip) throws IOException {
        validateClip(clip);
        File audio = resolve(clip.relativePath);
        validateAudio(audio);
        if (audio.getName().startsWith("recording_") && clip.caseId <= 0) {
            throw new IOException("Rekaman harus terkait kasus yang dikonfirmasi mekanik.");
        }
        // Case existence and confirmation are checked by the sound screen before saving.
        if (clip.caseId > 0 && clip.causeName.trim().isEmpty()) {
            throw new IOException("Rekaman belum memiliki diagnosis akhir kasus.");
        }
        if (clip.caseId == 0 && (!clip.causeId.isEmpty() || !clip.causeName.isEmpty())) {
            throw new IOException("Audio impor belum memiliki penyebab terkonfirmasi.");
        }
        JSONObject metadata = new JSONObject();
        try {
            metadata.put("schema_version", 1);
            metadata.put("relative_path", clip.relativePath);
            metadata.put("sound_id", clip.soundId);
            metadata.put("label", clip.label);
            metadata.put("cause_id", clip.causeId);
            metadata.put("cause_name", clip.causeName);
            metadata.put("case_id", clip.caseId);
            metadata.put("created_at", clip.createdAt);
        } catch (JSONException error) {
            throw new IOException("Metadata audio tidak dapat dibuat.", error);
        }
        byte[] bytes = metadata.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_METADATA_BYTES) throw new IOException("Metadata audio terlalu besar.");
        AtomicFile atomic = new AtomicFile(sidecar(audio));
        FileOutputStream output = null;
        try {
            output = atomic.startWrite();
            output.write(bytes);
            atomic.finishWrite(output);
        } catch (IOException error) {
            if (output != null) atomic.failWrite(output);
            throw error;
        }
    }

    public List<Clip> clips(String soundId) throws IOException {
        File folder = resolve("audio");
        if (!folder.exists()) return Collections.emptyList();
        File[] entries = folder.listFiles();
        if (entries == null) throw new IOException("Daftar audio pribadi tidak dapat dibaca.");
        List<Clip> result = new ArrayList<>();
        Set<String> metadataNames = new HashSet<>();
        for (File entry : entries) {
            String name = entry.getName();
            if (name.endsWith(".json.bak")) name = name.substring(0, name.length() - 4);
            if (!name.endsWith(".json") || !metadataNames.add(name)) continue;
            File safe = resolve("audio/" + name);
            try (InputStream input = new AtomicFile(safe).openRead();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                copyBounded(input, output, MAX_METADATA_BYTES);
                JSONObject json = new JSONObject(new String(output.toByteArray(), StandardCharsets.UTF_8));
                if (json.getInt("schema_version") != 1) throw new JSONException("Versi metadata tidak didukung.");
                Clip clip = new Clip(json.getString("relative_path"), json.getString("sound_id"),
                        json.getString("label"), json.getString("cause_id"),
                        json.getString("cause_name"), json.getLong("case_id"), json.getLong("created_at"));
                validateClip(clip);
                File audio = resolve(clip.relativePath);
                if (!sidecar(audio).equals(safe) || !audio.isFile() || audio.length() == 0) {
                    throw new IOException("Berkas audio pribadi hilang atau metadata tidak cocok.");
                }
                if (audio.getName().startsWith("recording_") && clip.caseId <= 0) {
                    throw new IOException("Metadata rekaman tidak memiliki kasus terkonfirmasi.");
                }
                if (soundId == null || soundId.equals(clip.soundId)) result.add(clip);
            } catch (JSONException error) {
                throw new IOException("Metadata audio pribadi rusak.", error);
            }
        }
        Collections.sort(result, (left, right) -> {
            int time = Long.compare(right.createdAt, left.createdAt);
            return time != 0 ? time : left.relativePath.compareTo(right.relativePath);
        });
        return result;
    }

    private void validateClip(Clip clip) throws IOException {
        if (clip == null || clip.relativePath == null || clip.soundId == null || clip.soundId.trim().isEmpty()
                || clip.label == null || clip.label.trim().isEmpty() || clip.label.length() > 200
                || clip.causeId == null || clip.causeName == null || clip.caseId < 0 || clip.createdAt <= 0
                || !clip.relativePath.matches("audio/[A-Za-z0-9_-]+\\.[A-Za-z0-9]+")) {
            throw new IOException("Metadata audio belum lengkap atau tidak valid.");
        }
        resolve(clip.relativePath);
    }

    private File sidecar(File audio) {
        String name = audio.getName();
        return new File(audio.getParentFile(), name.substring(0, name.lastIndexOf('.')) + ".json");
    }

    private File newFile(String folder, String prefix, String suffix) throws IOException {
        File parent = resolve(folder);
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Folder media tidak dapat dibuat.");
        File result = resolve(folder + "/" + prefix + UUID.randomUUID() + suffix);
        if (!result.createNewFile()) throw new IOException("Berkas media tidak dapat dibuat.");
        return result;
    }

    private void copyUri(Uri uri, File destination, long maximum) throws IOException {
        if (uri == null || !("content".equals(uri.getScheme()) || "file".equals(uri.getScheme()))) {
            throw new IOException("Pilih berkas lokal melalui pemilih berkas.");
        }
        try (InputStream input = context.getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(destination)) {
            if (input == null) throw new IOException("Berkas pilihan tidak dapat dibuka.");
            if (copyBounded(input, output, maximum) == 0) throw new IOException("Berkas pilihan kosong.");
            output.getFD().sync();
        } catch (SecurityException error) {
            throw new IOException("Izin membaca berkas tidak tersedia.", error);
        }
    }

    private static long copyBounded(InputStream input, OutputStream output, long maximum) throws IOException {
        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maximum) throw new IOException("Ukuran berkas melebihi batas impor.");
            output.write(buffer, 0, read);
        }
        return total;
    }

    private static void validateAudio(File file) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            if (!file.isFile() || file.length() == 0 || file.length() > MAX_AUDIO_BYTES) {
                throw new IOException("Berkas audio kosong atau terlalu besar.");
            }
            extractor.setDataSource(file.getPath());
            for (int index = 0; index < extractor.getTrackCount(); index++) {
                MediaFormat format = extractor.getTrackFormat(index);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) return;
            }
            throw new IOException("Berkas tidak memiliki audio yang didukung.");
        } catch (RuntimeException error) {
            throw new IOException("Audio rusak atau formatnya tidak didukung.", error);
        } finally {
            extractor.release();
        }
    }

    private static void removeTemporary(File file) throws IOException {
        if (file.exists() && !file.delete()) throw new IOException("Berkas sementara tidak dapat dibersihkan.");
    }

    public static final class Clip {
        public final String relativePath, soundId, label, causeId, causeName;
        public final long caseId, createdAt;

        public Clip(String relativePath, String soundId, String label, String causeId, String causeName,
                    long caseId, long createdAt) {
            this.relativePath = relativePath;
            this.soundId = soundId;
            this.label = label;
            this.causeId = causeId;
            this.causeName = causeName;
            this.caseId = caseId;
            this.createdAt = createdAt;
        }
    }
}
