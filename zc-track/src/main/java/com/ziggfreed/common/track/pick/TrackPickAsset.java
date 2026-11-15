package com.ziggfreed.common.track.pick;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.assetstore.map.JsonAssetWithMap;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.array.ArrayCodec;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.ziggfreed.common.asset.EditorSchema;
import com.ziggfreed.common.commerce.asset.CostAsset;
import com.ziggfreed.common.codec.JsonTreeCodec;
import com.ziggfreed.common.grant.BuiltInGrants;
import com.ziggfreed.common.grant.Grant;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.progress.gate.StoreGates;
import com.ziggfreed.common.text.ContentTextAsset;
import com.ziggfreed.common.track.TrackKey;
import com.ziggfreed.common.track.asset.PrestigeReset;
import com.ziggfreed.common.track.asset.TrackAsset;

/**
 * One pick on a track, at {@code Server/ZiggfreedCommon/TrackPicks/<Owner>/<Id>.json}: the one shape every choice,
 * Mastery node, trainer offer and prestige perk is (R599). The FILE NAME is the id; the folders are the author's own.
 * What a file writes chooses its style, never a mode:
 *
 * <ul>
 *   <li><b>A choice</b> writes {@code Groups}: each group opens a slot at its levels ({@code From}, {@code Every},
 *       {@code To}, {@code At}, less {@code Skip}), where the player chooses {@code Choose} of the options open
 *       there (shared {@link PickOptionAsset} files, each open between its own {@code From} and {@code To}); what
 *       an option grants is its payload. One line covers every level:
 *       {@code "Groups": [ { "From": 2, "Every": 1, "Choose": 1, "Options": [ ... ] } ]}.</li>
 *   <li><b>A pick bought outright</b> writes no {@code Groups}: it opens at {@code At}, may be taken up to
 *       {@code Repeat.Max} times, each take paying its {@code Cost} (none means free) and granting its own
 *       {@code Grants}. The points style is the track's {@code Points.PerLevel} with picks such as
 *       {@code { "Cost": { "Points": 1 }, "Repeat": { "Max": 40 }, "Grants": [ ... ] }}.</li>
 * </ul>
 *
 * <p>Either style also reads {@code Requires} (asked of the player; a plain mod condition keeps the file out where
 * the mod is not installed), {@code Prerequisites} (picks or options on the same track taken first), {@code Where}
 * (the trainers whose page offers it; none means the track's own page), {@code SpendCap} (the most points of its
 * Cost's pool a player may have spent with this one taken), {@code Node} (the either-or it is one face of, on any
 * track) and {@code ResetsAs} (which of the track's {@code Prestige.Resets} switches decides what a prestige does to
 * it, R852). A choice's offers are a function of the files alone (R724): every option open at a slot's level, the
 * same on every server. Every leaf inherits through {@code Parent}; a server owner retunes a pick in
 * {@code mods/ziggfreedcommon/track-picks.json}.
 *
 * <p>A file writing {@code Abstract} is a skeleton the picks under it inherit from (a tree's track and branch), never a
 * pick, and a pick lives only while every pick up its {@code Parent} chain is switched on, so a skeleton switched off
 * in one file (or one owner entry) takes its whole tree away ({@link TrackPicks#lineageLive}).
 */
public final class TrackPickAsset implements JsonAssetWithMap<String, DefaultAssetMap<String, TrackPickAsset>> {

    /** The store's content path; the folders below it are the author's own grouping. */
    public static final String TYPE_ROOT = "ZiggfreedCommon/TrackPicks";

    /** The sentence the Asset Editor shows beside a pick's own {@code Requires}. */
    static final String REQUIRES_DOCUMENTATION = "What a player must meet to take this pick, in the Requires "
            + "shape quests use, every condition read. A plain { \"Factor\": \"hytale:mod_installed\", \"Param\": "
            + "\"Group:Name\", \"Min\": 1 } keeps the file out entirely where that mod is not installed. A choice "
            + "whose Requires the player does not meet is not owed to them.";

    private String id;
    private AssetExtraInfo.Data data;

