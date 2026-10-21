package com.ziggfreed.common.proc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.server.core.entity.InteractionChain;
import com.hypixel.hytale.server.core.entity.damage.DamageDataComponent;
import com.hypixel.hytale.server.core.entity.effect.ActiveEntityEffect;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.stats.condition.EffectFamilies;

/**
 * The moves a proc waits to see truly happen before it answers them, read by the holder pass on every tick. A key
 * press is not a move: the engine starts the chain first and checks and pays its cost inside it, so a refused dodge,
 * a forward or back press (which dashes nowhere) and a signature with an empty bar all start chains.
 *
 * <ul>
 *   <li><b>Dodge</b>: the player's own Dodge chain opens a window; the dodge counts once an effect of the
 *       {@value #DODGE_FAMILY} effect family lands on them inside it (vanilla's side dodges and their moment of
 *       invulnerability, put on only after the stamina check passed), the chain still running or just ended, at most
 *       {@value #DODGE_WINDOW_NANOS} ns after the press. One landing proves one press, the oldest waiting: a second
 *       press made before it waits for a landing of its own.</li>
 *   <li><b>GuardStart</b>: no chain at all. The guard is up when the engine says the player wields a block
 *       ({@code DamageDataComponent.getCurrentWielding}), so the moment is that going from none to one, whatever
 *       input raised it (a sword's guard is a Secondary chain holding a Wielding step).</li>
 *   <li><b>Cast</b>: the player's own Ability1 to Ability4 chain counts once it got past its opening: it finished,
 *       or it still runs {@value #CAST_PROOF_NANOS} ns after it started (a cost check that refuses fails the chain at
 *       once). A chain whose root a consumer says starts its own casts ({@link ProcAbilities}) never counts here:
 *       that consumer fires its casts itself ({@link Procs#cast}) as they go off. When that consumer hands a press
 *       back to the move its key displaced ({@link Procs#handBack}), the server chain it runs counts as the press.</li>
 * </ul>
 *
 * <p>World thread. A holder's state is touched by its own world's thread alone; the outer map is concurrent.
 */
final class MoveProofs {

    /** The effect family a dodge that went through puts on (zc-entity ships it: Dodge_Left, Dodge_Right, and
     *  Dodge_Invulnerability). */
    static final String DODGE_FAMILY = "Dodging";

    /** The longest a dodge may take, from its press, to land its effect. */
    static final long DODGE_WINDOW_NANOS = 1_500_000_000L;

    /** How long a cast's chain must still run to count as past its opening. */
    static final long CAST_PROOF_NANOS = 500_000_000L;

    /** An active effect as one look saw it: which instance, and how long it had left. */
    record Mark(@Nonnull Object instance, float remaining) {
    }

    /** One move waiting for its proof. */
    private static final class Pending {
        final ProcTrigger trigger;
        final InteractionChain chain;
        final long startedNanos;
        /** The dodge effects worn at the press; moved up to the latest look once another press spent a landing. */
        @Nullable Map<Integer, Mark> before;
        @Nullable final String root;
        boolean endedSeen;

        Pending(@Nonnull ProcTrigger trigger, @Nonnull InteractionChain chain, long startedNanos,
                @Nullable Map<Integer, Mark> before, @Nullable String root) {
            this.trigger = trigger;
            this.chain = chain;
            this.startedNanos = startedNanos;
            this.before = before;
            this.root = root;
        }
    }

    /**
     * One holder's waiting moves, whether their guard was up at the last look, and the roots a consumer handed a press
     * back to ({@link #handedBack}), each until when its chain may start.
     */
    private static final class HolderState {
        final List<Pending> pending = new ArrayList<>(2);
        final Map<String, Long> handedBack = new HashMap<>(2);
        boolean guardUp;
    }

    /** How long after a consumer hands a press back its chain may start and still count as the press. */
    static final long HANDED_BACK_NANOS = 1_000_000_000L;

