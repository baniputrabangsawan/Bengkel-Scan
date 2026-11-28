package com.fr3ts0n.ecu.gui.androbd.diagnosis.core

import java.util.Collections
import java.util.Locale

/** Reported lexical observations only; these are not confirmed component failures. */
data class DescriptionMatch(
    val symptomIds: Set<String>,
    val triggerConditions: Set<String>,
    val engineTempState: String?,
    val location: String?,
    val flags: Set<String>,
    val explanations: List<String>,
    val needsClarification: Boolean,
    val temperaturePattern: String? = null,
    val followUpQuestions: Set<String> = emptySet(),
)

internal data class DescriptionRule(
    val kind: String,
    val id: String,
    val phrases: List<String>,
    val requiredAny: List<String>,
    val excludedAny: List<String>,
    val explanation: String,
)

/** Stateless, bounded phrase matching. Obtain a validated instance from SymptomLexiconLoader. */
class SymptomDescriptionParser internal constructor(rules: List<DescriptionRule>) {
    private data class Phrase(val tokens: List<String>)
    private data class Rule(
        val source: DescriptionRule,
        val phrases: List<Phrase>,
        val required: List<Phrase>,
        val excluded: List<Phrase>,
    )
    private data class Clause(val tokens: List<String>, val inheritedNegative: Boolean)
    private data class Hit(val rule: Rule, val clause: Int, val start: Int, val end: Int)
    private val rules = rules.map { rule ->
        Rule(rule, rule.phrases.map(::phrase), rule.requiredAny.map(::phrase), rule.excludedAny.map(::phrase))
    }

    fun parse(text: String): DescriptionMatch {
        if (text.length > MAX_INPUT_CHARS || malformed(text)) return unknown(INVALID_INPUT)
        val clauses = clauses(text)
        val positive = ArrayList<Hit>()
        val denied = HashSet<String>()
        var uncertain = false
        var safetyCaution = false
        clauses.forEachIndexed { clauseIndex, clause ->
            // A question alone is not an assertion, including questions about a safety condition.
            val question = clause.tokens.any { it in QUESTIONS }
            rules.forEach { rule ->
                val contexts = rule.required.flatMap { occurrences(clause.tokens, it) }
                val contextMatches = rule.required.isEmpty() || contexts.any { range ->
                    !negated(clause, range.first, range.last + 1, "context")
                }
                if (!contextMatches || rule.excluded.any { occurrences(clause.tokens, it).isNotEmpty() }) {
                    return@forEach
                }
                rule.phrases.forEach { phrase ->
                    occurrences(clause.tokens, phrase).forEach { range ->
                        val key = key(rule)
                        if (question) {
                            uncertain = true
                        } else if (negated(clause, range.first, range.last + 1, rule.source.kind)) {
                            denied.add(key)
                        } else if (clause.tokens.any { it in UNCERTAIN }) {
                            uncertain = true
                            if (isSafety(rule)) {
                                positive.add(Hit(rule, clauseIndex, range.first, range.last + 1))
                                safetyCaution = true
                            }
                        } else {
                            positive.add(Hit(rule, clauseIndex, range.first, range.last + 1))
                        }
                    }
                }
            }
        }
        val contradictions = positive.any { key(it.rule) in denied }
        if (positive.any { isSafety(it.rule) && key(it.rule) in denied }) safetyCaution = true
        val accepted = positive.filter { key(it.rule) !in denied || isSafety(it.rule) }
        val specific = accepted.filter { it.rule.source.kind == "symptom" && it.rule.source.id != "unknown" }
        // Generic sound words are unresolved only when no more specific rule covers that occurrence.
        val unresolved = accepted.any { hit ->
            hit.rule.source.kind == "symptom" && hit.rule.source.id == "unknown" && specific.none {
                it.clause == hit.clause && it.start <= hit.start && it.end >= hit.end
            }
        }
        val symptoms = linkedSetOf<String>()
        val triggers = linkedSetOf<String>()
        val locations = linkedSetOf<String>()
        val flags = linkedSetOf<String>()
        val patterns = linkedSetOf<String>()
        val explanations = linkedSetOf<String>()
        // Asset order, rather than hash iteration or transcript order, defines explanation order.
        rules.forEach { rule ->
            if (accepted.any { it.rule === rule } && rule.source.id != "unknown") {
                when (rule.source.kind) {
                    "symptom" -> symptoms.add(rule.source.id)
                    "trigger" -> triggers.add(rule.source.id)
                    "location" -> locations.add(rule.source.id)
                    "flag" -> flags.add(rule.source.id)
                    "temperature_pattern" -> patterns.add(rule.source.id)
                }
                explanations.add(rule.source.explanation)
            }
        }
        if ("fades_when_warm" in flags) patterns.add("fades_when_warm")
        // "Roda depan" is compatible with wheel + front, not two contradictory locations.
        if (locations.size > 1 && ("front" in locations || "rear" in locations)) locations.remove("wheel")
        val temperatureConflict = patterns.size > 1 ||
            ("cold_only" in patterns && "warm" in triggers) ||
            ("cold" in triggers && "warm" in triggers && "fades_when_warm" !in patterns)
        if (temperatureConflict) flags.remove("fades_when_warm")
        val locationConflict = locations.size > 1
        val followUps = linkedSetOf<String>()
        if ("brake_change" in flags && "steering_change" in flags) {
            followUps.add("engine_stalled")
            explanations.add(ENGINE_STALL_QUESTION)
        }
        if ("hiss" in symptoms) {
            followUps.addAll(listOf("steam", "sweet_smell", "temperature_rising"))
        }
        val clarification = symptoms.isEmpty() || unresolved || uncertain || contradictions ||
            temperatureConflict || locationConflict || followUps.isNotEmpty()
        if (symptoms.isEmpty() || unresolved || uncertain || contradictions) {
            symptoms.add("unknown")
            explanations.add(UNKNOWN_SOUND)
        }
        if (contradictions) explanations.add(CONTRADICTION)
        if (safetyCaution) explanations.add(SAFETY_CAUTION)
        if (temperatureConflict) explanations.add(TEMPERATURE_CONFLICT)
        if (locationConflict) explanations.add(LOCATION_CONFLICT)
        val temperature = when {
            temperatureConflict || ("cold" in triggers && "warm" in triggers) -> null
            "cold" in triggers -> "cold"
            "warm" in triggers -> "warm"
            else -> null
        }
        return DescriptionMatch(frozen(symptoms), frozen(triggers), temperature,
            locations.singleOrNull(), frozen(flags), Collections.unmodifiableList(explanations.toList()), clarification,
            if (temperatureConflict) null else patterns.singleOrNull(), frozen(followUps))
    }