    @Nullable protected Boolean enabled;
    @Nullable protected Boolean skeleton;
    /** The Parent as the pack layer wrote it, for an owner entry (whose own decode names itself): see {@link #parentId}. */
    @Nullable private String lineage;
    @Nullable protected String track;
    @Nullable protected ContentTextAsset text;
    @Nullable protected String icon;
    @Nullable protected Integer order;
    @Nullable protected Integer at;
    @Nullable protected Integer choose;
    @Nullable protected Group[] groups;
    @Nullable protected JsonElement vars;
    @Nullable protected Cost cost;
    @Nullable protected String[] prerequisites;
    @Nullable private GateSpec gate;
    @Nullable protected Repeat repeat;
    @Nullable protected Integer spendCap;
    @Nullable protected Where where;
    @Nullable protected String resetsAs;
    @Nullable protected String branch;
    @Nullable protected String[] node;
    @Nullable protected Grant[] grants;

    /** The live part of the {@code Requires} block, lifted once per instance ({@link StoreGates#live}). */
    @Nullable private volatile GateSpec[] live;

    static {
        // Grants (and a fold reading an option's grants) decodes the library's own kinds in any JVM.
        BuiltInGrants.ensure();
    }

    public static final AssetBuilderCodec<String, TrackPickAsset> CODEC = AssetBuilderCodec.builder(
                    TrackPickAsset.class,
                    TrackPickAsset::new,
                    Codec.STRING,
                    (a, id) -> a.id = id,
                    a -> a.id,
                    (a, extra) -> a.data = extra,
                    a -> a.data)
            .appendInherited(new KeyedCodec<>("Enabled", Codec.BOOLEAN, false),
                    (a, v) -> a.enabled = v, a -> a.enabled, (a, p) -> a.enabled = p.enabled)
            .metadata(EditorSchema.defaultValue(true))
            .documentation("Whether this pick exists on this server; unauthored means true. False takes it away: "
                    + "nothing is owed or offered, and the grants of picks already made are taken off. False on a "
                    + "pick other picks name as their Parent takes every pick under it away too, whatever they "
                    + "write, so one file (or one owner entry) switches a whole tree off.").add()
            .append(new KeyedCodec<>("Abstract", Codec.BOOLEAN, false),
                    (a, v) -> a.skeleton = v, a -> a.skeleton)
            .metadata(EditorSchema.defaultValue(false))
            .documentation("True makes this file a skeleton other picks inherit from (their Parent), never a pick "
                    + "itself: it is on no track's page and nobody takes it. Not inherited, so a pick under a "
                    + "skeleton is a real pick. False when unauthored.").add()
            .appendInherited(new KeyedCodec<>("Track", Codec.STRING, false),
                    (a, v) -> a.track = v, a -> a.track, (a, p) -> a.track = p.track)
            .metadata(EditorSchema.assetRef(TrackAsset.class))
            .documentation("The track this pick sits on, by id: its levels open the pick, its points pay for it "
                    + "unless Cost names another track, and its prestige setting decides what a prestige does to it. "
                    + "A pick naming no switched-on track is nowhere.").add()
            .appendInherited(new KeyedCodec<>("Text", ContentTextAsset.CODEC, false),
                    (a, v) -> a.text = v, a -> a.text, (a, p) -> a.text = p.text)
            .documentation("What the pick is called (TitleKey) and its description (FlavorKey), as localization keys "
                    + "in your own lang file. Without a TitleKey it is named by its id.").add()
            .appendInherited(new KeyedCodec<>("Icon", Codec.STRING, false),
                    (a, v) -> a.icon = v, a -> a.icon, (a, p) -> a.icon = p.icon)
            .metadata(EditorSchema.assetRef(Item.class))
            .documentation("The item whose picture stands for the pick.").add()
            .appendInherited(new KeyedCodec<>("Order", Codec.INTEGER, false),
                    (a, v) -> a.order = v, a -> a.order, (a, p) -> a.order = p.order)
            .metadata(EditorSchema.defaultValue(0L))
            .documentation("Where the pick sorts among the track's others: lower first, then by id.").add()
            .appendInherited(new KeyedCodec<>("At", Codec.INTEGER, false),
                    (a, v) -> a.at = v, a -> a.at, (a, p) -> a.at = p.at)
            .metadata(EditorSchema.defaultValue(1L))
            .documentation("The level this pick opens at: nothing below it is owed or may be taken. 1 when "
                    + "unauthored.").add()
            .appendInherited(new KeyedCodec<>("Choose", Codec.INTEGER, false),
                    (a, v) -> a.choose = v, a -> a.choose, (a, p) -> a.choose = p.choose)
            .metadata(EditorSchema.defaultValue(1L))
            .documentation("How many options a player chooses at each slot of this pick's groups, unless a group "
                    + "says otherwise. 1 when unauthored.").add()
            .appendInherited(new KeyedCodec<>("Groups", new ArrayCodec<>(Group.CODEC, Group[]::new), false),
                    (a, v) -> a.groups = v, a -> a.groups, (a, p) -> a.groups = p.groups)
            .documentation("The choice groups: each opens one slot at each of its levels, where the player chooses "
                    + "from the options offered there. Leave it out for a pick bought outright, which grants its own "
                    + "Grants. Under Parent this list replaces the parent's whole.").add()
            .appendInherited(new KeyedCodec<>("Vars", JsonTreeCodec.object(), false),
                    (a, v) -> a.vars = v, a -> a.vars, (a, p) -> a.vars = p.vars)
            .documentation("The values this pick's options are filled with: an option file writing {Held} or "
                    + "{Cause} in its Grants reads them from here (a group's own Vars win), so one option serves "
                    + "every track. Each value is a string, number or true/false.").add()
            .appendInherited(new KeyedCodec<>("Cost", Cost.CODEC, false),
                    (a, v) -> a.cost = v, a -> a.cost, (a, p) -> a.cost = p.cost)
            .documentation("What each take of this pick costs (each level of a choice made), in a track's points, "
                    + "and for a pick bought outright a Price in any wallet or item besides. Leave it out for a free "
                    + "pick.").add()
            .appendInherited(new KeyedCodec<>("Prerequisites", Codec.STRING_ARRAY, false),
                    (a, v) -> a.prerequisites = v, a -> a.prerequisites, (a, p) -> a.prerequisites = p.prerequisites)
            .documentation("Picks (by id) or options (by id) on the same track that must each have been taken at "
                    + "least once before this one opens.").add()
            .appendInherited(new KeyedCodec<>("Requires", GateSpec.CODEC, false),
                    (a, v) -> a.gate = v, a -> a.gate, (a, p) -> a.gate = p.gate)
            .documentation(REQUIRES_DOCUMENTATION).add()
            .appendInherited(new KeyedCodec<>("Repeat", Repeat.CODEC, false),
                    (a, v) -> a.repeat = v, a -> a.repeat, (a, p) -> a.repeat = p.repeat)
            .documentation("How many times a pick bought outright may be taken. Leave it out to take it once.").add()
            .appendInherited(new KeyedCodec<>("SpendCap", Codec.INTEGER, false),
                    (a, v) -> a.spendCap = v, a -> a.spendCap, (a, p) -> a.spendCap = p.spendCap)
            .documentation("The most points of this pick's Cost pool a player may have spent in all, this take "
                    + "included: 12 on every Mastery pick leaves room for two of three branches however many points "
                    + "are earned. Leave it out for no cap.").add()
            .appendInherited(new KeyedCodec<>("Where", Where.CODEC, false),
                    (a, v) -> a.where = v, a -> a.where, (a, p) -> a.where = p.where)
            .documentation("Where this pick is taken: the trainers whose page offers it. Leave it out to take it on "
                    + "the track's own page; a server owner turns trainers off by giving it no Placements.").add()
            .appendInherited(new KeyedCodec<>("ResetsAs", Codec.STRING, false),
                    (a, v) -> a.resetsAs = v, a -> a.resetsAs, (a, p) -> a.resetsAs = p.resetsAs)
            .metadata(PrestigeReset.editorHint())
            .documentation(PrestigeReset.DOCUMENTATION + " A prestige the track's Prestige.Resets says keeps this "
                    + "kind leaves the pick exactly as it is, held even if a branch's points no longer meet its "
                    + "Requires; one it resets follows the track's prestige Build. It governs a prestige only: a "
                    + "respec clears every pick but what a trainer taught and gives its points back.").add()
            .appendInherited(new KeyedCodec<>("Branch", Codec.STRING, false),
                    (a, v) -> a.branch = v, a -> a.branch, (a, p) -> a.branch = p.branch)
            .documentation("The branch of the track's tree this pick sits in, by id (one its track's Branches "
                    + "names): the points spent on it count toward that branch, so a later row's Requires can "
                    + "open on them (ziggfreedcommon:track_branch_spent, Param <Track>:<Branch>, Min 5). Leave it "
                    + "out for a pick in no branch, such as a root row.").add()
            .appendInherited(new KeyedCodec<>("Node", Codec.STRING_ARRAY, false),
                    (a, v) -> a.node = v, a -> a.node, (a, p) -> a.node = p.node)
            .documentation("The either-or groups this pick is one face of, by ids of your own: every pick that "
                    + "writes a group's id is one choice with the others writing it, on its own track or on another "
                    + "(a group one face of which sits on each of five tracks lets a player hold it on one of them), "
                    + "so taking one face locks every other face of each of its groups until it is undone or its "
                    + "track is respecced. The first id is the row's own choice, the one the tree audit weighs a row "
                    + "by; any after it are further either-ors the pick belongs to (two picks of different rows that "
                    + "rule each other out). Give a group an id no other track's picks use unless they belong to the "
                    + "same choice. Leave it out for a pick that stands alone.").add()
            .appendInherited(new KeyedCodec<>("Grants", Grant.ARRAY_CODEC, false),
                    (a, v) -> a.grants = v, a -> a.grants, (a, p) -> a.grants = p.grants)
            .documentation("What a pick bought outright grants each time it is taken (a choice grants what its "
                    + "chosen options grant). Each entry names its Kind: Stat raises a native stat; other mods add "
                    + "kinds of their own.").add()
            .build();

