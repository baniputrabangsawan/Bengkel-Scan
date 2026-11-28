package com.fr3ts0n.ecu.gui.androbd.diagnosis.core

import java.util.Locale
import kotlin.math.abs

/** Receipt time is a real observation time, never the time a cached value was read. */
data class ObdPidSample(val mnemonic: String, val value: Double, val receivedAtMs: Long, val ecu: String = "default")
data class ObdDtcEvidence(val code: String, val status: String, val receivedAtMs: Long, val ecu: String = "default")
data class ObdEvidence(
    val sessionId: Long = 0,
    val connected: Boolean = false,
    val source: String = "NONE",
    val codes: List<ObdDtcEvidence> = emptyList(),
    val samples: List<ObdPidSample> = emptyList(),
    val issue: String = "",
)
data class ObdFusionResult(val triage: TriageResult, val notes: List<String>, val used: Boolean)

/** All numeric gates and weights are engineering drafts, not universal vehicle specifications. */
data class ObdFusionConfig(
    val revision: String,
    val pidFreshnessMs: Long,
    val dtcFreshnessMs: Long,
    val reviewNotice: String,
    val dtcRules: List<ObdDtcRule>,
    val fuelTrim: ObdFuelTrimConfig,
)
data class ObdDtcRule(
    val id: String,
    val codes: Set<String>,
    val fuelTypes: Set<String>,
    val description: String,
    val causeWeights: Map<String, Double>,
)
data class ObdTrimBank(val bank: String, val shortMnemonic: String, val longMnemonic: String)
data class ObdFuelTrimConfig(
    val minimumObservations: Int,
    val minimumSpanMs: Long,
    val maximumPairSkewMs: Long,
    val supportFreshnessMs: Long,
    val minimumRpm: Double,
    val minimumCoolantC: Double,
    val positiveCombinedPercent: Double,
    val negativeCombinedPercent: Double,
    val closedLoopValue: Double,
    val banks: List<ObdTrimBank>,
    val positiveCauseWeights: Map<String, Double>,
    val negativeCauseWeights: Map<String, Double>,
)

