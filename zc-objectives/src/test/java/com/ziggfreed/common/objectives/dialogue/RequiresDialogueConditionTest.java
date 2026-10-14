package com.ziggfreed.common.objectives.dialogue;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.schema.config.Schema;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.commerce.asset.WhereAxis;
import com.ziggfreed.common.dialogue.DialogueContext;
import com.ziggfreed.common.dialogue.DialogueEngine;
import com.ziggfreed.common.dialogue.schema.DialogueTypeTable;
import com.ziggfreed.common.dialogue.schema.NpcDialogue;
import com.ziggfreed.common.dialogue.state.DialogueFlagStore;
import com.ziggfreed.common.dialogue.state.InMemoryDialogueFlagStore;
import com.ziggfreed.common.dialogue.type.DialogueCondition;
import com.ziggfreed.common.factor.FactorContributions;
import com.ziggfreed.common.objectives.dialogue.HasActiveBountiesDialogueCondition.HasActiveBounties;
import com.ziggfreed.common.objectives.dialogue.RequiresDialogueCondition.Gate;
import com.ziggfreed.common.objectives.dialogue.RequiresDialogueCondition.Requires;
import com.ziggfreed.common.objectives.runtime.ProgressionDefaults;
import com.ziggfreed.common.progress.runtime.ProgressionGates;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.subject.Subject;

/**
 * The library's own dialogue conditions on a server where no consumer registered any: {@code Requires}
 * reads and answers a factor block through the server's one evaluator, its deprecated {@code Gate} spelling
 * reads to the same answer, and {@code HasActiveBounties} reads and answers no where no board stands. The
 * library's own setup registers all three, and each codec's schema is one the Asset Editor can take.
 */
class RequiresDialogueConditionTest {

    /** A reading only this test contributes, so its answer is known: 3. Left registered, like any contribution. */
    private static final String READING = "zcrequirestest:reading";

    private static final Subject PLAYER = Subject.of(UUID.randomUUID(), "tester");

    @BeforeEach
    void setUp() {
        resetEverything();
        // The library's own setup halves, as its plugin runs them: the progression defaults (the factor
        // vocabulary a Requires block is answered with) and the dialogue vocabulary.
        ProgressionDefaults.register();
        FactorContributions.register(READING, "requires-test", ctx -> 3.0);
        DialogueBootstrap.registerDialogueVocabulary();
    }

    @AfterEach
    void tearDown() {
        resetEverything();
    }

    private static void resetEverything() {
        ProgressionRuntime.resetForTests();
        ProgressionDefaults.reset();
        ProgressionGates.resetForTests();
        DialogueTypeTable.get().resetForTests();
        DialogueEngine.resetSharedForTests();
    }

    @Test
    void requiresAndItsDeprecatedGateSpellingReadAndAnswerTheSameBlock() {
        NpcDialogue dialogue = decode(
                "{\"Type\": \"Requires\", \"Requires\": " + block(2) + "}",
                "{\"Type\": \"Gate\", \"Requirements\": " + block(2) + "}",
                "{\"Type\": \"Requires\", \"Requires\": " + block(5) + "}",
                "{\"Type\": \"Gate\", \"Requirements\": " + block(5) + "}",
                "{\"Type\": \"Requires\"}");

        Requires met = assertInstanceOf(Requires.class, condition(dialogue, 0));
        Gate metAsGate = assertInstanceOf(Gate.class, condition(dialogue, 1));
        Requires unmet = assertInstanceOf(Requires.class, condition(dialogue, 2));
        Gate unmetAsGate = assertInstanceOf(Gate.class, condition(dialogue, 3));
        Requires open = assertInstanceOf(Requires.class, condition(dialogue, 4));

        assertTrue(RequiresDialogueCondition.passes(met.getRequires(), PLAYER), "a reading of 3 meets Min 2");
        assertTrue(RequiresDialogueCondition.passes(metAsGate.getRequires(), PLAYER),
                "and the Gate spelling reads the same block to the same answer");
        assertFalse(RequiresDialogueCondition.passes(unmet.getRequires(), PLAYER), "a reading of 3 falls short of Min 5");
        assertFalse(RequiresDialogueCondition.passes(unmetAsGate.getRequires(), PLAYER), "in both spellings");
        assertTrue(open.getRequires().isEmpty(), "an unauthored block asks for nothing");
        assertTrue(RequiresDialogueCondition.passes(open.getRequires(), null), "so it passes with nobody to ask");
        assertFalse(RequiresDialogueCondition.passes(met.getRequires(), null),
                "while a block that asks for something refuses a missing player");
    }

