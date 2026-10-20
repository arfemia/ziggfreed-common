package com.ziggfreed.common.track.pick;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.progress.gate.GateClause;
import com.ziggfreed.common.progress.gate.GateQuery;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.track.PickState;
import com.ziggfreed.common.track.TrackDef;
import com.ziggfreed.common.track.TrackFactors;
import com.ziggfreed.common.track.TrackKey;
import com.ziggfreed.common.track.TrackReading;
import com.ziggfreed.common.track.TrackRecord;
import com.ziggfreed.common.track.TrackService;
import com.ziggfreed.common.track.asset.TrackAsset;
import com.ziggfreed.common.track.pick.PickService.Status;

/**
 * One player's tree on one track, read for a page (R736): the picks bought outright on the track's own page (a
 * trainer's that teaches now, and every choice, left out), by branch in the track's {@code Branches} order (the picks
 * in no branch first, as the root row), each branch's picks in rows by the points its row asks spent in that branch
 * (a {@code Requires} on {@code ziggfreedcommon:track_branch_spent} naming the track and the branch), and each pick
 * with its ranks, cost, level gate, the other faces of its choice node and what a take would answer now.
 *
 * <p>It only reads: a take is {@link PickService#take} on the track's own page, which asks every rule again. A
 * pick's state here asks the same rules a take does, in the same order (taken as often as it may be read first, so a
 * pick a prestige kept reads as taken), through {@link PickService#open} and the pick's cost on its pool. World
 * thread.
 */
public final class TrackTree {

    /**
     * One pick of the tree as the player stands now: its id, branch (null for none), the points its row asks spent
     * in that branch, the level it opens at, how often it was taken and may be ({@link Integer#MAX_VALUE} for no
     * limit), the points one take costs and the track they come from (0 and null for a free pick), the other faces of
     * its choice node, the picks it needs first and which of them are not taken yet, and what a take would answer.
     */
    public record Node(@Nonnull String pickId, @Nullable String branch, int gate, int opensAt, int taken, int ranks,
                       int cost, @Nullable String costTrack, @Nonnull List<String> faces,
                       @Nonnull List<String> prerequisites, @Nonnull List<String> missing, @Nonnull Status status) {

        public Node {
            faces = List.copyOf(faces);
            prerequisites = List.copyOf(prerequisites);
            missing = List.copyOf(missing);
        }

        /** Taken as often as it may be? */
        public boolean maxed() {
            return status == Status.MAXED;
        }

        /** Could it be taken once more right now? */
        public boolean takeable() {
            return status == Status.DONE;
        }

        /** Is it one face of a choice node? */
        public boolean isFace() {
            return !faces.isEmpty();
        }
    }

    /**
     * One row of a branch: its place (1 for the first), the points it asks spent in the branch, how many more the
     * player needs (0 once it is open), and its picks in the track's order.
     */
    public record Row(int index, int gate, int missing, @Nonnull List<Node> nodes) {

        public Row {
            nodes = List.copyOf(nodes);
        }

        /** Is the row open: the branch's points it asks for spent? */
        public boolean open() {
            return missing == 0;
        }
    }

    /** One branch ({@code id} null for the picks in no branch), the points spent in it, and its rows by gate. */
    public record Branch(@Nullable String id, int spent, @Nonnull List<Row> rows) {

        public Branch {
            rows = List.copyOf(rows);
        }

        /** Is it the root row: the picks in no branch? */
        public boolean isRoot() {
            return id == null;
        }
    }

    /**
     * The whole tree for one player: the track, the player's pick level, the points the track earned them, spent
     * from its pool and left, and the branches (the root first when any pick sits in none).
     */
    public record View(@Nonnull TrackDef def, int level, int earned, int spent, int left,
                       @Nonnull List<Branch> branches) {

        public View {
            branches = List.copyOf(branches);
        }

        /** Is there something to do in the tree: a point left to spend, or a node ready to take? */
        public boolean offersAny() {
            if (left > 0) {
                return true;
            }
            for (Branch branch : branches) {
                for (Row row : branch.rows()) {
                    for (Node node : row.nodes()) {
                        if (node.takeable()) {
                            return true;
                        }
                    }
                }
            }
            return false;
        }

        /** The node {@code pickId} names (any case), or null when the tree holds none. */
        @Nullable
        public Node node(@Nullable String pickId) {
            String wanted = TrackKey.normalize(pickId);
            for (Branch branch : branches) {
                for (Row row : branch.rows()) {
                    for (Node node : row.nodes()) {
                        if (node.pickId().equals(wanted)) {
                            return node;
                        }
                    }
                }
            }
            return null;
        }

        /** The branch {@code id} names (null for the root), or null when the tree has none such. */
        @Nullable
        public Branch branch(@Nullable String id) {
            String wanted = id == null ? null : TrackKey.normalize(id);
            for (Branch branch : branches) {
                if (wanted == null ? branch.isRoot() : wanted.equals(branch.id())) {
                    return branch;
                }
            }
            return null;
        }
    }