/** Stateless fusion. The caller must explicitly attach the current, same-vehicle REAL session. */
object ObdEvidenceFusion {
    @JvmStatic fun merge(
        knowledge: ExpertKnowledge,
        context: VehicleContext,
        symptoms: Set<String>,
        audioProbabilities: Map<String, Double>,
        evidence: ObdEvidence,
        config: ObdFusionConfig,
        nowMs: Long,
    ): ObdFusionResult {
        val baseline = DiagnosisScorer.rank(knowledge, context, symptoms, audioProbabilities)
        val notes = ArrayList<String>()
        fun baselineWith(note: String): ObdFusionResult {
            notes.add(note)
            return ObdFusionResult(baseline, notes.toList(), false)
        }
        if (evidence.source != "REAL") return baselineWith("OBD tidak digabung: sumber bukan sesi kendaraan nyata saat ini. Hasil teks dan audio tetap digunakan.")
        if (!evidence.connected || evidence.sessionId <= 0) return baselineWith("OBD tidak digabung: koneksi atau sesi aktif tidak tersedia; bukti lama tidak digunakan.")
        val fuel = context.fuelType?.trim()?.lowercase(Locale.ROOT)
        val system = context.fuelSystem?.trim()?.lowercase(Locale.ROOT)
        if (system == "carburetor" || fuel !in setOf("petrol", "diesel")) {
            return baselineWith("OBD tidak digabung: konteks bahan bakar atau sistem kendaraan belum mendukung aturan ini; tidak ada asumsi jenis mesin.")
        }
        if (evidence.issue.isNotBlank()) notes.add("Keterbatasan sesi OBD: ${evidence.issue}")
        val freshCodes = evidence.codes.filter {
            it.ecu.isNotBlank() && fresh(it.receivedAtMs, nowMs, config.dtcFreshnessMs)
        }
        val freshSamples = evidence.samples.filter {
            it.ecu.isNotBlank() && it.value.isFinite() && fresh(it.receivedAtMs, nowMs, config.pidFreshnessMs)
        }
        if (freshCodes.size != evidence.codes.size || freshSamples.size != evidence.samples.size) {
            notes.add("Bukti OBD kedaluwarsa, bertanggal masa depan, tidak bernilai hingga, atau tanpa ECU dikeluarkan.")
        }
        if (freshCodes.isEmpty() && freshSamples.isEmpty()) return baselineWith("Belum ada bukti OBD segar yang dapat digunakan. Tidak adanya kode bukan bukti kendaraan sehat.")
        val validCauseIds = knowledge.entries.flatMap { it.candidateCauses }.mapTo(HashSet()) { it.id }
        val weights = config.dtcRules.flatMap { it.causeWeights.keys } +
            config.fuelTrim.positiveCauseWeights.keys + config.fuelTrim.negativeCauseWeights.keys
        require(weights.all { it in validCauseIds }) { "obd.unknown_cause_id" }
        val contributions = LinkedHashMap<String, MutableList<ScoreContribution>>()
        fun add(weightsByCause: Map<String, Double>, source: String, reason: String) {
            for ((id, delta) in weightsByCause) {
                contributions.getOrPut(id) { ArrayList() }.add(ScoreContribution(source, delta, reason))
            }
        }
        val normalizedCodes = freshCodes.map { it.copy(code = it.code.trim().uppercase(Locale.ROOT),
            status = it.status.trim().lowercase(Locale.ROOT)) }
        fun statusLabel(status: String): String = when (status) {
            "stored" -> "tersimpan"
            "pending" -> "menunggu konfirmasi"
            "permanent" -> "permanen"
            else -> "belum diketahui"
        }
        for (dtc in normalizedCodes.distinctBy { Triple(it.ecu, it.code, it.status) }) {
            val rule = config.dtcRules.firstOrNull { dtc.code in it.codes }
            when {
                dtc.status == "permanent" -> notes.add("${dtc.code} permanen pada ECU ${dtc.ecu}: riwayat yang masih tersimpan, bukan bukti gangguan aktif; tidak menambah bobot.")
                dtc.status !in setOf("stored", "pending") -> notes.add("${dtc.code} berstatus belum diketahui tidak digunakan untuk pembobotan.")
                rule == null -> notes.add("${dtc.code} (${statusLabel(dtc.status)}) belum memiliki aturan draf; kode tidak dianggap normal atau diabaikan sebagai bukti sehat.")
            }
        }
        for (rule in config.dtcRules) {
            if (fuel !in rule.fuelTypes) continue
            // One family contributes once per session, not once per cylinder/status/ECU.
            val matching = normalizedCodes.filter { it.code in rule.codes && it.status in setOf("stored", "pending") }
                .distinctBy { Triple(it.ecu, it.code, it.status) }
            if (matching.isEmpty()) continue
            val observed = matching.joinToString("; ") { "${it.code} ${statusLabel(it.status)}, ECU ${it.ecu}" }
            add(rule.causeWeights, "obd_dtc", "$observed: ${rule.description} Bobot draf, bukan konfirmasi komponen rusak.")
        }
        val trim = config.fuelTrim
        if (fuel == "petrol" && system == "injection") {
            var foundPattern = false
            for ((ecu, samples) in freshSamples.groupBy { it.ecu }) {
                if (!supported(samples, nowMs, trim)) continue
                for (bank in trim.banks) {
                    val pairs = paired(samples, bank, trim).filter { supported(samples, it.time, trim) }
                    // Require a consistent direction across the recent eligible window, not cherry-picked spikes.
                    if (pairs.size < trim.minimumObservations || pairs.last().time - pairs.first().time < trim.minimumSpanMs) continue
                    val positive = pairs.all { it.short + it.long >= trim.positiveCombinedPercent }
                    val negative = pairs.all { it.short + it.long <= trim.negativeCombinedPercent }
                    if (!positive && !negative) continue
                    foundPattern = true
                    val readings = pairs.joinToString("; ") { "STFT ${number(it.short)}%, LTFT ${number(it.long)}%" }
                    val direction = if (positive) "menambah" else "mengurangi"
                    val reason = "ECU $ecu, bank ${bank.bank}: $readings (${pairs.size} pasangan segar dalam ${pairs.last().time - pairs.first().time} ms). Mesin berjalan, coolant memenuhi gerbang hangat draf dan status closed-loop diketahui. ECU $direction bahan bakar; pola ini bukan diagnosis atau batas servis universal."
                    add(if (positive) trim.positiveCauseWeights else trim.negativeCauseWeights, "obd_fuel_trim", reason)
                }
            }
            if (!foundPattern) notes.add("Fuel trim belum digunakan: perlu pasangan STFT/LTFT berulang pada bank dan ECU yang sama, mesin berjalan, coolant hangat terukur, dan closed-loop segar. PID yang hilang atau tidak didukung tidak dianggap normal.")
        } else notes.add("Aturan fuel trim bensin injeksi tidak diterapkan pada konteks diesel atau sistem bahan bakar yang belum dipastikan.")
        notes.add("Status kesiapan misfire bukan penghitung misfire. Penghitung Mode 06 atau khusus pabrikan tidak tersedia melalui bukti ini.")
        if (contributions.isEmpty()) return baselineWith("Tidak ada aturan OBD yang memenuhi syarat; hasil teks dan audio tidak berubah. Tidak adanya kode bukan bukti sehat.")
        val merged = DiagnosisScorer.rank(knowledge, context, symptoms, audioProbabilities, contributions)
        val used = merged.causes.any { cause -> cause.contributions.any { it.source == "obd_dtc" || it.source == "obd_fuel_trim" } }
        notes.add(config.reviewNotice)
        return ObdFusionResult(merged, notes.toList(), used)
    }

