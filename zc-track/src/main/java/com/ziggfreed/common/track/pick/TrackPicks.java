package com.ziggfreed.common.track.pick;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.grant.Grant;
import com.ziggfreed.common.progress.asset.GeneratorCore;
import com.ziggfreed.common.progress.gate.GateEpochs;
import com.ziggfreed.common.track.PickState;
import com.ziggfreed.common.track.TrackDerived;
import com.ziggfreed.common.track.TrackKey;
import com.ziggfreed.common.track.TrackRecord;
import com.ziggfreed.common.track.asset.TrackAsset;
import com.ziggfreed.common.track.asset.TrackConfig;
import com.ziggfreed.common.validation.Finding;

/**
 * Every pick folded once per load, per track: each choice group's slots and the options it offers, their grants
 * filled from the pick's {@code Vars} through zc-progression's {@link GeneratorCore} (the library's one substitution
 * contract), the faces of every choice node across the tracks, and what the fold found wrong. Built on the first
 * ask after a pick, option or track fold ({@link #invalidate}), in sorted id order.
 *
 * <p><b>One line of authoring, never 199 groups.</b> A group's slots are derived from its own {@code From},
 * {@code Every}, {@code To}, {@code At} and {@code Skip} the way an unlock row's levels are, and each slot offers every
 * option of the group open at its level (the option's own {@code From} and {@code To}), in the group's order; no slot
 * is ever written out as its own group or file. An option shared by many picks is folded once per group naming it,
 * its {@code {Name}}s filled from that pick's (and group's) {@code Vars}. The offers read the files alone, never a
 * seed (R724), so every server offers the same options at every level.
 *
 * <p><b>A choice node</b> ({@code Node}) is one either-or wherever its faces sit (R859): the picks writing the same
 * Node, on one track or several, each know the others ({@link PickDef#otherFaces}), so one face taken locks the rest.
 *
 * <p>An option whose grants still hold a {@code {Name}} after filling, or whose grants do not read (an unknown
 * {@code Kind} among them), is left out of that group and reported; an option nobody ships is reported and passed
 * over. A pick on a track that is not switched on is nowhere, and so is a skeleton ({@code Abstract}) and every pick
 * under a pick switched off in its {@code Parent} chain ({@link #lineageLive}).
 */
public final class TrackPicks {

    /** The audit domain the fold's findings are filed under (the track audit's). */
    static final String DOMAIN = "track";

    public static final String UNKNOWN_PICK_TRACK = "UNKNOWN_PICK_TRACK";
    public static final String PICK_WITHOUT_TRACK = "PICK_WITHOUT_TRACK";
    public static final String UNKNOWN_PICK_OPTION = "UNKNOWN_PICK_OPTION";
    public static final String GROUP_WITHOUT_LEVEL = "GROUP_WITHOUT_LEVEL";
    public static final String GROUP_WITHOUT_OPTIONS = "GROUP_WITHOUT_OPTIONS";
    public static final String UNRESOLVED_PICK_TOKEN = "UNRESOLVED_PICK_TOKEN";
    public static final String UNREADABLE_PICK_GRANTS = "UNREADABLE_PICK_GRANTS";

    /** One option as one group offers it: its file and what it grants there, every {@code {Name}} filled. */
    public record OptionDef(@Nonnull String id, @Nonnull PickOptionAsset asset, @Nonnull List<Grant> grants) {

        public OptionDef {
            grants = List.copyOf(grants);
        }
    }

    /** One choice group of one pick, folded: its key, its slots and its options. */
    public static final class GroupDef {

        @Nonnull private final String key;
        @Nonnull private final TrackPickAsset.Group group;
        private final int opensAt;
        private final int choose;
        @Nonnull private final Map<String, OptionDef> options;

        GroupDef(@Nonnull String key, @Nonnull TrackPickAsset.Group group, int opensAt, int choose,
                @Nonnull Map<String, OptionDef> options) {
            this.key = key;
            this.group = group;
            this.opensAt = opensAt;
            this.choose = choose;
            this.options = Collections.unmodifiableMap(new LinkedHashMap<>(options));
        }

        /** What this group's slots are filed under in a player's record. */
        @Nonnull
        public String key() {
            return key;
        }