    public TrackPickAsset() {
    }

    @Override
    public String getId() {
        return id;
    }

    /** Is the pick switched on in its own file? Unauthored means true. */
    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    /** Is this file a skeleton ({@code Abstract}): inherited from, never a pick itself? */
    public boolean isAbstract() {
        return Boolean.TRUE.equals(skeleton);
    }

    /**
     * The id of the pick this file names as its {@code Parent}, as the pack layer wrote it, or null for none: what
     * the fold walks to switch a pick off with the skeleton above it. An owner entry (decoded against its own pack
     * file) answers its pack file's Parent.
     */
    @Nullable
    public String parentId() {
        if (lineage != null) {
            return lineage.isEmpty() ? null : lineage;
        }
        Object parent = data == null ? null : data.getParentKey();
        String key = parent == null ? null : blankToNull(parent.toString());
        return key == null || key.equalsIgnoreCase(id == null ? "" : id.trim()) ? null : key;
    }

    /**
     * Carry {@code below}'s lineage (its {@code Abstract} and its {@code Parent}) onto this owner entry, decoded
     * against it: an owner retunes a pick's leaves, never which skeleton it is or sits under. The owner layer's merge
     * calls it ({@link TrackPickConfig}).
     */
    void keepLineageOf(@Nonnull TrackPickAsset below) {
        skeleton = below.skeleton;
        String parent = below.parentId();
        lineage = parent == null ? "" : parent;
    }

