package com.ziggfreed.common.dialogue;

import javax.annotation.Nonnull;

/**
 * Executes one registered action type. Replaces the {@code instanceof} ladder of
 * a single-consumer executor: each {@link DialogueActionType} binds a handler, so
 * a consumer adds an action's behavior alongside its schema and the two cannot
 * drift. Handlers contribute to the shared {@link DialogueActionExecutor.Mut}
 * (goto / close / opened-other-page) and otherwise act through the
 * {@link DialogueExecContext}.
 */
@FunctionalInterface
public interface DialogueActionHandler<A extends DialogueAction> {

    void handle(@Nonnull A action, @Nonnull DialogueExecContext ctx, @Nonnull DialogueActionExecutor.Mut out);
}
