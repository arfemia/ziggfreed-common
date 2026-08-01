package com.ziggfreed.common.interaction.type;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.protocol.InteractionState;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.ziggfreed.common.util.SafeLog;

/**
 * Resolves a custom interaction Type's chain state, encoding the TWO documented conventions so
 * a Type never has to re-decide them:
 *
 * <ul>
 *   <li><b>SILENT SKIP ({@link #skip}, resolves {@code Finished}).</b> A gate miss - a cooldown
 *       still running, a chance roll lost, an absent permission, no player on the firing entity.
 *       The carrying chain continues exactly as if the step were absent: a weapon still swings,
 *       a consumable still consumes. This is the {@code RunCommandInteraction} convention.</li>
 *   <li><b>FAILED ({@link #failed}).</b> A hard internal error (null CommandBuffer, unresolvable
 *       target, an uncaught throw) OR a deliberate branch to the chain's {@code Failed} label.
 *       {@code Failed} SUPPRESSES the chained native step - which is exactly the free-refund
 *       semantics a cast-then-consume scroll wants (a failed cast never reaches the chained
 *       {@code ModifyInventory}). Choosing {@code Failed} for a gate miss is therefore a behavior
 *       change, never a formatting choice.</li>
 * </ul>
 *
 * <p><b>Every exit path MUST set the state</b> or the client spins forever on a server-waiting
 * node. {@link #guard} makes that structural: it always writes {@code Finished} or {@code
 * Failed}, never leaves the state untouched.
 *
 * <p>Reminder for Type authors: a Type whose {@code firstRun} can resolve {@code Failed} must
 * return {@code WaitForDataFrom.Server} from {@code getWaitForDataFrom()} (the {@code
 * SimpleInteraction} contract), which costs a round trip at that node. A Type that only ever
 * resolves {@code Finished} may stay {@code None}. Decision 38 binds ability BODIES to
 * Server/None only.
 *
 * <p>World-thread (mutates a live {@link InteractionContext}'s sync state).
 */
public final class InteractionOutcome {

    private InteractionOutcome() {
    }

    /** Resolve {@code Finished} - the work ran. A null {@code ctx} is a guarded no-op. */
    public static void finished(@Nullable InteractionContext ctx) {
        writeState(ctx, InteractionState.Finished);
    }

    /**
     * Resolve {@code Finished} - a GATE MISS, chain continues. Semantically distinct from
     * {@link #finished} even though the resolved state is the same: this call site is
     * documenting "I chose to skip", not "I did the work".
     */
    public static void skip(@Nullable InteractionContext ctx) {
        writeState(ctx, InteractionState.Finished);
    }

    /** Resolve {@code Failed} - hard error or a deliberate Failed branch. */
    public static void failed(@Nullable InteractionContext ctx) {
        writeState(ctx, InteractionState.Failed);
    }

    /**
     * Run {@code body}, resolve the state from its result, and turn any {@link Throwable} into
     * {@code Failed} plus one guarded WARN tagged with {@code label}. The body always runs even
     * when {@code ctx} is null (a null ctx only means the state write is skipped), so this is
     * unit-testable without a live engine context.
     *
     * @return the resolved success flag ({@code false} when the body returned false or threw)
     */
    public static boolean guard(@Nullable InteractionContext ctx, @Nonnull String label, @Nonnull InteractionBody body) {
        boolean success;
        Throwable failure = null;
        try {
            success = body.run();
        } catch (Throwable t) {
            success = false;
            failure = t;
        }
        if (failure != null) {
            SafeLog.warn("[interaction] " + label + " threw, resolving Failed", failure);
        }
        if (success) {
            finished(ctx);
        } else {
            failed(ctx);
        }
        return success;
    }

    private static void writeState(@Nullable InteractionContext ctx, @Nonnull InteractionState state) {
        if (ctx == null) {
            return;
        }
        try {
            ctx.getState().state = state;
        } catch (Throwable t) {
            SafeLog.fine("[interaction] failed to write chain state " + state, t);
        }
    }
}