    /** The track id as written, trimmed, or null. */
    @Nullable
    public String trackId() {
        return blankToNull(track);
    }

    @Nullable
    public ContentTextAsset getText() {
        return text;
    }

    /** The localization key the pick is named by, or null. */
    @Nullable
    public String titleKey() {
        return text == null ? null : blankToNull(text.getTitleKey());
    }

    @Nullable
    public String icon() {
        return blankToNull(icon);
    }

    public int order() {
        return order == null ? 0 : order;
    }

    /** The level it opens at, 1 or more. */
    public int opensAt() {
        return at == null ? 1 : Math.max(1, at);
    }

    /** How many options each slot takes, unless a group says otherwise; at least 1. */
    public int choose() {
        return choose == null ? 1 : Math.max(1, choose);
    }

    /** The groups as written (a null entry left out); empty for a pick bought outright. */
    @Nonnull
    public List<Group> groups() {
        List<Group> out = new ArrayList<>();
        if (groups != null) {
            for (Group group : groups) {
                if (group != null) {
                    out.add(group);
                }
            }
        }
        return List.copyOf(out);
    }

    /** Is this a choice (it writes groups) rather than a pick bought outright? */
    public boolean isChoice() {
        return groups != null && groups.length > 0;
    }

    /** The {@code Vars} object as written, or null. */
    @Nullable
    public JsonElement vars() {
        return vars;
    }

    /**
     * Which of its track's {@code Prestige.Resets} switches decides what a prestige does to this pick (R852): its
     * {@code ResetsAs} as written, else {@link PrestigeReset#TRAINER_PICKS} for a pick whose {@code Where} names a
     * trainer and {@link PrestigeReset#PICKS} for any other.
     */
    @Nonnull
    public PrestigeReset resetsAs() {
        PrestigeReset own = PrestigeReset.of(resetsAs);
        if (own != null) {
            return own;
        }
        return placements().isEmpty() ? PrestigeReset.PICKS : PrestigeReset.TRAINER_PICKS;
    }

