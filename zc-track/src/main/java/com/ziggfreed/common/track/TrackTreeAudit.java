package com.ziggfreed.common.track;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.grant.Grant;
import com.ziggfreed.common.grant.StatGrant;
import com.ziggfreed.common.progress.gate.GateClause;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.track.asset.TrackAsset;
import com.ziggfreed.common.track.pick.TrackPickAsset;
import com.ziggfreed.common.track.pick.TrackPicks;
import com.ziggfreed.common.validation.Finding;

/**
 * The shape audit of a track's tree (R736), pure over the switched-on tracks and the tree as authored
 * ({@link TrackPicks#authored}: the live picks, and the picks switched off in their own pack files whose lineage is
 * live, so a node shipped off for a later pass still counts in every check below, what it will grant once switched
 * on included), filed under the track audit's domain; every finding is a WARNING but {@link #STARTER_WALKS_OFF}, an
 * INFO. A tree is a switched-on track that declares {@code Branches} or writes a {@code Tree} group.
 * What it checks against the track's {@code Tree} group (read by this audit alone) where one is written:
 *
 * <ul>
 *   <li>{@link #EMPTY_BRANCH}: a declared branch no pick sits in;</li>
 *   <li>{@link #GATE_OUT_OF_REACH}: a row gate ({@code track_branch_spent}, Min N) the nodes below it in that branch
 *       can never reach, so the row never opens; {@link #GATE_SLACK_THIN}: they reach it with fewer spare points
 *       than {@code Tree.GateSlack};</li>
 *   <li>{@link #BRANCH_POINTS_OFF}: a branch whose nodes add up to other than {@code Tree.BranchPoints}, each node at
 *       its full rank, a choice node at its dearest face;</li>
 *   <li>{@link #NODE_ONE_FACE}: a {@code Node} with one face; {@link #NODE_FACES_UNEVEN}: faces of one node that cost,
 *       rank, open or keep through a prestige differently;</li>
 *   <li>{@link #STARTER_STOPS}, {@link #STARTER_SHORT}, {@link #STARTER_PAST_CAP}, {@link #STARTER_STEP_DROPPED}: the
 *       track's {@code Starter} walked as a one-time claim walks it, at the level that earns the whole pool, with
 *       every point the pool holds ({@code TrackAsset.pointsCap}): a step that must wait stops it, a pool it leaves
 *       unspent, steps past the pool, and a step it drops (taken as often as it may be, or its node taken); and, at
 *       INFO, {@link #STARTER_WALKS_OFF}: how many of its steps walk a node still switched off in its own file (in the
 *       tree as authored, not live), each passed over by a player's claim today, so it spends less than the pool (the
 *       consumer's release-seam check fails on it; development stays green);</li>
 *   <li>{@link #STAT_PAST_CEILING}: a stat one build can raise past its {@code Tree.Ceilings} entry: its
 *       {@code Points.Grants} for every point of the pool plus the most the pool can buy of the nodes granting it (a
 *       knapsack over the costs, gates aside, so it is the peak a build could ever hold); with a {@code Tree} group
 *       written, {@link #STAT_WITHOUT_CEILING} for a stat the tree grants that it names no ceiling for;</li>
 *   <li>{@link #NON_STAT_GRANT}: a node, or the track's {@code Points.Grants}, granting a kind other than {@code Stat}
 *       (every quantity a tree gives is a native stat, which gear and effects can carry too).</li>
 * </ul>
 *
 * <p>Gates are read from a pick's {@code Requires} at its top level and its {@code AllOf} clauses; an {@code AnyOf}
 * or {@code Not} clause is not reasoned about. A {@code Stat} grant's {@code Add} and {@code Percent} are summed as
 * written.
 */
public final class TrackTreeAudit {

