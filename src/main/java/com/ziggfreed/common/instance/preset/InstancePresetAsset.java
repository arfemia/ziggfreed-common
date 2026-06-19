package com.ziggfreed.common.instance.preset;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.instance.reward.InstanceReward;
import com.ziggfreed.common.instance.reward.RewardOnExit;

/**
 * A pack-authorable, mod-agnostic instance preset, loaded from a consumer's
 * {@code Server/<Mod>/Instances/*.json} (the path is supplied by the consumer at
 * register time, so common hard-codes no mod name). It owns the CROSS-CUTTING,
 * non-gameplay layer of an instance preset - queue policy, display keys, leaderboard
 * config, and the configurable reward list - joined to the consumer's gameplay config
 * (e.g. Kweebec's {@code RoundPresetAsset} -> {@code RuleSet}) by the SHARED lowercase
 * preset id. The two are field-disjoint codecs over one id namespace, never a twin
 * authority for the same field; the gameplay knobs stay on the consumer's asset.
 *
 * <p><b>Pattern A - full structured asset</b> (mirrors Kweebec's {@code RoundPresetAsset}
 * / hyMMO's {@code QuestGiverAsset}). The engine decodes it DIRECTLY into typed fields
 * via {@link #CODEC} - the codec IS the single schema authority on both the pack layer
 * and the owner layer (the same CODEC decodes an owner override). Every {@code KeyedCodec}
 * field name is PascalCase (the constructor rejects a lower-case first letter at static
 * init); the {@code AssetCodecInitTest} unit test guards it.
 *
 * <p>The reward list is a {@code String[]} of compact specs ({@link InstanceReward#parse})
 * because the codec has no list-of-objects form; commands (which contain spaces) are
 * Java-authored via the API-override seam, not pack-spec'd. Pack JSON shape (all fields
 * optional; absent = the documented default):
 * <pre>{@code
 * { "Name": "nightmare", "Enabled": true,
 *   "NameKey": "preset.nightmare.name", "DescriptionKey": "preset.nightmare.desc",
 *   "FillTimeoutSeconds": 20, "CountdownSeconds": 5, "AllowSolo": true, "LeaderForceStart": false,
 *   "LeaderboardBucket": "PARTY_SIZE", "LeaderboardKey": "leaderboard.nightmare.title",
 *   "Rewards": [ "currency bounty_token 100", "item hytale:emerald 5 reward.emerald.name" ],
 *   "RewardOnExit": "ON_WIN" }
 * }</pre>
 */
public final class InstancePresetAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, InstancePresetAsset>> {

    /** Sentinel for an absent optional int (use the documented default). Package-visible for {@link QueueModeSet}. */
    static final int UNSET_INT = Integer.MIN_VALUE;

    /** Queue-policy defaults (Kweebec's previously-hardcoded values). */
    private static final int DEFAULT_FILL_SECONDS = 20;
    private static final int DEFAULT_COUNTDOWN_SECONDS = 5;
    private static final boolean DEFAULT_ALLOW_SOLO = true;
    private static final boolean DEFAULT_LEADER_FORCE_START = false;

    private String id;
    private AssetExtraInfo.Data data;

    private boolean enabled = true;
    @Nullable private String nameKey;
    @Nullable private String descriptionKey;
    private int fillTimeoutSeconds = UNSET_INT;
    private int countdownSeconds = UNSET_INT;
    @Nullable private Boolean allowSolo;
    @Nullable private Boolean leaderForceStart;
    @Nullable private String leaderboardBucket;
    @Nullable private String leaderboardKey;
    @Nullable private String[] rewards;
    @Nullable private String rewardOnExit;
    @Nullable private QueueModes queueModes;

