/* (C) 2026 Bengkel Scan contributors. GPL-2.0-or-later. */
package com.fr3ts0n.ecu.gui.androbd.diagnosis.ui;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;

import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.DiagnosisMediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/** Main-thread player for confined private files and bundled offline sound assets. */
public final class DiagnosisAudioPlayer {
    public enum State { IDLE, PREPARING, PLAYING, PAUSED }

    public interface Listener {
        void onStateChanged(State state);
        void onError();
    }

    private final Context context;
    private final DiagnosisMediaStore media;
    private final Listener listener;
    private MediaPlayer player;
    private String currentKey;
    private State state = State.IDLE;

    public DiagnosisAudioPlayer(Context context, DiagnosisMediaStore media, Listener listener) {
        this.context = context.getApplicationContext();
        this.media = media;
        this.listener = listener;
    }

    public void togglePrivate(String relativePath) {
        toggle(relativePath, false);
    }

    public void toggleReference(String relativePath) {
        toggle(relativePath, true);
    }

    private void toggle(String relativePath, boolean bundled) {
        String key = (bundled ? "reference:" : "private:") + relativePath;
        if (player != null && key.equals(currentKey)) {
            try {
                if (state == State.PLAYING) {
                    player.pause();
                    changeState(State.PAUSED);
                } else if (state == State.PAUSED) {
                    player.start();
                    changeState(State.PLAYING);
                }
            } catch (IllegalStateException error) {
                fail();
            }
            return;
        }
        release();
        MediaPlayer next;
        try {
            next = new MediaPlayer();
        } catch (RuntimeException error) {
            listener.onError();
            return;
        }
        player = next;
        currentKey = key;
        next.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build());
        next.setOnPreparedListener(prepared -> {
            if (player != prepared) return;
            try {
                prepared.start();
                changeState(State.PLAYING);
            } catch (IllegalStateException error) {
                fail();
            }
        });
        next.setOnCompletionListener(completed -> {
            if (player == completed) release();
        });
        next.setOnErrorListener((failed, what, extra) -> {
            if (player == failed) fail();
            return true;
        });
        try {
            File file = media.resolve(relativePath);
            if (file.exists() || !bundled) {
                if (!file.isFile()) throw new IOException("Audio pribadi tidak tersedia.");
                try (FileInputStream input = new FileInputStream(file)) {
                    next.setDataSource(input.getFD());
                }
            } else {
                try (AssetFileDescriptor asset = context.getAssets().openFd("diagnosis/" + relativePath)) {
                    next.setDataSource(asset.getFileDescriptor(), asset.getStartOffset(), asset.getLength());
                }
            }
            changeState(State.PREPARING);
            next.prepareAsync();
        } catch (IOException | RuntimeException error) {
            fail();
        }
    }

    public void release() {
        MediaPlayer previous = player;
        player = null;
        currentKey = null;
        if (previous != null) {
            previous.setOnPreparedListener(null);
            previous.setOnCompletionListener(null);
            previous.setOnErrorListener(null);
            previous.release();
        }
        changeState(State.IDLE);
    }

    private void fail() {
        release();
        listener.onError();
    }

    private void changeState(State value) {
        state = value;
        listener.onStateChanged(value);
    }
}
