package com.ziggfreed.common.ui.route;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Everything a destination handler is told about the moment it was asked to open something.
 *
 * <p>The four player handles are always present, because there is no destination without somebody to
 * show it to. Everything else is an INDEPENDENT nullable leaf, so one context shape serves a press-F
 * at an NPC, a conversation line, a block, a command and a button on a page without any of them
 * pretending to carry what it has not got:
 *
 * <ul>
 *   <li>{@code npcRef} - the entity the player is standing at, when there is one. It is also what a
 *       page is opened ON ({@link #pageAnchor()}), matching how the engine's own NPC pages behave.</li>
 *   <li>{@code npcId} - WHO that character is, already resolved by whoever knew (a placement knows
 *       exactly who stands there). A handler that opens a conversation or a per-character screen
 *       reads this rather than resolving an identity of its own, which is what keeps one character's
 *       id from being spelled twice.</li>
 *   <li>{@code placementId} - which placement the moment came from, for a handler that keeps
 *       per-placement state.</li>
 *   <li>{@code depsKey} - which registered UI-provider a handler that has several should use; null
 *       means its default.</li>
 * </ul>
 *
 * <p>The handles are LIVE and valid only for the duration of the {@code open} call. World thread.
 */
public record DestinationContext(@Nonnull Store<EntityStore> store,
                                 @Nonnull Ref<EntityStore> playerReference,
                                 @Nonnull PlayerRef playerRef,
                                 @Nonnull Player player,
                                 @Nullable Ref<EntityStore> npcRef,
                                 @Nullable String npcId,
                                 @Nullable String placementId,
                                 @Nullable String depsKey) {

    /** The player-only form: no character in front of them, no placement, no provider key. */
    @Nonnull
    public static DestinationContext of(@Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> playerReference, @Nonnull PlayerRef playerRef, @Nonnull Player player) {
        return new DestinationContext(store, playerReference, playerRef, player, null, null, null, null);
    }

    /** A copy that also names the character the player is standing at, and where it stands. */
    @Nonnull
    public DestinationContext withNpc(@Nullable Ref<EntityStore> npcRef, @Nullable String npcId,
            @Nullable String placementId) {
        return new DestinationContext(store, playerReference, playerRef, player, npcRef, npcId, placementId, depsKey);
    }

    /** A copy that also names which registered UI-provider a handler should resolve through. */
    @Nonnull
    public DestinationContext withDepsKey(@Nullable String depsKey) {
        return new DestinationContext(store, playerReference, playerRef, player, npcRef, npcId, placementId, depsKey);
    }

    /**
     * The ref a page should be opened on: the character being talked to when there is one, otherwise
     * the player themselves. A page reads its own state off the player it holds, so this only decides
     * which entity the page is anchored to.
     */
    @Nonnull
    public Ref<EntityStore> pageAnchor() {
        return npcRef != null ? npcRef : playerReference;
    }

    /** True when a character is named, so a per-character screen has somebody to be about. */
    public boolean hasNpc() {
        return npcId != null && !npcId.isBlank();
    }
}
