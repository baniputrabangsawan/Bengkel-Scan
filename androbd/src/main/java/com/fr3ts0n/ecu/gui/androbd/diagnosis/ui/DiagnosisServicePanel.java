package com.fr3ts0n.ecu.gui.androbd.diagnosis.ui;

import android.database.sqlite.SQLiteDatabase;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.fr3ts0n.ecu.gui.androbd.R;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledge;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.DiagnosisRepository;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.DiagnosisServiceLink;
import com.fr3ts0n.ecu.gui.androbd.workshop.Customer;
import com.fr3ts0n.ecu.gui.androbd.workshop.EstimateItem;
import com.fr3ts0n.ecu.gui.androbd.workshop.ItemKind;
import com.fr3ts0n.ecu.gui.androbd.workshop.ServiceRecord;
import com.fr3ts0n.ecu.gui.androbd.workshop.Vehicle;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopDatabase;
import com.fr3ts0n.ecu.gui.androbd.workshop.WorkshopRepository;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Embedded explicit link/proposal flow. Existing workshop approval state is not changed. */
public final class DiagnosisServicePanel
{
    private static final String TAG = "DiagnosisServicePanel";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private final AppCompatActivity activity;
    private final LinearLayout panel;
    private final long caseId;
    private final WorkshopRepository workshop;
    private final DiagnosisRepository cases;
    private final DiagnosisServiceLink link;

    private DiagnosisServicePanel(AppCompatActivity activity, LinearLayout panel, long caseId)
    {
        this.activity = activity;
        this.panel = panel;
        this.caseId = caseId;
        workshop = new WorkshopRepository(WorkshopDatabase.getInstance(activity));
        cases = new DiagnosisRepository(activity);
        link = new DiagnosisServiceLink(activity);
    }

    /** The parent owns the surrounding screen; refreshes replace only this panel. */
    public static void render(AppCompatActivity activity, LinearLayout root, DiagnosisRepository.CaseRecord record)
    {
        View previous = root.findViewWithTag(TAG);
        if (previous != null && previous.getParent() == root) root.removeView(previous);
        LinearLayout panel = new LinearLayout(activity);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setTag(TAG);
        root.addView(panel, new LinearLayout.LayoutParams(-1, -2));
        new DiagnosisServicePanel(activity, panel, record.id).refresh();
    }

