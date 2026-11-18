package com.fr3ts0n.ecu.gui.androbd.diagnosis.data;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@RunWith(AndroidJUnit4.class)
public class DiagnosisMediaStoreInstrumentedTest {
    private final Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();

    @Test public void rejectsTraversalAbsoluteNetworkAndAmbiguousPaths() throws Exception {
        DiagnosisMediaStore store = new DiagnosisMediaStore(context);
        for (String path : new String[]{"../outside", "/tmp/a", "audio/../a", "audio//a",
                "https://example.org/a", "audio\\a", "./audio/a", "audio/./a", "audio/a\u0000"}) {
            try {
                store.resolve(path);
                fail("Accepted unsafe path: " + path);
            } catch (IOException expected) { }
        }
        assertEquals("audio/example.m4a", store.relativePath(store.resolve("audio/example.m4a")));
    }

    @Test public void rejectsNonImageAndCorruptAudioWithoutPublishingFiles() throws Exception {
        DiagnosisMediaStore store = new DiagnosisMediaStore(context);
        File source = File.createTempFile("diagnosis-invalid-", ".bin", context.getCacheDir());
        try {
            try (FileOutputStream output = new FileOutputStream(source)) {
                output.write(new byte[]{1, 2, 3, 4, 5});
            }
            try {
                store.importPhoto(Uri.fromFile(source));
                fail("Accepted non-image");
            } catch (IOException expected) { }
            try {
                store.importAudio(Uri.fromFile(source));
                fail("Accepted corrupt audio");
            } catch (IOException expected) { }
        } finally {
            assertTrue(source.delete());
        }
    }

    @Test public void downsamplesLargeImageToPrivateJpeg() throws Exception {
        DiagnosisMediaStore store = new DiagnosisMediaStore(context);
        File source = File.createTempFile("diagnosis-image-", ".png", context.getCacheDir());
        File imported = null;
        Bitmap bitmap = Bitmap.createBitmap(4096, 1024, Bitmap.Config.ARGB_8888);
        try {
            try (FileOutputStream output = new FileOutputStream(source)) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
            }
            bitmap.recycle();
            imported = store.importPhoto(Uri.fromFile(source));
            assertTrue(store.relativePath(imported).startsWith("photos/"));
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(imported.getPath(), bounds);
            assertTrue(bounds.outWidth <= 2048);
            assertTrue(bounds.outHeight <= 2048);
            assertEquals("image/jpeg", bounds.outMimeType);
        } finally {
            if (!bitmap.isRecycled()) bitmap.recycle();
            if (imported != null) assertTrue(imported.delete());
            assertTrue(source.delete());
        }
    }

    @Test public void recordingCannotBeSavedWithoutConfirmedCase() throws Exception {
        DiagnosisMediaStore store = new DiagnosisMediaStore(context);
        File recording = store.createRecordingFile();
        try {
            writeWave(recording);
            try {
                store.saveClip(new DiagnosisMediaStore.Clip(store.relativePath(recording), "slow_cranking",
                        "Uji rekaman", "", "", 0, System.currentTimeMillis()));
                fail("Saved an unassociated recording");
            } catch (IOException expected) { }
        } finally {
            assertTrue(recording.delete());
        }
    }
    @Test public void importedAudioMetadataRoundTripsAndCorruptionIsNotHidden() throws Exception {
        DiagnosisMediaStore store = new DiagnosisMediaStore(context);
        File source = File.createTempFile("diagnosis-audio-", ".wav", context.getCacheDir());
        File imported = null;
        File metadata = null;
        String soundId = "test_" + UUID.randomUUID();
        try {
            writeWave(source);
            imported = store.importAudio(Uri.fromFile(source));
            String path = store.relativePath(imported);
            store.saveClip(new DiagnosisMediaStore.Clip(path, soundId, "Suara uji", "", "", 0, 12345));
            assertEquals(1, store.clips(soundId).size());
            DiagnosisMediaStore.Clip loaded = store.clips(soundId).get(0);
            assertEquals(path, loaded.relativePath);
            assertEquals("Suara uji", loaded.label);
            assertEquals(0, loaded.caseId);
            assertEquals("", loaded.causeId);
            metadata = new File(imported.getParentFile(),
                    imported.getName().substring(0, imported.getName().lastIndexOf('.')) + ".json");
            try (FileOutputStream output = new FileOutputStream(metadata)) {
                output.write("{rusak".getBytes(StandardCharsets.UTF_8));
            }
            try {
                store.clips(soundId);
                fail("Corrupt metadata was hidden");
            } catch (IOException expected) { }
        } finally {
            if (metadata != null) assertTrue(metadata.delete());
            if (imported != null) assertTrue(imported.delete());
            assertTrue(source.delete());
        }
    }

    private static void writeWave(File destination) throws IOException {
        int samples = 1600;
        ByteBuffer wave = ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN);
        wave.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + samples * 2);
        wave.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
        wave.putShort((short) 1).putShort((short) 1).putInt(16000).putInt(32000);
        wave.putShort((short) 2).putShort((short) 16);
        wave.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(samples * 2);
        try (FileOutputStream output = new FileOutputStream(destination)) {
            output.write(wave.array());
        }
    }
}
