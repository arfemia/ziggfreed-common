package com.ziggfreed.common.encounter.asset;

import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.asset.EditorDataSets;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.codec.InheritMapCodec;
import com.ziggfreed.common.factor.FactorFormula;
import com.ziggfreed.common.loot.LootRef;

/**
 * What the server OWES for a fight it does not describe: one row per native encounter script,
 * naming only what the script itself cannot say.
 *
 * <p>The fight is {@code Server/EncounterManager/<EncounterAsset>.json}: its states, phases, health
 * thresholds, role swaps, adds, bar and music are authored THERE and run by the engine. This row,
 * at {@code Server/ZiggfreedCommon/Encounters/<Id>.json}, binds that script to the things around
 * it: how credit is shared out, how the subject's health scales with the party, what the defeat
 * pays, which feedback moments fire, where a map marker goes. It deliberately re-states nothing the
 * script says, and a test holds it to that.
 *
 * <pre>{@code
 * {
 *   "EncounterAsset": "Zc_Encounter_Example",
 *   "NameKey": "ziggfreedcommon.encounter.example.name",
 *   "Subject":       { "TargetSlot": "Boss" },
 *   "Scale":         { "HealthPerMember": 0.35, "MaxHealthMultiplier": 5.0 },
 *   "Timing":        { "WipeGraceSeconds": 15 },
 *   "Loot":          { "OnDefeat": { "Rolls": [ ... ] }, "QueueIfOffline": true },
 *   "Feedback":      { "Defeated": "Encounter_Defeated" },
 *   "Discovery":     { "MapMarker": true }
 * }
 * }</pre>
 *
 * <p><b>Every group is optional and every leaf is nullable</b>, so a pack or an owner overlay that
 * writes one leaf keeps the rest, and a script with no row at all still runs, announces and credits
 * under the library's structural defaults. {@code Enabled: false} switches the whole binding off:
 * the library then neither spawns, binds, scales nor pays that encounter, which is how an owner
 * takes a boss out of rotation without touching its script.
 *
 * <p>Override a row by id from a later pack, or leaf by leaf from
 * {@code mods/ziggfreedcommon/encounters.json}; reuse one with a top-level {@code "Parent"}.
 */
