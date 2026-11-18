package com.fr3ts0n.ecu.gui.androbd.diagnosis.data;

import android.content.Context;
import android.net.Uri;
import android.util.AtomicFile;

import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledge;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledge.Cause;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledge.Complaint;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledge.Option;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledge.Question;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledge.Rule;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledge.Sound;
import com.fr3ts0n.ecu.gui.androbd.diagnosis.core.DiagnosisKnowledgeValidator;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Platform JSON codec and validated, offline extra-file imports. */
public final class DiagnosisKnowledgeLoader {
    private static final String ASSET = "diagnosis/knowledge-base.json";
    private static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_DEPTH = 64;
    private static final Pattern JSON_NUMBER = Pattern.compile(
            "-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");
    private static final Object FILE_LOCK = new Object();

    private final Context context;
    private volatile String warning = "";

    public DiagnosisKnowledgeLoader(Context context) {
        if (context == null) throw new IllegalArgumentException("knowledge.required_context");
        this.context = context;
    }

    public DiagnosisKnowledge load() throws IOException {
        synchronized (FILE_LOCK) {
            warning = "";
            DiagnosisKnowledge base = loadBundled();
            File external = context.getExternalFilesDir(null);
            if (external == null) {
                warning = "Penyimpanan tambahan tidak tersedia; basis pengetahuan bawaan digunakan.";
                return base;
            }
            File file = new File(external, "diagnosis/extra.json");
            if (!file.exists() && !new File(file.getPath() + ".bak").exists()) return base;
            try (InputStream input = new AtomicFile(file).openRead()) {
                return mergeExtra(base, readUtf8(input));
            } catch (IOException | SecurityException ex) {
                warning = "Berkas pengetahuan tambahan tidak dapat dibaca atau tidak valid; "
                        + "basis pengetahuan bawaan digunakan. Periksa lalu impor ulang berkas.";
                return base;
            }
        }
    }

    /** Empty on a clean load; callers must present nonempty warnings to the user. */
    public String warning() {
        return warning;
    }

