/* (C) 2026 Bengkel Scan contributors. GPL-2.0-or-later. */
package com.fr3ts0n.ecu.gui.androbd.workshop.ui;

import android.database.sqlite.SQLiteException;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.widget.TextViewCompat;

import com.fr3ts0n.ecu.gui.androbd.ModernUiUtils;
import com.fr3ts0n.ecu.gui.androbd.R;
import com.fr3ts0n.ecu.gui.androbd.SettingsActivity;
import com.fr3ts0n.ecu.gui.androbd.workshop.ServiceRecord;
import com.fr3ts0n.ecu.gui.androbd.workshop.Vehicle;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopRepository;
import com.fr3ts0n.ecu.gui.androbd.workshop.guidance.WorkshopGuideCatalog;
import com.google.android.material.button.MaterialButton;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Offline-only catalogue/detail reader. Does not diagnose, write service data or command an ECU. */
public class WorkshopGuideActivity extends AppCompatActivity
{
    public static final String EXTRA_MODULE = "guide.module";
    public static final String EXTRA_CODE = "guide.code";
    private static final String STATE_QUERY = "guide.query";
    private static final String STATE_MODULE = "guide.filter";
    private static final String STATE_SELECTED = "guide.selected";
    private static final String STATE_SCROLL = "guide.scroll";
    private static final String STATE_CATALOGUE_SCROLL = "guide.catalogue_scroll";
    private static final String[] MODULES = {"", "ENGINE", "SRS", "TRANSMISSION", "ABS", "BODY", "OTHER"};
    private static final int[] SECTION_HEADINGS = {R.string.guide_section_symptoms,
            R.string.guide_section_safety, R.string.guide_section_tools, R.string.guide_section_checks,
            R.string.guide_section_reference, R.string.guide_section_repairs, R.string.guide_section_verify};

    private WorkshopGuideCatalog catalog;
    private EditText query;
    private Spinner module;
    private ScrollView scroll;
    private LinearLayout results;
    private String selectedId;
    private int catalogueScroll;
    private ExecutorService contextReader;

