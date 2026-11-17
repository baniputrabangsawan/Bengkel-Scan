package com.fr3ts0n.ecu.gui.androbd;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.view.ContextThemeWrapper;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fr3ts0n.ecu.Conversion;
import com.fr3ts0n.ecu.EcuCodeItem;
import com.fr3ts0n.ecu.EcuDataPv;
import com.fr3ts0n.ecu.HashConversion;
import com.fr3ts0n.ecu.prot.obd.ObdProt;
import com.fr3ts0n.pvs.PvList;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;

import static org.junit.Assert.assertEquals;

/** Exercises recycled presentation without opening an activity or connecting to a vehicle. */
@RunWith(AndroidJUnit4.class)
public class DiagnosticPresentationInstrumentedTest
{
    private Context context()
    {
        return new ContextThemeWrapper(
                InstrumentationRegistry.getInstrumentation().getTargetContext(), R.style.AppTheme);
    }

    private EcuCodeItem code(String value, int status)
    {
        EcuCodeItem code = new EcuCodeItem(value, "Manufacturer description");
        code.put(EcuCodeItem.FID_STATUS, Integer.valueOf(status));
        return code;
    }

    @Test
    public void recycledFaultRowsDoNotKeepThePreviousStatusOrNoCodeState()
    {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = context();
            DfcItemAdapter adapter = new DfcItemAdapter(context, R.layout.obd_item, new PvList());
            adapter.add(code("P0101", ObdProt.OBD_SVC_PENDINGCODES));
            FrameLayout parent = new FrameLayout(context);
            View row = adapter.getView(0, null, parent);
            assertEquals(context.getString(R.string.result_status_pending),
                    ((TextView) row.findViewById(R.id.result_status)).getText().toString());

            adapter.clear();
            adapter.add(code("P0000", ObdProt.OBD_SVC_READ_CODES));
            row = adapter.getView(0, row, parent);
            assertEquals(View.GONE, row.findViewById(R.id.result_status).getVisibility());
            assertEquals(View.GONE, row.findViewById(R.id.obd_units).getVisibility());
            assertEquals(View.VISIBLE, row.findViewById(R.id.result_context).getVisibility());

            adapter.clear();
            adapter.add(code("P0116", ObdProt.OBD_SVC_PERMACODES));
            row = adapter.getView(0, row, parent);
            assertEquals(View.VISIBLE, row.findViewById(R.id.result_status).getVisibility());
            assertEquals(context.getString(R.string.result_status_permanent),
                    ((TextView) row.findViewById(R.id.result_status)).getText().toString());
            assertEquals(View.VISIBLE, row.findViewById(R.id.obd_units).getVisibility());
            assertEquals(View.GONE, row.findViewById(R.id.result_context).getVisibility());
        });
    }

    @Test
    public void freezeFrameDropdownRestoresDescriptionsAfterCompactSelection()
    {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = context();
            DfcItemAdapter adapter = new DfcItemAdapter(context, R.layout.obd_item, new PvList());
            adapter.add(code("P0101", ObdProt.OBD_SVC_READ_CODES));
            Spinner parent = new Spinner(context);
            View selected = adapter.getView(0, null, parent);
            assertEquals(View.GONE, selected.findViewById(R.id.obd_label).getVisibility());
            View dropdown = adapter.getDropDownView(0, selected, parent);
            assertEquals(View.VISIBLE, dropdown.findViewById(R.id.obd_label).getVisibility());
            View selectedAgain = adapter.getView(0, dropdown, parent);
            assertEquals(View.GONE, selectedAgain.findViewById(R.id.obd_label).getVisibility());
        });
    }

    @Test
    public void statusReadingsDoNotReuseNumericBarsOrHideFollowingUnits()
    {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = context();
            ObdItemAdapter adapter = new ObdItemAdapter(context, R.layout.obd_item, new PvList());
            EcuDataPv value = new EcuDataPv();
            value.put(EcuDataPv.FID_DESCRIPT, "Example pressure");
            value.put(EcuDataPv.FID_VALUE, Double.valueOf(40d));
            value.put(EcuDataPv.FID_MIN, 0d);
            value.put(EcuDataPv.FID_MAX, 100d);
            value.put(EcuDataPv.FID_UNITS, "kPa");
            adapter.add(value);
            FrameLayout parent = new FrameLayout(context);
            View row = adapter.getView(0, null, parent);
            assertEquals(View.VISIBLE, row.findViewById(R.id.bar).getVisibility());
            assertEquals(View.VISIBLE, row.findViewById(R.id.obd_units).getVisibility());

            HashMap<Long, String> states = new HashMap<>();
            states.put(1L, "On");
            HashConversion conversion = new HashConversion(states);
            value.put(EcuDataPv.FID_VALUE, Long.valueOf(1L));
            value.put(EcuDataPv.FID_CNVID, new Conversion[] {conversion, conversion});
            row = adapter.getView(0, row, parent);
            assertEquals(View.GONE, row.findViewById(R.id.bar).getVisibility());
            assertEquals(View.GONE, row.findViewById(R.id.obd_units).getVisibility());

            value.put(EcuDataPv.FID_CNVID, null);
            value.put(EcuDataPv.FID_VALUE, Double.valueOf(40d));
            row = adapter.getView(0, row, parent);
            assertEquals(View.VISIBLE, row.findViewById(R.id.bar).getVisibility());
            assertEquals(View.VISIBLE, row.findViewById(R.id.obd_units).getVisibility());
        });
    }
}
