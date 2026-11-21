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
package com.fr3ts0n.ecu.gui.androbd.workshop;

import java.io.IOException;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Plain CSV for customers, services and unpaid invoices.
 *
 * <p>Written for a spreadsheet, not for re-import: one header row per table,
 * semicolon separated so Indonesian decimals stay readable, and every field
 * quoted so a name containing a separator cannot shift columns.</p>
 */
public final class CsvExporter
{
	/** Excel in the Indonesian locale expects this separator. */
	private static final char SEPARATOR = ';';

	private CsvExporter()
	{
	}

	public static void writeCustomers(WorkshopRepository repository, Writer out) throws IOException
	{
		out.write("nama;telepon;catatan;kendaraan\n");
		for (Customer customer : repository.listCustomers())
		{
			StringBuilder vehicles = new StringBuilder();
			for (Vehicle vehicle : repository.vehiclesForCustomer(customer.id))
			{
				if (vehicles.length() > 0) vehicles.append(", ");
				vehicles.append(vehicle.plate);
			}
			out.write(quote(customer.name) + SEPARATOR + quote(customer.phone) + SEPARATOR
					+ quote(customer.notes) + SEPARATOR + quote(vehicles.toString()) + "\n");
		}
	}

	public static void writeServices(WorkshopRepository repository, Writer out) throws IOException
	{
		out.write("tanggal;pelanggan;plat;keluhan;status;dibayar;diterima;total_disetujui\n");
		SimpleDateFormat date = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
		for (ServiceRecord service : repository.allServices())
		{
			Vehicle vehicle = repository.findVehicle(service.vehicleId);
			Customer customer = vehicle == null ? null : repository.findCustomer(vehicle.customerId);
			out.write(quote(date.format(new Date(service.createdAt))) + SEPARATOR
					+ quote(customer == null ? "" : customer.name) + SEPARATOR
					+ quote(vehicle == null ? "" : vehicle.plate) + SEPARATOR
					+ quote(service.complaint) + SEPARATOR
					+ quote(service.status.name()) + SEPARATOR
					+ quote(service.paymentStatus.name()) + SEPARATOR
					+ service.paidAmount + SEPARATOR
					+ repository.approvedTotal(service.id) + "\n");
		}
	}

	public static void writeUnpaid(WorkshopRepository repository, Writer out) throws IOException
	{
		out.write("pelanggan;telepon;plat;tanggal;sisa_tagihan\n");
		SimpleDateFormat date = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
		List<ServiceRecord> unpaid = repository.unpaidServices();
		for (ServiceRecord service : unpaid)
		{
			Vehicle vehicle = repository.findVehicle(service.vehicleId);
			Customer customer = vehicle == null ? null : repository.findCustomer(vehicle.customerId);
			out.write(quote(customer == null ? "" : customer.name) + SEPARATOR
					+ quote(customer == null ? "" : customer.phone) + SEPARATOR
					+ quote(vehicle == null ? "" : vehicle.plate) + SEPARATOR
					+ quote(date.format(new Date(service.createdAt))) + SEPARATOR
					+ repository.balanceDue(service.id) + "\n");
		}
	}

	/** Always quoted, doubling embedded quotes, so any text stays one field. */
	private static String quote(String value)
	{
		String text = value == null ? "" : value;
		return '"' + text.replace("\"", "\"\"") + '"';
	}
}
