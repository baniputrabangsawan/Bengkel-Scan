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
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.fr3ts0n.ecu.gui.androbd.ModernUiUtils;
import com.fr3ts0n.ecu.gui.androbd.R;
import com.fr3ts0n.ecu.gui.androbd.SettingsActivity;
import com.fr3ts0n.ecu.gui.androbd.workshop.ApprovalState;
import com.fr3ts0n.ecu.gui.androbd.workshop.EstimateItem;
import com.fr3ts0n.ecu.gui.androbd.workshop.InspectionItem;
import com.fr3ts0n.ecu.gui.androbd.workshop.InspectionRepository;
import com.fr3ts0n.ecu.gui.androbd.workshop.ItemKind;
import com.fr3ts0n.ecu.gui.androbd.workshop.PaymentStatus;
import com.fr3ts0n.ecu.gui.androbd.workshop.ServiceRecord;
import com.fr3ts0n.ecu.gui.androbd.workshop.ServiceStatus;
import com.fr3ts0n.ecu.gui.androbd.workshop.Vehicle;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopRepository;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

/** One service: status, payment, notes and the cost estimate. */
public class WorkshopServiceActivity extends AppCompatActivity
{
	private WorkshopRepository repository;
	private WorkshopRowAdapter adapter;
	private ServiceRecord service;
	private Spinner status;
	private MaterialSwitch paid;
	private EditText notes;
	private TextView total;
	private TextView empty;
	private boolean binding;

	@Override
	protected void onCreate(Bundle savedInstanceState)
	{
		SettingsActivity.applyLocale(this);
		super.onCreate(savedInstanceState);
		long serviceId = getIntent().getLongExtra(WorkshopUi.EXTRA_SERVICE_ID, -1);
		repository = WorkshopUi.repository(this);
		service = repository.findService(serviceId);
		if (service == null)
		{
			finish();
			return;
		}
		setContentView(R.layout.workshop_service);
		ModernUiUtils.applyScreenInsets(this);

		Vehicle vehicle = repository.findVehicle(service.vehicleId);
		WorkshopUi.prepare(this, vehicle == null ? getString(R.string.workshop_services_title)
				: vehicle.plate);
		((TextView) findViewById(R.id.workshop_summary)).setText(
				service.complaint + "\n" + WorkshopUi.dateTime(service.createdAt));
		findViewById(R.id.workshop_canned).setOnClickListener(view ->
				startActivity(new Intent(this, WorkshopCannedJobsActivity.class)
						.putExtra(WorkshopUi.EXTRA_SERVICE_ID, service.id)));
		findViewById(R.id.workshop_inspection).setOnClickListener(view ->
				startActivity(new Intent(this, WorkshopInspectionActivity.class)
						.putExtra(WorkshopUi.EXTRA_SERVICE_ID, service.id)));
		findViewById(R.id.workshop_scan).setOnClickListener(view ->
				startActivity(new Intent(this, WorkshopScanActivity.class)
						.putExtra(WorkshopUi.EXTRA_SERVICE_ID, service.id)
						.putExtra(WorkshopScanActivity.EXTRA_MODE, modeName())));
		findViewById(R.id.workshop_scan_compare).setOnClickListener(view ->
				startActivity(new Intent(this, WorkshopScanCompareActivity.class)
						.putExtra(WorkshopUi.EXTRA_SERVICE_ID, service.id)));
		findViewById(R.id.workshop_diagnostics).setOnClickListener(view ->
				startActivity(new Intent(this, WorkshopDiagnosticsActivity.class)
						.putExtra(WorkshopUi.EXTRA_SERVICE_ID, service.id)));
		findViewById(R.id.workshop_guided_diagnosis).setOnClickListener(view ->
				startActivity(new Intent(this,
						com.fr3ts0n.ecu.gui.androbd.diagnosis.ui.DiagnosisStartActivity.class)
						.putExtra(WorkshopUi.EXTRA_SERVICE_ID, service.id)));

		status = findViewById(R.id.workshop_status);
		ArrayAdapter<String> statuses = new ArrayAdapter<>(this,
				android.R.layout.simple_spinner_item,
				new String[]{getString(R.string.workshop_status_waiting),
						getString(R.string.workshop_status_in_progress),
						getString(R.string.workshop_status_done),
						getString(R.string.workshop_status_paid)});
		statuses.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		status.setAdapter(statuses);
		status.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener()
		{
			@Override
			public void onItemSelected(AdapterView<?> parent, View view, int position, long id)
			{
				if (binding || service == null) return;
				// The fourth step "Lunas" finishes the job and records payment in one tap.
				if (position == 3)
				{
					if (service.status != ServiceStatus.DONE) updateStatus(ServiceStatus.DONE);
					if (service.paymentStatus != PaymentStatus.PAID)
					{
						setPaid(true);
					}
					bind();
					return;
				}
				ServiceStatus next = position == 1 ? ServiceStatus.IN_PROGRESS
						: position == 2 ? ServiceStatus.DONE : ServiceStatus.WAITING;
				if (next != service.status) updateStatus(next);
				// Stepping back to an unfinished state also clears an earlier payment.
				if (service.paymentStatus == PaymentStatus.PAID && next != ServiceStatus.DONE)
				{
					setPaid(false);
				}
			}

			@Override
			public void onNothingSelected(AdapterView<?> parent)
			{
			}
		});

