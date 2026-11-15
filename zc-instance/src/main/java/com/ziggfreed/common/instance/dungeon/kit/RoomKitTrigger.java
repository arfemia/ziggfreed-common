package com.ziggfreed.common.instance.dungeon.kit;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerContext;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.instance.dungeon.DungeonRuns;

/**
 * What every room-kit effect asks of the trigger that fired it, in one place: who stepped in, which dungeon run the
 * volume acts for, and the placement the effect counts under. Run on the world thread the volume ticks on; nothing
 * here throws on a missing piece, it answers null instead.
 */
final class RoomKitTrigger {

    private RoomKitTrigger() {
    }

    /** The player who stepped in, by uuid; null when the volume fired for no player (a signal, a mob). */
    @Nullable
    static UUID playerOf(@Nonnull TriggerContext context) {
        Ref<EntityStore> ref = context.getEntityRef();
        if (ref == null || !ref.isValid()) {
            return null;
        }
        PlayerRef player = context.getStore().getComponent(ref, PlayerRef.getComponentType());
        return player == null ? null : player.getUuid();
    }

    /**
     * The run the volume acts for, while it is going: {@code player}'s area's run, or with no player the run of the
     * area the volume stands in ({@link DungeonRuns#liveRun}). Null in a world that is no dungeon copy, for a player
     * in no area, or once the run is over.
     */
    @Nullable
    static DungeonRuns.LiveRun runOf(@Nonnull TriggerContext context, @Nullable UUID player) {
        World world = context.getStore().getExternalData().getWorld();
        if (world == null) {
            return null;
        }
        return DungeonRuns.shared().liveRun(player, world.getWorldConfig().getUuid(),
                context.getVolume().getPosition());
    }

    /**
     * What the effect counts under: {@code authored}, trimmed, when written; else the volume's own id, which the
     * engine mints afresh for every paste, so each room of a run counts on its own.
     */
    @Nonnull
    static String placement(@Nonnull TriggerContext context, @Nullable String authored) {
        if (authored != null && !authored.isBlank()) {
            return authored.trim();
        }
        String id = context.getVolume().getId();
        return id == null ? "" : id;
    }
}