    private fun fresh(time: Long, now: Long, window: Long): Boolean =
        time >= 0 && now >= time && window > 0 && now - time <= window

    private fun supported(samples: List<ObdPidSample>, at: Long, config: ObdFuelTrimConfig): Boolean {
        fun latest(mnemonic: String): Double? = samples.asSequence().filter {
            it.mnemonic == mnemonic && fresh(it.receivedAtMs, at, config.supportFreshnessMs)
        }.maxByOrNull { it.receivedAtMs }?.value
        return (latest("engine_speed") ?: return false) >= config.minimumRpm &&
            (latest("engine_coolant_temperature") ?: return false) >= config.minimumCoolantC &&
            latest("status_fuel_system_1") == config.closedLoopValue
    }

    private data class PairReading(val short: Double, val long: Double, val time: Long)

    private fun paired(samples: List<ObdPidSample>, bank: ObdTrimBank, config: ObdFuelTrimConfig): List<PairReading> {
        val shorts = samples.filter { it.mnemonic == bank.shortMnemonic }.distinctBy { it.receivedAtMs }.sortedBy { it.receivedAtMs }
        val longs = samples.filter { it.mnemonic == bank.longMnemonic }.distinctBy { it.receivedAtMs }.sortedBy { it.receivedAtMs }
        val result = ArrayList<PairReading>()
        var left = 0
        var right = 0
        while (left < shorts.size && right < longs.size) {
            val short = shorts[left]
            val long = longs[right]
            if (abs(short.receivedAtMs - long.receivedAtMs) <= config.maximumPairSkewMs) {
                result.add(PairReading(short.value, long.value, maxOf(short.receivedAtMs, long.receivedAtMs)))
                left++
                right++
            } else if (short.receivedAtMs < long.receivedAtMs) left++ else right++
        }
        return result
    }

    private fun number(value: Double): String = String.format(Locale.ROOT, "%.1f", value)
}
