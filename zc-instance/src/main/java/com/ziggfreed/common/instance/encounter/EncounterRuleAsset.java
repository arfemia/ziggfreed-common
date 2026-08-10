package com.ziggfreed.common.instance.encounter;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;

/**
 * A pack-authorable ENCOUNTER RULE - the generic, mod-agnostic seam that drives an
 * {@link EncounterDirector} wave: WHEN a wave fires (the {@code Trigger} moment), WHERE
 * its extra entities appear (the {@code Placement}), and HOW MANY join, purely as DATA.
 * Loaded from a consumer's {@code Server/ZiggfreedCommon/EncounterRules/*.json} (the
 * store path common registers; a consumer authors its rules there and reads them back
 * through {@link EncounterRuleConfig}). Generalized from Kweebec's {@code SpawnRuleAsset}
 * so any minigame, dungeon, or raid (eventually the MMO) tunes its in-encounter
 * escalation without a code change.
 *
 * <p><b>Pattern A - full structured asset</b> (mirrors common's {@code InstancePresetAsset}
 * / Kweebec's {@code SpawnRuleAsset} field-for-field). The engine decodes a rule DIRECTLY
 * into typed fields via {@link #CODEC} - the codec IS the single schema authority on the
 * pack layer and any owner override. Every {@code KeyedCodec} field name is PascalCase
 * (the constructor rejects a lower-case first letter at static init, throwing at server
 * start).
 *
 * <p><b>Generalized seams (vs Kweebec's closed enums).</b> {@code Trigger} is a free
 * STRING here, NOT a closed enum: common ships only generic moments conceptually
 * (ROUND_START / TIME_ELAPSED / PLAYER_PROXIMITY), and the asset stores whatever the
 * author writes so a consumer can resolve its OWN moments (e.g. {@code SHRINE_LIT},
 * {@code CORRUPTION_TIER}) without changing common. {@code Placement} is likewise a free
 * STRING the consumer maps onto the existing common {@link com.ziggfreed.common.world.SpawnPlacement}
 * helpers ({@code ringAround} / {@code nearPlayer} / {@code snapToSurface}); common ships
 * no placement enum, so the consumer owns the mapping. The numeric knobs
 * (count / weight / cap / cooldownSeconds / maxPerRound / ringRadius) stay generic and
 * feed an {@link EncounterDirector} gate plus a {@link com.ziggfreed.common.world.SpawnPlacement}
 * geometry call directly.
 *
 * <p>Pack JSON shape (all knobs optional; absent = the documented default):
 * <pre>{@code
 * { "Name": "shrine_reinforce", "Trigger": "SHRINE_LIT",
 *   "Placement": "NEAR_RANDOM_PLAYER", "UnitId": "",
 *   "Count": 1, "Weight": 1.0, "Cap": 6, "CooldownSeconds": 8.0,
 *   "MinTier": 0, "MaxPerRound": 3, "RingRadius": 14.0,
 *   "AtSeconds": 0, "AtTier": 0 }
 * }</pre>
 */
public final class EncounterRuleAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, EncounterRuleAsset>> {

    /** The trigger string when none is authored (the generic round-start moment). */
    public static final String DEFAULT_TRIGGER = "ROUND_START";
    /** The placement string when none is authored (a player-relative ring placement). */
    public static final String DEFAULT_PLACEMENT = "NEAR_RANDOM_PLAYER";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private String trigger;
    @Nullable private String placement;
    @Nullable private String unitId;
    private int count = 1;
    private double weight = 1.0;
    private int cap = 0;
    private double cooldownSeconds = 0.0;
    private int minTier = 0;
    private int maxPerRound = 0;
    private double ringRadius = 12.0;
    private int atSeconds = 0;
    private int atTier = 0;

