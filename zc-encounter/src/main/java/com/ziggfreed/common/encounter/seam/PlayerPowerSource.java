package com.ziggfreed.common.encounter.seam;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.encounter.power.PlayerPowerConfig;

/**
 * One player's power: the single number a mod that scales the world to its players reads (how hard a region
 * hardens, which danger word a HUD shows, how far a kill sits above its killer). The library answers it
 * itself, from what any server can read (the player's worn gear, {@code Server/ZiggfreedCommon/PlayerPower}),
 * so a mod reading it works on a server running the library alone. A mod that measures power its own way
 * (levels, talents, a class) fills this seam ({@link EncounterSeams#fillPlayerPowerSource}), and its answer
 * REPLACES the library's reading, never adds to it: a player has one power, read one way.
 *
 * <p>The shape is the encounter power seam's ({@link EncounterPowerSource}): asked on the world thread, it
 * answers null for a player it cannot say anything about. A fill also says the scale its numbers live on
 * ({@link #minPower()}, {@link #maxPower()}), which a reader calibrates against instead of hardcoding a range;
 * a fill that does not say lives on the library reading's own scale.
 */
@FunctionalInterface
public interface PlayerPowerSource {

    /**
     * The player's power on this source's scale, or null when it cannot say (no record for the player, a ref
     * that is no player). World thread.
     */
    @Nullable
    Double powerOf(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> playerRef);

    /** The floor of this source's scale: what a player with nothing reads. The library reading's, unless overridden. */
    default double minPower() {
        return PlayerPowerConfig.getInstance().settings().minPower();
    }

    /** The ceiling of this source's scale. The library reading's, unless overridden. */
    default double maxPower() {
        return PlayerPowerConfig.getInstance().settings().maxPower();
    }

    /**
     * Who answers, in plain words for a log line and an owner's page ({@code YourMod}); the class name unless
     * overridden.
     */
    @Nonnull
    default String owner() {
        return getClass().getSimpleName();
    }
}
