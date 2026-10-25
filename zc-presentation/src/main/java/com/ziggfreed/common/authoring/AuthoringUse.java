package com.ziggfreed.common.authoring;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * One admin opening the authoring kit: the player (their entity, store and connection) and what their tool pointed
 * at, {@link WorldPick#NOTHING} when no tool was used (an admin command, a consumer's page). Handed to a tool's
 * {@link AuthoringTools.Opener} and a wizard's {@link AuthoringWizards.Factory} on the player's world thread,
 * outside every system's tick.
 */
public record AuthoringUse(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
        @Nonnull PlayerRef playerRef, @Nonnull Player player, @Nonnull WorldPick pick) {

    /**
     * The use of the player at {@code ref} with {@code pick}, or null when {@code ref} is no live player. World
     * thread.
     */
    @Nullable
    public static AuthoringUse of(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nullable WorldPick pick) {
        try {
            if (!ref.isValid()) {
                return null;
            }
            Player player = store.getComponent(ref, Player.getComponentType());
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            return player == null || playerRef == null ? null
                    : new AuthoringUse(ref, store, playerRef, player, pick == null ? WorldPick.NOTHING : pick);
        } catch (Throwable t) {
            return null;
        }
    }
}
