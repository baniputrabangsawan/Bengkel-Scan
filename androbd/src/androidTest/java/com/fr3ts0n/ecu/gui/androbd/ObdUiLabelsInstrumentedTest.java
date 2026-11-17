package com.fr3ts0n.ecu.gui.androbd;

import android.content.Context;
import android.content.res.Configuration;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Locale;

import static org.junit.Assert.assertEquals;

/** Ensures language presentation preserves custom PIDs and the English selection. */
@RunWith(AndroidJUnit4.class)
public class ObdUiLabelsInstrumentedTest
{
    private Context localized(String language)
    {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.setLocale(new Locale(language));
        return context.createConfigurationContext(configuration);
    }

    @Test
    public void customAndUnknownLabelsSurviveTranslation()
    {
        Context context = localized("id");
        assertEquals("Workshop sensor", ObdUiLabels.label(context,
                "engine_speed", "Workshop sensor"));
        assertEquals("Auxiliary sensor", ObdUiLabels.label(context,
                "workshop_custom_sensor", "Auxiliary sensor"));
    }

    @Test
    public void englishSelectionRestoresStandardLabels()
    {
        Context context = localized("en");
        assertEquals("Coolant Temperature", ObdUiLabels.label(context,
                "engine_coolant_temperature", "Coolant Temperature"));
    }

    @Test
    public void localizedReadinessPreservesBitMarkersAndUnknownValues()
    {
        ObdUiValues values = new ObdUiValues(localized("id"));
        String translated = values.status("(*)  Available\n(  )  Incomplete");
        assertEquals("(*)", translated.substring(0, 3));
        assertEquals("(  )", translated.substring(translated.indexOf('\n') + 1,
                translated.indexOf('\n') + 5));
        assertEquals("12.5", values.status("12.5"));
        assertEquals("Manufacturer-specific status", values.status("Manufacturer-specific status"));
        assertEquals("(*)  Available", new ObdUiValues(localized("en")).status("(*)  Available"));
    }
}
