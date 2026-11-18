package com.fr3ts0n.ecu.gui.androbd.diagnosis.core;

import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledge.Cause;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledge.Complaint;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledge.Option;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledge.Question;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledge.Rule;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisScorer.RankedCause;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class DiagnosisScorerTest
{
    @Test public void matchingRulesAddSignedWeightsAndOnlyTheirExplanations()
    {
        Cause cause = cause("starting.supply", 4,
                new Rule("cranking", "slow", 5, "Putaran starter lambat."),
                new Rule("fuel", "normal", -3, "Aliran bahan bakar tidak menunjukkan gangguan."),
                new Rule("temperature", "cold", 0, "Pemeriksaan dilakukan saat mesin dingin."),
                new Rule("cranking", "normal", -5, "Putaran starter normal."));
        Map<String, String> answers = new HashMap<>();
        answers.put("temperature", "cold");
        answers.put("fuel", "normal");
        answers.put("cranking", "slow");

        RankedCause result = DiagnosisScorer.rank(complaint(cause), answers, noChecks()).get(0);

        assertSame(cause, result.cause);
        assertEquals(6, result.score);
        assertEquals(Arrays.asList("Putaran starter lambat.",
                "Aliran bahan bakar tidak menunjukkan gangguan.",
                "Pemeriksaan dilakukan saat mesin dingin."), result.reasons);
    }

    @Test public void negativeScoresAreNotClampedOrConvertedToConfidence()
    {
        Cause cause = cause("starting.supply", 1,
                new Rule("cranking", "normal", -5, "Putaran starter normal."));
        RankedCause result = DiagnosisScorer.rank(complaint(cause),
                Collections.singletonMap("cranking", "normal"), noChecks()).get(0);

        assertEquals(-4, result.score);
        assertEquals(Collections.singletonList("Putaran starter normal."), result.reasons);
    }

    @Test public void unknownAndUnansweredAreNotTreatedAsNormal()
    {
        Complaint complaint = complaint(cause("starting.supply", 5,
                new Rule("cranking", "normal", -4, "Putaran starter normal."),
                new Rule("cranking", "slow", 4, "Putaran starter lambat.")));

        RankedCause unanswered = DiagnosisScorer.rank(complaint, noAnswers(), noChecks()).get(0);
        RankedCause unknown = DiagnosisScorer.rank(complaint,
                Collections.singletonMap("cranking", "unknown"), noChecks()).get(0);
        RankedCause normal = DiagnosisScorer.rank(complaint,
                Collections.singletonMap("cranking", "normal"), noChecks()).get(0);

        assertEquals(5, unanswered.score);
        assertTrue(unanswered.reasons.isEmpty());
        assertEquals(5, unknown.score);
        assertTrue(unknown.reasons.isEmpty());
        assertEquals(1, normal.score);
        assertEquals(Collections.singletonList("Putaran starter normal."), normal.reasons);
    }

    @Test public void humanConfirmationPromotesAndRuleOutDemotesWithoutAddingReasons()
    {
        Cause low = cause("starting.low", 0,
                new Rule("cranking", "slow", 3, "Putaran starter lambat."));
        Cause middle = cause("starting.middle", 5);
        Cause high = cause("starting.high", 10);
        Map<String, CheckStatus> checks = new HashMap<>();
        checks.put(low.id, CheckStatus.CONFIRMED);
        checks.put(middle.id, CheckStatus.NOT_TESTED);
        checks.put(high.id, CheckStatus.RULED_OUT);

        List<RankedCause> ranked = DiagnosisScorer.rank(complaint(high, middle, low),
                Collections.singletonMap("cranking", "slow"), checks);

        assertSame(low, ranked.get(0).cause);
        assertEquals(103, ranked.get(0).score);
        assertEquals(Collections.singletonList("Putaran starter lambat."), ranked.get(0).reasons);
        assertSame(middle, ranked.get(1).cause);
        assertEquals(5, ranked.get(1).score);
        assertTrue(ranked.get(1).reasons.isEmpty());
        assertSame(high, ranked.get(2).cause);
        assertEquals(-90, ranked.get(2).score);
        assertTrue(ranked.get(2).reasons.isEmpty());
        assertFalse(low.verified);
    }

    @Test public void knowledgeReviewFlagDoesNotCountAsHumanConfirmation()
    {
        Cause reviewed = new Cause("starting.reviewed", "Pasokan daya starter",
                Collections.singletonList("Putaran starter lambat."),
                Collections.singletonList("Periksa sambungan sesuai manual servis."),
                "LOW", "", "LABOR", "Pemeriksaan pasokan daya", 2,
                Collections.emptyList(), true, Collections.emptyList());

        RankedCause result = DiagnosisScorer.rank(complaint(reviewed), noAnswers(), noChecks()).get(0);

        assertEquals(2, result.score);
        assertTrue(result.reasons.isEmpty());
        assertTrue(reviewed.verified);
    }

    @Test public void equalScoresSortByIdRegardlessOfCauseOrAnswerOrder()
    {
        Cause first = cause("starting.a", 3,
                new Rule("cranking", "slow", 2, "Putaran starter lambat."));
        Cause second = cause("starting.b", 5);
        Cause last = cause("starting.c", 6,
                new Rule("fuel", "normal", -1, "Aliran bahan bakar normal."));
        Map<String, String> forward = new java.util.LinkedHashMap<>();
        forward.put("cranking", "slow");
        forward.put("fuel", "normal");
        Map<String, String> reverse = new java.util.LinkedHashMap<>();
        reverse.put("fuel", "normal");
        reverse.put("cranking", "slow");

        List<RankedCause> a = DiagnosisScorer.rank(complaint(last, second, first), forward, noChecks());
        List<RankedCause> b = DiagnosisScorer.rank(complaint(second, first, last), reverse, noChecks());

        for (int i = 0; i < 3; i++)
        {
            assertEquals("starting." + (char) ('a' + i), a.get(i).cause.id);
            assertEquals(5, a.get(i).score);
            assertSame(a.get(i).cause, b.get(i).cause);
            assertEquals(a.get(i).score, b.get(i).score);
            assertEquals(a.get(i).reasons, b.get(i).reasons);
        }
    }

    @Test public void rejectsUnknownQuestionAndOptionsIncludingOptionFromAnotherQuestion()
    {
        Complaint complaint = complaint(cause("starting.supply", 5));

        assertInvalid("diagnosis.scoring.invalid_question_id", complaint,
                Collections.singletonMap("missing", "normal"), noChecks());
        assertInvalid("diagnosis.scoring.invalid_option_id", complaint,
                Collections.singletonMap("cranking", "missing"), noChecks());
        assertInvalid("diagnosis.scoring.invalid_option_id", complaint,
                Collections.singletonMap("cranking", "air"), noChecks());
    }

    @Test public void rejectsChecksOutsideSelectedComplaint()
    {
        Complaint complaint = complaint(cause("starting.supply", 5));

        assertInvalid("diagnosis.scoring.invalid_cause_id", complaint, noAnswers(),
                Collections.singletonMap("other.supply", CheckStatus.CONFIRMED));
        assertInvalid("diagnosis.scoring.invalid_cause_id", complaint, noAnswers(),
                Collections.singletonMap("missing", CheckStatus.RULED_OUT));
    }

    @Test public void rejectsNullIdsAnswersAndCheckStatuses()
    {
        Complaint complaint = complaint(cause("starting.supply", 5));

        assertInvalid("diagnosis.scoring.invalid_question_id", complaint,
                Collections.singletonMap(null, "normal"), noChecks());
        assertInvalid("diagnosis.scoring.invalid_option_id", complaint,
                Collections.singletonMap("cranking", null), noChecks());
        assertInvalid("diagnosis.scoring.invalid_cause_id", complaint, noAnswers(),
                Collections.singletonMap(null, CheckStatus.CONFIRMED));
        assertInvalid("diagnosis.scoring.invalid_check_status", complaint, noAnswers(),
                Collections.singletonMap("starting.supply", null));
    }

    @Test public void rejectsNullArgumentsInsteadOfSilentlyDiscardingProgress()
    {
        Complaint complaint = complaint(cause("starting.supply", 5));

        assertInvalid("diagnosis.scoring.invalid_arguments", null, noAnswers(), noChecks());
        assertInvalid("diagnosis.scoring.invalid_arguments", complaint, null, noChecks());
        assertInvalid("diagnosis.scoring.invalid_arguments", complaint, noAnswers(), null);
    }

    @Test public void resultsAreReadOnlyAndInputsAreNotReordered()
    {
        Cause last = cause("starting.z", 5);
        Cause first = cause("starting.a", 5,
                new Rule("cranking", "slow", 0, "Putaran starter lambat."));
        Complaint complaint = complaint(last, first);
        List<RankedCause> ranked = DiagnosisScorer.rank(complaint,
                Collections.singletonMap("cranking", "slow"), noChecks());

        assertSame(last, complaint.causes.get(0));
        assertSame(first, ranked.get(0).cause);
        assertThrows(UnsupportedOperationException.class, ranked::clear);
        assertThrows(UnsupportedOperationException.class, ranked.get(0).reasons::clear);
    }

    private static void assertInvalid(String reason, Complaint complaint,
                                      Map<String, String> answers, Map<String, CheckStatus> checks)
    {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> DiagnosisScorer.rank(complaint, answers, checks));
        assertEquals(reason, error.getMessage());
    }

    private static Complaint complaint(Cause... causes)
    {
        return new Complaint("starting", "starting", "Mesin sulit dihidupkan",
                Arrays.asList(
                        question("cranking", "Bagaimana putaran starter?", "slow", "Lambat"),
                        question("fuel", "Bagaimana aliran bahan bakar?", "air", "Terdapat udara"),
                        question("temperature", "Bagaimana suhu mesin?", "cold", "Dingin")),
                Arrays.asList(causes));
    }

    private static Question question(String id, String text, String optionId, String optionText)
    {
        return new Question(id, text, Arrays.asList(
                new Option("normal", "Normal"),
                new Option(optionId, optionText),
                new Option("unknown", "Belum diperiksa")));
    }

    private static Cause cause(String id, int baseWeight, Rule... rules)
    {
        return new Cause(id, "Pasokan saat menghidupkan mesin",
                Collections.singletonList("Mesin sulit dihidupkan."),
                Collections.singletonList("Periksa sesuai manual servis."),
                "LOW", "", "LABOR", "Pemeriksaan awal", baseWeight,
                Arrays.asList(rules), false, Collections.emptyList());
    }

    private static Map<String, String> noAnswers()
    {
        return Collections.emptyMap();
    }

    private static Map<String, CheckStatus> noChecks()
    {
        return Collections.emptyMap();
    }
}
