package com.ziggfreed.common.sound;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.component.Store;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.world.SectionBlockCursor;

/**
 * Plays the sound a block authors on one of its interaction STATES at the block's position.
 *
 * <p>The engine fires a state's {@code InteractionSoundEventId} (the ignite/click "whoosh") only when
 * the state is entered through a player {@code Use} interaction. A mod that flips a block state
 * SERVER-SIDE (through {@code BlockOperations.setBlockInteractionState}, or zc-world's
 * {@code BlockOps.setInteractionState}) gets the state's visuals and looping
 * {@code AmbientSoundEventId}, but NOT the one-shot interaction sound. This helper closes that gap
 * generically: it reads the authored {@code InteractionSoundEventId} (or {@code AmbientSoundEventId})
 * off the named state's {@link BlockType} and plays it at the block centre via {@link Sound3D}.
 *
 * <p>The sound id therefore stays authored in the block's asset JSON (one authority); the caller only
 * names the state it just set. Mod-agnostic: any block whose state definition carries the sound works.
 *
 * <p><b>World-thread only</b> (block + asset reads). The block is read off its loaded chunk section
 * through zc-core's {@link SectionBlockCursor} (the read both the live server and Update 7 keep), which
 * never loads a chunk, so a block whose section is not in memory plays nothing. Every entry point is
 * try-guarded and logs through {@code SafeLog}, so a missing block / state / sound degrades to a no-op
 * rather than throwing into the caller.
 */
public final class BlockStateSound {

    private BlockStateSound() {
    }

    /**
     * Play the {@code InteractionSoundEventId} authored on {@code stateName} of the block at
     * {@code (x, y, z)}, at the block centre, to all listeners in range. No-op if the block, state,
     * or sound id is absent. Call right after flipping the state ({@code BlockOps.setInteractionState}
     * or {@code BlockOperations.setBlockInteractionState}).
     *
     * @param stateName    the state definition whose {@code InteractionSoundEventId} to play (the same
     *                     name the state flip used)
     * @param contextLabel short label prefixed to any FINE log line
     */
    public static void playInteractionSound(@Nonnull World world, int x, int y, int z,
                                            @Nonnull String stateName, @Nonnull SoundCategory category,
                                            @Nonnull Store<EntityStore> store, @Nonnull String contextLabel) {
        play(world, x, y, z, stateName, category, store, contextLabel, true);
    }

    /**
     * As {@link #playInteractionSound} but plays the state's {@code AmbientSoundEventId} once (a one-shot
     * of the loop sound) - useful when a block has no distinct interaction sound. No-op if absent.
     */
    public static void playAmbientSound(@Nonnull World world, int x, int y, int z,
                                        @Nonnull String stateName, @Nonnull SoundCategory category,
                                        @Nonnull Store<EntityStore> store, @Nonnull String contextLabel) {
        play(world, x, y, z, stateName, category, store, contextLabel, false);
    }

    private static void play(@Nonnull World world, int x, int y, int z, @Nonnull String stateName,
                             @Nonnull SoundCategory category, @Nonnull Store<EntityStore> store,
                             @Nonnull String contextLabel, boolean interaction) {
        try {
            int blockId = blockIdAt(world, x, y, z);
            if (blockId == BlockType.EMPTY_ID) {
                return;
            }
            BlockType base = BlockType.getAssetMap().getAsset(blockId);
            if (base == null || base.getData() == null) {
                return;
            }
            BlockType state = base.getBlockForState(stateName);
            if (state == null) {
                return;
            }
            String soundId = interaction ? state.getInteractionSoundEventId() : state.getAmbientSoundEventId();
            if (soundId == null || soundId.isEmpty()) {
                return;
            }
            // Block centre: world block coords are the block's min corner.
            Sound3D.play(soundId, category, x + 0.5, y + 0.5, z + 0.5,
                    ref -> true, store, contextLabel, false);
        } catch (Throwable t) {
            log(contextLabel, t);
        }
    }

    /**
     * The block id at one cell, read off its loaded chunk section: {@code BlockType.EMPTY_ID} for air
     * and for a section not in memory. The read uses the world's own chunk store, never the entity
     * {@code store} the sound plays through.
     */
    private static int blockIdAt(@Nonnull World world, int x, int y, int z) {
        return SectionBlockCursor.of(world).blockId(x, y, z);
    }

    private static void log(@Nonnull String contextLabel, @Nullable Throwable t) {
        SafeLog.fine(contextLabel + " block-state sound failed: " + (t == null ? "?" : t.getMessage()));
    }
}
