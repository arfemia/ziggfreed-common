package com.ziggfreed.common.world;

import javax.annotation.Nullable;

import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.gameplay.GameplayConfig;
import com.hypixel.hytale.server.core.universe.world.World;
import com.ziggfreed.common.util.SafeLog;

/**
 * May a player break this block here? The breaking twin of {@link BuildPermission}, for a break the engine's own
 * break interaction never asks about: a block a mod breaks on a player's behalf through the engine's break call (a
 * shaped break's extra blocks), which applies no world rule of its own. It asks what the break interaction and the
 * damage path ask before a hand break: the world's breaking permission for the block ({@code World} config,
 * {@code AllowBlockBreaking} and its bypass tag), the environment's block-modification permission at the cell (the
 * damage path holds every player to it), and the library's creative-only breaking rule ({@link BlockRules}). A claim
 * or protection mod refuses at the engine's break event, which the break call fires with the player, so that stays
 * the engine's to ask.
 *
 * <p><b>Cannot tell reads as allowed</b>, as {@link BuildPermission} reads it. World-thread only.
 */
public final class BreakPermission {

    private BreakPermission() {
    }

    /**
     * Will this world let a breaker in this game mode break {@code blockType} at this position?
     *
     * @param world    the world, or null when it cannot be resolved (allowed: the engine still decides)
     * @param gameMode the breaker's game mode, null reading as non-creative
     */
    public static boolean allowsBreaking(@Nullable World world, @Nullable GameMode gameMode,
            @Nullable BlockType blockType, int x, int y, int z) {
        if (world == null) {
            return true;
        }
        try {
            GameplayConfig config = world.getGameplayConfig();
            if (blockType != null && !config.getWorldConfig().isBlockBreakingAllowed(blockType)) {
                return false;
            }
            if (BlockRules.refusesBreaker(BlockRules.creativeOnlyBreaking(config), gameMode)) {
                return false;
            }
            return BuildPermission.environmentAllowsModification(world, x, y, z);
        } catch (Throwable t) {
            SafeLog.fine("[build] could not tell whether breaking is allowed at (" + x + ", " + y + ", " + z
                    + "); leaving the answer to the engine: " + t.getMessage());
            return true;
        }
    }
}
