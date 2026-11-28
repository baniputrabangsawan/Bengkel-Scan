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
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fr3ts0n.ecu.gui.androbd.R
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.VehicleProfile
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.RecordingContext
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioDiagnosticUiInstrumentedTest {
    @Test fun explicitTechnicalActionExposesStateFlowWithoutVehicleDiagnosis() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val display = requireNotNull(InstrumentationRegistry.getArguments().getString("displayId")) { "Use an isolated virtual display" }.toInt()
        require(display != 0) { "Do not disturb the user's main display" }
        val options = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= 26) options.launchDisplayId = display
        val intent = Intent(instrumentation.targetContext, GuidedRecordingActivity::class.java)
            .putExtra(RecordingContext.EXTRA_CONTEXT, RecordingContext(VehicleProfile()).json().toString())
        ActivityScenario.launch<GuidedRecordingActivity>(intent, options.toBundle()).use { scenario ->
            await(scenario) { model(it).state.value.metadata != null }
            scenario.onActivity { activity ->
                assertEquals("Mode uji teknis, bukan diagnosis", activity.findViewById<TextView>(R.id.audio_mode).text.toString())
                assertNull(model(activity).state.value.result)
                activity.findViewById<ScrollView>(R.id.recording_scroll).fullScroll(View.FOCUS_DOWN)
                assertTrue(activity.findViewById<View>(R.id.audio_technical_fixture).performClick())
            }
            await(scenario) { model(it).state.value.result != null || model(it).state.value.failed }
            scenario.onActivity { activity ->
                val state = model(activity).state.value
                assertFalse(state.failed)
                assertFalse(state.busy)
                assertTrue(state.result!!.synthetic)
                assertNull(state.result.triage)
                assertEquals("Mode uji teknis, bukan diagnosis", activity.findViewById<TextView>(R.id.audio_mode).text.toString())
                val summary = activity.findViewById<TextView>(R.id.audio_analysis_status).text.toString()
                assertTrue(summary.contains("Akurasi diagnosis belum diukur"))
                assertFalse(summary.contains("belt_squeal"))
                assertFalse(summary.contains("%"))
                activity.findViewById<ScrollView>(R.id.recording_scroll).fullScroll(View.FOCUS_DOWN)
            }
            instrumentation.waitForIdleSync()
            if (Build.VERSION.SDK_INT >= 26) {
                val latch = CountDownLatch(1)
                var status = -1
                var bitmap: Bitmap? = null
                scenario.onActivity { activity ->
                    val image = Bitmap.createBitmap(activity.window.decorView.width, activity.window.decorView.height, Bitmap.Config.ARGB_8888)
                    bitmap = image
                    PixelCopy.request(activity.window, image, { status = it; latch.countDown() }, Handler(Looper.getMainLooper()))
                }
                assertTrue(latch.await(5, TimeUnit.SECONDS))
                assertEquals(PixelCopy.SUCCESS, status)
                File(instrumentation.targetContext.cacheDir, "phase5-technical-mode.png").outputStream().use {
                    assertTrue(bitmap!!.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                bitmap!!.recycle()
            }
        }
    }

    private fun model(activity: GuidedRecordingActivity) = ViewModelProvider(activity)[AudioDiagnosticViewModel::class.java]
    private fun await(scenario: ActivityScenario<GuidedRecordingActivity>, condition: (GuidedRecordingActivity) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15000
        var ready = false
        while (!ready && SystemClock.elapsedRealtime() < deadline) {
            scenario.onActivity { ready = condition(it) }
            if (!ready) SystemClock.sleep(25)
        }
        assertTrue("Technical inference state was not reached", ready)
    }
}
