package com.fr3ts0n.ecu.gui.androbd.diagnosis.core;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledge.Cause;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledge.Complaint;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledge.Option;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledge.Question;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledge.Rule;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledge.Sound;

/** Structural validation only; passing does not establish mechanical accuracy. */
public final class DiagnosisKnowledgeValidator {
    private static final Set<String> CATEGORIES = new HashSet<>(Arrays.asList(
            "noise", "smoke", "starting", "power", "overheating", "leaks", "vibration",
            "electrical", "brakes_steering", "other"));
    private static final Set<String> DANGER_LEVELS = new HashSet<>(Arrays.asList(
            "LOW", "MEDIUM", "HIGH"));
    private static final Set<String> WORK_KINDS = new HashSet<>(Arrays.asList(
            "PART", "LABOR", "OTHER"));

    private DiagnosisKnowledgeValidator() { }

    public static void validate(DiagnosisKnowledge knowledge) {
        validate(knowledge, false);
    }

    /** Validate a source before merging; only root emptiness and missing sounds are deferred. */
    public static void validateExtra(DiagnosisKnowledge knowledge) {
        validate(knowledge, true);
    }

    private static void validate(DiagnosisKnowledge knowledge, boolean extra) {
        require(knowledge != null, "knowledge.required_root");
        require(knowledge.schemaVersion == 1, "knowledge.schema_version");
        text(knowledge.revision);
        require(extra || !knowledge.complaints.isEmpty(), "knowledge.required_complaints");
        Set<String> soundIds = new HashSet<>();
        for (Sound sound : knowledge.sounds) {
            require(sound != null, "knowledge.required_sound");
            unique(soundIds, sound.id, "knowledge.duplicate_sound_id");
            text(sound.title);
            text(sound.description);
            text(sound.when);
            if (sound.audioFile != null) validateAudioPath(sound.audioFile);
        }
        Set<String> complaintIds = new HashSet<>();
        Set<String> causeIds = new HashSet<>();
        for (Complaint complaint : knowledge.complaints) {
            require(complaint != null, "knowledge.required_complaint");
            unique(complaintIds, complaint.id, "knowledge.duplicate_complaint_id");
            require(CATEGORIES.contains(complaint.category), "knowledge.category");
            text(complaint.title);
            require(complaint.questions.size() >= 3 && complaint.questions.size() <= 6,
                    "knowledge.question_count");
            Map<String, Set<String>> optionsByQuestion = new HashMap<>();
            for (Question question : complaint.questions) {
                require(question != null, "knowledge.required_question");
                text(question.id);
                text(question.text);
                require(!optionsByQuestion.containsKey(question.id), "knowledge.duplicate_question_id");
                require(!question.options.isEmpty(), "knowledge.required_options");
                Set<String> options = new HashSet<>();
                for (Option option : question.options) {
                    require(option != null, "knowledge.required_option");
                    unique(options, option.id, "knowledge.duplicate_option_id");
                    text(option.text);
                }
                optionsByQuestion.put(question.id, options);
            }
            require(!complaint.causes.isEmpty(), "knowledge.required_causes");
            for (Cause cause : complaint.causes) {
                require(cause != null, "knowledge.required_cause");
                unique(causeIds, cause.id, "knowledge.duplicate_cause_id");
                text(cause.name);
                texts(cause.signs, true);
                texts(cause.verificationSteps, true);
                require(DANGER_LEVELS.contains(cause.dangerLevel), "knowledge.danger_level");
                require(cause.warning != null, "knowledge.required_warning");
                require(WORK_KINDS.contains(cause.workKind), "knowledge.work_kind");
                text(cause.workDescription);
                require(cause.baseWeight >= 0 && cause.baseWeight <= 10, "knowledge.base_weight");
                Map<String, Set<String>> matchedRules = new HashMap<>();
                for (Rule rule : cause.scoreRules) {
                    require(rule != null, "knowledge.required_rule");
                    text(rule.questionId);
                    text(rule.optionId);
                    Set<String> options = optionsByQuestion.get(rule.questionId);
                    require(options != null, "knowledge.rule_question_reference");
                    require(options.contains(rule.optionId), "knowledge.rule_option_reference");
                    Set<String> usedOptions = matchedRules.get(rule.questionId);
                    if (usedOptions == null) {
                        usedOptions = new HashSet<>();
                        matchedRules.put(rule.questionId, usedOptions);
                    }
                    require(usedOptions.add(rule.optionId), "knowledge.duplicate_rule");
                    require(rule.delta >= -5 && rule.delta <= 5, "knowledge.rule_delta");
                    text(rule.reason);
                }
                Set<String> references = new HashSet<>();
                for (String soundId : cause.soundIds) {
                    unique(references, soundId, "knowledge.duplicate_sound_reference");
                    require(extra || soundIds.contains(soundId), "knowledge.sound_reference");
                }
            }
        }
    }

    private static void validateAudioPath(String path) {
        text(path);
        require(path.equals(path.trim()) && !path.startsWith("/") && !path.contains("\\")
                && !path.contains(":") && !path.contains("%") && !path.contains("?")
                && !path.contains("#"), "knowledge.audio_path");
        for (int i = 0; i < path.length(); i++) {
            require(!Character.isISOControl(path.charAt(i)), "knowledge.audio_path");
        }
        for (String segment : path.split("/", -1)) {
            require(!segment.isEmpty() && !segment.equals(".") && !segment.equals(".."),
                    "knowledge.audio_path");
        }
    }

    private static void texts(List<String> values, boolean nonempty) {
        require(!nonempty || !values.isEmpty(), "knowledge.required_list");
        for (String value : values) text(value);
    }

    private static void unique(Set<String> ids, String id, String reason) {
        text(id);
        require(id.equals(id.trim()), "knowledge.id_whitespace");
        require(ids.add(id), reason);
    }

    private static void text(String value) {
        require(value != null && !value.trim().isEmpty(), "knowledge.required_text");
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }
}
