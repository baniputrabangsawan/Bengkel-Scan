/*
 * (C) Copyright 2026 by the Bengkel Scan contributors.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2 of
 * the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 */
package com.fr3ts0n.ecu.gui.androbd.workshop.ui;

import android.media.MediaRecorder;

import java.io.File;
import java.io.IOException;

/**
 * One short foreground voice note.
 *
 * <p>Used for sound guesses and for notes on an inspected part. Recording only
 * starts when the mechanic presses the button, never exceeds the limit, and is
 * stopped by the caller when the screen goes away.</p>
 */
public final class VoiceNoteRecorder
{
	/** Long enough for one sentence, short enough to stay a note. */
	public static final int MAX_DURATION_MS = 15000;

	private MediaRecorder recorder;
	private File file;
	private Runnable onLimitReached;
	private Runnable onError;

	/** True while a file is being written. */
	public boolean recording()
	{
		return recorder != null;
	}

	/**
	 * Start writing to {@code target}.
	 *
	 * @param onLimit called when the limit is reached, so the caller can finish
	 * @param onError called when the platform reports a failure
	 * @throws IOException when the recorder cannot be prepared
	 */
	public void start(File target, Runnable onLimit, Runnable onError) throws IOException
	{
		if (recorder != null) throw new IOException("note already recording");
		MediaRecorder next;
		try
		{
			next = new MediaRecorder();
		}
		catch (RuntimeException error)
		{
			throw new IOException("recorder unavailable", error);
		}
		file = target;
		this.onLimitReached = onLimit;
		this.onError = onError;
		try
		{
			next.setAudioSource(MediaRecorder.AudioSource.MIC);
			next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
			next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
			next.setAudioSamplingRate(44100);
			next.setAudioEncodingBitRate(96000);
			next.setMaxDuration(MAX_DURATION_MS);
			next.setOutputFile(target.getPath());
			next.setOnInfoListener((source, what, extra) ->
			{
				if (source == recorder && what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED
						&& this.onLimitReached != null)
				{
					this.onLimitReached.run();
				}
			});
			next.setOnErrorListener((source, what, extra) ->
			{
				if (source == recorder && this.onError != null) this.onError.run();
			});
			next.prepare();
			next.start();
			recorder = next;
		}
		catch (IOException | RuntimeException error)
		{
			next.release();
			recorder = null;
			throw new IOException("recorder failed", error);
		}
	}

	/**
	 * Stop recording.
	 *
	 * @return the written file, or {@code null} when nothing usable was captured
	 */
	public File stopKeeping()
	{
		File written = stop();
		if (written == null) return null;
		return written;
	}

	/** Stop recording and delete the partial file. */
	public void stopAndDiscard()
	{
		File written = stop();
		if (written != null && written.exists()) written.delete();
	}

	private File stop()
	{
		MediaRecorder previous = recorder;
		if (previous == null) return null;
		recorder = null;
		onLimitReached = null;
		onError = null;
		boolean stopped = false;
		try
		{
			previous.setOnInfoListener(null);
			previous.setOnErrorListener(null);
			previous.stop();
			stopped = true;
		}
		catch (RuntimeException error)
		{
			stopped = false;
		}
		finally
		{
			previous.release();
		}
		File written = file;
		file = null;
		return stopped ? written : null;
	}
}