    @Test
    void throughTheEngineALineThatAsksForSomethingHidesFromAContextWithNoPlayer() {
        NpcDialogue dialogue = decode(
                "{\"Type\": \"Requires\", \"Requires\": " + block(2) + "}",
                "{\"Type\": \"Gate\", \"Requirements\": " + block(2) + "}",
                "{\"Type\": \"Requires\"}",
                "{\"Type\": \"HasActiveBounties\"}");
        DialogueContext nobody = nobody(dialogue);
        DialogueEngine engine = DialogueEngine.shared();

        assertFalse(engine.conditionsPass(List.of(condition(dialogue, 0)), nobody), "the library answers it, closed");
        assertFalse(engine.conditionsPass(List.of(condition(dialogue, 1)), nobody), "the Gate spelling too");
        assertTrue(engine.conditionsPass(List.of(condition(dialogue, 2)), nobody), "an open block shows the line");
        assertFalse(engine.conditionsPass(List.of(condition(dialogue, 3)), nobody), "and with nobody to draw for, no work");
    }

    @Test
    void hasActiveBountiesReadsAndAnswersNoWhereNoBoardStands() {
        NpcDialogue dialogue = decode("{\"Type\": \"HasActiveBounties\"}");
        assertInstanceOf(HasActiveBounties.class, condition(dialogue, 0));

        long now = System.currentTimeMillis();
        assertFalse(HasActiveBountiesDialogueCondition.anyPosted(PLAYER, WhereAxis.viewer((World) null), now),
                "no board on this server, so there is no work to point at");
        assertFalse(HasActiveBountiesDialogueCondition.anyPosted(null, WhereAxis.viewer((World) null), now),
                "and nobody to draw for answers no");
    }

    @Test
    void eachCodecsSchemaIsOneTheEditorCanTake() {
        assertNotNull(Requires.CODEC.toSchema(new SchemaContext()).getProperties().get("Requires").getMarkdownDescription());
        assertNotNull(Gate.CODEC.toSchema(new SchemaContext()).getProperties().get("Requirements").getMarkdownDescription());
        assertNotNull(Schema.CODEC.encode(Requires.CODEC.toSchema(new SchemaContext()), new ExtraInfo()));
        assertNotNull(Schema.CODEC.encode(Gate.CODEC.toSchema(new SchemaContext()), new ExtraInfo()));
        assertNotNull(Schema.CODEC.encode(HasActiveBounties.CODEC.toSchema(new SchemaContext()), new ExtraInfo()));
    }

    // ==================== helpers ====================

    /** A Requires block asking for at least {@code min} of this test's reading. */
    @Nonnull
    private static String block(int min) {
        return "{\"Factors\": [ {\"Factor\": \"" + READING + "\", \"Min\": " + min + "} ]}";
    }

    /** A conversation with one screen whose line {@code i} is gated on {@code conditions[i]} alone. */
    @Nonnull
    private static NpcDialogue decode(@Nonnull String... conditions) {
        StringBuilder options = new StringBuilder();
        for (int i = 0; i < conditions.length; i++) {
            options.append(i == 0 ? "" : ",").append("{\"LabelKey\": \"t.n.").append(i)
                    .append("\", \"Conditions\": [").append(conditions[i]).append("]}");
        }
        NpcDialogue dialogue = DialogueEngine.shared().decode("requires_test",
                "{\"Nodes\": {\"n\": {\"Options\": [" + options + "]}}}");
        assertNotNull(dialogue, "the library's own vocabulary reads it");
        return dialogue;
    }

    @Nonnull
    private static DialogueCondition condition(@Nonnull NpcDialogue dialogue, int option) {
        List<DialogueCondition> conditions = dialogue.getNode("n").getOptions().get(option).getConditions();
        return conditions.get(0);
    }

    /** A render whose context names no player, as a reference that went stale reads. */
    @Nonnull
    private static DialogueContext nobody(@Nonnull NpcDialogue dialogue) {
        DialogueFlagStore flags = InMemoryDialogueFlagStore.forPlayer(UUID.randomUUID());
        return new DialogueContext() {
            @Override public Store<EntityStore> store() { return null; }
            @Override public Ref<EntityStore> ref() { return null; }
            @Override @Nullable public PlayerRef playerRef() { return null; }
            @Override public Player player() { return null; }
            @Override @Nullable public String contextId() { return null; }
            @Override @Nullable public NpcDialogue dialogue() { return dialogue; }
            @Override public DialogueFlagStore flags() { return flags; }
            @Override @Nullable public <T> T payload(@Nonnull Class<T> type) { return null; }
        };
    }
}
