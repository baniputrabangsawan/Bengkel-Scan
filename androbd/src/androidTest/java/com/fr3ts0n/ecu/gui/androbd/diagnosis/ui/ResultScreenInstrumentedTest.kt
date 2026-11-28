package com.fr3ts0n.ecu.gui.androbd.diagnosis.ui

import android.app.ActivityOptions
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fr3ts0n.ecu.gui.androbd.BuildConfig
import com.fr3ts0n.ecu.gui.androbd.R
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosticOutcome
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.VehicleProfile
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.*
import com.google.android.material.checkbox.MaterialCheckBox
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.sin

/** UI fixtures are source-set-isolated examples, never automotive predictions. No microphone/network use. */
@RunWith(AndroidJUnit4::class)
class ResultScreenInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private fun options(): android.os.Bundle {
        val display = requireNotNull(InstrumentationRegistry.getArguments().getString("displayId")).toInt()
        require(display != 0) { "Use an isolated virtual display, never the main screen" }
        return ActivityOptions.makeBasic().apply { launchDisplayId = display }.toBundle()
    }

    @Test fun everyOutcomeRendersItsRealLayoutAndSecondaryDangerIsProminent() {
        val cases = listOf("confident" to R.string.result_confident_title, "low" to R.string.result_low_title,
            "noise" to R.string.result_noise_title, "rejected" to R.string.result_rejected_title,
            "conflict" to R.string.result_conflict_title, "stop_second" to R.string.result_expert_title,
            "no_percentage_stop" to R.string.result_expert_title, "technical" to R.string.result_technical_title,
            "unavailable" to R.string.result_unavailable_title, "error" to R.string.result_error_title)
        for ((name, title) in cases) {
            ActivityScenario.launch<ResultPreviewActivity>(Intent(app, ResultPreviewActivity::class.java).putExtra("scenario", name), options()).use { scenario ->
                scenario.onActivity { activity ->
                    assertEquals(activity.getString(title), activity.findViewById<TextView>(R.id.result_outcome_title).text.toString())
                    assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.result_example).visibility)
                    val warning = activity.findViewById<View>(R.id.result_stop_warning)
                    assertEquals(if (name in setOf("stop_second", "no_percentage_stop")) View.VISIBLE else View.GONE, warning.visibility)
                    assertTrue(activity.findViewById<TextView>(R.id.result_severity).compoundDrawablesRelative[0] != null)
                    if (warning.visibility == View.VISIBLE) assertTrue(warning.top < activity.findViewById<View>(R.id.result_causes).top)
                    assertFalse(activity.findViewById<View>(R.id.result_export).isEnabled)
                    assertTrue(activity.findViewById<TextView>(R.id.result_disclaimer).text.contains("Keputusan akhir ada pada mekanik"))
                }
                capture(scenario, "phase6-$name.png")
            }
        }
    }

    @Test fun realSavedRecordingUsesUncalibratedGuidanceAndConsentIsExplicitAndRevocable() = withRecording { id, store ->
        ActivityScenario.launch<AudioResultActivity>(Intent(app, AudioResultActivity::class.java)
            .putExtra(AudioResultActivity.EXTRA_RECORDING_ID, id), options()).use { scenario ->
            await(scenario) { !model(it).state.value.busy && model(it).state.value.result != null }
            scenario.onActivity { activity ->
                assertFalse(activity.findViewById<MaterialCheckBox>(R.id.result_mechanic_verified).isChecked)
                assertFalse(activity.findViewById<MaterialCheckBox>(R.id.result_export_consent).isChecked)
                assertFalse(activity.findViewById<View>(R.id.result_export).isEnabled)
                activity.findViewById<EditText>(R.id.result_description).setText("Tek tek saat mesin dingin lalu hilang saat panas")
                activity.findViewById<View>(R.id.result_review_description).performClick()
            }
            await(scenario) { !model(it).state.value.busy && model(it).state.value.result?.causes?.isNotEmpty() == true }
            scenario.onActivity { activity ->
                assertEquals(DiagnosticOutcome.EXPERT_ONLY, model(activity).state.value.result!!.outcome)
                assertTrue(model(activity).state.value.result!!.causes.all { it.probability == null })
                activity.findViewById<View>(R.id.result_feedback_correct).performClick()
            }
            await(scenario) { !model(it).state.value.busy && model(it).state.value.feedback != null }
            assertEquals(FeedbackChoice.CORRECT, store.load(id)!!.choice)
            assertFalse(store.load(id)!!.mechanicVerified)
            assertFalse(store.load(id)!!.consentGranted)
            scenario.onActivity { it.findViewById<MaterialCheckBox>(R.id.result_export_consent).isChecked = true }
            await(scenario) { !model(it).state.value.busy && model(it).state.value.feedback?.consentGranted == true }
            val packageFile = File(app.cacheDir, "phase6-consented-package.zip")
            try {
                assertFalse(packageFile.exists())
                scenario.onActivity { model(it).export(android.net.Uri.fromFile(packageFile)) }
                await(scenario) { !model(it).state.value.busy && model(it).state.value.notice == R.string.result_exported }
                java.util.zip.ZipFile(packageFile).use { zip ->
                    val entries = zip.entries().toList().map { it.name }.toSet()
                    assertEquals(setOf("audio.wav", "vehicle-context.json", "feedback.json", "manifest.json"), entries)
                    val vehicle = zip.getInputStream(zip.getEntry("vehicle-context.json")).bufferedReader().use { it.readText() }
                    assertFalse(vehicle.contains("\"location\""))
                    assertFalse(vehicle.contains("device_group_id"))
                    assertFalse(vehicle.contains("vehicle_group_id"))
                }
            } finally { if (packageFile.exists()) assertTrue(packageFile.delete()) }
            scenario.onActivity {
                assertTrue(it.findViewById<View>(R.id.result_export).isEnabled)
                it.findViewById<MaterialCheckBox>(R.id.result_export_consent).isChecked = false
            }
            await(scenario) { !model(it).state.value.busy && model(it).state.value.feedback?.consentGranted == false }
            val destination = ByteArrayOutputStream()
            var refused = false
            try { store.exportPackage(id, modelVersion(scenario), destination) } catch (_: Exception) { refused = true }
            assertTrue(refused)
            assertEquals(0, destination.size())
            scenario.onActivity { it.findViewById<View>(R.id.result_feedback_incorrect).performClick() }
            await(scenario) { !model(it).state.value.busy && model(it).state.value.feedback?.choice == FeedbackChoice.INCORRECT }
            assertFalse(store.load(id)!!.consentGranted)
            scenario.onActivity { model(it).saveFeedback(FeedbackChoice.ACTUAL_CAUSE, "Label pengguna untuk fixture teknis", false) }
            await(scenario) { !model(it).state.value.busy && model(it).state.value.feedback?.choice == FeedbackChoice.ACTUAL_CAUSE }
            assertFalse(store.load(id)!!.mechanicVerified)
            assertFalse(store.load(id)!!.consentGranted)
            scenario.onActivity { it.findViewById<android.widget.ScrollView>(R.id.result_scroll).fullScroll(View.FOCUS_DOWN) }
            capture(scenario, "phase6-consent.png")
        }
    }

    @Test fun savedRecordingNavigationOpensMatchingResultAndRejectedScreenCannotExport() = withRecording { id, _ ->
        val monitor = instrumentation.addMonitor(AudioResultActivity::class.java.name, null, false)
        try {
            val context = RecordingContext(VehicleProfile(fuelType = "diesel"))
            ActivityScenario.launch<GuidedRecordingActivity>(Intent(app, GuidedRecordingActivity::class.java)
                .putExtra(RecordingContext.EXTRA_CONTEXT, context.json().toString()), options()).use { scenario ->
                val deadline = SystemClock.elapsedRealtime() + 10000
                var opened = false
                while (!opened && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { activity ->
                        val root = activity.findViewById<ViewGroup>(R.id.recording_saved_list)
                        for (index in 0 until root.childCount - 1) {
                            if ((root.getChildAt(index) as? TextView)?.text?.contains(id.take(8)) == true) {
                                opened = root.getChildAt(index + 1).performClick(); break
                            }
                        }
                    }
                    if (!opened) SystemClock.sleep(25)
                }
                assertTrue(opened)
                val result = instrumentation.waitForMonitorWithTimeout(monitor, 5000)
                assertTrue(result is AudioResultActivity)
                instrumentation.runOnMainSync {
                    assertEquals(id, result.intent.getStringExtra(AudioResultActivity.EXTRA_RECORDING_ID)); result.finish()
                }
            }
        } finally { instrumentation.removeMonitor(monitor) }
        ActivityScenario.launch<AudioResultActivity>(Intent(app, AudioResultActivity::class.java)
            .putExtra(AudioResultActivity.EXTRA_REJECTED, true), options()).use { scenario ->
            await(scenario) { !model(it).state.value.busy }
            scenario.onActivity {
                assertEquals(DiagnosticOutcome.RECORDING_REJECTED, model(it).state.value.result!!.outcome)
                assertFalse(it.findViewById<View>(R.id.result_feedback_actual).isEnabled)
                assertFalse(it.findViewById<View>(R.id.result_export).isEnabled)
            }
        }
    }

    private fun model(activity: AudioResultActivity) = ViewModelProvider(activity)[AudioResultViewModel::class.java]
    private fun modelVersion(scenario: ActivityScenario<AudioResultActivity>): String {
        var version = ""
        scenario.onActivity { version = model(it).state.value.config!!.consentTextVersion }
        return version
    }
    private fun await(scenario: ActivityScenario<AudioResultActivity>, condition: (AudioResultActivity) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        var done = false
        while (!done && SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { done = condition(it) }
            if (!done) SystemClock.sleep(25)
        }
        assertTrue("Result state not reached", done)
    }
    private fun withRecording(test: (String, RecordingFeedbackStore) -> Unit) {
        val root = File(app.noBackupFilesDir, "guided-recordings")
        val store = RecordingStore(root, RecordingDevice(Build.MANUFACTURER, Build.MODEL, Build.VERSION.SDK_INT, BuildConfig.VERSION_NAME))
        val config = RecordingConfigLoader.load(app)
        val diagnostic = CaptureDiagnostics(9, 9, "UNPROCESSED", null, 16000, 1, "PCM_16BIT", 17, 99, 15,
            "engineering_fixture", 16000, false, emptyList(), 9)
        fun tone(seconds: Int, amplitude: Double) = ShortArray(16000 * seconds) { (sin(2 * PI * 400 * it / 16000) * 32768 * amplitude).toInt().toShort() }
        val saved = store.save(RecordingContext(VehicleProfile(fuelType = "diesel")), "idle",
            PcmCapture(tone(15, 0.2), diagnostic, 6000), PcmCapture(tone(2, 0.002), diagnostic, 1000), config, 500, "phase6-engineering-fixture")
        try { test(saved.id, RecordingFeedbackStore(root)) } finally { store.delete(saved.id) }
    }
    private fun <T : AppCompatActivity> capture(scenario: ActivityScenario<T>, name: String) {
        instrumentation.waitForIdleSync()
        val frame = CountDownLatch(1)
        scenario.onActivity { it.window.decorView.postOnAnimation { it.window.decorView.postOnAnimation { frame.countDown() } } }
        assertTrue(frame.await(5, TimeUnit.SECONDS))
        val copied = CountDownLatch(1)
        var status = -1
        var bitmap: Bitmap? = null
        scenario.onActivity { activity ->
            bitmap = Bitmap.createBitmap(activity.window.decorView.width, activity.window.decorView.height, Bitmap.Config.ARGB_8888)
            PixelCopy.request(activity.window, bitmap!!, { status = it; copied.countDown() }, Handler(Looper.getMainLooper()))
        }
        assertTrue(copied.await(5, TimeUnit.SECONDS)); assertEquals(PixelCopy.SUCCESS, status)
        File(app.cacheDir, name).outputStream().use { assertTrue(bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap!!.recycle()
    }
}
