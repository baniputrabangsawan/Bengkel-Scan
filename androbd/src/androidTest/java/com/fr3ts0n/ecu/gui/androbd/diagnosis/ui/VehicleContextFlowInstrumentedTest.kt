package com.fr3ts0n.ecu.gui.androbd.diagnosis.ui

import android.app.ActivityOptions
import android.content.Intent
import android.os.SystemClock
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fr3ts0n.ecu.gui.androbd.R
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.Severity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises actual controls and the shipped parser/scorer, without synthesizing speech results. */
@RunWith(AndroidJUnit4::class)
class VehicleContextFlowInstrumentedTest {
    @Test fun typedBrakeSquealFlowsThroughReviewAndKeepsDraftCautionAfterRecreation() = screen { scenario ->
        await(scenario) { it.findViewById<android.view.View>(R.id.context_review).isEnabled }
        scenario.onActivity { activity ->
            activity.findViewById<EditText>(R.id.context_description).setText("Rem cit-cit saat ngerem")
            activity.findViewById<android.view.View>(R.id.context_review).performClick()
        }
        await(scenario) { model(it).state.value.review != null }
        scenario.onActivity { activity ->
            assertEquals(setOf("brake_squeal"), model(activity).state.value.symptoms)
            assertTrue(activity.findViewById<EditText>(R.id.context_description).isEnabled)
            activity.findViewById<android.view.View>(R.id.context_assess).performClick()
        }
        await(scenario) { model(it).state.value.result != null }
        scenario.onActivity { activity ->
            val result = requireNotNull(model(activity).state.value.result)
            assertEquals(Severity.INSPECT, result.severity)
            assertTrue(result.causes.all { it.entry.id == "brake_squeal" })
            assertTrue(result.reviewNotice.isNotBlank())
            assertTrue(activity.findViewById<LinearLayout>(R.id.context_results).childCount > 0)
        }
        scenario.recreate()
        await(scenario) { model(it).state.value.result != null }
        scenario.onActivity { activity ->
            assertEquals("Rem cit-cit saat ngerem", activity.findViewById<EditText>(R.id.context_description).text.toString())
            assertFalse(activity.findViewById<EditText>(R.id.context_description).isSaveEnabled)
            activity.findViewById<EditText>(R.id.context_description).setText("Mesin langsam normal")
            assertNull(model(activity).state.value.result)
        }
    }

    @Test fun hissShowsQuestionsAndObservedSteamStopsWithoutInventingVacuumLeak() = screen { scenario ->
        await(scenario) { it.findViewById<android.view.View>(R.id.context_review).isEnabled }
        scenario.onActivity { activity ->
            activity.findViewById<EditText>(R.id.context_description).setText("Mendesis dari ruang mesin saat langsam")
            activity.findViewById<android.view.View>(R.id.context_review).performClick()
        }
        await(scenario) { model(it).state.value.review != null }
        scenario.onActivity { activity ->
            val state = model(activity).state.value
            assertEquals(setOf("hiss"), state.symptoms)
            assertEquals(setOf("steam", "sweet_smell", "temperature_rising"), state.review?.followUpQuestions)
            val panel = activity.findViewById<LinearLayout>(R.id.context_followups)
            val spinner = (0 until panel.childCount).map { panel.getChildAt(it) }
                .filterIsInstance<android.widget.Spinner>().first()
            spinner.setSelection(1)
        }
        await(scenario) { model(it).state.value.observations["steam"] == "yes" }
        scenario.onActivity { it.findViewById<android.view.View>(R.id.context_assess).performClick() }
        await(scenario) { model(it).state.value.result != null }
        scenario.onActivity {
            val result = requireNotNull(model(it).state.value.result)
            assertEquals(Severity.STOP, result.severity)
            assertTrue(result.causes.isEmpty())
        }
    }

    @Test fun combinedBrakeSteeringComplaintAsksEngineStallFirstAndDoesNotDowngrade() = screen { scenario ->
        await(scenario) { it.findViewById<android.view.View>(R.id.context_review).isEnabled }
        scenario.onActivity { activity ->
            activity.findViewById<EditText>(R.id.context_description).setText("Rem blong dan setir mendadak berat")
            activity.findViewById<android.view.View>(R.id.context_review).performClick()
        }
        await(scenario) { model(it).state.value.review != null }
        scenario.onActivity { activity ->
            val panel = activity.findViewById<LinearLayout>(R.id.context_followups)
            val question = (0 until panel.childCount).map { panel.getChildAt(it) }.filterIsInstance<TextView>()
                .first { it.text.toString().contains("apakah mesin mati") }
            assertTrue(question.text.toString().startsWith("Periksa pertama"))
            activity.findViewById<android.view.View>(R.id.context_assess).performClick()
        }
        await(scenario) { model(it).state.value.result != null }
        scenario.onActivity { assertEquals(Severity.STOP, model(it).state.value.result?.severity) }
    }

    private fun model(activity: VehicleContextActivity) = ViewModelProvider(activity)[VehicleContextViewModel::class.java]
    private fun await(scenario: ActivityScenario<VehicleContextActivity>, condition: (VehicleContextActivity) -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 8000
        var ready = false
        while (!ready && SystemClock.elapsedRealtime() < end) {
            scenario.onActivity { ready = condition(it) }
            if (!ready) SystemClock.sleep(40)
        }
        assertTrue("Expected visible workflow state was not reached", ready)
    }
    private fun screen(test: (ActivityScenario<VehicleContextActivity>) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val id = InstrumentationRegistry.getArguments().getString("displayId")?.toIntOrNull() ?: 0
        val options = ActivityOptions.makeBasic()
        if (android.os.Build.VERSION.SDK_INT >= 26) options.launchDisplayId = id
        val intent = Intent(instrumentation.targetContext, VehicleContextActivity::class.java)
        ActivityScenario.launch<VehicleContextActivity>(intent, options.toBundle()).use(test)
    }
}
