package com.ziggfreed.common.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.dialogue.page.DialoguePageDeps;

/**
 * Which mod's page dependencies a press-F route resolves, and who is on record as having said so.
 *
 * <p>The suppliers here are never invoked - a {@link DialoguePageDeps} needs a live engine and this
 * registry only ever hands the supplier back - so what is asserted is resolution and ATTRIBUTION:
 * an authored {@code DepsKey} reaches the consumer that claimed that key, the un-keyed default is a
 * genuine last-writer-wins slot, and the ledger records which mod holds each one.
 */
class NpcDialogueDepsRegistryTest {

    /** Distinct instances standing in for two mods' {@code deps()} providers. */
    private static final Supplier<DialoguePageDeps> MOD_A = () -> null;
    private static final Supplier<DialoguePageDeps> MOD_B = () -> null;

    @BeforeEach
    @AfterEach
    void freshRegistry() {
        NpcDialogueDepsRegistry.resetForTests();
    }

    @Test
    void nothingRegisteredResolvesToNull() {
        assertNull(NpcDialogueDepsRegistry.get());
        assertNull(NpcDialogueDepsRegistry.get("whatever"));
        assertTrue(NpcDialogueDepsRegistry.info().isEmpty());
    }

    @Test
    void theDefaultProviderAnswersEveryUnkeyedRoute() {
        NpcDialogueDepsRegistry.setDefault("ModA", MOD_A);

        assertSame(MOD_A, NpcDialogueDepsRegistry.get());
        assertSame(MOD_A, NpcDialogueDepsRegistry.get(null),
                "an action with no authored DepsKey resolves the default");
        assertSame(MOD_A, NpcDialogueDepsRegistry.get("   "), "and so does a blank one");
        assertEquals("ModA", NpcDialogueDepsRegistry.info()
                .get(NpcDialogueDepsRegistry.DEFAULT_KEY).owner());
    }

    @Test
    void anAuthoredKeyReachesTheConsumerThatClaimedItRegardlessOfCasing() {
        NpcDialogueDepsRegistry.setDefault("ModA", MOD_A);
        NpcDialogueDepsRegistry.set("KweebecGuide", "ModB", MOD_B);

        assertSame(MOD_B, NpcDialogueDepsRegistry.get("kweebecguide"));
        assertSame(MOD_B, NpcDialogueDepsRegistry.get("  KWEEBECGUIDE  "),
                "author casing and stray whitespace must not decide which mod answers");
        assertSame(MOD_A, NpcDialogueDepsRegistry.get(),
                "and claiming a named key must not disturb the default");
    }

    @Test
    void twoModsOnTheUnkeyedDefaultIsLastWriterWinsAndTheLedgerSaysWho() {
        NpcDialogueDepsRegistry.setDefault("ModA", MOD_A);
        NpcDialogueDepsRegistry.setDefault("ModB", MOD_B);

        assertSame(MOD_B, NpcDialogueDepsRegistry.get(),
                "the later registration takes the slot, which is what the boot line warns about");
        assertEquals("ModB", NpcDialogueDepsRegistry.info()
                        .get(NpcDialogueDepsRegistry.DEFAULT_KEY).owner(),
                "and a server owner can read back which mod actually holds it");
    }

    @Test
    void aModReRunningItsOwnSetupKeepsItsAttribution() {
        NpcDialogueDepsRegistry.setDefault("ModA", MOD_A);
        NpcDialogueDepsRegistry.setDefault("ModA", MOD_A);

        assertSame(MOD_A, NpcDialogueDepsRegistry.get());
        assertEquals("ModA", NpcDialogueDepsRegistry.info()
                .get(NpcDialogueDepsRegistry.DEFAULT_KEY).owner());
    }
}