    public static final String EMPTY_BRANCH = "EMPTY_BRANCH";
    public static final String GATE_OUT_OF_REACH = "GATE_OUT_OF_REACH";
    public static final String GATE_SLACK_THIN = "GATE_SLACK_THIN";
    public static final String BRANCH_POINTS_OFF = "BRANCH_POINTS_OFF";
    public static final String NODE_ONE_FACE = "NODE_ONE_FACE";
    public static final String NODE_FACES_UNEVEN = "NODE_FACES_UNEVEN";
    public static final String STARTER_STOPS = "STARTER_STOPS";
    public static final String STARTER_SHORT = "STARTER_SHORT";
    public static final String STARTER_PAST_CAP = "STARTER_PAST_CAP";
    public static final String STARTER_STEP_DROPPED = "STARTER_STEP_DROPPED";
    public static final String STARTER_WALKS_OFF = "STARTER_WALKS_OFF";
    public static final String STAT_PAST_CEILING = "STAT_PAST_CEILING";
    public static final String STAT_WITHOUT_CEILING = "STAT_WITHOUT_CEILING";
    public static final String NON_STAT_GRANT = "NON_STAT_GRANT";

    private static final String DOMAIN = TrackValidator.DOMAIN;

    /** How many off nodes a {@link #STARTER_WALKS_OFF} finding names before it says there are more. */
    private static final int STARTER_OFF_NAMED = 5;

    private TrackTreeAudit() {
    }

    /**
     * Audit every tree among {@code tracks} (switched-on tracks) over {@code picks} (the tree as authored,
     * {@link TrackPicks#authored} or a {@link TrackPicks#foldAuthored} of the files).
     */
    @Nonnull
    public static List<Finding> audit(@Nonnull Collection<TrackAsset> tracks,
            @Nonnull Collection<TrackPicks.PickDef> picks) {
        Map<String, List<TrackPicks.PickDef>> byTrack = new TreeMap<>();
        // How many picks, on any track, name each either-or group: a group one pick alone names is no choice.
        Map<String, Integer> faceCounts = new HashMap<>();
        for (TrackPicks.PickDef pick : picks) {
            byTrack.computeIfAbsent(pick.trackId(), k -> new ArrayList<>()).add(pick);
            for (String node : pick.asset().nodes()) {
                faceCounts.merge(node, 1, Integer::sum);
            }
        }
        List<Finding> out = new ArrayList<>();
        for (TrackAsset track : tracks) {
            if (track == null || track.getId() == null || (track.branches().isEmpty() && track.tree() == null)) {
                continue;
            }
            String trackId = TrackKey.normalize(track.getId());
            List<TrackPicks.PickDef> onTrack = byTrack.getOrDefault(trackId, List.of());
            auditTree(track, trackId, onTrack, faceCounts, out);
        }
        return out;
    }

    private static void auditTree(@Nonnull TrackAsset track, @Nonnull String trackId,
            @Nonnull List<TrackPicks.PickDef> onTrack, @Nonnull Map<String, Integer> faceCounts,
            @Nonnull List<Finding> out) {
        String id = track.getId();
        TrackAsset.Tree template = track.tree();
        List<TrackPicks.PickDef> bought = new ArrayList<>();
        for (TrackPicks.PickDef pick : onTrack) {
            if (!pick.isChoice()) {
                bought.add(pick);
            }
        }
        Map<String, List<TrackPicks.PickDef>> byBranch = new TreeMap<>();
        for (TrackPicks.PickDef pick : bought) {
            String branch = pick.asset().branch();
            if (branch != null) {
                byBranch.computeIfAbsent(branch, k -> new ArrayList<>()).add(pick);
            }
        }
        if (!onTrack.isEmpty()) {
            for (String branch : track.branches().keySet()) {
                if (!byBranch.containsKey(branch)) {
                    out.add(Finding.warning(DOMAIN, EMPTY_BRANCH, "track '" + id + "' declares the branch '" + branch
                            + "', where no pick sits", id));
                }
            }
        }
        auditGates(id, trackId, bought, byBranch, template == null ? 0 : template.gateSlack(), out);
        if (template != null && template.branchPoints() > 0) {
            byBranch.forEach((branch, inBranch) -> {
                long total = fullCost(inBranch);
                if (total != template.branchPoints()) {
                    out.add(Finding.warning(DOMAIN, BRANCH_POINTS_OFF, "track '" + id + "' branch '" + branch
                            + "' adds up to " + (total < 0 ? "no end (a node without a rank limit)" : total
                            + " points") + ", where its Tree.BranchPoints is " + template.branchPoints(), id));
                }
            });
        }
        auditNodes(id, trackId, bought, faceCounts, out);
        auditStarter(track, trackId, bought, out);
        if (template != null) {
            auditCeilings(track, bought, template.ceilings(), out);
        }
        auditKinds(track, onTrack, out);
    }

