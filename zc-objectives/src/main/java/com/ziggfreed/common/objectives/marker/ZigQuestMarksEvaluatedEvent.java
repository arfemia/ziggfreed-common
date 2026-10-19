package com.ziggfreed.common.objectives.marker;

import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.event.IEvent;

/**
 * One viewer's quest marks were just recomputed, carrying exactly the {@link QuestMarkerScope} a surface
 * draws from: the library's own surfaces (the marks over characters' heads, the compass and map) listen
 * for it, and a mod draws a surface of its own (a map pin, a compass, a list) from the same answer,
 * directly or through {@link QuestMarkers#registerSurface}.
 *
 * <p>Synchronous {@code IEvent<Void>} POJO on the shared engine event bus, fired by
 * {@link QuestMarkers} for one viewer with a quest subject (after any surface added through its
 * deprecated {@code addListener}): on each of the six quest events, at player ready and on the
 * {@value QuestMarkers#SWEEP_SECONDS} s sweep. Built only when somebody is listening.
 *
 * <p>Fired on the world thread. Everything in the scope is valid only synchronously during dispatch:
 * read through {@code scope().store()}, and spawn or write through {@code scope().accessor()}, which
 * during the sweep is the tick's command buffer. A viewer who leaves is the engine's own
 * {@code PlayerDisconnectEvent}, where a surface drops what it held for them.
 */
public final class ZigQuestMarksEvaluatedEvent implements IEvent<Void> {

    private final QuestMarkerScope scope;

    public ZigQuestMarksEvaluatedEvent(@Nonnull QuestMarkerScope scope) {
        this.scope = scope;
    }

    /** The evaluation: store, accessor, world, viewer, their quest subject and the placed characters. */
    @Nonnull
    public QuestMarkerScope scope() {
        return scope;
    }

    /** Whose marks these are. */
    @Nonnull
    public UUID viewerId() {
        return scope.viewerId();
    }
}
