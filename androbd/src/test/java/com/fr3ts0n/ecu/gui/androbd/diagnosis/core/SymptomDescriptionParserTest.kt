package com.fr3ts0n.ecu.gui.androbd.diagnosis.core

import com.fr3ts0n.ecu.gui.androbd.diagnosis.data.SymptomLexiconLoader
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SymptomDescriptionParserTest {
    private val parser = SymptomLexiconLoader.parse(resource("symptom-synonyms.json"))

    @Test fun exactlyTwentyDraftExamplesUseTheShippedLexicon() {
        val root = JSONObject(resource("description-examples.json"))
        assertEquals("DRAFT", root.getString("review_status"))
        assertEquals("needs_mechanic_verification", root.getString("mechanical_review_status"))
        assertEquals("user_not_mechanic", root.getString("reviewer_qualification"))
        val examples = root.getJSONArray("examples")
        assertEquals(20, examples.length())
        for (index in 0 until examples.length()) {
            val example = examples.getJSONObject(index)
            assertEquals(index + 1, example.getInt("id"))
            val text = example.getString("text")
            val actual = parser.parse(text)
            assertEquals(text, strings(example.getJSONArray("symptomIds")), actual.symptomIds)
            assertEquals(text, strings(example.getJSONArray("triggerConditions")), actual.triggerConditions)
            assertEquals(text, nullable(example, "engineTempState"), actual.engineTempState)
            assertEquals(text, nullable(example, "location"), actual.location)
            assertEquals(text, strings(example.getJSONArray("flags")), actual.flags)
            assertEquals(text, nullable(example, "temperaturePattern"), actual.temperaturePattern)
            assertEquals(text, strings(example.getJSONArray("followUpQuestions")), actual.followUpQuestions)
            assertEquals(text, example.getBoolean("needsClarification"), actual.needsClarification)
            assertTrue(text, actual.explanations.isNotEmpty())
        }
    }

    @Test fun independentNegationsNeverActivateFaultsOrSafetyFlags() {
        listOf(
            "Nggak ada ngelitik", "Gak tek-tek", "Tanpa ketukan mesin keras",
            "Bukan rem blong", "Tidak rem tidak pakem", "Tidak ada roda oblak",
            "Tidak ada setir mendadak berat", "Mesin tidak overheat",
            "Ngelitik sudah tidak ada", "Tek tek tidak terdengar", "Ngelitik sudah hilang",
            "Dulu ngelitik sekarang tidak", "Bukan tidak ngelitik", "Dulu ngelitik",
            "Pernah rem blong", "Ngelitik tidak terjadi", "Tek tek nggak muncul",
            "Ngelitik hilang setelah servis", "Roda oblak sudah diperbaiki"
        ).forEach { text ->
            val match = parser.parse(text)
            assertEquals(text, setOf("unknown"), match.symptomIds)
            assertTrue(text, match.flags.isEmpty())
            assertTrue(text, match.needsClarification)
        }
    }

    @Test fun negationCoversDisjunctionButStopsAtPositiveContrast() {
        assertEquals(setOf("unknown"), parser.parse("Tidak ngelitik atau tek tek").symptomIds)
        val match = parser.parse("Tidak overheat dan bukan ngelitik, namun tek-tek waktu dingin")
        assertEquals(setOf("valvetrain_tick"), match.symptomIds)
        assertEquals(setOf("cold"), match.triggerConditions)
        assertTrue(match.flags.isEmpty())
        assertFalse(match.needsClarification)
    }

    @Test fun multipleIndependentSoundsRemainMultipleSymptomsNotOneGuessedCause() {
        val match = parser.parse("Ngelitik saat digas dan tek tek saat langsam; roda belakang berdengung")
        assertEquals(setOf("detonation_pinging", "valvetrain_tick", "wheel_bearing_hum"), match.symptomIds)
        assertEquals(setOf("throttle", "idle"), match.triggerConditions)
        assertEquals("rear", match.location)
        assertFalse(match.needsClarification)
    }

    @Test fun ambiguousAndUnsupportedSoundsStayVisibleAlongsideKnownSymptoms() {
        listOf("Klotak dari roda", "Gredeg saat jalan", "Mungkin ngelitik",
            "Ngelitik atau tek tek", "Saya tidak tahu apakah ngelitik").forEach { text ->
            val match = parser.parse(text)
            assertEquals(text, setOf("unknown"), match.symptomIds)
            assertTrue(text, match.needsClarification)
        }
        val match = parser.parse("Ngelitik dan klotak")
        assertEquals(setOf("detonation_pinging", "unknown"), match.symptomIds)
        assertTrue(match.needsClarification)
    }

    @Test fun brakeSquealDoesNotBecomeGrindingEvenWithEngineMention() {
        listOf("Rem berdecit", "Rem mencicit saat mesin hidup", "Sabuk mesin cit cit saat ngerem")
            .forEach { text ->
                val match = parser.parse(text)
                assertFalse(text, "brake_grinding" in match.symptomIds)
                assertFalse(text, "belt_squeal" in match.symptomIds)
                assertFalse(text, match.needsClarification)
                assertEquals(text, setOf("brake_squeal"), match.symptomIds)
            }
        assertEquals(setOf("brake_grinding"), parser.parse("Gredeg kasar saat mengerem").symptomIds)
    }

    @Test fun requiredContextMustBePositiveAndInTheSameClause() {
        assertEquals(setOf("unknown"), parser.parse("Berdengung, roda tidak berbunyi").symptomIds)
        assertEquals(setOf("unknown"), parser.parse("Bukan roda yang berdengung").symptomIds)
        assertEquals(setOf("unknown"), parser.parse("Cit cit; mesin dingin").symptomIds)
        assertEquals(setOf("wheel_bearing_hum"), parser.parse("Roda berdengung").symptomIds)
    }

    @Test fun contradictionsRemoveDisputedFactsRatherThanChoosingTheLastSentence() {
        val match = parser.parse("Ngelitik. Bukan ngelitik. Tek tek")
        assertEquals(setOf("valvetrain_tick", "unknown"), match.symptomIds)
        assertTrue(match.needsClarification)
        val safety = parser.parse("Rem blong tetapi bukan rem blong")
        assertEquals(setOf("brake_change"), safety.flags)
        assertTrue(safety.needsClarification)
    }

    @Test fun contextNegationAndConflictsAreNotSilentlyResolved() {
        val warm = parser.parse("Tek tek bukan saat dingin tapi saat panas")
        assertEquals(setOf("warm"), warm.triggerConditions)
        assertEquals("warm", warm.engineTempState)
        assertFalse(warm.needsClarification)
        val places = parser.parse("Ngelitik di depan dan di belakang")
        assertEquals(null, places.location)
        assertTrue(places.needsClarification)
        assertEquals("front", parser.parse("Roda depan berdengung").location)
    }

    @Test fun thermalPatternsDoNotForceAChoiceOrInferFromColdAlone() {
        val fading = parser.parse("Tek tek saat dingin lalu hilang saat panas")
        assertEquals("fades_when_warm", fading.temperaturePattern)
        assertEquals(null, fading.engineTempState)
        assertEquals(setOf("cold", "warm"), fading.triggerConditions)
        assertFalse(fading.needsClarification)
        val coldOnly = parser.parse("Tek tek hanya saat dingin")
        assertEquals("cold_only", coldOnly.temperaturePattern)
        assertEquals("cold", coldOnly.engineTempState)
        assertFalse(coldOnly.needsClarification)
        assertEquals("cold_only", parser.parse("Tek tek saat dingin saja").temperaturePattern)
        assertEquals(null, parser.parse("Tek tek saat dingin").temperaturePattern)
        assertTrue(parser.parse("Tek tek saat dingin dan saat panas").needsClarification)
        val contradictory = parser.parse("Tek tek hilang saat panas tetapi tidak hilang saat panas")
        assertEquals(null, contradictory.temperaturePattern)
        assertTrue(contradictory.needsClarification)
        assertTrue(parser.parse("Tek tek hanya saat dingin tetapi juga saat panas").needsClarification)
    }

    @Test fun genericHissAsksSafetyQuestionsWithoutInferringVacuumLeak() {
        val match = parser.parse("Mendesis dari ruang mesin saat langsam")
        assertEquals(setOf("hiss"), match.symptomIds)
        assertEquals("under_hood", match.location)
        assertEquals(setOf("idle"), match.triggerConditions)
        assertEquals(listOf("steam", "sweet_smell", "temperature_rising"), match.followUpQuestions.toList())
        assertTrue(match.flags.isEmpty())
        assertTrue(match.needsClarification)
        assertEquals(setOf("hiss"), parser.parse("Selang vakum mendesis").symptomIds)
    }

    @Test fun combinedBrakeAndSteeringChangesAskAboutEngineStallFirstNotAsAFact() {
        val match = parser.parse("Mendesis. Rem blong dan setir mendadak berat")
        assertEquals(listOf("engine_stalled", "steam", "sweet_smell", "temperature_rising"),
            match.followUpQuestions.toList())
        assertFalse("engine_stalled" in match.flags)
        assertTrue(match.explanations.any { "bukan kepastian" in it && "mesin mati" in it })
        assertEquals(emptySet<String>(), parser.parse("Rem blong").followUpQuestions)
        assertEquals(setOf("engine_stalled"), parser.parse("Mesin mendadak mati").flags)
    }

    @Test fun tentativeOrContradictoryPositiveSafetyReportsKeepPrecautionFlags() {
        val reports = mapOf(
            "ketukan mesin keras" to "heavy_engine_knock", "mesin overheat" to "overheating",
            "rem blong" to "brake_change", "setir mendadak berat" to "steering_change",
            "roda oblak" to "wheel_play", "uap dari ruang mesin" to "steam",
            "suhu mesin naik" to "temperature_rising")
        reports.forEach { (report, flag) ->
            listOf("Mungkin $report", "$report tetapi bukan $report", "Bukan $report tetapi $report")
                .forEach { text ->
                    val match = parser.parse(text)
                    assertTrue(text, flag in match.flags)
                    assertTrue(text, match.needsClarification)
                    assertTrue(text, match.explanations.any { "pencegahan" in it })
                }
            listOf("Bukan $report", "Apakah $report", "Mungkin bukan $report",
                "Dulu $report", "$report sudah diperbaiki").forEach { text ->
                assertTrue(text, parser.parse(text).flags.isEmpty())
            }
        }
        val confirmed = parser.parse("Uap dari ruang mesin dan bau manis dan suhu mesin naik")
        assertEquals(setOf("steam", "sweet_smell", "temperature_rising"), confirmed.flags)
        assertFalse(confirmed.explanations.any { "kebocoran" in it })
    }

    @Test fun fadingIsNotInferredFromNegativeOrAlreadyResolvedNoise() {
        listOf("Tek tek tidak hilang saat panas", "Tek tek nggak hilang setelah mesin hangat",
            "Tek tek bukan hilang saat panas").forEach { text ->
            assertFalse(text, "fades_when_warm" in parser.parse(text).flags)
        }
        assertTrue("fades_when_warm" in parser.parse("Tek tek hilang saat panas").flags)
        assertEquals(setOf("unknown"), parser.parse("Ngelitik sudah hilang setelah servis").symptomIds)
    }

    @Test fun lexicalBoundariesAndRepeatNormalizationAreLocaleIndependent() {
        listOf("antiktek", "ngelitiknyaa", "overheated", "citric", "mengeremote").forEach { text ->
            val match = parser.parse(text)
            assertEquals(text, setOf("unknown"), match.symptomIds)
            assertTrue(text, match.flags.isEmpty())
        }
        val expected = parser.parse("tik tik saat dingin")
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            listOf("TIK-TIK-TIK SAAT DINGIN", "tiktik saat dingin", "tik—tik saat dingin",
                "tik tik tik tik saat dingin").forEach { assertEquals(it, expected, parser.parse(it)) }
        } finally {
            Locale.setDefault(original)
        }
        assertEquals(parser.parse("cit cit dari sabuk"), parser.parse("citcitcit dari sabuk"))
    }

    @Test fun emptyMalformedAndOversizedInputNeverYieldsAPartialDiagnosis() {
        listOf("", "   ", "🎵", "ngelitik\u0000rem blong", "ngelitik\uD800",
            "ngelitik " + "x".repeat(SymptomDescriptionParser.MAX_INPUT_CHARS)).forEach { text ->
            val match = parser.parse(text)
            assertEquals(setOf("unknown"), match.symptomIds)
            assertTrue(match.flags.isEmpty())
            assertTrue(match.needsClarification)
        }
        val boundary = "ngelitik".padEnd(SymptomDescriptionParser.MAX_INPUT_CHARS, ' ')
        assertEquals(setOf("detonation_pinging"), parser.parse(boundary).symptomIds)
    }

    @Test fun deterministicExplanationsContainNoRawTranscriptAndNoVehicleInference() {
        val text = "Mobil saya rahasiaunik123 ngelitik saat digas"
        val first = parser.parse(text)
        assertEquals(first, parser.parse(text))
        assertFalse(first.explanations.any { "rahasiaunik123" in it })
        assertTrue(first.explanations.any { "bukan" in it })
        assertEquals(null, first.engineTempState)
        assertEquals(null, first.location)
        assertTrue(first.flags.isEmpty())
        assertNotEquals(first.explanations, parser.parse("Klotak").explanations)
    }

    private fun resource(name: String): String = checkNotNull(javaClass.classLoader)
        .getResourceAsStream("diagnosis/$name")!!.bufferedReader().use { it.readText() }
    private fun strings(array: JSONArray): Set<String> = (0 until array.length()).map { array.getString(it) }.toSet()
    private fun nullable(source: JSONObject, field: String): String? =
        if (source.isNull(field)) null else source.getString(field)
}
