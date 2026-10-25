package com.ziggfreed.common.track;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.instance.reward.DeferredRewards;
import com.ziggfreed.common.instance.reward.InstanceReward;
import com.ziggfreed.common.loot.claim.Claim;
import com.ziggfreed.common.loot.claim.ClaimStore;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.progress.gate.GateQuery;
import com.ziggfreed.common.progress.gate.StoreGates;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.track.asset.TrackAsset;
import com.ziggfreed.common.util.SafeLog;

/**
 * A track's {@code Unlocks} paid as Claims the player collects (M428), once each. A climb keeps, per row, the
 * level the row has paid through ({@link TrackRecord#paidThrough}), filed under the row's key
 * ({@link TrackAsset.Unlock#key()}: its {@code Id}, or a name built from its own levels, never its place in the
 * list), so rows added, removed or reordered on a live track each keep their own mark. A pass pays every level
 * of the row above that mark and at or below the best level ever reached, then moves the mark to the last level
 * it paid (a row with nothing due keeps its mark, so a player who never reached a paying level keeps no
 * record). The mark only rises, and it is read against the BEST level, which a prestige never lowers, so a fall,
 * a climb back and a prestige pay nothing twice, while a row added to a track later pays what is already behind
 * the player.
 *
 * <p>Each level is one Claim, {@code <track>:<row key>:<level>}, paying the row's rewards as they are written at
 * that moment, through the one deferral every Claim uses ({@code DeferredRewards}). A row's reward that its own
 * {@code Requires} refuses the player is left out; a level whose row pays nothing here still moves the mark. At
 * most {@link #MAX_CLAIMS_PER_PASS} Claims a pass: a bigger jump pays the rest on the next change or login.
 *
 * <p><b>A pass is two steps</b>, so a failure never loses or doubles a Claim: {@link #plan} works out the marks
 * and the Claims they stand for, the caller saves the marks, and only then {@link #pay} writes the Claims. A
 * Claim that cannot be written puts its row's mark back to the last level that row did write (or where it stood
 * before the pass) and leaves the rest of that row for the next pass; the caller saves that record again.
 */
final class TrackUnlocks {

    /** The most Claims one pass writes. */
    public static final int MAX_CLAIMS_PER_PASS = 50;

    /** What a track's Claim source and its returns key start with. */
    public static final String SOURCE_PREFIX = "track:";

    /** The name a deferred command keeps for whoever presses Claim. */
    private static final String CLAIMER = "{player}";

    private TrackUnlocks() {
    }

    /** What a pass did: the record with its marks moved, and how many Claims it wrote. */
    public record Parked(@Nonnull TrackRecord record, int claims) {
    }

    /** One Claim a pass owes: the row, by its key, the level, and what it pays. */
    record Owed(@Nonnull String row, int level, @Nonnull List<InstanceReward> rewards) {
    }

    /**
     * A pass worked out and not yet paid: the record with every mark it moves, the Claims those marks stand for
     * (row by row, each row's levels rising), and where each row that owes a Claim stood before the pass.
     */
    record Plan(@Nonnull TrackRecord record, @Nonnull List<Owed> owed, @Nonnull Map<String, Integer> before) {
    }

    /** {@code <track>:<row key>:<level>}: one Claim per row and level, per player. */
    @Nonnull
    public static String claimId(@Nonnull String trackId, @Nonnull String rowKey, int level) {
        return TrackKey.normalize(trackId) + ':' + rowKey + ':' + level;
    }

