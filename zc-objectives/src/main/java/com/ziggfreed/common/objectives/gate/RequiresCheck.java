package com.ziggfreed.common.objectives.gate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.progress.runtime.ProgressionGates;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.quest.LockReasons;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * The one read both {@code ZigRequires} surfaces answer with: the interaction a block or an item chain
 * starts with ({@code objectives/interaction/ZigRequiresInteraction}) and the trigger-volume condition
 * ({@link ZigRequiresTriggerCondition}). The player at a ref, as the runtime's own subject, against a
 * {@code Requires} block, through the server's one evaluator ({@link ProgressionGates#evaluator}), so a
 * door gated here and the same block on a quest or a shop row never disagree.
 *
 * <p>It only reads. Nothing here writes progress, so no call scope wraps it, and the subject is never one
 * built locally: a subject built here would read neutral through another mod's store and refuse everyone.
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
