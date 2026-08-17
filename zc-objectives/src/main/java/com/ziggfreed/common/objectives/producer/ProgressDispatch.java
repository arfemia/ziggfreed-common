package com.ziggfreed.common.objectives.producer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.progress.DispatchOptions;
import com.ziggfreed.common.progress.ZoneLocator;
import com.ziggfreed.common.progress.ZoneRef;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.progress.runtime.ProgressionSubjectSource;
import com.ziggfreed.common.progress.runtime.ProgressionSystem;
import com.ziggfreed.common.progress.runtime.ProgressionSystemGate;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * The one route from a producer to THE shared engines.
 *
 * <p>Every producer calls exactly this, so no producer ever holds an engine or learns how a subject
 * is built. Both engines see the same moment, quests first, with the full dispatch options.
 *
 * <p>Three things travel with the moment that a producer knows nothing about, and all three are
 * resolved here so no producer ever has to remember them:
 * <ul>
 *   <li><b>the subject</b>, from the runtime's registered source rather than built here: on a
 *   server where a consumer's own store is the active one, a subject carrying the wrong handle
 *   reads neutral and drops every write, so the progress would simply not happen. Each engine gets
 *   the subject ITS OWN store understands, which on most servers is the same object and on some is
 *   deliberately not;
 *   <li><b>the zone</b>, from {@link ZoneLocator}: an objective scoped to a zone can never be
 *   satisfied by an event that carries no location, so a dispatch with no zone would switch that
 *   content off rather than merely losing precision;
 *   <li><b>the call scope</b>, from the runtime: the engines' outbound events carry an id and
 *   nothing else, and a consumer's own listeners resolve the rest from context its facade
 *   published. A call made without it pays out in silence - no toast, no jingle, no progress line -
 *   which is exactly what a shared surface must not do differently from the consumer's own menu.
 * </ul>
 *
 * <p>A fourth thing is asked rather than carried: every registered {@link ProgressionSystemGate},
 * per half, so an owner who has switched quests or achievements off for a player still has them
 * off. That is a SYSTEM gate every producer honours equally, never a producer claim - the producer
 * runs and reaches this dispatch whatever any gate answers, and a refusal costs only the half it
 * names.
 *
 * <p><b>Why there is no "is anything listening?" short-circuit here.</b> Both engines can answer
 * that cheaply ({@code index().forKind(kind)}), and skipping the dispatch on an empty answer would
 * be free progress-wise - but the observer tap is deliberately fed on a dispatch that MATCHED
 * NOTHING, because a lifetime counter has to count a block broken while no content wanted it. A
 * short-circuit here would take exactly those events away from every registered tap. The per-engine
 * skip stays inside each engine, where the tap has already been fed.
 *
 * <p>World thread only: the engines and the outbound native events they publish both expect it, and
 * every producer is an ECS system, which is already there.
 */
public final class ProgressDispatch {

    private ProgressDispatch() {
    }

    /**
     * Feed one moment to both shared engines for this player.
     *
     * <p><b>This is the stable public entry point for a NET NEW moment.</b> A mod with something
     * nobody covers registers the kind through {@code ObjectiveKindRegistry} if it is not already
     * known and calls this from its own ECS event system. There is nothing to claim and nothing to
     * conflict over, because nothing gates it: contributions stack and no producer ever replaces
     * another.
     */
    public static void fire(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull String kindId, @Nonnull String target,
            @Nullable String qualifier, long amount) {
        try {
            ProgressionSubjectSource subjects = ProgressionRuntime.subjects();
            Subject questSubject = subjects.questSubject(store, ref);
            Subject achievementSubject = subjects.achievementSubject(store, ref);
            if (questSubject == null && achievementSubject == null) {
                return;
            }
            dispatch(ProgressionRuntime.quests(), ProgressionRuntime.achievements(),
                    questSubject, achievementSubject, kindId, target, qualifier, amount,
                    ZoneLocator.currentZone(store, ref));
        } catch (Throwable t) {
            SafeLog.warn("[progression] dispatching '" + kindId + "' failed", t);
        }
    }

    /**
     * The engine-facing half, with both engines and both subjects handed in. Package-visible so a
     * test can drive it over an in-memory store with no server anywhere near it.
     *
     * <p>Each half is wrapped in the runtime's registered call scope, which is what makes a moment
     * produced here reach a consumer's listeners with everything they read - the same context its
     * own menus publish. A server that registered no scope runs the DIRECT one and pays nothing.
     *
     * <p><b>ONE action, ONE tap.</b> Both engines are built over the same composed observer tap, so
     * only ONE half may carry {@code tapObservers} - the first that actually runs. A second FULL
     * would hand a lifetime counter two of every block broken, and skipping the tap on a half that
     * never ran would cost the count altogether on a server that keeps only one of the two.
     *
     * <p><b>The owner's system switches are asked here, per half.</b> A refusal costs exactly the
     * half it names and leaves the other alone; nothing about the producer changes, which is why
     * this is a {@link ProgressionSystemGate} rather than anything a producer knows about.
     */
    static void dispatch(@Nullable QuestEngine quests, @Nullable AchievementEngine achievements,
            @Nullable Subject questSubject, @Nullable Subject achievementSubject,
            @Nonnull String kindId, @Nonnull String target, @Nullable String qualifier, long amount,
            @Nullable ZoneRef zone) {
        boolean tapSpent = false;
        if (quests != null && questSubject != null
                && ProgressionRuntime.systemEnabled(ProgressionSystem.QUEST, questSubject)) {
            ProgressionRuntime.questScope().run(questSubject, subject ->
                    quests.dispatch(subject, kindId, target, qualifier, amount, zone,
                            DispatchOptions.FULL));
            tapSpent = true;
        }
        if (achievements != null && achievementSubject != null
                && ProgressionRuntime.systemEnabled(ProgressionSystem.ACHIEVEMENT,
                        achievementSubject)) {
            DispatchOptions options = tapSpent ? DispatchOptions.OBJECTIVES_ONLY : DispatchOptions.FULL;
            ProgressionRuntime.achievementScope().run(achievementSubject, subject ->
                    achievements.dispatch(subject, kindId, target, qualifier, amount, zone, options));
        }
    }
}
