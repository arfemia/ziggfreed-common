package com.ziggfreed.common.instance.dungeon.gen;

import java.nio.file.Path;
import java.util.List;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;

/**
 * What one world generator hands the painter ({@link PrefabPaint#paint}) for a chunk: the pieces it paints, the seed
 * every spawner pick and child pick is made from (so every chunk a piece crosses makes the same pick), and the cache
 * its spawner children load into. A generated dungeon's run is one ({@code DungeonRun}), a hub copy's district plan
 * another. Safe to read from any painter thread.
 */
public interface PaintJob {

    /** The seed every pick of this job is made from. */
    long seed();

    /** The pieces to paint, in order. */
    @Nonnull
    List<? extends PaintPiece> pieces();

    /** The spawner child prefab at {@code prefab}, loaded once for the job and shared by every chunk it crosses. */
    @Nonnull
    IPrefabBuffer child(@Nonnull Path prefab);

    /** What the job is, for a warning line. */
    @Nonnull
    String describe();

    /** Count one spawner child the painter expanded. */
    default void childExpanded() {
    }

    /** Count {@code count} prefab entities the painter left for someone else to place. */
    default void entitiesLeft(int count) {
    }
}
