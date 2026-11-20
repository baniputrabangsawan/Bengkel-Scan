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
package com.fr3ts0n.ecu.gui.androbd.workshop.pdf;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.fr3ts0n.ecu.gui.androbd.workshop.Customer;
import com.fr3ts0n.ecu.gui.androbd.workshop.EstimateItem;
import com.fr3ts0n.ecu.gui.androbd.workshop.InspectionItem;
import com.fr3ts0n.ecu.gui.androbd.workshop.InspectionState;
import com.fr3ts0n.ecu.gui.androbd.workshop.Photo;
import com.fr3ts0n.ecu.gui.androbd.workshop.PhotoOwner;
import com.fr3ts0n.ecu.gui.androbd.workshop.PhotoRepository;
import com.fr3ts0n.ecu.gui.androbd.workshop.ServiceRecord;
import com.fr3ts0n.ecu.gui.androbd.workshop.Vehicle;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopProfile;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopRepository;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopSettingsRepository;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Builds the two printable documents for one service.
 *
 * <p>All labels are passed in by the caller from string resources, so the
 * printed language follows the app language. Numbers are formatted the same way
 * the screen shows them.</p>
 */
public final class WorkshopPdf
{
	private WorkshopPdf()
	{
	}

	/** Labels the caller supplies, already localized. */
	public static final class Labels
	{
		public final String reportTitle;
		public final String invoiceTitle;
		public final String customer;
		public final String vehicle;
		public final String complaint;
		public final String dropOff;
		public final String customerDropsOff;
		public final String scannedCodes;
		public final String inspectedParts;
		public final String estimate;
		public final String approvedTotal;
		public final String notes;
		public final String notRecorded;
		public final String noCodes;
		public final String noParts;
		public final String noItems;
		public final String status;
		public final String payment;
		public final String total;
		public final String link;
		public final String disclaimer;
		public final String good;
		public final String attention;
		public final String broken;
		public final String demoData;
		public final String sticker;

		@SuppressWarnings("checkstyle:ParameterNumber")
		public Labels(String reportTitle, String invoiceTitle, String customer, String vehicle,
				String complaint, String dropOff, String customerDropsOff, String scannedCodes,
				String inspectedParts, String estimate, String approvedTotal, String notes,
				String notRecorded, String noCodes, String noParts, String noItems, String status,
				String payment, String total, String link, String disclaimer, String good,
				String attention, String broken, String demoData, String sticker)
		{
			this.reportTitle = reportTitle;
			this.invoiceTitle = invoiceTitle;
			this.customer = customer;
			this.vehicle = vehicle;
			this.complaint = complaint;
			this.dropOff = dropOff;
			this.customerDropsOff = customerDropsOff;
			this.scannedCodes = scannedCodes;
			this.inspectedParts = inspectedParts;
			this.estimate = estimate;
			this.approvedTotal = approvedTotal;
			this.notes = notes;
			this.notRecorded = notRecorded;
			this.noCodes = noCodes;
			this.noParts = noParts;
			this.noItems = noItems;
			this.status = status;
			this.payment = payment;
			this.total = total;
			this.link = link;
			this.disclaimer = disclaimer;
			this.good = good;
			this.attention = attention;
			this.broken = broken;
			this.demoData = demoData;
			this.sticker = sticker;
		}
	}

	/** Report: identity, complaint, the before scan in words, inspection with photos, estimate. */
	public static void writeReport(Context context, WorkshopRepository repository, long serviceId,
			Labels labels, OutputStream output) throws IOException
	{
		ServiceRecord service = repository.findService(serviceId);
		if (service == null) throw new IOException("service missing");
		Vehicle vehicle = repository.findVehicle(service.vehicleId);
		Customer customer = vehicle == null ? null : repository.findCustomer(vehicle.customerId);
		WorkshopSettingsRepository settings = new WorkshopSettingsRepository(repository.database());
		WorkshopProfile profile = settings.profile();

		try (PdfPageWriter writer = new PdfPageWriter(profile.footer))
		{
			header(writer, profile, labels.reportTitle);
			writer.body(labels.customer + ": " + (customer == null ? labels.notRecorded : customer.name));
			writer.body(labels.vehicle + ": " + vehicleLine(vehicle, labels));
			writer.body(labels.dropOff + ": " + date(service.createdAt));
			writer.body(labels.complaint + ": " + orNone(service.complaint, labels.notRecorded));
			writer.rule();

			// Scan evidence: the newest before-scan, stated with its provenance.
			writer.title(labels.scannedCodes);
			com.fr3ts0n.ecu.gui.androbd.workshop.ScanSnapshotRepository scans =
					new com.fr3ts0n.ecu.gui.androbd.workshop.ScanSnapshotRepository(
							repository.database());
			com.fr3ts0n.ecu.gui.androbd.workshop.ScanSnapshot before =
					scans.latest(serviceId, com.fr3ts0n.ecu.gui.androbd.workshop.ScanPhase.BEFORE);
			if (before == null)
			{
				writer.body(labels.noCodes);
			}
			else
			{
				if (!before.real()) writer.subtitle(labels.demoData);
				writer.subtitle(date(before.createdAt) + " · " + before.source.name());
				appendCodes(writer, before.codesJson);
			}
			writer.rule();

			// Inspected parts with their photos.
			writer.title(labels.inspectedParts);
			List<InspectionItem> items = new com.fr3ts0n.ecu.gui.androbd.workshop
					.InspectionRepository(repository.database()).forService(serviceId);
			if (items.isEmpty())
			{
				writer.body(labels.noParts);
			}
			PhotoRepository photos = new PhotoRepository(repository.database());
			for (InspectionItem item : items)
			{
				writer.body("• " + item.name + " — " + stateLabel(item.state, labels));
				if (!item.note.isEmpty()) writer.subtitle("  " + item.note);
				for (Photo photo : photos.forOwner(PhotoOwner.INSPECTION, item.id))
				{
					Bitmap bitmap = decode(context, photo.relativePath);
					if (bitmap != null)
					{
						try
						{
							writer.image(bitmap, photo.caption);
						}
						finally
						{
							bitmap.recycle();
						}
					}
				}
			}
			writer.rule();

			// Estimate lines and what the customer agreed to.
			writer.title(labels.estimate);
			List<EstimateItem> lines = repository.estimateItems(serviceId);
			if (lines.isEmpty())
			{
				writer.body(labels.noItems);
			}
			NumberFormat rupiah = NumberFormat.getIntegerInstance(new Locale("in", "ID"));
			for (EstimateItem line : lines)
			{
				writer.body("• " + line.description + " — " + line.approval.name() + " — Rp "
						+ rupiah.format(line.lineTotal()));
			}
			writer.subtitle(labels.approvedTotal + ": Rp "
					+ rupiah.format(repository.approvedTotal(serviceId)));
			if (!service.notes.isEmpty())
			{
				writer.rule();
				writer.title(labels.notes);
				writer.body(service.notes);
			}
			writer.rule();
			writer.subtitle(labels.disclaimer);
			writer.writeTo(output);
		}
	}