    // ==================== gates ====================

    /** Each distinct branch gate on the track, against what the nodes below it in that branch can spend. */
    private static void auditGates(@Nonnull String id, @Nonnull String trackId,
            @Nonnull List<TrackPicks.PickDef> bought, @Nonnull Map<String, List<TrackPicks.PickDef>> byBranch,
            int slack, @Nonnull List<Finding> out) {
        Set<String> seen = new TreeSet<>();
        for (TrackPicks.PickDef pick : bought) {
            for (Map.Entry<String, Integer> gate : branchGates(pick.asset(), trackId).entrySet()) {
                String branch = gate.getKey();
                int min = gate.getValue();
                if (min <= 0 || !seen.add(branch + "@" + min)) {
                    continue;
                }
                List<TrackPicks.PickDef> below = new ArrayList<>();
                for (TrackPicks.PickDef candidate : byBranch.getOrDefault(branch, List.of())) {
                    if (branchGates(candidate.asset(), trackId).getOrDefault(branch, 0) < min) {
                        below.add(candidate);
                    }
                }
                long reach = fullCost(below);
                if (reach < 0) {
                    continue; // a node without a rank limit below reaches any gate
                }
                if (reach < min) {
                    out.add(Finding.warning(DOMAIN, GATE_OUT_OF_REACH, "track '" + id + "' opens a row at " + min
                            + " points in '" + branch + "' (first on '" + pick.asset().getId() + "'), but the nodes "
                            + "below it there add up to " + reach + ", so that row never opens", id));
                } else if (slack > 0 && reach - min < slack) {
                    out.add(Finding.warning(DOMAIN, GATE_SLACK_THIN, "track '" + id + "' opens a row at " + min
                            + " points in '" + branch + "' (first on '" + pick.asset().getId() + "'), and the nodes "
                            + "below it there add up to " + reach + ": " + (reach - min) + " spare, under its "
                            + "Tree.GateSlack of " + slack + ", so a player must take nearly every one", id));
                }
            }
        }
    }

    /**
     * What {@code picks} add up to at most, each node at its full rank and the faces of one {@code Node} counted at
     * the dearest; -1 when one has no rank limit and a cost.
     */
    private static long fullCost(@Nonnull List<TrackPicks.PickDef> picks) {
        Map<String, Long> byNode = new LinkedHashMap<>();
        long total = 0L;
        for (TrackPicks.PickDef pick : picks) {
            long cost = fullCostOf(pick.asset());
            if (cost < 0) {
                return -1L;
            }
            String node = pick.asset().node();
            if (node == null) {
                total += cost;
            } else {
                byNode.merge(node, cost, Math::max);
            }
        }
        for (long cost : byNode.values()) {
            total += cost;
        }
        return total;
    }

    /** One pick at its full rank: its cost times its ranks; -1 for a costed pick without a rank limit. */
    private static long fullCostOf(@Nonnull TrackPickAsset asset) {
        int ranks = asset.repeatMax();
        if (asset.costPoints() <= 0) {
            return 0L;
        }
        return ranks == Integer.MAX_VALUE ? -1L : (long) asset.costPoints() * ranks;
    }

    /**
     * The branch gates {@code asset} writes on its own track, by lower-cased branch to the highest Min: its
     * {@code Requires} at the top level and its {@code AllOf} clauses.
     */
    @Nonnull
    static Map<String, Integer> branchGates(@Nonnull TrackPickAsset asset, @Nonnull String trackId) {
        Map<String, Integer> out = new TreeMap<>();
        for (FactorCondition condition : allOfConditions(asset.requires())) {
            if (!TrackFactors.BRANCH_SPENT.equalsIgnoreCase(String.valueOf(condition.getFactor()).trim())) {
                continue;
            }
            String[] parts = TrackFactors.splitBranch(condition.getParam());
            if (parts == null || !trackId.equals(TrackKey.normalize(parts[0]))) {
                continue;
            }
            int min = minOf(condition);
            out.merge(TrackKey.normalize(parts[1]), min, Math::max);
        }
        return out;
    }