    private fun key(rule: Rule): String = "${rule.source.kind}:${rule.source.id}"
    private fun isSafety(rule: Rule): Boolean = rule.source.kind == "flag" && rule.source.id in SAFETY_FLAGS
    private fun phrase(text: String): Phrase = Phrase(normalizePhrase(text).split(' '))

    private fun occurrences(tokens: List<String>, phrase: Phrase): List<IntRange> {
        if (phrase.tokens.size > tokens.size) return emptyList()
        val matches = ArrayList<IntRange>()
        for (start in 0..tokens.size - phrase.tokens.size) {
            if (phrase.tokens.indices.all { tokens[start + it] == phrase.tokens[it] }) {
                matches.add(start until start + phrase.tokens.size)
            }
        }
        return matches
    }

    private fun negated(clause: Clause, start: Int, end: Int, kind: String): Boolean {
        if (clause.inheritedNegative) return true
        val tokens = clause.tokens
        for (index in 0 until start) {
            if (tokens[index] in NEGATIONS) {
                // Negating disappearance does not negate the reported warm condition/location.
                if (kind != "symptom" && kind != "flag" && tokens.getOrNull(index + 1) in DISAPPEARANCE) continue
                return true
            }
        }
        // Postposed absence/resolution must not become a current symptom or safety flag.
        if (kind == "symptom" || kind == "flag" || kind == "context") {
            val prefix = tokens.take(start)
            if (prefix.any { it == "dulu" || it == "pernah" } &&
                prefix.none { it == "sekarang" || it == "masih" }) return true
            val suffix = tokens.drop(end)
            if (ABSENCE.any { sequence -> suffix.windowed(sequence.size).any { it == sequence } }) return true
            val resolved = suffix.indexOfFirst { it in DISAPPEARANCE }
            if (resolved >= 0 && suffix.take(resolved).none { it in NEGATIONS }) {
                val completed = suffix.take(resolved).any { it in setOf("sudah", "udah", "telah", "sekarang") }
                val conditional = suffix.drop(resolved + 1).any { it in setOf("dingin", "panas", "hangat") }
                if (completed || !conditional) return true
            }
        }
        return false
    }

    private fun clauses(text: String): List<Clause> {
        val source = text.lowercase(Locale.ROOT)
        val result = ArrayList<Clause>()
        var start = 0
        var inherited = false
        CLAUSE_BOUNDARY.findAll(source).forEach { boundary ->
            val tokens = normalizedTokens(source.substring(start, boundary.range.first))
            if (tokens.isNotEmpty()) result.add(Clause(tokens, inherited))
            inherited = boundary.value in setOf("dan", "serta") &&
                (inherited || tokens.any { it in NEGATIONS }) &&
                !containsIntrinsicBrakeChange(tokens)
            start = boundary.range.last + 1
        }
        val tail = normalizedTokens(source.substring(start))
        if (tail.isNotEmpty()) result.add(Clause(tail, inherited))
        return result
    }

    private fun containsIntrinsicBrakeChange(tokens: List<String>): Boolean =
        tokens.windowed(3).any { it == listOf("rem", "tidak", "pakem") || it == listOf("rem", "nggak", "pakem") ||
            it == listOf("rem", "gak", "pakem") }

