package com.fr3ts0n.ecu.gui.androbd.diagnosis.ui;

import android.content.Context;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.DiagnosisMediaStore;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** A temporary silent fixture proves playback state, not any mechanical sound identity. */
@RunWith(AndroidJUnit4.class)
public class DiagnosisPlaybackSuccessInstrumentedTest
{
    @Test public void localAudioCanPlayPauseAndResumeOffline() throws Exception
    {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        DiagnosisMediaStore media = new DiagnosisMediaStore(context);
        File wave = new File(media.resolve("audio"), "playback_test_" + java.util.UUID.randomUUID().toString().replace("-", "") + ".wav");
        assertTrue(wave.getParentFile().isDirectory() || wave.getParentFile().mkdirs());
        int payload = 8000 * 2 * 5;
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(payload + 36).put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII))
                .putInt(16).putShort((short) 1).putShort((short) 1).putInt(8000).putInt(16000).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(payload);
        try (FileOutputStream output = new FileOutputStream(wave)) { output.write(header.array()); output.write(new byte[payload]); }
        CountDownLatch playing = new CountDownLatch(1);
        AtomicReference<DiagnosisAudioPlayer> player = new AtomicReference<>();
        AtomicReference<DiagnosisAudioPlayer.State> state = new AtomicReference<>();
        AtomicReference<Boolean> failed = new AtomicReference<>(false);
        try
        {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                player.set(new DiagnosisAudioPlayer(context, media, new DiagnosisAudioPlayer.Listener() {
                    @Override public void onStateChanged(DiagnosisAudioPlayer.State value) { state.set(value); if (value == DiagnosisAudioPlayer.State.PLAYING) playing.countDown(); }
                    @Override public void onError() { failed.set(true); playing.countDown(); }
                }));
                player.get().togglePrivate("audio/" + wave.getName());
            });
            assertTrue("Local audio never prepared", playing.await(5, TimeUnit.SECONDS));
            assertFalse("Valid local fixture failed playback", failed.get());
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                assertEquals(DiagnosisAudioPlayer.State.PLAYING, state.get());
                player.get().togglePrivate("audio/" + wave.getName());
                assertEquals(DiagnosisAudioPlayer.State.PAUSED, state.get());
                player.get().togglePrivate("audio/" + wave.getName());
                assertEquals(DiagnosisAudioPlayer.State.PLAYING, state.get());
                player.get().release();
                assertEquals(DiagnosisAudioPlayer.State.IDLE, state.get());
            });
        }
        finally
        {
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> { if (player.get() != null) player.get().release(); });
            wave.delete();
        }
    }
}