    /** The level {@code asset} opens at on its own track: its At, and any {@code track_level} gate naming the track. */
    private static int levelGate(@Nonnull TrackPickAsset asset, @Nonnull String trackId) {
        int level = asset.opensAt();
        for (FactorCondition condition : allOfConditions(asset.requires())) {
            if (TrackFactors.LEVEL.equalsIgnoreCase(String.valueOf(condition.getFactor()).trim())
                    && trackId.equals(TrackKey.normalize(condition.getParam()))) {
                level = Math.max(level, minOf(condition));
            }
        }
        return level;
    }

    private static int minOf(@Nonnull FactorCondition condition) {
        Double min = condition.getMin();
        return min == null || !Double.isFinite(min) ? 0 : (int) Math.ceil(min);
    }

    @Nonnull
    private static List<FactorCondition> allOfConditions(@Nullable GateSpec requires) {
        List<FactorCondition> out = new ArrayList<>();
        if (requires == null) {
            return out;
        }
        List<GateClause> clauses = new ArrayList<>();
        clauses.add(requires);
        clauses.addAll(List.of(requires.allOfOrEmpty()));
        for (GateClause clause : clauses) {
            if (clause == null) {
                continue;
            }
            for (FactorCondition condition : clause.factorsOrEmpty()) {
                if (condition != null && condition.getFactor() != null) {
                    out.add(condition);
                }
            }
        }
        return out;
    }

    // ==================== nodes ====================

    /**
     * The nodes of one track's tree: a group no other pick on any track names ({@link #NODE_ONE_FACE}, read off
     * {@code faceCounts}), and the faces of a row's own choice (each pick's first {@code Node}) that cost, rank, open or
     * keep differently ({@link #NODE_FACES_UNEVEN}). A further group a pick names (two picks of different rows ruling
     * each other out) joins no row, so it is held to no evenness.
     */
    private static void auditNodes(@Nonnull String id, @Nonnull String trackId,
            @Nonnull List<TrackPicks.PickDef> bought, @Nonnull Map<String, Integer> faceCounts,
            @Nonnull List<Finding> out) {
        Map<String, List<TrackPicks.PickDef>> byNode = new TreeMap<>();
        Map<String, TrackPicks.PickDef> named = new TreeMap<>();
        for (TrackPicks.PickDef pick : bought) {
            String node = pick.asset().node();
            if (node != null) {
                byNode.computeIfAbsent(node, k -> new ArrayList<>()).add(pick);
            }
            for (String group : pick.asset().nodes()) {
                named.putIfAbsent(group, pick);
            }
        }
        named.forEach((node, pick) -> {
            // A group whose other faces sit on other tracks (R859) is a choice all the same.
            if (faceCounts.getOrDefault(node, 0) < 2) {
                out.add(Finding.warning(DOMAIN, NODE_ONE_FACE, "track '" + id + "' has the choice node '" + node
                        + "' with one face ('" + pick.asset().getId() + "'), so it is no choice", id));
            }
        });
        byNode.forEach((node, faces) -> {
            if (faces.size() < 2) {
                return;
            }
            TrackPickAsset first = faces.get(0).asset();
            List<String> uneven = new ArrayList<>();
            for (TrackPicks.PickDef face : faces.subList(1, faces.size())) {
                TrackPickAsset other = face.asset();
                note(uneven, "cost", first.costPoints() != other.costPoints());
                note(uneven, "ranks", first.repeatMax() != other.repeatMax());
                note(uneven, "level", levelGate(first, trackId) != levelGate(other, trackId));
                note(uneven, "row", ownGate(first, trackId) != ownGate(other, trackId));
                note(uneven, "prestige", first.resetsAs() != other.resetsAs());
            }
            if (!uneven.isEmpty()) {
                List<String> ids = new ArrayList<>();
                for (TrackPicks.PickDef face : faces) {
                    ids.add(face.asset().getId());
                }
                out.add(Finding.warning(DOMAIN, NODE_FACES_UNEVEN, "track '" + id + "' choice node '" + node
                        + "' has faces (" + String.join(", ", ids) + ") that differ in " + String.join(", ", uneven)
                        + "; the faces of one node are one choice, so they cost, rank, open and keep alike", id));
            }
        });
    }

