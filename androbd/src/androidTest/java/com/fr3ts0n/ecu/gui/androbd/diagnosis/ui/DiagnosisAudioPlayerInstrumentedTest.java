package com.fr3ts0n.ecu.gui.androbd.diagnosis.ui;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.DiagnosisMediaStore;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class DiagnosisAudioPlayerInstrumentedTest {
    @Test public void corruptPrivateAudioReportsErrorAndReleasesPlayer() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DiagnosisMediaStore media = new DiagnosisMediaStore(context);
        File audio = media.createRecordingFile();
        AtomicReference<DiagnosisAudioPlayer.State> state = new AtomicReference<>();
        AtomicReference<DiagnosisAudioPlayer> player = new AtomicReference<>();
        CountDownLatch error = new CountDownLatch(1);
        try {
            try (FileOutputStream output = new FileOutputStream(audio)) {
                output.write(new byte[]{0, 1, 2, 3});
            }
            String path = media.relativePath(audio);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                player.set(new DiagnosisAudioPlayer(context, media, new DiagnosisAudioPlayer.Listener() {
                    @Override public void onStateChanged(DiagnosisAudioPlayer.State value) { state.set(value); }
                    @Override public void onError() { error.countDown(); }
                }));
                player.get().togglePrivate(path);
            });
            assertTrue("Corrupt playback must report an error", error.await(5, TimeUnit.SECONDS));
            assertEquals(DiagnosisAudioPlayer.State.IDLE, state.get());
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                if (player.get() != null) player.get().release();
            });
            assertTrue(audio.delete());
        }
    }

    @Test public void assetTraversalReportsErrorInsteadOfOpeningOutsideDiagnosis() {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        AtomicReference<DiagnosisAudioPlayer.State> state = new AtomicReference<>();
        CountDownLatch error = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            DiagnosisAudioPlayer player = new DiagnosisAudioPlayer(context, new DiagnosisMediaStore(context),
                    new DiagnosisAudioPlayer.Listener() {
                        @Override public void onStateChanged(DiagnosisAudioPlayer.State value) { state.set(value); }
                        @Override public void onError() { error.countDown(); }
                    });
            try {
                player.toggleReference("../knowledge-base.json");
                assertEquals(0, error.getCount());
                assertEquals(DiagnosisAudioPlayer.State.IDLE, state.get());
            } finally {
                player.release();
            }
        });
    }
}
