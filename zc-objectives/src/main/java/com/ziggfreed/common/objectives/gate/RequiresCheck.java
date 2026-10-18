package com.ziggfreed.common.objectives.gate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.progress.gate.GateQuery;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.progress.runtime.ProgressionGates;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.quest.LockReasons;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * The one read every {@code ZigRequires} surface answers with: the interaction a block or an item chain
 * starts with ({@code objectives/interaction/ZigRequiresInteraction}) and the native condition Types
 * ({@link ZigRequiresBootstrap} lists them: trigger volume, entity, NPC decision, world event, choice). The
 * player at a ref, as the runtime's own subject, against a {@code Requires} block, through the server's one
 * evaluator ({@link ProgressionGates#evaluator}), so a door gated here and the same block on a quest or a
 * shop row never disagree.
 *
 * <p>It only reads. Nothing here writes progress, so no call scope wraps it, and the subject is never one
 * built locally: a subject built here would read neutral through another mod's store and refuse everyone.
 * Another entity (an NPC, a mob) is read through its own factor context ({@link #query}), so a block of
 * factors answers for it and a {@code Quests} leaf refuses it.
 */
public final class RequiresCheck {

    private RequiresCheck() {
    }

    /**
     * The subject the quest store understands for the player at {@code ref}; null for anything that is not
     * a player (an NPC, a thrown item, a block event with no actor) and for a player the runtime cannot name
     * yet. Null is never an error: the caller refuses. World thread.
     */
    @Nullable
    public static Subject playerSubject(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref) {
        if (store == null || ref == null || !ref.isValid()) {
            return null;
        }
        try {
            if (store.getComponent(ref, PlayerRef.getComponentType()) == null) {
                return null;
            }
            return ProgressionRuntime.subjects().questSubject(store, ref);
        } catch (Throwable t) {
            SafeLog.fine("[gate] the player a Requires check is for could not be named: " + t.getMessage());
            return null;
        }
    }

    /** Does {@code subject} meet {@code requires}? An absent or empty block asks for nothing. */
    public static boolean passes(@Nonnull Subject subject, @Nullable GateSpec requires) {
        return ProgressionGates.evaluator().passes(subject, requires);
    }

    /**
     * The question a {@code Requires} block is put about the entity at {@code ref}: a player as the runtime's
     * own subject ({@link #playerSubject}), any other entity read through its own factor context. Null when
     * there is nobody to ask: the ref is gone, the runtime cannot name the player yet, or the caller is off the
     * store's world thread, where no reading may be made (an engine that scores conditions in parallel asks
     * from a worker). Null is never an error: {@link #passes(GateQuery, GateSpec)} refuses it.
     */
    @Nullable
    public static GateQuery query(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref) {
        if (store == null || ref == null || !ref.isValid()) {
            return null;
        }
        try {
            if (!store.isInThread()) {
                return null;
            }
            if (store.getComponent(ref, PlayerRef.getComponentType()) == null) {
                return GateQuery.of(FactorContext.about(store, ref));
            }
            Subject subject = ProgressionRuntime.subjects().questSubject(store, ref);
            return subject == null ? null : GateQuery.of(subject);
        } catch (Throwable t) {
            SafeLog.fine("[gate] the entity a Requires check is for could not be read: " + t.getMessage());
            return null;
        }
    }

    /**
     * The question a {@code Requires} block is put about a world with nobody in particular in it: the world and
     * its store, no subject, so a server-wide reading (a feature, a season, an installed mod) answers and every
     * reading of a player refuses. Null off the world thread, or when the store names no world.
     */
    @Nullable
    public static GateQuery worldQuery(@Nullable Store<EntityStore> store) {
        if (store == null) {
            return null;
        }
        try {
            if (!store.isInThread()) {
                return null;
            }
            EntityStore entities = store.getExternalData();
            return GateQuery.of(FactorContext.builder()
                    .world(entities == null ? null : entities.getWorld())
                    .store(store)
                    .build());
        } catch (Throwable t) {
            SafeLog.fine("[gate] the world a Requires check is for could not be read: " + t.getMessage());
            return null;
        }
    }

    /**
     * Does {@code requires} ask for nothing? An absent or empty block holds for anyone, asked or not, so a caller
     * that maps the answer onto the engine's own flip ({@code Inverse}, a true and a false score) needs no query
     * for it; any other block needs one, and a caller with nobody to ask refuses whatever the flip.
     */
    public static boolean asksNothing(@Nullable GateSpec requires) {
        return requires == null || requires.isEmpty();
    }

    /**
     * Does {@code query} meet {@code requires}? An absent or empty block asks for nothing, so it passes even with
     * nobody to ask; any other block refuses a null query. The hot-path ask ({@code GateEvaluator.check}): the
     * block's compiled form, its verdict kept between asks where its readings allow, so a condition the engine
     * asks every tick pays for one walk only when something it reads has moved.
     */
    public static boolean passes(@Nullable GateQuery query, @Nullable GateSpec requires) {
        if (asksNothing(requires)) {
            return true;
        }
        return query != null && ProgressionGates.evaluator().check(requires, query).passed();
    }

    /**
     * Null when {@code subject} meets {@code requires}, else the line telling them why it shut: the first
     * requirement they did not meet, worded by the one mapping every surface reads
     * ({@link LockReasons#line(String)}), so it names the quest to finish or the factor to raise and never shows
     * a raw token. One walk of the block answers both, so a refusal never evaluates it twice.
     */
    @Nullable
    public static Message refusal(@Nonnull Subject subject, @Nullable GateSpec requires) {
        String failure = ProgressionGates.evaluator().firstFailure(subject, requires);
        return failure == null ? null : LockReasons.line(failure);
    }
}
