package com.fr3ts0n.ecu.gui.androbd.diagnosis.core;

import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledge.Cause;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledge.Complaint;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledge.Option;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledge.Question;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledge.Rule;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** Ranks possible causes using explicit answers and independent human check states. */
public final class DiagnosisScorer
{
    private static final int CHECK_WEIGHT = 100;
    private static final Comparator<RankedCause> RANK_ORDER = (left, right) -> {
        int scoreOrder = Integer.compare(right.score, left.score);
        return scoreOrder != 0 ? scoreOrder : left.cause.id.compareTo(right.cause.id);
    };

    private DiagnosisScorer()
    {
    }

    /**
     * Uses a complaint from validated knowledge. Missing answers and checks contribute nothing;
     * an unknown answer is an explicit option, never an implicit normal answer.
     *
     * @throws IllegalArgumentException if arguments are null, an answer references an unknown
     * question or option, or a check references an unknown cause or has a null status
     */
    public static List<RankedCause> rank(Complaint complaint, Map<String, String> answers,
                                         Map<String, CheckStatus> checks)
    {
        if (complaint == null || answers == null || checks == null)
        {
            throw new IllegalArgumentException("diagnosis.scoring.invalid_arguments");
        }
        validateAnswers(complaint, answers);
        validateChecks(complaint, checks);

        List<RankedCause> ranked = new ArrayList<>(complaint.causes.size());
        for (Cause cause : complaint.causes)
        {
            int score = cause.baseWeight;
            List<String> reasons = null;
            for (Rule rule : cause.scoreRules)
            {
                if (rule.optionId.equals(answers.get(rule.questionId)))
                {
                    score += rule.delta;
                    if (reasons == null)
                    {
                        reasons = new ArrayList<>();
                    }
                    reasons.add(rule.reason);
                }
            }
            CheckStatus status = checks.get(cause.id);
            if (status == CheckStatus.CONFIRMED)
            {
                score += CHECK_WEIGHT;
            }
            else if (status == CheckStatus.RULED_OUT)
            {
                score -= CHECK_WEIGHT;
            }
            ranked.add(new RankedCause(cause, score, reasons));
        }
        Collections.sort(ranked, RANK_ORDER);
        return Collections.unmodifiableList(ranked);
    }

    private static void validateAnswers(Complaint complaint, Map<String, String> answers)
    {
        for (Map.Entry<String, String> answer : answers.entrySet())
        {
            Question question = null;
            for (Question candidate : complaint.questions)
            {
                if (candidate.id.equals(answer.getKey()))
                {
                    question = candidate;
                    break;
                }
            }
            if (question == null)
            {
                throw new IllegalArgumentException("diagnosis.scoring.invalid_question_id");
            }
            boolean foundOption = false;
            for (Option option : question.options)
            {
                if (option.id.equals(answer.getValue()))
                {
                    foundOption = true;
                    break;
                }
            }
            if (!foundOption)
            {
                throw new IllegalArgumentException("diagnosis.scoring.invalid_option_id");
            }
        }
    }

    private static void validateChecks(Complaint complaint, Map<String, CheckStatus> checks)
    {
        for (Map.Entry<String, CheckStatus> check : checks.entrySet())
        {
            if (check.getKey() == null || complaint.findCause(check.getKey()) == null)
            {
                throw new IllegalArgumentException("diagnosis.scoring.invalid_cause_id");
            }
            if (check.getValue() == null)
            {
                throw new IllegalArgumentException("diagnosis.scoring.invalid_check_status");
            }
        }
    }

    public static final class RankedCause
    {
        public final Cause cause;
        public final int score;
        public final List<String> reasons;

        private RankedCause(Cause cause, int score, List<String> reasons)
        {
            this.cause = cause;
            this.score = score;
            this.reasons = reasons == null ? Collections.emptyList()
                    : Collections.unmodifiableList(reasons);
        }
    }
}