    @Override protected void onCreate(Bundle savedInstanceState)
    {
        SettingsActivity.applyLocale(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.workshop_guides);
        ModernUiUtils.applyScreenInsets(this);
        WorkshopUi.prepare(this, getString(R.string.guide_title));
        catalog = new WorkshopGuideCatalog(this::getString);
        query = findViewById(R.id.guide_query);
        module = findViewById(R.id.guide_module_filter);
        scroll = findViewById(R.id.guide_scroll);
        results = findViewById(R.id.guide_results);
        ArrayAdapter<CharSequence> modules = ArrayAdapter.createFromResource(this,
                R.array.guide_modules, android.R.layout.simple_spinner_item);
        modules.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        module.setAdapter(modules);
        String initialModule = savedInstanceState == null ? getIntent().getStringExtra(EXTRA_MODULE)
                : savedInstanceState.getString(STATE_MODULE);
        module.setSelection(modulePosition(initialModule));
        String initialQuery = savedInstanceState == null ? getIntent().getStringExtra(EXTRA_CODE)
                : savedInstanceState.getString(STATE_QUERY);
        query.setText(initialQuery == null ? "" : initialQuery);
        query.addTextChangedListener(new TextWatcher()
        {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count)
            {
                renderResults();
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        module.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener()
        {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id)
            {
                renderResults();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        findViewById(R.id.guide_back).setOnClickListener(v -> showCatalogue());
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true)
        {
            @Override public void handleOnBackPressed()
            {
                if (selectedId != null) showCatalogue();
                else finish();
            }
        });
        renderResults();
        if (savedInstanceState != null)
        {
            catalogueScroll = savedInstanceState.getInt(STATE_CATALOGUE_SCROLL);
            WorkshopGuideCatalog.Guide guide = catalog.find(savedInstanceState.getString(STATE_SELECTED));
            if (guide != null) showGuide(guide, false);
            int y = savedInstanceState.getInt(STATE_SCROLL);
            scroll.post(() -> scroll.scrollTo(0, y));
        }
        loadServiceContext();
    }

    private static int modulePosition(String value)
    {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        for (int i = 0; i < MODULES.length; i++) if (MODULES[i].equals(normalized)) return i;
        return MODULES.length - 1; // Unknown supplied module must not broaden to all modules.
    }

    private void renderResults()
    {
        WorkshopGuideCatalog.Result found = catalog.search(MODULES[module.getSelectedItemPosition()],
                query.getText().toString());
        TextView status = findViewById(R.id.guide_result_status);
        if (!found.unmatchedCodes.isEmpty())
            status.setText(getString(R.string.guide_unmatched, TextUtils.join(", ", found.unmatchedCodes)));
        else if (found.guides.isEmpty()) status.setText(R.string.guide_no_specific);
        else status.setText(getString(R.string.guide_matches, found.guides.size()));
        results.removeAllViews();
        for (WorkshopGuideCatalog.Guide guide : found.guides) addGuideButton(guide);
        if (found.fallback != null) addGuideButton(found.fallback);
    }

    private void addGuideButton(WorkshopGuideCatalog.Guide guide)
    {
        MaterialButton button = new MaterialButton(this);
        button.setText(getString(guide.title) + "\n" + getString(R.string.guide_general_label));
        button.setAllCaps(false);
        button.setMinHeight(dp(48));
        button.setMinimumHeight(dp(48));
        button.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        button.setOnClickListener(v -> showGuide(guide, true));
        results.addView(button);
    }

    private void showGuide(WorkshopGuideCatalog.Guide guide, boolean fromCatalogue)
    {
        if (fromCatalogue) catalogueScroll = scroll.getScrollY();
        selectedId = guide.id;
        WorkshopUi.hideKeyboard(this);
        findViewById(R.id.guide_catalogue).setVisibility(View.GONE);
        findViewById(R.id.guide_detail).setVisibility(View.VISIBLE);
        LinearLayout content = findViewById(R.id.guide_detail_content);
        content.removeAllViews();
        addText(content, getString(guide.title), true);
        addText(content, getString(R.string.guide_general_label), false);
        addText(content, getString(R.string.guide_manual_missing), true);
        for (int i = 0; i < guide.sections.size(); i++)
        {
            addText(content, getString(SECTION_HEADINGS[i]), true);
            addText(content, getString(guide.sections.get(i)), false);
        }
        addText(content, getString(R.string.guide_section_sources), true);
        addText(content, getString(R.string.guide_sources_note), false);
        for (int source : guide.sources) addText(content, getString(source), false);
        WorkshopUi.prepare(this, getString(guide.title));
        if (fromCatalogue) scroll.post(() -> scroll.scrollTo(0, 0));
    }

    private void addText(LinearLayout parent, String text, boolean heading)
    {
        TextView view = new TextView(this);
        view.setText(text);
        TextViewCompat.setTextAppearance(view, heading ? R.style.WorkshopHomeSectionHeading
                : R.style.WorkshopHomeHelper);
        view.setTextIsSelectable(true);
        ViewCompat.setAccessibilityHeading(view, heading);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        layout.topMargin = dp(heading ? 20 : 8);
        view.setLayoutParams(layout);
        parent.addView(view);
    }

    private void showCatalogue()
    {
        selectedId = null;
        findViewById(R.id.guide_detail).setVisibility(View.GONE);
        findViewById(R.id.guide_catalogue).setVisibility(View.VISIBLE);
        WorkshopUi.prepare(this, getString(R.string.guide_title));
        scroll.post(() -> scroll.scrollTo(0, catalogueScroll));
    }

    private void loadServiceContext()
    {
        long serviceId = getIntent().getLongExtra(WorkshopUi.EXTRA_SERVICE_ID, -1);
        if (serviceId <= 0) return;
        TextView context = findViewById(R.id.guide_vehicle_context);
        context.setVisibility(View.VISIBLE);
        context.setText(R.string.guide_context_loading);
        contextReader = Executors.newSingleThreadExecutor();
        contextReader.execute(() ->
        {
            String summary = getString(R.string.guide_context_missing);
            try
            {
                WorkshopRepository repository = WorkshopUi.repository(getApplicationContext());
                ServiceRecord service = repository.findService(serviceId);
                if (service != null)
                {
                    Vehicle vehicle = repository.findVehicle(service.vehicleId);
                    if (vehicle != null)
                    {
                        String identity = vehicle.plate + " · " + vehicle.model
                                + (vehicle.year == null ? "" : " · " + vehicle.year);
                        summary = getString(R.string.guide_context, identity, service.complaint);
                    }
                }
            }
            catch (SQLiteException ex)
            {
                Log.w("WorkshopGuide", "Cannot read optional service context", ex);
            }
            String loaded = summary;
            runOnUiThread(() ->
            {
                if (!isFinishing() && !isDestroyed()) context.setText(loaded);
            });
        });
    }

    @Override protected void onSaveInstanceState(Bundle state)
    {
        state.putString(STATE_QUERY, query.getText().toString());
        state.putString(STATE_MODULE, MODULES[module.getSelectedItemPosition()]);
        state.putString(STATE_SELECTED, selectedId);
        state.putInt(STATE_SCROLL, scroll.getScrollY());
        state.putInt(STATE_CATALOGUE_SCROLL, catalogueScroll);
        super.onSaveInstanceState(state);
    }

    @Override public boolean onOptionsItemSelected(MenuItem item)
    {
        if (item.getItemId() == android.R.id.home)
        {
            if (selectedId != null) showCatalogue();
            else finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override protected void onDestroy()
    {
        if (contextReader != null) contextReader.shutdownNow();
        super.onDestroy();
    }

    private int dp(int value)
    {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
