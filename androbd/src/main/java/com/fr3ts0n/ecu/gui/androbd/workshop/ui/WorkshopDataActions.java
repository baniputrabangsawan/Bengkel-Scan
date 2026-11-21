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

import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.fr3ts0n.ecu.gui.androbd.R;
import com.fr3ts0n.ecu.gui.androbd.workshop.CsvExporter;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopBackup;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopRepository;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopSettingsRepository;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Backup, restore and CSV export, shared by the customer list and the "Data bengkel" hub.
 *
 * <p>The file work is the same in both places, so it lives here once. Restore runs on its own
 * thread because the archive can be hundreds of megabytes; the caller supplies the callback
 * that refreshes whatever list it is showing.</p>
 */
final class WorkshopDataActions
{
	/** Settings key: when the last successful backup happened. */
	static final String KEY_LAST_BACKUP = "backup.last_at";
	/** Settings key: when the backup nudge was last shown, so it appears once a day. */
	static final String KEY_LAST_NUDGE = "backup.last_nudge";

	private WorkshopDataActions()
	{
	}

	/** Ask which table to write, then hand the file to another app. */
	static void chooseExport(AppCompatActivity activity, WorkshopRepository repository)
	{
		new AlertDialog.Builder(activity)
				.setTitle(R.string.workshop_export_csv)
				.setItems(new String[]{activity.getString(R.string.workshop_export_customers),
						activity.getString(R.string.workshop_export_services),
						activity.getString(R.string.workshop_export_unpaid)},
						(dialog, which) -> export(activity, repository, which))
				.setNegativeButton(R.string.workshop_action_cancel, null)
				.show();
	}

	private static void export(AppCompatActivity activity, WorkshopRepository repository, int which)
	{
		// Writing a CSV reads every row; on a full workshop it is not a UI-thread task.
		new Thread(() ->
		{
			try
			{
				File folder = new File(activity.getExternalFilesDir(null), "exports");
				if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("exports folder");
				String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
				String name = which == 0 ? "pelanggan-" : which == 1 ? "servis-" : "belum-lunas-";
				File target = new File(folder, name + stamp + ".csv");
				try (Writer out = new OutputStreamWriter(new FileOutputStream(target),
						StandardCharsets.UTF_8))
				{
					// BOM so a spreadsheet opens Indonesian text correctly.
					out.write('\uFEFF');
					if (which == 0) CsvExporter.writeCustomers(repository, out);
					else if (which == 1) CsvExporter.writeServices(repository, out);
					else CsvExporter.writeUnpaid(repository, out);
				}
				activity.runOnUiThread(() -> Toast.makeText(activity,
						activity.getString(R.string.workshop_export_done, target.getName()),
						Toast.LENGTH_LONG).show());
			}
			catch (IOException | RuntimeException error)
			{
				activity.runOnUiThread(() ->
						showError(activity, R.string.workshop_export_failed, error));
			}
		}, "workshop-export").start();
	}

	static void backup(AppCompatActivity activity, WorkshopRepository repository)
	{
		// A backup can be hundreds of megabytes of photos; zipping it on the UI thread is the
		// one credible ANR path in the workshop flow.
		new Thread(() ->
		{
			try
			{
				File target = WorkshopBackup.defaultExportFile(activity);
				WorkshopBackup.export(repository.database(), target);
				new WorkshopSettingsRepository(repository.database())
						.put(KEY_LAST_BACKUP, String.valueOf(System.currentTimeMillis()));
				activity.runOnUiThread(() -> new AlertDialog.Builder(activity)
						.setTitle(R.string.workshop_backup_title)
						.setMessage(activity.getString(R.string.workshop_backup_done,
								target.getAbsolutePath()))
						.setPositiveButton(android.R.string.ok, null)
						.setNeutralButton(R.string.workshop_backup_share,
								(dialog, which) -> shareBackup(activity, target))
						.show());
			}
			catch (IOException | RuntimeException error)
			{
				activity.runOnUiThread(() ->
						showError(activity, R.string.workshop_backup_failed, error));
			}
		}, "workshop-backup").start();
	}

