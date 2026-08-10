package com.ziggfreed.common.interaction.type;

/**
 * The body of a custom interaction Type's {@code firstRun}, allowed to throw.
 *
 * <p>Run through {@link InteractionOutcome#guard}, which turns a {@code true}/{@code false}
 * return into a {@code Finished}/{@code Failed} chain-state resolve and any thrown
 * {@link Throwable} into a logged {@code Failed}.
 */
@FunctionalInterface
public interface InteractionBody {

    /**
     * @return {@code true} to resolve {@code InteractionState.Finished}, {@code false} to
     *         resolve {@code InteractionState.Failed}
     * @throws Throwable any error; caught and logged by {@link InteractionOutcome#guard}
     */
    boolean run() throws Throwable;
}
