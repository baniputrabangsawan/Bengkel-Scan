package com.fr3ts0n.ecu.gui.androbd.diagnosis.ui

import android.Manifest
import android.app.ActivityOptions
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.widget.Spinner
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.fr3ts0n.ecu.gui.androbd.R
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.VehicleProfile
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.RecordingContext
import com.google.android.material.checkbox.MaterialCheckBox
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Opt in only with owner consent in a safe, nonprivate location; captures 2s and never saves it. */
@RunWith(AndroidJUnit4::class)
class GuidedRecordingHardwareInstrumentedTest {
    @get:Rule val microphone = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    @Test fun realForegroundReferenceReportsFormatEffectsAndReleasesOnCancel() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Explicit microphoneProbe=true required; do not listen during unattended suite runs", args.getString("microphoneProbe") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val options = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= 26) options.launchDisplayId = args.getString("displayId")?.toInt() ?: 0
        val context = RecordingContext(VehicleProfile())
        val intent = Intent(instrumentation.targetContext, GuidedRecordingActivity::class.java)
            .putExtra(RecordingContext.EXTRA_CONTEXT, context.json().toString())
        ActivityScenario.launch<GuidedRecordingActivity>(intent, options.toBundle()).use { scenario ->
            fun model(activity: GuidedRecordingActivity) = ViewModelProvider(activity)[GuidedRecordingViewModel::class.java]
            fun await(condition: (GuidedRecordingActivity) -> Boolean) {
                var ready = false
                val deadline = SystemClock.elapsedRealtime() + 12000
                while (!ready && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { ready = condition(it) }; if (!ready) SystemClock.sleep(30)
                }
                assertTrue("Actual microphone workflow did not reach expected state", ready)
            }
            await { model(it).state.value.config != null }
            scenario.onActivity { it.findViewById<Spinner>(R.id.recording_condition).setSelection(2) }
            await { model(it).state.value.condition == "idle" }
            var before = 0
            scenario.onActivity {
                before = model(it).state.value.saved.size
                it.findViewById<MaterialCheckBox>(R.id.recording_safety).isChecked = true
                it.findViewById<MaterialCheckBox>(R.id.recording_consent).isChecked = true
            }
            await { it.findViewById<android.view.View>(R.id.recording_reference_button).isEnabled }
            scenario.onActivity { it.findViewById<android.view.View>(R.id.recording_reference_button).performClick() }
            await { model(it).state.value.stage == GuidedRecordingViewModel.Stage.REFERENCE }
            await { model(it).state.value.stage == GuidedRecordingViewModel.Stage.READY }
            scenario.onActivity {
                val state = model(it).state.value
                val diagnostics = state.diagnostics
                assertNotNull("Hardware capture failed with notice=${state.notice}", diagnostics)
                diagnostics!!
                assertEquals(16000, diagnostics.sampleRate)
                assertEquals(1, diagnostics.channels)
                assertEquals("PCM_16BIT", diagnostics.encoding)
                assertEquals(setOf("AGC", "NS", "AEC"), diagnostics.effects.map { effect -> effect.name }.toSet())
                assertTrue(state.elapsedMs >= 1000)
                assertEquals(before, state.saved.size)
                println("RECORDING_HARDWARE_DEVICE=${Build.MANUFACTURER}/${Build.MODEL} diagnostics=$diagnostics")
                model(it).cancel()
                assertFalse(model(it).state.value.referenceReady)
                assertNull(model(it).state.value.diagnostics)
            }
        }
    }
}
