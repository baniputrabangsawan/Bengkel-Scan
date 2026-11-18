package com.fr3ts0n.ecu.gui.androbd.diagnosis.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable knowledge snapshot. Review status is not a mechanic's case finding. */
public final class DiagnosisKnowledge {
    public final int schemaVersion;
    public final String revision;
    public final List<Complaint> complaints;
    public final List<Sound> sounds;

    public DiagnosisKnowledge(int schemaVersion, String revision, List<Complaint> complaints,
                              List<Sound> sounds) {
        this.schemaVersion = schemaVersion;
        this.revision = revision;
        this.complaints = immutable(complaints);
        this.sounds = immutable(sounds);
    }

    public Complaint findComplaint(String id) {
        for (Complaint complaint : complaints) if (complaint.id.equals(id)) return complaint;
        return null;
    }

    public Cause findCause(String id) {
        for (Complaint complaint : complaints) {
            Cause cause = complaint.findCause(id);
            if (cause != null) return cause;
        }
        return null;
    }

    public Sound findSound(String id) {
        for (Sound sound : sounds) if (sound.id.equals(id)) return sound;
        return null;
    }

    private static <T> List<T> immutable(List<T> values) {
        if (values == null) throw new IllegalArgumentException("knowledge.required_list");
        return Collections.unmodifiableList(new ArrayList<>(values));
    }

    public static final class Complaint {
        public final String id;
        public final String category;
        public final String title;
        public final List<Question> questions;
        public final List<Cause> causes;

        public Complaint(String id, String category, String title, List<Question> questions,
                         List<Cause> causes) {
            this.id = id;
            this.category = category;
            this.title = title;
            this.questions = immutable(questions);
            this.causes = immutable(causes);
        }

        public Cause findCause(String id) {
            for (Cause cause : causes) if (cause.id.equals(id)) return cause;
            return null;
        }
    }

    public static final class Question {
        public final String id;
        public final String text;
        public final List<Option> options;

        public Question(String id, String text, List<Option> options) {
            this.id = id;
            this.text = text;
            this.options = immutable(options);
        }
    }

    public static final class Option {
        public final String id;
        public final String text;

        public Option(String id, String text) {
            this.id = id;
            this.text = text;
        }
    }

    public static final class Cause {
        public final String id;
        public final String name;
        public final List<String> signs;
        public final List<String> verificationSteps;
        public final String dangerLevel;
        public final String warning;
        public final String workKind;
        public final String workDescription;
        public final int baseWeight;
        public final List<Rule> scoreRules;
        public final boolean verified;
        public final List<String> soundIds;

        public Cause(String id, String name, List<String> signs, List<String> verificationSteps,
                     String dangerLevel, String warning, String workKind, String workDescription,
                     int baseWeight, List<Rule> scoreRules, boolean verified, List<String> soundIds) {
            this.id = id;
            this.name = name;
            this.signs = immutable(signs);
            this.verificationSteps = immutable(verificationSteps);
            this.dangerLevel = dangerLevel;
            this.warning = warning;
            this.workKind = workKind;
            this.workDescription = workDescription;
            this.baseWeight = baseWeight;
            this.scoreRules = immutable(scoreRules);
            this.verified = verified;
            this.soundIds = immutable(soundIds);
        }
    }

    public static final class Rule {
        public final String questionId;
        public final String optionId;
        public final int delta;
        public final String reason;

        public Rule(String questionId, String optionId, int delta, String reason) {
            this.questionId = questionId;
            this.optionId = optionId;
            this.delta = delta;
            this.reason = reason;
        }
    }

    public static final class Sound {
        public final String id;
        public final String title;
        public final String description;
        public final String when;
        public final String audioFile;

        public Sound(String id, String title, String description, String when, String audioFile) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.when = when;
            this.audioFile = audioFile;
        }
    }
}
