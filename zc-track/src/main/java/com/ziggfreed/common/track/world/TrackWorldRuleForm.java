package com.ziggfreed.common.track.world;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.bson.BsonDocument;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.ziggfreed.common.owner.OwnerFileBinding;
import com.ziggfreed.common.util.OwnerFiles;

/**
 * The world rules' owner form ({@code Server/ZiggfreedCommon/OwnerForms/Track_World_Rules.json}, an Entries form over
 * {@code mods/ziggfreedcommon/track-world-rules.json}, a row per rule in force): the file's binding, and the audit the
 * form counts ({@link #AUDIT}, the track audit's world rules part).
 *
 * <p>The binding is the keyed store's, with two things a plain leaf-by-leaf writer would get wrong on this file:
 * <ul>
 *   <li><b>The grant switches stand whole.</b> {@code Rules.Grants.Kinds} is a map an owner entry writes whole: the
 *       entry's map replaces the rule file's, so one switch written alone would turn every other switch the file
 *       writes back to on. The writer ({@link #wholeKinds}) writes the map as it reads now with the owner's change in
 *       it, and a Reset puts the rule file's value back for that switch; when nothing is left that differs from the
 *       rule file, the owner's map comes out whole. The view ({@link #view}) shows an owner's map as it applies: a
 *       switch the rule file writes and the owner's map leaves out reads on, as it does in force.</li>
 *   <li><b>What a switch reads as unwritten.</b> A rule leaf written nowhere reads as its codec's default (a rule and
 *       its groups on, a multiplier of 1, hits on players paying nothing, every grant switch on), which the form shows
 *       as its Default, rather than no value; a rule only the owner's file holds has no Default at all.</li>
 * </ul>
 * Which worlds a rule covers, and every new rule, stay in the files; the whole file's {@code $Enabled} switch too.
 */
public final class TrackWorldRuleForm {

    /** The binding's id: what the form's {@code File} names. */
    public static final String BINDING = "ziggfreedcommon/track-world-rules.json";

    /** The id the world rules' audit is registered under for the form to count. */
    public static final String AUDIT = "ziggfreedcommon:track_world_rules";

    /** The grant switches' map inside one rule. */
    static final String KINDS = "Rules.Grants.Kinds";

    private static final String KINDS_DOT = KINDS + ".";

    /** What a rule leaf reads as where nothing writes it. */
    private static final Map<String, Object> UNWRITTEN = Map.of(
            "Enabled", Boolean.TRUE,
            "Rules.Gain.Enabled", Boolean.TRUE,
            "Rules.Gain.Multiplier", 1.0,
            "Rules.Gain.PlayerHits", Boolean.FALSE,
            "Rules.Grants.Enabled", Boolean.TRUE);

    private TrackWorldRuleForm() {
    }

    /** The binding of {@code track-world-rules.json}, edited one rule at a time. */
    @Nonnull
    public static OwnerFileBinding binding() {
        TrackWorldRuleConfig config = TrackWorldRuleConfig.getInstance();
        OwnerFileBinding.Below files = OwnerFileBinding.belowFrom(config, TrackWorldRuleAsset.CODEC);
        OwnerFileBinding.Entries store = OwnerFileBinding.entriesOf(config, TrackWorldRuleAsset.CODEC);
        return OwnerFileBinding.builder(BINDING, TrackWorldRuleConfig::ownerFile)
                .below((entry, path) -> {
                    Object written = files.read(entry, path);
                    if (written == null && entry != null && path.startsWith(KINDS_DOT)) {
                        // The engine reads a switch word without regard to case, so the rule file's "ability" is the
                        // form's Ability.
                        Map<String, Boolean> map = below(entry);
                        String spelled = keyIgnoringCase(map.keySet(), path.substring(KINDS_DOT.length()));
                        written = spelled == null ? null : map.get(spelled);
                    }
                    return written != null ? written : unwritten(entry, path);
                })
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
                        return store.resolved(id);
                    }

                    @Override
                    public boolean accepts(@Nullable String id) {
                        return store.accepts(id);
                    }

                    @Override
                    public boolean put(@Nonnull Path file, @Nonnull String id, @Nonnull Map<String, Object> leaves) {
                        return store.put(file, id, wholeKinds(below(id), ownerKinds(file, id), leaves));
                    }

