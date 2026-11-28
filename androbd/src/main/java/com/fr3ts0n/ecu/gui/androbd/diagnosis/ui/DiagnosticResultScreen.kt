/* (C) 2026 Bengkel Scan contributors. GPL-2.0-or-later. */
package com.fr3ts0n.ecu.gui.androbd.diagnosis.ui

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.fr3ts0n.ecu.gui.androbd.R
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosticOutcome
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosticResult
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.Severity

/** The same real view tree renders production evidence and isolated debug-only UI examples. */
class DiagnosticResultScreen(private val activity: AppCompatActivity) {
    private var displayed: DiagnosticResult? = null

    fun render(result: DiagnosticResult) {
        if (displayed == result) return
        displayed = result
        activity.findViewById<View>(R.id.result_example).visibility = if (result.technicalExample) View.VISIBLE else View.GONE
        val (title, message) = when (result.outcome) {
            DiagnosticOutcome.CONFIDENT -> R.string.result_confident_title to R.string.result_confident_message
            DiagnosticOutcome.LOW_CONFIDENCE -> R.string.result_low_title to R.string.result_low_message
            DiagnosticOutcome.BACKGROUND_NOISE -> R.string.result_noise_title to R.string.result_noise_message
            DiagnosticOutcome.RECORDING_REJECTED -> R.string.result_rejected_title to R.string.result_rejected_message
            DiagnosticOutcome.CONFLICTING_EVIDENCE -> R.string.result_conflict_title to R.string.result_conflict_message
            DiagnosticOutcome.EXPERT_ONLY -> R.string.result_expert_title to R.string.result_expert_message
            DiagnosticOutcome.TECHNICAL_ONLY -> R.string.result_technical_title to R.string.result_technical_message
            DiagnosticOutcome.MODEL_UNAVAILABLE -> R.string.result_unavailable_title to R.string.result_unavailable_message
            DiagnosticOutcome.ERROR -> R.string.result_error_title to R.string.result_error_message
        }
        activity.findViewById<TextView>(R.id.result_outcome_title).setText(title)
        activity.findViewById<TextView>(R.id.result_outcome_message).setText(message)
        severity(activity.findViewById(R.id.result_severity), result.severity)
        activity.findViewById<TextView>(R.id.result_stop_warning).apply {
            visibility = if (result.stopWarning) View.VISIBLE else View.GONE
            text = buildString {
                append(activity.getString(R.string.result_stop_warning))
                result.safetyReasons.forEach { append("\n\n").append(it) }
            }
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_result_stop, 0, 0, 0)
        }
        activity.findViewById<TextView>(R.id.result_probability_notice)
            .setText(if (result.technicalExample) R.string.result_probability_example else R.string.result_probability_unavailable)
        activity.findViewById<TextView>(R.id.result_disclaimer).setText(R.string.result_disclaimer)
        val root = activity.findViewById<LinearLayout>(R.id.result_causes)
        root.removeAllViews()
        result.causes.forEachIndexed { index, cause ->
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.ui_chip_neutral)
                val padding = activity.resources.getDimensionPixelSize(R.dimen.ui_card_padding)
                setPadding(padding, padding, padding, padding)
            }
            card.addView(label(activity.getString(R.string.result_cause_heading, index + 1, cause.title), R.style.UiText_Section))
            card.addView(TextView(activity).also { severity(it, cause.severity) })
            cause.probability?.let { card.addView(label(activity.getString(R.string.result_probability, it * 100), R.style.UiText_Readout)) }
            card.addView(label(cause.reason, R.style.UiText_Body))
            card.addView(label(activity.getString(R.string.result_verify), R.style.UiText_Section))
            cause.verificationTests.forEach { card.addView(label("• $it", R.style.UiText_Body)) }
            card.addView(label(activity.getString(R.string.result_action), R.style.UiText_Section))
            card.addView(label(cause.recommendedAction, R.style.UiText_Body))
            root.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = activity.resources.getDimensionPixelSize(R.dimen.ui_section_gap)
                bottomMargin = activity.resources.getDimensionPixelSize(R.dimen.ui_space_3)
            })
        }
    }

    private fun label(value: String, appearance: Int) = TextView(activity).apply {
        text = value
        setTextAppearance(appearance)
        setTextIsSelectable(true)
    }

    private fun severity(view: TextView, severity: Severity) {
        val (icon, background, color) = when (severity) {
            Severity.MONITOR -> Triple(R.drawable.ic_result_monitor, R.drawable.ui_chip_success, R.color.workshop_status_success_text)
            Severity.INSPECT -> Triple(R.drawable.ic_result_inspect, R.drawable.ui_chip_warning, R.color.workshop_status_warning_text)
            Severity.STOP -> Triple(R.drawable.ic_result_stop, R.drawable.ui_chip_danger, R.color.workshop_status_danger_text)
        }
        view.text = severity.label
        view.setTextAppearance(R.style.UiText_Chip)
        view.setTextColor(ContextCompat.getColor(activity, color))
        view.setBackgroundResource(background)
        view.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
        view.compoundDrawablePadding = activity.resources.getDimensionPixelSize(R.dimen.ui_space_2)
        val padding = activity.resources.getDimensionPixelSize(R.dimen.ui_space_2)
        view.setPadding(padding, padding, padding, padding)
    }
}
