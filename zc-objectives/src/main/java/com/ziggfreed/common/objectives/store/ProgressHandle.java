package com.ziggfreed.common.objectives.store;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.subject.Subject;

/**
 * What the standalone runtime attaches to a {@link Subject}: enough to find the player's live
 * entity, and therefore their {@link ZigProgressComponent}.
 *
 * <p>Read it back with {@code subject.handleAs(ProgressHandle.class)}. A subject built somewhere
 * else - a unit test driving the state machine, a maintenance pass with no world - carries no
 * handle, and every site that needs one degrades to doing nothing this pass rather than throwing.
 *
 * <p><b>It also answers for the live {@link Player}</b>, through {@link Subject.HandleFacets}. That
 * is not decoration: every ready-made reward handler in this library finds its player with
 * {@code subject.handleAs(Player.class)}, so a handle that answered only for itself would leave a
 * collected quest paying out nothing at all while still reporting success. A rich handle has to say
 * what it can stand in for.
 */
public record ProgressHandle(@Nonnull Store<EntityStore> store,
                             @Nonnull Ref<EntityStore> ref,
                             @Nonnull PlayerRef playerRef) implements Subject.HandleFacets {

    /** This player's progress component, or null when there is none to read. */
    @Nullable
    public ZigProgressComponent component() {
        if (ZigProgressComponent.TYPE == null || !ref.isValid()) {
            return null;
        }
        return store.getComponent(ref, ZigProgressComponent.TYPE);
    }

    /** This player's live entity, or null when the ref no longer resolves one. */
    @Nullable
    public Player player() {
        return ref.isValid() ? store.getComponent(ref, Player.getComponentType()) : null;
    }

    @Override
    @Nullable
    public Object facet(@Nonnull Class<?> type) {
        if (type.isAssignableFrom(Player.class)) {
            return player();
        }
        return type.isAssignableFrom(PlayerRef.class) ? playerRef : null;
    }

    /**
     * The foreign types this handle stands in for: the live {@link Player} and the
     * {@link PlayerRef}, and nothing else. The second is what lets the library's ready-made
     * permission probe - which expects the handle to BE a {@code PlayerRef} - work over a subject
     * carrying this richer handle instead. Everything else a reader might ask for is either the
     * handle itself (answered by the direct cast before this is ever consulted) or genuinely not
     * ours to answer.
     */
    static boolean answersFor(@Nonnull Class<?> type) {
        return type.isAssignableFrom(Player.class) || type.isAssignableFrom(PlayerRef.class);
    }
}