                    @Override
                    public boolean remove(@Nonnull Path file, @Nonnull String id) {
                        return store.remove(file, id);
                    }
                })
                .view(TrackWorldRuleForm::view)
                .reload(TrackWorldRuleConfig::reloadOwnerLayer)
                .build();
    }

    /**
     * What a leaf of the rule {@code entry} reads as when no layer writes it, or null for a rule no layer under the
     * owner's file holds (without the owner's word it is no rule at all) and for a leaf with no fixed reading.
     */
    @Nullable
    static Object unwritten(@Nullable String entry, @Nonnull String path) {
        if (entry == null || TrackWorldRuleConfig.getInstance().resolveBelowOwner(entry.trim()) == null) {
            return null;
        }
        Object fixed = UNWRITTEN.get(path);
        if (fixed != null) {
            return fixed;
        }
        return path.startsWith(KINDS_DOT) ? Boolean.TRUE : null;
    }

    /**
     * The owner's file as the form reads it: each rule whose entry writes a grant switch map gets every switch the
     * rule file writes and the owner's map leaves out, as on (the owner's map applies whole, so that is what it reads
     * as in force); and since the engine reads a switch word without regard to case, each word the owner wrote also
     * reads under the rule file's spelling of it, else under its capitalized one ({@code ability} as {@code Ability}).
     */
    @Nonnull
    static JsonObject view(@Nonnull JsonObject root) {
        TrackWorldRuleConfig config = TrackWorldRuleConfig.getInstance();
        for (Map.Entry<String, JsonElement> rule : root.entrySet()) {
            if (OwnerFiles.isReservedKey(rule.getKey()) || !rule.getValue().isJsonObject()) {
                continue;
            }
            JsonObject kinds = object(rule.getValue().getAsJsonObject(), KINDS);
            if (kinds == null) {
                continue;
            }
            Map<String, Boolean> file = below(config, rule.getKey());
            for (String written : List.copyOf(kinds.keySet())) {
                String fileWord = keyIgnoringCase(file.keySet(), written);
                String spelled = fileWord != null ? fileWord : capitalized(written);
                if (!spelled.equals(written) && !kinds.has(spelled)) {
                    kinds.add(spelled, kinds.get(written).deepCopy());
                }
            }
            for (String word : file.keySet()) {
                if (keyIgnoringCase(kinds.keySet(), word) == null) {
                    kinds.addProperty(word, true);
                }
            }
        }
        return root;
    }

    /** {@code word} with its first letter upper case and the rest lower ({@code COMBAT} as {@code Combat}). */
    @Nonnull
    private static String capitalized(@Nonnull String word) {
        if (word.isEmpty()) {
            return word;
        }
        return word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1).toLowerCase(Locale.ROOT);
    }

    /**
     * The leaves to write for {@code leaves}, keeping the grant switches whole: unchanged when no switch is among them;
     * else every switch as the map reads now ({@code owner}'s map, else the rule file's {@code below}) with each given
     * switch set, or for a null one, put back to the rule file's value (left out where the rule file writes none);
     * and the whole map taken out when that leaves it exactly the rule file's. Pure.
     *
     * @param below the rule file's switch map (under the owner's), as written; empty for none
     * @param owner the owner entry's switch map, as written, or null when the entry writes none
     */
    @Nonnull
    static Map<String, Object> wholeKinds(@Nonnull Map<String, Boolean> below, @Nullable Map<String, Boolean> owner,
            @Nonnull Map<String, Object> leaves) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> switches = new LinkedHashMap<>();
        for (Map.Entry<String, Object> leaf : leaves.entrySet()) {
            if (leaf.getKey().startsWith(KINDS_DOT)) {
                switches.put(leaf.getKey().substring(KINDS_DOT.length()), leaf.getValue());
            } else {
                out.put(leaf.getKey(), leaf.getValue());
            }
        }
        if (switches.isEmpty()) {
            return leaves;
        }
        Map<String, Boolean> result = new LinkedHashMap<>(owner != null ? owner : below);
        for (Map.Entry<String, Object> change : switches.entrySet()) {
            String word = change.getKey();
            String spelled = keyIgnoringCase(result.keySet(), word);
            if (spelled == null) {
                spelled = keyIgnoringCase(below.keySet(), word);
            }
            String key = spelled != null ? spelled : word;
            String held = keyIgnoringCase(result.keySet(), word);
            if (held != null) {
                result.remove(held);
            }
            if (change.getValue() instanceof Boolean on) {
                result.put(key, on);
            } else {
                String fileKey = keyIgnoringCase(below.keySet(), word);
                if (fileKey != null) {
                    result.put(key, below.get(fileKey));
                }
            }
        }
        if (sameIgnoringCase(result, below)) {
            out.put(KINDS, null);
            return out;
        }
        if (owner != null) {
            for (String written : owner.keySet()) {
                if (keyIgnoringCase(result.keySet(), written) == null) {
                    out.put(KINDS_DOT + written, null);
                }
            }
        }
        for (Map.Entry<String, Boolean> e : result.entrySet()) {
            out.put(KINDS_DOT + e.getKey(), e.getValue());
        }
        return out;
    }

    /** The rule {@code id}'s switch map as the layers under the owner's file write it; empty for none. */
    @Nonnull
    private static Map<String, Boolean> below(@Nonnull String id) {
        return below(TrackWorldRuleConfig.getInstance(), id);
    }

    @Nonnull
    private static Map<String, Boolean> below(@Nonnull TrackWorldRuleConfig config, @Nonnull String id) {
        TrackWorldRuleAsset rule = config.resolveBelowOwner(id.trim());
        TrackRules rules = rule == null ? null : rule.rules();
        TrackRules.GrantRules grants = rules == null ? null : rules.grants();
        return grants == null ? Map.of() : new LinkedHashMap<>(grants.kinds());
    }

    /**
     * The owner entry for {@code id}'s switch map in {@code file}, as written (booleans only), or null when the file,
     * the entry or the map is missing or the file cannot be read (its writer refuses it then anyway).
     */
    @Nullable
    static Map<String, Boolean> ownerKinds(@Nonnull Path file, @Nonnull String id) {
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            String body = Files.readString(file, StandardCharsets.UTF_8);
            if (body.isBlank()) {
                return null;
            }
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject()) {
                return null;
            }
            JsonObject root = parsed.getAsJsonObject();
            String key = null;
            for (String written : root.keySet()) {
                if (!OwnerFiles.isReservedKey(written) && written.equalsIgnoreCase(id.trim())) {
                    key = written;
                    break;
                }
            }
            JsonElement entry = key == null ? null : root.get(key);
            JsonObject kinds = entry != null && entry.isJsonObject() ? object(entry.getAsJsonObject(), KINDS) : null;
            if (kinds == null) {
                return null;
            }
            Map<String, Boolean> out = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> e : kinds.entrySet()) {
                JsonElement value = e.getValue();
                if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
                    out.put(e.getKey(), value.getAsBoolean());
                }
            }
            return out;
        } catch (Exception unreadable) {
            return null;
        }
    }

    /** The object at a dotted path inside {@code scope}, or null. */
    @Nullable
    private static JsonObject object(@Nonnull JsonObject scope, @Nonnull String path) {
        JsonElement cursor = scope;
        for (String part : path.split("\\.")) {
            if (cursor == null || !cursor.isJsonObject()) {
                return null;
            }
            cursor = cursor.getAsJsonObject().get(part);
        }
        return cursor != null && cursor.isJsonObject() ? cursor.getAsJsonObject() : null;
    }

    /** The key of {@code keys} equal to {@code word} without regard to case, or null. */
    @Nullable
    private static String keyIgnoringCase(@Nonnull Iterable<String> keys, @Nonnull String word) {
        for (String key : keys) {
            if (key != null && key.equalsIgnoreCase(word)) {
                return key;
            }
        }
        return null;
    }

    /** Do two switch maps say the same, key case aside? */
    private static boolean sameIgnoringCase(@Nonnull Map<String, Boolean> a, @Nonnull Map<String, Boolean> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (Map.Entry<String, Boolean> e : a.entrySet()) {
            String other = keyIgnoringCase(b.keySet(), e.getKey());
            if (other == null || !e.getValue().equals(b.get(other))) {
                return false;
            }
        }
        return true;
    }
}