    /** The points each take costs, 0 for a free pick. */
    public int costPoints() {
        Integer points = cost == null ? null : cost.points;
        return points == null ? 0 : Math.max(0, points);
    }

    /** The track whose points pay for it: the Cost's own Track, else this pick's track; null for none. */
    @Nullable
    public String costTrack() {
        String own = cost == null ? null : blankToNull(cost.track);
        return own != null ? own : trackId();
    }

    /** What each take also costs in wallets or items ({@code Cost.Price}) as written, or null for none. */
    @Nullable
    public CostAsset price() {
        return cost == null || cost.price == null || cost.price.isFree() ? null : cost.price;
    }

    /** The prerequisite ids, lower-cased, blanks left out. */
    @Nonnull
    public List<String> prerequisites() {
        return lowered(prerequisites);
    }

    /** The {@code Requires} block as written, or null. */
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

    /** How many times a pick bought outright may be taken: 1 unauthored, {@link Integer#MAX_VALUE} for no limit. */
    public int repeatMax() {
        Integer max = repeat == null ? null : repeat.max;
        if (max == null) {
            return 1;
        }
        return max <= 0 ? Integer.MAX_VALUE : max;
    }

    /** The spend cap, or 0 for none. */
    public int spendCap() {
        return spendCap == null ? 0 : Math.max(0, spendCap);
    }

    /** The trainers (NPC placement ids) whose page offers it, lower-cased; empty for the track's own page. */
    @Nonnull
    public List<String> placements() {
        return where == null ? List.of() : lowered(where.placements);
    }

    /** The branch this pick sits in, lower-cased, or null for none. */
    @Nullable
    public String branch() {
        String id = TrackKey.normalize(branch);
        return id.isEmpty() ? null : id;
    }

    /**
     * Every either-or group ({@code Node}) this pick is one face of, lower-cased, in written order, blanks and repeats
     * left out; empty for a pick that stands alone (R859, R895).
     */
    @Nonnull
    public List<String> nodes() {
        return lowered(node);
    }

    /**
     * The first group it names, lower-cased: its row's own choice, the one the tree audit weighs a row by (a branch's
     * total at the dearest face, a node's even faces); null for none.
     */
    @Nullable
    public String node() {
        List<String> all = nodes();
        return all.isEmpty() ? null : all.get(0);
    }

    /** The grants a pick bought outright pays (null entries left out). */
    @Nonnull
    public List<Grant> grants() {
        List<Grant> out = new ArrayList<>();
        if (grants != null) {
            for (Grant grant : grants) {
                if (grant != null) {
                    out.add(grant);
                }
            }
        }
        return List.copyOf(out);
    }

    @Nonnull
    static List<String> lowered(@Nullable String[] ids) {
        if (ids == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            String key = TrackKey.normalize(id);
            if (!key.isEmpty() && !out.contains(key)) {
                out.add(key);
            }
        }
        return List.copyOf(out);
    }

    @Nullable
    static String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * One choice group: the levels it opens a slot at, how many options a slot takes, and the options it offers. A
     * player's record knows each slot as {@code <pick>/<group key>/<level>}, the group key its {@code Id} or,
     * unauthored, built from its own levels, never its place in the list.
     */
    public static final class Group {

        @Nullable protected String id;
        @Nullable protected Integer from;
        @Nullable protected Integer every;
        @Nullable protected Integer to;
        @Nullable protected int[] at;
        @Nullable protected int[] skip;
        @Nullable protected Integer choose;
        @Nullable protected String[] options;
        @Nullable protected JsonElement vars;

