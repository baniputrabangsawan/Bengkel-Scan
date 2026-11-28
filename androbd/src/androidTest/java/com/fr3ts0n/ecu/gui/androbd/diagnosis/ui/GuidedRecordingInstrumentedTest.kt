package com.fr3ts0n.ecu.gui.androbd.diagnosis.ui

import android.app.ActivityOptions
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.widget.Spinner
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fr3ts0n.ecu.gui.androbd.R
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.AudioQuality
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.QualityFailure
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.VehicleProfile
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.RecordingConfigLoader
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.RecordingContext
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.RecordingStore
import com.google.android.material.checkbox.MaterialCheckBox
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.log10
import android.widget.EditText
import kotlin.math.sqrt

/** No microphone capture in these tests: countdown is cancelled before capture begins. */
@RunWith(AndroidJUnit4::class)
class GuidedRecordingInstrumentedTest {
    @Test fun unreviewedDangerousTextFlowsThroughActualNavigationAndBlocksMicrophone() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val options = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= 26) options.launchDisplayId = InstrumentationRegistry.getArguments().getString("displayId")?.toInt() ?: 0
        val monitor = instrumentation.addMonitor(GuidedRecordingActivity::class.java.name, null, false)
        try {
            ActivityScenario.launch<VehicleContextActivity>(Intent(instrumentation.targetContext, VehicleContextActivity::class.java),
                options.toBundle()).use { scenario ->
                val deadline = SystemClock.elapsedRealtime() + 5000
                var ready = false
                while (!ready && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { ready = it.findViewById<View>(R.id.context_record_audio).isEnabled }
                    if (!ready) SystemClock.sleep(30)
                }
                assertTrue(ready)
                scenario.onActivity {
                    it.findViewById<EditText>(R.id.context_description).setText("Rem blong dan setir berat")
                    it.findViewById<View>(R.id.context_record_audio).performClick()
                }
                val launched = instrumentation.waitForMonitorWithTimeout(monitor, 6000)
                assertTrue("Vehicle form did not open real recording screen", launched is GuidedRecordingActivity)
                instrumentation.runOnMainSync {
                    val info = RecordingContext.parse(launched.intent.getStringExtra(RecordingContext.EXTRA_CONTEXT)!!)
                    assertTrue(info.safetyBlocked)
                    launched.finish()
                }
            }
        } finally { instrumentation.removeMonitor(monitor) }
    }

    @Test fun drivingRequiresOutsideObserverAndConsentAndCancelStopsCountdown() = screen { scenario ->
        await(scenario) { model(it).state.value.config != null }
        scenario.onActivity {
            assertFalse(it.findViewById<View>(R.id.recording_reference_button).isEnabled)
            it.findViewById<Spinner>(R.id.recording_condition).setSelection(4)
        }
        await(scenario) { model(it).state.value.condition == "driving_braking" }
        scenario.onActivity {
            it.findViewById<MaterialCheckBox>(R.id.recording_safety).isChecked = true
            it.findViewById<MaterialCheckBox>(R.id.recording_consent).isChecked = true
        }
        await(scenario) { model(it).state.value.safety && model(it).state.value.consent }
        scenario.onActivity {
            assertFalse(it.findViewById<View>(R.id.recording_reference_button).isEnabled)
            it.findViewById<MaterialCheckBox>(R.id.recording_observer).isChecked = true
        }
        await(scenario) { it.findViewById<View>(R.id.recording_reference_button).isEnabled }
        scenario.onActivity {
            // ViewModel action starts countdown only; no native microphone until countdown expires.
            model(it).startReference()
            assertEquals(GuidedRecordingViewModel.Stage.COUNTDOWN, model(it).state.value.stage)
            model(it).cancel()
            assertEquals(GuidedRecordingViewModel.Stage.READY, model(it).state.value.stage)
            assertFalse(model(it).state.value.referenceReady)
        }
    }

    @Test fun recreationDiscardsCountdownWithoutRestartingMicrophone() = screen { scenario ->
        prepare(scenario)
        scenario.onActivity { model(it).startReference() }
        scenario.recreate()
        await(scenario) { model(it).state.value.stage == GuidedRecordingViewModel.Stage.READY }
        scenario.onActivity {
            assertEquals(R.string.recording_cancelled, model(it).state.value.notice)
            assertFalse(model(it).state.value.referenceReady)
            assertNull(model(it).state.value.diagnostics)
        }
    }

    @Test fun stopSeverityContextCannotStartCaptureEvenWithCheckboxes() = screen(true) { scenario ->
        prepare(scenario)
        scenario.onActivity {
            assertFalse(model(it).state.value.mayRecord)
            model(it).startReference()
            assertEquals(GuidedRecordingViewModel.Stage.READY, model(it).state.value.stage)
            assertEquals(R.string.recording_blocked, model(it).state.value.notice)
            assertNull(model(it).state.value.diagnostics)
        }
    }

    @Test fun permissionGrantDoesNotStartMicrophoneAndRevocationClearsConsent() = screen { scenario ->
        prepare(scenario)
        scenario.onActivity {
            model(it).permissionResult(true)
            assertEquals(GuidedRecordingViewModel.Stage.READY, model(it).state.value.stage)
            assertEquals(R.string.recording_permission_ready, model(it).state.value.notice)
            model(it).consent(false)
            assertFalse(model(it).state.value.mayRecord)
            assertNull(model(it).state.value.diagnostics)
        }
    }

    @Test fun fixedDigitalSignalProducesReferenceMetricsAndExplicitRejectsOnAndroid() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val config = RecordingConfigLoader.load(context).copy(recordingSeconds = 2, minDurationSeconds = 2.0, maxDurationSeconds = 2.0)
        val signal = ShortArray(32000) { when (it % 4) { 1 -> 8192; 3 -> -8192; else -> 0 }.toShort() }
        val background = ShortArray(32000) { when (it % 4) { 1 -> 256; 3 -> -256; else -> 0 }.toShort() }
        val result = AudioQuality.evaluate(signal, background, config)
        assertTrue(result.accepted)
        assertEquals(2.0, result.metrics.durationSeconds, 0.0)
        assertEquals(sqrt(0.03125), result.metrics.rms, 1e-12)
        assertEquals(20 * log10(sqrt(0.03125)), result.metrics.rmsDbfs, 1e-9)
        assertEquals(0.25, result.metrics.peak, 0.0)
        assertEquals(0.0, result.metrics.clippingRatio, 0.0)
        assertEquals(10 * log10(1023.0), result.metrics.snrEstimateDb!!, 1e-9)
        assertTrue(QualityFailure.TOO_QUIET in AudioQuality.evaluate(ShortArray(32000), background, config).reasons)
        assertTrue(QualityFailure.CLIPPING in AudioQuality.evaluate(ShortArray(32000) { Short.MIN_VALUE }, background, config).reasons)
        assertTrue(QualityFailure.TOO_SHORT in AudioQuality.evaluate(signal.copyOf(300), background, config).reasons)
        assertTrue(QualityFailure.TOO_NOISY in AudioQuality.evaluate(background, background, config).reasons)
        println("RECORDING_DIGITAL_DEVICE=${Build.MANUFACTURER}/${Build.MODEL} metrics=${RecordingStore.metrics(result.metrics)}")
    }

    private fun prepare(scenario: ActivityScenario<GuidedRecordingActivity>) {
        await(scenario) { model(it).state.value.config != null }
        scenario.onActivity { it.findViewById<Spinner>(R.id.recording_condition).setSelection(2) }
        await(scenario) { model(it).state.value.condition == "idle" }
        scenario.onActivity {
            it.findViewById<MaterialCheckBox>(R.id.recording_safety).isChecked = true
            it.findViewById<MaterialCheckBox>(R.id.recording_consent).isChecked = true
        }
        await(scenario) { model(it).state.value.safety && model(it).state.value.consent }
    }
    private fun model(activity: GuidedRecordingActivity) = ViewModelProvider(activity)[GuidedRecordingViewModel::class.java]
    private fun await(scenario: ActivityScenario<GuidedRecordingActivity>, condition: (GuidedRecordingActivity) -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 5000
        var ready = false
        while (!ready && SystemClock.elapsedRealtime() < end) {
            scenario.onActivity { ready = condition(it) }; if (!ready) SystemClock.sleep(30)
        }
        assertTrue("Recording workflow state not reached", ready)
    }
    private fun screen(blocked: Boolean = false, test: (ActivityScenario<GuidedRecordingActivity>) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val options = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= 26) options.launchDisplayId = InstrumentationRegistry.getArguments().getString("displayId")?.toInt() ?: 0
        val info = RecordingContext(VehicleProfile(), safetyBlocked = blocked)
        val intent = Intent(instrumentation.targetContext, GuidedRecordingActivity::class.java)
            .putExtra(RecordingContext.EXTRA_CONTEXT, info.json().toString())
        ActivityScenario.launch<GuidedRecordingActivity>(intent, options.toBundle()).use(test)
    }
}
