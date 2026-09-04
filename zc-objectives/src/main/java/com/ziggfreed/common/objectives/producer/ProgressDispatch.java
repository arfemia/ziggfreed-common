package com.ziggfreed.common.objectives.producer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.achievement.AchievementEngine;
import com.ziggfreed.common.achievement.FirstClaims;
import com.ziggfreed.common.progress.DispatchOptions;
import com.ziggfreed.common.progress.ZoneLocator;
import com.ziggfreed.common.progress.ZoneRef;
import com.ziggfreed.common.progress.runtime.Moment;
import com.ziggfreed.common.progress.runtime.MomentListener;
import com.ziggfreed.common.progress.runtime.MomentPayload;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.progress.runtime.ProgressionSubjectSource;
import com.ziggfreed.common.progress.runtime.ProgressionSystem;
import com.ziggfreed.common.progress.runtime.ProgressionSystemGate;
import com.ziggfreed.common.progress.runtime.SharedCredit;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * The one route from a producer to every REACTION and to THE shared engines.
 *
 * <p>Every producer calls exactly this, so no producer ever holds an engine or learns how a subject
 * is built. A produced moment goes two places, in this order: first to every registered
 * {@link MomentListener}, unconditionally; then to both engines, quests first, with the full
 * dispatch options.
 *
 * <p><b>The listeners fire FIRST, before the subject test and before both system gates.</b> A
 * reaction is not a progression half: XP, a lifetime counter, a bonus drop are a consumer's own
 * product, and a player who has never opened a quest log still mines ore for XP, an owner who
 * switched quests off has said nothing about mining. So the fan-out sits ahead of the one early
 * return in this class and ahead of every gate the engines are asked; nothing a listener does can
 * refuse the moment or stand a producer down (see {@link MomentListener} for what a listener is
 * not, and {@link com.ziggfreed.common.progress.ProgressDispatchTap} for the tap it is not).
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
 * NOTHING, because a lifetime counter has to count a block broken while no content wanted it, and
 * the listeners are fed on every moment for the same reason. A short-circuit here would take
 * exactly those events away from every registered tap and every reaction. The per-engine skip stays
 * inside each engine, where the tap has already been fed.
 *
 * <p>World thread only: the engines and the outbound native events they publish both expect it, and
 * every producer is an ECS system, which is already there.
 */
public final class ProgressDispatch {

    private ProgressDispatch() {
    }

    /**
     * Feed one moment to every reaction and to both shared engines for this player.
     *
     * <p><b>This is the stable public entry point for a NET NEW moment.</b> A mod with something
     * nobody covers registers the kind through {@code ObjectiveKindRegistry} if it is not already
     * known and calls this from its own ECS event system. There is nothing to claim and nothing to
     * conflict over, because nothing gates it: contributions stack and no producer ever replaces
     * another. A moment fired this way reaches every listener too, carrying no payload; a producer
     * with more to say uses the payload overload.
     */
    public static void fire(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull String kindId, @Nonnull String target,
            @Nullable String qualifier, long amount) {
        fire(store, ref, null, kindId, target, qualifier, amount, null);
    }

    /**
     * The producer's form of {@link #fire(Store, Ref, String, String, String, long)}: the same
     * moment, plus the command buffer the producing system was handed and the producer's own
     * {@link MomentPayload}, both carried to every listener on the {@link Moment}.
     *
     * <p>{@code ref} is the PLAYER credited. A producer that redirected the moment - a kill by a
     * turret credited to its owner through the attribution seam - passes the owner here, so every
     * reaction and both engines see the moment on the same terms.
     */
    public static void fire(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nullable CommandBuffer<EntityStore> commandBuffer, @Nonnull String kindId,
            @Nonnull String target, @Nullable String qualifier, long amount,
            @Nullable MomentPayload payload) {
        try {
            ProgressionSubjectSource subjects = ProgressionRuntime.subjects();
            Moment moment = new Moment(kindId, target, qualifier, amount,
                    ZoneLocator.currentZone(store, ref), store, ref, commandBuffer,
                    questSubject(subjects, store, ref), achievementSubject(subjects, store, ref),
                    payload);
            produce(ProgressionRuntime.quests(), ProgressionRuntime.achievements(), moment);
        } catch (Throwable t) {
            SafeLog.warn("[progression] dispatching '" + kindId + "' failed", t);
        }
    }

    /**
     * Feed one moment to the ENGINES ONLY, with the given options, and to no listener.
     *
     * <p>For an ALIAS: the same action a listener already reacted to, re-dispatched under a second
     * kind or id so content authored against that spelling advances too - a harvest picked up by
     * hand also counting as a block broken under the crop's item id. The listeners must NOT see it a
     * second time: a reaction that awards XP for the aliased kind would pay the one action twice, a
     * lifetime counter would count it twice, and a reaction reading the aliased kind's payload would
     * find nothing there. So this route deliberately never enters the fan-out, whatever the options
     * say. Pass {@link DispatchOptions#OBJECTIVES_ONLY} for the ordinary alias, or
     * {@link DispatchOptions#TARGETED_ONLY} when a match-all objective already counted the primary
     * fire and only content that NAMES this id may advance.
     */
    public static void fire(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull String kindId, @Nonnull String target, @Nullable String qualifier, long amount,
            @Nonnull DispatchOptions options) {
        try {
            ProgressionSubjectSource subjects = ProgressionRuntime.subjects();
            Subject questSubject = questSubject(subjects, store, ref);
            Subject achievementSubject = achievementSubject(subjects, store, ref);
            if (questSubject == null && achievementSubject == null) {
                return;
            }
            dispatch(ProgressionRuntime.quests(), ProgressionRuntime.achievements(),
                    questSubject, achievementSubject, kindId, target, qualifier, amount,
                    ZoneLocator.currentZone(store, ref), options);
        } catch (Throwable t) {
            SafeLog.warn("[progression] dispatching '" + kindId + "' failed", t);
        }
    }