		paid = findViewById(R.id.workshop_paid);
		notes = findViewById(R.id.workshop_notes);
		total = findViewById(R.id.workshop_total);

		ListView list = findViewById(R.id.workshop_list);
		empty = findViewById(R.id.workshop_empty);
		adapter = new WorkshopRowAdapter(this);
		list.setAdapter(adapter);
		list.setOnItemLongClickListener((parent, view, position, id) ->
		{
			itemActions(adapter.getItem(position));
			return true;
		});
		list.setOnItemClickListener((parent, view, position, id) ->
		{
			long itemId = adapter.getItem(position).id;
			for (EstimateItem item : repository.estimateItems(service.id))
			{
				if (item.id == itemId)
				{
					showItemDialog(item);
					return;
				}
			}
		});

		findViewById(R.id.workshop_add).setOnClickListener(view -> addEstimateItem());
		findViewById(R.id.workshop_save_notes).setOnClickListener(view ->
		{
			repository.updateServiceNotes(service.id, notes.getText().toString().trim());
			WorkshopUi.hideKeyboard(this);
			Toast.makeText(this, R.string.workshop_service_notes_saved, Toast.LENGTH_SHORT).show();
		});
	}

	@Override
	protected void onResume()
	{
		super.onResume();
		if (service == null) return;
		service = repository.findService(service.id);
		if (service == null)
		{
			finish();
			return;
		}
		bind();
	}

	/** Record payment, keep the service screen's copy current, and log the change. */
	private void setPaid(boolean nowPaid)
	{
		repository.setPayment(service.id, nowPaid ? PaymentStatus.PAID : PaymentStatus.UNPAID,
				null, null);
		new com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopSettingsRepository(repository.database())
				.record("service.payment", "service", service.id, nowPaid ? "PAID" : "UNPAID");
		service = repository.findService(service.id);
	}

	/** Change the workflow state and keep one audit line per change. */
	private void updateStatus(ServiceStatus next)
	{
		repository.updateServiceStatus(service.id, next);
		new com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopSettingsRepository(repository.database())
				.record("service.status", "service", service.id, next.name());
		service = repository.findService(service.id);
	}

	private void bind()
	{
		binding = true;
		status.setSelection(service.paymentStatus == PaymentStatus.PAID ? 3
				: service.status == ServiceStatus.IN_PROGRESS ? 1
				: service.status == ServiceStatus.DONE ? 2 : 0);
		binding = false;

		paid.setOnCheckedChangeListener(null);
		paid.setChecked(service.paymentStatus == PaymentStatus.PAID);
		paid.setOnCheckedChangeListener((button, checked) ->
		{
			if (binding) return;
			setPaid(checked);
			bind();
		});

		if (!notes.hasFocus())
		{
			notes.setText(service.notes);
		}
		loadItems();
		loadScans();
	}

	/** Mode reported by the main screen; the snapshot never guesses its own provenance. */
	private String modeName()
	{
		return com.fr3ts0n.ecu.gui.androbd.MainActivity.modeName();
	}

	/**
	 * Show what has already been frozen for this service, with demo data
	 * clearly labelled. Read-only: nothing here can change the protocol lists.
	 */
	private void loadScans()
	{
		TextView view = findViewById(R.id.workshop_scan_list);
		if (view == null) return;
		java.util.List<com.fr3ts0n.ecu.gui.androbd.workshop.ScanSnapshot> snapshots =
				new com.fr3ts0n.ecu.gui.androbd.workshop.ScanSnapshotRepository(repository.database())
						.forService(service.id);
		if (snapshots.isEmpty())
		{
			view.setVisibility(android.view.View.GONE);
			return;
		}
		StringBuilder text = new StringBuilder();
		for (com.fr3ts0n.ecu.gui.androbd.workshop.ScanSnapshot snapshot : snapshots)
		{
			if (text.length() > 0) text.append('\n');
			text.append(getString(snapshot.phase == com.fr3ts0n.ecu.gui.androbd.workshop.ScanPhase.AFTER
							? R.string.workshop_scan_phase_after : R.string.workshop_scan_phase_before))
					.append(" · ").append(WorkshopUi.dateTime(snapshot.createdAt))
					.append(" · ").append(getString(sourceLabelOf(snapshot.source)))
					.append(" · ")
					.append(getString(R.string.workshop_scan_codes,
							com.fr3ts0n.ecu.gui.androbd.workshop.ScanSnapshotWriter
									.size(snapshot.codesJson)));
		}
		view.setText(text.toString());
		view.setVisibility(android.view.View.VISIBLE);
	}

	private static int sourceLabelOf(com.fr3ts0n.ecu.gui.androbd.workshop.ScanSource source)
	{
		switch (source)
		{
			case REAL:
				return R.string.workshop_scan_source_real;
			case FILE:
				return R.string.workshop_scan_source_file;
			case DEMO:
			default:
				return R.string.workshop_scan_source_demo;
		}
	}

	private void loadItems()
	{
		List<EstimateItem> items = repository.estimateItems(service.id);
		DecimalFormat quantity = new DecimalFormat("0.##");
		List<WorkshopRow> rows = new ArrayList<>(items.size());
		for (EstimateItem item : items)
		{
			StringBuilder subtitle = new StringBuilder(getString(R.string.workshop_item_line,
					quantity.format(item.quantity), WorkshopUi.money(item.unitPrice)));
			if (item.discount > 0)
			{
				subtitle.append(" · ").append(getString(R.string.workshop_item_discount_short,
						WorkshopUi.money(item.discount)));
			}
			if (item.inspectionItemId != null)
			{
				subtitle.append(" · ").append(getString(R.string.workshop_estimate_linked));
			}
			rows.add(new WorkshopRow(item.id, item.description, subtitle.toString(),
					getString(approvalLabel(item.approval)) + " · "
							+ WorkshopUi.money(item.lineTotal())));
		}
		adapter.submit(rows);
		empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
		com.fr3ts0n.ecu.gui.androbd.workshop.EstimateApprovalSummary approval =
				com.fr3ts0n.ecu.gui.androbd.workshop.EstimateApprovalSummary.of(items);
		total.setText(WorkshopUi.money(repository.estimateTotal(service.id)) + " · "
				+ getString(R.string.workshop_approval_approved_total) + " "
				+ WorkshopUi.money(approval.approvedTotal));
	}

	private boolean confirmDeleteItem(AdapterView<?> parent, View view, int position, long id)
	{
		WorkshopRow row = adapter.getItem(position);
		new AlertDialog.Builder(this)
				.setTitle(R.string.workshop_delete_item_title)
				.setMessage(getString(R.string.workshop_delete_item_message, row.title))
				.setNegativeButton(R.string.workshop_action_cancel, null)
				.setPositiveButton(R.string.workshop_action_delete, (dialog, which) ->
				{
					repository.deleteEstimateItem(row.id);
					loadItems();
				})
				.show();
		return true;
	}

	/** Long press: answer the line, read its history, or remove it. */
	private void itemActions(WorkshopRow row)
	{
		new AlertDialog.Builder(this)
				.setTitle(row.title)
				.setItems(new String[]{getString(R.string.workshop_approval_approve),
						getString(R.string.workshop_approval_decline),
						getString(R.string.workshop_approval_history),
						getString(R.string.workshop_action_delete)}, (dialog, which) ->
				{
					if (which == 0) answerLine(row.id, ApprovalState.APPROVED);
					else if (which == 1) answerLine(row.id, ApprovalState.DECLINED);
					else if (which == 2) showHistory(row.id);
					else confirmDeleteItem(row);
				})
				.setNegativeButton(R.string.workshop_action_cancel, null)
				.show();
	}

	/** Record the customer's answer with the note the mechanic typed. */
	private void answerLine(long itemId, ApprovalState state)
	{
		View form = getLayoutInflater().inflate(R.layout.dialog_text, null);
		TextInputEditText note = form.findViewById(R.id.workshop_text);
		AlertDialog dialog = new AlertDialog.Builder(this)
				.setTitle(state == ApprovalState.APPROVED
						? R.string.workshop_approval_approve : R.string.workshop_approval_decline)
				.setView(form)
				.setNegativeButton(R.string.workshop_action_cancel, null)
				.setPositiveButton(R.string.workshop_action_save, null)
				.create();
		dialog.setOnShowListener(ignored ->
				dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view ->
				{
					repository.setApproval(itemId, state, textOf(note));
					new com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopSettingsRepository(
							repository.database())
							.record("estimate.approval", "estimate_item", itemId, state.name());
					dialog.dismiss();
					loadItems();
					Toast.makeText(this, R.string.workshop_approval_saved, Toast.LENGTH_SHORT).show();
				}));
		dialog.show();
	}

	private void showHistory(long itemId)
	{
		List<com.fr3ts0n.ecu.gui.androbd.workshop.EstimateApproval> history =
				repository.approvals(itemId);
		StringBuilder text = new StringBuilder();
		for (com.fr3ts0n.ecu.gui.androbd.workshop.EstimateApproval entry : history)
		{
			if (text.length() > 0) text.append('\n');
			text.append(WorkshopUi.dateTime(entry.changedAt)).append(" · ")
					.append(getString(approvalLabel(entry.state)));
			if (!entry.note.isEmpty()) text.append(" · ").append(entry.note);
		}
		new AlertDialog.Builder(this)
				.setTitle(R.string.workshop_approval_history)
				.setMessage(history.isEmpty() ? getString(R.string.workshop_approval_history_empty)
						: text.toString())
				.setPositiveButton(android.R.string.ok, null)
				.show();
	}

	private static int approvalLabel(ApprovalState state)
	{
		switch (state)
		{
			case APPROVED:
				return R.string.workshop_approval_approved;
			case DECLINED:
				return R.string.workshop_approval_declined;
			case PENDING:
			default:
				return R.string.workshop_approval_pending;
		}
	}

	private boolean confirmDeleteItem(WorkshopRow row)
	{
		new AlertDialog.Builder(this)
				.setTitle(R.string.workshop_delete_item_title)
				.setMessage(getString(R.string.workshop_delete_item_message, row.title))
				.setNegativeButton(R.string.workshop_action_cancel, null)
				.setPositiveButton(R.string.workshop_action_delete, (dialog, which) ->
				{
					repository.deleteEstimateItem(row.id);
					loadItems();
				})
				.show();
		return true;
	}

	private void addEstimateItem()
	{
		showItemDialog(null);
	}

	/**
	 * Add or edit one estimate line.
	 *
	 * @param editing line to change, or {@code null} to add
	 */
	private void showItemDialog(final EstimateItem editing)
	{
		View form = getLayoutInflater().inflate(R.layout.dialog_estimate_item, null);
		Spinner kind = form.findViewById(R.id.workshop_kind);
		TextInputEditText description = form.findViewById(R.id.workshop_description);
		TextInputEditText quantity = form.findViewById(R.id.workshop_quantity);
		TextInputEditText price = form.findViewById(R.id.workshop_price);
		TextInputEditText discount = form.findViewById(R.id.workshop_discount);
		Spinner inspectionLink = form.findViewById(R.id.workshop_inspection_link);

		ArrayAdapter<String> kinds = new ArrayAdapter<>(this,
				android.R.layout.simple_spinner_item,
				new String[]{getString(R.string.workshop_item_labour),
						getString(R.string.workshop_item_part)});
		kinds.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		kind.setAdapter(kinds);

		// Optional link to a part inspected during this service.
		final List<InspectionItem> inspected =
				new InspectionRepository(repository.database()).forService(service.id);
		ArrayAdapter<String> links = new ArrayAdapter<>(this,
				android.R.layout.simple_spinner_item, linkLabels(inspected));
		links.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		inspectionLink.setAdapter(links);

		if (editing != null)
		{
			kind.setSelection(editing.kind == ItemKind.LABOUR ? 0 : 1);
			description.setText(editing.description);
			quantity.setText(new DecimalFormat("0.##").format(editing.quantity));
			price.setText(String.valueOf(editing.unitPrice));
			discount.setText(editing.discount == 0 ? "" : String.valueOf(editing.discount));
			for (int i = 0; i < inspected.size(); i++)
			{
				if (Long.valueOf(inspected.get(i).id).equals(editing.inspectionItemId))
				{
					inspectionLink.setSelection(i + 1);
					break;
				}
			}
		}

		AlertDialog dialog = new AlertDialog.Builder(this)
				.setTitle(editing == null ? R.string.workshop_estimate_add
						: R.string.workshop_estimate_edit)
				.setView(form)
				.setNegativeButton(R.string.workshop_action_cancel, null)
				.setPositiveButton(R.string.workshop_action_save, null)
				.create();
		dialog.setOnShowListener(ignored ->
				dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view ->
				{
					String text = textOf(description);
					if (text.isEmpty())
					{
						description.setError(getString(R.string.workshop_item_description));
						description.requestFocus();
						return;
					}
					double amount = parseQuantity(textOf(quantity));
					if (amount <= 0)
					{
						quantity.setError(getString(R.string.workshop_error_quantity));
						quantity.requestFocus();
						return;
					}
					long unitPrice = parseRupiah(textOf(price), -1);
					if (unitPrice < 0)
					{
						price.setError(getString(R.string.workshop_error_price));
						price.requestFocus();
						return;
					}
					long taken = parseRupiah(textOf(discount), 0);
					if (taken < 0 || taken > Math.round(amount * unitPrice))
					{
						discount.setError(getString(R.string.workshop_error_discount));
						discount.requestFocus();
						return;
					}
					ItemKind itemKind = kind.getSelectedItemPosition() == 0
							? ItemKind.LABOUR : ItemKind.PART;
					if (editing == null)
					{
						int link = inspectionLink.getSelectedItemPosition();
						repository.addEstimateItem(service.id, itemKind, text, amount, unitPrice,
								taken, link <= 0 ? null
										: Long.valueOf(inspected.get(link - 1).id));
					}
					else
					{
						repository.updateEstimateItem(editing.id, itemKind, text, amount,
								unitPrice, taken);
					}
					WorkshopUi.hideKeyboard(this);
					dialog.dismiss();
					loadItems();
					Toast.makeText(this, R.string.workshop_item_saved, Toast.LENGTH_SHORT).show();
				}));
		dialog.show();
	}

	private String[] linkLabels(List<InspectionItem> inspected)
	{
		String[] labels = new String[inspected.size() + 1];
		labels[0] = getString(R.string.workshop_item_inspection_none);
		for (int i = 0; i < inspected.size(); i++)
		{
			labels[i + 1] = inspected.get(i).name;
		}
		return labels;
	}

	/** "1,5" or "1.5" hours, as the mechanic types it. */
	static double parseQuantity(String value)
	{
		try
		{
			return Double.parseDouble(value.trim().replace(',', '.'));
		}
		catch (NumberFormatException invalid)
		{
			return 0;
		}
	}

	/** Whole Rupiah; separators are ignored, and a blank field keeps {@code fallback}. */
	static long parseRupiah(String value, long fallback)
	{
		String digits = value == null ? "" : value.replaceAll("[^0-9]", "");
		if (digits.isEmpty()) return fallback;
		try
		{
			return Long.parseLong(digits);
		}
		catch (NumberFormatException invalid)
		{
			return -1;
		}
	}

	private static String textOf(TextInputEditText field)
	{
		return field.getText() == null ? "" : field.getText().toString().trim();
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