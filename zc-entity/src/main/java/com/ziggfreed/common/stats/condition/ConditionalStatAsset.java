package com.ziggfreed.common.stats.condition;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.server.core.modules.entity.condition.Condition;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.ziggfreed.common.asset.EditorSchema;

/**
 * What makes a native entity stat a CONDITIONAL one (R757): the stat ({@code Stat}, else the file's own id) is an
 * ordinary {@code EntityStatType} that gear, an effect or a pick raises like any other, and this file says when it
 * counts and what it pays into. The library ships the stats and their files for its own families
 * ({@code Damage_VsUndead}, {@code DamageResist_All_Crowd}); a consumer ships its own the same way.
 *
 * <pre>{@code
 * // Server/ZiggfreedCommon/ConditionalStats/Damage_WhileLoneFoe.json
 * { "Holder": [ { "Id": "ZigFoesNear", "Radius": 10, "Min": 1, "Max": 1 } ],
 *   "Pays": { "Damage": { "Types": [ "*" ] } } }
 * // Server/ZiggfreedCommon/ConditionalStats/Damage_VsUndead.json
 * { "Target": [ { "Id": "ZigFoeFamily", "Groups": [ "Zig_Foe_Undead" ] } ],
 *   "Max": 50,
 *   "Pays": { "Damage": {} } }
 * }</pre>
 *
 * <p><b>The conditions.</b> {@code Holder} is a list of the engine's own entity conditions (the ones an effect's
 * {@code ApplyConditions} take, the library's {@code ZigFoesNear}, {@code ZigNight} and the others among them), every
 * one asked of the stat's holder and cached per holder a few times a second; {@code HoldSeconds} asks that they have
 * held that long. {@code Target} is the same list asked of the hit's (or the heal's) target, and {@code Hit} reads the
 * hit itself (the first hit, the target's distance and height, the hit's class). {@code PerFoe} multiplies what the stat
 * pays by how many foes near the holder meet its conditions, and {@code Max} holds what it pays in all, whatever gear,
 * effects and picks raise it to (the engine clamps only a stat's current value to its maximum, never the maximum its
 * modifiers raise, which is where these stats accumulate, so the hold is here).
 *
 * <p><b>What it pays</b> ({@code Pays}): {@code Damage} is a percent on the holder's hits through the library's route
 * step, under {@code DamageBonusMax} (on hits of its {@code Types}, and with {@code Like} only on hits where that stat's
 * own damage line counts, as a weapon skill's line counts only with its weapon); {@code Healing} is a percent on the
 * heals the holder gives; {@code CritChance} is a critical chance on the hits it counts on, through the library's crit
 * step; {@code Onto} folds the stat into other stats while its holder conditions hold (a resistance, knockback, the
 * every-type {@code DamageResist}), one modifier written only when it changes, so whatever reads them never knows the
 * condition. A file with no payout is read only by a consumer that asks for it ({@code ConditionalStats.pointsOnHit}).
 * Any living entity holds them, a creature as much as a player (R860).
 */
public final class ConditionalStatAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, ConditionalStatAsset>> {

    /** Where these are authored. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/ConditionalStats";

    /** The every-type damage key, as the per-type damage bonus spells it. */
    public static final String EVERY_TYPE = "*";

    /**
     * The engine's damage classes a {@code Hit.Classes} entry may name (its fixed four, less Unknown). Held by
     * {@link Hit} itself, so a reader that initializes the Hit group first (a proc's codec) never reaches back into
     * this class while it is still being built.
     */
    public static final String[] DAMAGE_CLASSES = Hit.CLASS_NAMES;

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable protected Boolean isAbstract;
    @Nullable protected Boolean enabled;
    @Nullable protected String stat;
    @Nullable protected Condition[] holder;
    @Nullable protected Double holdSeconds;
    @Nullable protected Condition[] target;
    @Nullable protected Hit hit;
    @Nullable protected PerFoe perFoe;
    @Nullable protected Double max;
    @Nullable protected Pays pays;

