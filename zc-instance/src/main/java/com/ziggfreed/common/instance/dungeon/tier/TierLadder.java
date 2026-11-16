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
 * A dungeon's tiers as a ladder, pure: which rungs a player has open, which a party may pick, and which opens next.
 * No engine type, so it runs in a plain unit JVM.
 *
 * <p>A rung is a dungeon file: its id, its {@code Tier.Rank} (1 for a dungeon that writes no {@code Tier}), the
 * dungeon whose clear opens it ({@code Tier.Opens}, null for a rung open to everyone) and whether it is a daily
 * ({@code Seed.Daily}). A rung is open to a player when it names no {@code Opens} or they have cleared the dungeon it
 * names: its own {@code Opens}, never its rank, so two rungs of one rank opened by different dungeons, or a tier that
 * skips one, each open on their own clear. A party may pick a rung only when it is open to every member asked (the
 * lowest across members, rung by rung). Rank orders the list and nothing else.
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
     * Whether {@code rung} is open to a player whose clears {@code cleared} answers (a dungeon id, as a rung's
     * {@code opens} names it): it names no {@code Opens}, or they have cleared the dungeon it names.
     */
    public static boolean open(@Nonnull Rung rung, @Nonnull Predicate<String> cleared) {
        return rung.opens() == null || cleared.test(rung.opens());
    }

    /**
     * Whether {@code rung} is open to every one of {@code members} (each one's clears); with no member asked, only a
     * rung open to everyone is.
     */
    public static boolean openToAll(@Nonnull Rung rung, @Nonnull Collection<Predicate<String>> members) {
        if (rung.opens() == null) {
            return true;
        }
        if (members.isEmpty()) {
            return false;
        }
        for (Predicate<String> cleared : members) {
            if (!open(rung, cleared)) {
                return false;
            }
        }
        return true;
    }

    /** The rungs open to every one of {@code members}, the ones a party may pick, in {@link #ORDER}. */
    @Nonnull
    public static List<Rung> pickable(@Nonnull Collection<Rung> rungs,
            @Nonnull Collection<Predicate<String>> members) {
        List<Rung> out = new ArrayList<>();
        for (Rung rung : rungs) {
            if (openToAll(rung, members)) {
                out.add(rung);
            }
        }
        out.sort(ORDER);
        return List.copyOf(out);
    }

    /**
     * The normal rung the party opens next, or null when every one is open to it: the first in {@link #ORDER} that is
     * not open to every member and whose {@code Opens} the party can go and clear now (a dungeon outside the ladder,
     * or a rung open to all of them); else the first that is not open at all.
     */
    @Nullable
    public static Rung next(@Nonnull Collection<Rung> rungs, @Nonnull Collection<Predicate<String>> members) {
        List<Rung> closed = new ArrayList<>();
        for (Rung rung : rungs) {
            if (!rung.daily() && !openToAll(rung, members)) {
                closed.add(rung);
            }
        }
        closed.sort(ORDER);
        for (Rung rung : closed) {
            Rung opener = rungOf(rungs, rung.opens());
            if (opener == null || openToAll(opener, members)) {
                return rung;
            }
        }
        return closed.isEmpty() ? null : closed.get(0);
    }

    /** The rung of {@code rungs} whose id is {@code id} (matched without regard to case), or null. */
    @Nullable
    private static Rung rungOf(@Nonnull Collection<Rung> rungs, @Nullable String id) {
        if (id == null) {
            return null;
        }
        for (Rung rung : rungs) {
            if (rung.id().equalsIgnoreCase(id)) {
                return rung;
            }
        }
        return null;
    }
}
