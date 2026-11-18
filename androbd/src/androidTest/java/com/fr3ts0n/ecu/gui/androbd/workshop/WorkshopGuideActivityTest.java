package com.fr3ts0n.ecu.gui.androbd.workshop;

import android.content.Intent;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.fr3ts0n.ecu.gui.androbd.R;
import com.fr3ts0n.ecu.gui.androbd.workshop.ui.WorkshopGuideActivity;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

/** Device flow tests: catalogue selection opens content and state survives recreation. */
@RunWith(AndroidJUnit4.class)
public class WorkshopGuideActivityTest
{
    private static void assertDisplayedSection(LinearLayout detail, String expected)
    {
        for (int i = 0; i < detail.getChildCount(); i++)
        {
            View child = detail.getChildAt(i);
            if (child instanceof TextView && expected.contentEquals(((TextView) child).getText()))
                return;
        }
        fail("The selected guide must display its own checks and source, not another topic");
    }

    @Test public void selectionAndSearchSurviveRecreationAndReturnToCatalogue()
    {
        Intent intent = new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(),
                WorkshopGuideActivity.class);
        intent.putExtra(WorkshopGuideActivity.EXTRA_MODULE, "ENGINE");
        intent.putExtra(WorkshopGuideActivity.EXTRA_CODE, "p0299 underboost");
        try (ActivityScenario<WorkshopGuideActivity> scenario = ActivityScenario.launch(intent))
        {
            scenario.onActivity(activity ->
            {
                LinearLayout results = activity.findViewById(R.id.guide_results);
                assertEquals(1, results.getChildCount());
                results.getChildAt(0).performClick();
                assertEquals(View.VISIBLE, activity.findViewById(R.id.guide_detail).getVisibility());
                LinearLayout detail = activity.findViewById(R.id.guide_detail_content);
                assertDisplayedSection(detail, activity.getString(R.string.guide_boost_checks));
                assertDisplayedSection(detail, activity.getString(R.string.guide_source_garrett));
            });
            scenario.recreate();
            scenario.onActivity(activity ->
            {
                assertEquals(View.VISIBLE, activity.findViewById(R.id.guide_detail).getVisibility());
                assertEquals("p0299 underboost", ((EditText) activity.findViewById(R.id.guide_query))
                        .getText().toString());
                activity.findViewById(R.id.guide_back).performClick();
                assertEquals(View.VISIBLE, activity.findViewById(R.id.guide_catalogue).getVisibility());
                assertEquals(View.GONE, activity.findViewById(R.id.guide_detail).getVisibility());
                assertEquals(1, ((LinearLayout) activity.findViewById(R.id.guide_results)).getChildCount());
            });
        }
    }

    @Test public void mismatchedCodeModuleShowsOnlySafetyFallback()
    {
        Intent intent = new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(),
                WorkshopGuideActivity.class);
        intent.putExtra(WorkshopGuideActivity.EXTRA_MODULE, "SRS");
        intent.putExtra(WorkshopGuideActivity.EXTRA_CODE, "P0299");
        try (ActivityScenario<WorkshopGuideActivity> scenario = ActivityScenario.launch(intent))
        {
            scenario.onActivity(activity ->
            {
                LinearLayout results = activity.findViewById(R.id.guide_results);
                assertEquals(1, results.getChildCount());
                results.getChildAt(0).performClick();
                assertEquals(View.VISIBLE, activity.findViewById(R.id.guide_detail).getVisibility());
                assertEquals(activity.getString(R.string.guide_unsupported_title),
                        activity.getSupportActionBar().getTitle());
            });
        }
    }
}
