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
 * A pack-authorable, mod-agnostic MULTI-PHASE BOSS definition, loaded from a consumer's
 * {@code Server/<Mod>/Bosses/*.json} (the path is supplied by the consumer at register time,
 * so common hard-codes no mod name). It is the pure-DATA description of a multi-phase
 * boss capstone a consumer's controller spawns at an encounter climax: which NPC role each
 * phase uses, the HP-fraction thresholds the controller swaps phases on, how many adds each
 * phase summons, the live-add ceiling, and the spawn/swap sound cues. This is the asset-driven
 * seam so a boss is tuned purely as DATA, no code change.
 *
 * <p><b>Pattern A - full structured asset</b> (mirrors common's {@code InstancePresetAsset}
 * and Kweebec's {@code RoundPresetAsset} field-for-field). The engine decodes a boss DIRECTLY
 * into typed fields via {@link #CODEC} - the codec IS the single schema authority on both the
 * pack layer and the owner layer (the same CODEC decodes an owner override). The schema is
 * 100% generic: common ships ZERO jar defaults, a consumer authors every boss as pack JSON.
 *
 * <p>Every {@code KeyedCodec} field name is PascalCase (the constructor rejects a lower-case
 * first letter at static init, throwing at server start); the reserved keys {@code Id}/
 * {@code Parent}/{@code Tags} are never reused as field names.
 *
 * <p>Pack JSON shape (all knobs optional; absent = the documented default):
 * <pre>{@code
 * { "Name": "warden",
 *   "Phase1Role": "MyMod_Warden",
 *   "Phase2Role": "MyMod_Warden_Phase2",
 *   "Phase3Role": "MyMod_Warden_Phase3",
 *   "Phase2ThresholdFraction": 0.5, "Phase3ThresholdFraction": 0.25,
 *   "Phase1AddCount": 0, "Phase2AddCount": 2, "Phase3AddCount": 3,
 *   "AddRole": "MyMod_Add", "AddCap": 4,
 *   "SpawnSoundId": "", "PhaseSwapSoundId": "",
 *   "NameKey": "mymod.npc.warden.name" }
 * }</pre>
 */
public final class MultiPhaseBossAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, MultiPhaseBossAsset>> {

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private String nameKey;
    @Nullable private String phase1Role;
    @Nullable private String phase2Role;
    @Nullable private String phase3Role;
    private double phase2ThresholdFraction = 0.5;
    private double phase3ThresholdFraction = 0.25;
    private int phase1AddCount = 0;
    private int phase2AddCount = 2;
    private int phase3AddCount = 3;
    @Nullable private String addRole;
    private int addCap = 4;
    @Nullable private String spawnSoundId;
    @Nullable private String phaseSwapSoundId;

    public static final AssetBuilderCodec<String, MultiPhaseBossAsset> CODEC = AssetBuilderCodec.builder(
                    MultiPhaseBossAsset.class,
                    MultiPhaseBossAsset::new,
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
            .append(new KeyedCodec<>("NameKey", Codec.STRING, false), (a, v) -> a.nameKey = v, a -> a.nameKey)
            .add()
            .append(new KeyedCodec<>("Phase1Role", Codec.STRING, false), (a, v) -> a.phase1Role = v, a -> a.phase1Role)
            .add()
            .append(new KeyedCodec<>("Phase2Role", Codec.STRING, false), (a, v) -> a.phase2Role = v, a -> a.phase2Role)
            .add()
            .append(new KeyedCodec<>("Phase3Role", Codec.STRING, false), (a, v) -> a.phase3Role = v, a -> a.phase3Role)
            .add()
            .append(new KeyedCodec<>("Phase2ThresholdFraction", Codec.DOUBLE, false), (a, v) -> a.phase2ThresholdFraction = v, a -> a.phase2ThresholdFraction)
            .add()
            .append(new KeyedCodec<>("Phase3ThresholdFraction", Codec.DOUBLE, false), (a, v) -> a.phase3ThresholdFraction = v, a -> a.phase3ThresholdFraction)
            .add()
            .append(new KeyedCodec<>("Phase1AddCount", Codec.INTEGER, false), (a, v) -> a.phase1AddCount = v, a -> a.phase1AddCount)
            .add()
            .append(new KeyedCodec<>("Phase2AddCount", Codec.INTEGER, false), (a, v) -> a.phase2AddCount = v, a -> a.phase2AddCount)
            .add()
            .append(new KeyedCodec<>("Phase3AddCount", Codec.INTEGER, false), (a, v) -> a.phase3AddCount = v, a -> a.phase3AddCount)
            .add()
            .append(new KeyedCodec<>("AddRole", Codec.STRING, false), (a, v) -> a.addRole = v, a -> a.addRole)
            .add()
            .append(new KeyedCodec<>("AddCap", Codec.INTEGER, false), (a, v) -> a.addCap = v, a -> a.addCap)
            .add()
            .append(new KeyedCodec<>("SpawnSoundId", Codec.STRING, false), (a, v) -> a.spawnSoundId = v, a -> a.spawnSoundId)
            .add()
            .append(new KeyedCodec<>("PhaseSwapSoundId", Codec.STRING, false), (a, v) -> a.phaseSwapSoundId = v, a -> a.phaseSwapSoundId)
            .add()
            .build();

    public MultiPhaseBossAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /**
     * Lang key for the boss display name (the HUD label). Returns the explicit {@code NameKey}
     * when authored, else {@code null} - common bakes NO by-convention namespace (a consumer
     * supplies its own NameKey or derives a convention key in its own code). A consumer mod
     * resolving a {@code null} key should fall back to its own naming convention.
     */
    @Nullable
    public String nameKey() {
        if (nameKey != null && !nameKey.isBlank()) {
            return nameKey;
        }
        return null;
    }

    /** NPC role spawned at the climax (full HP). Never blank in a usable boss. */
    @Nullable
    public String phase1Role() {
        return phase1Role;
    }

    /** NPC role the controller swaps to at {@link #phase2ThresholdFraction()}; blank = no Phase 2 swap. */
    @Nullable
    public String phase2Role() {
        return phase2Role;
    }

    /** NPC role the controller swaps to at {@link #phase3ThresholdFraction()}; blank = no Phase 3 swap. */
    @Nullable
    public String phase3Role() {
        return phase3Role;
    }

    /** HP fraction (0..1) at/below which the controller swaps to {@link #phase2Role()}. */
    public double phase2ThresholdFraction() {
        return phase2ThresholdFraction;
    }

    /** HP fraction (0..1) at/below which the controller swaps to {@link #phase3Role()}. */
    public double phase3ThresholdFraction() {
        return phase3ThresholdFraction;
    }

    /** Adds summoned when entering Phase 1 (clamped to at least 0 by the consumer). */
    public int phase1AddCount() {
        return phase1AddCount;
    }

    /** Adds summoned when entering Phase 2. */
    public int phase2AddCount() {
        return phase2AddCount;
    }

    /** Adds summoned when entering Phase 3. */
    public int phase3AddCount() {
        return phase3AddCount;
    }

    /** The NPC role each summoned add uses; blank/null = no adds regardless of the per-phase counts. */
    @Nullable
    public String addRole() {
        return addRole;
    }

    /** Live-add ceiling so the boss can never flood the arena; {@code 0} = no extra adds. */
    public int addCap() {
        return addCap;
    }

    /** Sound id played at the boss when it first spawns, or blank/null for none. */
    @Nullable
    public String spawnSoundId() {
        return spawnSoundId;
    }

    /** Sound id played at the boss on each phase swap (the roar), or blank/null for none. */
    @Nullable
    public String phaseSwapSoundId() {
        return phaseSwapSoundId;
    }
}
