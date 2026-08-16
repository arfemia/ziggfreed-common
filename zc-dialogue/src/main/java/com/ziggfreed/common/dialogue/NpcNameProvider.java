package com.ziggfreed.common.dialogue;

import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Resolves the dialogue page's name-header {@link Message} for the context id
 * (an NPC the player talks through), or null to leave the header blank. The
 * consumer supplies its own name localization; the default never shows a name.
 */
@FunctionalInterface
public interface NpcNameProvider {

    @Nullable Message nameFor(@Nullable String contextId);

    /**
     * The same header name, answered with a LIVE entity in hand when the page has one. A
     * provider that can only work from the id (every existing single-arg implementation) needs
     * no change: the default forwards to {@link #nameFor(String)} and ignores the entity. A
     * provider that CAN read a live entity - the library default among them - overrides this
     * method instead, so the header answers from the character actually standing there rather
     * than a static walk of its role.
     */
    @Nullable
    default Message nameFor(@Nullable String contextId, @Nullable Ref<EntityStore> npcRef,
            @Nullable Store<EntityStore> store) {
        return nameFor(contextId);
    }

    /** MVP default: never shows a name. */
    NpcNameProvider NONE = contextId -> null;
}