        /** How many options a slot takes. */
        public int choose() {
            return choose;
        }

        /** The levels up to {@code upTo} (in) this group opens a slot at, ascending. */
        @Nonnull
        public List<Integer> levels(int upTo) {
            return group.levels(opensAt, upTo);
        }

        /** Does this group open a slot at {@code level}? */
        public boolean opensAt(int level) {
            return level >= opensAt && group.levels(level, level).contains(level);
        }

        /**
         * The offer at {@code level}: every option of this group open there (its own {@code From} to {@code To}), in
         * the group's order, a function of the files alone, the same for every player on every server (R724). Empty
         * where the group opens no slot.
         */
        @Nonnull
        public List<String> offerAt(int level) {
            if (!opensAt(level)) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (OptionDef option : options.values()) {
                if (option.asset().openAt(level)) {
                    out.add(option.id());
                }
            }
            return List.copyOf(out);
        }

        /** The options this group offers, by id (lower-cased), in authored order. */
        @Nonnull
        public Map<String, OptionDef> options() {
            return options;
        }

        /** The option {@code id} names (any case), or null when this group offers none by that id. */
        @Nullable
        public OptionDef option(@Nullable String id) {
            return id == null ? null : options.get(TrackKey.normalize(id));
        }
    }

    /** One pick, folded. */
    public static final class PickDef {

        @Nonnull private final TrackPickAsset asset;
        @Nonnull private final String id;
        @Nonnull private final String trackId;
        @Nullable private final String categoryId;
        @Nonnull private final List<GroupDef> groups;
        /** The other faces of this pick's choice node on any track, set once by the fold before it is published. */
        @Nonnull private List<String> otherFaces = List.of();

        PickDef(@Nonnull TrackPickAsset asset, @Nonnull String id, @Nonnull String trackId,
                @Nullable String categoryId, @Nonnull List<GroupDef> groups) {
            this.asset = asset;
            this.id = id;
            this.trackId = trackId;
            this.categoryId = categoryId;
            this.groups = List.copyOf(groups);
        }

        /**
         * The other picks that write this pick's {@code Node}, by id, on its own track first and then on the others
         * (each track by id, its picks by Order then id): one of them taken locks this one. Empty for a pick in no
         * node.
         */
        @Nonnull
        public List<String> otherFaces() {
            return otherFaces;
        }

        /** How many times this pick's cost was paid on {@code state}: each slot made for a choice, each take else. */
        public int paidTimes(@Nonnull PickState state) {
            return isChoice() ? state.slotsMade(id) : state.takesOf(id);
        }

        @Nonnull
        public TrackPickAsset asset() {
            return asset;
        }

        /** The pick's id, lower-cased. */
        @Nonnull
        public String id() {
            return id;
        }

        /** The track it sits on, lower-cased. */
        @Nonnull
        public String trackId() {
            return trackId;
        }

        /** The category of its track, as the track writes it, or null. */
        @Nullable
        public String categoryId() {
            return categoryId;
        }

        /** Is it a choice (it writes groups) rather than a pick bought outright? */
        public boolean isChoice() {
            return asset.isChoice();
        }

        /** Its groups, folded; empty for a pick bought outright. */
        @Nonnull
        public List<GroupDef> groups() {
            return groups;
        }

        /** The group filed under {@code key} (any case), or null. */
        @Nullable
        public GroupDef group(@Nullable String key) {
            String wanted = TrackKey.normalize(key);
            for (GroupDef group : groups) {
                if (group.key().equals(wanted)) {
                    return group;
                }
            }
            return null;
        }

        /** The slot key of this pick's group {@code group} at {@code level}. */
        @Nonnull
        public String slotKey(@Nonnull GroupDef group, int level) {
            return PickState.slotKey(id, group.key(), level);
        }
    }

    /** One fold: the picks by track (sorted by Order then id), by id, and what the fold found wrong. */
    public record Folded(@Nonnull Map<String, List<PickDef>> byTrack, @Nonnull Map<String, PickDef> byId,
                         @Nonnull List<Finding> issues) {

        public static final Folded EMPTY = new Folded(Map.of(), Map.of(), List.of());
    }