        public static final BuilderCodec<Group> CODEC = BuilderCodec.builder(Group.class, Group::new)
                .appendInherited(new KeyedCodec<>("Id", Codec.STRING, false),
                        (o, v) -> o.id = v, o -> o.id, (o, p) -> o.id = p.id)
                .documentation("What this group's slots are known by in a player's record (letters, digits, _ and "
                        + "-). Unauthored, it is known by its lowest level and its Every (From 2, Every 1 is "
                        + "from_2_every_1), so give it an Id before changing either on a live track.").add()
                .appendInherited(new KeyedCodec<>("From", Codec.INTEGER, false),
                        (o, v) -> o.from = v, o -> o.from, (o, p) -> o.from = p.from)
                .documentation("The first level this group opens a slot at; with Every, the start of the run.").add()
                .appendInherited(new KeyedCodec<>("Every", Codec.INTEGER, false),
                        (o, v) -> o.every = v, o -> o.every, (o, p) -> o.every = p.every)
                .metadata(EditorSchema.defaultValue(1L))
                .documentation("A slot every this many levels from From on (1 is every level). 1 when unauthored; "
                        + "0 or less opens From alone.").add()
                .appendInherited(new KeyedCodec<>("To", Codec.INTEGER, false),
                        (o, v) -> o.to = v, o -> o.to, (o, p) -> o.to = p.to)
                .documentation("The last level a slot may open at. Leave it out to run as far as the track goes.")
                .add()
                .appendInherited(new KeyedCodec<>("At", Codec.INT_ARRAY, false),
                        (o, v) -> o.at = v, o -> o.at, (o, p) -> o.at = p.at)
                .documentation("Levels that open a slot as well as (or instead of) the From and Every run.").add()
                .appendInherited(new KeyedCodec<>("Skip", Codec.INT_ARRAY, false),
                        (o, v) -> o.skip = v, o -> o.skip, (o, p) -> o.skip = p.skip)
                .documentation("Levels that open no slot here, such as the levels another group's milestones take.")
                .add()
                .appendInherited(new KeyedCodec<>("Choose", Codec.INTEGER, false),
                        (o, v) -> o.choose = v, o -> o.choose, (o, p) -> o.choose = p.choose)
                .documentation("How many options a player chooses at each slot. Unauthored, the pick's own Choose.")
                .add()
                .appendInherited(new KeyedCodec<>("Options", Codec.STRING_ARRAY, false),
                        (o, v) -> o.options = v, o -> o.options, (o, p) -> o.options = p.options)
                .documentation("The options this group offers, by id (files under "
                        + "Server/ZiggfreedCommon/TrackPickOptions), in this order: every option open at a slot's level "
                        + "(between its own From and To) is offered there, and the player chooses Choose of them.")
                .add()
                .appendInherited(new KeyedCodec<>("Vars", JsonTreeCodec.object(), false),
                        (o, v) -> o.vars = v, o -> o.vars, (o, p) -> o.vars = p.vars)
                .documentation("Values this group's options are filled with, over the pick's own Vars.").add()
                .build();

        public Group() {
        }

        /** Java-side factory (a test); sets the same fields the codec fills. */
        @Nonnull
        public static Group of(@Nullable String id, @Nullable Integer from, @Nullable Integer every,
                @Nullable Integer to, @Nullable String[] options) {
            Group group = new Group();
            group.id = id;
            group.from = from;
            group.every = every;
            group.to = to;
            group.options = options == null ? null : options.clone();
            return group;
        }

        /** The key this group's slots are filed under: its Id, else built from its lowest level and its Every. */
        @Nonnull
        public String key() {
            String own = blankToNull(id);
            if (own != null) {
                StringBuilder out = new StringBuilder();
                for (char c : own.toLowerCase(Locale.ROOT).toCharArray()) {
                    out.append(Character.isLetterOrDigit(c) || c == '_' || c == '-' ? c : '_');
                }
                return out.toString();
            }
            int lowest = Integer.MAX_VALUE;
            if (from != null && from >= 1) {
                lowest = from;
            }
            if (at != null) {
                for (int level : at) {
                    if (level >= 1 && level < lowest) {
                        lowest = level;
                    }
                }
            }
            if (lowest == Integer.MAX_VALUE) {
                return "none";
            }
            return from != null && from == lowest ? "from_" + lowest + "_every_" + every() : "at_" + lowest;
        }

        /** The Every step: 1 unauthored, 0 when a run opens From alone. */
        public int every() {
            return every == null ? 1 : Math.max(0, every);
        }

        /** How many a slot takes: the group's own, else {@code fallback}; at least 1. */
        public int choose(int fallback) {
            return choose == null ? Math.max(1, fallback) : Math.max(1, choose);
        }

