/* (C) 2026 Bengkel Scan contributors. GPL-2.0-or-later. */
package com.fr3ts0n.ecu.gui.androbd.diagnosis.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.fr3ts0n.ecu.gui.androbd.ModernUiUtils
import com.fr3ts0n.ecu.gui.androbd.R
import com.fr3ts0n.ecu.gui.androbd.SettingsActivity
import com.fr3ts0n.ecu.gui.androbd.MainActivity
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.ObdLiveData
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.FeedbackChoice
import com.fr3ts0n.ecu.gui.androbd.workshop.ui.WorkshopUi
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.launch

class AudioResultActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_RECORDING_ID = "result.recording_id"
        const val EXTRA_REJECTED = "result.recording_rejected"
        const val EXTRA_SAFETY_BLOCKED = "result.safety_blocked"
        const val EXTRA_REJECTION_REASON = "result.rejection_reason"
        const val EXTRA_CONTEXT = "result.vehicle_context"
        const val EXTRA_SYMPTOMS = "result.reviewed_symptoms"
    }
    private lateinit var model: AudioResultViewModel
    private lateinit var screen: DiagnosticResultScreen
    private var rendering = false
    private lateinit var obdPanel: ObdResultPanel
    private val destination = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null && model.mayExport()) model.export(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        SettingsActivity.applyLocale(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.diagnosis_audio_result)
        ModernUiUtils.applyScreenInsets(this)
        WorkshopUi.prepare(this, getString(R.string.result_title))
        screen = DiagnosticResultScreen(this)
        val id = intent.getStringExtra(EXTRA_RECORDING_ID)
        obdPanel = ObdResultPanel(this)
        val rejected = intent.getBooleanExtra(EXTRA_REJECTED, false)
        model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass.isAssignableFrom(AudioResultViewModel::class.java))
                @Suppress("UNCHECKED_CAST")
                return AudioResultViewModel(application, id, rejected, intent.getBooleanExtra(EXTRA_SAFETY_BLOCKED, false),
                    intent.getStringExtra(EXTRA_CONTEXT), intent.getStringArrayListExtra(EXTRA_SYMPTOMS)?.toSet().orEmpty()) as T
            }
        })[AudioResultViewModel::class.java]
        findViewById<EditText>(R.id.result_description).imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        findViewById<View>(R.id.result_review_description).setOnClickListener {
            WorkshopUi.hideKeyboard(this)
            model.assessDescription(findViewById<EditText>(R.id.result_description).text.toString())
        }
        findViewById<View>(R.id.result_feedback_correct).setOnClickListener { feedback(FeedbackChoice.CORRECT) }
        findViewById<View>(R.id.result_obd_scanner).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(ObdLiveData.EXTRA_RETURN_RESULT, id.orEmpty())
                .putExtra(EXTRA_CONTEXT, intent.getStringExtra(EXTRA_CONTEXT)))
        }
        findViewById<View>(R.id.result_obd_codes).setOnClickListener {
            if (!ObdLiveData.requestRead(true)) Toast.makeText(this, R.string.obd_scanner_failed, Toast.LENGTH_LONG).show()
        }
        findViewById<View>(R.id.result_obd_pids).setOnClickListener {
            if (!ObdLiveData.requestRead(false)) Toast.makeText(this, R.string.obd_scanner_failed, Toast.LENGTH_LONG).show()
        }
        findViewById<MaterialCheckBox>(R.id.result_obd_use).setOnCheckedChangeListener { box, checked ->
            if (!rendering) {
                if (!checked) model.attachObd(false)
                else {
                    rendering = true; box.isChecked = false; rendering = false
                    MaterialAlertDialogBuilder(this).setTitle(R.string.obd_confirm_title).setMessage(R.string.obd_confirm_message)
                        .setNegativeButton(R.string.obd_without, null)
                        .setPositiveButton(R.string.obd_confirm_use) { _, _ -> model.attachObd(true) }.show()
                }
            }
        }
        findViewById<View>(R.id.result_feedback_incorrect).setOnClickListener { feedback(FeedbackChoice.INCORRECT) }
        findViewById<View>(R.id.result_feedback_actual).setOnClickListener { actualCause() }
        findViewById<MaterialCheckBox>(R.id.result_export_consent).setOnCheckedChangeListener { _, checked ->
            if (!rendering) model.consent(checked)
        }
        findViewById<View>(R.id.result_export).setOnClickListener {
            if (model.mayExport()) destination.launch("tanggapan-${id?.take(8).orEmpty()}.zip")
        }
        findViewById<View>(R.id.result_nearby).setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode("bengkel")}")))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, R.string.result_maps_unavailable, Toast.LENGTH_LONG).show()
            }
        }
        lifecycleScope.launch { repeatOnLifecycle(Lifecycle.State.STARTED) { model.state.collect(::render) } }
    }

    private fun feedback(choice: FeedbackChoice) = model.saveFeedback(choice, null,
        findViewById<MaterialCheckBox>(R.id.result_mechanic_verified).isChecked)

    private fun actualCause() {
        val layout = TextInputLayout(this, null, com.google.android.material.R.attr.textInputFilledStyle).apply {
            hint = getString(R.string.result_actual_hint)
            val padding = resources.getDimensionPixelSize(R.dimen.ui_card_padding)
            setPadding(padding, padding, padding, 0)
        }
        val input = com.google.android.material.textfield.TextInputEditText(layout.context).apply {
            id = View.generateViewId()
            isSaveEnabled = false
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            filters = arrayOf(InputFilter.LengthFilter(1000))
        }
        layout.addView(input)
        val dialog = MaterialAlertDialogBuilder(this).setTitle(R.string.result_feedback_actual).setView(layout)
            .setNegativeButton(R.string.button_cancel, null).setPositiveButton(R.string.recording_save, null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val cause = input.text?.toString()?.trim().orEmpty()
                if (cause.isBlank()) layout.error = getString(R.string.result_actual_required)
                else {
                    model.saveFeedback(FeedbackChoice.ACTUAL_CAUSE, cause,
                        findViewById<MaterialCheckBox>(R.id.result_mechanic_verified).isChecked)
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun render(state: AudioResultViewModel.State) {
        rendering = true
        state.result?.let(screen::render)
        obdPanel.render(state, model.canUseObd())
        if (state.result?.outcome == com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosticOutcome.RECORDING_REJECTED) {
            val reason = intent.getStringExtra(EXTRA_REJECTION_REASON).orEmpty()
            findViewById<TextView>(R.id.result_outcome_message).text = getString(R.string.result_rejected_message) +
                if (reason.isBlank()) "" else "\n\n$reason"
        }
        findViewById<View>(R.id.result_loading).visibility = if (state.busy) View.VISIBLE else View.GONE
        if (state.result == null) {
            findViewById<TextView>(R.id.result_outcome_title).setText(R.string.result_loading)
        }
        val canFeedback = state.canFeedback && !state.busy && state.result?.technicalExample != true
        val hasCauses = state.result?.causes?.isNotEmpty() == true
        findViewById<View>(R.id.result_feedback_correct).isEnabled = canFeedback && hasCauses
        findViewById<View>(R.id.result_feedback_incorrect).isEnabled = canFeedback && hasCauses
        findViewById<View>(R.id.result_feedback_actual).isEnabled = canFeedback
        findViewById<View>(R.id.result_mechanic_verified).isEnabled = canFeedback
        findViewById<View>(R.id.result_review_description).isEnabled = !state.busy && state.config != null
        findViewById<View>(R.id.result_description).isEnabled = !state.busy
        val saved = state.feedback
        findViewById<TextView>(R.id.result_feedback_status).text = when {
            !state.canFeedback -> getString(R.string.result_feedback_no_recording)
            saved == null -> getString(R.string.result_feedback_help)
            else -> getString(R.string.result_feedback_saved_status, getString(when (saved.choice) {
                FeedbackChoice.CORRECT -> R.string.result_feedback_correct
                FeedbackChoice.INCORRECT -> R.string.result_feedback_incorrect
                FeedbackChoice.ACTUAL_CAUSE -> R.string.result_feedback_actual
            }), getString(if (saved.mechanicVerified) R.string.result_yes else R.string.result_no))
        }
        findViewById<MaterialCheckBox>(R.id.result_export_consent).apply {
            isChecked = saved?.consentGranted == true && saved.consentTextVersion == state.config?.consentTextVersion
            isEnabled = canFeedback && saved != null
        }
        findViewById<View>(R.id.result_export).isEnabled = model.mayExport()
        findViewById<TextView>(R.id.result_consent_version).text = state.config?.let {
            getString(R.string.result_consent_version, it.consentTextVersion)
        }.orEmpty()
        findViewById<TextView>(R.id.result_notice).text = if (state.notice == 0) "" else getString(state.notice)
        rendering = false
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}
