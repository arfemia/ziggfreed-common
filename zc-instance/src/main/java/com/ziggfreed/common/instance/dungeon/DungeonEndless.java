package com.ziggfreed.common.instance.dungeon;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.codec.InheritMapCodec;
import com.ziggfreed.common.codec.Vec3;
import com.ziggfreed.common.encounter.wave.WaveRamp;
import com.ziggfreed.common.encounter.wave.WaveRules;
import com.ziggfreed.common.instance.claims.RewardChestAsset;
import com.ziggfreed.common.loot.LootableAsset;

/**
 * A dungeon's endless wave run, its {@code Run.Endless} group: one run per visit whose reach is its difficulty, the
 * waves never running out, a boss wave every few, and a chest that grows with every wave cleared.
 *
 * <pre>{@code
 * "Run": { "Endless": {
 *   "BossEvery": 5,
 *   "Bands": { "Bones": { "Slot": 1 }, "Rotten": { "Slot": 2 }, "Void": { "Slot": 3 } },
 *   "Sizes": { "Max": 3, "Every": 1 },
 *   "Ramp": { "HealthPerWave": 0.08, "DamagePerWave": 0.04, "HealthPerReturn": 0.25, "DamagePerReturn": 0.1,
 *             "Max": 4.0 },
 *   "Chest": { "PerWave": "MyPack_Crypt_Wave", "PerBossWave": "MyPack_Crypt_Boss_Wave" } } }
 * }</pre>
 *
 * <p>The fight is the prefab's encounter script, a Variant of the library's {@code Zc_Encounter_Endless} whose band
 * slots and boss slot say what rises; this group says when each takes its turn ({@link WaveRules}), how much tougher
 * each wave is ({@link WaveRamp}), and what a cleared wave banks. A dungeon with this group is endless: nothing in
 * {@code Complete} clears its runs, and a run lasts until every member in the area is down at once, its time limit
 * passes, or everyone has left. Each member banks every wave cleared while they stand in the area; a member who
 * leaves collects theirs as they go, and the rest collect theirs from a chest where they came in when the run ends.
 * Full pay once a window, then less, by the dungeon's {@code Rewards.Diminish}.
 *
 * <p>Every leaf is nullable and inherits under native {@code Parent}; {@code Bands} merges by name.
 */
public final class DungeonEndless {

    /** The reward chest a run's end stands when the group names none: the library's neutral one. */
    public static final String DEFAULT_CHEST = RewardChestAsset.REWARD_CHEST;

    /** How many mob waves one band raises in a row with no boss waves and nothing written. */
    public static final int DEFAULT_BAND_WAVES = 4;

    /** How far in front of where a player arrives the chest stands when no offset is written, in blocks. */
    public static final double DEFAULT_CHEST_AHEAD = 2.0;

    @Nullable protected Integer bossEvery;
    @Nullable protected Integer bandWaves;
    @Nullable protected Map<String, Band> bands;
    @Nullable protected Sizes sizes;
    @Nullable protected Ramp ramp;
    @Nullable protected Chest chest;

    public static final BuilderCodec<DungeonEndless> CODEC = BuilderCodec.builder(DungeonEndless.class,
                    DungeonEndless::new)
            .appendInherited(new KeyedCodec<>("BossEvery", Codec.INTEGER, false),
                    (o, v) -> o.bossEvery = v, o -> o.bossEvery, (o, p) -> o.bossEvery = p.bossEvery)
            .documentation("Which waves are boss waves: every this many, 2 or more (5 makes the fifth, the tenth and "
                    + "so on boss waves, raised by the script's BossWave slot, each return stronger by the Ramp's "
                    + "per-return steps). Unauthored means no boss waves; under 2 is reported and refuses entry.")
            .add()
            .appendInherited(new KeyedCodec<>("BandWaves", Codec.INTEGER, false),
                    (o, v) -> o.bandWaves = v, o -> o.bandWaves, (o, p) -> o.bandWaves = p.bandWaves)
            .documentation("How many mob waves one band raises in a row before the next band takes its turn, 1 or "
                    + "more. Unauthored means the waves between two boss waves (BossEvery less 1), or 4 with no boss "
                    + "waves.").add()
            .appendInherited(new KeyedCodec<>("Bands", new InheritMapCodec<>(Band.CODEC), false),
                    (o, v) -> o.bands = v, o -> o.bands, (o, p) -> o.bands = p.bands)
            .documentation("The wave pool, keyed by a name of your own for each band: which band slot of the "
                    + "encounter script (Band1 to Band4) raises it. The bands take turns in the order of their "
                    + "slots, and once every band has had its turn they start again one size bigger (Sizes). An "
                    + "endless run with no band is reported and refuses entry. Merges by name under Parent; a Slot "
                    + "of 0 takes an inherited band out.").add()
            .appendInherited(new KeyedCodec<>("Sizes", Sizes.CODEC, false),
                    (o, v) -> o.sizes = v, o -> o.sizes, (o, p) -> o.sizes = p.sizes)
            .documentation("How the waves grow in number: each time the bands start again, a size bigger, up to "
                    + "Max. A size is how many times the band's slot rises at once.").add()
            .appendInherited(new KeyedCodec<>("Ramp", Ramp.CODEC, false),
                    (o, v) -> o.ramp = v, o -> o.ramp, (o, p) -> o.ramp = p.ramp)
            .documentation("How much tougher each wave is: health and damage a step per wave, a boss a step per "
                    + "return, each held to Max. It applies on top of the encounter's own Scale.Adds and of any mob "
                    + "scaling mod. Unauthored, every wave is as its roles make it.").add()
            .appendInherited(new KeyedCodec<>("Chest", Chest.CODEC, false),
                    (o, v) -> o.chest = v, o -> o.chest, (o, p) -> o.chest = p.chest)
            .documentation("What each cleared wave banks into a member's chest, and the chest the run's end "
                    + "stands.").add()
            .build();

