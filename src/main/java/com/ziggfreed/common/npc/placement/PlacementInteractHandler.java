package com.ziggfreed.common.npc.placement;

import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * What a mod implements to make pressing F on a placed NPC DO something in its own domain.
 *
 * <p>Register one per namespace with {@link NpcPlacementBindings#register}; the generic
 * {@link ActionPlacementInteract} NPC action then hands you every binding the placement authored
 * on YOUR namespace, in one call, so a handler that needs two channels together (which page to
 * open AND which NPC identity to credit) reads both without a second lookup.
 *
 * <p>The handler runs on the world thread, inside the interaction. It must not throw: the
 * dispatcher guards it, but a throwing handler still loses its own effect.
 */
@FunctionalInterface
public interface PlacementInteractHandler {

    /**
     * What the handler is given. Field-additive: a new component here never breaks an existing
     * implementer, unlike widening a functional-interface signature.
     *
     * @param placementId the placement the NPC belongs to (lower-cased)
     * @param namespace   the namespace this handler registered for
     * @param bindings    every binding on that namespace, keyed by the FULL channel id
     *                    ({@code "yourmod:ui_target"}), so a handler can switch on the channel
     *                    without re-deriving it
     * @param npcRef      the NPC that was interacted with
     * @param playerRef   the player who pressed F
     * @param store       the live entity store
     */
    record InteractContext(@Nonnull String placementId,
                           @Nonnull String namespace,
                           @Nonnull Map<String, PlacementBinding> bindings,
                           @Nonnull Ref<EntityStore> npcRef,
                           @Nonnull Ref<EntityStore> playerRef,
                           @Nonnull Store<EntityStore> store) {

        /** The {@code Value} of one channel, or {@code null} when the placement did not author it. */
        @Nullable
        public String value(@Nonnull String channelId) {
            PlacementBinding b = bindings.get(channelId);
            return b == null ? null : b.getValue();
        }
    }

    /** Handle a press-F on a placed NPC. Runs on the world thread; must not throw. */
    void onInteract(@Nonnull InteractContext ctx);
}
