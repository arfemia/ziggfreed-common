package com.ziggfreed.common.world;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.ziggfreed.common.CommonLog;

/**
 * The guarded read of what worlds this server actually has, in the two axes a {@code Where} scores
 * against: each world's {@link World#getName() name} and its authored
 * {@code WorldConfig.getGameplayConfig()} key.
 *
 * <p>It exists so the describes-a-real-world audit ({@link WhereValidator#validateAgainstWorlds})
 * has one engine-touching source to ask, and so that {@link WhereValidator} itself stays pure and
 * unit-testable with no server anywhere near it.
 *
 * <p>Nothing here is cached. A world's name and gameplay config are two field reads, and an
 * instance world's roster changes as instances come and go, so a cache would only be a way to
 * answer with a world that has already been torn down.
 */
public final class WorldIdentity {

    private WorldIdentity() {
    }

    /**
     * Every world the server currently has loaded, as {@code (name, gameplayConfig)} pairs.
     *
     * <p>An EMPTY list means "cannot tell" and never "nothing is loaded": it is what a unit JVM, a
     * pre-boot call, and a failed read all return, and a caller that treated it as an answer would
     * report a finding against every world-targeted file on the server. Try-guarded throughout.
     *
     * <p><b>World-thread</b> for the underlying world reads.
     */
    @Nonnull
    public static List<WhereValidator.LoadedWorld> loadedWorlds() {
        List<WhereValidator.LoadedWorld> out = new ArrayList<>();
        try {
            Universe universe = Universe.get();
            if (universe == null) {
                return List.of();
            }
            for (World world : universe.getWorlds().values()) {
                if (world != null) {
                    out.add(new WhereValidator.LoadedWorld(world.getName(),
                            world.getWorldConfig().getGameplayConfig()));
                }
            }
        } catch (Throwable t) {
            warn("WorldIdentity could not enumerate the loaded worlds: " + t.getMessage());
            return List.of();
        }
        return out;
    }

    private static void warn(@Nonnull String message) {
        try {
            CommonLog.LOGGER.atWarning().log(message);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM: the flogger LOGGER can throw; swallow it.
        }
    }
}