        /**
         * Every level from {@code opensAt} up to {@code upTo} (both in) this group opens a slot at, ascending: its
         * From and Every run under To, its At levels, less its Skip levels.
         */
        @Nonnull
        public List<Integer> levels(int opensAt, int upTo) {
            int top = to == null ? upTo : Math.min(upTo, to);
            int bottom = Math.max(1, opensAt);
            TreeSet<Integer> out = new TreeSet<>();
            if (from != null && top >= bottom) {
                int step = every();
                int level = Math.max(1, from);
                if (step <= 0) {
                    if (level >= bottom && level <= top) {
                        out.add(level);
                    }
                } else {
                    if (level < bottom) {
                        level += ((bottom - level + step - 1) / step) * step;
                    }
                    for (; level <= top; level += step) {
                        out.add(level);
                    }
                }
            }
            if (at != null) {
                for (int level : at) {
                    if (level >= bottom && level <= top) {
                        out.add(level);
                    }
                }
            }
            if (skip != null) {
                for (int level : skip) {
                    out.remove(level);
                }
            }
            return List.copyOf(out);
        }

        /** Does this group name any level at all? */
        public boolean namesALevel() {
            return (from != null && from >= 1) || (at != null && Arrays.stream(at).anyMatch(l -> l >= 1));
        }

        /** The option ids, lower-cased, blanks and repeats left out. */
        @Nonnull
        public List<String> options() {
            return lowered(options);
        }

        /** The {@code Vars} object as written, or null. */
        @Nullable
        public JsonElement vars() {
            return vars;
        }
    }

    /** The Cost group: points of a track each take spends, and a price in any wallet or item besides. */
    public static final class Cost {

        @Nullable protected Integer points;
        @Nullable protected String track;
        @Nullable protected CostAsset price;

        public static final BuilderCodec<Cost> CODEC = BuilderCodec.builder(Cost.class, Cost::new)
                .appendInherited(new KeyedCodec<>("Points", Codec.INTEGER, false),
                        (o, v) -> o.points = v, o -> o.points, (o, p) -> o.points = p.points)
                .metadata(EditorSchema.defaultValue(0L))
                .documentation("The points each take spends. A track earns Points.PerLevel points for each level "
                        + "past the first; what is spent comes back on a respec. 0 when unauthored.").add()
                .appendInherited(new KeyedCodec<>("Track", Codec.STRING, false),
                        (o, v) -> o.track = v, o -> o.track, (o, p) -> o.track = p.track)
                .metadata(EditorSchema.assetRef(TrackAsset.class))
                .documentation("The track whose points are spent, by id: a pick on a skill's tree priced in that "
                        + "skill's Mastery points names the Mastery track here. Unauthored, the pick's own track.")
                .add()
                .appendInherited(new KeyedCodec<>("Price", CostAsset.CODEC, false),
                        (o, v) -> o.price = v, o -> o.price, (o, p) -> o.price = p.price)
                .documentation("What each take also costs in any wallet or item (the one price shape shops use), "
                        + "charged as the pick is taken and given back if that take is undone; an admin's teach "
                        + "is free, and a walk that spends picks for the player (a starter build, a re-spend) "
                        + "passes a priced pick over. Free when unauthored.")
                .add()
                .build();

        public Cost() {
        }
    }

    /** The Repeat group: how many times a pick bought outright may be taken. */
    public static final class Repeat {

        @Nullable protected Integer max;

        public static final BuilderCodec<Repeat> CODEC = BuilderCodec.builder(Repeat.class, Repeat::new)
                .appendInherited(new KeyedCodec<>("Max", Codec.INTEGER, false),
                        (o, v) -> o.max = v, o -> o.max, (o, p) -> o.max = p.max)
                .metadata(EditorSchema.defaultValue(1L))
                .documentation("The most times it may be taken; each take grants its Grants once more. 1 when "
                        + "unauthored; 0 or less sets no limit.").add()
                .build();

        public Repeat() {
        }
    }

    /** The Where group: the trainers whose page offers the pick. */
    public static final class Where {

        @Nullable protected String[] placements;

        public static final BuilderCodec<Where> CODEC = BuilderCodec.builder(Where.class, Where::new)
                .appendInherited(new KeyedCodec<>("Placements", Codec.STRING_ARRAY, false),
                        (o, v) -> o.placements = v, o -> o.placements, (o, p) -> o.placements = p.placements)
                .documentation("The NPC placements (trainers) whose page offers this pick, by id. Empty or "
                        + "unauthored, it is taken on the track's own page.").add()
                .build();

        public Where() {
        }
    }
}