    private TrackTree() {
    }

    /**
     * Does {@code trackId} (any case) carry a tree: a pick bought outright on its own page with a {@code Branch}?
     * One fold read; false for an unknown or switched-off track.
     */
    public static boolean has(@Nullable String trackId) {
        if (trackId == null || TrackService.def(trackId) == null) {
            return false;
        }
        for (TrackPicks.PickDef pick : TrackPicks.onTrack(trackId)) {
            if (inTree(pick) && pick.asset().branch() != null) {
                return true;
            }
        }
        return false;
    }

    /** {@code player}'s tree on {@code trackId}, or null when the track has none or the player is not live. */
    @Nullable
    public static View read(@Nonnull UUID player, @Nullable String trackId) {
        if (!has(trackId)) {
            return null;
        }
        TrackDef def = TrackService.def(trackId);
        TrackReading reading = def == null ? null : TrackService.read(TrackKey.player(player, def.asset().getId()));
        Ref<EntityStore> ref = PlayerAccess.liveRef(player);
        if (reading == null || ref == null) {
            return null;
        }
        GateQuery query = GateQuery.of(FactorContext.about(ref.getStore(), ref));
        Map<String, TrackRecord> records = new HashMap<>();
        records.put(def.id(), reading.record());
        PickState state = reading.record().picks();
        int level = PickService.pickLevel(def, reading.record());
        int earned = PickService.pointsEarned(def, reading.record());
        int spent = spentOn(player, def.id(), records);

        List<TrackPicks.PickDef> onTrack = TrackPicks.onTrack(def.id());
        Map<String, List<Node>> byBranch = new LinkedHashMap<>();
        for (TrackPicks.PickDef pick : onTrack) {
            if (!inTree(pick)) {
                continue;
            }
            String branch = pick.asset().branch();
            List<String> missing = new ArrayList<>();
            for (String needed : pick.asset().prerequisites()) {
                if (state.count(needed) < 1) {
                    missing.add(needed);
                }
            }
            Node node = new Node(pick.id(), branch, rowGate(pick.asset(), def.id()), pick.asset().opensAt(),
                    state.takesOf(pick.id()), pick.asset().repeatMax(), pick.asset().costPoints(),
                    pick.asset().costPoints() > 0 ? TrackKey.normalize(pick.asset().costTrack()) : null,
                    pick.otherFaces(), pick.asset().prerequisites(), missing,
                    status(player, pick, state, level, query, records));
            byBranch.computeIfAbsent(branch == null ? "" : branch, k -> new ArrayList<>()).add(node);
        }
        List<Branch> branches = new ArrayList<>();
        List<Node> root = byBranch.remove("");
        if (root != null) {
            branches.add(branch(null, 0, root));
        }
        for (String id : branchOrder(def.asset(), byBranch.keySet())) {
            branches.add(branch(id, TrackPicks.branchSpent(onTrack, state, id), byBranch.get(id)));
        }
        return new View(def, level, earned, spent, Math.max(0, earned - spent), branches);
    }

    /**
     * Is {@code pick} one of the tree's: bought outright, and taken on its track's own page (no trainer its
     * {@code Where} names teaches now)?
     */
    static boolean inTree(@Nonnull TrackPicks.PickDef pick) {
        return !pick.isChoice() && PickTrainers.of(pick.asset()).isEmpty();
    }

    /**
     * The branch ids {@code present} names, in the track's {@code Branches} order (each branch's {@code Order}, lower
     * first, then by id), then any the track does not declare, by id.
     */
    @Nonnull
    static List<String> branchOrder(@Nonnull TrackAsset track, @Nonnull Collection<String> present) {
        Map<String, TrackAsset.Branch> declared = track.branches();
        List<String> sorted = new ArrayList<>(declared.keySet());
        sorted.sort(Comparator.comparingInt((String id) -> declared.get(id).order()).thenComparing(id -> id));
        List<String> out = new ArrayList<>();
        for (String id : sorted) {
            if (present.contains(id)) {
                out.add(id);
            }
        }
        List<String> rest = new ArrayList<>();
        for (String id : present) {
            if (!out.contains(id)) {
                rest.add(id);
            }
        }
        rest.sort(String::compareTo);
        out.addAll(rest);
        return out;
    }