	/** Invoice: the lines, the totals and the payment state. */
	public static void writeInvoice(Context context, WorkshopRepository repository, long serviceId,
			Labels labels, OutputStream output) throws IOException
	{
		ServiceRecord service = repository.findService(serviceId);
		if (service == null) throw new IOException("service missing");
		Vehicle vehicle = repository.findVehicle(service.vehicleId);
		Customer customer = vehicle == null ? null : repository.findCustomer(vehicle.customerId);
		WorkshopSettingsRepository settings = new WorkshopSettingsRepository(repository.database());
		WorkshopProfile profile = settings.profile();

		try (PdfPageWriter writer = new PdfPageWriter(profile.footer))
		{
			header(writer, profile, labels.invoiceTitle);
			writer.body(labels.customer + ": " + (customer == null ? labels.notRecorded : customer.name));
			writer.body(labels.vehicle + ": " + vehicleLine(vehicle, labels));
			writer.body(labels.status + ": " + service.status.name() + " · " + labels.payment + ": "
					+ service.paymentStatus.name());
			writer.rule();

			NumberFormat rupiah = NumberFormat.getIntegerInstance(new Locale("in", "ID"));
			List<EstimateItem> lines = repository.approvedItems(serviceId);
			writer.title(labels.estimate);
			if (lines.isEmpty())
			{
				writer.body(labels.noItems);
			}
			for (EstimateItem line : lines)
			{
				writer.body("• " + line.description + " — Rp " + rupiah.format(line.lineTotal()));
			}
			writer.rule();
			writer.title(labels.total + ": Rp " + rupiah.format(repository.approvedTotal(serviceId)));
			writer.writeTo(output);
		}
	}

	private static void header(PdfPageWriter writer, WorkshopProfile profile, String title)
	{
		writer.title(profile.printable() ? profile.name : "Bengkel");
		if (!profile.address.isEmpty()) writer.subtitle(profile.address);
		if (!profile.phone.isEmpty()) writer.subtitle(profile.phone);
		writer.spacer(6);
		writer.title(title);
		writer.spacer(2);
	}

	private static String vehicleLine(Vehicle vehicle, Labels labels)
	{
		if (vehicle == null) return labels.notRecorded;
		StringBuilder text = new StringBuilder(vehicle.plate);
		if (!vehicle.model.isEmpty()) text.append(" · ").append(vehicle.model);
		if (vehicle.year != null) text.append(" · ").append(vehicle.year);
		if (!vehicle.vin.isEmpty()) text.append(" · ").append(vehicle.vin);
		if (vehicle.odometerKm != null) text.append(" · ").append(vehicle.odometerKm).append(" km");
		return text.toString();
	}

	private static String stateLabel(InspectionState state, Labels labels)
	{
		switch (state)
		{
			case ATTENTION:
				return labels.attention;
			case BROKEN:
				return labels.broken;
			case GOOD:
			default:
				return labels.good;
		}
	}

	private static void appendCodes(PdfPageWriter writer, String codesJson)
	{
		try
		{
			JSONArray array = new JSONArray(codesJson);
			if (array.length() == 0)
			{
				writer.body("—");
				return;
			}
			for (int i = 0; i < array.length(); i++)
			{
				JSONObject code = array.optJSONObject(i);
				if (code == null) continue;
				String description = code.optString("description", "");
				writer.body("• " + code.optString("code", "") + " — " + description);
			}
		}
		catch (org.json.JSONException ignored)
		{
			writer.body("—");
		}
	}

	private static Bitmap decode(Context context, String relativePath)
	{
		try
		{
			File file = new com.fr3ts0n.ecu.gui.androbd.diagnosis.data.DiagnosisMediaStore(context)
					.resolve(relativePath);
			if (!file.isFile()) return null;
			BitmapFactory.Options options = new BitmapFactory.Options();
			options.inSampleSize = 2;
			return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
		}
		catch (IOException | RuntimeException ignored)
		{
			return null;
		}
	}

	private static String orNone(String text, String none)
	{
		return text == null || text.trim().isEmpty() ? none : text.trim();
	}

	private static String date(long millis)
	{
		return new SimpleDateFormat("d MMM yyyy HH:mm", Locale.getDefault()).format(new Date(millis));
	}
}
