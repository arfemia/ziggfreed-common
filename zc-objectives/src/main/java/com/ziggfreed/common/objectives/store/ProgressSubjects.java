package com.ziggfreed.common.objectives.store;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.progress.runtime.ProgressionSubjectSource;
import com.ziggfreed.common.subject.Subject;

/**
 * Builds the engine {@link Subject} the library's DEFAULT stores speak in: the player's own uuid and
 * name, plus a {@link ProgressHandle} so a store adapter, a gate or a factor reading can reach their
 * live entity.
 *
 * <p>Registered as the shared runtime's default {@link ProgressionSubjectSource}, so a surface asks
 * the runtime rather than calling this directly - which is what keeps a shared surface working on a
 * server where a consumer's own store, and therefore a consumer's own handle, is the registered one.
 *
 * <p>Fails soft, deliberately. A player whose uuid cannot be read yet is not an error - it is a
 * "not this pass", and every caller treats a null subject as a reason to do nothing.
 */
public final class ProgressSubjects implements ProgressionSubjectSource {

    /** The one instance, registered at library-default rank. */
    public static final ProgressSubjects INSTANCE = new ProgressSubjects();

    private ProgressSubjects() {
    }

    /** The subject for this player, or null when there is not enough to build one. */
    @Nullable
    public static Subject of(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef) {
        UUID uuid = playerRef.getUuid();
        if (uuid == null) {
            return null;
        }
        String username = playerRef.getUsername();
        return new Subject(uuid, username == null ? "" : username,
                new ProgressHandle(store, ref, playerRef));
    }

    /** One handle answers for both engines here, so both sides return the same subject. */
    @Override
    @Nullable
    public Subject questSubject(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef) {
        return of(store, ref, playerRef);
    }

    @Override
    @Nullable
    public Subject achievementSubject(@Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> ref, @Nonnull PlayerRef playerRef) {
        return of(store, ref, playerRef);
    }
}