    private fun malformed(text: String): Boolean {
        var index = 0
        while (index < text.length) {
            val character = text[index]
            if (Character.isHighSurrogate(character)) {
                if (index + 1 == text.length || !Character.isLowSurrogate(text[index + 1])) return true
                index++
            } else if (Character.isLowSurrogate(character) ||
                (Character.isISOControl(character) && character !in "\t\r\n")) return true
            index++
        }
        return false
    }

    private fun unknown(explanation: String): DescriptionMatch = DescriptionMatch(
        setOf("unknown"), emptySet(), null, null, emptySet(), listOf(explanation), true)
    private fun frozen(values: Set<String>): Set<String> = Collections.unmodifiableSet(LinkedHashSet(values))

    companion object {
        const val MAX_INPUT_CHARS = 4000
        private val TOKEN = Regex("[\\p{L}\\p{N}]+")
        private val CLAUSE_BOUNDARY = Regex("\\b(?:tapi|tetapi|namun|melainkan|sedangkan|dan|serta)\\b|[.,;:!?\\r\\n]+")
        private val NEGATIONS = setOf("tidak", "nggak", "enggak", "gak", "ga", "tak", "bukan", "tanpa", "belum")
        private val UNCERTAIN = setOf("mungkin", "sepertinya", "kayaknya", "entah", "atau")
        private val QUESTIONS = setOf("apakah", "adakah", "benarkah")
        private val SAFETY_FLAGS = setOf("heavy_engine_knock", "overheating", "brake_change",
            "steering_change", "wheel_play", "steam", "temperature_rising")
        private val DISAPPEARANCE = setOf("hilang", "lenyap", "berhenti")
        private val ABSENCE = listOf(
            listOf("tidak", "ada"), listOf("nggak", "ada"), listOf("gak", "ada"),
            listOf("tidak", "terdengar"), listOf("nggak", "terdengar"), listOf("gak", "terdengar"),
            listOf("tidak", "terjadi"), listOf("nggak", "terjadi"), listOf("gak", "terjadi"),
            listOf("tidak", "muncul"), listOf("nggak", "muncul"), listOf("gak", "muncul"),
            listOf("sudah", "diperbaiki"), listOf("sudah", "normal"), listOf("sudah", "beres"),
            listOf("sekarang", "tidak"), listOf("sekarang", "nggak"), listOf("sekarang", "gak"))
        private val SOUNDS = setOf("tek", "tik", "cit", "krek", "grek", "tok", "duk")
        private const val INVALID_INPUT = "Teks kosong, tidak valid, atau terlalu panjang. Tulis ulang keluhan singkat untuk ditinjau."
        private const val UNKNOWN_SOUND = "Sebagian bunyi belum dapat dipetakan. Jelaskan bunyi, lokasi, dan kapan muncul; bukan kepastian kerusakan."
        private const val CONTRADICTION = "Ada keterangan yang saling menyangkal. Konfirmasi gejala atau tanda bahaya yang masih terjadi."
        private const val TEMPERATURE_CONFLICT = "Keterangan suhu atau pola bunyi saling bertentangan. Perjelas apakah bunyi hanya saat dingin, mereda saat hangat, atau tetap ada."
        private const val LOCATION_CONFLICT = "Beberapa lokasi disebut bersama. Pilih lokasi bunyi yang ingin diperiksa."
        private const val SAFETY_CAUTION = "Tanda bahaya masih mungkin terjadi meski keterangannya belum pasti atau bertentangan. Sebagai pencegahan, berhenti di tempat aman dan minta bantuan; jangan anggap kendaraan aman sebelum diperiksa."
        private const val ENGINE_STALL_QUESTION = "Periksa lebih dahulu apakah mesin mati saat kemampuan rem dan kemudi berubah. Ini pertanyaan awal, bukan kepastian mesin mati atau penyebabnya; sistem bantuan berbeda antar kendaraan. Jangan mencoba menyalakan ulang saat situasi belum aman."

        internal fun normalizePhrase(text: String): String = normalizedTokens(text).joinToString(" ")

        private fun normalizedTokens(text: String): List<String> {
            val result = ArrayList<String>()
            TOKEN.findAll(text.lowercase(Locale.ROOT)).forEach { match ->
                val token = match.value
                val repeated = SOUNDS.firstOrNull { sound ->
                    token.length >= sound.length * 2 && token.length % sound.length == 0 &&
                        token.chunked(sound.length).all { it == sound }
                }
                if (repeated == null) result.add(token) else {
                    result.add(repeated)
                    result.add(repeated)
                }
            }
            // Onomatopoeia repetitions normalize to two, including hyphens/em-dashes/spaces.
            val collapsed = ArrayList<String>(result.size)
            result.forEach { token ->
                if (token !in SOUNDS || collapsed.size < 2 || collapsed.last() != token ||
                    collapsed[collapsed.lastIndex - 1] != token) collapsed.add(token)
            }
            return collapsed
        }
    }
}
