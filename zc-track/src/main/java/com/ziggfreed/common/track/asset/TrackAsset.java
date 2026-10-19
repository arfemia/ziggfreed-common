package com.ziggfreed.common.track.asset;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.codec.InheritMapCodec;
import com.ziggfreed.common.commerce.asset.CostAsset;
import com.ziggfreed.common.grant.Grant;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.progress.asset.RewardEntryAsset;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.progress.gate.StoreGates;
import com.ziggfreed.common.text.ContentTextAsset;
import com.ziggfreed.common.track.gain.GainFactors;

/**
 * One track, at {@code Server/ZiggfreedCommon/Tracks/<Owner>/<Id>.json}: a named value a player climbs, read
 * as levels on a shared curve. The FILE NAME is the id (matched without regard to case; it may not contain
 * {@code @}, which a seasonal run's key reserves); the folders are the author's own grouping.
 *
 * <pre>{@code
 * { "Text": { "TitleKey": "yourmod.track.mining" },
 *   "Icon": "Tool_Pickaxe_Iron",
 *   "Category": "Gathering",
 *   "Curve": "Skill_Hybrid",
 *   "Cap": 200,
 *   "Mirror": { "Stat": "Yourmod_Level_Mining" },
 *   "Prestige": { "Ranks": 10 },
 *   "Unlocks": [ { "At": [ 25, 50, 75 ], "Rewards": [ { "Kind": "Item", "Params": { "Item": "Your_Cache" } } ] },
 *                { "Every": 15, "Rewards": [ { "Kind": "Yourmod_Point", "Params": { "Amount": 1 } } ] } ] }
 * }</pre>
 *
 * <p>A player's climb keeps its current value (with the fraction a gain left over), the best level they ever
 * reached and how often they prestiged. Every read of a level that gates content reads the best ever, so a
 * prestige, which resets only the current climb, never locks anything again. Each {@code Unlocks} row pays a
 * Claim the first time the best level reaches one of its levels, never twice. Every leaf is nullable and
 * inherited through {@code Parent}: {@code Unlocks} replaces whole, every group merges leaf by leaf. The server
 * owner retunes any of it in {@code mods/ziggfreedcommon/tracks.json}.
 */
public final class TrackAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, TrackAsset>> {

    /** The store's content path; the folders below it are the author's own grouping. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/Tracks";

    /** The character a track id may not contain: a seasonal run's key puts it between the track and the run. */
    public static final char RUN_SEPARATOR = '@';

    /** The prestige ranks a track allows when its Prestige group names none. */
    public static final int DEFAULT_PRESTIGE_RANKS = 10;

    /** The level the nth prestige needs is this plus n, unless a track's Prestige group says otherwise. */
    public static final int DEFAULT_PRESTIGE_BASE = 100;

    /** What a prestiged climb earns on top of every gain, as a percent, unless its Prestige group says otherwise (R642). */
    public static final double DEFAULT_PRESTIGE_GAIN_PERCENT = 10.0;

    /** The sentence the Asset Editor shows beside a track's own {@code Requires}. */
    static final String REQUIRES_DOCUMENTATION = "What a player must meet for this track to be theirs, in the "
            + "Requires shape quests use, every condition read. A plain { \"Factor\": \"hytale:mod_installed\", "
            + "\"Param\": \"Group:Name\", \"Min\": 1 } keeps the track out entirely where that mod is not installed; "
            + "the rest is asked of the player each time the track would grow, and a player who does not meet it "
            + "gains nothing on it. Write Min 1 on a presence check.";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable protected Boolean enabled;
    @Nullable protected ContentTextAsset text;
    @Nullable protected String icon;
    @Nullable protected Integer order;
    @Nullable protected String category;
    @Nullable private GateSpec gate;
    @Nullable protected String curve;
    @Nullable protected Integer cap;
    @Nullable protected Points points;
    @Nullable protected Map<String, Branch> branches;
    @Nullable protected String[] starter;
    @Nullable protected Loadouts loadouts;
    @Nullable protected Respec respec;
    @Nullable protected Mirror mirror;
    @Nullable protected Prestige prestige;
    @Nullable protected Unlock[] unlocks;
    @Nullable protected Gain gain;

    /** The live part of the {@code Requires} block, lifted once per instance ({@link StoreGates#live}). */
    @Nullable private volatile GateSpec[] live;