    public static final AssetBuilderCodec<String, EncounterRuleAsset> CODEC = AssetBuilderCodec.builder(
                    EncounterRuleAsset.class,
                    EncounterRuleAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            // Name is an optional human-readable echo of the asset key (the authoritative
            // key is the filename) - consumed by a no-op setter so it doesn't trip the
            // "Unused key(s)" warning, and emitted on encode.
            .append(new KeyedCodec<>("Name", Codec.STRING, false),
                    (a, name) -> { /* no-op - id already comes from the filename */ },
                    a -> a.id)
            .add()
            .append(new KeyedCodec<>("Trigger", Codec.STRING, false), (a, v) -> a.trigger = v, a -> a.trigger)
            .add()
            .append(new KeyedCodec<>("Placement", Codec.STRING, false), (a, v) -> a.placement = v, a -> a.placement)
            .add()
            .append(new KeyedCodec<>("UnitId", Codec.STRING, false), (a, v) -> a.unitId = v, a -> a.unitId)
            .add()
            .append(new KeyedCodec<>("Count", Codec.INTEGER, false), (a, v) -> a.count = v, a -> a.count)
            .add()
            .append(new KeyedCodec<>("Weight", Codec.DOUBLE, false), (a, v) -> a.weight = v, a -> a.weight)
            .add()
            .append(new KeyedCodec<>("Cap", Codec.INTEGER, false), (a, v) -> a.cap = v, a -> a.cap)
            .add()
            .append(new KeyedCodec<>("CooldownSeconds", Codec.DOUBLE, false), (a, v) -> a.cooldownSeconds = v, a -> a.cooldownSeconds)
            .add()
            .append(new KeyedCodec<>("MinTier", Codec.INTEGER, false), (a, v) -> a.minTier = v, a -> a.minTier)
            .add()
            .append(new KeyedCodec<>("MaxPerRound", Codec.INTEGER, false), (a, v) -> a.maxPerRound = v, a -> a.maxPerRound)
            .add()
            .append(new KeyedCodec<>("RingRadius", Codec.DOUBLE, false), (a, v) -> a.ringRadius = v, a -> a.ringRadius)
            .add()
            .append(new KeyedCodec<>("AtSeconds", Codec.INTEGER, false), (a, v) -> a.atSeconds = v, a -> a.atSeconds)
            .add()
            .append(new KeyedCodec<>("AtTier", Codec.INTEGER, false), (a, v) -> a.atTier = v, a -> a.atTier)
            .add()
            .build();

    public EncounterRuleAsset() {
    }

    /**
     * Build an encounter rule in code (a consumer's jar baseline floor), without going
     * through the JSON {@link #CODEC}. The args are, in order: {@code id, trigger,
     * placement, unitId, count, weight, cap, cooldownSeconds, minTier, maxPerRound,
     * ringRadius, atSeconds, atTier}.
     */
    @Nonnull
    public static EncounterRuleAsset of(@Nonnull String id, @Nullable String trigger, @Nullable String placement,
                                        @Nullable String unitId, int count, double weight, int cap,
                                        double cooldownSeconds, int minTier, int maxPerRound,
                                        double ringRadius, int atSeconds, int atTier) {
        EncounterRuleAsset a = new EncounterRuleAsset();
        a.id = id;
        a.trigger = trigger;
        a.placement = placement;
        a.unitId = unitId;
        a.count = count;
        a.weight = weight;
        a.cap = cap;
        a.cooldownSeconds = cooldownSeconds;
        a.minTier = minTier;
        a.maxPerRound = maxPerRound;
        a.ringRadius = ringRadius;
        a.atSeconds = atSeconds;
        a.atTier = atTier;
        return a;
    }

    @Override
    public String getId() {
        return id;
    }

    /**
     * The gameplay moment that fires this rule, as the authored STRING (the consumer
     * resolves it against its own moments). Defaults to {@link #DEFAULT_TRIGGER} when none
     * is authored.
     */
    @Nonnull
    public String trigger() {
        return (trigger == null || trigger.isBlank()) ? DEFAULT_TRIGGER : trigger.trim();
    }

    /**
     * Where this rule's extra entities appear, as the authored STRING (the consumer maps
     * it onto a {@link com.ziggfreed.common.world.SpawnPlacement} call). Defaults to
     * {@link #DEFAULT_PLACEMENT} when none is authored.
     */
    @Nonnull
    public String placement() {
        return (placement == null || placement.isBlank()) ? DEFAULT_PLACEMENT : placement.trim();
    }

    /**
     * The unit id (role / archetype / type) this rule spawns, or {@code null}/blank to draw
     * purely from the consumer's eligible roster. Consumer-interpreted.
     */
    @Nullable
    public String unitId() {
        return unitId;
    }

    /** How many extra entities one fire of this rule requests (clamped to at least 1 by the consumer). */
    public int count() {
        return count;
    }

    /** Weighted-selection weight when this rule's unit competes in the roster pick. */
    public double weight() {
        return weight;
    }

    /**
     * Per-rule live ceiling; {@code 0} = defer to the consumer's global cap. The consumer
     * always also honors its global cap, so a rule {@code Cap} only LOWERS the ceiling for
     * this rule.
     */
    public int cap() {
        return cap;
    }

    /** Minimum seconds between two fires of this rule; {@code 0} = no cooldown. */
    public double cooldownSeconds() {
        return cooldownSeconds;
    }

    /** Tier at/after which this rule is eligible to fire (0 = always). */
    public int minTier() {
        return minTier;
    }

    /** Maximum fires per round/encounter; {@code 0} = unlimited (still bounded by the cap + cooldown). */
    public int maxPerRound() {
        return maxPerRound;
    }

    /** Ring/scatter radius (blocks) for the player-relative placements. */
    public double ringRadius() {
        return ringRadius;
    }

    /** For a time-elapsed trigger: encounter-elapsed seconds at/after which the rule may fire. */
    public int atSeconds() {
        return atSeconds;
    }

    /** For a tier-crossing trigger: the specific tier whose crossing fires the rule (0 = any crossing). */
    public int atTier() {
        return atTier;
    }
}
