package com.ziggfreed.common.dialogue;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Pure-logic coverage for {@link NpcNameProvider}'s three-arg default method: it exists so a
 * page can hand a provider a live NPC ref without breaking every existing single-arg
 * implementation (the interface stays {@code @FunctionalInterface}-compatible as source). Uses
 * {@code assertSame} throughout rather than {@code assertEquals}, because {@link Message} carries
 * no value-based {@code equals}.
 */
class NpcNameProviderTest {

    private static final Message STATIC_ANSWER = Message.raw("static");
    private static final Message LIVE_ANSWER = Message.raw("live");

    /** A single-arg-only implementation (a lambda) still answers through the three-arg call. */
    @Test
    void singleArgImplementationForwardsThroughTheDefault() {
        NpcNameProvider byIdOnly = contextId -> contextId == null ? null : STATIC_ANSWER;

        assertSame(STATIC_ANSWER, byIdOnly.nameFor("guide", null, null));
    }

    /** The default forwards a null id through unchanged, matching the single-arg contract. */
    @Test
    void singleArgImplementationDefaultStillAnswersNullForNullId() {
        NpcNameProvider byIdOnly = contextId -> contextId == null ? null : STATIC_ANSWER;

        assertNull(byIdOnly.nameFor(null, null, null));
    }

    /**
     * A provider that DOES override the three-arg form answers from it, not from the default
     * that would otherwise forward to the single-arg static walk.
     */
    @Test
    void overriddenThreeArgFormIsUsedInsteadOfTheDefault() {
        NpcNameProvider entityAware = new NpcNameProvider() {
            @Override
            @Nullable
            public Message nameFor(@Nullable String contextId) {
                return STATIC_ANSWER;
            }

            @Override
            @Nullable
            public Message nameFor(@Nullable String contextId, @Nullable Ref<EntityStore> npcRef,
                    @Nullable Store<EntityStore> store) {
                return LIVE_ANSWER;
            }
        };

        // The single-arg call still answers the static form...
        assertSame(STATIC_ANSWER, entityAware.nameFor("guide"));
        // ...but the three-arg call reaches the override, not the default's forward to it.
        assertSame(LIVE_ANSWER, entityAware.nameFor("guide", null, null));
    }

    /** {@link NpcNameProvider#NONE} answers null through both arities. */
    @Test
    void noneAnswersNullThroughBothArities() {
        assertNull(NpcNameProvider.NONE.nameFor("guide"));
        assertNull(NpcNameProvider.NONE.nameFor("guide", null, null));
    }
}