    public static final AssetBuilderCodec<String, TrackAsset> CODEC = AssetBuilderCodec.builder(
                    TrackAsset.class,
                    TrackAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Whether this track exists on this server; unauthored means true. False makes it "
                    + "absent: its readings answer nothing, rewards naming it are refused and it pays nothing. "
                    + "What players already hold on it is kept.").add()
            .appendInherited(new KeyedCodec<>("Text", ContentTextAsset.CODEC, false),
                    (a, v) -> a.text = v, a -> a.text, (a, p) -> a.text = p.text)
            .documentation("What the track is called (TitleKey) and its description (FlavorKey), as "
                    + "localization keys in your own lang file. Without a TitleKey it is named by its id.").add()
            .appendInherited(new KeyedCodec<>("Icon", Codec.STRING, false),
                    (a, v) -> a.icon = v, a -> a.icon, (a, p) -> a.icon = p.icon)
            .metadata(EditorSchema.assetRef(Item.class))
            .documentation("The item whose picture stands for the track wherever it is shown, its rewards "
                    + "included.").add()
            .appendInherited(new KeyedCodec<>("Order", Codec.INTEGER, false),
                    (a, v) -> a.order = v, a -> a.order, (a, p) -> a.order = p.order)
            .metadata(EditorSchema.defaultValue(0L))
            .documentation("Where the track sorts among the others: lower first, then by id.").add()
            .appendInherited(new KeyedCodec<>("Category", Codec.STRING, false),
                    (a, v) -> a.category = v, a -> a.category, (a, p) -> a.category = p.category)
            .metadata(EditorSchema.assetRef(TrackCategoryAsset.class))
            .documentation("The category the track sits in, by id (a file under "
                    + "Server/ZiggfreedCommon/TrackCategories): Combat, Gathering and Crafting ship with the "
                    + "library. Its Prestige.Build is this track's default.").add()
            .appendInherited(new KeyedCodec<>("Requires", GateSpec.CODEC, false),
                    (a, v) -> a.gate = v, a -> a.gate, (a, p) -> a.gate = p.gate)
            .documentation(REQUIRES_DOCUMENTATION).add()
            .appendInherited(new KeyedCodec<>("Curve", Codec.STRING, false),
                    (a, v) -> a.curve = v, a -> a.curve, (a, p) -> a.curve = p.curve)
            .metadata(EditorSchema.assetRef(TrackCurveAsset.class))
            .documentation("The curve that turns this track's value into levels, by id (a file under "
                    + "Server/ZiggfreedCommon/TrackCurves); tracks share one. Skill_Hybrid ships with the library. "
                    + "A track whose curve is not loaded answers nothing.").add()
            .appendInherited(new KeyedCodec<>("Cap", Codec.INTEGER, false),
                    (a, v) -> a.cap = v, a -> a.cap, (a, p) -> a.cap = p.cap)
            .documentation("The highest level a player can reach: a gain past it stops at its start. Leave it out "
                    + "to climb the whole curve (and its Beyond, without end).").add()
            .appendInherited(new KeyedCodec<>("Points", Points.CODEC, false),
                    (a, v) -> a.points = v, a -> a.points, (a, p) -> a.points = p.points)
            .documentation("Points a player earns to spend on this track's picks as it levels. Leave it out for "
                    + "a track whose picks cost no points.").add()
            .appendInherited(new KeyedCodec<>("Branches", new InheritMapCodec<>(Branch.CODEC), false),
                    (a, v) -> a.branches = v, a -> a.branches, (a, p) -> a.branches = p.branches)
            .documentation("The branches of this track's tree, by id: what each is called and where it sorts. A "
                    + "pick names its branch in its own Branch, and a row's Requires opens on the points spent in "
                    + "it (the ziggfreedcommon:track_branch_spent reading, Param <Track>:<Branch>). Merges branch by "
                    + "branch under Parent and in the owner file.").add()
            .appendInherited(new KeyedCodec<>("Starter", Codec.STRING_ARRAY, false),
                    (a, v) -> a.starter = v, a -> a.starter, (a, p) -> a.starter = p.starter)
            .documentation("The starter build: pick ids in the order they are spent, a pick written again for each "
                    + "further rank. Read only by a one-time claim of points a player earned before the tree "
                    + "existed; nothing else suggests it.").add()
            .appendInherited(new KeyedCodec<>("Loadouts", Loadouts.CODEC, false),
                    (a, v) -> a.loadouts = v, a -> a.loadouts, (a, p) -> a.loadouts = p.loadouts)
            .documentation("Saved builds: how many a player keeps on this track and what swapping to one costs. "
                    + "Each leaf left out reads from the track's category, else none are kept and a swap is free.")
            .add()
            .appendInherited(new KeyedCodec<>("Respec", Respec.CODEC, false),
                    (a, v) -> a.respec = v, a -> a.respec, (a, p) -> a.respec = p.respec)
            .documentation("What a player pays to clear every pick on this track and get every point back; what "
                    + "a trainer taught stays. Left out, the track's category decides, else it is free.").add()
            .appendInherited(new KeyedCodec<>("Mirror", Mirror.CODEC, false),
                    (a, v) -> a.mirror = v, a -> a.mirror, (a, p) -> a.mirror = p.mirror)
            .documentation("A native stat this track's level is written onto, so anything reading stats (an "
                    + "item, an effect, a hytale:stat requirement) sees it. Leave it out for no stat.").add()
            .appendInherited(new KeyedCodec<>("Prestige", Prestige.CODEC, false),
                    (a, v) -> a.prestige = v, a -> a.prestige, (a, p) -> a.prestige = p.prestige)
            .documentation("Whether a player may prestige this track: start the climb again from the bottom, "
                    + "keeping the best level they ever reached, so nothing they unlocked locks again and no "
                    + "unlock pays twice. Leave it out for a track that never prestiges.").add()
            .appendInherited(new KeyedCodec<>("Unlocks", new ArrayCodec<>(Unlock.CODEC, Unlock[]::new), false),
                    (a, v) -> a.unlocks = v, a -> a.unlocks, (a, p) -> a.unlocks = p.unlocks)
            .documentation("Rewards for reaching levels: each row pays its Rewards as a Claim the player "
                    + "collects, once for each of its levels the best level ever reached passes, and never "
                    + "twice, whatever a prestige does. A row added later pays what is already behind the "
                    + "player. A player's record knows each row by its Id (or, unauthored, by its levels), never "
                    + "by its place, so the rows can be reordered freely. Under Parent this list replaces the "
                    + "parent's whole.").add()
            .appendInherited(new KeyedCodec<>("Gain", Gain.CODEC, false),
                    (a, v) -> a.gain = v, a -> a.gain, (a, p) -> a.gain = p.gain)
            .documentation("How a gain on this track is worked out before it lands: the named factors it runs, "
                    + "in order, the ones that add together, and the rested pool. Leave it out for the usual order "
                    + "(Class, World, Bonus, Boost, Prestige, Rested), Bonus and Boost adding together, and no "
                    + "rested pool.").add()
            .build();

    public TrackAsset() {
    }

