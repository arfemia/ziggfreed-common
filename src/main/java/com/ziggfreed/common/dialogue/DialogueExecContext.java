package com.ziggfreed.common.dialogue;

import javax.annotation.Nonnull;

/**
 * A {@link DialogueContext} enriched with the option being executed, handed to
 * action handlers. The extra fields let a handler key per-(dialogue,node,option)
 * state - notably the once-only reward guard
 * ({@link DialogueActionExecutor#rewardOnceKey}).
 */
public interface DialogueExecContext extends DialogueContext {

    @Nonnull NpcDialogue dialogue();

    @Nonnull String nodeId();

    int optionIndex();
}
