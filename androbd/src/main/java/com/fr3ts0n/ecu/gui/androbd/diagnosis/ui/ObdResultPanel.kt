/* (C) 2026 Bengkel Scan contributors. GPL-2.0-or-later. */
package com.fr3ts0n.ecu.gui.androbd.diagnosis.ui

import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.fr3ts0n.ecu.gui.androbd.DfcItemAdapter
import com.fr3ts0n.ecu.gui.androbd.R
import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.ObdLiveData
import com.google.android.material.checkbox.MaterialCheckBox
import java.util.Locale

/** Presentation only: the fusion engine owns every numeric diagnostic decision. */
class ObdResultPanel(private val activity: AppCompatActivity) {
    private val labels = mapOf("engine_speed" to "Putaran mesin", "engine_coolant_temperature" to "Suhu pendingin",
        "fuel_trim_short_b1" to "STFT bank 1", "fuel_trim_long_b1" to "LTFT bank 1",
        "fuel_trim_short_b2" to "STFT bank 2", "fuel_trim_long_b2" to "LTFT bank 2")

    fun render(state: AudioResultViewModel.State, allowed: Boolean) {
        val evidence = state.obd
        val connected = evidence.connected && evidence.source == "REAL"
        text(R.id.result_obd_status, when {
            !allowed && state.obdConfig != null -> activity.getString(R.string.obd_carburetor)
            !connected -> evidence.issue.ifBlank { activity.getString(R.string.obd_unavailable) }
            else -> "Sesi OBD ${evidence.sessionId} · adapter langsung\n" + evidence.issue
        })
        activity.findViewById<View>(R.id.result_obd_read_actions).visibility = if (connected) View.VISIBLE else View.GONE
        listOf(R.id.result_obd_codes, R.id.result_obd_pids).forEach { activity.findViewById<View>(it).isEnabled = connected && !state.busy }
        activity.findViewById<MaterialCheckBox>(R.id.result_obd_use).apply {
            isChecked = state.obdAttached
            isEnabled = connected && allowed && !state.busy
        }
        text(R.id.result_obd_notes, listOf(activity.getString(if (state.obdUsed) R.string.obd_used else R.string.obd_not_used))
            .plus(state.obdNotes).joinToString("\n\n"))
        val now = state.obdNowMs.takeIf { it > 0 } ?: System.currentTimeMillis()
        val readouts = ObdLiveData.keyMnemonics.joinToString("\n\n") { mnemonic ->
            val sample = evidence.samples.filter { it.mnemonic == mnemonic }.maxByOrNull { it.receivedAtMs }
            val label = labels[mnemonic] ?: activity.getString(if (mnemonic == "status_misfires") R.string.obd_status_misfires else R.string.obd_fuel_status)
            val value = when {
                sample == null -> activity.getString(R.string.obd_pid_unread)
                sample.receivedAtMs > now || now - sample.receivedAtMs > (state.obdConfig?.pidFreshnessMs ?: 0) -> activity.getString(R.string.obd_pid_stale)
                else -> {
                    val unit = when (mnemonic) { "engine_speed" -> "rpm"; "engine_coolant_temperature" -> "°C"
                        "status_misfires", "status_fuel_system_1" -> ""; else -> "%" }
                    "${String.format(Locale.getDefault(), "%.1f", sample.value)} $unit · ${sample.ecu} · ${(now - sample.receivedAtMs) / 1000} dtk lalu"
                }
            }
            "$label: $value"
        }
        text(R.id.result_obd_readouts, readouts)
        val dtcs = evidence.codes.joinToString("\n\n") { code ->
            val status = when (code.status) { "STORED" -> "tersimpan"; "PENDING" -> "menunggu konfirmasi"; "PERMANENT" -> "permanen/riwayat, bukan bukti gangguan aktif"; else -> "status belum diketahui" }
            val stale = if (code.receivedAtMs > now || now - code.receivedAtMs > (state.obdConfig?.dtcFreshnessMs ?: 0)) " · kedaluwarsa, tidak dipakai" else ""
            "${code.code} · $status$stale\n${DfcItemAdapter.indonesianDescription(code.code, activity.getString(R.string.obd_unknown_code))}"
        }.ifEmpty { activity.getString(R.string.obd_no_codes) }
        text(R.id.result_obd_dtcs, dtcs)
        listOf(R.id.result_obd_readouts, R.id.result_obd_dtcs, R.id.result_obd_limits, R.id.result_obd_use).forEach {
            activity.findViewById<View>(it).visibility = if (connected) View.VISIBLE else View.GONE
        }
    }
    private fun text(id: Int, value: String) {
        activity.findViewById<TextView>(id).apply { if (text.toString() != value) text = value }
    }
}
