package com.ziggfreed.common.sound;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.component.Store;
import com.ziggfreed.common.ZiggfreedCommonPlugin;

/**
 * Plays the sound a block authors on one of its interaction STATES at the block's position.
 *
 * <p>The engine fires a state's {@code InteractionSoundEventId} (the ignite/click "whoosh") only when
 * the state is entered through a player {@code Use} interaction. A mod that flips a block state
 * SERVER-SIDE (via {@code World.setBlockInteractionState}) gets the state's visuals and looping
 * {@code AmbientSoundEventId}, but NOT the one-shot interaction sound. This helper closes that gap
 * generically: it reads the authored {@code InteractionSoundEventId} (or {@code AmbientSoundEventId})
 * off the named state's {@link BlockType} and plays it at the block centre via {@link Sound3D}.
 *
 * <p>The sound id therefore stays authored in the block's asset JSON (one authority); the caller only
 * names the state it just set. Mod-agnostic: any block whose state definition carries the sound works.
 *
 * <p><b>World-thread only</b> (block + asset reads); every entry point is try-guarded so a missing
 * block / state / sound degrades to a no-op rather than throwing into the caller.
 */
public final class BlockStateSound {

    private BlockStateSound() {
    }

    /**
     * Play the {@code InteractionSoundEventId} authored on {@code stateName} of the block at
     * {@code (x, y, z)}, at the block centre, to all listeners in range. No-op if the block, state,
     * or sound id is absent. Call right after {@code World.setBlockInteractionState(...)}.
     *
     * @param stateName    the state definition whose {@code InteractionSoundEventId} to play (the same
     *                     name passed to {@code setBlockInteractionState})
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
            BlockType base = world.getBlockType(x, y, z);
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

    private static void log(@Nonnull String contextLabel, @Nullable Throwable t) {
        ZiggfreedCommonPlugin.LOGGER.atFine().log(
                contextLabel + " block-state sound failed: " + (t == null ? "?" : t.getMessage()));
    }
}
