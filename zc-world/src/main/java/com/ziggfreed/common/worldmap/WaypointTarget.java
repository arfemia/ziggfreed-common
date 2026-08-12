package com.ziggfreed.common.worldmap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * One place a viewer is being pointed at, as a SOURCE describes it: a stable id, the key whatever
 * knows about positions can look up, what to call it, and optionally its own icon.
 *
 * <p><b>A target names a place, it does not hold coordinates.</b> That separation is the whole point:
 * one target can resolve to several positions (the same character standing in two worlds, two live
 * copies of one dungeon), a position can move without the target changing, and a target for a place
 * that does not exist right now simply produces no marker until it does. Turning a
 * {@link #positionKey()} into coordinates is a {@link WaypointPositionResolver}'s job.
 *
 * @param id          stable, unique within one viewer's set; part of every marker id built from it,
 *                    and how a snapshot dedupes two sources pointing at the same place
 * @param positionKey what the resolver looks up. Its spelling belongs entirely to the pair of
 *                    consumer classes that produce and resolve it; nothing here parses it
 * @param title       hover name, a {@link Message} the viewer's own client resolves in their
 *                    language, or null for an unnamed marker
 * @param icon        client map-marker texture id for this target, or null to take the service's
 *                    default
 */
public record WaypointTarget(@Nonnull String id, @Nonnull String positionKey,
                             @Nullable Message title, @Nullable String icon) {

    /** A target taking the service's default icon. */
    @Nonnull
    public static WaypointTarget of(@Nonnull String id, @Nonnull String positionKey,
                                    @Nullable Message title) {
        return new WaypointTarget(id, positionKey, title, null);
    }

    /** A target whose id IS its position key - the common case when the place has one name. */
    @Nonnull
    public static WaypointTarget of(@Nonnull String idAndPositionKey, @Nullable Message title) {
        return new WaypointTarget(idAndPositionKey, idAndPositionKey, title, null);
    }
}