	private static void shareBackup(AppCompatActivity activity, File file)
	{
		Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".provider", file);
		Intent send = new Intent(Intent.ACTION_SEND)
				.setType(file.getName().endsWith(".zip")
						? "application/zip" : "application/octet-stream")
				.putExtra(Intent.EXTRA_STREAM, uri)
				.putExtra(Intent.EXTRA_SUBJECT, activity.getString(R.string.workshop_backup_subject))
				.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
		activity.startActivity(Intent.createChooser(send,
				activity.getString(R.string.workshop_backup_share)));
	}

	/** Confirm before replacing data; a safety copy is kept by the restore path itself. */
	static void confirmRestore(AppCompatActivity activity, WorkshopRepository repository, Uri uri)
	{
		new AlertDialog.Builder(activity)
				.setTitle(R.string.workshop_restore_title)
				.setMessage(R.string.workshop_restore_message)
				.setNegativeButton(R.string.workshop_action_cancel, null)
				.setPositiveButton(R.string.workshop_restore_action,
						(dialog, which) -> restore(activity, repository, uri, null))
				.show();
	}

	static void restore(AppCompatActivity activity, WorkshopRepository repository, Uri uri,
			Runnable onDone)
	{
		new Thread(() ->
		{
			String failure = null;
			File temp = new File(activity.getCacheDir(), "restore-" + System.currentTimeMillis() + ".db");
			try (InputStream in = activity.getContentResolver().openInputStream(uri);
				 OutputStream out = new FileOutputStream(temp))
			{
				if (in == null) throw new IOException("Cannot read the selected file");
				byte[] buffer = new byte[8192];
				int read;
				long total = 0;
				while ((read = in.read(buffer)) != -1)
				{
					total += read;
					if (total > 512L * 1024 * 1024) throw new IOException("Backup is too large");
					out.write(buffer, 0, read);
				}
				out.flush();
				WorkshopBackup.restore(repository.database(), temp);
			}
			catch (IOException | RuntimeException error)
			{
				failure = error.getMessage() == null ? error.toString() : error.getMessage();
			}
			finally
			{
				if (temp.exists() && !temp.delete()) temp.deleteOnExit();
			}

			String result = failure;
			activity.runOnUiThread(() ->
			{
				if (result == null)
				{
					Toast.makeText(activity, R.string.workshop_restore_done, Toast.LENGTH_LONG).show();
					if (onDone != null) onDone.run();
				}
				else
				{
					new AlertDialog.Builder(activity)
							.setTitle(R.string.workshop_restore_title)
							.setMessage(activity.getString(R.string.workshop_restore_failed, result))
							.setPositiveButton(android.R.string.ok, null)
							.show();
				}
			});
		}, "workshop-restore").start();
	}

	/**
	 * Gentle reminder after the configured number of days without a backup. Shown at most once
	 * a day; dismissing it postpones it for the day.
	 */
	static void maybeNudgeBackup(AppCompatActivity activity, WorkshopRepository repository,
			Runnable onBackup)
	{
		WorkshopSettingsRepository settings = new WorkshopSettingsRepository(repository.database());
		long days = Math.max(1, settings.getLong(WorkshopSettingsRepository.KEY_BACKUP_REMINDER_DAYS, 7));
		long now = System.currentTimeMillis();
		long lastNudge = settings.getLong(KEY_LAST_NUDGE, 0);
		if (lastNudge > 0 && now - lastNudge < 24L * 60 * 60 * 1000) return;

		long lastBackup = settings.getLong(KEY_LAST_BACKUP, 0);
		long silentSince = lastBackup > 0 ? now - lastBackup : -1;
		if (lastBackup > 0 && silentSince < days * 24L * 60 * 60 * 1000) return;

		settings.put(KEY_LAST_NUDGE, String.valueOf(now));
		String message = lastBackup > 0
				? activity.getString(R.string.workshop_backup_nudge_message,
						(int) (silentSince / (24L * 60 * 60 * 1000)))
				: activity.getString(R.string.workshop_backup_never);
		new AlertDialog.Builder(activity)
				.setTitle(R.string.workshop_backup_nudge_title)
				.setMessage(message)
				.setNegativeButton(R.string.workshop_backup_nudge_later, null)
				.setPositiveButton(R.string.workshop_backup_now, (dialog, which) -> onBackup.run())
				.show();
	}

	private static void showError(AppCompatActivity activity, int format, Exception error)
	{
		String detail = error.getMessage() == null ? error.toString() : error.getMessage();
		new AlertDialog.Builder(activity)
				.setTitle(R.string.workshop_data_title)
				.setMessage(activity.getString(format, detail))
				.setPositiveButton(android.R.string.ok, null)
				.show();
	}
}
