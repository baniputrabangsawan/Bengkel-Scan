package com.fr3ts0n.ecu.gui.androbd.diagnosis.ui

import android.content.Intent
import android.os.Build
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real platform capability evidence only: this does not emulate a transcript or open the microphone. */
@RunWith(AndroidJUnit4::class)
class OfflineSpeechCapabilityInstrumentedTest {
    @Test fun reportIndonesianOnDeviceCapabilityWithoutNetworkFallback() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val finished = CountDownLatch(1)
        var recognizer: SpeechRecognizer? = null
        instrumentation.runOnMainSync {
            val available = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
            println("SPEECH_ON_DEVICE_AVAILABLE=$available")
            if (!available || Build.VERSION.SDK_INT < 33) finished.countDown()
            else {
                val current = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                recognizer = current
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "id-ID")
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                current.checkRecognitionSupport(intent, context.mainExecutor, object : RecognitionSupportCallback {
                    override fun onSupportResult(support: RecognitionSupport) {
                        println("SPEECH_INSTALLED_LANGUAGES=${support.installedOnDeviceLanguages}")
                        println("SPEECH_SUPPORTED_LANGUAGES=${support.supportedOnDeviceLanguages}")
                        finished.countDown()
                    }
                    override fun onError(error: Int) {
                        println("SPEECH_SUPPORT_ERROR=$error")
                        finished.countDown()
                    }
                })
            }
        }
        try { assertTrue("Speech service did not answer capability request", finished.await(12, TimeUnit.SECONDS)) }
        finally { instrumentation.runOnMainSync { recognizer?.destroy() } }
    }
}