    private static final Map<Ref<EntityStore>, HolderState> STATES = new ConcurrentHashMap<>();

    private MoveProofs() {
    }

    // ==================== The starts ====================

    /** The holder's own Dodge chain started: note what dodge effects they already wear. */
    static void dodgeStarted(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> holder,
            @Nonnull InteractionChain chain, long nanos) {
        STATES.computeIfAbsent(holder, h -> new HolderState()).pending.add(
                new Pending(ProcTrigger.DODGE, chain, nanos, dodgeMarks(accessor, holder), null));
    }

    /** The holder's own Ability chain started from {@code root}. */
    static void castStarted(@Nonnull Ref<EntityStore> holder, @Nonnull InteractionChain chain, @Nullable String root,
            long nanos) {
        STATES.computeIfAbsent(holder, h -> new HolderState()).pending.add(
                new Pending(ProcTrigger.CAST, chain, nanos, null, root));
    }

    /**
     * A consumer that took the holder's key is handing the press back to the move it displaced, which it runs from
     * the server as a chain of {@code root}: that chain, started within {@value #HANDED_BACK_NANOS} ns, counts as the
     * holder's own press.
     */
    static void handBack(@Nonnull Ref<EntityStore> holder, @Nonnull String root, long nanos) {
        STATES.computeIfAbsent(holder, h -> new HolderState()).handedBack.put(root.trim().toLowerCase(Locale.ROOT),
                nanos + HANDED_BACK_NANOS);
    }

    /** Is a server chain of {@code root} starting now one a consumer handed back for the holder (spent once)? */
    static boolean takeHandedBack(@Nonnull Ref<EntityStore> holder, @Nullable String root, long nanos) {
        HolderState state = STATES.get(holder);
        if (state == null || root == null || state.handedBack.isEmpty()) {
            return false;
        }
        Long until = state.handedBack.remove(root.trim().toLowerCase(Locale.ROOT));
        state.handedBack.values().removeIf(at -> at < nanos);
        return until != null && nanos <= until;
    }

    // ==================== The holder pass ====================

    /** Every tick: settle the holder's waiting moves, and see whether their guard went up. Never throws. */
    static void check(@Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer,
            @Nonnull Ref<EntityStore> holder, long nanos) {
        HolderState state = STATES.get(holder);
        boolean guards = Procs.listens(ProcTrigger.GUARD_START);
        if (state == null && !guards) {
            return;
        }
        if (state == null) {
            state = STATES.computeIfAbsent(holder, h -> new HolderState());
        }
        if (guards) {
            boolean up = guardUp(commandBuffer, holder);
            boolean rose = up && !state.guardUp;
            state.guardUp = up;
            if (rose) {
                Procs.fire(new ProcMoment(ProcTrigger.GUARD_START, store, commandBuffer, holder, null, null, 1.0,
                        null));
            }
        }
        if (state.pending.isEmpty()) {
            return;
        }
        List<Pending> proven = null;
        Map<Integer, Mark> dodgeNow = null;
        boolean landingSpent = false;
        Iterator<Pending> it = state.pending.iterator();
        while (it.hasNext()) {
            Pending pending = it.next();
            Verdict verdict;
            if (pending.trigger == ProcTrigger.DODGE) {
                if (dodgeNow == null) {
                    dodgeNow = dodgeMarks(commandBuffer, holder);
                }
                if (landingSpent) {
                    // One landing proves one press: a later press waits for a landing of its own.
                    pending.before = dodgeNow;
                }
                verdict = dodgeVerdict(pending, dodgeNow, nanos);
                landingSpent |= verdict == Verdict.PROVEN;
            } else {
                verdict = castVerdict(pending.chain.getServerState(), nanos - pending.startedNanos);
            }
            if (verdict == Verdict.WAIT) {
                continue;
            }
            it.remove();
            if (verdict == Verdict.PROVEN) {
                if (proven == null) {
                    proven = new ArrayList<>(1);
                }
                proven.add(pending);
            }
        }
        if (landingSpent) {
            // Every press still waiting looks for a landing after this one, never this one again.
            for (Pending pending : state.pending) {
                if (pending.trigger == ProcTrigger.DODGE) {
                    pending.before = dodgeNow;
                }
            }
        }
        if (proven != null) {
            for (Pending pending : proven) {
                Procs.fire(new ProcMoment(pending.trigger, store, commandBuffer, holder, null, null, 1.0, null,
                        pending.root, null, null));
            }
        }
    }

