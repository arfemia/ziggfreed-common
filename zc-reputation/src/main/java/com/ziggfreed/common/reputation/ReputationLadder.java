package com.ziggfreed.common.reputation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The rank ladder as zc reads it: the server's shared native {@code ReputationRank}s sorted by
 * {@code MinValue}, plus, for one reputation, its own tiers above the shared top
 * ({@link #of(Collection, Map)}). A value's rank is the HIGHEST rank whose floor it reaches, and a value
 * below the bottom reads as the bottom rank, so a gap or an overlap between authored ranks never leaves a
 * value rankless.
 * Below two ranks the ladder is unusable (the engine's own clamp is garbage then) and answers no rank.
 * Pure: the engine snapshot comes in through {@link ReputationNative#ranks()}.
 */
public final class ReputationLadder {

    /** One rank: its id as authored and its half-open span {@code [min, max)}. */
    public record Rank(@Nonnull String id, int min, int max) {
    }

    /** Where a value stands inside its rank: {@code current} out of {@code span}. */
    public record Progress(long current, long span) {

        /** A bar drawn full: the open top with nothing to count toward. */
        public static final Progress FULL = new Progress(1, 1);
    }

    /** No ranks at all. */
    public static final ReputationLadder EMPTY = new ReputationLadder(List.of());

    private final List<Rank> ranks;

    private ReputationLadder(@Nonnull List<Rank> sorted) {
        this.ranks = List.copyOf(sorted);
    }

    /** The ladder of {@code ranks}, sorted by floor whatever order they arrive in. */
    @Nonnull
    public static ReputationLadder of(@Nonnull Collection<Rank> ranks) {
        List<Rank> sorted = new ArrayList<>(ranks);
        sorted.sort(Comparator.comparingInt(Rank::min));
        return new ReputationLadder(sorted);
    }

    /** Why a rank of a reputation's own is left off its ladder. */
    public enum Refusal {
        /** {@code From} on one of the server's shared ranks, whose floors every reputation shares. */
        SHARED_RANK,
        /** {@code From} at or below the shared top's floor, or at or past its ceiling. */
        OUT_OF_RANGE,
        /** {@code From} equal to another tier's, so one of the two could never be reached. */
        SHARED_FLOOR
    }

    /** One tier a reputation authored that its ladder leaves out, and why. */
    public record RefusedTier(@Nonnull String id, int from, @Nonnull Refusal refusal) {
    }

    /**
     * The ladder ONE reputation reads: the server's shared {@code global} ranks, then the reputation's own
     * tiers above the shared top, each from its {@code From} floor to the next tier's floor, the highest to
     * the shared top's ceiling; the shared top then reaches only to the first tier. A floor on a shared rank
     * id, at or below the shared top's floor (or at or past its ceiling), or equal to another tier's is left
     * out ({@link #refusedTiers}), so the shared floors never move. With no tiers this is {@link #of(Collection)}.
     */
    @Nonnull
    public static ReputationLadder of(@Nonnull Collection<Rank> global, @Nonnull Map<String, Integer> fromFloors) {
        return fold(global, fromFloors).ladder();
    }

    /** The tiers {@link #of(Collection, Map)} leaves out of {@code fromFloors}, in floor order, each with why. */
    @Nonnull
    public static List<RefusedTier> refusedTiers(@Nonnull Collection<Rank> global,
            @Nonnull Map<String, Integer> fromFloors) {
        return fold(global, fromFloors).refused();
    }

    /** One walk, two answers, so the ladder and the audit cannot disagree about a tier. */
    private record Fold(@Nonnull ReputationLadder ladder, @Nonnull List<RefusedTier> refused) {
    }

    @Nonnull
    private static Fold fold(@Nonnull Collection<Rank> global, @Nonnull Map<String, Integer> fromFloors) {
        ReputationLadder shared = of(global);
        if (fromFloors.isEmpty()) {
            return new Fold(shared, List.of());
        }
        Rank top = shared.top();
        List<Map.Entry<String, Integer>> tiers = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : fromFloors.entrySet()) {
            if (entry.getKey() != null && !entry.getKey().isBlank() && entry.getValue() != null) {
                tiers.add(Map.entry(entry.getKey().trim(), entry.getValue()));
            }
        }
        tiers.sort(Comparator.comparingInt((Map.Entry<String, Integer> tier) -> tier.getValue())
                .thenComparing(tier -> tier.getKey().toLowerCase(Locale.ROOT)));
        List<Rank> kept = new ArrayList<>();
        List<RefusedTier> refused = new ArrayList<>();
        for (Map.Entry<String, Integer> tier : tiers) {
            Refusal why = refusalOf(shared, top, kept, tier.getKey(), tier.getValue());
            if (why != null) {
                refused.add(new RefusedTier(tier.getKey(), tier.getValue(), why));
            } else {
                kept.add(new Rank(tier.getKey(), tier.getValue(), tier.getValue()));
            }
        }
        if (kept.isEmpty()) {
            return new Fold(shared, List.copyOf(refused));
        }
        List<Rank> ranks = new ArrayList<>(shared.ranks());
        ranks.set(ranks.size() - 1, new Rank(top.id(), top.min(), kept.get(0).min()));
        for (int i = 0; i < kept.size(); i++) {
            int max = i + 1 < kept.size() ? kept.get(i + 1).min() : top.max();
            ranks.add(new Rank(kept.get(i).id(), kept.get(i).min(), max));
        }
        return new Fold(new ReputationLadder(ranks), List.copyOf(refused));
    }

    @Nullable
    private static Refusal refusalOf(@Nonnull ReputationLadder shared, @Nullable Rank top, @Nonnull List<Rank> kept,
            @Nonnull String id, int from) {
        if (shared.byId(id) != null) {
            return Refusal.SHARED_RANK;
        }
        if (top == null || !shared.usable() || from <= top.min() || from >= top.max()) {
            return Refusal.OUT_OF_RANGE;
        }
        if (!kept.isEmpty() && kept.get(kept.size() - 1).min() == from) {
            return Refusal.SHARED_FLOOR;
        }
        return null;
    }

    /** Every rank, bottom first. */
    @Nonnull
    public List<Rank> ranks() {
        return ranks;
    }

    /** Two ranks or more: anything less and no rank reading answers. */
    public boolean usable() {
        return ranks.size() >= 2;
    }

    /** The rank named {@code id}, without regard to case, or null. */
    @Nullable
    public Rank byId(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String wanted = id.trim();
        for (Rank rank : ranks) {
            if (rank.id().equalsIgnoreCase(wanted)) {
                return rank;
            }
        }
        return null;
    }

    /** The highest rank whose floor {@code value} reaches (the bottom for a value below it); null when unusable. */
    @Nullable
    public Rank rankFor(long value) {
        if (!usable()) {
            return null;
        }
        Rank found = ranks.get(0);
        for (Rank rank : ranks) {
            if (rank.min() > value) {
                break;
            }
            found = rank;
        }
        return found;
    }

    /** Where {@code rank} sits, bottom 0; -1 for null or a rank not on this ladder. */
    public int indexOf(@Nullable Rank rank) {
        return rank == null ? -1 : ranks.indexOf(rank);
    }

    /**
     * The ranks a player at {@code current} has reached with a reputation that starts in {@code start} (the
     * rank its InitialReputationValue lands in), bottom first: at or above the start, from the start up to
     * and including {@code current}; below it, from {@code current} up to the rank just below the start (the
     * ranks reached going down). Never a rank below the start while at or above it, never the start's own or
     * above while below it. A start off the ladder counts from the bottom; empty for no current rank.
     */
    @Nonnull
    public List<Rank> credited(@Nullable Rank current, @Nullable Rank start) {
        int at = indexOf(current);
        if (at < 0) {
            return List.of();
        }
        int from = Math.max(0, indexOf(start));
        return at >= from ? List.copyOf(ranks.subList(from, at + 1)) : List.copyOf(ranks.subList(at, from));
    }

    /** The rank above {@code rank}, or null at the top (or for null). */
    @Nullable
    public Rank next(@Nullable Rank rank) {
        int index = indexOf(rank);
        return index < 0 || index + 1 >= ranks.size() ? null : ranks.get(index + 1);
    }

    @Nullable
    public Rank top() {
        return ranks.isEmpty() ? null : ranks.get(ranks.size() - 1);
    }

    @Nullable
    public Rank bottom() {
        return ranks.isEmpty() ? null : ranks.get(0);
    }

    /**
     * Where {@code value} stands inside its rank: from the rank's floor to the next rank's floor, and on the
     * open top against {@code beyondEvery} (each payout's span), else a full bar.
     */
    @Nonnull
    public Progress progress(long value, int beyondEvery) {
        Rank rank = rankFor(value);
        if (rank == null) {
            return Progress.FULL;
        }
        Rank next = next(rank);
        if (next != null) {
            return new Progress(Math.max(0L, value - rank.min()), (long) next.min() - rank.min());
        }
        if (beyondEvery > 0) {
            return new Progress(Math.floorMod(value - rank.min(), (long) beyondEvery), beyondEvery);
        }
        return Progress.FULL;
    }

    /**
     * How many multiples of {@code every} past {@code floor} a rise of earned standing from {@code before} to
     * {@code after} newly crosses; the floor itself pays nothing, a fall nothing, no Every nothing.
     */
    public static int crossings(long before, long after, int floor, int every) {
        if (every <= 0 || after <= before) {
            return 0;
        }
        long gained = steps(after, floor, every) - steps(before, floor, every);
        return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, gained));
    }

    /** The earned standing the next Beyond payout lands at, for a player standing at {@code earned}. */
    public static long nextRewardAt(long earned, int floor, int every) {
        return floor + (steps(earned, floor, every) + 1) * (long) every;
    }

    private static long steps(long value, int floor, int every) {
        return value <= floor ? 0L : (value - floor) / every;
    }
}
