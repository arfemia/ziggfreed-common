package com.ziggfreed.common.loot.reward;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Reads a reward written as {@code type} plus flat fields, for the authoring formats no codec
 * decodes: an override config, a hand-written content file, a JSON body carried on something else.
 *
 * <p>The parse is deliberately generic. {@code type} names the KIND and EVERY other field becomes a
 * parameter of the same name, so content can author a kind this library never heard of - another
 * mod's, or one a server minted as a kind FILE - with nothing here changing:
 *
 * <pre>{@code
 * { "type": "Item", "item": "Ingredient_Bar_Iron", "count": 5 }
 * { "type": "Lootable", "lootable": "forest_finds" }
 * { "type": "Mymod_Title", "title": "Trailblazer" }
 * }</pre>
 *
 * <p>Values are read as text and nested objects and arrays are skipped, because a parameter is a
 * single value whichever kind reads it. What a parameter MEANS belongs to the kind that declares
 * it, so this layer never interprets one. A field naming no value at all leaves the reward as
 * whatever the remaining fields say.
 *
 * <h2>The consumer supplies its own vocabulary</h2>
 *
 * <p>Three things vary per consumer and none of them can live here: which historical spellings of a
 * kind id still have to parse, which historical spellings of a parameter do, and which rewards its
 * own kinds REFUSE to be authored without. So a reader is built with a {@link #using} dialect and
 * reused; a consumer with no compat history at all passes {@link UnaryOperator#identity()} twice and
 * a null refusal rule.
 *
 * <p><b>A refusal happens HERE, at load, and that is the point.</b> A reward missing what its own
 * kind requires is skipped with a warning naming the file, so whoever authored it finds out at boot
 * and in the content audit. The alternative - loading it and failing at grant time - turns an
 * authoring mistake into a player finishing something and receiving nothing, which is the one
 * failure nobody in the room can diagnose. A kind the dialect has no opinion about is NEVER refused:
 * the mod that defines it may simply not be installed yet, and an audit is where that is reported.
 */
public final class RewardJson {

    /** The field naming the kind. Matched case-insensitively, like every other field here. */
    private static final String FIELD_TYPE = "type";

    @Nonnull
    private final UnaryOperator<String> kinds;

    @Nonnull
    private final UnaryOperator<String> paramKeys;

    @Nullable
    private final Function<RewardSpec, String> refusals;

    @Nonnull
    private final Consumer<String> warn;

    private RewardJson(@Nonnull UnaryOperator<String> kinds, @Nonnull UnaryOperator<String> paramKeys,
                       @Nullable Function<RewardSpec, String> refusals, @Nonnull Consumer<String> warn) {
        this.kinds = kinds;
        this.paramKeys = paramKeys;
        this.refusals = refusals;
        this.warn = warn;
    }

    /**
     * A reader speaking one consumer's dialect. Build it once and keep it: it holds no state, and
     * a second one built differently is a second answer to what a file means.
     *
     * @param kinds     an authored {@code type} to the kind id that is registered, for content
     *                  written before the ids settled; identity when there is no such history
     * @param paramKeys an authored field name to the parameter the handler reads it under, same
     *                  reason; it is also where a field name is folded to whatever casing the
     *                  consumer's parameters are written in
     * @param refusals  why a reward cannot be paid out as authored, or null when it can; the whole
     *                  rule is null for a consumer that refuses nothing
     * @param warn      where a refusal is reported, with the caller's own context label in front
     */
    @Nonnull
    public static RewardJson using(@Nonnull UnaryOperator<String> kinds,
                                   @Nonnull UnaryOperator<String> paramKeys,
                                   @Nullable Function<RewardSpec, String> refusals,
                                   @Nonnull Consumer<String> warn) {
        return new RewardJson(kinds, paramKeys, refusals, warn);
    }

    /** {@link #parse(JsonObject, String, boolean)} with offline queueing off. */
    @Nullable
    public RewardSpec parse(@Nonnull JsonObject json, @Nonnull String contextLabel) {
        return parse(json, contextLabel, false);
    }

    /**
     * One reward object, or null when the dialect refuses it (warned with {@code contextLabel} so
     * whoever wrote the file can find it).
     *
     * @param defaultQueueIfOffline what this system's rewards mean when they say nothing about
     *                              waiting for an absent player; written onto the reward, so the
     *                              default is decided once here rather than re-guessed at payout
     */
    @Nullable
    public RewardSpec parse(@Nonnull JsonObject json, @Nonnull String contextLabel,
                            boolean defaultQueueIfOffline) {
        Map<String, String> params = new LinkedHashMap<>();
        String kind = LootRewardKinds.KIND_COMMAND;
        for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
            String key = entry.getKey();
            JsonElement value = entry.getValue();
            if (key == null || value == null || !value.isJsonPrimitive()) {
                // A nested object or array is not a reward parameter; a parameter is a single value
                // whichever kind reads it.
                continue;
            }
            if (FIELD_TYPE.equalsIgnoreCase(key.trim())) {
                String type = value.getAsString();
                if (type != null && !type.isBlank()) {
                    kind = kinds.apply(type);
                }
                continue;
            }
            params.put(paramKeys.apply(key), value.getAsString());
        }

        RewardSpec spec = RewardSpec.of(kind, params);
        if (spec.param(RewardGrants.P_QUEUE_IF_OFFLINE) == null) {
            // Filled on the SPEC, not the raw map: a dialect that does not fold field casing
            // (identity()) can still author the field under a different case than the constant, and
            // RewardSpec is what folds every key to the same case before this checks for one.
            // Filling the raw map instead can leave both the authored entry and this default alive
            // under different cases, and whichever the map iterates last wins the fold.
            spec = spec.with(RewardGrants.P_QUEUE_IF_OFFLINE, Boolean.toString(defaultQueueIfOffline));
        }
        String refusal = refusalReason(spec);
        if (refusal != null) {
            warn.accept(contextLabel + ": " + refusal + " - skipping reward");
            return null;
        }
        return spec;
    }

    /**
     * Can this reward be paid out as authored? True for every kind the dialect has no opinion
     * about, since the requirements of those belong to whoever defines them.
     */
    public boolean isPayable(@Nonnull RewardSpec spec) {
        return refusalReason(spec) == null;
    }

    /** Why this reward cannot be paid out as authored, or null when it can. */
    @Nullable
    public String refusalReason(@Nonnull RewardSpec spec) {
        return refusals == null ? null : refusals.apply(spec);
    }
}