    /** What one look decides about a waiting move. */
    enum Verdict {
        WAIT, PROVEN, DROPPED
    }

    @Nonnull
    private static Verdict dodgeVerdict(@Nonnull Pending pending, @Nonnull Map<Integer, Mark> now, long nanos) {
        if (gained(pending.before == null ? Map.of() : pending.before, now)) {
            return Verdict.PROVEN;
        }
        if (nanos - pending.startedNanos > DODGE_WINDOW_NANOS) {
            return Verdict.DROPPED;
        }
        if (pending.chain.getServerState() != InteractionState.NotFinished) {
            // Its effects land before it ends; one more look after the end, then it never dodged.
            if (pending.endedSeen) {
                return Verdict.DROPPED;
            }
            pending.endedSeen = true;
        }
        return Verdict.WAIT;
    }

    /**
     * A cast chain in {@code state}, {@code elapsedNanos} after it started: proven once finished or still running
     * past {@value #CAST_PROOF_NANOS} ns, dropped once it ended any other way. Pure.
     */
    @Nonnull
    static Verdict castVerdict(@Nullable InteractionState state, long elapsedNanos) {
        if (state == InteractionState.Finished) {
            return Verdict.PROVEN;
        }
        if (state == null || state == InteractionState.NotFinished) {
            return elapsedNanos >= CAST_PROOF_NANOS ? Verdict.PROVEN : Verdict.WAIT;
        }
        return Verdict.DROPPED;
    }

    /**
     * Did a dodge effect land between two looks: one on now that was not on before, a new instance of one, or one
     * whose time left grew (a second apply of an effect that adds its time)? Pure.
     */
    static boolean gained(@Nonnull Map<Integer, Mark> before, @Nonnull Map<Integer, Mark> now) {
        for (Map.Entry<Integer, Mark> entry : now.entrySet()) {
            Mark was = before.get(entry.getKey());
            Mark is = entry.getValue();
            if (was == null || was.instance() != is.instance() || is.remaining() > was.remaining() + 1e-3f) {
                return true;
            }
        }
        return false;
    }

    /** The dodge effects {@code holder} wears now, by effect index. Empty when none or unreadable. */
    @Nonnull
    private static Map<Integer, Mark> dodgeMarks(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> holder) {
        try {
            EffectControllerComponent controller =
                    accessor.getComponent(holder, EffectControllerComponent.getComponentType());
            if (controller == null || controller.getActiveEffects().isEmpty()) {
                return Map.of();
            }
            Map<Integer, Mark> out = new HashMap<>();
            for (ActiveEntityEffect active : controller.getActiveEffects().values()) {
                int index = active.getEntityEffectIndex();
                if (EffectFamilies.contains(DODGE_FAMILY, index)) {
                    out.put(index, new Mark(active, active.getRemainingDuration()));
                }
            }
            return out;
        } catch (Throwable t) {
            return Map.of();
        }
    }

    /** Does {@code ref} wield a block right now? False when it cannot tell. */
    static boolean guardUp(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref) {
        try {
            DamageDataComponent data = accessor.getComponent(ref, DamageDataComponent.getComponentType());
            return data != null && data.getCurrentWielding() != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Forget a holder that left its store. */
    static void forget(@Nonnull Ref<EntityStore> ref) {
        STATES.remove(ref);
    }

    /** Forget everything (a test). */
    static void clear() {
        STATES.clear();
    }
}
