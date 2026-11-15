package com.ziggfreed.common.entity;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * Who counts as a player's ally, the one rule every module asks (a proc's "allies near", anything that reaches a
 * group): a member of the same party when the player is in one; with no party, any other player while the world's
 * PvP is off; with PvP on and no party, nobody. The engine has no team, party or friend, so the party half is a seam
 * ({@link Groups}): zc-instance fills it at setup from the library's own party, and a consumer with groups of its own
 * installs its answer in their place. Unfilled (a unit test, a jar built without zc-instance), every player reads as
 * in no party, which the seam says once.
 */
public final class Allies {

    /** The party half of the rule: which group a player is in. */
    @FunctionalInterface
    public interface Groups {

        /**
         * Is {@code other} in {@code player}'s group? Null when {@code player} is in no group at all. Any thread; a
         * cheap read.
         */
        @Nullable
        Boolean sameGroup(@Nonnull UUID player, @Nonnull UUID other);
    }

    @Nullable
    private static volatile Groups groups;

    private static final AtomicBoolean FALLBACK_SAID = new AtomicBoolean();

    private Allies() {
    }

    /** Install the party half of the rule (zc-instance at setup; a consumer with its own groups after it). */
    public static void installGroups(@Nonnull Groups source) {
        groups = source;
    }

    /**
     * Is {@code other} an ally of {@code player}, in a world whose PvP is {@code pvpOn}? Never oneself. Never
     * throws: a group read that fails reads as no group.
     */
    public static boolean areAllies(@Nonnull UUID player, @Nonnull UUID other, boolean pvpOn) {
        if (player.equals(other)) {
            return false;
        }
        Boolean same = null;
        Groups source = groups;
        if (source == null) {
            if (FALLBACK_SAID.compareAndSet(false, true)) {
                SafeLog.info("[allies] no party source is installed, so a player's allies are the other players "
                        + "in a world with PvP off");
            }
        } else {
            try {
                same = source.sameGroup(player, other);
            } catch (Throwable t) {
                SafeLog.fine("[allies] the party read failed: " + t.getMessage());
            }
        }
        return decide(same, pvpOn);
    }

    /**
     * Is PvP on in {@code store}'s world, the second half of {@link #areAllies}? Read as on when it cannot be read, so
     * no stranger becomes an ally. World thread.
     */
    public static boolean pvpOn(@Nonnull Store<EntityStore> store) {
        return pvpOn((ComponentAccessor<EntityStore>) store);
    }

    /**
     * {@link #pvpOn(Store)} through any accessor of the world's entities (a system's command buffer). Read as on when
     * it cannot be read. World thread.
     */
    public static boolean pvpOn(@Nonnull ComponentAccessor<EntityStore> accessor) {
        try {
            World world = accessor.getExternalData().getWorld();
            return world == null || world.getWorldConfig().isPvpEnabled();
        } catch (Throwable t) {
            return true;
        }
    }

    /** The rule itself: a group answer wins whole; with no group, every other player is an ally while PvP is off. */
    static boolean decide(@Nullable Boolean sameGroup, boolean pvpOn) {
        return sameGroup != null ? sameGroup : !pvpOn;
    }

    /** Put the seam back unfilled (a test). */
    static void resetForTests() {
        groups = null;
        FALLBACK_SAID.set(false);
    }
}
