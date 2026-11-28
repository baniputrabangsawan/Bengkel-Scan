/* (C) 2026 Bengkel Scan contributors. GPL-2.0-or-later. */
package com.fr3ts0n.ecu.gui.androbd.diagnosis.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.fr3ts0n.ecu.gui.androbd.ModernUiUtils
import com.fr3ts0n.ecu.gui.androbd.R
import com.fr3ts0n.ecu.gui.androbd.SettingsActivity
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.QualityFailure
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.RecordingContext
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.SavedRecording
import com.fr3ts0n.ecu.gui.androbd.workshop.ui.WorkshopUi
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

/** No automatic microphone start, including after permission grants or Activity recreation. */
class GuidedRecordingActivity : AppCompatActivity() {
    private lateinit var model: GuidedRecordingViewModel
    private lateinit var audioModel: AudioDiagnosticViewModel
    private var rendering = false
    private var savedList: List<SavedRecording>? = null
    private var savedBusy: Boolean? = null
    private var savedModelReady: Boolean? = null
    private var displayedQuality: com.fr3ts0n.ecu.gui.androbd.diagnosis.core.QualityResult? = null
    private val conditions = listOf("", "cold_start", "idle", "gradual_rev", "driving_braking")
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> model.permissionResult(granted) }

    override fun onCreate(savedInstanceState: Bundle?) {
        SettingsActivity.applyLocale(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.diagnosis_guided_recording)
        ModernUiUtils.applyScreenInsets(this)
        WorkshopUi.prepare(this, getString(R.string.recording_title))
        val json = intent.getStringExtra(RecordingContext.EXTRA_CONTEXT).orEmpty()
        model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(GuidedRecordingViewModel::class.java))
                @Suppress("UNCHECKED_CAST")
                return GuidedRecordingViewModel(application, json) as T
            }
        })[GuidedRecordingViewModel::class.java]
        audioModel = ViewModelProvider(this)[AudioDiagnosticViewModel::class.java]
        findViewById<Spinner>(R.id.recording_condition).apply {
            adapter = ArrayAdapter.createFromResource(this@GuidedRecordingActivity, R.array.recording_conditions,
                android.R.layout.simple_spinner_item).also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    if (!rendering) model.condition(conditions[position])
                }
            }
        }
        findViewById<MaterialCheckBox>(R.id.recording_safety).setOnCheckedChangeListener { _, checked -> if (!rendering) model.safety(checked) }
        findViewById<MaterialCheckBox>(R.id.recording_observer).setOnCheckedChangeListener { _, checked -> if (!rendering) model.observer(checked) }
        findViewById<MaterialCheckBox>(R.id.recording_consent).setOnCheckedChangeListener { _, checked -> if (!rendering) model.consent(checked) }
        findViewById<EditText>(R.id.recording_group).apply {
            imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            addTextChangedListener { if (!rendering) model.group(it?.toString().orEmpty()) }
        }
        findViewById<View>(R.id.recording_reference_button).setOnClickListener { begin(true) }
        findViewById<View>(R.id.recording_target_button).setOnClickListener { begin(false) }
        findViewById<View>(R.id.recording_stop).setOnClickListener { model.stop() }
        findViewById<View>(R.id.recording_cancel).setOnClickListener { model.cancel() }
        findViewById<View>(R.id.recording_save).setOnClickListener { WorkshopUi.hideKeyboard(this); model.save() }
        findViewById<View>(R.id.recording_rejected_result).setOnClickListener {
            startActivity(Intent(this, AudioResultActivity::class.java).putExtra(AudioResultActivity.EXTRA_REJECTED, true)
                .putExtra(AudioResultActivity.EXTRA_SAFETY_BLOCKED, model.state.value.context?.safetyBlocked == true)
                .putExtra(AudioResultActivity.EXTRA_CONTEXT, model.state.value.context?.json()?.toString())
                .putExtra(AudioResultActivity.EXTRA_REJECTION_REASON, model.state.value.quality?.reasons
                    ?.joinToString("\n\n") { getString(failureMessage(it)) }.orEmpty()))
        }
        findViewById<View>(R.id.audio_technical_fixture).setOnClickListener {
            if (!model.state.value.busy) audioModel.analyzeTechnicalFixture()
        }
        findViewById<View>(R.id.audio_analysis_cancel).setOnClickListener { audioModel.cancel() }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { audioModel.state.collect(::renderAudio) } }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect(::render) } }
    }

    private fun begin(reference: Boolean) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        WorkshopUi.hideKeyboard(this)
        if (!model.state.value.mayRecord || model.state.value.busy || audioModel.state.value.busy) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        if (reference) model.startReference() else model.startRecording()
    }

    private fun render(state: GuidedRecordingViewModel.State) {
        rendering = true
        val condition = conditions.indexOf(state.condition).coerceAtLeast(0)
        findViewById<Spinner>(R.id.recording_condition).apply { if (selectedItemPosition != condition) setSelection(condition); isEnabled = !state.busy }
        findViewById<MaterialCheckBox>(R.id.recording_safety).apply { isChecked = state.safety; isEnabled = !state.busy }
        findViewById<MaterialCheckBox>(R.id.recording_observer).apply {
            visibility = if (state.condition == "driving_braking") View.VISIBLE else View.GONE
            isChecked = state.observer; isEnabled = !state.busy
        }
        findViewById<MaterialCheckBox>(R.id.recording_consent).apply { isChecked = state.consent; isEnabled = !state.busy }
        findViewById<EditText>(R.id.recording_group).apply {
            if (text.toString() != state.vehicleGroup) setText(state.vehicleGroup)
            isEnabled = !state.busy
        }
        text(R.id.recording_guide, when (state.condition) {
            "cold_start" -> getString(R.string.recording_cold_guide)
            "idle" -> getString(R.string.recording_idle_guide)
            "gradual_rev" -> getString(R.string.recording_rev_guide)
            "driving_braking" -> getString(R.string.recording_drive_guide)
            else -> ""
        })
        text(R.id.recording_notice, if (state.notice == 0) "" else getString(state.notice))
        text(R.id.recording_protocol, state.config?.let { getString(R.string.recording_protocol,
            it.referenceSeconds, it.recordingSeconds, it.countdownSeconds) }.orEmpty())
        findViewById<View>(R.id.recording_reference_button).isEnabled = state.mayRecord && !state.busy
        findViewById<View>(R.id.recording_target_button).isEnabled = state.mayRecord && state.referenceReady && !state.busy
        findViewById<View>(R.id.recording_save).apply {
            visibility = if (state.quality?.accepted == true) View.VISIBLE else View.GONE
            isEnabled = !state.busy && state.mayRecord
        }
        val recording = state.stage == GuidedRecordingViewModel.Stage.REFERENCE || state.stage == GuidedRecordingViewModel.Stage.RECORDING
        val counting = state.stage == GuidedRecordingViewModel.Stage.COUNTDOWN
        findViewById<View>(R.id.recording_stop).visibility = if (recording) View.VISIBLE else View.GONE
        findViewById<View>(R.id.recording_cancel).apply {
            visibility = if (recording || counting || state.referenceReady || state.quality != null) View.VISIBLE else View.GONE
            isEnabled = state.stage != GuidedRecordingViewModel.Stage.SAVING
        }
        if (recording || counting) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val seconds = (state.elapsedMs / 1000).toInt()
        text(R.id.recording_status, when (state.stage) {
            GuidedRecordingViewModel.Stage.LOADING -> getString(R.string.recording_loading)
            GuidedRecordingViewModel.Stage.READY -> getString(R.string.recording_ready)
            GuidedRecordingViewModel.Stage.COUNTDOWN -> getString(R.string.recording_countdown, state.countdown)
            GuidedRecordingViewModel.Stage.REFERENCE -> getString(R.string.recording_live_reference, seconds)
            GuidedRecordingViewModel.Stage.RECORDING -> getString(R.string.recording_live_target, seconds)
            GuidedRecordingViewModel.Stage.REVIEW -> getString(R.string.recording_review)
            GuidedRecordingViewModel.Stage.SAVING -> getString(R.string.recording_saving)
        })
        findViewById<ProgressBar>(R.id.recording_level).apply {
            visibility = if (recording) View.VISIBLE else View.GONE
            progress = ((state.levelDbfs + 60.0) / 60.0 * 1000).toInt().coerceIn(0, 1000)
        }
        text(R.id.recording_level_text, if (recording) getString(R.string.recording_meter, state.levelDbfs) else "")
        val quality = state.quality
        text(R.id.recording_quality, if (quality == null) "" else buildString {
            val metrics = quality.metrics
            append(getString(R.string.recording_metrics, metrics.durationSeconds, metrics.rmsDbfs, metrics.peakDbfs, metrics.clippingRatio * 100))
            append('\n').append(metrics.snrEstimateDb?.let { getString(R.string.recording_snr, it) } ?: getString(R.string.recording_snr_unknown))
            quality.reasons.forEach { append("\n\n").append(getString(failureMessage(it))) }
        })
        text(R.id.recording_source, state.diagnostics?.let { diagnostic ->
            val effects = diagnostic.effects.joinToString("\n") { effect ->
                val status = when {
                    effect.after == true -> R.string.recording_effect_active
                    effect.after == false -> R.string.recording_effect_disabled
                    effect.available == false -> R.string.recording_effect_unavailable
                    else -> R.string.recording_effect_unknown
                }
                "${effect.name}: ${getString(status)}"
            }
            getString(R.string.recording_source, diagnostic.sourceName,
                getString(if (diagnostic.fallbackReason == null) R.string.recording_no_fallback else R.string.recording_fallback), effects)
        }.orEmpty())
        if (quality != null && displayedQuality !== quality) {
            findViewById<View>(R.id.recording_quality).post {
                if (!isFinishing && !isDestroyed) findViewById<android.widget.ScrollView>(R.id.recording_scroll)
                    .smoothScrollTo(0, findViewById<View>(R.id.recording_quality).top)
            }
        }
        displayedQuality = quality
        findViewById<View>(R.id.recording_rejected_result).visibility =
            if (state.quality?.accepted == false && !state.busy) View.VISIBLE else View.GONE
        findViewById<View>(R.id.audio_technical_fixture).isEnabled = !state.busy && !audioModel.state.value.busy
        val savedActionsBusy = state.busy || audioModel.state.value.busy
        val modelReady = audioModel.state.value.metadata != null
        if (savedList !== state.saved || savedBusy != savedActionsBusy || savedModelReady != modelReady) {
            savedList = state.saved; savedBusy = savedActionsBusy; savedModelReady = modelReady
            renderSaved(state.saved, savedActionsBusy)
        }
        rendering = false
    }

    private fun renderAudio(state: AudioDiagnosticViewModel.State) {
        text(R.id.audio_mode, when {
            state.metadata?.synthetic == true -> getString(R.string.audio_technical_mode)
            state.metadata != null -> getString(R.string.audio_research_mode)
            else -> getString(R.string.audio_model_unavailable)
        })
        text(R.id.audio_analysis_status, when {
            state.loading -> getString(R.string.audio_model_loading)
            state.failed -> getString(R.string.audio_analysis_failed)
            state.busy -> getString(R.string.audio_analysis_running, state.completedWindows, state.totalWindows)
            state.result != null -> getString(R.string.audio_technical_complete, state.result.windows, state.result.elapsedMs)
            state.metadata != null -> getString(R.string.audio_technical_explanation)
            else -> ""
        })
        findViewById<View>(R.id.audio_technical_fixture).apply {
            visibility = if (state.metadata?.synthetic == true) View.VISIBLE else View.GONE
            isEnabled = !state.busy && !model.state.value.busy
        }
        findViewById<View>(R.id.audio_analysis_cancel).visibility = if (state.busy) View.VISIBLE else View.GONE
        // No fixture class names, probabilities, causes or fused diagnoses are rendered here.
        render(model.state.value)
    }

    private fun renderSaved(items: List<SavedRecording>, busy: Boolean) {
        val root = findViewById<LinearLayout>(R.id.recording_saved_list)
        root.removeAllViews()
        if (items.isEmpty()) root.addView(TextView(this).apply { setText(R.string.recording_empty); setTextAppearance(R.style.UiText_Body) })
        items.forEach { item ->
            val condition = conditions.indexOf(item.condition)
            val label = if (condition > 0) resources.getStringArray(R.array.recording_conditions)[condition] else item.condition
            root.addView(TextView(this).apply {
                text = "$label • ${DateFormat.getDateTimeInstance().format(Date(item.createdAtMs))} • ${item.id.take(8)}"
                setTextAppearance(R.style.UiText_Body)
            })
            root.addView(MaterialButton(this).apply {
                isAllCaps = false
                minHeight = resources.getDimensionPixelSize(R.dimen.ui_touch_primary)
                text = getString(R.string.result_open); isEnabled = !busy
                setOnClickListener {
                    if (!model.state.value.busy) startActivity(Intent(this@GuidedRecordingActivity, AudioResultActivity::class.java)
                        .putExtra(AudioResultActivity.EXTRA_RECORDING_ID, item.id))
                }
            }, LinearLayout.LayoutParams(-1, -2))
            root.addView(MaterialButton(this).apply {
                isAllCaps = false
                minHeight = resources.getDimensionPixelSize(R.dimen.ui_touch_primary)
                text = getString(R.string.recording_delete, item.id.take(8)); isEnabled = !busy
                setOnClickListener {
                    MaterialAlertDialogBuilder(this@GuidedRecordingActivity).setTitle(R.string.recording_delete_title)
                        .setMessage(R.string.recording_delete_message).setNegativeButton(R.string.button_cancel, null)
                        .setPositiveButton(R.string.recording_delete_confirm) { _, _ -> model.delete(item.id) }.show()
                }
            }, LinearLayout.LayoutParams(-1, -2))
        }
    }
    private fun text(id: Int, value: String) {
        findViewById<TextView>(id).apply { if (text.toString() != value) text = value; visibility = if (value.isEmpty()) View.GONE else View.VISIBLE }
    }
    private fun failureMessage(failure: QualityFailure) = when (failure) {
        QualityFailure.TOO_SHORT -> R.string.recording_short
        QualityFailure.TOO_LONG -> R.string.recording_long
        QualityFailure.TOO_QUIET -> R.string.recording_quiet
        QualityFailure.TOO_LOUD -> R.string.recording_loud
        QualityFailure.CLIPPING -> R.string.recording_clipped
        QualityFailure.TOO_NOISY -> R.string.recording_noisy
        QualityFailure.REFERENCE_INVALID -> R.string.recording_reference_invalid
    }
    override fun onPause() {
        model.leave()
        audioModel.cancel()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onPause()
    }
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
