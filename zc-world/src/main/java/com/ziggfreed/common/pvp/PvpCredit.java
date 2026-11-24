package com.ziggfreed.common.pvp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * The PvP ledger's credit rules, pure: who a player's death credits, with what. Read once at the death
 * ({@link PvpLedger}); every engine read is made before and handed in.
 *
 * <ul>
 *   <li><b>The killer</b> is the player whose hit (a blow, a shot, a damage over time they put on) killed the victim;
 *       a death that is no one's (a fall, lava, drowning) within the world's {@code EnvironmentSeconds} of a player's
 *       hit is the last such player's kill. A death a creature dealt, or one with no player's hit near it, credits
 *       nobody.</li>
 *   <li><b>Assists</b>: everyone who hurt the victim within {@code AssistSeconds}, the killer included, each with
 *       their share of the player damage dealt in that time; an aura's owner (an effect the killer wears, put on by a
 *       player, tagged {@code ZigPvpAssist}) shares in it at the tag's share when that is more than their own.</li>
 *   <li><b>What credits nothing</b>: an idle victim, a victim killed within {@code FreshSpawnSeconds} of coming back,
 *       the victim themself, and the victim's allies (their party), who are left out one by one. Each player still in
 *       is weighed once by the repeat guard ({@link Weigher}: the same player killed again too soon), and one weighed
 *       at 0 is left out whole.</li>
 * </ul>
 */
public final class PvpCredit {

    private PvpCredit() {
    }

    /** Why a death credits nobody at all. */
    public enum Refusal {
        /** It credits someone. */
        NONE,
        /** The victim had gone idle. */
        IDLE,
        /** The victim had only just come back from death. */
        FRESH_SPAWN,
        /** No player's hit killed them, or every candidate was left out. */
        NO_CREDIT
    }

    /** Is one player an ally of another (their party), in a world whose PvP is on? */
    @FunctionalInterface
    public interface Allies {

        boolean areAllies(@Nonnull UUID player, @Nonnull UUID other);
    }

    /**
     * The repeat guard: what a credit to {@code player} for this victim counts for, from 1 (in full) to 0 (nothing).
     * Asked once per player still in, so a counting guard counts each death once per player.
     */
    @FunctionalInterface
    public interface Weigher {

        double weigh(@Nonnull UUID player);
    }

    /**
     * What the death hands the rules.
     *
     * @param victim              the player who died
     * @param directKiller        the player whose hit killed them, or null
     * @param noOnesDeath         the death had no entity behind it (a fall, lava, drowning)
     * @param shares              each player's share of the damage within the assist window ({@link PvpTally#shares})
     * @param lastHitter          the player who struck last within the environment window, or null
     * @param auraShares          each aura owner's share for the killer's kill (their {@code ZigPvpAssist} tag)
     * @param victimIdle          the victim was idle
     * @param victimFresh         the victim had just come back from death
     */
    public record Input(@Nonnull UUID victim, @Nullable UUID directKiller, boolean noOnesDeath,
                        @Nonnull Map<UUID, Double> shares, @Nullable UUID lastHitter,
                        @Nonnull Map<UUID, Double> auraShares, boolean victimIdle, boolean victimFresh) {
    }

    /**
     * One player's credit for the death: the killing blow or not, their share of it (0 to 1), and what the repeat
     * guard counted it for (above 0).
     */
    public record Credit(@Nonnull UUID player, boolean killingBlow, double share, double weight) {
    }

    /** The answer: the killer (null for none), every credit (the killer first), or why there are none. */
    public record Outcome(@Nullable UUID killer, @Nonnull List<Credit> credits, @Nonnull Refusal refusal) {

        static Outcome refused(@Nonnull Refusal refusal, @Nullable UUID killer) {
            return new Outcome(killer, List.of(), refusal);
        }

        /** The killer's credit, or null when the killing blow credits nobody. */
        @Nullable
        public Credit killCredit() {
            return !credits.isEmpty() && credits.get(0).killingBlow() ? credits.get(0) : null;
        }
    }

    /** The death's killer by the rules, before anyone is left out: the direct killer, else the environment's. Pure. */
    @Nullable
    public static UUID killerOf(@Nonnull Input in) {
        if (in.directKiller() != null) {
            return in.directKiller().equals(in.victim()) ? null : in.directKiller();
        }
        if (in.noOnesDeath() && in.lastHitter() != null && !in.lastHitter().equals(in.victim())) {
            return in.lastHitter();
        }
        return null;
    }

    /** Decide the credits of one death. Pure but for the guard it asks. */
    @Nonnull
    public static Outcome decide(@Nonnull Input in, @Nonnull Allies allies, @Nonnull Weigher weigher) {
        UUID killer = killerOf(in);
        if (killer == null) {
            return Outcome.refused(Refusal.NO_CREDIT, null);
        }
        if (in.victimIdle()) {
            return Outcome.refused(Refusal.IDLE, killer);
        }
        if (in.victimFresh()) {
            return Outcome.refused(Refusal.FRESH_SPAWN, killer);
        }
        Map<UUID, Double> candidates = new LinkedHashMap<>();
        candidates.put(killer, share(in.shares(), killer));
        for (Map.Entry<UUID, Double> entry : in.shares().entrySet()) {
            candidates.merge(entry.getKey(), clean(entry.getValue()), Math::max);
        }
        for (Map.Entry<UUID, Double> entry : in.auraShares().entrySet()) {
            candidates.merge(entry.getKey(), clean(entry.getValue()), Math::max);
        }
        List<Credit> credits = new ArrayList<>();
        for (Map.Entry<UUID, Double> entry : candidates.entrySet()) {
            UUID player = entry.getKey();
            boolean blow = player.equals(killer);
            if (player.equals(in.victim()) || allies.areAllies(in.victim(), player)
                    || (!blow && !(entry.getValue() > 0.0))) {
                continue;
            }
            double weight = clean(weigher.weigh(player));
            if (weight > 0.0) {
                credits.add(new Credit(player, blow, entry.getValue(), Math.min(1.0, weight)));
            }
        }
        return credits.isEmpty() ? Outcome.refused(Refusal.NO_CREDIT, killer)
                : new Outcome(killer, List.copyOf(credits), Refusal.NONE);
    }

    private static double share(@Nonnull Map<UUID, Double> shares, @Nonnull UUID player) {
        Double share = shares.get(player);
        return share == null ? 0.0 : clean(share);
    }

    /** A share or weight held to [0, 1]; 0 for one that is not a number. */
    private static double clean(@Nullable Double value) {
        if (value == null || !Double.isFinite(value) || value <= 0.0) {
            return 0.0;
        }
        return Math.min(1.0, value);
    }
}
