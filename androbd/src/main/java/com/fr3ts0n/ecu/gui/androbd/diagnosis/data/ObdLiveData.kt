/* (C) 2026 Bengkel Scan contributors. GPL-2.0-or-later. */
package com.fr3ts0n.ecu.gui.androbd.diagnosis.data

import com.fr3ts0n.ecu.Conversion
import com.fr3ts0n.ecu.EcuCodeItem
import com.fr3ts0n.ecu.EcuDataItem
import com.fr3ts0n.ecu.EcuDataPv
import com.fr3ts0n.ecu.gui.androbd.CommService
import com.fr3ts0n.ecu.gui.androbd.MainActivity
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.ObdDtcEvidence
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.ObdEvidence
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.ObdPidSample
import com.fr3ts0n.ecu.prot.obd.ObdProt
import com.fr3ts0n.pvs.IndexedProcessVar
import com.fr3ts0n.pvs.PvList
import java.lang.ref.WeakReference
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory observations of the existing socket/ELM decoder; never a second adapter connection. */
object ObdLiveData {
    const val EXTRA_RETURN_RESULT = "obd.return_recording"
    private const val HISTORY_PER_ITEM = 32
    private val mutable = MutableStateFlow(ObdEvidence())
    val state = mutable.asStateFlow()
    private var scanner = WeakReference<MainActivity>(null)
    private var generation = 0L
    private var ecu: String? = null
    private var realTransport = false
    private val hex = Regex("[0-9A-F]+")
    private var focusPids = false
    private val requestedPids = intArrayOf(0x01, 0x03, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0C)
    private val continuation = Regex("[0-9A-F]+:[0-9A-F]+")
    private val codePattern = Regex("[PCBU][0-3][0-9A-F]{3}")
    private val bytesByPid = mapOf(0x01 to 4, 0x03 to 2, 0x05 to 1, 0x06 to 1,
        0x07 to 1, 0x08 to 1, 0x09 to 1, 0x0C to 2)
    val keyMnemonics = listOf("engine_speed", "engine_coolant_temperature", "fuel_trim_short_b1",
        "fuel_trim_long_b1", "fuel_trim_short_b2", "fuel_trim_long_b2", "status_fuel_system_1", "status_misfires")

    @JvmStatic @Synchronized fun bind(activity: MainActivity) { scanner = WeakReference(activity) }
    @JvmStatic @Synchronized fun unbind(activity: MainActivity) {
        if (scanner.get() === activity) { scanner.clear(); invalidate("Pemindai ditutup.", "NONE", false) }
    }
    @JvmStatic @Synchronized fun transportChanged(transportState: String, medium: String) {
        realTransport = medium == "BLUETOOTH"
        when (transportState) {
            "CONNECTED" -> { ecu = null; invalidate("Menunggu identifikasi ECU.", if (realTransport) "REAL" else "NONE", realTransport) }
            "CONNECTING" -> { ecu = null; invalidate("Menghubungkan adapter…", "NONE", false) }
            else -> { ecu = null; invalidate("Adapter terputus. Bukti OBD tidak digunakan.", "NONE", false) }
        }
    }
    @JvmStatic @Synchronized fun modeChanged(mode: String) {
        if (mode != "ONLINE") {
            realTransport = false; ecu = null
            invalidate(if (mode == "DEMO" || mode == "FILE") "Data demo/berkas bukan bukti kendaraan saat ini." else
                "Tanpa koneksi OBD. Diagnosis tanpa OBD tetap tersedia.", mode, false)
        }
    }
    @JvmStatic @Synchronized fun ecuSelected(address: Int) {
        ecu = if (address != 0) "0x${address.toUInt().toString(16).uppercase(Locale.ROOT)}" else null
        val old = mutable.value
        invalidate(if (ecu == null) "Pilih satu ECU sebelum menggunakan bukti." else "Menunggu pembacaan baru dari ECU terpilih.",
            old.source, old.connected)
    }
    @JvmStatic @Synchronized fun protocolState(status: String) {
        if (!realTransport || !mutable.value.connected) return
        if (status in setOf("ERROR", "RXERROR", "DATAERROR", "BUSERROR", "DISCONNECTED")) {
            val old = mutable.value
            invalidate("Komunikasi ECU bermasalah ($status). Data sebelumnya dibatalkan; baca ulang atau sambungkan ulang.", old.source, old.connected)
        } else if (status == "NODATA") {
            mutable.value = mutable.value.copy(issue = "ECU tidak menjawab permintaan ini. Tidak ada data bukan berarti kendaraan normal.")
        }
    }
    @JvmStatic @Synchronized fun beginRead(codes: Boolean) {
        focusPids = !codes
        val old = mutable.value
        mutable.value = if (codes) old.copy(codes = emptyList(), issue = "Meminta DTC; layanan yang tidak didukung dapat tidak menjawab.")
            else old.copy(samples = emptyList(), issue = "Meminta PID yang didukung ECU. Nilai belum dibaca tidak dianggap nol.")
    }
    fun requestRead(codes: Boolean): Boolean {
        val owner = synchronized(this) { if (mutable.value.connected && realTransport) scanner.get() else null } ?: return false
        // Never hold the collector lock while calling back into the protocol/UI owner.
        owner.runOnUiThread { owner.requestDiagnosticObdService(codes) }
        return true
    }

