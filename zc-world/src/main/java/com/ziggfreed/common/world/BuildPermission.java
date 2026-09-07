package com.ziggfreed.common.world;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.asset.type.environment.config.Environment;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.BlockChunk;
import com.hypixel.hytale.server.core.universe.world.chunk.section.ChunkSection;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * May a block go down here? The engine's OWN two refusals, asked early enough to be useful.
 *
 * <p><b>Why anyone needs this.</b> The native place-block event is dispatched BEFORE the engine
 * decides whether the placement is allowed at all: a world whose gameplay config turns building off
 * (the Forgotten Temple and the creative hub both do) and a protected environment a non-creative
 * player may not modify are both checked afterwards, and a refused placement then returns the item
 * to the hand and leaves the world untouched. Nothing tells the listeners of that event, so anything
 * that pays out per placement pays out for a placement that never happened, as often as the player
 * cares to click. Asking here answers the same two questions the engine is about to ask.
 *
 * <p><b>What it deliberately does NOT answer.</b> Whether the block can physically go in that cell
 * (support, replaceability, its own prefab), which is the engine's placement validation and reads
 * far more state than a permission question. This is about permission: is building allowed in this
 * world, and is it allowed at this spot.
 *
 * <p><b>Cannot tell reads as allowed.</b> An unresolved world, an unloaded section or any engine
 * throw leaves the answer to the engine rather than refusing on a guess: a reader that refuses on
 * an unreadable edge quietly takes away the credit for ordinary building, which is a worse failure
 * than the one it is guarding. The world-permission half is a plain asset field, so the case that
 * matters most is also the one that cannot fail to be read.
 *
 * <p><b>World-thread only</b> (resolves section refs and components on the owning world's store);
 * the caller guarantees the thread.
 */
public final class BuildPermission {

    private BuildPermission() {
    }

    /**
     * Will this world let a placer in this game mode put a block at this position?
     *
     * @param world    the world the placement is happening in, or null when it cannot be resolved
     * @param gameMode the placer's game mode, or null when it could not be read (which is read the
     *                 way the engine reads it: as an ordinary, non-creative placer)
     */
    public static boolean allowsPlacement(@Nullable World world, @Nullable GameMode gameMode,
            int x, int y, int z) {
        if (world == null) {
            return true;
        }
        try {
            boolean worldAllows = world.getGameplayConfig().getWorldConfig().isBlockPlacementAllowed();
            // A world that refuses building refuses it for everybody, and a creative placer is
            // never held to the environment, so both cases are settled without a chunk read.
            boolean asksEnvironment = worldAllows && gameMode != GameMode.Creative;
            boolean environmentAllows = !asksEnvironment
                    || environmentAllowsModification(world, x, y, z);
            return allowsPlacement(worldAllows, gameMode, environmentAllows);
        } catch (Throwable t) {
            SafeLog.fine("[build] could not tell whether building is allowed at (" + x + ", " + y
                    + ", " + z + "); leaving the answer to the engine: " + t.getMessage());
            return true;
        }
    }

    /**
     * The pure decision the live read feeds: the world's own building permission, then the
     * environment's, which the engine only holds a NON-creative placer to.
     *
     * @param worldAllowsPlacement          the world gameplay config's block-placement permission
     * @param gameMode                      the placer's game mode, null reading as non-creative
     * @param environmentAllowsModification whether the environment at the spot allows block
     *                                      modification (true when there is none, or none could be
     *                                      read)
     */
    public static boolean allowsPlacement(boolean worldAllowsPlacement, @Nullable GameMode gameMode,
            boolean environmentAllowsModification) {
        if (!worldAllowsPlacement) {
            return false;
        }
        return gameMode == GameMode.Creative || environmentAllowsModification;
    }

    /**
     * Does the environment covering this position allow blocks to be modified? True when no
     * environment is set there, and true when it cannot be told: see the class javadoc.
     */
    public static boolean environmentAllowsModification(@Nullable World world, int x, int y, int z) {
        if (world == null) {
            return true;
        }
        try {
            ChunkStore chunkStore = world.getChunkStore();
            if (chunkStore == null) {
                return true;
            }
            Ref<ChunkStore> sectionRef = chunkStore.getChunkSectionReferenceAtBlock(x, y, z);
            if (sectionRef == null || !sectionRef.isValid()) {
                return true;
            }
            Store<ChunkStore> store = chunkStore.getStore();
            ChunkSection section = store.getComponent(sectionRef, ChunkSection.getComponentType());
            if (section == null) {
                return true;
            }
            // The environment is stored per COLUMN, so the section only points the way to it.
            Ref<ChunkStore> columnRef = section.getChunkColumnReference();
            if (columnRef == null || !columnRef.isValid()) {
                return true;
            }
            BlockChunk column = store.getComponent(columnRef, BlockChunk.getComponentType());
            if (column == null) {
                return true;
            }
            Environment environment = Environment.getAssetMap().getAsset(column.getEnvironment(x, y, z));
            return environment == null || environment.isBlockModificationAllowed();
        } catch (Throwable t) {
            SafeLog.fine("[build] could not read the environment at (" + x + ", " + y + ", " + z
                    + "): " + t.getMessage());
            return true;
        }
    }
}
