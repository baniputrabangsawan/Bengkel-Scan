/*
 * Copyright 2026 Bengkel Scan contributors.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.fr3ts0n.ecu.gui.androbd.workshop.ui;

import android.content.Intent;
import android.database.sqlite.SQLiteException;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.MenuItem;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.fr3ts0n.ecu.gui.androbd.ModernUiUtils;
import com.fr3ts0n.ecu.gui.androbd.R;
import com.fr3ts0n.ecu.gui.androbd.SettingsActivity;
import com.fr3ts0n.ecu.gui.androbd.workshop.DiagnosticConclusion;
import com.fr3ts0n.ecu.gui.androbd.workshop.DiagnosticModule;
import com.fr3ts0n.ecu.gui.androbd.workshop.DiagnosticReadStatus;
import com.fr3ts0n.ecu.gui.androbd.workshop.DiagnosticRecord;
import com.fr3ts0n.ecu.gui.androbd.workshop.DiagnosticRepository;
import com.fr3ts0n.ecu.gui.androbd.workshop.ServiceRecord;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopDatabase;

/** Saves operator evidence, never infers a fault or commands a vehicle module. */
public class WorkshopDiagnosticEditActivity extends AppCompatActivity
{
	private static final int SCANNER = 0;
	private static final int CODES = 1;
	private static final int PROCEDURE = 4;
	private static final int RESULT = 5;
	private static final int REFERENCE = 6;
	private static final int DIAGNOSIS = 7;
	private static final int[] REQUIRED_EVIDENCE = { PROCEDURE, RESULT, REFERENCE, DIAGNOSIS };
	private final int[] fieldIds = { R.id.diagnostic_scanner, R.id.diagnostic_codes,
			R.id.diagnostic_symptoms, R.id.diagnostic_observations, R.id.diagnostic_procedure,
			R.id.diagnostic_result, R.id.diagnostic_reference, R.id.diagnostic_diagnosis,
			R.id.diagnostic_repair, R.id.diagnostic_verification };
	private final EditText[] fields = new EditText[fieldIds.length];
	private final DiagnosticReadStatus[] readStates = DiagnosticReadStatus.values();
	private final DiagnosticConclusion[] conclusions = DiagnosticConclusion.values();
	private DiagnosticRepository repository;
	private long serviceId;
	private DiagnosticModule module;
	private DiagnosticRecord original;
	private String[] originalTexts;
	private Spinner readStatus;
	private Spinner conclusion;
	private TextView validation;

