package com.ziggfreed.common.objectives.marker;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.subject.Subject;

/**
 * Everything one evaluation of one viewer needs, on the world thread: the live store, the accessor to
 * spawn with (the store from a world task, the tick's command buffer from the sweep), the world, the
 * viewer, their quest subject and the placed characters standing in that world (a read-only view of
 * the hub's index).
 */
public record QuestMarkerScope(@Nonnull Store<EntityStore> store, @Nonnull ComponentAccessor<EntityStore> accessor,
                               @Nonnull World world, @Nonnull Ref<EntityStore> viewerRef, @Nonnull UUID viewerId,
                               @Nonnull Subject subject, @Nonnull Set<Ref<EntityStore>> hosts) {

    /** {@code hosts} is kept as a read-only view: a surface reads the hub's index and never edits it. */
    public QuestMarkerScope {
        hosts = Collections.unmodifiableSet(hosts);
    }
}