    /** Copies a SAF-selected document; no persistent URI permission is required afterward. */
    public void importExtra(Uri uri) throws IOException {
        if (uri == null || !"content".equals(uri.getScheme())) {
            throw new IOException("Pilih berkas JSON melalui pemilih dokumen Android.");
        }
        synchronized (FILE_LOCK) {
            String text;
            try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("Berkas pilihan tidak dapat dibuka.");
                text = readUtf8(input);
            } catch (SecurityException ex) {
                throw new IOException("Izin membaca berkas pilihan tidak tersedia.", ex);
            }
            mergeExtra(loadBundled(), text);
            File external = context.getExternalFilesDir(null);
            if (external == null) throw new IOException("Penyimpanan tambahan tidak tersedia.");
            File directory = new File(external, "diagnosis");
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IOException("Folder pengetahuan tambahan tidak dapat dibuat.");
            }
            AtomicFile destination = new AtomicFile(new File(directory, "extra.json"));
            FileOutputStream output = null;
            try {
                output = destination.startWrite();
                output.write(text.getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
                destination.finishWrite(output);
                output = null;
                warning = "";
            } catch (IOException | SecurityException ex) {
                if (output != null) destination.failWrite(output);
                throw new IOException("Berkas tambahan tidak dapat disimpan; berkas lama dipertahankan.", ex);
            }
        }
    }

    /** Parses a complete, standalone snapshot, not an unresolved partial extra. */
    public static DiagnosisKnowledge parse(String json) throws IOException {
        return parseDocument(json, false);
    }

    public static String encode(DiagnosisKnowledge knowledge) {
        DiagnosisKnowledgeValidator.validate(knowledge);
        try {
            JSONObject root = new JSONObject();
            root.put("schema_version", knowledge.schemaVersion);
            root.put("revision", knowledge.revision);
            JSONArray complaints = new JSONArray();
            for (Complaint complaint : knowledge.complaints) {
                JSONObject entry = new JSONObject();
                entry.put("id", complaint.id);
                entry.put("category", complaint.category);
                entry.put("title", complaint.title);
                JSONArray questions = new JSONArray();
                for (Question question : complaint.questions) {
                    JSONObject questionJson = new JSONObject();
                    questionJson.put("id", question.id);
                    questionJson.put("text", question.text);
                    JSONArray options = new JSONArray();
                    for (Option option : question.options) {
                        JSONObject optionJson = new JSONObject();
                        optionJson.put("id", option.id);
                        optionJson.put("text", option.text);
                        options.put(optionJson);
                    }
                    questionJson.put("options", options);
                    questions.put(questionJson);
                }
                entry.put("questions", questions);
                JSONArray causes = new JSONArray();
                for (Cause cause : complaint.causes) causes.put(encodeCause(cause));
                entry.put("causes", causes);
                complaints.put(entry);
            }
            root.put("complaints", complaints);
            JSONArray sounds = new JSONArray();
            for (Sound sound : knowledge.sounds) {
                JSONObject entry = new JSONObject();
                entry.put("id", sound.id);
                entry.put("title", sound.title);
                entry.put("description", sound.description);
                entry.put("when", sound.when);
                entry.put("audio_file", sound.audioFile == null ? JSONObject.NULL : sound.audioFile);
                sounds.put(entry);
            }
            root.put("sounds", sounds);
            return root.toString();
        } catch (JSONException ex) {
            throw new IllegalArgumentException("knowledge.encoding", ex);
        }
    }

    private static JSONObject encodeCause(Cause cause) throws JSONException {
        JSONObject entry = new JSONObject();
        entry.put("id", cause.id);
        entry.put("name", cause.name);
        entry.put("signs", new JSONArray(cause.signs));
        entry.put("verification_steps", new JSONArray(cause.verificationSteps));
        entry.put("danger_level", cause.dangerLevel);
        entry.put("warning", cause.warning);
        JSONObject work = new JSONObject();
        work.put("kind", cause.workKind);
        work.put("description", cause.workDescription);
        entry.put("work_item", work);
        entry.put("base_weight", cause.baseWeight);
        JSONArray rules = new JSONArray();
        for (Rule rule : cause.scoreRules) {
            JSONObject ruleJson = new JSONObject();
            ruleJson.put("question_id", rule.questionId);
            ruleJson.put("option_id", rule.optionId);
            ruleJson.put("delta", rule.delta);
            ruleJson.put("reason", rule.reason);
            rules.put(ruleJson);
        }
        entry.put("score_rules", rules);
        entry.put("verified", cause.verified);
        entry.put("sound_ids", new JSONArray(cause.soundIds));
        return entry;
    }

    /** Reads only the APK asset, without observing or modifying the optional extra. */
    public DiagnosisKnowledge loadBundled() throws IOException {
        try (InputStream input = context.getAssets().open(ASSET)) {
            return parse(readUtf8(input));
        }
    }

    /** Validates an archived or selected extra against a base without touching storage. */
    public static DiagnosisKnowledge mergeExtra(DiagnosisKnowledge bundled, String extraJson)
            throws IOException {
        try {
            DiagnosisKnowledgeValidator.validate(bundled);
        } catch (IllegalArgumentException ex) {
            throw invalid(ex);
        }
        return merge(bundled, parseDocument(extraJson, true));
    }

    private static DiagnosisKnowledge merge(DiagnosisKnowledge base, DiagnosisKnowledge extra)
            throws IOException {
        Map<String, Complaint> complaints = new LinkedHashMap<>();
        for (Complaint complaint : base.complaints) complaints.put(complaint.id, complaint);
        for (Complaint complaint : extra.complaints) complaints.put(complaint.id, complaint);
        Map<String, Sound> sounds = new LinkedHashMap<>();
        for (Sound sound : base.sounds) sounds.put(sound.id, sound);
        for (Sound sound : extra.sounds) sounds.put(sound.id, sound);
        DiagnosisKnowledge merged = new DiagnosisKnowledge(1, base.revision + " + " + extra.revision,
                new ArrayList<>(complaints.values()), new ArrayList<>(sounds.values()));
        try {
            DiagnosisKnowledgeValidator.validate(merged);
        } catch (IllegalArgumentException ex) {
            throw invalid(ex);
        }
        return merged;
    }

    private static DiagnosisKnowledge parseDocument(String json, boolean extra) throws IOException {
        // A complete snapshot can contain the union of two bounded source files.
        int maximum = extra ? MAX_BYTES : 2 * MAX_BYTES;
        if (json == null || json.length() > maximum
                || json.getBytes(StandardCharsets.UTF_8).length > maximum) {
            throw new IOException("Isi pengetahuan kosong atau melebihi batas ukuran yang diizinkan.");
        }
        checkDepth(json);
        try {
            JSONTokener tokener = new JSONTokener(json);
            JSONObject root = object(tokener.nextValue());
            if (tokener.nextClean() != 0) throw new JSONException("knowledge.trailing_content");
            fields(root, "schema_version", "revision", "complaints", "sounds");
            int schema = integer(root, "schema_version");
            String revision = string(root, "revision");
            List<Complaint> complaints = new ArrayList<>();
            JSONArray complaintArray = array(root.get("complaints"));
            for (int i = 0; i < complaintArray.length(); i++) {
                complaints.add(parseComplaint(object(complaintArray.get(i))));
            }
            List<Sound> sounds = new ArrayList<>();
            JSONArray soundArray = array(root.get("sounds"));
            for (int i = 0; i < soundArray.length(); i++) {
                JSONObject sound = object(soundArray.get(i));
                fields(sound, "id", "title", "description", "when", "audio_file");
                String audioFile = sound.has("audio_file") && !sound.isNull("audio_file")
                        ? string(sound, "audio_file") : null;
                sounds.add(new Sound(string(sound, "id"), string(sound, "title"),
                        string(sound, "description"), string(sound, "when"), audioFile));
            }
            DiagnosisKnowledge result = new DiagnosisKnowledge(schema, revision, complaints, sounds);
            if (extra) DiagnosisKnowledgeValidator.validateExtra(result);
            else DiagnosisKnowledgeValidator.validate(result);
            return result;
        } catch (JSONException | IllegalArgumentException ex) {
            throw invalid(ex);
        }
    }

    private static Complaint parseComplaint(JSONObject source) throws JSONException {
        fields(source, "id", "category", "title", "questions", "causes");
        List<Question> questions = new ArrayList<>();
        JSONArray questionArray = array(source.get("questions"));
        for (int i = 0; i < questionArray.length(); i++) {
            JSONObject question = object(questionArray.get(i));
            fields(question, "id", "text", "options");
            List<Option> options = new ArrayList<>();
            JSONArray optionArray = array(question.get("options"));
            for (int j = 0; j < optionArray.length(); j++) {
                JSONObject option = object(optionArray.get(j));
                fields(option, "id", "text");
                options.add(new Option(string(option, "id"), string(option, "text")));
            }
            questions.add(new Question(string(question, "id"), string(question, "text"), options));
        }
        List<Cause> causes = new ArrayList<>();
        JSONArray causeArray = array(source.get("causes"));
        for (int i = 0; i < causeArray.length(); i++) causes.add(parseCause(object(causeArray.get(i))));
        return new Complaint(string(source, "id"), string(source, "category"),
                string(source, "title"), questions, causes);
    }

    private static Cause parseCause(JSONObject source) throws JSONException {
        fields(source, "id", "name", "signs", "verification_steps", "danger_level", "warning",
                "work_item", "base_weight", "score_rules", "verified", "sound_ids");
        JSONObject work = object(source.get("work_item"));
        fields(work, "kind", "description");
        List<Rule> rules = new ArrayList<>();
        JSONArray ruleArray = array(source.get("score_rules"));
        for (int i = 0; i < ruleArray.length(); i++) {
            JSONObject rule = object(ruleArray.get(i));
            fields(rule, "question_id", "option_id", "delta", "reason");
            rules.add(new Rule(string(rule, "question_id"), string(rule, "option_id"),
                    integer(rule, "delta"), string(rule, "reason")));
        }
        Object verified = source.get("verified");
        if (!(verified instanceof Boolean)) throw new JSONException("knowledge.boolean_type");
        return new Cause(string(source, "id"), string(source, "name"), strings(source, "signs"),
                strings(source, "verification_steps"), string(source, "danger_level"),
                source.has("warning") ? string(source, "warning") : "", string(work, "kind"),
                string(work, "description"), integer(source, "base_weight"), rules,
                (Boolean) verified, strings(source, "sound_ids"));
    }

    private static List<String> strings(JSONObject source, String key) throws JSONException {
        JSONArray values = array(source.get(key));
        List<String> result = new ArrayList<>();
        for (int i = 0; i < values.length(); i++) {
            Object value = values.get(i);
            if (!(value instanceof String)) throw new JSONException("knowledge.string_type");
            result.add((String) value);
        }
        return result;
    }

    private static String string(JSONObject source, String key) throws JSONException {
        Object value = source.get(key);
        if (!(value instanceof String)) throw new JSONException("knowledge.string_type");
        return (String) value;
    }

    private static int integer(JSONObject source, String key) throws JSONException {
        Object value = source.get(key);
        if (!(value instanceof Integer) && !(value instanceof Long)) {
            throw new JSONException("knowledge.integer_type");
        }
        long number = ((Number) value).longValue();
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new JSONException("knowledge.integer_range");
        }
        return (int) number;
    }

    private static JSONObject object(Object value) throws JSONException {
        if (!(value instanceof JSONObject)) throw new JSONException("knowledge.object_type");
        return (JSONObject) value;
    }

    private static JSONArray array(Object value) throws JSONException {
        if (!(value instanceof JSONArray)) throw new JSONException("knowledge.array_type");
        return (JSONArray) value;
    }

    private static void fields(JSONObject source, String... allowed) throws JSONException {
        Set<String> names = new HashSet<>(Arrays.asList(allowed));
        Iterator<String> keys = source.keys();
        while (keys.hasNext()) {
            if (!names.contains(keys.next())) throw new JSONException("knowledge.unknown_field");
        }
    }

    private static String readUtf8(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (bytes.size() > MAX_BYTES - count) {
                throw new IOException("Berkas pengetahuan melebihi batas 1 MiB.");
            }
            bytes.write(buffer, 0, count);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException ex) {
            throw new IOException("Berkas pengetahuan harus menggunakan UTF-8 yang valid.", ex);
        }
    }

    /** Bound nesting before JSONTokener recursively parses untrusted documents. */
    private static void checkDepth(String json) throws IOException {
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char current = json.charAt(i);
            if (quoted) {
                if (current < 0x20) throw new IOException("Teks JSON berisi karakter kontrol.");
                if (escaped) escaped = false;
                else if (current == '\\') escaped = true;
                else if (current == '"') quoted = false;
            } else if (current == '"') {
                quoted = true;
            } else if (current == '{' || current == '[') {
                if (++depth > MAX_DEPTH) throw new IOException("Susunan JSON terlalu dalam.");
            } else if (current == '}' || current == ']') {
                if (--depth < 0) throw new IOException("Susunan JSON tidak valid.");
            } else if (!Character.isWhitespace(current) && current != '\ufeff'
                    && current != ':' && current != ',') {
                int start = i;
                while (i < json.length() && !Character.isWhitespace(json.charAt(i))
                        && "{}[],:".indexOf(json.charAt(i)) == -1) i++;
                String literal = json.substring(start, i);
                if (!literal.equals("true") && !literal.equals("false") && !literal.equals("null")
                        && !JSON_NUMBER.matcher(literal).matches()) {
                    throw new IOException("Gunakan sintaks JSON dengan teks berkutip ganda.");
                }
                i--;
            }
        }
        if (depth != 0 || quoted) throw new IOException("Susunan JSON tidak lengkap.");
    }

    private static IOException invalid(Exception cause) {
        return new IOException("Berkas pengetahuan tidak valid; periksa skema, ID, dan rujukannya.", cause);
    }
}