	@Override
	protected void onCreate(Bundle state)
	{
		SettingsActivity.applyLocale(this);
		super.onCreate(state);
		serviceId = getIntent().getLongExtra(WorkshopUi.EXTRA_SERVICE_ID, -1);
		String moduleName = getIntent().getStringExtra(DiagnosticUi.EXTRA_MODULE);
		try
		{
			module = DiagnosticModule.valueOf(moduleName == null ? "" : moduleName);
		}
		catch (IllegalArgumentException invalid)
		{
			finish();
			return;
		}
		ServiceRecord service = WorkshopUi.repository(this).findService(serviceId);
		if (service == null)
		{
			finish();
			return;
		}
		repository = new DiagnosticRepository(WorkshopDatabase.getInstance(this));
		original = repository.find(serviceId, module);
		if (original == null)
		{
			original = new DiagnosticRecord(serviceId, module, DiagnosticReadStatus.NOT_CHECKED,
					DiagnosticConclusion.ECU_INDICATION, "", "", service.complaint, "", "", "", "", "", "", "", 0);
		}
		originalTexts = texts(original);
		setContentView(R.layout.workshop_diagnostic_edit);
		ModernUiUtils.applyScreenInsets(this);
		WorkshopUi.prepare(this, getString(DiagnosticUi.moduleLabel(module)));
		((TextView) findViewById(R.id.diagnostic_context)).setText(R.string.diagnostic_title);
		validation = findViewById(R.id.diagnostic_validation);
		readStatus = findViewById(R.id.diagnostic_read_status);
		conclusion = findViewById(R.id.diagnostic_conclusion);
		setChoices(readStatus, R.array.diagnostic_read_states);
		setChoices(conclusion, R.array.diagnostic_conclusions);
		readStatus.setSelection(original.readStatus.ordinal());
		conclusion.setSelection(original.conclusion.ordinal());
		for (int i = 0; i < fields.length; i++)
		{
			fields[i] = findViewById(fieldIds[i]);
			fields[i].setText(originalTexts[i]);
		}
		fields[REFERENCE].addTextChangedListener(new TextWatcher()
		{
			@Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
			@Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
			@Override public void afterTextChanged(Editable s) { showReferenceStatus(); }
		});
		showReferenceStatus();
		TextView safety = findViewById(R.id.diagnostic_safety);
		if (module == DiagnosticModule.SRS || module == DiagnosticModule.TRANSMISSION)
		{
			safety.setText(module == DiagnosticModule.SRS ? R.string.diagnostic_srs_warning
					: R.string.diagnostic_transmission_warning);
			safety.setVisibility(View.VISIBLE);
		}
		findViewById(R.id.diagnostic_open_guide).setOnClickListener(view ->
				startActivity(new Intent(this, WorkshopGuideActivity.class)
						.putExtra(WorkshopUi.EXTRA_SERVICE_ID, serviceId)
						.putExtra("guide.module", module.name())
						.putExtra("guide.code", value(CODES))));
		findViewById(R.id.diagnostic_save).setOnClickListener(view -> save());
		getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true)
		{
			@Override public void handleOnBackPressed() { leave(); }
		});
	}

	private void setChoices(Spinner spinner, int arrayId)
	{
		ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this, arrayId,
				android.R.layout.simple_spinner_item);
		adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		spinner.setAdapter(adapter);
	}

	private void showReferenceStatus()
	{
		validation.setText(R.string.diagnostic_no_reference);
		validation.setVisibility(value(REFERENCE).isEmpty() ? View.VISIBLE : View.GONE);
	}

	private String value(int index)
	{
		return fields[index].getText().toString().trim();
	}

	private void save()
	{
		DiagnosticReadStatus read = readStates[readStatus.getSelectedItemPosition()];
		DiagnosticConclusion level = conclusions[conclusion.getSelectedItemPosition()];
		EditText firstMissing = null;
		for (EditText field : fields) field.setError(null);
		if (read == DiagnosticReadStatus.READ && value(SCANNER).isEmpty())
		{
			fields[SCANNER].setError(getString(R.string.diagnostic_required));
			firstMissing = fields[SCANNER];
		}
		if (level == DiagnosticConclusion.CONFIRMED)
		{
			if (read != DiagnosticReadStatus.READ)
			{
				showError(R.string.diagnostic_require_read);
				return;
			}
			for (int index : REQUIRED_EVIDENCE)
			{
				if (!value(index).isEmpty()) continue;
				fields[index].setError(getString(R.string.diagnostic_required));
				if (firstMissing == null) firstMissing = fields[index];
			}
		}
		if (firstMissing != null)
		{
			firstMissing.requestFocus();
			validation.setText(R.string.diagnostic_invalid);
			validation.setVisibility(View.VISIBLE);
			return;
		}
		DiagnosticRecord record = new DiagnosticRecord(serviceId, module, read, level,
				value(0), value(1), value(2), value(3), value(4), value(5), value(6),
				value(7), value(8), value(9), System.currentTimeMillis());
		try
		{
			repository.save(record);
		}
		catch (IllegalArgumentException invalid)
		{
			showError(R.string.diagnostic_invalid);
			return;
		}
		catch (SQLiteException failure)
		{
			showError(R.string.diagnostic_save_failed);
			return;
		}
		WorkshopUi.hideKeyboard(this);
		Toast.makeText(this, R.string.diagnostic_saved, Toast.LENGTH_SHORT).show();
		finish();
	}

	private void showError(int message)
	{
		validation.setText(message);
		validation.setVisibility(View.VISIBLE);
		((ScrollView) findViewById(R.id.diagnostic_scroll)).smoothScrollTo(0, 0);
		validation.announceForAccessibility(getString(message));
	}

	private boolean changed()
	{
		if (readStatus.getSelectedItemPosition() != original.readStatus.ordinal()
				|| conclusion.getSelectedItemPosition() != original.conclusion.ordinal()) return true;
		for (int i = 0; i < fields.length; i++)
		{
			if (!value(i).equals(originalTexts[i])) return true;
		}
		return false;
	}

	private void leave()
	{
		if (!changed())
		{
			finish();
			return;
		}
		new AlertDialog.Builder(this).setTitle(R.string.diagnostic_discard_title)
				.setMessage(R.string.diagnostic_discard_message)
				.setNegativeButton(R.string.diagnostic_keep_editing, null)
				.setPositiveButton(R.string.diagnostic_discard, (dialog, which) -> finish()).show();
	}

	private static String[] texts(DiagnosticRecord record)
	{
		return new String[]{record.scanner, record.codes, record.symptoms, record.observations,
				record.testProcedure, record.testResult, record.reference, record.diagnosis,
				record.repair, record.verification};
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item)
	{
		if (item.getItemId() == android.R.id.home)
		{
			leave();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}
}
