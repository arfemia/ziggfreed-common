package com.ziggfreed.common.instance.dungeon.gen;

import java.util.List;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.prefab.PrefabRotation;
import com.hypixel.hytale.server.core.prefab.selection.buffer.impl.IPrefabBuffer;
import com.ziggfreed.common.instance.dungeon.layout.Box;
import com.ziggfreed.common.instance.dungeon.layout.Cell;

/**
 * One prefab placement as the painter ({@link PrefabPaint#paint}) reads it, in world blocks: a generated dungeon's
 * room, or a hub district on its lot. The painter writes its base prefab, then each overlay in order at the same anchor
 * and turn, then the spawner children they hold, every write clipped to {@link #box()}; a cell no pass listed takes
 * {@link #fillBlock()} when it names one.
 */
public interface PaintPiece {

    /** What the piece is, for a warning line: its room or district id and the prefab it pastes. */
    @Nonnull
    String label();

    /** Where its prefab anchor stands. */
    @Nonnull
    Cell origin();

    /** Its turn, the engine's own. */
    @Nonnull
    PrefabRotation rotation();

    /** The blocks it may write, both corners in. */
    @Nonnull
    Box box();

    /** The prefab painted first. */
    @Nonnull
    IPrefabBuffer base();

    /** The prefabs painted over the base, in order; empty for none. */
    @Nonnull
    List<IPrefabBuffer> overlays();

    /** The block id its unlisted cells are filled with, or -1 for none. */
    int fillBlock();

    /** Whether its prefabs' containers are kept (else painted as air). */
    boolean keepContainers();
}
