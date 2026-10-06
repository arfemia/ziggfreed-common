package com.ziggfreed.common.reputation;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Effective rank, self-healing. A check reads one reputation's effective rank (earned plus gear) for one
 * player and credits the ranks from the reputation's starting rank (where its InitialReputationValue lands)
 * up to the current one, so a jump never misses a rank in between and a login re-credits whatever an
 * earlier session left out (an achievement ignores a repeat). A player below the starting rank is credited
 * only the ranks reached going down, never the start's own ({@link ReputationLadder#credited}). Only a rise DURING
 * PLAY raises the notice: the last rank seen per player and reputation is remembered here, and the first
 * check after login (nothing remembered yet) is a silent hydrate.
 *
 * <p>Checks come from three places: every change through {@link ReputationService#change}, every equip
 * recompute, and once at login. An equip that moved no rank does nothing at all, since equips are frequent
 * and the ranks it would credit are already credited.
 *
 * <p>The memory is TRANSIENT and single-process: nothing is saved, {@link #forget} drops a player at
 * disconnect, and a restart starts everyone with a hydrate.
 */
public final class ReputationRankWatch {

    /** Where a check came from. */
    public enum Trigger {
        LOGIN, CHANGE, EQUIP
    }

    /** What one check does: credit the ranks held, and whether to announce a rise. */
    public record Decision(boolean credit, boolean rose) {
    }

    /** Player uuid to (lower-cased reputation id to the last rank index seen). */
    private final Map<UUID, Map<String, Integer>> lastSeen = new ConcurrentHashMap<>();

    /** The rule, over the last index seen (null when none since login), the index now and the trigger. */
    @Nonnull
    public static Decision decide(@Nullable Integer previous, int index, @Nonnull Trigger trigger) {
        if (trigger == Trigger.LOGIN || previous == null) {
            return new Decision(true, false);
        }
        boolean moved = previous.intValue() != index;
        return new Decision(trigger == Trigger.CHANGE || moved, index > previous);
    }

    /** Check one reputation for one player at {@code effective} standing, and act on the decision. */
    public void observe(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref, @Nonnull UUID player,
            @Nonnull ReputationDef reputation, long effective, @Nonnull ReputationLadder ladder,
            @Nonnull Trigger trigger, @Nonnull ReputationFanOut out) {
        ReputationLadder.Rank rank = ladder.rankFor(effective);
        if (rank == null) {
            return;
        }
        int index = ladder.indexOf(rank);
        Integer previous = lastSeen.computeIfAbsent(player, key -> new ConcurrentHashMap<>())
                .put(reputation.id().toLowerCase(Locale.ROOT), index);
        Decision decision = decide(previous, index, trigger);
        if (decision.credit()) {
            out.credit(store, ref, player, reputation, ladder.credited(rank, ladder.rankFor(reputation.initial())));
        }
        if (decision.rose()) {
            out.rose(store, ref, reputation, rank);
        }
    }

    /** Drop everything remembered about {@code player}; their next check is a hydrate. */
    public void forget(@Nonnull UUID player) {
        lastSeen.remove(player);
    }
}