    /** The row a pick sits in: the Min of its gate on its own branch (0 for none, or a pick in no branch). */
    private static int ownGate(@Nonnull TrackPickAsset asset, @Nonnull String trackId) {
        String branch = asset.branch();
        return branch == null ? 0 : branchGates(asset, trackId).getOrDefault(branch, 0);
    }

    private static void note(@Nonnull List<String> into, @Nonnull String what, boolean differs) {
        if (differs && !into.contains(what)) {
            into.add(what);
        }
    }

    // ==================== the starter build ====================

    /** Walk the Starter as a one-time claim does, with the whole pool, at the level that earns it. */
    private static void auditStarter(@Nonnull TrackAsset track, @Nonnull String trackId,
            @Nonnull List<TrackPicks.PickDef> bought, @Nonnull List<Finding> out) {
        List<String> steps = track.starter();
        int pool = track.pointsCap();
        if (steps.isEmpty() || pool <= 0) {
            return;
        }
        String id = track.getId();
        int level = pool / Math.max(1, track.pointsPerLevel()) + 1;
        Map<String, TrackPicks.PickDef> byId = new HashMap<>();
        for (TrackPicks.PickDef pick : bought) {
            byId.put(pick.id(), pick);
        }
        auditStarterOff(id, steps, byId, out);
        Map<String, Integer> takes = new HashMap<>();
        Map<String, Integer> inBranch = new HashMap<>();
        int spent = 0;
        for (int i = 0; i < steps.size(); i++) {
            String step = steps.get(i);
            TrackPicks.PickDef pick = byId.get(TrackKey.normalize(step));
            if (pick == null || !pick.asset().placements().isEmpty()) {
                continue; // not in the tree as authored, or a trainer's: passed over (UNKNOWN_STARTER_PICK names one)
            }
            TrackPickAsset asset = pick.asset();
            String wait = null;
            boolean dropped = false;
            if (levelGate(asset, trackId) > level) {
                wait = "it opens at level " + levelGate(asset, trackId) + ", past level " + level;
            }
            for (String face : pick.otherFaces()) {
                if (wait == null && takes.getOrDefault(face, 0) > 0) {
                    dropped = true;
                }
            }
            if (wait == null && !dropped) {
                for (Map.Entry<String, Integer> gate : branchGates(asset, trackId).entrySet()) {
                    int have = inBranch.getOrDefault(gate.getKey(), 0);
                    if (wait == null && have < gate.getValue()) {
                        wait = "it needs " + gate.getValue() + " points in '" + gate.getKey() + "' and the build has "
                                + have + " there";
                    }
                }
            }
            if (wait == null && !dropped) {
                for (String needed : asset.prerequisites()) {
                    if (wait == null && takes.getOrDefault(needed, 0) < 1) {
                        wait = "it needs '" + needed + "' taken first";
                    }
                }
            }
            if (wait == null && !dropped && takes.getOrDefault(pick.id(), 0) >= asset.repeatMax()) {
                dropped = true;
            }
            int cost = trackId.equals(TrackKey.normalize(asset.costTrack())) ? asset.costPoints() : 0;
            if (wait == null && !dropped && spent + cost > pool) {
                out.add(Finding.warning(DOMAIN, STARTER_PAST_CAP, "track '" + id + "' starter build spends its whole "
                        + "pool of " + pool + " points by step " + i + ", so step " + (i + 1) + " ('" + step
                        + "') and every one after it is never spent", id));
                return;
            }
            if (wait == null && !dropped && asset.spendCap() > 0 && spent + cost > asset.spendCap()) {
                wait = "its SpendCap of " + asset.spendCap() + " is passed";
            }
            if (dropped) {
                out.add(Finding.warning(DOMAIN, STARTER_STEP_DROPPED, "track '" + id + "' starter build step "
                        + (i + 1) + " ('" + step + "') can never be taken there (taken as often as it may be, or its "
                        + "choice node already taken), so it is dropped", id));
                continue;
            }
            if (wait != null) {
                out.add(Finding.warning(DOMAIN, STARTER_STOPS, "track '" + id + "' starter build stops at step "
                        + (i + 1) + " ('" + step + "'): " + wait + ", so that step and every one after it wait, "
                        + spent + " of " + pool + " points spent", id));
                return;
            }
            takes.merge(pick.id(), 1, Integer::sum);
            spent += cost;
            String branch = asset.branch();
            if (branch != null) {
                inBranch.merge(branch, asset.costPoints(), Integer::sum);
            }
        }
        if (spent < pool) {
            out.add(Finding.warning(DOMAIN, STARTER_SHORT, "track '" + id + "' starter build spends " + spent + " of "
                    + "its pool of " + pool + " points, so a claim of the whole pool leaves " + (pool - spent)
                    + " unspent", id));
        }
    }

