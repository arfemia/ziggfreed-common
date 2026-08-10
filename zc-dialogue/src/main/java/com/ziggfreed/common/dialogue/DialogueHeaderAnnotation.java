package com.ziggfreed.common.dialogue;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Optional one-line annotation shown under the name header (e.g. an MMO's active
 * objective hint). Returns the fully-framed {@link Message} to show, or null to
 * keep the annotation row hidden. The default shows nothing, so a minimal
 * consumer gets full visual parity minus the annotation.
 */
@FunctionalInterface
public interface DialogueHeaderAnnotation {

    @Nullable Message annotationFor(@Nullable String contextId,
                                    @Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store);

    /** MVP default: the annotation row stays hidden. */
    DialogueHeaderAnnotation NONE = (contextId, ref, store) -> null;
}
