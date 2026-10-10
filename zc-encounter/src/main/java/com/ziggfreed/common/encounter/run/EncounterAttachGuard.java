package com.ziggfreed.common.encounter.run;

import javax.annotation.Nonnull;

/**
 * Whether an encounter entity arriving in a store carries a run: the one decision the attach system
 * makes, pure so it is pinned without a server.
 *
 * <p>The engine runs many things as encounter scripts that are not boss fights: a hunt site's bait,
 * an elite's phase swap, a world event's waves, a crystal. A script is a fight this library counts
 * only on its own word or an author's: it puts up a boss bar ({@code SetEncounterBossBar}), or a
 * binding row names it. Anything else opens no run, so it is never engaged, wiped, scaled, paid or
 * listed; a rule row ({@code EncounterRules}) can still credit the kill of the creature it names.
 *
 * <p>A run this library's spawner attached is always kept, and renewed when its entity comes back
 * (a builder reload re-adds it): whoever spawned it here asked for one, whatever its script says.
 */
public final class EncounterAttachGuard {

    /** What the attach does with the arriving entity. */
    public enum Decision {
        /** No run yet, and the script counts: attach a fresh one. */
        OPEN,
        /** It carries a run that ended (a re-add), and it still counts: attach that run's copy. */
        RENEW,
        /** It carries a live run already (the spawner's): leave it. */
        KEEP,
        /** The script does not count: no run. */
        SKIP
    }

    private EncounterAttachGuard() {
    }

    /**
     * @param hasRun      whether the entity arrives carrying a run
     * @param runEnded    whether that run has ended (meaningless without one)
     * @param spawned     whether that run was stamped by this library's spawner
     * @param bound       whether a binding row names the script
     * @param setsBossBar whether the script puts up a boss bar, false when it cannot be read
     */
    @Nonnull
    public static Decision decide(boolean hasRun, boolean runEnded, boolean spawned, boolean bound,
            boolean setsBossBar) {
        if (hasRun && !runEnded) {
            return Decision.KEEP;
        }
        boolean counts = bound || setsBossBar;
        if (hasRun) {
            return counts || spawned ? Decision.RENEW : Decision.SKIP;
        }
        return counts ? Decision.OPEN : Decision.SKIP;
    }
}
