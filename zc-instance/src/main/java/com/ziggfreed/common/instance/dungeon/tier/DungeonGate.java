package com.ziggfreed.common.instance.dungeon.tier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * Whether a way in shows a dungeon's gate page instead of sending the player in: a dungeon whose ladder holds more
 * than one rung ({@link DungeonTiers#ladderOf}: tiers, or a daily beside the normal one) opens its
 * {@link DungeonGatePage}, where the party picks; any other goes straight in as before. The block's
 * {@code ZigEnterDungeon} and the {@code Dungeon} destination both ask here first.
 */
public final class DungeonGate {

    private DungeonGate() {
    }

    /** Whether {@code dungeonId} has a choice to offer: more than one rung on its ladder. */
    public static boolean offers(@Nullable String dungeonId) {
        return dungeonId != null && !dungeonId.isBlank() && DungeonTiers.ladderOf(dungeonId).size() > 1;
    }

    /**
     * Open {@code dungeonId}'s gate for the player at {@code ref} on a task of {@code world} (their world) of its own,
     * since a way in may run inside a system, where a page cannot open. Never throws.
     */
    public static void openSoon(@Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef player, @Nonnull World world,
            @Nonnull String dungeonId) {
        try {
            world.execute(() -> {
                try {
                    if (ref.isValid()) {
                        DungeonGatePage.open(ref, ref.getStore(), player, dungeonId);
                    }
                } catch (Throwable t) {
                    SafeLog.warn("[dungeon] the gate of '" + dungeonId + "' could not open for " + player.getUuid(), t);
                }
            });
        } catch (Throwable t) {
            SafeLog.warn("[dungeon] the gate of '" + dungeonId + "' could not be queued for " + player.getUuid(), t);
        }
    }
}