    /**
     * Work out every Claim {@code record}'s rows owe {@code player} up to {@code best}, writing nothing: each row's
     * reward entries asked of the player through {@code query} (null asks nothing), deferred once per row. The
     * plan's record carries every mark the pass moves; the caller saves it, then hands the plan to {@link #pay}.
     */
    @Nonnull
    static Plan plan(@Nonnull UUID player, @Nullable GateQuery query, @Nonnull TrackDef def,
            @Nonnull TrackRecord record, int best) {
        TrackRecord out = record;
        List<Owed> owed = new ArrayList<>();
        Map<String, Integer> before = new HashMap<>();
        String source = SOURCE_PREFIX + def.id();
        for (TrackAsset.Unlock unlock : def.asset().unlocks()) {
            String row = unlock.key();
            int from = out.paidThrough(row);
            if (from >= best) {
                continue;
            }
            int level = unlock.nextLevelAfter(from);
            if (level == Integer.MAX_VALUE || level > best) {
                continue;
            }
            List<InstanceReward> rewards;
            try {
                rewards = rewardsFor(unlock, player, query, source);
            } catch (Throwable t) {
                // The row keeps its mark, so the next pass asks again.
                SafeLog.warn("[track] the rewards of unlock row '" + row + "' on '" + def.id() + "' could not be "
                        + "read for " + player + ": " + t.getMessage());
                continue;
            }
            int paidTo = from;
            while (level != Integer.MAX_VALUE && level <= best) {
                if (!rewards.isEmpty()) {
                    if (owed.size() >= MAX_CLAIMS_PER_PASS) {
                        break;
                    }
                    owed.add(new Owed(row, level, rewards));
                }
                // A level whose row paid nothing here (its rewards gated out) is settled all the same.
                paidTo = level;
                level = unlock.nextLevelAfter(level);
            }
            if (paidTo > from) {
                before.putIfAbsent(row, from);
                out = out.withPaid(row, paidTo);
            }
        }
        return new Plan(out, List.copyOf(owed), Map.copyOf(before));
    }

    /**
     * Write the Claims {@code plan} owes, once its record is saved. A Claim the player still holds by its id is
     * not written again and counts as paid. A Claim that cannot be written puts its row's mark back to the last
     * level that row wrote in this pass (or where it stood before), and the row's later levels wait for the next
     * pass, so the caller saves the answered record again whenever it differs from the plan's.
     */
    @Nonnull
    static Parked pay(@Nonnull UUID player, @Nonnull TrackDef def, @Nonnull Plan plan, long nowMs) {
        TrackRecord out = plan.record();
        int written = 0;
        String source = SOURCE_PREFIX + def.id();
        Map<String, Integer> lastPaid = new HashMap<>();
        Set<String> failed = new HashSet<>();
        for (Owed claim : plan.owed()) {
            if (failed.contains(claim.row())) {
                continue;
            }
            try {
                if (award(player, def, claim, source, nowMs)) {
                    written++;
                }
                lastPaid.put(claim.row(), claim.level());
            } catch (Throwable t) {
                failed.add(claim.row());
                Integer back = lastPaid.get(claim.row());
                out = out.withPaidAt(claim.row(), back != null ? back : plan.before().getOrDefault(claim.row(), 0));
                SafeLog.warn("[track] the level " + claim.level() + " Claim of '" + def.id() + "' for " + player
                        + " could not be written, so it waits for the next pass: " + t.getMessage());
            }
        }
        return new Parked(out, written);
    }

    /** One row's rewards as a Claim pays them: the entries the player meets, deferred. */
    @Nonnull
    private static List<InstanceReward> rewardsFor(@Nonnull TrackAsset.Unlock unlock, @Nonnull UUID player,
            @Nullable GateQuery query, @Nonnull String source) {
        List<RewardSpec> met = new ArrayList<>();
        for (RewardSpec spec : unlock.rewards()) {
            if (query == null || StoreGates.passes(spec.requires(), query)) {
                met.add(spec);
            }
        }
        if (met.isEmpty()) {
            return List.of();
        }
        return InstanceReward.merge(DeferredRewards.fromSpecs(met, RewardKinds.shared(), Subject.of(player, CLAIMER),
                source, message -> SafeLog.warn("[track] " + message)));
    }

    /**
     * Write one level's Claim; true when it was written, false when the player still holds a Claim by that id or
     * it came to nothing. Throws when it could not be written at all, which {@link #pay} answers by putting the
     * row's mark back.
     */
    private static boolean award(@Nonnull UUID player, @Nonnull TrackDef def, @Nonnull Owed owed,
            @Nonnull String source, long nowMs) {
        Claim claim = new Claim(claimId(def.id(), owed.row(), owed.level()), source,
                TrackText.PREFIX + "claim.unlock",
                List.of(TrackText.claimNameArg(def.asset()), Integer.toString(owed.level())), null, owed.rewards(),
                nowMs);
        return ClaimStore.shared().award(player, claim, null, source, nowMs);
    }
}