    public DungeonEndless() {
    }

    /** {@code BossEvery} as written, or null when unauthored. */
    @Nullable
    public Integer bossEveryAuthored() {
        return bossEvery;
    }

    /** {@code BossEvery}, or null for no boss waves (unauthored, or under 2, which the audit reports). */
    @Nullable
    public Integer bossEvery() {
        return bossEvery == null || bossEvery < WaveRules.MIN_BOSS_EVERY ? null : bossEvery;
    }

    /** {@code BandWaves}, at least 1: unauthored, the waves between two boss waves, or 4 with none. */
    public int bandWaves() {
        if (bandWaves != null) {
            return Math.max(1, bandWaves);
        }
        Integer every = bossEvery();
        return every == null ? DEFAULT_BAND_WAVES : Math.max(1, every - 1);
    }

    /** The band slots that take turns, each 1 to {@link WaveRules#MAX_SLOTS} and once, in slot order. */
    @Nonnull
    public List<Integer> bandSlots() {
        TreeSet<Integer> slots = new TreeSet<>();
        if (bands != null) {
            for (Band band : bands.values()) {
                if (band != null && band.slot != null && band.slot >= 1 && band.slot <= WaveRules.MAX_SLOTS) {
                    slots.add(band.slot);
                }
            }
        }
        return List.copyOf(slots);
    }

    /** Each band's name with the slot it names as written (null when it names none), for the audit. */
    @Nonnull
    public Map<String, Integer> bandsAuthored() {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (bands != null) {
            bands.forEach((name, band) -> out.put(name, band == null ? null : band.slot));
        }
        return out;
    }

    /** {@code Sizes.Max}, 1 to {@link WaveRules#MAX_SIZE}; 1 unauthored. */
    public int sizeMax() {
        Integer v = sizes == null ? null : sizes.max;
        return v == null ? 1 : Math.max(1, Math.min(WaveRules.MAX_SIZE, v));
    }

    /** {@code Sizes.Every}, at least 1; 1 unauthored. */
    public int sizeEvery() {
        Integer v = sizes == null ? null : sizes.every;
        return v == null ? 1 : Math.max(1, v);
    }

    /** Whether {@code Ramp} writes a step above zero. */
    public boolean rampSteps() {
        return ramp != null && (positive(ramp.healthPerWave) || positive(ramp.damagePerWave)
                || positive(ramp.healthPerReturn) || positive(ramp.damagePerReturn));
    }

    /** {@code Ramp.Max} as written, or null. */
    @Nullable
    public Double rampMax() {
        return ramp == null ? null : ramp.max;
    }

    /**
     * The ramp as it applies: each per-return step falling back to its per-wave one, held to {@code Max}; none when
     * the group writes no step, and a ramp with steps but no {@code Max} held to the library's ceiling (the audit
     * reports it, and the dungeon refuses entry until it names one).
     */
    @Nonnull
    public WaveRamp ramp() {
        if (!rampSteps()) {
            return WaveRamp.NONE;
        }
        double perWaveHealth = orZero(ramp.healthPerWave);
        double perWaveDamage = orZero(ramp.damagePerWave);
        return new WaveRamp(perWaveHealth, perWaveDamage,
                ramp.healthPerReturn == null ? perWaveHealth : ramp.healthPerReturn,
                ramp.damagePerReturn == null ? perWaveDamage : ramp.damagePerReturn,
                ramp.max == null ? WaveRamp.CEILING : ramp.max);
    }