    public static final AssetBuilderCodec<String, ConditionalStatAsset> CODEC = AssetBuilderCodec.builder(
                    ConditionalStatAsset.class,
                    ConditionalStatAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .append(new KeyedCodec<>("Abstract", Codec.BOOLEAN, false), (a, v) -> a.isAbstract = v, a -> a.isAbstract)
            .metadata(EditorSchema.defaultValue(false))
            .documentation("A skeleton other files take their fields from through Parent: it makes no stat "
                    + "conditional itself. Never inherited. False when unauthored.").add()
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Whether the stat counts at all. Switched off, it pays nothing. True when unauthored.").add()
            .append(new KeyedCodec<>("Stat", Codec.STRING, false), (a, v) -> a.stat = v, a -> a.stat)
            .metadata(EditorSchema.assetRef(EntityStatType.class))
            .documentation("The native stat this file makes conditional, by its id. Never inherited; the file's own "
                    + "id when unauthored.").add()
            .appendInherited(new KeyedCodec<>("Holder", new ArrayCodec<>(Condition.CODEC, Condition[]::new), false),
                    (a, v) -> a.holder = v, a -> a.holder, (a, p) -> a.holder = p.holder)
            .documentation("Entity conditions every one of which must hold on the stat's holder (ZigFoesNear, "
                    + "ZigNight, ZigStatPercent, ZigMoving, the engine's own Sprinting or NoDamageTaken). Read a few "
                    + "times a second and cached. None when unauthored.").add()
            .appendInherited(new KeyedCodec<>("HoldSeconds", Codec.DOUBLE, false),
                    (a, v) -> a.holdSeconds = v, a -> a.holdSeconds, (a, p) -> a.holdSeconds = p.holdSeconds)
            .metadata(EditorSchema.defaultValue(0.0))
            .documentation("How long, in seconds, the Holder conditions must have held before the stat counts "
                    + "(standing still for 2 seconds). 0 when unauthored.").add()
            .appendInherited(new KeyedCodec<>("Target", new ArrayCodec<>(Condition.CODEC, Condition[]::new), false),
                    (a, v) -> a.target = v, a -> a.target, (a, p) -> a.target = p.target)
            .documentation("Entity conditions every one of which must hold on the target of the hit or heal "
                    + "(ZigFoeFamily, ZigEffectFamily, ZigStatPercent). None when unauthored.").add()
            .appendInherited(new KeyedCodec<>("Hit", Hit.CODEC, false),
                    (a, v) -> a.hit = v, a -> a.hit, (a, p) -> a.hit = p.hit)
            .documentation("What the hit itself must be: the first on its target, from far enough away, of a damage "
                    + "class. Nothing asked when unauthored.").add()
            .appendInherited(new KeyedCodec<>("PerFoe", PerFoe.CODEC, false),
                    (a, v) -> a.perFoe = v, a -> a.perFoe, (a, p) -> a.perFoe = p.perFoe)
            .documentation("Pay the stat once for each foe near the holder that meets its conditions, up to a "
                    + "number. Paid once, unscaled, when unauthored.").add()
            .appendInherited(new KeyedCodec<>("Max", Codec.DOUBLE, false),
                    (a, v) -> a.max = v, a -> a.max, (a, p) -> a.max = p.max)
            .documentation("The most the stat pays in all, in its own units (50 is +50% on a damage stat), however "
                    + "high gear, effects and picks raise it and after its PerFoe count, so a stat many skill trees "
                    + "share holds one limit between them. No limit when unauthored.").add()
            .appendInherited(new KeyedCodec<>("Pays", Pays.CODEC, false),
                    (a, v) -> a.pays = v, a -> a.pays, (a, p) -> a.pays = p.pays)
            .documentation("What the stat pays into while it counts. Read only by a consumer that asks for it when "
                    + "unauthored.").add()
            .build();

    public ConditionalStatAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** Is this a skeleton (never itself a conditional stat)? */
    public boolean isAbstract() {
        return isAbstract != null && isAbstract;
    }

    /** Is it switched on? */
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    /** The stat this file makes conditional: {@code Stat} as written, else the file's id. */
    @Nullable
    public String statId() {
        if (stat != null && !stat.isBlank()) {
            return stat.trim();
        }
        return id == null || id.isBlank() ? null : id.trim();
    }

    /** The holder conditions, never null. */
    @Nonnull
    public Condition[] holderConditions() {
        return holder == null ? new Condition[0] : holder;
    }

    /** The seconds the holder conditions must have held, 0 or more. */
    public double holdSeconds() {
        return holdSeconds == null || !Double.isFinite(holdSeconds) ? 0.0 : Math.max(0.0, holdSeconds);
    }

    /** The target conditions, never null. */
    @Nonnull
    public Condition[] targetConditions() {
        return target == null ? new Condition[0] : target;
    }

    /** The hit group, or null when unauthored. */
    @Nullable
    public Hit hit() {
        return hit;
    }

    /** The per-foe scale, or null when unauthored. */
    @Nullable
    public PerFoe perFoe() {
        return perFoe;
    }

    /** The most the stat pays in all, as written; positive infinity when unauthored or not finite. */
    public double max() {
        return max == null || !Double.isFinite(max) ? Double.POSITIVE_INFINITY : max;
    }

    /** The payouts, or null when unauthored. */
    @Nullable
    public Pays pays() {
        return pays;
    }

    /** Does it ask anything of a hit or a target (so it can never fold onto another stat)? */
    public boolean asksOfTarget() {
        return targetConditions().length > 0 || (hit != null && !hit.isEmpty());
    }

    /** The Hit group: what the hit itself must be. Every leaf optional; an unauthored one asks nothing. */
    public static final class Hit {

        /** The damage classes a {@code Classes} entry may name; {@link ConditionalStatAsset#DAMAGE_CLASSES}. */
        static final String[] CLASS_NAMES = {"Light", "Charged", "Signature"};

        @Nullable protected Boolean firstHit;
        @Nullable protected Boolean notTargeted;
        @Nullable protected Double minDistance;
        @Nullable protected Double maxDistance;
        @Nullable protected Double minHeightAbove;
        @Nullable protected String[] classes;
        @Nullable protected Boolean projectile;
        @Nullable protected Boolean overTime;
        @Nullable protected Boolean onSelf;
        @Nullable protected Boolean headshot;

        public static final BuilderCodec<Hit> CODEC = BuilderCodec.builder(Hit.class, Hit::new)
                .appendInherited(new KeyedCodec<>("FirstHit", Codec.BOOLEAN, false),
                        (o, v) -> o.firstHit = v, o -> o.firstHit, (o, p) -> o.firstHit = p.firstHit)
                .documentation("True: only the holder's first hit on this target (its first since the target "
                        + "appeared). False: only every later one. Either when unauthored. Only a direct hit is "
                        + "noted, so pair True with OverTime false, or every damage-over-time tick on a foe never "
                        + "hit directly reads as a first hit.").add()
                .appendInherited(new KeyedCodec<>("NotTargeted", Codec.BOOLEAN, false),
                        (o, v) -> o.notTargeted = v, o -> o.notTargeted, (o, p) -> o.notTargeted = p.notTargeted)
                .documentation("True: only a creature target whose locked target is not the holder (a foe that has "
                        + "not turned on you). A player target never counts. Either when unauthored.").add()
                .appendInherited(new KeyedCodec<>("MinDistance", Codec.DOUBLE, false),
                        (o, v) -> o.minDistance = v, o -> o.minDistance, (o, p) -> o.minDistance = p.minDistance)
                .documentation("The holder at least this many blocks from the target when the hit lands (a "
                        + "projectile's shooter, where they stand). No limit when unauthored.").add()
                .appendInherited(new KeyedCodec<>("MaxDistance", Codec.DOUBLE, false),
                        (o, v) -> o.maxDistance = v, o -> o.maxDistance, (o, p) -> o.maxDistance = p.maxDistance)
                .documentation("The holder at most this many blocks from the target. No limit when unauthored.").add()
                .appendInherited(new KeyedCodec<>("MinHeightAbove", Codec.DOUBLE, false),
                        (o, v) -> o.minHeightAbove = v, o -> o.minHeightAbove,
                        (o, p) -> o.minHeightAbove = p.minHeightAbove)
                .documentation("The holder standing at least this many blocks above the target. No limit when "
                        + "unauthored.").add()
                .appendInherited(new KeyedCodec<>("Classes", Codec.STRING_ARRAY, false),
                        (o, v) -> o.classes = v, o -> o.classes, (o, p) -> o.classes = p.classes)
                .metadata(EditorSchema.oneOf(CLASS_NAMES))
                .documentation("The damage classes the hit's interaction declares (Light, Charged, Signature), any of "
                        + "which counts. Only the first damage of a strike carries its class, and the hit's other "
                        + "damages take the same answer. Any class, or none, when unauthored.").add()
                .appendInherited(new KeyedCodec<>("Projectile", Codec.BOOLEAN, false),
                        (o, v) -> o.projectile = v, o -> o.projectile, (o, p) -> o.projectile = p.projectile)
                .documentation("True: only a hit a projectile dealt (an arrow, a thrown spear, a bomb). False: only "
                        + "one that none dealt. Either when unauthored.").add()
                .appendInherited(new KeyedCodec<>("OverTime", Codec.BOOLEAN, false),
                        (o, v) -> o.overTime = v, o -> o.overTime, (o, p) -> o.overTime = p.overTime)
                .documentation("True: only a damage-over-time tick of an effect the holder put on. False: only a "
                        + "direct hit. Either when unauthored.").add()
                .appendInherited(new KeyedCodec<>("OnSelf", Codec.BOOLEAN, false),
                        (o, v) -> o.onSelf = v, o -> o.onSelf, (o, p) -> o.onSelf = p.onSelf)
                .documentation("For a heal: true only on the holder, false only on someone else. Either when "
                        + "unauthored.").add()
                .appendInherited(new KeyedCodec<>("Headshot", Codec.BOOLEAN, false),
                        (o, v) -> o.headshot = v, o -> o.headshot, (o, p) -> o.headshot = p.headshot)
                .documentation("True: only a hit that took its weapon's own Head branch (the interaction's "
                        + "TargetedDamage Head entry: in vanilla, a shortbow's full-draw arrow to the head). False: "
                        + "only one that did not. Either when unauthored.").add()
                .build();

        public Hit() {
        }

        /** Does it ask nothing at all? */
        public boolean isEmpty() {
            return firstHit == null && notTargeted == null && minDistance == null && maxDistance == null
                    && minHeightAbove == null && (classes == null || classes.length == 0) && projectile == null
                    && overTime == null && onSelf == null && headshot == null;
        }

        @Nullable public Boolean firstHit() { return firstHit; }
        @Nullable public Boolean notTargeted() { return notTargeted; }
        @Nullable public Double minDistance() { return finite(minDistance); }
        @Nullable public Double maxDistance() { return finite(maxDistance); }
        @Nullable public Double minHeightAbove() { return finite(minHeightAbove); }
        @Nullable public Boolean projectile() { return projectile; }
        @Nullable public Boolean overTime() { return overTime; }
        @Nullable public Boolean onSelf() { return onSelf; }
        @Nullable public Boolean headshot() { return headshot; }

        /** The classes as written, lower-cased, blanks dropped. */
        @Nonnull
        public Set<String> classes() {
            Set<String> out = new LinkedHashSet<>();
            if (classes != null) {
                for (String c : classes) {
                    if (c != null && !c.isBlank()) {
                        out.add(c.trim().toLowerCase(Locale.ROOT));
                    }
                }
            }
            return out;
        }
    }

    /** The PerFoe group: pay once for each foe near the holder that meets its conditions. */
    public static final class PerFoe {

        /** The radius in blocks unless written. */
        public static final double DEFAULT_RADIUS = 8.0;

        @Nullable protected Double radius;
        @Nullable protected Integer upTo;
        @Nullable protected Condition[] conditions;

        public static final BuilderCodec<PerFoe> CODEC = BuilderCodec.builder(PerFoe.class, PerFoe::new)
                .appendInherited(new KeyedCodec<>("Radius", Codec.DOUBLE, false),
                        (o, v) -> o.radius = v, o -> o.radius, (o, p) -> o.radius = p.radius)
                .metadata(EditorSchema.defaultValue(DEFAULT_RADIUS))
                .documentation("How far, in blocks, a foe counts. 8 when unauthored.").add()
                .appendInherited(new KeyedCodec<>("UpTo", Codec.INTEGER, false),
                        (o, v) -> o.upTo = v, o -> o.upTo, (o, p) -> o.upTo = p.upTo)
                .metadata(EditorSchema.defaultValue(3L))
                .documentation("The most foes it counts. 3 when unauthored.").add()
                .appendInherited(new KeyedCodec<>("Conditions", new ArrayCodec<>(Condition.CODEC, Condition[]::new),
                        false), (o, v) -> o.conditions = v, o -> o.conditions, (o, p) -> o.conditions = p.conditions)
                .documentation("Entity conditions each foe must meet to count, asked of the foe. Every foe counts "
                        + "when unauthored.").add()
                .build();

        public PerFoe() {
        }

        /** The radius, 0 or more. */
        public double radius() {
            return radius == null || !Double.isFinite(radius) ? DEFAULT_RADIUS : Math.max(0.0, radius);
        }

        /** The most foes counted, at least 1. */
        public int upTo() {
            return upTo == null ? 3 : Math.max(1, upTo);
        }

        /** Each foe's conditions, or null for none. */
        @Nullable
        public Condition[] conditions() {
            return conditions;
        }
    }

    /** The Pays group: what the stat pays into while it counts. */
    public static final class Pays {

        @Nullable protected Damage damage;
        @Nullable protected Boolean healing;
        @Nullable protected Boolean critChance;
        @Nullable protected String[] onto;

        public static final BuilderCodec<Pays> CODEC = BuilderCodec.builder(Pays.class, Pays::new)
                .appendInherited(new KeyedCodec<>("Damage", Damage.CODEC, false),
                        (o, v) -> o.damage = v, o -> o.damage, (o, p) -> o.damage = p.damage)
                .documentation("The stat is a percent on the holder's hits (whole percent points: 10 is +10%), added "
                        + "in the library's route step inside the engine's own outgoing sum and held to the holder's "
                        + "DamageBonusMax. Not a damage stat when unauthored.").add()
                .appendInherited(new KeyedCodec<>("Healing", Codec.BOOLEAN, false),
                        (o, v) -> o.healing = v, o -> o.healing, (o, p) -> o.healing = p.healing)
                .documentation("True: the stat is a percent on the heals the holder gives through the library's one "
                        + "healing path, beside HealingPower. False when unauthored.").add()
                .appendInherited(new KeyedCodec<>("CritChance", Codec.BOOLEAN, false),
                        (o, v) -> o.critChance = v, o -> o.critChance, (o, p) -> o.critChance = p.critChance)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("True: the stat is a critical chance on the holder's hits (whole percent points: 5 is "
                        + "5% more), added to their CritChance in the library's crit step on the hits it counts on (a "
                        + "frozen foe, say). False when unauthored.").add()
                .appendInherited(new KeyedCodec<>("Onto", Codec.STRING_ARRAY, false),
                        (o, v) -> o.onto = v, o -> o.onto, (o, p) -> o.onto = p.onto)
                .metadata(EditorSchema.assetRef(EntityStatType.class))
                .documentation("Stats the stat's value is added to while its Holder conditions hold (a resistance, "
                        + "KnockbackResist): one modifier on each, written when it changes. Only for a file that asks "
                        + "nothing of a target or a hit. None when unauthored.").add()
                .build();

        public Pays() {
        }

        /** The damage payout, or null. */
        @Nullable
        public Damage damage() {
            return damage;
        }

        /** Does it pay on heals? */
        public boolean healing() {
            return healing != null && healing;
        }

        /** Does it pay into the crit step's chance? */
        public boolean critChance() {
            return critChance != null && critChance;
        }

        /** The stats it folds onto, trimmed, blanks dropped, in the order written. */
        @Nonnull
        public List<String> onto() {
            List<String> out = new ArrayList<>();
            if (onto != null) {
                for (String s : onto) {
                    if (s != null && !s.isBlank() && !out.contains(s.trim())) {
                        out.add(s.trim());
                    }
                }
            }
            return out;
        }
    }

    /** The Pays.Damage group: on which hits the damage percent counts. */
    public static final class Damage {

        @Nullable protected String[] types;
        @Nullable protected String like;

        public static final BuilderCodec<Damage> CODEC = BuilderCodec.builder(Damage.class, Damage::new)
                .appendInherited(new KeyedCodec<>("Types", Codec.STRING_ARRAY, false),
                        (o, v) -> o.types = v, o -> o.types, (o, p) -> o.types = p.types)
                .documentation("Damage types (DamageCause ids; a type covers the causes filed under it), or \"*\" "
                        + "for every hit. Every hit when unauthored.").add()
                .appendInherited(new KeyedCodec<>("Like", Codec.STRING, false),
                        (o, v) -> o.like = v, o -> o.like, (o, p) -> o.like = p.like)
                .metadata(EditorSchema.assetRef(EntityStatType.class))
                .documentation("Another damage stat: count only on a hit where that stat's own damage line counts "
                        + "(a weapon skill's line, which counts only with its weapon or on its own route). Every hit "
                        + "when unauthored.").add()
                .build();

        public Damage() {
        }

        /** The types as written, trimmed, blanks dropped; {@code ["*"]} when none. */
        @Nonnull
        public List<String> types() {
            List<String> out = new ArrayList<>();
            if (types != null) {
                for (String t : types) {
                    if (t != null && !t.isBlank() && !out.contains(t.trim())) {
                        out.add(t.trim());
                    }
                }
            }
            return out.isEmpty() ? List.of(EVERY_TYPE) : out;
        }

        /** The stat whose line this one follows, or null. */
        @Nullable
        public String like() {
            return like == null || like.isBlank() ? null : like.trim();
        }
    }

    @Nullable
    private static Double finite(@Nullable Double value) {
        return value == null || !Double.isFinite(value) ? null : value;
    }
}