    private void refresh()
    {
        panel.removeAllViews();
        text(panel, R.string.diagnosis_link_title, true);
        text(panel, R.string.diagnosis_link_notice, false);
        TextView status = text(panel, R.string.diagnosis_link_loading, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        load(() -> {
            DiagnosisRepository.CaseRecord record = cases.get(caseId);
            if (record == null) throw new IllegalArgumentException("unknown_case");
            ServiceRecord service = record.serviceId == null ? null : workshop.findService(record.serviceId);
            String preview = service == null ? null : preview(service);
            EstimateItem linkedItem = null;
            if (record.estimateItemId != null && service != null)
                for (EstimateItem item : workshop.estimateItems(service.id))
                    if (item.id == record.estimateItemId) { linkedItem = item; break; }
            return new PanelData(record, preview, linkedItem);
        }, data -> {
            status.setVisibility(View.GONE);
            show(data);
        }, () -> status.setText(R.string.diagnosis_link_failed));
    }

    private void show(PanelData data)
    {
        DiagnosisRepository.CaseRecord record = data.record;
        if (data.preview != null) DiagnosisUi.text(activity, panel, data.preview, false);
        else text(panel, R.string.diagnosis_link_none, false);

        if (record.estimateItemId != null)
        {
            DiagnosisUi.text(activity, panel, activity.getString(R.string.diagnosis_link_existing, record.estimateItemId), false);
            if (data.item == null) text(panel, R.string.diagnosis_link_invalid_existing, false);
            else
            {
                NumberFormat numbers = NumberFormat.getNumberInstance(new Locale("id", "ID"));
                EstimateItem item = data.item;
                DiagnosisUi.text(activity, panel, activity.getString(R.string.diagnosis_link_existing_item,
                        item.description, activity.getString(item.kind == ItemKind.LABOUR
                                ? R.string.diagnosis_link_kind_labour : R.string.diagnosis_link_kind_part),
                        numbers.format(item.quantity), numbers.format(item.unitPrice), numbers.format(item.lineTotal())), false);
            }
            return;
        }
        DiagnosisUi.button(activity, panel, activity.getString(record.serviceId == null
                ? R.string.diagnosis_link_choose : R.string.diagnosis_link_change), view -> chooseCustomer());
        if (!record.isFinal) text(panel, R.string.diagnosis_link_final_required, false);
        else if (record.serviceId != null && data.preview != null)
            DiagnosisUi.button(activity, panel, activity.getString(R.string.diagnosis_link_estimate_action),
                    view -> estimateForm(data));
    }

    private void chooseCustomer()
    {
        load(workshop::listCustomers, customers -> {
            if (customers.isEmpty()) { message(R.string.diagnosis_link_no_customers); return; }
            String[] labels = new String[customers.size()];
            for (int i = 0; i < labels.length; i++)
            {
                Customer customer = customers.get(i);
                labels[i] = activity.getString(R.string.diagnosis_link_customer_row,
                        present(customer.name), present(customer.phone), customer.id);
            }
            new AlertDialog.Builder(activity).setTitle(R.string.diagnosis_link_customer)
                    .setItems(labels, (dialog, which) -> chooseVehicle(customers.get(which)))
                    .setNegativeButton(R.string.diagnosis_link_cancel, null).show();
        }, this::failed);
    }

    private void chooseVehicle(Customer customer)
    {
        load(() -> workshop.vehiclesForCustomer(customer.id), vehicles -> {
            if (vehicles.isEmpty()) { message(R.string.diagnosis_link_no_vehicles); return; }
            String[] labels = new String[vehicles.size()];
            for (int i = 0; i < labels.length; i++)
            {
                Vehicle vehicle = vehicles.get(i);
                labels[i] = activity.getString(R.string.diagnosis_link_vehicle_row,
                        present(vehicle.plate), present(vehicle.model), vehicle.id);
            }
            new AlertDialog.Builder(activity).setTitle(R.string.diagnosis_link_vehicle)
                    .setItems(labels, (dialog, which) -> chooseService(vehicles.get(which)))
                    .setNegativeButton(R.string.diagnosis_link_cancel, null).show();
        }, this::failed);
    }

    private void chooseService(Vehicle vehicle)
    {
        load(() -> workshop.servicesForVehicle(vehicle.id), services -> {
            if (services.isEmpty()) { message(R.string.diagnosis_link_no_services); return; }
            String[] labels = new String[services.size()];
            for (int i = 0; i < labels.length; i++)
            {
                ServiceRecord service = services.get(i);
                labels[i] = activity.getString(R.string.diagnosis_link_service_row,
                        service.id, date(service.createdAt), present(service.complaint));
            }
            new AlertDialog.Builder(activity).setTitle(R.string.diagnosis_link_service)
                    .setItems(labels, (dialog, which) -> confirmLink(services.get(which)))
                    .setNegativeButton(R.string.diagnosis_link_cancel, null).show();
        }, this::failed);
    }

    private void confirmLink(ServiceRecord selected)
    {
        load(() -> preview(selected), preview -> {
            LinearLayout content = content();
            DiagnosisUi.text(activity, content, preview, false);
            text(content, R.string.diagnosis_link_confirm_notice, false);
            AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(R.string.diagnosis_link_confirm_title)
                    .setView(scroll(content)).setNegativeButton(R.string.diagnosis_link_cancel, null)
                    .setPositiveButton(R.string.diagnosis_link_confirm, null).create();
            dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                load(() -> {
                    SQLiteDatabase db = workshop.database().getWritableDatabase();
                    db.beginTransaction();
                    try
                    {
                        // Keep the verified destination and repository write under one lock.
                        ServiceRecord fresh = workshop.findService(selected.id);
                        if (fresh == null || !preview.equals(preview(fresh)))
                            throw new IllegalStateException("service_changed");
                        link.linkService(caseId, selected.id);
                        db.setTransactionSuccessful();
                        return true;
                    }
                    finally { db.endTransaction(); }
                }, result -> { dialog.dismiss(); refresh(); }, () -> {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                    failed();
                });
            }));
            dialog.show();
        }, this::failed);
    }

    private void estimateForm(PanelData data)
    {
        LinearLayout content = content();
        DiagnosisUi.text(activity, content, data.preview, false);
        text(content, R.string.diagnosis_link_notice, false);
        DiagnosisKnowledge.Cause cause = data.record.complaint.findCause(data.record.finalCauseId);
        text(content, cause == null ? R.string.diagnosis_link_manual : R.string.diagnosis_link_suggestion, false);
        EditText description = field(content, R.string.diagnosis_link_description,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        if (cause != null) description.setText(cause.workDescription);
        TextView kindLabel = text(content, R.string.diagnosis_link_kind, false);
        Spinner kind = new Spinner(activity);
        kind.setId(View.generateViewId());
        kindLabel.setLabelFor(kind.getId());
        kind.setMinimumHeight(DiagnosisUi.dp(activity, 56));
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_spinner_item,
                new String[]{activity.getString(R.string.diagnosis_link_kind_labour), activity.getString(R.string.diagnosis_link_kind_part)});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        kind.setAdapter(adapter);
        content.addView(kind, new LinearLayout.LayoutParams(-1, -2));
        EditText quantity = field(content, R.string.diagnosis_link_quantity,
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        EditText price = field(content, R.string.diagnosis_link_price, InputType.TYPE_CLASS_NUMBER);
        // No default quantity, default price, inferred rate, or automatic approval.
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(R.string.diagnosis_link_estimate_title)
                .setView(scroll(content)).setNegativeButton(R.string.diagnosis_link_cancel, null)
                .setPositiveButton(R.string.diagnosis_link_add, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            String work = description.getText().toString().trim();
            if (work.isEmpty()) { error(description, R.string.diagnosis_link_description_invalid); return; }
            double amount;
            long rupiah;
            try
            {
                String input = quantity.getText().toString().trim();
                if (!input.matches("[0-9]+([.,][0-9]+)?")) throw new NumberFormatException();
                amount = Double.parseDouble(input.replace(',', '.'));
                if (Double.isNaN(amount) || Double.isInfinite(amount) || amount <= 0) throw new NumberFormatException();
            }
            catch (NumberFormatException exception) { error(quantity, R.string.diagnosis_link_quantity_invalid); return; }
            try
            {
                String input = price.getText().toString().trim();
                if (!input.matches("[0-9]+")) throw new NumberFormatException();
                rupiah = Long.parseLong(input);
            }
            catch (NumberFormatException exception) { error(price, R.string.diagnosis_link_price_invalid); return; }
            ItemKind selectedKind = kind.getSelectedItemPosition() == 0 ? ItemKind.LABOUR : ItemKind.PART;
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            load(() -> {
                SQLiteDatabase db = workshop.database().getWritableDatabase();
                db.beginTransaction();
                try
                {
                    DiagnosisRepository.CaseRecord fresh = cases.get(caseId);
                    if (fresh == null || fresh.serviceId == null || !fresh.serviceId.equals(data.record.serviceId))
                        throw new IllegalStateException("service_changed");
                    ServiceRecord service = workshop.findService(fresh.serviceId);
                    if (service == null || !data.preview.equals(preview(service)))
                        throw new IllegalStateException("service_changed");
                    long itemId = link.addEstimate(caseId, selectedKind, work, amount, rupiah);
                    db.setTransactionSuccessful();
                    return itemId;
                }
                finally { db.endTransaction(); }
            }, item -> {
                dialog.dismiss();
                Toast.makeText(activity, R.string.diagnosis_link_saved, Toast.LENGTH_LONG).show();
                refresh();
            }, () -> {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                message(R.string.diagnosis_link_service_changed);
            });
        }));
        dialog.show();
    }

    private String preview(ServiceRecord service)
    {
        Vehicle vehicle = workshop.findVehicle(service.vehicleId);
        Customer customer = vehicle == null ? null : workshop.findCustomer(vehicle.customerId);
        if (customer == null) throw new IllegalArgumentException("invalid_service_link");
        return activity.getString(R.string.diagnosis_link_preview, present(customer.name), customer.id,
                present(vehicle.plate), present(vehicle.model), service.id, date(service.createdAt), present(service.complaint));
    }

    private String date(long value)
    {
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT,
                new Locale("id", "ID")).format(new Date(value));
    }

    private String present(String value)
    {
        return value == null || value.trim().isEmpty() ? activity.getString(R.string.diagnosis_link_missing_data) : value;
    }

    private LinearLayout content()
    {
        LinearLayout result = new LinearLayout(activity);
        result.setOrientation(LinearLayout.VERTICAL);
        int padding = DiagnosisUi.dp(activity, 20);
        result.setPadding(padding, 0, padding, padding);
        return result;
    }

    private ScrollView scroll(LinearLayout content)
    {
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        return scroll;
    }

    private TextView text(LinearLayout root, int resource, boolean heading)
    {
        return DiagnosisUi.text(activity, root, activity.getString(resource), heading);
    }

    private EditText field(LinearLayout root, int label, int inputType)
    {
        TextInputLayout layout = new TextInputLayout(activity);
        layout.setHint(activity.getString(label));
        TextInputEditText input = new TextInputEditText(activity);
        input.setInputType(inputType);
        input.setMinHeight(DiagnosisUi.dp(activity, 56));
        layout.addView(input, new LinearLayout.LayoutParams(-1, -2));
        root.addView(layout, new LinearLayout.LayoutParams(-1, -2));
        return input;
    }

    private void error(EditText field, int resource)
    {
        field.setError(activity.getString(resource));
        field.requestFocus();
    }

    private void failed() { message(R.string.diagnosis_link_failed); }

    private void message(int resource)
    {
        new AlertDialog.Builder(activity).setMessage(resource)
                .setPositiveButton(R.string.diagnosis_link_close, null).show();
    }

    private boolean active()
    {
        return !activity.isFinishing() && !activity.isDestroyed() && panel.getParent() != null;
    }

    private <T> void load(Callable<T> query, Loaded<T> loaded, Runnable failure)
    {
        IO.execute(() -> {
            try
            {
                T value = query.call();
                activity.runOnUiThread(() -> { if (active()) loaded.accept(value); });
            }
            catch (Exception exception)
            {
                Log.w(TAG, "Service link operation failed", exception);
                activity.runOnUiThread(() -> { if (active()) failure.run(); });
            }
        });
    }

    private interface Loaded<T> { void accept(T value); }

    private static final class PanelData
    {
        final DiagnosisRepository.CaseRecord record;
        final String preview;
        final EstimateItem item;
        PanelData(DiagnosisRepository.CaseRecord record, String preview, EstimateItem item)
        {
            this.record = record;
            this.preview = preview;
            this.item = item;
        }
    }
}