    /** Called AFTER ElmProt.handleTelegram by the current RFCOMM worker, including equal-value replies. */
    @JvmStatic fun onTelegram(buffer: CharArray) {
        if (buffer.size !in 2..4096) return
        val packet = String(buffer).filterNot(Char::isWhitespace).uppercase(Locale.ROOT)
        val start = mutable.value
        val selected = synchronized(this) { if (realTransport && start.connected && start.source == "REAL") ecu else null } ?: return
        val now = System.currentTimeMillis()
        // The existing decoder reconstructs DTC multi-line frames; inspect its decoded rows only.
        if (continuation.matches(packet) && CommService.elm.service in intArrayOf(3, 7, 10)) {
            captureCodes(start.sessionId, null, selected, now)
            return
        }
        if (!hex.matches(packet) || packet.length % 2 != 0) return
        if (packet.startsWith("41") && packet.length >= 6 && CommService.elm.service == ObdProt.OBD_SVC_DATA) {
            val pid = packet.substring(2, 4).toInt(16)
            if (pid % 0x20 == 0 && packet.length == 12) {
                val focused = synchronized(this) { focusPids && mutable.value.sessionId == start.sessionId }
                if (focused) { ObdProt.resetFixedPid(); ObdProt.setFixedPid(requestedPids) }
                return
            }
            val bytes = bytesByPid[pid] ?: return
            // ELM is configured headers-off. Never turn a malformed/truncated packet into fresh cached data.
            if (packet.length != 4 + bytes * 2) return
            val received = ArrayList<ObdPidSample>()
            for (pv in items(ObdProt.PidPvs)) {
                if ((pv.get(EcuDataPv.FID_PID) as? Number)?.toInt() != pid) continue
                val mnemonic = pv.get(EcuDataPv.FID_MNEMONIC) as? String ?: continue
                if (mnemonic !in keyMnemonics) continue
                val physical = pv.get(EcuDataPv.FID_VALUE) as? Number ?: continue
                val conversions = pv.get(EcuDataPv.FID_CNVID) as? Array<*> ?: continue
                val current = conversions.getOrNull(EcuDataItem.cnvSystem) as? Conversion ?: continue
                val metric = conversions.getOrNull(EcuDataItem.SYSTEM_METRIC) as? Conversion ?: continue
                val value = metric.memToPhys(Math.round(current.physToMem(physical).toDouble())).toDouble()
                if (value.isFinite()) received.add(ObdPidSample(mnemonic, value, now, selected))
            }
            synchronized(this) {
                val old = mutable.value
                if (old.sessionId != start.sessionId || !old.connected || ecu != selected || received.isEmpty()) return
                val history = ArrayList<ObdPidSample>(old.samples.size + received.size)
                val counts = HashMap<String, Int>()
                received.forEach { counts[it.mnemonic] = 1 }
                for (index in old.samples.indices.reversed()) {
                    val sample = old.samples[index]
                    val count = counts[sample.mnemonic] ?: 0
                    if (count < HISTORY_PER_ITEM) { history.add(sample); counts[sample.mnemonic] = count + 1 }
                }
                history.reverse()
                history.addAll(received)
                mutable.value = old.copy(samples = history, issue = "")
            }
        } else if (packet.length >= 6 && packet.take(2) in setOf("43", "47", "4A")) {
            val status = when (packet.take(2)) { "43" -> "STORED"; "47" -> "PENDING"; else -> "PERMANENT" }
            captureCodes(start.sessionId, status, selected, now)
        }
    }

    private fun captureCodes(session: Long, status: String?, selected: String, now: Long) {
        val received = items(ObdProt.tCodes).mapNotNull { pv ->
            val code = pv.get(EcuCodeItem.FID_CODE) as? String ?: return@mapNotNull null
            val observedStatus = when ((pv.get(EcuCodeItem.FID_STATUS) as? Number)?.toInt()) {
                3 -> "STORED"; 7 -> "PENDING"; 10 -> "PERMANENT"; else -> return@mapNotNull null
            }
            if (code == "P0000" || !codePattern.matches(code) || status != null && observedStatus != status) null
            else ObdDtcEvidence(code, observedStatus, now, selected)
        }
        synchronized(this) {
            val old = mutable.value
            if (old.sessionId != session || !old.connected || ecu != selected) return
            mutable.value = old.copy(codes = (if (status == null) emptyList() else old.codes.filterNot { it.status == status }) + received,
                issue = if (received.isEmpty()) "Tidak ada DTC pada jawaban layanan ini; bukan jaminan kendaraan sehat." else "")
        }
    }
    // Copy list membership under its lock, then release it BEFORE taking child PV locks.
    private fun items(list: PvList): List<IndexedProcessVar> = synchronized(list) { list.values.filterIsInstance<IndexedProcessVar>() }
    private fun invalidate(issue: String, source: String, connected: Boolean) {
        generation++
        mutable.value = ObdEvidence(sessionId = generation, connected = connected, source = source, issue = issue)
    }
}
