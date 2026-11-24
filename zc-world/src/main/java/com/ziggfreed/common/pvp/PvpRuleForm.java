package com.ziggfreed.common.pvp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 * The PvP Rules owner form's binding ({@code Server/ZiggfreedCommon/OwnerForms/Pvp_Rules.json}, an Entries form over
 * {@code mods/ziggfreedcommon/pvp-rules.json}, a row per rule file). Every path is the rule's own leaf, read and
 * written through the store as it is, but the repeat guard's steps, which sit in a list a form's dotted paths cannot
 * reach: the binding lifts them to a plain list, {@link #STEPS}. The repeat guard is one group ({@code Rules.Credit.Repeat}:
 * its steps and its window), so a change to either writes the whole group, the other half as it reads now, and a
 * Reset of either takes the owner's whole group out, so the guard never reads half the owner's and half the packs'.
 */
public final class PvpRuleForm {

    /** The repeat guard's steps as one list of words, as the form names it. */
    public static final String STEPS = "RepeatSteps";

    /** The repeat guard's window, a real leaf the form names by its path. */
    public static final String WINDOW = "Rules.Credit.Repeat.Window";

    private static final String REPEAT = "Rules.Credit.Repeat";
    private static final String REPEAT_STEPS = REPEAT + ".Steps";

    private PvpRuleForm() {
    }

    /** The binding of {@code pvp-rules.json}, edited one rule at a time. */
    @Nonnull
    public static OwnerFileBinding binding() {
        PvpRuleConfig config = PvpRuleConfig.getInstance();
        OwnerFileBinding.Entries store = OwnerFileBinding.entriesOf(config, PvpRuleAsset.CODEC);
        OwnerFileBinding.Below plain = OwnerFileBinding.belowFrom(config, PvpRuleAsset.CODEC);
        return OwnerFileBinding.builder(PvpRuleConfig.BINDING, PvpRuleConfig::file)
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
                        PvpRuleAsset rule = config.resolve(id.trim());
                        if (doc != null && rule != null) {
                            doc.put(STEPS, strings(DiminishSpec.stepWords(repeatOf(rule))));
                        }
                        return doc;
                    }

                    @Override
                    public boolean accepts(@Nullable String id) {
                        return store.accepts(id);
                    }

                    @Override
                    public boolean put(@Nonnull Path file, @Nonnull String id, @Nonnull Map<String, Object> leaves) {
                        return store.put(file, id, written(config.resolve(id.trim()), leaves));
                    }

                    @Override
                    public boolean remove(@Nonnull Path file, @Nonnull String id) {
                        return store.remove(file, id);
                    }
                })
                .view(PvpRuleForm::view)
                .reload(PvpRuleConfig::reloadOwner)
                .build();
    }

    /** A leaf without the owner's word: the lifted steps read off the rule under the owner, else the plain leaf. */
    @Nullable
    static Object below(@Nullable String entry, @Nonnull String path, @Nonnull OwnerFileBinding.Below plain) {
        if (entry == null || entry.isBlank()) {
            return null;
        }
        if (STEPS.equals(path)) {
            PvpRuleAsset rule = PvpRuleConfig.getInstance().resolveBelowOwner(entry.trim());
            return rule == null ? null : DiminishSpec.stepWords(repeatOf(rule));
        }
        return plain.read(entry, path);
    }

    /** The owner's file as the form reads it: each entry's own repeat steps lifted into the list. */
    @Nonnull
    static JsonObject view(@Nonnull JsonObject root) {
        for (Map.Entry<String, JsonElement> rule : root.entrySet()) {
            if (OwnerFiles.isReservedKey(rule.getKey()) || !rule.getValue().isJsonObject()) {
                continue;
            }
            JsonElement steps = child(rule.getValue(), "Rules", "Credit", "Repeat", "Steps");
            if (steps != null && steps.isJsonArray()) {
                JsonArray words = new JsonArray();
                for (JsonElement step : steps.getAsJsonArray()) {
                    if (step != null && step.isJsonPrimitive() && step.getAsJsonPrimitive().isNumber()) {
                        words.add(DiminishSpec.stepWords(DiminishSpec.of(null, step.getAsDouble())).get(0));
                    }
                }
                rule.getValue().getAsJsonObject().add(STEPS, words);
            }
        }
        return root;
    }

    /**
     * {@code leaves} as the store's writer takes them: a change to the repeat steps or window writes the whole
     * {@code Rules.Credit.Repeat} group (the other half as {@code inForce} reads it now), and a Reset (null) of either
     * takes the owner's whole group out. Every other leaf as given. Pure.
     */
    @Nonnull
    static Map<String, Object> written(@Nullable PvpRuleAsset inForce, @Nonnull Map<String, Object> leaves) {
        Map<String, Object> out = new LinkedHashMap<>(leaves);
        boolean stepsGiven = out.containsKey(STEPS);
        boolean windowGiven = out.containsKey(WINDOW);
        if (!stepsGiven && !windowGiven) {
            return out;
        }
        Object steps = out.remove(STEPS);
        Object window = out.remove(WINDOW);
        if ((stepsGiven && steps == null) || (windowGiven && window == null)) {
            out.put(REPEAT, null);
            return out;
        }
        DiminishSpec now = inForce == null ? PvpRules.DEFAULT_REPEAT : repeatOf(inForce);
        List<Double> parsed = stepsGiven ? DiminishSpec.parseStepValue(steps) : List.of();
        List<Object> stepList = new ArrayList<>();
        if (!parsed.isEmpty()) {
            stepList.addAll(parsed);
        } else {
            for (double step : now.steps()) {
                stepList.add(step);
            }
        }
        out.put(REPEAT_STEPS, stepList);
        out.put(WINDOW, windowGiven ? String.valueOf(window).trim() : now.windowWord());
        return out;
    }

    /** The repeat guard a rule reads now. */
    @Nonnull
    private static DiminishSpec repeatOf(@Nonnull PvpRuleAsset rule) {
        PvpRules rules = rule.rules();
        return rules == null ? PvpRules.DEFAULT_REPEAT : rules.credit().repeat();
    }

    /** The element at {@code path} under {@code from}, or null when any step is missing or no object. */
    @Nullable
    private static JsonElement child(@Nonnull JsonElement from, @Nonnull String... path) {
        JsonElement at = from;
        for (String key : path) {
            if (at == null || !at.isJsonObject()) {
                return null;
            }
            at = at.getAsJsonObject().get(key);
        }
        return at;
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