    /** The run's schedule and ramp, or null when no band slot is named, so nothing could rise. */
    @Nullable
    public WaveRules rules() {
        List<Integer> slots = bandSlots();
        if (slots.isEmpty()) {
            return null;
        }
        return new WaveRules(bossEvery(), bandWaves(), slots, sizeMax(), sizeEvery(), ramp());
    }

    /** {@code Chest.PerWave}, the loot table each cleared mob wave rolls into the chest, or null. */
    @Nullable
    public String perWaveLootable() {
        return blankToNull(chest == null ? null : chest.perWave);
    }

    /** {@code Chest.PerBossWave}, the table each cleared boss wave rolls, else {@link #perWaveLootable()}. */
    @Nullable
    public String perBossWaveLootable() {
        String boss = blankToNull(chest == null ? null : chest.perBossWave);
        return boss != null ? boss : perWaveLootable();
    }

    /** {@code Chest.PerBossWave} as written, or null. */
    @Nullable
    public String perBossWaveAuthored() {
        return blankToNull(chest == null ? null : chest.perBossWave);
    }

    /** {@code Chest.File}, the reward chest the run's end stands; {@link #DEFAULT_CHEST} unauthored. */
    @Nonnull
    public String chestFile() {
        String file = blankToNull(chest == null ? null : chest.file);
        return file == null ? DEFAULT_CHEST : file;
    }

    /** {@code Chest.Offset} as written, or null: the chest then stands in front of where a player arrives. */
    @Nullable
    public Vec3 chestOffset() {
        return chest == null ? null : chest.offset;
    }

    @Nullable
    private static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean positive(@Nullable Double value) {
        return value != null && Double.isFinite(value) && value > 0.0;
    }

    private static double orZero(@Nullable Double value) {
        return value == null || !Double.isFinite(value) ? 0.0 : value;
    }

    // ==================== Band ====================

    /** One band of the wave pool: the script slot that raises it. */
    public static final class Band {

        @Nullable protected Integer slot;

        public static final BuilderCodec<Band> CODEC = BuilderCodec.builder(Band.class, Band::new)
                .appendInherited(new KeyedCodec<>("Slot", Codec.INTEGER, false),
                        (o, v) -> o.slot = v, o -> o.slot, (o, p) -> o.slot = p.slot)
                .documentation("The encounter script's band slot that raises this band, 1 to 4 (Band1 to Band4 of "
                        + "Zc_Encounter_Endless). 0, or none, takes the band out.").add()
                .build();

        public Band() {
        }
    }

    // ==================== Sizes ====================

    /** How the waves grow in number. */
    public static final class Sizes {

        @Nullable protected Integer max;
        @Nullable protected Integer every;

        public static final BuilderCodec<Sizes> CODEC = BuilderCodec.builder(Sizes.class, Sizes::new)
                .appendInherited(new KeyedCodec<>("Max", Codec.INTEGER, false),
                        (o, v) -> o.max = v, o -> o.max, (o, p) -> o.max = p.max)
                .metadata(EditorSchema.defaultValue(1L))
                .documentation("The biggest size a wave reaches, 1 to 3: at size 2 a band's slot rises twice at "
                        + "once, at 3 three times. Unauthored means 1, every wave one rise.").add()
                .appendInherited(new KeyedCodec<>("Every", Codec.INTEGER, false),
                        (o, v) -> o.every = v, o -> o.every, (o, p) -> o.every = p.every)
                .metadata(EditorSchema.defaultValue(1L))
                .documentation("How many times the bands go round before the size steps up, 1 or more. "
                        + "Unauthored means 1: each time they start again.").add()
                .build();

        public Sizes() {
        }
    }

    // ==================== Ramp ====================

    /** How much tougher each wave is. */
    public static final class Ramp {

        @Nullable protected Double healthPerWave;
        @Nullable protected Double damagePerWave;
        @Nullable protected Double healthPerReturn;
        @Nullable protected Double damagePerReturn;
        @Nullable protected Double max;

