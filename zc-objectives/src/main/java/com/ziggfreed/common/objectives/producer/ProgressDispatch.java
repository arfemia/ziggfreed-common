package com.ziggfreed.common.objectives.producer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * The one route from a producer to THE shared engines.
 *
 * <p>Every producer calls exactly this, so no producer ever holds an engine or learns how a subject
 * is built. Both engines see the same moment, quests first, with the full dispatch options and no
 * zone (nothing here knows what a zone is).
 *
 * <p>The subject comes from the runtime's registered source rather than being built here: on a
 * server where a consumer's own store is the active one, a subject carrying the wrong handle reads
 * neutral and drops every write, so the progress would simply not happen.
 *
 * <p>World thread only: the engines and the outbound native events they publish both expect it, and
 * every producer is an ECS system, which is already there.
 */
public final class ProgressDispatch {

    private ProgressDispatch() {
    }

    /**
     * Feed one moment to both shared engines for this player. Whether this producer should be firing
     * at all is its OWN first line (see the per-kind stand-down), not a question asked here.
     */
    public static void fire(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull PlayerRef playerRef, @Nonnull String kindId, @Nonnull String target,
            @Nullable String qualifier, long amount) {
        try {
            Subject subject = ProgressionRuntime.subjects().questSubject(store, ref, playerRef);
            if (subject == null) {
                return;
            }
            dispatch(ProgressionRuntime.quests(), ProgressionRuntime.achievements(),
                    subject, kindId, target, qualifier, amount);
        } catch (Throwable t) {
            SafeLog.warn("[progression] dispatching '" + kindId + "' failed", t);
        }
    }

    /**
     * The engine-facing half, with both engines handed in. Package-visible so a test can drive it
     * over an in-memory store with no server anywhere near it.
     */
    static void dispatch(@Nullable QuestEngine quests, @Nullable AchievementEngine achievements,
            @Nonnull Subject subject, @Nonnull String kindId, @Nonnull String target,
            @Nullable String qualifier, long amount) {
        if (quests != null) {
            quests.dispatch(subject, kindId, target, qualifier, amount);
        }
        if (achievements != null) {
            achievements.dispatch(subject, kindId, target, qualifier, amount);
        }
    }
}