    /**
     * The starter build's steps that walk a node still switched off in its own file (in the tree as authored, its
     * file not live), as one INFO a track: the audit walks them, a player's claim passes each over until it is
     * switched on. A pick in the tree as authored is off exactly when its own file is.
     */
    private static void auditStarterOff(@Nonnull String id, @Nonnull List<String> steps,
            @Nonnull Map<String, TrackPicks.PickDef> byId, @Nonnull List<Finding> out) {
        int count = 0;
        Set<String> off = new LinkedHashSet<>();
        for (String step : steps) {
            TrackPicks.PickDef pick = byId.get(TrackKey.normalize(step));
            if (pick != null && !pick.asset().isEnabled()) {
                count++;
                off.add(pick.asset().getId());
            }
        }
        if (count == 0) {
            return;
        }
        List<String> named = new ArrayList<>(off).subList(0, Math.min(off.size(), STARTER_OFF_NAMED));
        out.add(Finding.info(DOMAIN, STARTER_WALKS_OFF, "track '" + id + "' starter build walks " + count
                + " step(s) through " + off.size() + " node(s) still switched off (" + String.join(", ", named)
                + (off.size() > named.size() ? ", ..." : "") + "); a claim today passes them over and leaves their "
                + "points unspent until they are switched on", id));
    }

    // ==================== stat ceilings ====================

    /** Each ceiling the Tree group names, against the peak one build of the tree can grant that stat. */
    private static void auditCeilings(@Nonnull TrackAsset track, @Nonnull List<TrackPicks.PickDef> bought,
            @Nonnull Map<String, Double> ceilings, @Nonnull List<Finding> out) {
        String id = track.getId();
        Set<String> capped = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        capped.addAll(ceilings.keySet());
        Set<String> granted = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        List<Grant> every = new ArrayList<>(track.pointsGrants());
        for (TrackPicks.PickDef pick : bought) {
            every.addAll(pick.asset().grants());
        }
        for (Grant grant : every) {
            if (grant instanceof StatGrant stat && stat.stat() != null && !capped.contains(stat.stat())
                    && granted.add(stat.stat())) {
                out.add(Finding.warning(DOMAIN, STAT_WITHOUT_CEILING, "track '" + id + "' grants the stat '"
                        + stat.stat() + "' on its tree, which its Tree.Ceilings names no ceiling for, so how far one "
                        + "build can raise it is never checked", id));
            }
        }
        int pool = track.pointsCap();
        if (ceilings.isEmpty() || pool < 0) {
            return;
        }
        String trackId = TrackKey.normalize(id);
        for (Map.Entry<String, Double> ceiling : ceilings.entrySet()) {
            String stat = ceiling.getKey();
            double mainLine = pool * amountOn(track.pointsGrants(), stat);
            double free = 0.0;
            Map<String, List<long[]>> groups = new LinkedHashMap<>();
            Map<String, List<Double>> values = new LinkedHashMap<>();
            for (TrackPicks.PickDef pick : bought) {
                TrackPickAsset asset = pick.asset();
                double each = amountOn(asset.grants(), stat);
                if (each == 0.0) {
                    continue;
                }
                int ranks = asset.repeatMax();
                boolean priced = asset.costPoints() > 0 && trackId.equals(TrackKey.normalize(asset.costTrack()));
                if (!priced) {
                    free += ranks == Integer.MAX_VALUE ? Double.POSITIVE_INFINITY : Math.max(0.0, each * ranks);
                    continue;
                }
                String group = asset.node() != null ? "node:" + asset.node() : "pick:" + pick.id();
                groups.computeIfAbsent(group, k -> new ArrayList<>()).add(new long[] {asset.costPoints(),
                        Math.min(ranks, pool)});
                values.computeIfAbsent(group, k -> new ArrayList<>()).add(each);
            }
            double peak = mainLine + free + bestWithin(pool, groups, values);
            if (peak > ceiling.getValue() + 1e-9) {
                out.add(Finding.warning(DOMAIN, STAT_PAST_CEILING, "track '" + id + "' can raise '" + stat + "' by "
                        + String.format(Locale.ROOT, "%.2f", peak) + " in one build (" + String.format(Locale.ROOT,
                        "%.2f", mainLine) + " of it the points' own grants), past its Tree.Ceilings entry of "
                        + String.format(Locale.ROOT, "%.2f", ceiling.getValue()), id));
            }
        }
    }