    /**
     * One branch's nodes in rows (pure): grouped by the points their row asks spent in the branch, lowest first, each
     * row's nodes in the order given, and how many more points each row needs at {@code spent}.
     */
    @Nonnull
    static Branch branch(@Nullable String id, int spent, @Nonnull List<Node> nodes) {
        TreeMap<Integer, List<Node>> byGate = new TreeMap<>();
        for (Node node : nodes) {
            byGate.computeIfAbsent(id == null ? 0 : node.gate(), k -> new ArrayList<>()).add(node);
        }
        List<Row> rows = new ArrayList<>(byGate.size());
        int index = 1;
        for (Map.Entry<Integer, List<Node>> row : byGate.entrySet()) {
            rows.add(new Row(index++, row.getKey(), Math.max(0, row.getKey() - spent), row.getValue()));
        }
        return new Branch(id, spent, rows);
    }

    /**
     * The points {@code pick}'s row asks spent in its own branch (pure): the highest {@code Min} its {@code Requires}
     * (top level or an {@code AllOf} group) writes on {@code ziggfreedcommon:track_branch_spent} naming
     * {@code trackId} and the pick's {@code Branch}; 0 for a pick in no branch or one asking for none.
     */
    static int rowGate(@Nonnull TrackPickAsset pick, @Nonnull String trackId) {
        String branch = pick.branch();
        GateSpec requires = pick.requires();
        if (branch == null || requires == null) {
            return 0;
        }
        String track = TrackKey.normalize(trackId);
        int gate = gateIn(requires.factorsOrEmpty(), track, branch);
        for (GateClause group : requires.allOfOrEmpty()) {
            if (group != null) {
                gate = Math.max(gate, gateIn(group.factorsOrEmpty(), track, branch));
            }
        }
        return gate;
    }

    /** The highest branch-points {@code Min} among {@code factors} naming {@code track}'s {@code branch}. */
    private static int gateIn(@Nonnull FactorCondition[] factors, @Nonnull String track, @Nonnull String branch) {
        int gate = 0;
        for (FactorCondition condition : factors) {
            if (condition == null || condition.getMin() == null
                    || !TrackFactors.BRANCH_SPENT.equals(TrackKey.normalize(condition.getFactor()))) {
                continue;
            }
            String param = TrackKey.normalize(condition.getParam());
            int cut = param.lastIndexOf(TrackFactors.BRANCH_SEPARATOR);
            if (cut <= 0 || !param.substring(0, cut).equals(track) || !param.substring(cut + 1).equals(branch)) {
                continue;
            }
            double min = condition.getMin();
            gate = Math.max(gate, Double.isFinite(min) ? (int) Math.max(0, Math.min(Integer.MAX_VALUE, Math.ceil(min)))
                    : 0);
        }
        return gate;
    }

    /**
     * What a take of {@code pick} would answer now, read without writing: taken as often as it may be first, then
     * every rule a take on the track's own page asks ({@link PickService#open}: its level, its node, its Requires and
     * prerequisites; then its points on their pool and its spend cap). A trainer's pick is never in the tree.
     */
    @Nonnull
    private static Status status(@Nonnull UUID player, @Nonnull TrackPicks.PickDef pick, @Nonnull PickState state,
            int level, @Nonnull GateQuery query, @Nonnull Map<String, TrackRecord> records) {
        if (state.takesOf(pick.id()) >= pick.asset().repeatMax()) {
            return Status.MAXED;
        }
        Status open = PickService.open(pick, state, level, query);
        if (open != Status.DONE) {
            return open;
        }
        int cost = pick.asset().costPoints();
        if (cost <= 0) {
            return Status.DONE;
        }
        TrackDef pool = TrackService.def(pick.asset().costTrack());
        TrackRecord poolRecord = pool == null ? null : record(player, pool.id(), records);
        if (poolRecord == null) {
            return Status.NO_POINTS;
        }
        long spent = spentOn(player, pool.id(), records);
        if (spent + cost > PickService.pointsEarned(pool, poolRecord)) {
            return Status.NO_POINTS;
        }
        int cap = pick.asset().spendCap();
        return cap > 0 && spent + cost > cap ? Status.SPEND_CAP : Status.DONE;
    }

    /** The points {@code player} has spent of {@code poolId}'s pool, on every pick priced in it. */
    private static int spentOn(@Nonnull UUID player, @Nonnull String poolId, @Nonnull Map<String, TrackRecord> records) {
        return TrackPicks.spentOn(poolId, TrackPicks.all().values(), id -> record(player, id, records));
    }

    @Nullable
    private static TrackRecord record(@Nonnull UUID player, @Nonnull String trackId,
            @Nonnull Map<String, TrackRecord> records) {
        if (records.containsKey(trackId)) {
            return records.get(trackId);
        }
        TrackReading reading = TrackService.read(TrackKey.player(player, trackId));
        TrackRecord record = reading == null ? null : reading.record();
        records.put(trackId, record);
        return record;
    }
}
