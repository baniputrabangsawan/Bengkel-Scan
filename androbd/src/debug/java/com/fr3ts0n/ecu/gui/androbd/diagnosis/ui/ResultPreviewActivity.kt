package com.fr3ts0n.ecu.gui.androbd.diagnosis.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.fr3ts0n.ecu.gui.androbd.ModernUiUtils
import com.fr3ts0n.ecu.gui.androbd.R
import com.fr3ts0n.ecu.gui.androbd.SettingsActivity
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.*
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.ResultConfigLoader
import com.fr3ts0n.ecu.gui.androbd.workshop.ui.WorkshopUi

/** Source-set-isolated UI examples, NOT model predictions or real calibrated diagnoses. */
class ResultPreviewActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        SettingsActivity.applyLocale(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.diagnosis_audio_result)
        ModernUiUtils.applyScreenInsets(this)
        WorkshopUi.prepare(this, getString(R.string.result_title))
        val config = ResultConfigLoader.load(this)
        val scenario = intent.getStringExtra("scenario") ?: "low"
        fun cause(id: String, title: String, severity: Severity) = ExpertRankedCause(
            ExpertEntry(id, title, "Contoh layar buatan", emptySet(), emptySet(), emptyList(), severity,
                listOf("Catat gejala yang sudah diamati dari tempat aman.", "Minta mekanik memeriksa sesuai manual kendaraan."),
                "Jangan memicu gejala untuk menguji. Minta pemeriksaan mekanik.", "unknown", "needs_mechanic_verification", true),
            ExpertCause("$id.cause", title, 1.0, emptyList(), emptyList()), 1.0,
            listOf(ScoreContribution("fixture", 1.0, "Contoh buatan khusus pengujian tampilan; bukan temuan kendaraan.")))
        val candidates = listOf(cause("fixture_a", "Sabuk aksesori perlu diperiksa", Severity.INSPECT),
            cause("fixture_b", "Komponen rem perlu diperiksa", if (scenario in setOf("stop_second", "no_percentage_stop")) Severity.STOP else Severity.INSPECT),
            cause("fixture_c", "Pelindung komponen perlu diperiksa", Severity.INSPECT))
        val triage = TriageResult(candidates, Severity.INSPECT, TriageStatus.RANKED, "Contoh uji", getString(R.string.result_disclaimer), "Data buatan", emptyList())
        val probabilities = if (scenario == "confident") mapOf("fixture_a.cause" to 0.8, "fixture_b.cause" to 0.15, "fixture_c.cause" to 0.05)
            else if (scenario == "stop_second") mapOf("fixture_a.cause" to 0.55, "fixture_b.cause" to 0.30, "fixture_c.cause" to 0.15) else null
        val audio = when (scenario) {
            "noise" -> mapOf("background_noise" to 0.85, "fixture_a" to 0.15)
            "conflict" -> mapOf("fixture_b" to 0.85, "fixture_a" to 0.15)
            "low" -> mapOf("fixture_a" to 0.45, "fixture_b" to 0.30, "background_noise" to 0.25)
            "confident" -> mapOf("fixture_a" to 0.8, "fixture_b" to 0.15, "background_noise" to 0.05)
            else -> emptyMap()
        }
        val input = ResultInput(triage = if (scenario in setOf("technical", "unavailable")) null else triage,
            audioProbabilities = audio, reportedSymptoms = if (scenario == "conflict") setOf("fixture_a") else emptySet(),
            recordingRejected = scenario == "rejected", synthetic = scenario == "technical", modelAvailable = scenario != "unavailable",
            calibratedCauseProbabilities = probabilities, technicalExample = true)
        val result = DiagnosticResultPolicy.resolve(input, config)
        DiagnosticResultScreen(this).render(if (scenario == "error") result.copy(outcome = DiagnosticOutcome.ERROR, causes = emptyList()) else result)
        findViewById<View>(R.id.result_loading).visibility = View.GONE
        listOf(R.id.result_description, R.id.result_review_description, R.id.result_feedback_correct, R.id.result_feedback_incorrect,
            R.id.result_feedback_actual, R.id.result_export_consent, R.id.result_export, R.id.result_mechanic_verified, R.id.result_nearby)
            .forEach { findViewById<View>(it).isEnabled = false }
    }
}