    /** The parts of a slot key: the pick, the group and the level. */
    public record Slot(@Nonnull String pickId, @Nonnull String groupKey, int level) {
    }

    @Nullable private static volatile Folded folded;

    /** The tree as authored ({@link #authored}), folded on its first ask after a fold moved. */
    @Nullable private static volatile Folded authored;

    private TrackPicks() {
    }

    /** The picks on {@code trackId} (any case), by Order then id; empty when none. */
    @Nonnull
    public static List<PickDef> onTrack(@Nullable String trackId) {
        List<PickDef> picks = trackId == null ? null : folded().byTrack().get(TrackKey.normalize(trackId));
        return picks == null ? List.of() : picks;
    }

    /** The pick {@code id} names (any case), on a switched-on track, or null. */
    @Nullable
    public static PickDef pick(@Nullable String id) {
        return id == null ? null : folded().byId().get(TrackKey.normalize(id));
    }

    /** Every pick on a switched-on track, by id. */
    @Nonnull
    public static Map<String, PickDef> all() {
        return folded().byId();
    }

    /** Does any switched-on track carry a pick? One lookup; what a reader asks before reading anything. */
    public static boolean any() {
        return !folded().byId().isEmpty();
    }

    /** What the last fold found wrong (the track audit's to report). */
    @Nonnull
    public static List<Finding> issues() {
        return folded().issues();
    }

    /**
     * The tree as authored, by id: what the tree audit reads, never a runtime read (nothing takes, grants or gates on
     * it). Every pick {@link #all} holds, and with them each pick switched off in its own pack file (the layer under
     * the owner's word is off too, so a node shipped off for a later pass still counts toward its branch, its gates
     * and its starter build), its lineage live; a pick an owner entry switched off, a skeleton, and one under a
     * switched-off ancestor stay out. Folded as {@link #foldAuthored} folds, so a choice node's faces link across both.
     */
    @Nonnull
    public static Map<String, PickDef> authored() {
        Folded local = authored;
        if (local == null) {
            synchronized (TrackPicks.class) {
                local = authored;
                if (local == null) {
                    TrackPickConfig config = TrackPickConfig.getInstance();
                    local = foldAuthored(config.all(),
                            id -> PickOptionConfig.getInstance().resolve(id),
                            id -> TrackConfig.getInstance().live(id),
                            id -> {
                                TrackPickAsset file = config.resolveBelowOwner(id);
                                return file != null && !file.isEnabled();
                            });
                    authored = local;
                }
            }
        }
        return local.byId();
    }

    /**
     * A pick, option or track folded: the next ask folds again, the gate server epoch moves (a branch's
     * points, {@code ziggfreedcommon:track_branch_spent}, read the picks' {@code Branch} and {@code Cost}, so no
     * verdict kept over the old files outlives them), and every online player's grants are rebuilt on their own
     * world thread, so a pick switched off takes its grants off and a retuned option pays its new amount.
     */
    public static void invalidate() {
        synchronized (TrackPicks.class) {
            folded = null;
            authored = null;
        }
        GateEpochs.bumpServer();
        TrackDerived.rebuildAllOnline();
    }