    /**
     * Java-side factory for a track a module derives in code from assets of its own ({@link TrackConfig#provide}):
     * its name, picture, place, category, curve and Prestige group, every other leaf unauthored. Sets the same
     * fields the codec fills, so an owner entry decodes against it leaf by leaf.
     */
    @Nonnull
    public static TrackAsset of(@Nonnull String id, @Nullable ContentTextAsset text, @Nullable String icon,
            @Nullable Integer order, @Nullable String category, @Nonnull String curve, @Nullable Prestige prestige) {
        TrackAsset a = new TrackAsset();
        a.id = id;
        a.text = text;
        a.icon = blankToNull(icon);
        a.order = order;
        a.category = blankToNull(category);
        a.curve = curve;
        a.prestige = prestige;
        return a;
    }

    /** The track id, exactly as the filename spells it. */
    @Override
    public String getId() {
        return id;
    }

    /** Is the track switched on in its own file? Unauthored means true. */
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    @Nullable
    public ContentTextAsset getText() {
        return text;
    }

    /** The localization key the track is named by, or null when the file names none. */
    @Nullable
    public String titleKey() {
        return text == null ? null : blankToNull(text.getTitleKey());
    }

    /** The item standing for it, or null. */
    @Nullable
    public String icon() {
        return blankToNull(icon);
    }

    /** Its place among the tracks; 0 unless authored. */
    public int order() {
        return order == null ? 0 : order;
    }

    /** The category id as written, trimmed, or null. */
    @Nullable
    public String category() {
        return blankToNull(category);
    }

    /** The curve id as written, trimmed, or null. */
    @Nullable
    public String curveId() {
        return blankToNull(curve);
    }

    /** The highest level, or null for the whole curve. A cap below 1 reads as 1. */
    @Nullable
    public Integer cap() {
        return cap == null ? null : Math.max(1, cap);
    }

    /** Points earned per level past the first; 0 when unauthored or below 0. */
    public int pointsPerLevel() {
        Integer per = points == null ? null : points.perLevel;
        return per == null ? 0 : Math.max(0, per);
    }

    /** The last level that earns a point ({@code Points.To}), at least 1; {@link Integer#MAX_VALUE} unauthored. */
    public int pointsTo() {
        Integer to = points == null ? null : points.to;
        return to == null ? Integer.MAX_VALUE : Math.max(1, to);
    }

    /** What each point spent on this track grants ({@code Points.Grants}, null entries left out); empty for none. */
    @Nonnull
    public List<Grant> pointsGrants() {
        List<Grant> out = new ArrayList<>();
        if (points != null && points.grants != null) {
            for (Grant grant : points.grants) {
                if (grant != null) {
                    out.add(grant);
                }
            }
        }
        return List.copyOf(out);
    }

    /** The branches as written, by lower-cased id in authored order (a null entry left out); empty for none. */
    @Nonnull
    public Map<String, Branch> branches() {
        Map<String, Branch> out = new LinkedHashMap<>();
        if (branches != null) {
            branches.forEach((key, branch) -> {
                String id = key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
                if (!id.isEmpty() && branch != null) {
                    out.put(id, branch);
                }
            });
        }
        return Collections.unmodifiableMap(out);
    }

    /** The branch {@code id} names (any case), or null when this track declares none by that id. */
    @Nullable
    public Branch branch(@Nullable String id) {
        return id == null ? null : branches().get(id.trim().toLowerCase(Locale.ROOT));
    }

