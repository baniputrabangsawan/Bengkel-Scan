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
import android.os.Bundle;
import android.util.Log;
import android.view.MenuItem;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import com.fr3ts0n.ecu.gui.androbd.ModernUiUtils;
import com.fr3ts0n.ecu.gui.androbd.R;
import com.fr3ts0n.ecu.gui.androbd.SettingsActivity;
import com.fr3ts0n.ecu.gui.androbd.workshop.Customer;
import com.fr3ts0n.ecu.gui.androbd.workshop.EstimateItem;
import com.fr3ts0n.ecu.gui.androbd.workshop.ServiceRecord;
import com.fr3ts0n.ecu.gui.androbd.workshop.Vehicle;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopRepository;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopSettingsRepository;
import com.fr3ts0n.ecu.gui.androbd.workshop.pdf.WorkshopPdf;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * Generate the printable report and invoice for one service, then share them.
 *
 * <p>Files are written under the app's own external folder, which is the only
 * place the file provider exposes. Sharing goes through the system chooser so
 * WhatsApp or any other installed app can take the PDF.</p>
 */
public class WorkshopPdfActivity extends AppCompatActivity
{
	private static final String TAG = "WorkshopPdf";

	private WorkshopRepository repository;
	private long serviceId;
	private File report;
	private File invoice;

	@Override
	protected void onCreate(Bundle savedInstanceState)
	{
		SettingsActivity.applyLocale(this);
		super.onCreate(savedInstanceState);
		serviceId = getIntent().getLongExtra(WorkshopUi.EXTRA_SERVICE_ID, -1);
		repository = WorkshopUi.repository(this);
		if (repository.findService(serviceId) == null)
		{
			finish();
			return;
		}
		setContentView(R.layout.workshop_pdf);
		ModernUiUtils.applyScreenInsets(this);
		WorkshopUi.prepare(this, getString(R.string.workshop_pdf_title));

		findViewById(R.id.workshop_pdf_report).setOnClickListener(view -> make(true));
		findViewById(R.id.workshop_pdf_invoice).setOnClickListener(view -> make(false));
		findViewById(R.id.workshop_pdf_share).setOnClickListener(view -> shareFile());
		findViewById(R.id.workshop_pdf_share_text).setOnClickListener(view -> shareText());
	}

	private WorkshopPdf.Labels labels()
	{
		return new WorkshopPdf.Labels(
				getString(R.string.workshop_pdf_report),
				getString(R.string.workshop_pdf_invoice),
				getString(R.string.workshop_customer_name),
				getString(R.string.workshop_vehicles_title),
				getString(R.string.workshop_service_complaint),
				getString(R.string.workshop_service_status_label),
				getString(R.string.workshop_customers_title) + " →",
				getString(R.string.workshop_scan_codes),
				getString(R.string.workshop_inspection_title),
				getString(R.string.workshop_estimate_heading),
				getString(R.string.workshop_approval_approved_total),
				getString(R.string.workshop_service_notes),
				getString(R.string.workshop_no_phone),
				getString(R.string.workshop_inspection_empty),
				getString(R.string.workshop_inspection_empty),
				getString(R.string.workshop_estimate_empty),
				getString(R.string.workshop_service_status_label),
				getString(R.string.workshop_payment_paid),
				getString(R.string.workshop_estimate_total),
				getString(R.string.workshop_estimate_linked),
				getString(R.string.diagnosis_disclaimer),
				getString(R.string.workshop_inspection_state_good),
				getString(R.string.workshop_inspection_state_attention),
				getString(R.string.workshop_inspection_state_broken),
				getString(R.string.workshop_scan_demo_warning),
				"",
				getString(R.string.workshop_status_waiting),
				getString(R.string.workshop_status_in_progress),
				getString(R.string.workshop_status_done),
				getString(R.string.workshop_payment_paid),
				getString(R.string.workshop_payment_unpaid));
	}

	/**
	 * Write one document on a worker thread. The PDF embeds every inspection photo, so decoding
	 * and drawing them on the main thread stutters or ANRs on a job with a dozen pictures.
	 */
	private void make(boolean asReport)
	{
		state().setText(R.string.workshop_pdf_working);
		new Thread(() ->
		{
			try
			{
				File directory = new File(getExternalFilesDir(null), "exports");
				if (!directory.isDirectory() && !directory.mkdirs())
				{
					throw new IOException("exports folder");
				}
				String stamp = serviceId + "-" + System.currentTimeMillis();
				File target = new File(directory,
						(asReport ? "laporan-" : "nota-") + stamp + ".pdf");
				try (OutputStream output = new FileOutputStream(target))
				{
					if (asReport)
					{
						WorkshopPdf.writeReport(this, repository, serviceId, labels(), output);
					}
					else
					{
						WorkshopPdf.writeInvoice(this, repository, serviceId, labels(), output);
					}
				}
				runOnUiThread(() ->
				{
					if (asReport) report = target;
					else invoice = target;
					state().setText(getString(R.string.workshop_pdf_made, target.getAbsolutePath()));
				});
			}
			catch (IOException | RuntimeException error)
			{
				Log.e(TAG, "Document failed", error);
				runOnUiThread(() -> state().setText(getString(R.string.workshop_pdf_failed,
						error.getMessage() == null ? error.getClass().getSimpleName()
								: error.getMessage())));
			}
		}, "workshop-pdf").start();
	}

	private TextView state()
	{
		return findViewById(R.id.workshop_pdf_state);
	}

	/** Share whichever document exists; make the report first if none does. */
	private void shareFile()
	{
		File target = invoice != null ? invoice : report;
		if (target == null)
		{
			make(true);
			target = report;
		}
		if (target == null) return;
		Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".provider", target);
		Intent send = new Intent(Intent.ACTION_SEND)
				.setType("application/pdf")
				.putExtra(Intent.EXTRA_STREAM, uri)
				.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.workshop_pdf_title))
				.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
		startActivity(Intent.createChooser(send, getString(R.string.workshop_pdf_share)));
	}

	/** Prefilled message, so the mechanic can send a summary without a PDF. */
	private void shareText()
	{
		ServiceRecord service = repository.findService(serviceId);
		Vehicle vehicle = service == null ? null : repository.findVehicle(service.vehicleId);
		Customer customer = vehicle == null ? null : repository.findCustomer(vehicle.customerId);
		List<EstimateItem> approved = repository.approvedItems(serviceId);
		StringBuilder lines = new StringBuilder();
		for (EstimateItem item : approved)
		{
			if (lines.length() > 0) lines.append('\n');
			lines.append("• ").append(item.description).append(" — ")
					.append(WorkshopUi.money(item.lineTotal()));
		}
		String body = getString(R.string.workshop_pdf_text_body,
				customer == null ? getString(R.string.workshop_pdf_no_customer) : customer.name,
				vehicle == null ? "" : vehicle.plate,
				lines.toString(),
				WorkshopUi.money(repository.approvedTotal(serviceId)),
				getString(R.string.diagnosis_disclaimer));
		Intent send = new Intent(Intent.ACTION_SEND)
				.setType("text/plain")
				.putExtra(Intent.EXTRA_TEXT, body);
		startActivity(Intent.createChooser(send, getString(R.string.workshop_pdf_share_text)));
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item)
	{
		if (item.getItemId() == android.R.id.home)
		{
			finish();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}
}