    /**
     * The most {@code pool} points buy (a knapsack): each group (one pick, or a node's faces, one of which may be
     * taken) taken at 0 to its ranks, each rank its cost, worth its value.
     */
    private static double bestWithin(int pool, @Nonnull Map<String, List<long[]>> groups,
            @Nonnull Map<String, List<Double>> values) {
        double[] best = new double[pool + 1];
        for (Map.Entry<String, List<long[]>> group : groups.entrySet()) {
            double[] next = best.clone();
            List<long[]> faces = group.getValue();
            List<Double> worth = values.get(group.getKey());
            for (int f = 0; f < faces.size(); f++) {
                long cost = faces.get(f)[0];
                long ranks = faces.get(f)[1];
                double each = worth.get(f);
                for (int k = 1; k <= ranks && k * cost <= pool; k++) {
                    int price = (int) (k * cost);
                    for (int p = pool; p >= price; p--) {
                        next[p] = Math.max(next[p], best[p - price] + k * each);
                    }
                }
            }
            best = next;
        }
        double top = 0.0;
        for (double value : best) {
            top = Math.max(top, value);
        }
        return top;
    }

    /** What one hold of {@code grants} adds to {@code stat}: each Stat grant naming it, Add and Percent as written. */
    private static double amountOn(@Nonnull List<Grant> grants, @Nonnull String stat) {
        double total = 0.0;
        for (Grant grant : grants) {
            if (grant instanceof StatGrant granted && granted.stat() != null && granted.stat().equalsIgnoreCase(stat)) {
                total += granted.add() + granted.percent();
            }
        }
        return total;
    }

    // ==================== grant kinds ====================

    /** A node, or the points' own grants, granting a kind other than Stat. */
    private static void auditKinds(@Nonnull TrackAsset track, @Nonnull List<TrackPicks.PickDef> onTrack,
            @Nonnull List<Finding> out) {
        String id = track.getId();
        for (Grant grant : track.pointsGrants()) {
            if (!StatGrant.KIND.equalsIgnoreCase(grant.kind())) {
                out.add(Finding.warning(DOMAIN, NON_STAT_GRANT, "track '" + id + "' grants the kind '" + grant.kind()
                        + "' for each point spent; a tree gives native stats, which gear and effects carry too", id));
            }
        }
        for (TrackPicks.PickDef pick : onTrack) {
            List<Grant> grants = new ArrayList<>(pick.asset().grants());
            for (TrackPicks.GroupDef group : pick.groups()) {
                for (TrackPicks.OptionDef option : group.options().values()) {
                    grants.addAll(option.grants());
                }
            }
            Set<String> kinds = new TreeSet<>();
            for (Grant grant : grants) {
                if (!StatGrant.KIND.equalsIgnoreCase(grant.kind())) {
                    kinds.add(grant.kind());
                }
            }
            if (!kinds.isEmpty()) {
                out.add(Finding.warning(DOMAIN, NON_STAT_GRANT, "track pick '" + pick.asset().getId() + "' on the "
                        + "tree of '" + id + "' grants " + String.join(", ", kinds) + "; a tree node gives native "
                        + "stats, which gear and effects carry too", pick.asset().getId()));
            }
        }
    }
}
