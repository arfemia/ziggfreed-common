package com.ziggfreed.common.track.source;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bson.BsonArray;
import org.bson.BsonDocument;
import org.bson.BsonString;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.ziggfreed.common.loot.diminish.DiminishSpec;
import com.ziggfreed.common.owner.OwnerFileBinding;
import com.ziggfreed.common.util.OwnerFiles;

/**
 * The track sources' owner form ({@code Server/ZiggfreedCommon/OwnerForms/Track_Sources.json}, an Entries form over
 * {@code mods/ziggfreedcommon/track-sources.json}, a row per source): the file's binding. A source's rows sit in a
 * map keyed by name pattern and its diminishing steps in a list, which a form's dotted paths cannot reach, so the
 * binding lifts both to plain lists: {@link #ROW_LIST}, one word per row, {@code pattern=xp} or
 * {@code pattern=xp@level} (the level the row's soft gate waits for), and {@link #STEP_LIST}, the steps in order. Its
 * writer turns them back: a row word writes that row whole, its other leaves (PerAmount, PerMaxHealth, Max) kept as
 * the row reads now, and only the rows that read otherwise than without the owner's word are written, so the rest keep
 * following the packs; a step list writes {@code Diminish.Steps} whole. A Reset takes the owner's rows or steps out.
 * Every other path is the source's own leaf, read and written through the store as it is.
 */
public final class TrackSourceForm {

    /** The binding's id: what the form's {@code File} names. */
    public static final String BINDING = "ziggfreedcommon/" + TrackSourceOwnerLayers.FILE;

    /** A source's rows as one list of words, as the form names it. */
    public static final String ROW_LIST = "RowList";

    /** A source's diminishing steps as one list, as the form names it. */
    public static final String STEP_LIST = "StepList";

    private static final String ROWS = "Rows";
    private static final String DIMINISH = "Diminish";
    private static final String STEPS = "Steps";
    private static final String DIMINISH_STEPS = DIMINISH + "." + STEPS;

    private TrackSourceForm() {
    }

    /** The binding of {@code track-sources.json}, edited one source at a time. */
    @Nonnull
    public static OwnerFileBinding binding() {
        TrackSourceConfig config = TrackSourceConfig.getInstance();
        OwnerFileBinding.Entries store = OwnerFileBinding.entriesOf(config, TrackSourceAsset.CODEC);
        OwnerFileBinding.Below plain = OwnerFileBinding.belowFrom(config, TrackSourceAsset.CODEC);
        return OwnerFileBinding.builder(BINDING, TrackSourceForm::file)
                .below((entry, path) -> below(entry, path, plain))
                .entries(new OwnerFileBinding.Entries() {
                    @Override
                    @Nonnull
                    public List<String> ids() {
                        return store.ids();
                    }

                    @Override
                    public boolean hasBelow(@Nonnull String id) {
                        return store.hasBelow(id);
                    }

                    @Override
                    @Nullable
                    public BsonDocument resolved(@Nonnull String id) {
                        BsonDocument doc = store.resolved(id);
                        TrackSourceAsset source = config.resolve(id.trim());
                        if (doc != null && source != null) {
                            doc.put(ROW_LIST, strings(rowWords(source.rows())));
                            doc.put(STEP_LIST, strings(stepWords(source.diminish())));
                        }
                        return doc;
                    }

                    @Override
                    public boolean accepts(@Nullable String id) {
                        return store.accepts(id);
                    }

                    @Override
                    public boolean put(@Nonnull Path file, @Nonnull String id, @Nonnull Map<String, Object> leaves) {
                        return store.put(file, id, written(config, id, leaves));
                    }

                    @Override
                    public boolean remove(@Nonnull Path file, @Nonnull String id) {
                        return store.remove(file, id);
                    }
                })
                .view(TrackSourceForm::view)
                .reload(TrackSourceOwnerLayers::reload)
                .build();
    }

    /** Where the owner's file lives right now. */
    @Nonnull
    static Path file() {
        return TrackSourceOwnerLayers.file();
    }

    /** A leaf without the owner's word: the lifted lists read off the source under the owner, else the plain leaf. */
    @Nullable
    static Object below(@Nullable String entry, @Nonnull String path, @Nonnull OwnerFileBinding.Below plain) {
        if (entry == null || entry.isBlank()) {
            return null;
        }
        if (ROW_LIST.equals(path) || STEP_LIST.equals(path)) {
            TrackSourceAsset source = TrackSourceConfig.getInstance().resolveBelowOwner(entry.trim());
            if (source == null) {
                return null;
            }
            return ROW_LIST.equals(path) ? rowWords(source.rows()) : stepWords(source.diminish());
        }
        return plain.read(entry, path);
    }

