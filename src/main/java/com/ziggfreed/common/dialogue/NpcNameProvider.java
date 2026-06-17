package com.ziggfreed.common.dialogue;

import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * Resolves the dialogue page's name-header {@link Message} for the context id
 * (an NPC the player talks through), or null to leave the header blank. The
 * consumer supplies its own name localization; the default never shows a name.
 */
@FunctionalInterface
public interface NpcNameProvider {

    @Nullable Message nameFor(@Nullable String contextId);

    /** MVP default: never shows a name. */
    NpcNameProvider NONE = contextId -> null;
}