    /** {@code slot} as its parts, or null when it is no slot key. */
    @Nullable
    public static Slot slot(@Nullable String slot) {
        String key = TrackKey.normalize(slot);
        int last = key.lastIndexOf(PickState.SLOT_SEPARATOR);
        int first = key.indexOf(PickState.SLOT_SEPARATOR);
        if (first <= 0 || last <= first || last == key.length() - 1) {
            return null;
        }
        try {
            int level = Integer.parseInt(key.substring(last + 1));
            return level < 1 ? null : new Slot(key.substring(0, first), key.substring(first + 1, last), level);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nonnull
    private static Folded folded() {
        Folded local = folded;
        if (local == null) {
            synchronized (TrackPicks.class) {
                local = folded;
                if (local == null) {
                    local = fold(TrackPickConfig.getInstance().all(),
                            id -> PickOptionConfig.getInstance().resolve(id),
                            id -> TrackConfig.getInstance().live(id));
                    folded = local;
                }
            }
        }
        return local;
    }

    /**
     * Fold {@code picks} (pure but for the decode of each option's grants through the grant union): enabled ones on
     * a switched-on track, in sorted id order.
     *
     * @param options the option files by id (null for none)
     * @param tracks  the switched-on track {@code id} names, or null
     */
    @Nonnull
    public static Folded fold(@Nonnull Map<String, TrackPickAsset> picks,
            @Nonnull Function<String, PickOptionAsset> options, @Nonnull Function<String, TrackAsset> tracks) {
        return fold(picks, options, tracks, id -> false);
    }

    /**
     * Fold {@code picks} as authored, for the tree audit alone ({@link #authored}): what {@link #fold} folds, and with
     * it each pick switched off whose id {@code offInOwnFile} answers yes for (switched off in its own pack file, not
     * by an owner's word: any off pick, when {@code picks} are pack files alone), its lineage live as the fold asks.
     */
    @Nonnull
    public static Folded foldAuthored(@Nonnull Map<String, TrackPickAsset> picks,
            @Nonnull Function<String, PickOptionAsset> options, @Nonnull Function<String, TrackAsset> tracks,
            @Nonnull Predicate<String> offInOwnFile) {
        return fold(picks, options, tracks, offInOwnFile);
    }

    @Nonnull
    private static Folded fold(@Nonnull Map<String, TrackPickAsset> picks,
            @Nonnull Function<String, PickOptionAsset> options, @Nonnull Function<String, TrackAsset> tracks,
            @Nonnull Predicate<String> offInOwnFile) {
        List<String> ids = new ArrayList<>(picks.keySet());
        Collections.sort(ids);
        List<Finding> issues = new ArrayList<>();
        Map<String, List<PickDef>> byTrack = new LinkedHashMap<>();
        Map<String, PickDef> byId = new LinkedHashMap<>();
        Map<String, TrackPickAsset> byLowerId = new LinkedHashMap<>();
        picks.forEach((key, value) -> byLowerId.put(TrackKey.normalize(key), value));
        for (String rawId : ids) {
            TrackPickAsset asset = picks.get(rawId);
            if (asset == null || (!asset.isEnabled() && !offInOwnFile.test(rawId)) || asset.isAbstract()
                    || !lineageLive(asset, byLowerId)) {
                continue;
            }
            String id = TrackKey.normalize(rawId);
            String trackId = asset.trackId();
            if (trackId == null) {
                issues.add(Finding.warning(DOMAIN, PICK_WITHOUT_TRACK, "track pick '" + rawId + "' names no Track, "
                        + "so it is nowhere", rawId));
                continue;
            }
            TrackAsset track = tracks.apply(trackId);
            if (track == null) {
                issues.add(Finding.warning(DOMAIN, UNKNOWN_PICK_TRACK, "track pick '" + rawId + "' sits on the track '"
                        + trackId + "', which is not switched on here, so it is nowhere", rawId));
                continue;
            }
            String trackKey = TrackKey.normalize(track.getId());
            List<GroupDef> groups = new ArrayList<>();
            for (TrackPickAsset.Group group : asset.groups()) {
                GroupDef def = group(asset, rawId, group, options, issues);
                if (def != null) {
                    groups.add(def);
                }
            }
            PickDef def = new PickDef(asset, id, trackKey, track.category(), groups);
            byId.put(id, def);
            byTrack.computeIfAbsent(trackKey, k -> new ArrayList<>()).add(def);
        }
        Map<String, List<PickDef>> frozen = new LinkedHashMap<>();
        new TreeMap<>(byTrack).forEach((track, list) -> {
            list.sort((a, b) -> a.asset().order() != b.asset().order()
                    ? Integer.compare(a.asset().order(), b.asset().order()) : a.id().compareTo(b.id()));
            frozen.put(track, List.copyOf(list));
        });
        linkFaces(frozen);
        return new Folded(Collections.unmodifiableMap(frozen), Collections.unmodifiableMap(byId), List.copyOf(issues));
    }

    /**
     * Is every pick above {@code asset} in its {@code Parent} chain switched on ({@code picks} by lower-cased id)? A
     * skeleton switched off takes every pick under it away, whatever they write, so one file or one owner entry turns
     * a whole tree off. A Parent no file is ends the walk (the engine reports it), and so does a loop.
     */
    static boolean lineageLive(@Nonnull TrackPickAsset asset, @Nonnull Map<String, TrackPickAsset> picks) {
        Set<String> seen = new HashSet<>();
        String parent = asset.parentId();
        while (parent != null && seen.add(TrackKey.normalize(parent))) {
            TrackPickAsset above = picks.get(TrackKey.normalize(parent));
            if (above == null) {
                return true;
            }
            if (!above.isEnabled()) {
                return false;
            }
            parent = above.parentId();
        }
        return true;
    }

    /**
     * Point each pick at the other faces of every either-or group it names (the picks writing one of its {@code Node}
     * ids, on any track, R859, R895), each once: those on its own track first, then the others', tracks by id and each
     * track's picks in its order.
     */
    private static void linkFaces(@Nonnull Map<String, List<PickDef>> byTrack) {
        Map<String, List<PickDef>> byNode = new LinkedHashMap<>();
        List<PickDef> inOrder = new ArrayList<>();
        byTrack.values().forEach(list -> {
            for (PickDef pick : list) {
                inOrder.add(pick);
                for (String node : pick.asset().nodes()) {
                    byNode.computeIfAbsent(node, k -> new ArrayList<>()).add(pick);
                }
            }
        });
        for (PickDef pick : inOrder) {
            Set<PickDef> faces = new HashSet<>();
            for (String node : pick.asset().nodes()) {
                faces.addAll(byNode.get(node));
            }
            if (faces.isEmpty()) {
                continue;
            }
            List<String> own = new ArrayList<>();
            List<String> elsewhere = new ArrayList<>();
            for (PickDef face : inOrder) {
                if (face != pick && faces.contains(face)) {
                    (face.trackId().equals(pick.trackId()) ? own : elsewhere).add(face.id());
                }
            }
            own.addAll(elsewhere);
            pick.otherFaces = List.copyOf(own);
        }
    }

    /**
     * The points of {@code costTrackId}'s pool spent (pure): every pick of {@code picks} priced in them, its cost
     * once for each time it was paid on its own track's record ({@code records}, by lower-cased track id, null when
     * the player holds none there).
     */
    public static int spentOn(@Nonnull String costTrackId, @Nonnull Collection<PickDef> picks,
            @Nonnull Function<String, TrackRecord> records) {
        String pool = TrackKey.normalize(costTrackId);
        long spent = 0L;
        for (PickDef pick : picks) {
            int cost = pick.asset().costPoints();
            if (cost <= 0 || !pool.equals(TrackKey.normalize(pick.asset().costTrack()))) {
                continue;
            }
            TrackRecord record = records.apply(pick.trackId());
            if (record != null) {
                spent += (long) cost * pick.paidTimes(record.picks());
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, spent);
    }

    /**
     * The points spent in the branch {@code branch} (any case) of one track's tree (pure): every pick of
     * {@code onTrack} whose {@code Branch} names it, its cost once for each time it was paid on {@code state}. What
     * {@code ziggfreedcommon:track_branch_spent} reads.
     */
    public static int branchSpent(@Nonnull Collection<PickDef> onTrack, @Nonnull PickState state,
            @Nullable String branch) {
        String wanted = TrackKey.normalize(branch);
        if (wanted.isEmpty()) {
            return 0;
        }
        long spent = 0L;
        for (PickDef pick : onTrack) {
            if (wanted.equals(pick.asset().branch())) {
                spent += (long) pick.asset().costPoints() * pick.paidTimes(state);
            }
        }
        return (int) Math.min(Integer.MAX_VALUE, spent);
    }

    /** One group folded, or null when it names no level (reported). */
    @Nullable
    private static GroupDef group(@Nonnull TrackPickAsset pick, @Nonnull String rawId,
            @Nonnull TrackPickAsset.Group group, @Nonnull Function<String, PickOptionAsset> options,
            @Nonnull List<Finding> issues) {
        String key = group.key();
        String where = "track pick '" + rawId + "' group '" + key + "'";
        if (!group.namesALevel()) {
            issues.add(Finding.warning(DOMAIN, GROUP_WITHOUT_LEVEL, where + " names no From and no At level, so it "
                    + "opens no slot", rawId));
            return null;
        }
        Map<String, JsonPrimitive> vars = new LinkedHashMap<>();
        bindings(pick.vars(), vars);
        bindings(group.vars(), vars);
        Map<String, OptionDef> folded = new LinkedHashMap<>();
        for (String optionId : group.options()) {
            PickOptionAsset option = options.apply(optionId);
            if (option == null) {
                issues.add(Finding.warning(DOMAIN, UNKNOWN_PICK_OPTION, where + " offers the option '" + optionId
                        + "', which no loaded TrackPickOptions file defines, so it is left out", rawId));
                continue;
            }
            if (!option.isEnabled()) {
                continue;
            }
            List<Grant> grants = grants(option, vars, where, rawId, issues);
            if (grants != null) {
                folded.put(optionId, new OptionDef(optionId, option, grants));
            }
        }
        if (folded.isEmpty()) {
            issues.add(Finding.warning(DOMAIN, GROUP_WITHOUT_OPTIONS, where + " has no option to offer, so its slots "
                    + "offer nothing", rawId));
        }
        return new GroupDef(key, group, pick.opensAt(), group.choose(pick.choose()), folded);
    }

    /** Add {@code vars}' primitive values to {@code into}, a later call's winning. */
    private static void bindings(@Nullable JsonElement vars, @Nonnull Map<String, JsonPrimitive> into) {
        if (vars == null || !vars.isJsonObject()) {
            return;
        }
        for (Map.Entry<String, JsonElement> e : vars.getAsJsonObject().entrySet()) {
            if (e.getKey() != null && e.getValue() != null && e.getValue().isJsonPrimitive()) {
                into.put(e.getKey().trim(), e.getValue().getAsJsonPrimitive());
            }
        }
    }

    /**
     * {@code option}'s grants with {@code vars} filled in, read through the grant union; null (reported) when a
     * {@code {Name}} is left unfilled or the grants do not read.
     */
    @Nullable
    private static List<Grant> grants(@Nonnull PickOptionAsset option, @Nonnull Map<String, JsonPrimitive> vars,
            @Nonnull String where, @Nonnull String rawId, @Nonnull List<Finding> issues) {
        JsonElement template = option.grantsTemplate();
        if (template == null || template.isJsonNull()) {
            return List.of();
        }
        JsonElement filled = GeneratorCore.substitute(template, vars);
        Set<String> left = GeneratorCore.tokensIn(filled);
        if (!left.isEmpty()) {
            issues.add(Finding.error(DOMAIN, UNRESOLVED_PICK_TOKEN, where + " offers the option '" + option.getId()
                    + "', whose Grants still name " + braced(left) + " after this pick's Vars fill them, so the option "
                    + "is left out of this group; add the Var", rawId));
            return null;
        }
        try {
            Grant[] read = Grant.ARRAY_CODEC.decodeJson(RawJsonReader.fromJsonString(filled.toString()),
                    new ExtraInfo());
            List<Grant> out = new ArrayList<>();
            if (read != null) {
                for (Grant grant : read) {
                    if (grant != null) {
                        out.add(grant);
                    }
                }
            }
            return out;
        } catch (Throwable t) {
            issues.add(Finding.error(DOMAIN, UNREADABLE_PICK_GRANTS, where + " offers the option '" + option.getId()
                    + "', whose Grants do not read (" + t.getMessage() + "), so the option is left out of this group",
                    rawId));
            return null;
        }
    }

    @Nonnull
    private static String braced(@Nonnull Set<String> tokens) {
        List<String> out = new ArrayList<>();
        for (String token : tokens) {
            out.add("{" + token + "}");
        }
        return String.join(", ", out);
    }

    /** Is a pick or option with id {@code id} (any case) on {@code trackId}? (The audit's prerequisite read.) */
    public static boolean knownOnTrack(@Nonnull String trackId, @Nonnull String id) {
        String wanted = TrackKey.normalize(id);
        for (PickDef pick : onTrack(trackId)) {
            if (pick.id().equals(wanted)) {
                return true;
            }
            for (GroupDef group : pick.groups()) {
                if (group.options().containsKey(wanted)) {
                    return true;
                }
            }
        }
        return false;
    }
}
