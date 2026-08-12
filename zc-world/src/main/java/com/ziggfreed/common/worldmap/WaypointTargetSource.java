package com.ziggfreed.common.worldmap;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;

/**
 * Something that knows where a viewer is being pointed right now. A consumer registers one per
 * reason it has to point somebody somewhere, and the service asks them all whenever it refreshes.
 *
 * <p><b>Several sources, one snapshot.</b> Sources are additive and independent - one may answer
 * "where the thing you are in the middle of continues", another "the place you asked to be shown" -
 * and the service merges them, keeping the first target for any repeated id. That is what lets a
 * whole new reason to show a marker arrive as one more source with nothing here changing.
 *
 * <p><b>Called on the refresh thread</b> (the consumer's own, typically the world thread, which is
 * where reading its state is legal), never on the map tracker. Keep it a read: build the list and
 * return, do not fire anything. Throwing is survivable - the service logs it and takes what the
 * other sources gave - but a source that throws contributes nothing that refresh.
 */
@FunctionalInterface
public interface WaypointTargetSource {

    /** Where {@code viewerId} is being pointed right now, or an empty list for nowhere. */
    @Nonnull
    List<WaypointTarget> targetsFor(@Nonnull UUID viewerId);
}