    /**
     * The moment-facing half: every listener first, then both engines. Package-visible so a test can
     * drive it over a hand-built {@link Moment} and in-memory stores with no server anywhere near it.
     *
     * <p>The fan-out is the composed, guarded one the runtime holds, so a throwing listener costs
     * its own reaction and never the engines' half below; and it runs BEFORE the one early return
     * here, so a moment for a player with no subject on either side is still reacted to.
     */
    static void produce(@Nullable QuestEngine quests, @Nullable AchievementEngine achievements,
            @Nonnull Moment moment) {
        ProgressionRuntime.momentListener().react(moment);
        if (moment.questSubject() == null && moment.achievementSubject() == null) {
            return;
        }
        // A moment that is one event shared by several players (a party's boss kill, dispatched
        // once per member) runs its engine half under that shared credit, so a server-first one of
        // them wins inside it is a win for the rest of them rather than a race lost to a teammate.
        SharedCredit shared = moment.payload(SharedCredit.class);
        FirstClaims.withSharedCredit(shared == null ? null : shared.creditKey(), () ->
                dispatch(quests, achievements, moment.questSubject(), moment.achievementSubject(),
                        moment.kindId(), moment.target(), moment.qualifier(), moment.amount(),
                        moment.zone(), DispatchOptions.FULL));
    }

    /**
     * The engine-facing half, with both engines and both subjects handed in, as the AUTHORITATIVE
     * fire of an action. Package-visible so a test can drive it over an in-memory store with no
     * server anywhere near it.
     */
    static void dispatch(@Nullable QuestEngine quests, @Nullable AchievementEngine achievements,
            @Nullable Subject questSubject, @Nullable Subject achievementSubject,
            @Nonnull String kindId, @Nonnull String target, @Nullable String qualifier, long amount,
            @Nullable ZoneRef zone) {
        dispatch(quests, achievements, questSubject, achievementSubject, kindId, target, qualifier,
                amount, zone, DispatchOptions.FULL);
    }

    /**
     * The engine-facing half with the options spelled out.
     *
     * <p>Each half is wrapped in the runtime's registered call scope, which is what makes a moment
     * produced here reach a consumer's listeners with everything they read - the same context its
     * own menus publish. A server that registered no scope runs the DIRECT one and pays nothing.
     *
     * <p><b>ONE action, ONE tap.</b> Both engines are built over the same composed observer tap, so
     * only ONE half may carry {@code tapObservers} - the first that actually runs. A second FULL
     * would hand a lifetime counter two of every block broken, and skipping the tap on a half that
     * never ran would cost the count altogether on a server that keeps only one of the two. Options
     * that do not tap (an alias) reach both halves unchanged.
     *
     * <p><b>The owner's system switches are asked here, per half.</b> A refusal costs exactly the
     * half it names and leaves the other alone; nothing about the producer changes, which is why
     * this is a {@link ProgressionSystemGate} rather than anything a producer knows about.
     */
    static void dispatch(@Nullable QuestEngine quests, @Nullable AchievementEngine achievements,
            @Nullable Subject questSubject, @Nullable Subject achievementSubject,
            @Nonnull String kindId, @Nonnull String target, @Nullable String qualifier, long amount,
            @Nullable ZoneRef zone, @Nonnull DispatchOptions options) {
        boolean tapSpent = false;
        if (quests != null && questSubject != null
                && ProgressionRuntime.systemEnabled(ProgressionSystem.QUEST, questSubject)) {
            ProgressionRuntime.questScope().run(questSubject, subject ->
                    quests.dispatch(subject, kindId, target, qualifier, amount, zone, options));
            tapSpent = options.tapObservers();
        }
        if (achievements != null && achievementSubject != null
                && ProgressionRuntime.systemEnabled(ProgressionSystem.ACHIEVEMENT,
                        achievementSubject)) {
            DispatchOptions second = tapSpent
                    ? new DispatchOptions(false, options.targetedOnly()) : options;
            ProgressionRuntime.achievementScope().run(achievementSubject, subject ->
                    achievements.dispatch(subject, kindId, target, qualifier, amount, zone, second));
        }
    }

    /**
     * The quest-side subject, or null. Guarded on its own so a subject source that throws costs the
     * engines' half and never a reaction: a listener is promised the moment whatever the state of
     * either progression system.
     */
    @Nullable
    private static Subject questSubject(@Nonnull ProgressionSubjectSource subjects,
            @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        try {
            return subjects.questSubject(store, ref);
        } catch (Throwable t) {
            SafeLog.warn("[progression] the quest subject could not be built", t);
            return null;
        }
    }

    /** The achievement-side subject, on the same terms. */
    @Nullable
    private static Subject achievementSubject(@Nonnull ProgressionSubjectSource subjects,
            @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        try {
            return subjects.achievementSubject(store, ref);
        } catch (Throwable t) {
            SafeLog.warn("[progression] the achievement subject could not be built", t);
            return null;
        }
    }
}
