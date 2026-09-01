package com.ziggfreed.common.objectives.dialogue;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.dialogue.DialogueContext;
import com.ziggfreed.common.dialogue.DialogueEngine;
import com.ziggfreed.common.dialogue.state.DialogueFlagStore;
import com.ziggfreed.common.dialogue.schema.DialogueNode;
import com.ziggfreed.common.dialogue.schema.DialogueOption;
import com.ziggfreed.common.dialogue.state.InMemoryDialogueFlagStore;
import com.ziggfreed.common.dialogue.schema.NpcDialogue;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.factor.HytaleFactors;

/**
 * The library's OWN dialogue factor vocabulary, wired the way {@code DialogueBootstrap} wires it at
 * library setup: the portable {@code hytale:} standard library installed into the shared engine's
 * one factor slot. The install lives in this module for the reason the test does: it is the layer
 * that sees both the dialogue engine and the entity module owning the standard library.
 *
 * <p>What is pinned is the bare-server story: with NO consumer mod installed, a pack's
 * {@code Factor} condition on a {@code hytale:} id must be ANSWERABLE (the id is registered, so
 * the audit has nothing to report) while still failing closed when there is no live entity to
 * read - hidden content, never an offer the server cannot back up.
 */
class DialogueFactorSlotTest {

    @BeforeEach
    void installTheRootVocabulary() {
        DialogueEngine.resetSharedForTests();
        // The wiring root's own three lines: a "dialogue" registry, the standard library, the slot.
        FactorRegistry registry = new FactorRegistry("dialogue");
        HytaleFactors.registerInto(registry, "ziggfreed-common");
        DialogueEngine.installFactors("ziggfreed-common", registry);
    }

    @AfterEach
    void clearSharedEngine() {
        DialogueEngine.resetSharedForTests();
    }

    @Test
    void theSharedEngineCarriesThePortableVocabulary() {
        FactorRegistry factors = DialogueEngine.shared().factors();
        assertNotNull(factors, "without a registry every Factor condition fails closed");
        assertTrue(factors.isRegistered(HytaleFactors.STAT),
                "a pack must be able to gate a line on a native stat channel with no Java");
        assertTrue(factors.isRegistered(HytaleFactors.HELD_ITEM));
        assertTrue(factors.isRegistered(HytaleFactors.HELD_TAG));
    }

    @Test
    void aPortableFactorWithNoLiveSubjectHidesTheOption() {
        DialogueEngine engine = DialogueEngine.shared();
        NpcDialogue d = engine.decode("factor_slot_test_dialogue",
                "{\"Nodes\":{\"n\":{\"Options\":[{\"LabelKey\":\"d.n.opt.gated\",\"Conditions\":["
                        + "{\"Type\":\"Factor\",\"Factor\":\"" + HytaleFactors.STAT
                        + "\",\"Param\":\"Health\",\"Min\":1}]}]}}}");
        assertNotNull(d);
        DialogueNode node = d.getNode("n");
        assertNotNull(node);
        DialogueOption option = node.getOptions().get(0);
        assertFalse(engine.optionAvailable(d, "n", option, ctx(d)),
                "a registered factor with nobody to read must close the gate, not open it");
    }

    /** A minimal eval context: no live entity behind it, session-scoped flags. */
    @Nonnull
    private static DialogueContext ctx(@Nullable NpcDialogue dialogue) {
        DialogueFlagStore flags = InMemoryDialogueFlagStore.forPlayer(UUID.randomUUID());
        return new DialogueContext() {
            @Override public Store<EntityStore> store() { return null; }
            @Override public Ref<EntityStore> ref() { return null; }
            @Override public PlayerRef playerRef() { return null; }
            @Override public Player player() { return null; }
            @Override @Nullable public String contextId() { return null; }
            @Override @Nullable public NpcDialogue dialogue() { return dialogue; }
            @Override public DialogueFlagStore flags() { return flags; }
            @Override @Nullable public <T> T payload(@Nonnull Class<T> type) { return null; }
        };
    }
}