    /** The starter build's pick ids, lower-cased, in order, a repeat kept as a further rank; empty for none. */
    @Nonnull
    public List<String> starter() {
        List<String> out = new ArrayList<>();
        if (starter != null) {
            for (String id : starter) {
                if (id != null && !id.isBlank()) {
                    out.add(id.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
        return List.copyOf(out);
    }

    /** The {@code Loadouts} group as written, or null. */
    @Nullable
    public Loadouts loadouts() {
        return loadouts;
    }

    /** The {@code Respec} group as written, or null. */
    @Nullable
    public Respec respec() {
        return respec;
    }

    /** The native stat the level is mirrored onto, trimmed, or null for none. */
    @Nullable
    public String mirrorStat() {
        return mirror == null ? null : blankToNull(mirror.stat);
    }

    /** Does the mirror carry the current climb's level rather than the best level ever? Unauthored: no. */
    public boolean mirrorsCurrent() {
        return mirror != null && Boolean.TRUE.equals(mirror.current);
    }

    /** Is prestige on for this track: a Prestige group allowing at least one rank? */
    public boolean prestiges() {
        return prestige != null && prestigeRanks() > 0;
    }

    /**
     * How many times a player may prestige: {@link Integer#MAX_VALUE} with {@code Unlimited}, else the group's
     * {@code Ranks} ({@link #DEFAULT_PRESTIGE_RANKS} unauthored); 0 with no Prestige group.
     */
    public int prestigeRanks() {
        if (prestige == null) {
            return 0;
        }
        if (Boolean.TRUE.equals(prestige.unlimited)) {
            return Integer.MAX_VALUE;
        }
        return prestige.ranks == null ? DEFAULT_PRESTIGE_RANKS : Math.max(0, prestige.ranks);
    }

    /** The level the {@code nth} prestige (1 for the first) needs: the group's {@code Base} plus n. */
    public int prestigeLevel(int nth) {
        int base = prestige == null || prestige.base == null ? DEFAULT_PRESTIGE_BASE : Math.max(0, prestige.base);
        return (int) Math.min(Integer.MAX_VALUE, (long) base + Math.max(1, nth));
    }

    /** The build this track's own Prestige group names, or null when it names none. */
    @Nullable
    public PrestigeBuild ownPrestigeBuild() {
        return prestige == null ? null : PrestigeBuild.of(prestige.build);
    }

    /**
     * The unlock rows, in authored order (a null row left out: a row is known by its {@link Unlock#key()}, never
     * its place); empty when none.
     */
    @Nonnull
    public List<Unlock> unlocks() {
        List<Unlock> out = new ArrayList<>();
        if (unlocks != null) {
            for (Unlock row : unlocks) {
                if (row != null) {
                    out.add(row);
                }
            }
        }
        return List.copyOf(out);
    }

    /**
     * What a player must meet for this track to be theirs, or null when it authors none: its plain mod-presence
     * condition decides whether the track loads at all, and every other condition is asked of the player each
     * time it would grow ({@link #liveRequires}).
     */
    @Nullable
    public GateSpec requires() {
        return gate;
    }

    /** The part of {@link #requires()} asked of the player, lifted once per instance; null when nothing is. */
    @Nullable
    public GateSpec liveRequires() {
        GateSpec[] held = live;
        if (held == null) {
            held = new GateSpec[] {StoreGates.live(gate)};
            live = held;
        }
        return held[0];
    }

    /** Add to {@code into} the missing mod of each unlock reward row its own {@code Requires} keeps off this server. */
    public void collectGatedRows(@Nonnull Collection<String> into) {
        if (unlocks == null) {
            return;
        }
        for (Unlock row : unlocks) {
            if (row != null) {
                RewardEntryAsset.collectMissingMods(row.rewards, into);
            }
        }
    }

    /** Does an unlock reward row gate on more than mod presence (a live {@code Requires})? */
    public boolean widensRows() {
        if (unlocks == null) {
            return false;
        }
        for (Unlock row : unlocks) {
            if (row != null && RewardEntryAsset.anyWidened(row.rewards)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The gain factors this track runs, in order, as written ({@code Gain.Factors}, blank ids left out), or the
     * usual order ({@link GainFactors#DEFAULT_ORDER}) when it names none.
     */
    @Nonnull
    public List<String> gainFactors() {
        String[] written = gain == null ? null : gain.factors;
        if (written == null) {
            return GainFactors.DEFAULT_ORDER;
        }
        List<String> out = new ArrayList<>(written.length);
        for (String id : written) {
            if (id != null && !id.isBlank()) {
                out.add(id.trim());
            }
        }
        return List.copyOf(out);
    }

    /** Does this track write its own {@code Gain.Factors} list (rather than the usual order)? */
    public boolean writesGainFactors() {
        return gain != null && gain.factors != null;
    }

    /**
     * The gain factors this track adds together rather than multiplying, as written ({@code Gain.Additive}, blank
     * ids left out; an empty list multiplies every factor), or the usual pair ({@link GainFactors#DEFAULT_ADDITIVE},
     * Bonus and Boost) when it names none.
     */
    @Nonnull
    public List<String> additiveFactors() {
        String[] written = gain == null ? null : gain.additive;
        if (written == null) {
            return GainFactors.DEFAULT_ADDITIVE;
        }
        List<String> out = new ArrayList<>(written.length);
        for (String id : written) {
            if (id != null && !id.isBlank()) {
                out.add(id.trim());
            }
        }
        return List.copyOf(out);
    }

    /**
     * What a prestiged climb earns on top of every gain, as a percent ({@code Prestige.GainPercent}): 10 unless
     * written (R642), 0 for a track with no Prestige group.
     */
    public double prestigeGainPercent() {
        if (prestige == null) {
            return 0.0;
        }
        Double percent = prestige.gainPercent;
        return percent == null || !Double.isFinite(percent) ? DEFAULT_PRESTIGE_GAIN_PERCENT : percent;
    }

    /** The rested pool's settings when this track turns one on, else null: off unless written (R572). */
    @Nullable
    public Rested rested() {
        Rested rested = gain == null ? null : gain.rested;
        return rested == null || !rested.isEnabled() ? null : rested;
    }

    /** Is {@code trackId} usable as a track id: not blank, and free of the run separator? */
    public static boolean usableId(@Nullable String trackId) {
        return trackId != null && !trackId.isBlank() && trackId.indexOf(RUN_SEPARATOR) < 0;
    }

    @Nullable
    static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** The Points group: what a level earns toward the track's picks, and what each point spent grants. */
    public static final class Points {

        @Nullable protected Integer perLevel;
        @Nullable protected Integer to;
        @Nullable protected Grant[] grants;

        public static final BuilderCodec<Points> CODEC = BuilderCodec.builder(Points.class, Points::new)
                .appendInherited(new KeyedCodec<>("PerLevel", Codec.INTEGER, false),
                        (o, v) -> o.perLevel = v, o -> o.perLevel, (o, p) -> o.perLevel = p.perLevel)
                .metadata(EditorSchema.defaultValue(0L))
                .documentation("Points each level past the first earns, to spend on the picks priced in this "
                        + "track's points (a pick's Cost). A respec gives back what was spent. 0 when unauthored.")
                .add()
                .appendInherited(new KeyedCodec<>("To", Codec.INTEGER, false),
                        (o, v) -> o.to = v, o -> o.to, (o, p) -> o.to = p.to)
                .documentation("The last level that earns a point: 100 earns one at every level from 2 to 100 "
                        + "and none past it. Leave it out for every level the track reaches.").add()
                .appendInherited(new KeyedCodec<>("Grants", Grant.ARRAY_CODEC, false),
                        (o, v) -> o.grants = v, o -> o.grants, (o, p) -> o.grants = p.grants)
                .documentation("What each point spent on this track grants, wherever it was spent: the main line "
                        + "a skill gains with every point placed. Paid only for points a player spent, so a point "
                        + "waiting to be spent grants nothing; a world rule that takes a kind of grant away takes "
                        + "these too. Leave it out for none.").add()
                .build();

        public Points() {
        }
    }

    /** One branch of a track's tree: what it is called, the item standing for it, and where it sorts. */
    public static final class Branch {

        @Nullable protected ContentTextAsset text;
        @Nullable protected String icon;
        @Nullable protected Integer order;

        public static final BuilderCodec<Branch> CODEC = BuilderCodec.builder(Branch.class, Branch::new)
                .appendInherited(new KeyedCodec<>("Text", ContentTextAsset.CODEC, false),
                        (o, v) -> o.text = v, o -> o.text, (o, p) -> o.text = p.text)
                .documentation("What the branch is called (TitleKey) and its description (FlavorKey), as "
                        + "localization keys in your own lang file. Without a TitleKey it is named by its id.").add()
                .appendInherited(new KeyedCodec<>("Icon", Codec.STRING, false),
                        (o, v) -> o.icon = v, o -> o.icon, (o, p) -> o.icon = p.icon)
                .metadata(EditorSchema.assetRef(Item.class))
                .documentation("The item whose picture stands for the branch.").add()
                .appendInherited(new KeyedCodec<>("Order", Codec.INTEGER, false),
                        (o, v) -> o.order = v, o -> o.order, (o, p) -> o.order = p.order)
                .metadata(EditorSchema.defaultValue(0L))
                .documentation("Where the branch sorts among the track's others: lower first, then by id.").add()
                .build();

        public Branch() {
        }

        /** The localization key the branch is named by, or null. */
        @Nullable
        public String titleKey() {
            return text == null ? null : blankToNull(text.getTitleKey());
        }

        @Nullable
        public ContentTextAsset getText() {
            return text;
        }

        @Nullable
        public String icon() {
            return blankToNull(icon);
        }

        public int order() {
            return order == null ? 0 : order;
        }
    }

    /**
     * The Loadouts group: how many saved builds a player keeps on a track and what a swap costs. A category writes
     * the same group as its tracks' default, leaf by leaf.
     */
    public static final class Loadouts {

        @Nullable protected Integer max;
        @Nullable protected CostAsset swapCost;

        public static final BuilderCodec<Loadouts> CODEC = BuilderCodec.builder(Loadouts.class, Loadouts::new)
                .appendInherited(new KeyedCodec<>("Max", Codec.INTEGER, false),
                        (o, v) -> o.max = v, o -> o.max, (o, p) -> o.max = p.max)
                .metadata(EditorSchema.defaultValue(0L))
                .documentation("How many builds a player may save on the track and swap between. 0 when "
                        + "unauthored (here and on the category): no saved builds.").add()
                .appendInherited(new KeyedCodec<>("SwapCost", CostAsset.CODEC, false),
                        (o, v) -> o.swapCost = v, o -> o.swapCost, (o, p) -> o.swapCost = p.swapCost)
                .documentation("What a swap to a saved build costs, in any wallet or item (the one price shape "
                        + "shops use). An empty group is free. Unauthored here and on the category: free.").add()
                .build();

        public Loadouts() {
        }

        /** The most saved builds as written (0 or more), or null when unauthored. */
        @Nullable
        public Integer max() {
            return max == null ? null : Math.max(0, max);
        }

        /** The swap's price as written, or null when unauthored. */
        @Nullable
        public CostAsset swapCost() {
            return swapCost;
        }
    }

    /**
     * The Respec group: what clearing every pick on a track but the trainer-taught costs. A category writes it as its
     * tracks' default.
     */
    public static final class Respec {

        @Nullable protected CostAsset cost;

        public static final BuilderCodec<Respec> CODEC = BuilderCodec.builder(Respec.class, Respec::new)
                .appendInherited(new KeyedCodec<>("Cost", CostAsset.CODEC, false),
                        (o, v) -> o.cost = v, o -> o.cost, (o, p) -> o.cost = p.cost)
                .documentation("What a respec costs, in any wallet or item (the one price shape shops use). An "
                        + "empty group is free. Unauthored here and on the category: free.").add()
                .build();

        public Respec() {
        }

        /** The respec's price as written, or null when unauthored. */
        @Nullable
        public CostAsset cost() {
            return cost;
        }
    }

    /** The Mirror group: the native stat the level is written onto. */
    public static final class Mirror {

        @Nullable protected String stat;
        @Nullable protected Boolean current;

        public static final BuilderCodec<Mirror> CODEC = BuilderCodec.builder(Mirror.class, Mirror::new)
                .appendInherited(new KeyedCodec<>("Stat", Codec.STRING, false),
                        (o, v) -> o.stat = v, o -> o.stat, (o, p) -> o.stat = p.stat)
                .metadata(EditorSchema.assetRef(EntityStatType.class))
                .documentation("The native stat (an EntityStatType id) whose maximum carries the level, written "
                        + "whenever it changes and again at login. A stat this server does not have is reported "
                        + "and nothing is written.").add()
                .appendInherited(new KeyedCodec<>("Current", Codec.BOOLEAN, false),
                        (o, v) -> o.current = v, o -> o.current, (o, p) -> o.current = p.current)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("True writes the current climb's level, which a prestige starts again; false, "
                        + "or unauthored, writes the best level ever reached, which a prestige never lowers.")
                .add()
                .build();

        public Mirror() {
        }
    }

    /** The Prestige group: whether, how often and when a player may start the climb again. */
    public static final class Prestige {

        @Nullable protected Integer ranks;
        @Nullable protected Boolean unlimited;
        @Nullable protected Integer base;
        @Nullable protected String build;
        @Nullable protected Double gainPercent;

        public static final BuilderCodec<Prestige> CODEC = BuilderCodec.builder(Prestige.class, Prestige::new)
                .appendInherited(new KeyedCodec<>("Ranks", Codec.INTEGER, false),
                        (o, v) -> o.ranks = v, o -> o.ranks, (o, p) -> o.ranks = p.ranks)
                .metadata(EditorSchema.defaultValue((long) DEFAULT_PRESTIGE_RANKS))
                .documentation("How many times a player may prestige this track. 10 when unauthored; 0 turns "
                        + "prestige off.").add()
                .appendInherited(new KeyedCodec<>("Unlimited", Codec.BOOLEAN, false),
                        (o, v) -> o.unlimited = v, o -> o.unlimited, (o, p) -> o.unlimited = p.unlimited)
                .metadata(EditorSchema.defaultValue(false))
                .documentation("True lets a player prestige without limit, whatever Ranks says.").add()
                .appendInherited(new KeyedCodec<>("Base", Codec.INTEGER, false),
                        (o, v) -> o.base = v, o -> o.base, (o, p) -> o.base = p.base)
                .metadata(EditorSchema.defaultValue((long) DEFAULT_PRESTIGE_BASE))
                .documentation("The level each prestige needs, before the rank: the first needs Base + 1, the "
                        + "second Base + 2, and so on. 100 when unauthored, so the first prestige needs level "
                        + "101. Keep it below the track's Cap, or nobody can prestige.").add()
                .appendInherited(new KeyedCodec<>("Build", Codec.STRING, false),
                        (o, v) -> o.build = v, o -> o.build, (o, p) -> o.build = p.build)
                .metadata(PrestigeBuild.editorHint())
                .documentation(PrestigeBuild.DOCUMENTATION + " Unauthored, the track's category decides "
                        + "(Combat keeps, Gathering and Crafting remember), and Keep where it says nothing.").add()
                .appendInherited(new KeyedCodec<>("GainPercent", Codec.DOUBLE, false),
                        (o, v) -> o.gainPercent = v, o -> o.gainPercent, (o, p) -> o.gainPercent = p.gainPercent)
                .metadata(EditorSchema.defaultValue(DEFAULT_PRESTIGE_GAIN_PERCENT))
                .documentation("What a player who has prestiged this track earns on top of every gain on it, as a "
                        + "percent: the same after one prestige or ten. 10 when unauthored; 0 turns it off. It "
                        + "runs as the Prestige gain factor.").add()
                .build();

        public Prestige() {
        }

        /** Java-side factory; sets the same fields the codec fills, null leaves unauthored. */
        @Nonnull
        public static Prestige of(@Nullable Integer ranks, @Nullable Boolean unlimited, @Nullable Integer base,
                @Nullable String build) {
            Prestige p = new Prestige();
            p.ranks = ranks;
            p.unlimited = unlimited;
            p.base = base;
            p.build = blankToNull(build);
            return p;
        }
    }

    /** The Gain group: the factors a gain runs through, and the rested pool. */
    public static final class Gain {

        @Nullable protected String[] factors;
        @Nullable protected String[] additive;
        @Nullable protected Rested rested;

        public static final BuilderCodec<Gain> CODEC = BuilderCodec.builder(Gain.class, Gain::new)
                .appendInherited(new KeyedCodec<>("Factors", Codec.STRING_ARRAY, false),
                        (o, v) -> o.factors = v, o -> o.factors, (o, p) -> o.factors = p.factors)
                .documentation("The named factors every gain on this track runs through, in this order: World "
                        + "(the world's track rules), Boost (boosts), Prestige (the extra a prestiged climb "
                        + "earns), Rested (the rested pool below), and any a mod registers, such as Class and "
                        + "Bonus. A name nothing registered changes nothing. Leave it out for Class, World, Bonus, "
                        + "Boost, Prestige, Rested. A loss is never changed.").add()
                .appendInherited(new KeyedCodec<>("Additive", Codec.STRING_ARRAY, false),
                        (o, v) -> o.additive = v, o -> o.additive, (o, p) -> o.additive = p.additive)
                .documentation("The factors whose extras add together rather than multiplying, run where the first "
                        + "of them stands in Factors: with a Bonus of 1.2 and a Boost of 1.5 a gain pays 1.7 times "
                        + "(1 + 0.2 + 0.5), not 1.8. Leave it out for Bonus and Boost, the way skills have always "
                        + "stacked them; an empty list multiplies every factor.").add()
                .appendInherited(new KeyedCodec<>("Rested", Rested.CODEC, false),
                        (o, v) -> o.rested = v, o -> o.rested, (o, p) -> o.rested = p.rested)
                .documentation("A rested pool for this track: while a player is away it fills, and gains pay "
                        + "extra until it is spent. Leave it out for none; no track has one unless it is "
                        + "written.").add()
                .build();

        public Gain() {
        }
    }

    /**
     * The Rested group: how a track's rested pool fills while a player is away and how fast gains spend it. Off
     * unless written; each leaf has a default, and the pool fills from {@code PerHour} and {@code PercentPerHour}
     * together.
     */
    public static final class Rested {

        /** What a gain pays while the pool lasts, unauthored. */
        public static final double DEFAULT_MULTIPLIER = 2.0;
        /** The shortest time away that fills anything, unauthored. */
        public static final int DEFAULT_MIN_OFFLINE_MINUTES = 30;
        /** The longest time away that counts, unauthored. */
        public static final int DEFAULT_MAX_OFFLINE_HOURS = 168;
        /** The most the pool holds, unauthored: the MMO's own rested cap. */
        public static final long DEFAULT_MAX_POOL = 50_000L;

        @Nullable protected Boolean enabled;
        @Nullable protected Double multiplier;
        @Nullable protected Double perHour;
        @Nullable protected Double percentPerHour;
        @Nullable protected Long maxPool;
        @Nullable protected Integer minOfflineMinutes;
        @Nullable protected Integer maxOfflineHours;

        public static final BuilderCodec<Rested> CODEC = BuilderCodec.builder(Rested.class, Rested::new)
                .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                        (o, v) -> o.enabled = v, o -> o.enabled, (o, p) -> o.enabled = p.enabled)
                .metadata(EditorSchema.defaultValue(true))
                .documentation("Whether the pool is on. Unauthored means true once this group is written; false "
                        + "turns it off (a server owner's way to switch off a pool a pack wrote).").add()
                .appendInherited(new KeyedCodec<>("Multiplier", Codec.DOUBLE, false),
                        (o, v) -> o.multiplier = v, o -> o.multiplier, (o, p) -> o.multiplier = p.multiplier)
                .metadata(EditorSchema.defaultValue(DEFAULT_MULTIPLIER))
                .documentation("What a gain pays while the pool lasts: 2 doubles it, the extra taken from the "
                        + "pool. 2 when unauthored; 1 or less pays nothing extra.").add()
                .appendInherited(new KeyedCodec<>("PerHour", Codec.DOUBLE, false),
                        (o, v) -> o.perHour = v, o -> o.perHour, (o, p) -> o.perHour = p.perHour)
                .metadata(EditorSchema.defaultValue(0.0))
                .documentation("How much the pool fills for each hour away. 0 when unauthored.").add()
                .appendInherited(new KeyedCodec<>("PercentPerHour", Codec.DOUBLE, false),
                        (o, v) -> o.percentPerHour = v, o -> o.percentPerHour,
                        (o, p) -> o.percentPerHour = p.percentPerHour)
                .metadata(EditorSchema.defaultValue(0.0))
                .documentation("How much the pool fills for each hour away as a percent of the player's next "
                        + "level (5 is five percent of what that level takes), on top of PerHour. 0 when "
                        + "unauthored.").add()
                .appendInherited(new KeyedCodec<>("MaxPool", Codec.LONG, false),
                        (o, v) -> o.maxPool = v, o -> o.maxPool, (o, p) -> o.maxPool = p.maxPool)
                .metadata(EditorSchema.defaultValue(DEFAULT_MAX_POOL))
                .documentation("The most the pool holds. 50000 when unauthored, the MMO's own cap; 0 or less sets "
                        + "no limit.").add()
                .appendInherited(new KeyedCodec<>("MinOfflineMinutes", Codec.INTEGER, false),
                        (o, v) -> o.minOfflineMinutes = v, o -> o.minOfflineMinutes,
                        (o, p) -> o.minOfflineMinutes = p.minOfflineMinutes)
                .metadata(EditorSchema.defaultValue((long) DEFAULT_MIN_OFFLINE_MINUTES))
                .documentation("The shortest time away, in minutes, that fills the pool at all; at or past it the "
                        + "whole time away counts. 30 when unauthored.").add()
                .appendInherited(new KeyedCodec<>("MaxOfflineHours", Codec.INTEGER, false),
                        (o, v) -> o.maxOfflineHours = v, o -> o.maxOfflineHours,
                        (o, p) -> o.maxOfflineHours = p.maxOfflineHours)
                .metadata(EditorSchema.defaultValue((long) DEFAULT_MAX_OFFLINE_HOURS))
                .documentation("The longest time away, in hours, that counts. 168 (a week) when unauthored.").add()
                .build();

        public Rested() {
        }

        /** Is the pool on (the group written, and not switched off)? */
        public boolean isEnabled() {
            return enabled == null || enabled;
        }

        /** What a gain pays while the pool lasts, at least 1. */
        public double multiplier() {
            double m = multiplier == null || !Double.isFinite(multiplier) ? DEFAULT_MULTIPLIER : multiplier;
            return Math.max(1.0, m);
        }

        /** How much the pool fills an hour away, 0 or more. */
        public double perHour() {
            return perHour == null || !Double.isFinite(perHour) ? 0.0 : Math.max(0.0, perHour);
        }

        /** How much the pool fills an hour away, as a percent of the next level, 0 or more. */
        public double percentPerHour() {
            return percentPerHour == null || !Double.isFinite(percentPerHour) ? 0.0 : Math.max(0.0, percentPerHour);
        }

        /** The most the pool holds: {@link #DEFAULT_MAX_POOL} unauthored, {@link Long#MAX_VALUE} for no limit. */
        public long maxPool() {
            if (maxPool == null) {
                return DEFAULT_MAX_POOL;
            }
            return maxPool <= 0L ? Long.MAX_VALUE : maxPool;
        }

        /** The shortest time away that counts, in minutes, 0 or more. */
        public int minOfflineMinutes() {
            return minOfflineMinutes == null ? DEFAULT_MIN_OFFLINE_MINUTES : Math.max(0, minOfflineMinutes);
        }

        /** The longest time away that counts, in hours, 0 or more. */
        public int maxOfflineHours() {
            return maxOfflineHours == null ? DEFAULT_MAX_OFFLINE_HOURS : Math.max(0, maxOfflineHours);
        }
    }

    /**
     * One unlock row: what it is known by, the levels it pays at, and what it pays. A player's record keeps how
     * far each row has paid under the row's {@link #key()}, never its place in the list, so rows can be added,
     * removed or reordered on a live track and each keeps its own mark.
     */
    public static final class Unlock {

        /** The key of a row that names no level (it never pays, so its key reaches nothing). */
        static final String NO_LEVEL_KEY = "none";

        @Nullable protected String id;
        @Nullable protected int[] at;
        @Nullable protected Integer every;
        @Nullable protected RewardEntryAsset[] rewards;

        /** {@link #key()}, worked out on first ask (a row is never changed once decoded). */
        @Nullable private volatile String key;

        public static final BuilderCodec<Unlock> CODEC = BuilderCodec.builder(Unlock.class, Unlock::new)
                .appendInherited(new KeyedCodec<>("Id", Codec.STRING, false),
                        (o, v) -> o.id = v, o -> o.id, (o, p) -> o.id = p.id)
                .documentation("What this row is known by in a player's record and in the ids of its Claims "
                        + "(letters, digits, _ and -; anything else reads as _, and case does not matter). Rows can "
                        + "then be added, removed or reordered on a live track and none pays twice or is skipped. "
                        + "Unauthored, the row is known by its lowest At level and its Every (At [2, 5] is at_2, "
                        + "Every 4 is every_4, both together at_2_every_4), so adding a higher level to a live row "
                        + "keeps its record, while changing its lowest level or its Every makes it a new row that "
                        + "pays again: give it an Id before you do. Two rows known by the same name share one "
                        + "record, so the later one pays no level the first has passed.").add()
                .appendInherited(new KeyedCodec<>("At", Codec.INT_ARRAY, false),
                        (o, v) -> o.at = v, o -> o.at, (o, p) -> o.at = p.at)
                .documentation("The levels this row pays at, each once.").add()
                .appendInherited(new KeyedCodec<>("Every", Codec.INTEGER, false),
                        (o, v) -> o.every = v, o -> o.every, (o, p) -> o.every = p.every)
                .documentation("Pay at every multiple of this level as well (15 pays at 15, 30, 45, ...). Zero "
                        + "or less, or unauthored, adds no levels.").add()
                .appendInherited(new KeyedCodec<>("Rewards",
                                new ArrayCodec<>(RewardEntryAsset.CODEC, RewardEntryAsset[]::new), false),
                        (o, v) -> o.rewards = v, o -> o.rewards, (o, p) -> o.rewards = p.rewards)
                .documentation("What each level of this row pays, as one Claim the player collects: the same "
                        + "reward entries a quest pays.").add()
                .build();

        public Unlock() {
        }

        /** Java-side factory, for a test or a consumer's own default; sets the same fields the codec fills. */
        @Nonnull
        public static Unlock of(@Nullable int[] at, @Nullable Integer every, @Nullable RewardEntryAsset[] rewards) {
            Unlock u = new Unlock();
            u.at = at == null ? null : at.clone();
            u.every = every;
            u.rewards = rewards == null ? null : rewards.clone();
            return u;
        }

        /**
         * What this row is known by in a player's record and in its Claims' ids: its {@code Id}, lower-cased, or,
         * unauthored, a name built from its lowest At level and its Every ({@code at_2}, {@code every_4},
         * {@code at_2_every_4}; {@value #NO_LEVEL_KEY} for a row naming none), never its place in the list. Any
         * character other than a
         * letter, a digit, {@code _} or {@code -} reads as {@code _}, so a key never carries a Claim id's
         * {@code :}.
         */
        @Nonnull
        public String key() {
            String held = key;
            if (held == null) {
                held = deriveKey();
                key = held;
            }
            return held;
        }

        /**
         * {@link #key()} worked out; once per row instance, since every gain asks it of every row. A keyless row is
         * known by its lowest At level and its Every alone, which stay as they are when a later level is added
         * to a live row, so its paid mark keeps standing and no level it already paid pays again.
         */
        @Nonnull
        private String deriveKey() {
            String own = blankToNull(id);
            if (own != null) {
                return keyChars(own);
            }
            StringBuilder out = new StringBuilder();
            int lowest = at == null ? 0 : Arrays.stream(at).filter(l -> l >= 1).min().orElse(0);
            if (lowest > 0) {
                out.append("at_").append(lowest);
            }
            if (every() > 0) {
                out.append(out.length() == 0 ? "" : "_").append("every_").append(every());
            }
            return out.length() == 0 ? NO_LEVEL_KEY : out.toString();
        }

        /** {@code raw} as a key: lower-cased, every character but a letter, a digit, {@code _} and {@code -} as _. */
        @Nonnull
        private static String keyChars(@Nonnull String raw) {
            String lower = raw.toLowerCase(Locale.ROOT);
            StringBuilder out = new StringBuilder(lower.length());
            for (int i = 0; i < lower.length(); i++) {
                char c = lower.charAt(i);
                out.append(Character.isLetterOrDigit(c) || c == '_' || c == '-' ? c : '_');
            }
            return out.toString();
        }

        /** The At levels as written, or an empty array. */
        @Nonnull
        public int[] atLevels() {
            return at == null ? new int[0] : at.clone();
        }

        /** The Every step, 0 when unauthored or not positive. */
        public int every() {
            return every == null || every <= 0 ? 0 : every;
        }

        /** Does this row pay at {@code level}: one of its At levels, or a multiple of its Every? */
        public boolean paysAt(int level) {
            if (level < 1) {
                return false;
            }
            if (at != null) {
                for (int l : at) {
                    if (l == level) {
                        return true;
                    }
                }
            }
            int step = every();
            return step > 0 && level % step == 0;
        }

        /** The first level above {@code level} this row pays at, or {@link Integer#MAX_VALUE} when none is. */
        public int nextLevelAfter(int level) {
            long next = Integer.MAX_VALUE;
            if (at != null) {
                for (int l : at) {
                    if (l > level && l >= 1 && l < next) {
                        next = l;
                    }
                }
            }
            int step = every();
            if (step > 0) {
                long multiple = ((long) Math.max(0, level) / step + 1) * step;
                next = Math.min(next, multiple);
            }
            return (int) Math.min(Integer.MAX_VALUE, next);
        }

        /** Does this row name any level at all? */
        public boolean namesALevel() {
            if (every() > 0) {
                return true;
            }
            if (at != null) {
                for (int l : at) {
                    if (l >= 1) {
                        return true;
                    }
                }
            }
            return false;
        }

        /** What one level of this row pays, as reward specs (rows its mod gate keeps out dropped); empty when none. */
        @Nonnull
        public List<RewardSpec> rewards() {
            List<RewardSpec> out = new ArrayList<>();
            if (rewards == null) {
                return out;
            }
            for (RewardEntryAsset entry : rewards) {
                RewardSpec spec = entry == null ? null : entry.toSpec();
                if (spec != null) {
                    out.add(spec);
                }
            }
            return List.copyOf(out);
        }

        /** The reward entries as written, for an audit (the mod gate's absent rows left out). */
        @Nonnull
        public RewardEntryAsset[] presentEntries() {
            return RewardEntryAsset.present(rewards);
        }
    }
}
