package com.ziggfreed.common.instance.dungeon.tier;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * A dungeon's tiers as a ladder, pure: which rungs a player has opened, which a party may pick, and which opens next.
 * No engine type, so it runs in a plain unit JVM.
 *
 * <p>A rung is a dungeon file: its id, its {@code Tier.Rank} (1 for a dungeon that writes no {@code Tier}), the
 * dungeon whose clear opens it ({@code Tier.Opens}, null for a rung open to everyone) and whether it is a daily
 * ({@code Seed.Daily}). A player's opened rank is the highest rank among the rungs open to them: a rung is open when it
 * names no {@code Opens}, or when they have cleared the dungeon it names, so a tier's opening reads the clear of the
 * tier below. A party's rank is the lowest of its members', and it may pick every rung up to it.
 */
public final class TierLadder {

    /** By rank, the normal rung before the daily at one rank, then by id. */
    public static final Comparator<Rung> ORDER = Comparator.comparingInt(Rung::rank)
            .thenComparing(Rung::daily)
            .thenComparing(rung -> rung.id().toLowerCase(Locale.ROOT));

    private TierLadder() {
    }

    /**
     * One rung of a ladder.
     *
     * @param id    the dungeon's id
     * @param rank  its {@code Tier.Rank}, at least 1
     * @param opens the id of the dungeon whose clear opens it, or null when it is open to everyone
     * @param daily whether it is a daily ({@code Seed.Daily})
     */
    public record Rung(@Nonnull String id, int rank, @Nullable String opens, boolean daily) {

        public Rung {
            rank = Math.max(1, rank);
            opens = opens == null || opens.isBlank() ? null : opens.trim();
        }
    }

    /**
     * The highest rank among {@code rungs} open to a player whose clears {@code cleared} answers (a dungeon id, as a
     * rung's {@code opens} names it); 0 when none is open to them.
     */
    public static int openedRank(@Nonnull Collection<Rung> rungs, @Nonnull Predicate<String> cleared) {
        int best = 0;
        for (Rung rung : rungs) {
            if (rung.rank() > best && (rung.opens() == null || cleared.test(rung.opens()))) {
                best = rung.rank();
            }
        }
        return best;
    }

    /** The lowest of the members' opened ranks: the rank their party may pick up to; 0 for no member. */
    public static int partyRank(@Nonnull Collection<Integer> memberRanks) {
        int lowest = Integer.MAX_VALUE;
        for (Integer rank : memberRanks) {
            if (rank != null && rank < lowest) {
                lowest = rank;
            }
        }
        return lowest == Integer.MAX_VALUE ? 0 : Math.max(0, lowest);
    }

    /** The rungs a party of rank {@code partyRank} may pick, in {@link #ORDER}. */
    @Nonnull
    public static List<Rung> pickable(@Nonnull Collection<Rung> rungs, int partyRank) {
        List<Rung> out = new ArrayList<>();
        for (Rung rung : rungs) {
            if (rung.rank() <= partyRank) {
                out.add(rung);
            }
        }
        out.sort(ORDER);
        return List.copyOf(out);
    }

    /**
     * The lowest normal rung above {@code partyRank}, the one the party opens next, or null when it has opened every
     * rank; the first by id at that rank.
     */
    @Nullable
    public static Rung next(@Nonnull Collection<Rung> rungs, int partyRank) {
        Rung next = null;
        for (Rung rung : rungs) {
            if (!rung.daily() && rung.rank() > partyRank && (next == null || ORDER.compare(rung, next) < 0)) {
                next = rung;
            }
        }
        return next;
    }
}