    /** The owner's file as the form reads it: each entry's own rows and steps lifted into the two lists. */
    @Nonnull
    static JsonObject view(@Nonnull JsonObject root) {
        for (Map.Entry<String, JsonElement> source : root.entrySet()) {
            if (OwnerFiles.isReservedKey(source.getKey()) || !source.getValue().isJsonObject()) {
                continue;
            }
            JsonObject entry = source.getValue().getAsJsonObject();
            JsonElement rows = entry.get(ROWS);
            if (rows != null && rows.isJsonObject()) {
                JsonArray words = new JsonArray();
                for (Map.Entry<String, JsonElement> row : rows.getAsJsonObject().entrySet()) {
                    String word = rowWord(row.getKey(), row.getValue());
                    if (word != null) {
                        words.add(word);
                    }
                }
                entry.add(ROW_LIST, words);
            }
            JsonElement diminish = entry.get(DIMINISH);
            JsonElement steps = diminish != null && diminish.isJsonObject()
                    ? diminish.getAsJsonObject().get(STEPS) : null;
            if (steps != null && steps.isJsonArray()) {
                JsonArray words = new JsonArray();
                for (JsonElement step : steps.getAsJsonArray()) {
                    if (step != null && step.isJsonPrimitive() && step.getAsJsonPrimitive().isNumber()) {
                        words.add(number(step.getAsDouble()));
                    }
                }
                entry.add(STEP_LIST, words);
            }
        }
        return root;
    }

    /**
     * {@code leaves} as the store's writer takes them: {@link #ROW_LIST} turned into the rows that read otherwise than
     * without the owner's word, written whole ({@code Rows}, or taken out when none differs), {@link #STEP_LIST} into
     * {@code Diminish.Steps}; a lifted list given as null (a Reset) takes its leaf out. Every other leaf as given.
     */
    @Nonnull
    static Map<String, Object> written(@Nonnull TrackSourceConfig config, @Nonnull String id,
            @Nonnull Map<String, Object> leaves) {
        Map<String, Object> out = new LinkedHashMap<>(leaves);
        if (out.containsKey(ROW_LIST)) {
            Object words = out.remove(ROW_LIST);
            TrackSourceAsset inForce = config.resolve(id.trim());
            TrackSourceAsset below = config.resolveBelowOwner(id.trim());
            Map<String, Object> rows = words == null ? Map.of()
                    : changedRows(parseRows(wordsOf(words)), inForce == null ? Map.of() : inForce.rows(),
                            below == null ? Map.of() : below.rows());
            out.put(ROWS, rows.isEmpty() ? null : rows);
        }
        if (out.containsKey(STEP_LIST)) {
            Object words = out.remove(STEP_LIST);
            List<Double> steps = words == null ? List.of() : parseSteps(wordsOf(words));
            out.put(DIMINISH_STEPS, steps.isEmpty() ? null : new ArrayList<Object>(steps));
        }
        return out;
    }

    /** One row as a word: {@code pattern=xp}, or {@code pattern=xp@level} when it waits for a level. Pure. */
    @Nonnull
    static String rowWord(@Nonnull String pattern, @Nonnull SourceRow row) {
        return pattern + "=" + number(row.xp()) + (row.level() > 1 ? "@" + row.level() : "");
    }

    /** A row as the owner's file writes it (a bare number, or a group), as a word; null for another shape. */
    @Nullable
    private static String rowWord(@Nonnull String pattern, @Nullable JsonElement value) {
        if (value == null) {
            return null;
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            return pattern + "=" + number(value.getAsDouble());
        }
        if (!value.isJsonObject()) {
            return null;
        }
        JsonObject row = value.getAsJsonObject();
        double xp = row.has("Xp") && row.get("Xp").isJsonPrimitive() ? row.get("Xp").getAsDouble() : 0.0;
        int level = row.has("Level") && row.get("Level").isJsonPrimitive() ? row.get("Level").getAsInt() : 0;
        return pattern + "=" + number(xp) + (level > 1 ? "@" + level : "");
    }

