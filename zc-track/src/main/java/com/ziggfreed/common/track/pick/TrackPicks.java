package com.ziggfreed.common.track.pick;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

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
 * Every pick folded once per load, per track: each choice group's slots and road ({@link PickRoad}), the options
 * it offers with their grants filled from the pick's {@code Vars} through zc-progression's {@link GeneratorCore}
 * (the library's one substitution contract), and what the fold found wrong. Built on the first ask after a pick,
 * option, deck or track fold ({@link #invalidate}), in sorted id order.
 *
 * <p><b>One line of authoring, never 199 groups.</b> A group's slots are derived from its own {@code From},
 * {@code Every}, {@code To}, {@code At} and {@code Skip} the way an unlock row's levels are, and each slot's offer is
 * read off the group's one road; no slot is ever written out as its own group or file. An option shared by many
 * picks is folded once per group naming it, its {@code {Name}}s filled from that pick's (and group's) {@code Vars}.
 *
 * <p><b>The same files, the same road</b> (R724): a road reads the files alone, never a seed, so two folds of the
 * same files offer the same options at every level on every server. A pick's {@code Offers} pin a level: in place of
 * the draw, each group with a slot there that lists at least one of the pinned options live (switched on and read)
 * offers its core and those options; a group that lists none keeps its drawn offer, and an empty list unpins the
 * level.
 *
 * <p>An option whose grants still hold a {@code {Name}} after filling, or whose grants do not read (an unknown
 * {@code Kind} among them), is left out of that group and reported; a deck, core or option nobody ships is
 * reported and passed over, and so is an {@code Offers} level no group opens a slot at. A pick on a track that is
 * not switched on is nowhere, and so is a skeleton ({@code Abstract}) and every pick under a pick switched off in its
 * {@code Parent} chain ({@link #lineageLive}).
 */
public final class TrackPicks {

    /** The audit domain the fold's findings are filed under (the track audit's). */
    static final String DOMAIN = "track";

    public static final String UNKNOWN_PICK_TRACK = "UNKNOWN_PICK_TRACK";
    public static final String PICK_WITHOUT_TRACK = "PICK_WITHOUT_TRACK";
    public static final String UNKNOWN_PICK_OPTION = "UNKNOWN_PICK_OPTION";
    public static final String UNKNOWN_PICK_DECK = "UNKNOWN_PICK_DECK";
    public static final String GROUP_WITHOUT_LEVEL = "GROUP_WITHOUT_LEVEL";
    public static final String GROUP_WITHOUT_OPTIONS = "GROUP_WITHOUT_OPTIONS";
    public static final String UNRESOLVED_PICK_TOKEN = "UNRESOLVED_PICK_TOKEN";
    public static final String UNREADABLE_PICK_GRANTS = "UNREADABLE_PICK_GRANTS";
    public static final String OFFER_WITHOUT_SLOT = "OFFER_WITHOUT_SLOT";

    /** One option as one group offers it: its file and what it grants there, every {@code {Name}} filled. */
    public record OptionDef(@Nonnull String id, @Nonnull PickOptionAsset asset, @Nonnull List<Grant> grants) {

        public OptionDef {
            grants = List.copyOf(grants);
        }
    }

    /** One choice group of one pick, folded: its key, its slots, its options and its road. */
    public static final class GroupDef {

        @Nonnull private final String key;
        @Nonnull private final TrackPickAsset.Group group;
        private final int opensAt;
        private final int choose;
        @Nonnull private final Map<String, OptionDef> options;
        @Nonnull private final PickRoad road;

        GroupDef(@Nonnull String key, @Nonnull TrackPickAsset.Group group, int opensAt, int choose,
                @Nonnull Map<String, OptionDef> options, @Nonnull PickRoad road) {
            this.key = key;
            this.group = group;
            this.opensAt = opensAt;
            this.choose = choose;
            this.options = Collections.unmodifiableMap(new LinkedHashMap<>(options));
            this.road = road;
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
         * The offer at {@code level}, the core first: a function of the files alone, the same for every player on
         * every server (R724). Empty off the road.
         */
        @Nonnull
        public List<String> offerAt(int level) {
            return road.offerAt(level);
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
        /** The other faces of this pick's choice node on its track, set once by the fold before it is published. */
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
         * The other picks on this pick's track that write its {@code Node}, by id: one of them taken locks this one.
         * Empty for a pick in no node.
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
     * A pick, option, deck or track folded: the next ask folds again, the gate server epoch moves (a branch's
     * points, {@code ziggfreedcommon:track_branch_spent}, read the picks' {@code Branch} and {@code Cost}, so no
     * verdict kept over the old files outlives them), and every online player's grants are rebuilt on their own
     * world thread, so a pick switched off takes its grants off and a retuned option pays its new amount.
     */
    public static void invalidate() {
        synchronized (TrackPicks.class) {
            folded = null;
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
                            id -> PickDeckConfig.getInstance().resolve(id),
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
     * @param decks   the deck files by id (null for none)
     * @param tracks  the switched-on track {@code id} names, or null
     */
    @Nonnull
    public static Folded fold(@Nonnull Map<String, TrackPickAsset> picks,
            @Nonnull Function<String, PickOptionAsset> options, @Nonnull Function<String, PickDeckAsset> decks,
            @Nonnull Function<String, TrackAsset> tracks) {
        List<String> ids = new ArrayList<>(picks.keySet());
        Collections.sort(ids);
        List<Finding> issues = new ArrayList<>();
        Map<String, List<PickDef>> byTrack = new LinkedHashMap<>();
        Map<String, PickDef> byId = new LinkedHashMap<>();
        Map<String, TrackPickAsset> byLowerId = new LinkedHashMap<>();
        picks.forEach((key, value) -> byLowerId.put(TrackKey.normalize(key), value));
        for (String rawId : ids) {
            TrackPickAsset asset = picks.get(rawId);
            if (asset == null || !asset.isEnabled() || asset.isAbstract() || !lineageLive(asset, byLowerId)) {
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
                GroupDef def = group(asset, rawId, group, options, decks, issues);
                if (def != null) {
                    groups.add(def);
                }
            }
            pinnedFindings(asset, rawId, groups, options, issues);
            PickDef def = new PickDef(asset, id, trackKey, track.category(), groups);
            byId.put(id, def);
            byTrack.computeIfAbsent(trackKey, k -> new ArrayList<>()).add(def);
        }
        Map<String, List<PickDef>> frozen = new LinkedHashMap<>();
        byTrack.forEach((track, list) -> {
            list.sort((a, b) -> a.asset().order() != b.asset().order()
                    ? Integer.compare(a.asset().order(), b.asset().order()) : a.id().compareTo(b.id()));
            linkFaces(list);
            frozen.put(track, List.copyOf(list));
        });
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

    /** Point each pick on one track at the other faces of its choice node (the picks writing the same Node). */
    private static void linkFaces(@Nonnull List<PickDef> onTrack) {
        Map<String, List<String>> byNode = new LinkedHashMap<>();
        for (PickDef pick : onTrack) {
            String node = pick.asset().node();
            if (node != null) {
                byNode.computeIfAbsent(node, k -> new ArrayList<>()).add(pick.id());
            }
        }
        for (PickDef pick : onTrack) {
            String node = pick.asset().node();
            if (node == null) {
                continue;
            }
            List<String> others = new ArrayList<>(byNode.get(node));
            others.remove(pick.id());
            pick.otherFaces = List.copyOf(others);
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
            @Nonnull Function<String, PickDeckAsset> decks, @Nonnull List<Finding> issues) {
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
        List<String> wanted = new ArrayList<>(group.options());
        String core = group.core();
        if (core != null && !wanted.contains(core)) {
            wanted.add(0, core);
        }
        Map<String, OptionDef> folded = new LinkedHashMap<>();
        for (String optionId : wanted) {
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
        PickRoad.Deck deck = null;
        String deckId = group.deck();
        if (deckId != null) {
            PickDeckAsset asset = decks.apply(deckId);
            if (asset == null) {
                issues.add(Finding.warning(DOMAIN, UNKNOWN_PICK_DECK, where + " draws with the deck '" + deckId
                        + "', which no loaded TrackPickDecks file defines, so every option is offered at every slot",
                        rawId));
            } else {
                deck = asset.rules();
            }
        }
        List<PickRoad.Candidate> pool = new ArrayList<>();
        for (OptionDef option : folded.values()) {
            PickOptionAsset asset = option.asset();
            pool.add(new PickRoad.Candidate(option.id(), asset.draw(), asset.bag(), asset.from(), asset.to(),
                    asset.turn()));
        }
        int opensAt = pick.opensAt();
        String roadCore = core != null && folded.containsKey(core) ? core : null;
        // A level whose pinned options this group lists none of live goes in empty: the road keeps its drawn offer.
        Map<Integer, List<String>> pinned = new LinkedHashMap<>();
        pick.offers().forEach((level, ids) -> {
            if (level >= opensAt && group.levels(level, level).contains(level)) {
                List<String> listed = new ArrayList<>();
                for (String id : ids) {
                    if (folded.containsKey(id)) {
                        listed.add(id);
                    }
                }
                pinned.put(level, listed);
            }
        });
        PickRoad road = new PickRoad(pool, roadCore, deck, pinned,
                (from, to) -> group.levels(Math.max(opensAt, from), to));
        return new GroupDef(key, group, opensAt, group.choose(pick.choose()), folded, road);
    }

    /**
     * Report what {@code pick}'s {@code Offers} pin to no effect: a key that is no level or a level none of its
     * {@code groups} opens a slot at, and an option none of them lists (a switched-off option passed over quietly).
     */
    private static void pinnedFindings(@Nonnull TrackPickAsset pick, @Nonnull String rawId,
            @Nonnull List<GroupDef> groups, @Nonnull Function<String, PickOptionAsset> options,
            @Nonnull List<Finding> issues) {
        String where = "track pick '" + rawId + "'";
        for (String key : pick.unreadOfferLevels()) {
            issues.add(Finding.warning(DOMAIN, OFFER_WITHOUT_SLOT, where + " writes Offers at '" + key + "', which "
                    + "is no level, so it pins nothing", rawId));
        }
        pick.offers().forEach((level, ids) -> {
            boolean opens = false;
            for (GroupDef group : groups) {
                opens |= group.opensAt(level);
            }
            if (!opens) {
                issues.add(Finding.warning(DOMAIN, OFFER_WITHOUT_SLOT, where + " writes Offers at level " + level
                        + ", where none of its groups opens a slot, so it pins nothing", rawId));
                return;
            }
            for (String id : ids) {
                boolean listed = false;
                for (GroupDef group : groups) {
                    listed |= group.options().containsKey(id);
                }
                PickOptionAsset option = listed ? null : options.apply(id);
                if (!listed && (option == null || option.isEnabled())) {
                    issues.add(Finding.warning(DOMAIN, UNKNOWN_PICK_OPTION, where + " writes Offers at level "
                            + level + " naming the option '" + id + "', which none of its groups offers, so it is "
                            + "left out", rawId));
                }
            }
        });
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