    public static final AssetBuilderCodec<String, InstancePresetAsset> CODEC = AssetBuilderCodec.builder(
                    InstancePresetAsset.class,
                    InstancePresetAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            // Name is an optional human-readable echo of the asset key (the authoritative
            // key is the filename) - a no-op setter so it doesn't trip "Unused key(s)".
            .append(new KeyedCodec<>("Name", Codec.STRING, false),
                    (a, name) -> { /* no-op - id comes from the filename */ },
                    a -> a.id)
            .add()
            .append(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false), (a, v) -> a.enabled = v, a -> a.enabled)
            .add()
            .append(new KeyedCodec<>("NameKey", Codec.STRING, false), (a, v) -> a.nameKey = v, a -> a.nameKey)
            .add()
            .append(new KeyedCodec<>("DescriptionKey", Codec.STRING, false), (a, v) -> a.descriptionKey = v, a -> a.descriptionKey)
            .add()
            .append(new KeyedCodec<>("FillTimeoutSeconds", Codec.INTEGER, false), (a, v) -> a.fillTimeoutSeconds = v, a -> a.fillTimeoutSeconds)
            .add()
            .append(new KeyedCodec<>("CountdownSeconds", Codec.INTEGER, false), (a, v) -> a.countdownSeconds = v, a -> a.countdownSeconds)
            .add()
            .append(new KeyedCodec<>("AllowSolo", Codec.BOOLEAN, false), (a, v) -> a.allowSolo = v, a -> a.allowSolo)
            .add()
            .append(new KeyedCodec<>("LeaderForceStart", Codec.BOOLEAN, false), (a, v) -> a.leaderForceStart = v, a -> a.leaderForceStart)
            .add()
            .append(new KeyedCodec<>("LeaderboardBucket", Codec.STRING, false), (a, v) -> a.leaderboardBucket = v, a -> a.leaderboardBucket)
            .add()
            .append(new KeyedCodec<>("LeaderboardKey", Codec.STRING, false), (a, v) -> a.leaderboardKey = v, a -> a.leaderboardKey)
            .add()
            .append(new KeyedCodec<>("Rewards", Codec.STRING_ARRAY, false), (a, v) -> a.rewards = v, a -> a.rewards)
            .add()
            .append(new KeyedCodec<>("RewardOnExit", Codec.STRING, false), (a, v) -> a.rewardOnExit = v, a -> a.rewardOnExit)
            .add()
            .append(new KeyedCodec<>("QueueModes", QueueModes.CODEC, false), (a, v) -> a.queueModes = v, a -> a.queueModes)
            .add()
            .build();

    public InstancePresetAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Build the normalized runtime {@link InstancePreset}. Any field left absent keeps
     * the documented default, so a partial preset only overrides what it authors.
     *
     * @param presetId the preset id (asset key on the pack layer, map key on the owner layer)
     */
    @Nonnull
    public InstancePreset toPreset(@Nonnull String presetId) {
        int fill = fillTimeoutSeconds != UNSET_INT ? fillTimeoutSeconds : DEFAULT_FILL_SECONDS;
        int countdown = countdownSeconds != UNSET_INT ? countdownSeconds : DEFAULT_COUNTDOWN_SECONDS;
        boolean solo = allowSolo != null ? allowSolo : DEFAULT_ALLOW_SOLO;
        boolean force = leaderForceStart != null ? leaderForceStart : DEFAULT_LEADER_FORCE_START;
        return new InstancePreset(presetId.toLowerCase(java.util.Locale.ROOT), enabled, nameKey, descriptionKey,
                fill, countdown, solo, force,
                LeaderboardBucket.fromString(leaderboardBucket), leaderboardKey,
                InstanceReward.parseAll(rewards), RewardOnExit.fromString(rewardOnExit),
                QueueModeSet.from(queueModes));
    }

    /**
     * One authored queue-mode entry (a card on the {@code PlayModePage}). All fields
     * optional; absent = the neutral fallback ({@link QueueModeSet#fallback()} overlays).
     * PascalCase keys (the codec rejects a lower-case first letter at static init).
     */
    public static final class QueueMode {
        public static final BuilderCodec<QueueMode> CODEC = BuilderCodec.builder(QueueMode.class, QueueMode::new)
                .append(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false), (m, v) -> m.enabled = v, m -> m.enabled).add()
                .append(new KeyedCodec<>("IconItemId", Codec.STRING, false), (m, v) -> m.iconItemId = v, m -> m.iconItemId).add()
                .append(new KeyedCodec<>("Order", Codec.INTEGER, false), (m, v) -> m.order = v, m -> m.order).add()
                .append(new KeyedCodec<>("LabelKey", Codec.STRING, false), (m, v) -> m.labelKey = v, m -> m.labelKey).add()
                .build();

        @Nullable protected Boolean enabled;
        @Nullable protected String iconItemId;
        protected int order = UNSET_INT;
        @Nullable protected String labelKey;

        public QueueMode() {
        }
    }

    /** The three fixed queue modes a preset can author, each optional ({@code Public}/{@code Party}/{@code Solo}). */
    public static final class QueueModes {
        public static final BuilderCodec<QueueModes> CODEC = BuilderCodec.builder(QueueModes.class, QueueModes::new)
                .append(new KeyedCodec<>("Public", QueueMode.CODEC, false), (q, v) -> q.publicMode = v, q -> q.publicMode).add()
                .append(new KeyedCodec<>("Party", QueueMode.CODEC, false), (q, v) -> q.partyMode = v, q -> q.partyMode).add()
                .append(new KeyedCodec<>("Solo", QueueMode.CODEC, false), (q, v) -> q.soloMode = v, q -> q.soloMode).add()
                .build();

        @Nullable protected QueueMode publicMode;
        @Nullable protected QueueMode partyMode;
        @Nullable protected QueueMode soloMode;

        public QueueModes() {
        }
    }
}