    /** Every row as a word, in the source's own order. Pure. */
    @Nonnull
    static List<String> rowWords(@Nonnull Map<String, SourceRow> rows) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, SourceRow> row : rows.entrySet()) {
            if (row.getKey() != null && row.getValue() != null) {
                out.add(rowWord(row.getKey(), row.getValue()));
            }
        }
        return out;
    }

    /** The steps as words, in order; empty for no diminishing returns. Pure. */
    @Nonnull
    static List<String> stepWords(@Nullable DiminishSpec spec) {
        List<String> out = new ArrayList<>();
        if (spec != null) {
            for (double step : spec.steps()) {
                out.add(number(step));
            }
        }
        return out;
    }

    /** A row word parsed: its pattern, its Xp and its Level (0 for none). */
    record RowWord(@Nonnull String pattern, double xp, int level) {
    }

    /**
     * The words as rows: each split at its LAST {@code =} (a set key such as {@code ItemTag:Family=Sword} holds one
     * itself), the right side an Xp with an optional {@code @level}; a word that does not read is passed over. Pure.
     */
    @Nonnull
    static List<RowWord> parseRows(@Nonnull List<String> words) {
        List<RowWord> out = new ArrayList<>();
        for (String raw : words) {
            String word = raw == null ? "" : raw.trim();
            int eq = word.lastIndexOf('=');
            if (eq <= 0 || eq == word.length() - 1) {
                continue;
            }
            String pattern = word.substring(0, eq).trim();
            String value = word.substring(eq + 1).trim();
            int at = value.indexOf('@');
            Double xp = parse(at < 0 ? value : value.substring(0, at));
            Double level = at < 0 ? Double.valueOf(0.0) : parse(value.substring(at + 1));
            if (pattern.isEmpty() || xp == null || xp < 0.0 || level == null || level < 0.0) {
                continue;
            }
            out.add(new RowWord(pattern, xp, (int) Math.floor(level)));
        }
        return out;
    }

    /** The words as steps: each a number of 0 or more, in order; one that does not read is passed over. Pure. */
    @Nonnull
    static List<Double> parseSteps(@Nonnull List<String> words) {
        List<Double> out = new ArrayList<>();
        for (String word : words) {
            Double step = word == null ? null : parse(word.trim());
            if (step != null && step >= 0.0) {
                out.add(step);
            }
        }
        return out;
    }

    /**
     * The rows to write: each parsed row whose Xp or Level reads otherwise than the row without the owner's word
     * ({@code below}, matched without regard to case), written whole: its other leaves as the row in force reads
     * them ({@code inForce}). Pure.
     */
    @Nonnull
    static Map<String, Object> changedRows(@Nonnull List<RowWord> parsed, @Nonnull Map<String, SourceRow> inForce,
            @Nonnull Map<String, SourceRow> below) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (RowWord word : parsed) {
            SourceRow under = find(below, word.pattern());
            if (under != null && Double.compare(under.xp(), word.xp()) == 0 && under.level() == word.level()) {
                continue;
            }
            SourceRow now = find(inForce, word.pattern());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("Xp", word.xp());
            if (now != null && now.perAmount() > 0.0) {
                row.put("PerAmount", now.perAmount());
            }
            if (now != null && now.perMaxHealth() > 0.0) {
                row.put("PerMaxHealth", now.perMaxHealth());
            }
            if (now != null && now.max() > 0.0) {
                row.put("Max", now.max());
            }
            if (word.level() > 0) {
                row.put("Level", word.level());
            }
            out.put(word.pattern(), row);
        }
        return out;
    }

    @Nullable
    private static SourceRow find(@Nonnull Map<String, SourceRow> rows, @Nonnull String pattern) {
        SourceRow exact = rows.get(pattern);
        if (exact != null) {
            return exact;
        }
        String folded = pattern.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, SourceRow> row : rows.entrySet()) {
            if (row.getKey() != null && row.getKey().toLowerCase(Locale.ROOT).equals(folded)) {
                return row.getValue();
            }
        }
        return null;
    }

    @Nonnull
    private static List<String> wordsOf(@Nonnull Object value) {
        List<String> out = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
        } else {
            for (String part : String.valueOf(value).split(",")) {
                out.add(part);
            }
        }
        return out;
    }

    @Nullable
    private static Double parse(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            double value = Double.parseDouble(text.trim());
            return Double.isFinite(value) ? value : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** A number as a word: whole without a decimal point, else as it is. */
    @Nonnull
    static String number(double value) {
        return value == Math.rint(value) && Math.abs(value) < 1e15 ? Long.toString((long) value)
                : Double.toString(value);
    }

    @Nonnull
    private static BsonArray strings(@Nonnull List<String> words) {
        BsonArray out = new BsonArray();
        for (String word : words) {
            out.add(new BsonString(word));
        }
        return out;
    }
}