public final class EncounterBindingAsset
        implements JsonAssetWithMap<String, DefaultAssetMap<String, EncounterBindingAsset>> {

    /** Where these are authored. One folder, one file per binding, the file name being the id. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/Encounters";

    /** The target slot a subject is looked up in when a row names none. */
    public static final String DEFAULT_TARGET_SLOT = "Boss";

    /** The moment ids the four feedback leaves read as when unauthored. */
    public static final String DEFAULT_ENGAGED_MOMENT = "Encounter_Engaged";
    public static final String DEFAULT_PHASE_MOMENT = "Encounter_Phase_Changed";
    public static final String DEFAULT_DEFEATED_MOMENT = "Encounter_Defeated";
    public static final String DEFAULT_WIPED_MOMENT = "Encounter_Wiped";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable private String encounterAsset;
    @Nullable private String nameKey;
    @Nullable private Boolean enabled;
    @Nullable private Subject subject;
    @Nullable private Participation participation;
    @Nullable private Scale scale;
    @Nullable private Timing timing;
    @Nullable private Loot loot;
    @Nullable private Leaderboard leaderboard;
    @Nullable private Progression progression;
    @Nullable private Feedback feedback;
    @Nullable private Discovery discovery;

    public static final AssetBuilderCodec<String, EncounterBindingAsset> CODEC = AssetBuilderCodec.builder(
                    EncounterBindingAsset.class,
                    EncounterBindingAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("EncounterAsset", Codec.STRING, false),
                    (a, v) -> a.encounterAsset = v, a -> a.encounterAsset,
                    (a, p) -> a.encounterAsset = p.encounterAsset)
            .documentation("The native encounter script this row binds: the file name under "
                    + "Server/EncounterManager. Omit it and this row's own file name is the script id.")
            .add()
            .appendInherited(new KeyedCodec<>("NameKey", Codec.STRING, false),
                    (a, v) -> a.nameKey = v, a -> a.nameKey, (a, p) -> a.nameKey = p.nameKey)
            .documentation("A lang key naming the fight, read on every player's own client: it names "
                    + "the map marker and rides every event. Omit it and the subject's own display "
                    + "name is used.")
            .add()
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Set false to switch this binding off: the library then neither spawns, "
                    + "binds, scales nor pays this encounter. The owner file flips it per id without "
                    + "touching the script.")
            .add()
            .appendInherited(new KeyedCodec<>("Subject", Subject.CODEC, false),
                    (a, v) -> a.subject = v, a -> a.subject, (a, p) -> a.subject = p.subject)
            .documentation("Which of the script's own target slots holds the boss.")
            .add()
            .appendInherited(new KeyedCodec<>("Participation", Participation.CODEC, false),
                    (a, v) -> a.participation = v, a -> a.participation,
                    (a, p) -> a.participation = p.participation)
            .documentation("Credit rules for THIS encounter only, overriding leaf by leaf the "
                    + "EncounterParticipation row that matches its subject. Leave it out to take the "
                    + "matched row as it is.")
            .add()
            .appendInherited(new KeyedCodec<>("Scale", Scale.CODEC, false),
                    (a, v) -> a.scale = v, a -> a.scale, (a, p) -> a.scale = p.scale)
            .documentation("How the subject's maximum health grows with the party and the region.")
            .add()
            .appendInherited(new KeyedCodec<>("Timing", Timing.CODEC, false),
                    (a, v) -> a.timing = v, a -> a.timing, (a, p) -> a.timing = p.timing)
            .documentation("The guards AROUND the fight: how long an unannounced engage waits, how long "
                    + "an empty arena counts as a wipe, how long a run may last at most. Pacing INSIDE "
                    + "the fight is the script's own Timers.")
            .add()
            .appendInherited(new KeyedCodec<>("Loot", Loot.CODEC, false),
                    (a, v) -> a.loot = v, a -> a.loot, (a, p) -> a.loot = p.loot)
            .documentation("What the defeat, and any phase, pays out.")
            .add()
            .appendInherited(new KeyedCodec<>("Leaderboard", Leaderboard.CODEC, false),
                    (a, v) -> a.leaderboard = v, a -> a.leaderboard, (a, p) -> a.leaderboard = p.leaderboard)
            .documentation("Which leaderboard bucket a defeat writes, and how it is split.")
            .add()
            .appendInherited(new KeyedCodec<>("Progression", Progression.CODEC, false),
                    (a, v) -> a.progression = v, a -> a.progression, (a, p) -> a.progression = p.progression)
            .documentation("What a quest or achievement step naming this encounter can qualify on.")
            .add()
            .appendInherited(new KeyedCodec<>("Feedback", Feedback.CODEC, false),
                    (a, v) -> a.feedback = v, a -> a.feedback, (a, p) -> a.feedback = p.feedback)
            .documentation("The FeedbackMoment ids fired to each member at the four beats of a run.")
            .add()
            .appendInherited(new KeyedCodec<>("Discovery", Discovery.CODEC, false),
                    (a, v) -> a.discovery = v, a -> a.discovery, (a, p) -> a.discovery = p.discovery)
            .documentation("A world-map marker over the subject while the fight is on.")
            .add()
            .build();

    public EncounterBindingAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** The script this row binds: the authored id, else this row's own id. */
    @Nonnull
    public String encounterAsset() {
        return encounterAsset == null || encounterAsset.isBlank() ? id : encounterAsset.trim();
    }

    @Nullable
    public String getNameKey() {
        return nameKey == null || nameKey.isBlank() ? null : nameKey;
    }

    /** Whether the library acts on this binding at all; unauthored means yes. */
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    @Nullable
    public Subject getSubject() {
        return subject;
    }

    @Nullable
    public Participation getParticipation() {
        return participation;
    }

    @Nullable
    public Scale getScale() {
        return scale;
    }

    @Nullable
    public Timing getTiming() {
        return timing;
    }

    @Nullable
    public Loot getLoot() {
        return loot;
    }

    @Nullable
    public Leaderboard getLeaderboard() {
        return leaderboard;
    }

    @Nullable
    public Progression getProgression() {
        return progression;
    }

    @Nullable
    public Feedback getFeedback() {
        return feedback;
    }

    @Nullable
    public Discovery getDiscovery() {
        return discovery;
    }

    // ==================== Subject ====================

    /** Which of the script's target slots holds the boss. */
    public static final class Subject {

        @Nullable protected String targetSlot;
        @Nullable protected Boolean anyOccupiedSlot;

        public static final BuilderCodec<Subject> CODEC = BuilderCodec.builder(Subject.class, Subject::new)
                .appendInherited(new KeyedCodec<>("TargetSlot", Codec.STRING, false),
                        (o, v) -> o.targetSlot = v, o -> o.targetSlot, (o, p) -> o.targetSlot = p.targetSlot)
                .metadata(EditorSchema.defaultValue(DEFAULT_TARGET_SLOT))
                .documentation("The script's own TargetSlot name the boss is bound into (the slot its "
                        + "Target sensor and TriggerSpawners name). Unauthored reads as Boss.")
                .add()
                .appendInherited(new KeyedCodec<>("AnyOccupiedSlot", Codec.BOOLEAN, false),
                        (o, v) -> o.anyOccupiedSlot = v, o -> o.anyOccupiedSlot,
                        (o, p) -> o.anyOccupiedSlot = p.anyOccupiedSlot)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("Set true to fall back to the first slot holding any entity when the "
                        + "named slot is empty, for a script that binds its boss under a slot name of "
                        + "its own.")
                .add()
                .build();

        public Subject() {
        }

        @Nonnull
        public String targetSlot() {
            return targetSlot == null || targetSlot.isBlank() ? DEFAULT_TARGET_SLOT : targetSlot.trim();
        }

        public boolean anyOccupiedSlot() {
            return anyOccupiedSlot != null && anyOccupiedSlot;
        }
    }

    // ==================== Participation ====================

    /**
     * The per-encounter override of the matched participation rule: the same leaves as
     * {@link EncounterParticipationAsset}, minus the selectors, each replacing the matched rule's
     * leaf when authored.
     */
    public static final class Participation {

        @Nullable protected FactorFormula damageDealt;
        @Nullable protected FactorFormula damageTaken;
        @Nullable protected FactorFormula presence;
        @Nullable protected Double minShare;
        @Nullable protected Boolean creditDead;
        @Nullable protected Boolean creditDisconnected;

        public static final BuilderCodec<Participation> CODEC =
                BuilderCodec.builder(Participation.class, Participation::new)
                        .appendInherited(new KeyedCodec<>("DamageDealt",
                                        FactorFormula.numberOrGroup(EditorDataSets.FACTORS), false),
                                (o, v) -> o.damageDealt = v, o -> o.damageDealt,
                                (o, p) -> o.damageDealt = p.damageDealt)
                        .documentation("The weight of one point of damage dealt to the subject, a bare "
                                + "number or a factor formula read about each player.")
                        .add()
                        .appendInherited(new KeyedCodec<>("DamageTaken",
                                        FactorFormula.numberOrGroup(EditorDataSets.FACTORS), false),
                                (o, v) -> o.damageTaken = v, o -> o.damageTaken,
                                (o, p) -> o.damageTaken = p.damageTaken)
                        .documentation("The weight of one point of damage a member took while inside.")
                        .add()
                        .appendInherited(new KeyedCodec<>("Presence",
                                        FactorFormula.numberOrGroup(EditorDataSets.FACTORS), false),
                                (o, v) -> o.presence = v, o -> o.presence, (o, p) -> o.presence = p.presence)
                        .documentation("The weight of one second spent as a member of the fight.")
                        .add()
                        .appendInherited(new KeyedCodec<>("MinShare", Codec.DOUBLE, false),
                                (o, v) -> o.minShare = v, o -> o.minShare, (o, p) -> o.minShare = p.minShare)
                        .documentation("The share, 0 to 1 of the top contributor's, under which a "
                                + "participant is credited with the attempt but paid nothing.")
                        .add()
                        .appendInherited(new KeyedCodec<>("CreditDead", Codec.BOOLEAN, false),
                                (o, v) -> o.creditDead = v, o -> o.creditDead,
                                (o, p) -> o.creditDead = p.creditDead)
                        .documentation("Whether a member who died during the fight keeps their share.")
                        .add()
                        .appendInherited(new KeyedCodec<>("CreditDisconnected", Codec.BOOLEAN, false),
                                (o, v) -> o.creditDisconnected = v, o -> o.creditDisconnected,
                                (o, p) -> o.creditDisconnected = p.creditDisconnected)
                        .documentation("Whether a member who is offline at the payout keeps their "
                                + "share, paid on their next connect.")
                        .add()
                        .build();

        public Participation() {
        }

        @Nullable
        public FactorFormula getDamageDealt() {
            return damageDealt;
        }

        @Nullable
        public FactorFormula getDamageTaken() {
            return damageTaken;
        }

        @Nullable
        public FactorFormula getPresence() {
            return presence;
        }

        @Nullable
        public Double getMinShare() {
            return minShare;
        }

        @Nullable
        public Boolean getCreditDead() {
            return creditDead;
        }

        @Nullable
        public Boolean getCreditDisconnected() {
            return creditDisconnected;
        }
    }

    // ==================== Scale ====================

    /** How the subject's maximum health grows: one multiplicative modifier, keyed, applied once. */
    public static final class Scale {

        public static final double DEFAULT_HEALTH_PER_MEMBER = 0.0;
        public static final double DEFAULT_HEALTH_MULTIPLIER = 1.0;
        public static final double DEFAULT_HEALTH_PER_POWER_POINT = 0.0;
        public static final double DEFAULT_MAX_HEALTH_MULTIPLIER = 5.0;

        @Nullable protected Double healthPerMember;
        @Nullable protected Double healthMultiplier;
        @Nullable protected Double healthPerPowerPoint;
        @Nullable protected Double maxHealthMultiplier;
        @Nullable protected Boolean reconcileOnPhase;

        public static final BuilderCodec<Scale> CODEC = BuilderCodec.builder(Scale.class, Scale::new)
                .appendInherited(new KeyedCodec<>("HealthPerMember", Codec.DOUBLE, false),
                        (o, v) -> o.healthPerMember = v, o -> o.healthPerMember,
                        (o, p) -> o.healthPerMember = p.healthPerMember)
                .metadata(EditorSchema.defaultValue(DEFAULT_HEALTH_PER_MEMBER))
                .documentation("Extra maximum health per member beyond the first, as a fraction of "
                        + "the base: 0.35 makes a four-member fight 2.05 times the health.")
                .add()
                .appendInherited(new KeyedCodec<>("HealthMultiplier", Codec.DOUBLE, false),
                        (o, v) -> o.healthMultiplier = v, o -> o.healthMultiplier,
                        (o, p) -> o.healthMultiplier = p.healthMultiplier)
                .metadata(EditorSchema.defaultValue(DEFAULT_HEALTH_MULTIPLIER))
                .documentation("A flat multiplier on the whole result, composed with whatever the "
                        + "spawn call itself asked for.")
                .add()
                .appendInherited(new KeyedCodec<>("HealthPerPowerPoint", Codec.DOUBLE, false),
                        (o, v) -> o.healthPerPowerPoint = v, o -> o.healthPerPowerPoint,
                        (o, p) -> o.healthPerPowerPoint = p.healthPerPowerPoint)
                .metadata(EditorSchema.defaultValue(DEFAULT_HEALTH_PER_POWER_POINT))
                .documentation("Extra health per point of the party's aggregated power, when a "
                        + "companion mod answers power for the fight's world. Zero ignores power.")
                .add()
                .appendInherited(new KeyedCodec<>("MaxHealthMultiplier", Codec.DOUBLE, false),
                        (o, v) -> o.maxHealthMultiplier = v, o -> o.maxHealthMultiplier,
                        (o, p) -> o.maxHealthMultiplier = p.maxHealthMultiplier)
                .metadata(EditorSchema.defaultValue(DEFAULT_MAX_HEALTH_MULTIPLIER))
                .documentation("The ceiling on the whole multiplier; the floor is always 1.")
                .add()
                .appendInherited(new KeyedCodec<>("ReconcileOnPhase", Codec.BOOLEAN, false),
                        (o, v) -> o.reconcileOnPhase = v, o -> o.reconcileOnPhase,
                        (o, p) -> o.reconcileOnPhase = p.reconcileOnPhase)
                .metadata(EditorSchema.defaultValue(true))
                .documentation("Re-apply the multiplier after each phase signal, because an in-place "
                        + "role change rolls the new role's own maximum health. Unauthored means yes.")
                .add()
                .build();

        public Scale() {
        }

        public double healthPerMember() {
            return healthPerMember == null ? DEFAULT_HEALTH_PER_MEMBER : healthPerMember;
        }

        public double healthMultiplier() {
            return healthMultiplier == null ? DEFAULT_HEALTH_MULTIPLIER : healthMultiplier;
        }

        public double healthPerPowerPoint() {
            return healthPerPowerPoint == null ? DEFAULT_HEALTH_PER_POWER_POINT : healthPerPowerPoint;
        }

        public double maxHealthMultiplier() {
            return maxHealthMultiplier == null ? DEFAULT_MAX_HEALTH_MULTIPLIER : maxHealthMultiplier;
        }

        public boolean reconcileOnPhase() {
            return reconcileOnPhase == null || reconcileOnPhase;
        }
    }

    // ==================== Timing ====================

    /** The guards around the fight, in seconds; pacing inside it belongs to the script. */
    public static final class Timing {

        public static final int DEFAULT_ENGAGE_GRACE_SECONDS = 10;
        public static final int DEFAULT_WIPE_GRACE_SECONDS = 15;
        public static final int DEFAULT_MAX_RUN_SECONDS = 3600;

        @Nullable protected Integer engageGraceSeconds;
        @Nullable protected Integer wipeGraceSeconds;
        @Nullable protected Integer maxRunSeconds;

        public static final BuilderCodec<Timing> CODEC = BuilderCodec.builder(Timing.class, Timing::new)
                .appendInherited(new KeyedCodec<>("EngageGraceSeconds", Codec.INTEGER, false),
                        (o, v) -> o.engageGraceSeconds = v, o -> o.engageGraceSeconds,
                        (o, p) -> o.engageGraceSeconds = p.engageGraceSeconds)
                .metadata(EditorSchema.defaultValue(DEFAULT_ENGAGE_GRACE_SECONDS))
                .documentation("How long after the subject binds the library waits for the script's "
                        + "zc:engaged beat before counting the fight as engaged on its own.")
                .add()
                .appendInherited(new KeyedCodec<>("WipeGraceSeconds", Codec.INTEGER, false),
                        (o, v) -> o.wipeGraceSeconds = v, o -> o.wipeGraceSeconds,
                        (o, p) -> o.wipeGraceSeconds = p.wipeGraceSeconds)
                .metadata(EditorSchema.defaultValue(DEFAULT_WIPE_GRACE_SECONDS))
                .documentation("How long an engaged fight with no living member left counts as a "
                        + "wipe. A player running back inside the window keeps the run alive.")
                .add()
                .appendInherited(new KeyedCodec<>("MaxRunSeconds", Codec.INTEGER, false),
                        (o, v) -> o.maxRunSeconds = v, o -> o.maxRunSeconds,
                        (o, p) -> o.maxRunSeconds = p.maxRunSeconds)
                .metadata(EditorSchema.defaultValue(DEFAULT_MAX_RUN_SECONDS))
                .documentation("The longest a run may last once engaged; past it the run resets so a "
                        + "script that never concludes cannot hold credit forever.")
                .add()
                .build();

        public Timing() {
        }

        public int engageGraceSeconds() {
            return engageGraceSeconds == null ? DEFAULT_ENGAGE_GRACE_SECONDS : engageGraceSeconds;
        }

        public int wipeGraceSeconds() {
            return wipeGraceSeconds == null ? DEFAULT_WIPE_GRACE_SECONDS : wipeGraceSeconds;
        }

        public int maxRunSeconds() {
            return maxRunSeconds == null ? DEFAULT_MAX_RUN_SECONDS : maxRunSeconds;
        }
    }

    // ==================== Loot ====================

    /** What the fight pays, in the loot vocabulary every other drop site speaks. */
    public static final class Loot {

        @Nullable protected LootRef onDefeat;
        @Nullable protected Map<String, LootRef> onPhase;
        @Nullable protected Boolean toKiller;
        @Nullable protected Boolean queueIfOffline;

        public static final BuilderCodec<Loot> CODEC = BuilderCodec.builder(Loot.class, Loot::new)
                .appendInherited(new KeyedCodec<>("OnDefeat", LootRef.CODEC, false),
                        (o, v) -> o.onDefeat = v, o -> o.onDefeat, (o, p) -> o.onDefeat = p.onDefeat)
                .documentation("Rolled once PER credited participant when the subject dies, each roll "
                        + "kept with a chance equal to that participant's share, so the top "
                        + "contributor keeps everything they win and a marginal one keeps a little.")
                .add()
                .appendInherited(new KeyedCodec<>("OnPhase", new InheritMapCodec<>(LootRef.CODEC), false),
                        (o, v) -> o.onPhase = v, o -> o.onPhase, (o, p) -> o.onPhase = p.onPhase)
                .documentation("Keyed by the script's own state name: rolled once when that phase is "
                        + "signalled and dropped in the world at the subject.")
                .add()
                .appendInherited(new KeyedCodec<>("ToKiller", Codec.BOOLEAN, false),
                        (o, v) -> o.toKiller = v, o -> o.toKiller, (o, p) -> o.toKiller = p.toKiller)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("Also pay the player who landed the killing blow, at a full share, "
                        + "whatever their credited share was.")
                .add()
                .appendInherited(new KeyedCodec<>("QueueIfOffline", Codec.BOOLEAN, false),
                        (o, v) -> o.queueIfOffline = v, o -> o.queueIfOffline,
                        (o, p) -> o.queueIfOffline = p.queueIfOffline)
                .metadata(EditorSchema.defaultValue(true))
                .documentation("Hold a credited participant's payout for their next connect when they "
                        + "are offline at the defeat. False pays only whoever is present.")
                .add()
                .build();

        public Loot() {
        }

        @Nullable
        public LootRef getOnDefeat() {
            return onDefeat;
        }

        @Nullable
        public Map<String, LootRef> getOnPhase() {
            return onPhase;
        }

        public boolean toKiller() {
            return toKiller != null && toKiller;
        }

        public boolean queueIfOffline() {
            return queueIfOffline == null || queueIfOffline;
        }
    }

    // ==================== Leaderboard ====================

    /** Which leaderboard bucket a defeat writes, and how the bucket is split. */
    public static final class Leaderboard {

        @Nullable protected String bucket;
        @Nullable protected Boolean byPartySize;
        @Nullable protected Boolean byDifficulty;

        public static final BuilderCodec<Leaderboard> CODEC =
                BuilderCodec.builder(Leaderboard.class, Leaderboard::new)
                        .appendInherited(new KeyedCodec<>("Bucket", Codec.STRING, false),
                                (o, v) -> o.bucket = v, o -> o.bucket, (o, p) -> o.bucket = p.bucket)
                        .documentation("The leaderboard bucket a defeat records into. Unauthored records "
                                + "nothing.")
                        .add()
                        .appendInherited(new KeyedCodec<>("ByPartySize", Codec.BOOLEAN, false),
                                (o, v) -> o.byPartySize = v, o -> o.byPartySize,
                                (o, p) -> o.byPartySize = p.byPartySize)
                        .metadata(EditorSchema.defaultValue(false))
                        .documentation("Split the bucket by how many members the fight had.")
                        .add()
                        .appendInherited(new KeyedCodec<>("ByDifficulty", Codec.BOOLEAN, false),
                                (o, v) -> o.byDifficulty = v, o -> o.byDifficulty,
                                (o, p) -> o.byDifficulty = p.byDifficulty)
                        .metadata(EditorSchema.defaultValue(false))
                        .documentation("Split the bucket by the run's difficulty label.")
                        .add()
                        .build();

        public Leaderboard() {
        }

        @Nullable
        public String getBucket() {
            return bucket == null || bucket.isBlank() ? null : bucket;
        }

        public boolean byPartySize() {
            return byPartySize != null && byPartySize;
        }

        public boolean byDifficulty() {
            return byDifficulty != null && byDifficulty;
        }
    }

    // ==================== Progression ====================

    /** What a quest or achievement step naming this encounter can qualify on. */
    public static final class Progression {

        @Nullable protected String difficulty;

        public static final BuilderCodec<Progression> CODEC =
                BuilderCodec.builder(Progression.class, Progression::new)
                        .appendInherited(new KeyedCodec<>("Difficulty", Codec.STRING, false),
                                (o, v) -> o.difficulty = v, o -> o.difficulty,
                                (o, p) -> o.difficulty = p.difficulty)
                        .documentation("The difficulty label a run carries when the spawn call names "
                                + "none; a step's qualifier matches against it.")
                        .add()
                        .build();

        public Progression() {
        }

        @Nullable
        public String getDifficulty() {
            return difficulty == null || difficulty.isBlank() ? null : difficulty;
        }
    }

    // ==================== Feedback ====================

    /** The FeedbackMoment ids fired to each member at the four beats of a run. */
    public static final class Feedback {

        @Nullable protected String engaged;
        @Nullable protected String phaseChanged;
        @Nullable protected String defeated;
        @Nullable protected String wiped;

        public static final BuilderCodec<Feedback> CODEC = BuilderCodec.builder(Feedback.class, Feedback::new)
                .appendInherited(new KeyedCodec<>("Engaged", Codec.STRING, false),
                        (o, v) -> o.engaged = v, o -> o.engaged, (o, p) -> o.engaged = p.engaged)
                .metadata(EditorSchema.defaultValue(DEFAULT_ENGAGED_MOMENT))
                .documentation("The moment fired to each member when the fight engages.")
                .add()
                .appendInherited(new KeyedCodec<>("PhaseChanged", Codec.STRING, false),
                        (o, v) -> o.phaseChanged = v, o -> o.phaseChanged,
                        (o, p) -> o.phaseChanged = p.phaseChanged)
                .metadata(EditorSchema.defaultValue(DEFAULT_PHASE_MOMENT))
                .documentation("The moment fired to each member on every phase signal.")
                .add()
                .appendInherited(new KeyedCodec<>("Defeated", Codec.STRING, false),
                        (o, v) -> o.defeated = v, o -> o.defeated, (o, p) -> o.defeated = p.defeated)
                .metadata(EditorSchema.defaultValue(DEFAULT_DEFEATED_MOMENT))
                .documentation("The moment fired to each credited participant at the defeat, carrying "
                        + "their own share.")
                .add()
                .appendInherited(new KeyedCodec<>("Wiped", Codec.STRING, false),
                        (o, v) -> o.wiped = v, o -> o.wiped, (o, p) -> o.wiped = p.wiped)
                .metadata(EditorSchema.defaultValue(DEFAULT_WIPED_MOMENT))
                .documentation("The moment fired to each participant when the fight is lost.")
                .add()
                .build();

        public Feedback() {
        }

        @Nonnull
        public String engaged() {
            return orDefault(engaged, DEFAULT_ENGAGED_MOMENT);
        }

        @Nonnull
        public String phaseChanged() {
            return orDefault(phaseChanged, DEFAULT_PHASE_MOMENT);
        }

        @Nonnull
        public String defeated() {
            return orDefault(defeated, DEFAULT_DEFEATED_MOMENT);
        }

        @Nonnull
        public String wiped() {
            return orDefault(wiped, DEFAULT_WIPED_MOMENT);
        }
    }

    // ==================== Discovery ====================

    /** A world-map marker that follows the subject while the fight is on. */
    public static final class Discovery {

        public static final String DEFAULT_MARKER_ICON = "Home.png";
        public static final int DEFAULT_FOLLOW_SECONDS = 3;

        @Nullable protected Boolean mapMarker;
        @Nullable protected String markerIcon;
        @Nullable protected Integer followSeconds;
        @Nullable protected Boolean compassUpdating;

        public static final BuilderCodec<Discovery> CODEC = BuilderCodec.builder(Discovery.class, Discovery::new)
                .appendInherited(new KeyedCodec<>("MapMarker", Codec.BOOLEAN, false),
                        (o, v) -> o.mapMarker = v, o -> o.mapMarker, (o, p) -> o.mapMarker = p.mapMarker)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("Place a world-map marker at the subject once the fight engages, and "
                        + "take it down when the run resets.")
                .add()
                .appendInherited(new KeyedCodec<>("MarkerIcon", Codec.STRING, false),
                        (o, v) -> o.markerIcon = v, o -> o.markerIcon, (o, p) -> o.markerIcon = p.markerIcon)
                .metadata(EditorSchema.defaultValue(DEFAULT_MARKER_ICON))
                .documentation("The client map-marker texture, e.g. Home.png or Portal.png.")
                .add()
                .appendInherited(new KeyedCodec<>("FollowSeconds", Codec.INTEGER, false),
                        (o, v) -> o.followSeconds = v, o -> o.followSeconds,
                        (o, p) -> o.followSeconds = p.followSeconds)
                .metadata(EditorSchema.defaultValue(DEFAULT_FOLLOW_SECONDS))
                .documentation("How often the marker moves to the subject's current position. Zero "
                        + "places it once, where the fight engaged.")
                .add()
                .appendInherited(new KeyedCodec<>("CompassUpdating", Codec.BOOLEAN, false),
                        (o, v) -> o.compassUpdating = v, o -> o.compassUpdating,
                        (o, p) -> o.compassUpdating = p.compassUpdating)
                .metadata(EditorSchema.defaultValue(true))
                .documentation("Whether the marker keeps following while players are far away; false "
                        + "pins it where it was last seen up close.")
                .add()
                .build();

        public Discovery() {
        }

        public boolean mapMarker() {
            return mapMarker != null && mapMarker;
        }

        @Nonnull
        public String markerIcon() {
            return orDefault(markerIcon, DEFAULT_MARKER_ICON);
        }

        public int followSeconds() {
            return followSeconds == null || followSeconds < 0 ? DEFAULT_FOLLOW_SECONDS : followSeconds;
        }

        public boolean compassUpdating() {
            return compassUpdating == null || compassUpdating;
        }
    }

    @Nonnull
    private static String orDefault(@Nullable String value, @Nonnull String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