        public static final BuilderCodec<Ramp> CODEC = BuilderCodec.builder(Ramp.class, Ramp::new)
                .appendInherited(new KeyedCodec<>("HealthPerWave", Codec.DOUBLE, false),
                        (o, v) -> o.healthPerWave = v, o -> o.healthPerWave, (o, p) -> o.healthPerWave = p.healthPerWave)
                .metadata(EditorSchema.defaultValue(0.0))
                .documentation("Extra maximum health per wave after the first, as a fraction: 0.08 makes the "
                        + "eleventh wave's creatures 1.8 times as tough as the first's. Unauthored means 0.").add()
                .appendInherited(new KeyedCodec<>("DamagePerWave", Codec.DOUBLE, false),
                        (o, v) -> o.damagePerWave = v, o -> o.damagePerWave, (o, p) -> o.damagePerWave = p.damagePerWave)
                .metadata(EditorSchema.defaultValue(0.0))
                .documentation("Extra damage per wave after the first, as a fraction, on everything the creature's "
                        + "hits deal. Unauthored means 0.").add()
                .appendInherited(new KeyedCodec<>("HealthPerReturn", Codec.DOUBLE, false),
                        (o, v) -> o.healthPerReturn = v, o -> o.healthPerReturn,
                        (o, p) -> o.healthPerReturn = p.healthPerReturn)
                .documentation("Extra maximum health of a boss wave per return after the first, as a fraction: 0.25 "
                        + "makes the third boss wave 1.5 times the first. Unauthored means HealthPerWave.").add()
                .appendInherited(new KeyedCodec<>("DamagePerReturn", Codec.DOUBLE, false),
                        (o, v) -> o.damagePerReturn = v, o -> o.damagePerReturn,
                        (o, p) -> o.damagePerReturn = p.damagePerReturn)
                .documentation("Extra damage of a boss wave per return after the first, as a fraction. Unauthored "
                        + "means DamagePerWave.").add()
                .appendInherited(new KeyedCodec<>("Max", Codec.DOUBLE, false),
                        (o, v) -> o.max = v, o -> o.max, (o, p) -> o.max = p.max)
                .documentation("The most either multiplier reaches, 1 to 10: 4 stops a wave's health and its damage "
                        + "at four times the first wave's. A ramp with a step and no Max is reported and refuses "
                        + "entry.").add()
                .build();

        public Ramp() {
        }
    }

    // ==================== Chest ====================

    /** What each cleared wave banks, and the chest a run's end stands. */
    public static final class Chest {

        @Nullable protected String perWave;
        @Nullable protected String perBossWave;
        @Nullable protected String file;
        @Nullable protected Vec3 offset;

        public static final BuilderCodec<Chest> CODEC = BuilderCodec.builder(Chest.class, Chest::new)
                .appendInherited(new KeyedCodec<>("PerWave", Codec.STRING, false),
                        (o, v) -> o.perWave = v, o -> o.perWave, (o, p) -> o.perWave = p.perWave)
                .metadata(EditorSchema.assetRef(LootableAsset.class))
                .documentation("The loot table each cleared mob wave rolls once for each member standing in the "
                        + "area, banked into their chest (a file under Server/ZiggfreedCommon/Lootables/). "
                        + "Unauthored, a mob wave banks nothing.").add()
                .appendInherited(new KeyedCodec<>("PerBossWave", Codec.STRING, false),
                        (o, v) -> o.perBossWave = v, o -> o.perBossWave, (o, p) -> o.perBossWave = p.perBossWave)
                .metadata(EditorSchema.assetRef(LootableAsset.class))
                .documentation("The loot table each cleared boss wave rolls in the same way. Unauthored means "
                        + "PerWave.").add()
                .appendInherited(new KeyedCodec<>("File", Codec.STRING, false),
                        (o, v) -> o.file = v, o -> o.file, (o, p) -> o.file = p.file)
                .metadata(EditorSchema.defaultValue(DEFAULT_CHEST))
                .documentation("The reward chest (a file under Server/ZiggfreedCommon/RewardChests/) the run's end "
                        + "stands for the members still in the area, holding what each banked. Unauthored means "
                        + "Zc_Reward_Chest.").add()
                .appendInherited(new KeyedCodec<>("Offset", Vec3.CODEC, false),
                        (o, v) -> o.offset = v, o -> o.offset, (o, p) -> o.offset = p.offset)
                .documentation("Where that chest stands, in blocks from the spot the prefab is pasted at. "
                        + "Unauthored, two blocks in front of where a player arrives.").add()
                .build();

        public Chest() {
        }
    }
}
