package com.ziggfreed.common.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleSupplier;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.dialogue.schema.NpcDialogue;
import com.ziggfreed.common.dialogue.state.DialogueStateKeys;
import com.ziggfreed.common.dialogue.validate.DialogueStructureValidator;
import com.ziggfreed.common.npc.NpcDestinations;
import com.ziggfreed.common.quest.NpcOfferProviders;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.ui.route.Destination;

/**
 * WHICH screen a conversation opens on: the declared sections, the fixed ladder the engine walks
 * them in, and the two things a beat can do that a flat list could not - draw between screens, and
 * send the player somewhere else entirely.
 *
 * <p>Everything here is the pure decision core. The quest bands are answered through the same two
 * seams a quest-aware line reads ({@link TestQuests}), the draw reads an injected number rather than
 * a real random, and no server is involved anywhere.
 */
class DialogueStartTest {

    @BeforeEach
    @AfterEach
    void reset() {
        DialogueTestSupport.reset();
        NpcOfferProviders.clear();
    }

    // ==================== the ladder ====================

    /**
     * The whole point of the sections: an author writes each beat where it belongs and never orders
     * one against another. This walks the ladder from the top down by taking each rung away in turn.
     */
    @Test
    void theEngineWalksFirstThenReadyThenOfferableThenActiveThenThenThenFallback() {
        TestQuests quests = new TestQuests();
        DialogueEngine engine = engine(quests);
        NpcDialogue d = ladder(engine);
        TestDialogueContext ctx = new TestDialogueContext(d).talkingTo("guide");

        // Everything applies at once: First wins.
        quests.deliverableAt.add("errand@guide");
        quests.offer("side_job");
        quests.status.put("main", QuestStatus.ACTIVE);
        TestDialogueContext gated = new TestDialogueContext(d).talkingTo("guide");
        gated.state().set(DialogueStateKeys.memory(d.getId(), "world_beat", false));
        assertEquals("world_beat", engine.resolveEntryNodeId(d, gated));

        // First's condition fails: a quest ready to hand in here is next.
        assertEquals("ready_beat", engine.resolveEntryNodeId(d, ctx));

        // Nothing to hand in: what this character is offering.
        quests.deliverableAt.clear();
        assertEquals("offer_beat", engine.resolveEntryNodeId(d, ctx));

        // Nothing on offer: a quest the player is carrying.
        NpcOfferProviders.clear();
        assertEquals("active_beat", engine.resolveEntryNodeId(d, ctx));

        // No quest row applies at all: the Then beats.
        quests.status.clear();
        assertEquals("then_beat", engine.resolveEntryNodeId(d, ctx));

        // And with the Then beat's own condition failing, the screen of last resort.
        ctx.state().set(DialogueStateKeys.memory(d.getId(), "no_then", false));
        assertEquals("fallback_beat", engine.resolveEntryNodeId(d, ctx));
    }

    @Test
    void withinOneBandTheOrderTheRowsAreWrittenDecides() {
        TestQuests quests = new TestQuests();
        DialogueEngine engine = engine(quests);
        NpcDialogue d = decode(engine, """
                { "Start": { "Quests": {
                      "first_quest":  { "Active": "a" },
                      "second_quest": { "Active": "b" } },
                    "Fallback": "a" },
                  "Nodes": { "a": { "Options": [] }, "b": { "Options": [] } } }
                """);
        quests.status.put("second_quest", QuestStatus.ACTIVE);
        quests.status.put("first_quest", QuestStatus.ACTIVE);

        assertEquals("a", engine.resolveEntryNodeId(d, new TestDialogueContext(d).talkingTo("guide")),
                "both rows reach the same band, so the one written first decides");
    }

    @Test
    void aQuestRowIsSkippedWhenItsScreenGatesItselfShut() {
        TestQuests quests = new TestQuests();
        DialogueEngine engine = engine(quests);
        NpcDialogue d = decode(engine, """
                { "Start": { "Quests": { "main": { "Active": "gated" } }, "Fallback": "plain" },
                  "Nodes": {
                    "gated": { "Conditions": [ { "Type": "Remembered", "Memory": "ready" } ],
                               "Options": [] },
                    "plain": { "Options": [] } },
                  "Memories": { "ready": {} } }
                """);
        quests.status.put("main", QuestStatus.ACTIVE);

        assertEquals("plain", engine.resolveEntryNodeId(d, new TestDialogueContext(d).talkingTo("guide")),
                "a screen that gates itself shut cannot be opened by the row that names it");
    }

    @Test
    void aLockedOfferIsNotSomethingToOpenOn() {
        TestQuests quests = new TestQuests();
        DialogueEngine engine = engine(quests);
        NpcDialogue d = decode(engine, """
                { "Start": { "Quests": { "side_job": { "Offerable": "offer_beat" } },
                             "Fallback": "fallback_beat" },
                  "Nodes": { "offer_beat": { "Options": [] }, "fallback_beat": { "Options": [] } } }
                """);
        quests.offerLocked("side_job");

        assertEquals("fallback_beat",
                engine.resolveEntryNodeId(d, new TestDialogueContext(d).talkingTo("guide")),
                "a quest the player can see but not take is not something to hail them about");
    }

    @Test
    void withNoStartAtAllTheFirstScreenWhoseConditionsPassOpens() {
        DialogueEngine engine = engine(new TestQuests());
        NpcDialogue d = decode(engine, """
                { "Nodes": { "only": { "Options": [] } } }
                """);

        assertEquals("only", engine.resolveEntryNodeId(d, new TestDialogueContext(d)));
    }

    // ==================== the READY rule ====================

    /**
     * Ruling 78a in one test. A ready quest changes nothing until a row says so, and the row's
     * default sends the player to this character's list with that quest called out - never a hand-in
     * the player did not choose.
     */
    @Test
    void aReadyQuestDivertsNothingUntilItsRowSaysSo() {
        TestQuests quests = new TestQuests();
        DialogueEngine engine = engine(quests);
        quests.deliverableAt.add("errand@guide");

        NpcDialogue silent = decode(engine, """
                { "Start": { "Fallback": "greet" }, "Nodes": { "greet": { "Options": [] } } }
                """);
        assertEquals("greet", engine.resolveEntryNodeId(silent, new TestDialogueContext(silent).talkingTo("guide")),
                "no row means a finished quest is not this conversation's business");
    }

    @Test
    void readyWrittenAsTrueRoutesToTheQuestListWithThatQuestHighlighted() {
        NpcDestinations.register();
        TestQuests quests = new TestQuests();
        DialogueEngine engine = engine(quests);
        quests.deliverableAt.add("errand@guide");
        NpcDialogue d = decode(engine, """
                { "Start": { "Quests": { "errand": { "Ready": true } }, "Fallback": "greet" },
                  "Nodes": { "greet": { "Options": [] } } }
                """);

        DialogueEngine.EntryResolution entry =
                engine.resolveEntry(d, new TestDialogueContext(d).talkingTo("guide"));

        assertTrue(entry.routes(), "the conversation hands the screen over rather than opening");
        assertNull(entry.nodeId());
        assertNull(entry.onceKey(), "there is no beat to complete, so there is nothing to spend");
        Destination destination = entry.destination();
        assertNotNull(destination);
        assertTrue(destination instanceof NpcDestinations.Quests);
        assertEquals("errand", ((NpcDestinations.Quests) destination).getHighlight(),
                "the row's own quest is the one the list opens on");
        assertNull(((NpcDestinations.Quests) destination).getNpc(),
                "the character is the one the player is standing at, never spelled a second time");
    }

    @Test
    void anAuthoredQuestListDestinationIsHighlightedByTheRowThatFiredIt() {
        NpcDestinations.register();
        TestQuests quests = new TestQuests();
        DialogueEngine engine = engine(quests);
        quests.deliverableAt.add("errand@guide");
        NpcDialogue d = decode(engine, """
                { "Start": { "Quests": {
                      "errand": { "Ready": { "Type": "Quests", "Npc": "quartermaster" } } },
                    "Fallback": "greet" },
                  "Nodes": { "greet": { "Options": [] } } }
                """);

        Destination destination = engine.resolveEntry(d,
                new TestDialogueContext(d).talkingTo("guide")).destination();

        assertNotNull(destination);
        assertEquals("quartermaster", ((NpcDestinations.Quests) destination).getNpc());
        assertEquals("errand", ((NpcDestinations.Quests) destination).getHighlight());
    }

    @Test
    void withNothingInstalledToOpenAQuestListTheRowIsSkippedRatherThanDeadEnding() {
        TestQuests quests = new TestQuests();
        DialogueEngine engine = engine(quests);
        quests.deliverableAt.add("errand@guide");
        NpcDialogue d = decode(engine, """
                { "Start": { "Quests": { "errand": { "Ready": true } }, "Fallback": "greet" },
                  "Nodes": { "greet": { "Options": [] } } }
                """);

        // Nothing registered the quest-list router (the library installs it at setup), so the beat
        // cannot fire - and the ladder carries on instead of leaving the player with nothing.
        assertEquals("greet", engine.resolveEntryNodeId(d, new TestDialogueContext(d).talkingTo("guide")));
    }

    // ==================== the draw ====================

    @Test
    void aPickDrawsByWeightAndTheDrawIsWhereTheNumberSaysItIs() {
        NpcDialogue d = decode(engine(new TestQuests()), """
                { "Start": { "Then": [ { "Pick": [
                      { "Node": "a" },
                      { "Node": "b", "Weight": 3 } ] } ] },
                  "Nodes": { "a": { "Options": [] }, "b": { "Options": [] } } }
                """);

        // Total weight 4: the first quarter is "a", the rest is "b".
        assertEquals("a", drawWith(0.0, d));
        assertEquals("a", drawWith(0.2, d));
        assertEquals("b", drawWith(0.25, d));
        assertEquals("b", drawWith(0.99, d));
    }

    @Test
    void aWeightOfZeroTakesAVariantOutOfTheDrawWithoutDeletingIt() {
        NpcDialogue d = decode(engine(new TestQuests()), """
                { "Start": { "Then": [ { "Pick": [
                      { "Node": "parked", "Weight": 0 },
                      { "Node": "live" } ] } ] },
                  "Nodes": { "parked": { "Options": [] }, "live": { "Options": [] } } }
                """);

        assertEquals("live", drawWith(0.0, d));
        assertEquals("live", drawWith(0.999, d));
    }

    @Test
    void aBeatWhoseEveryVariantIsOutDoesNotFireAtAll() {
        NpcDialogue d = decode(engine(new TestQuests()), """
                { "Start": { "Then": [ { "Pick": [ { "Node": "parked", "Weight": 0 } ] } ],
                             "Fallback": "plain" },
                  "Nodes": { "parked": { "Options": [] }, "plain": { "Options": [] } } }
                """);

        assertEquals("plain", drawWith(0.5, d));
    }

    @Test
    void aVariantNamingAScreenThatIsNotThereIsDroppedRatherThanDrawn() {
        NpcDialogue d = decode(engine(new TestQuests()), """
                { "Start": { "Then": [ { "Pick": [
                      { "Node": "gone", "Weight": 99 },
                      { "Node": "here" } ] } ] },
                  "Nodes": { "here": { "Options": [] } } }
                """);

        assertEquals("here", drawWith(0.5, d),
                "a dead variant would otherwise swallow most of the draws in silence");
    }

    @Test
    void aWeightWrittenAsAFormulaIsTheSameValueAsANumber() {
        NpcDialogue d = decode(engine(new TestQuests()), """
                { "Start": { "Then": [ { "Pick": [
                      { "Node": "a", "Weight": { "Base": 1 } },
                      { "Node": "b", "Weight": { "Base": 3 } } ] } ] },
                  "Nodes": { "a": { "Options": [] }, "b": { "Options": [] } } }
                """);

        assertEquals("a", drawWith(0.2, d));
        assertEquals("b", drawWith(0.3, d));
    }

    // ==================== Once ====================

    @Test
    void aFirstBeatKeepsItsOnceSemanticsExactly() {
        DialogueEngine engine = engine(new TestQuests());
        NpcDialogue d = decode(engine, """
                { "Start": { "First": [ { "Node": "hello", "Once": true } ], "Fallback": "greet" },
                  "Nodes": { "hello": { "Options": [] }, "greet": { "Options": [] } } }
                """);
        TestDialogueContext ctx = new TestDialogueContext(d);

        DialogueEngine.EntryResolution entry = engine.resolveEntry(d, ctx);
        assertEquals("hello", entry.nodeId());
        assertNotNull(entry.onceKey(), "the key is pending, not spent");
        assertEquals("hello", engine.resolveEntryNodeId(d, ctx),
                "leaving without finishing the beat shows it again");

        engine.consumeOnce(entry.onceKey(), d, "hello", null, ctx);
        assertEquals("greet", engine.resolveEntryNodeId(d, ctx), "finishing it retires the beat");
    }

    // ==================== the retired list form ====================

    @Test
    void aFileStillWritingTheOldListFormIsToldWhatToWriteInstead() {
        DialogueEngine engine = engine(new TestQuests());
        List<String> warnings = new ArrayList<>();
        DialogueEngine loud = DialogueEngine.builder().warn(warnings::add).build();
        assertNotNull(engine);

        assertNull(loud.decode("legacy",
                "{\"Start\":[{\"Node\":\"g\"}],\"Nodes\":{\"g\":{\"Options\":[]}}}"));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("First"), warnings.toString());
        assertTrue(warnings.get(0).contains("Fallback"), warnings.toString());
    }

    // ==================== the audit ====================

    @Test
    void authoringBothNodeAndPickIsAnError() {
        NpcDialogue d = decode(engine(new TestQuests()), """
                { "Start": { "First": [ { "Node": "a", "Pick": [ { "Node": "b" } ] } ],
                             "Fallback": "a" },
                  "Nodes": { "a": { "Options": [] }, "b": { "Options": [] } } }
                """);

        assertTrue(codes(d).contains("START_NODE_AND_PICK"), codes(d).toString());
    }

    @Test
    void anEmptyPickIsAnError() {
        NpcDialogue d = decode(engine(new TestQuests()), """
                { "Start": { "Then": [ { "Pick": [] } ], "Fallback": "a" },
                  "Nodes": { "a": { "Options": [] } } }
                """);

        assertTrue(codes(d).contains("START_PICK_EMPTY"), codes(d).toString());
    }

    @Test
    void aQuestRowOrAFallbackNamingAMissingScreenJoinsTheExistingFamily() {
        NpcDialogue rowMiss = decode(engine(new TestQuests()), """
                { "Start": { "Quests": { "main": { "Active": "gone" } }, "Fallback": "a" },
                  "Nodes": { "a": { "Options": [] } } }
                """);
        assertTrue(codes(rowMiss).contains("START_MISSING_NODE"), codes(rowMiss).toString());

        NpcDialogue fallbackMiss = decode(engine(new TestQuests()), """
                { "Start": { "Then": [ { "Node": "a" } ], "Fallback": "gone" },
                  "Nodes": { "a": { "Options": [] } } }
                """);
        assertTrue(codes(fallbackMiss).contains("START_MISSING_NODE"), codes(fallbackMiss).toString());
    }

    @Test
    void aStartWithNoSectionsAtAllReadsAsSayingNothing() {
        NpcDialogue d = decode(engine(new TestQuests()), """
                { "Start": { }, "Nodes": { "a": { "Options": [] } } }
                """);

        assertTrue(codes(d).contains("MISSING_START"), codes(d).toString());
        assertFalse(codes(d).contains("UNREACHABLE_NODE"),
                "the screen it falls back to is still reachable");
    }

    @Test
    void aScreenOnlyAQuestRowOrADrawOpensIsNotReportedAsUnreachable() {
        NpcDialogue d = decode(engine(new TestQuests()), """
                { "Start": { "Quests": { "main": { "Active": "by_row" } },
                             "Then": [ { "Pick": [ { "Node": "by_draw" } ] } ],
                             "Fallback": "plain" },
                  "Nodes": { "by_row": { "Options": [] }, "by_draw": { "Options": [] },
                             "plain": { "Options": [] } } }
                """);

        assertFalse(codes(d).contains("UNREACHABLE_NODE"), codes(d).toString());
    }

    // ==================== helpers ====================

    @Nonnull
    private static List<String> codes(@Nonnull NpcDialogue dialogue) {
        return DialogueTestSupport.codes(DialogueStructureValidator.validate(dialogue));
    }

    /** Resolve the entry with the draw pinned to one number. */
    @Nonnull
    private static String drawWith(double roll, @Nonnull NpcDialogue dialogue) {
        DialogueEngine engine = DialogueEngine.builder().warn(m -> { })
                .random(fixed(roll)).quests(new TestQuests()).build();
        String node = engine.resolveEntryNodeId(dialogue, new TestDialogueContext(dialogue));
        assertNotNull(node);
        return node;
    }

    @Nonnull
    private static DoubleSupplier fixed(double value) {
        return () -> value;
    }

    @Nonnull
    private static DialogueEngine engine(@Nonnull TestQuests quests) {
        return DialogueEngine.builder().warn(m -> { }).quests(quests).build();
    }

    @Nonnull
    private static NpcDialogue decode(@Nonnull DialogueEngine engine, @Nonnull String json) {
        NpcDialogue d = engine.decode("guide", json);
        assertNotNull(d, "the conversation must decode");
        return d;
    }

    /** One conversation carrying a beat in every section, for walking the ladder rung by rung. */
    @Nonnull
    private static NpcDialogue ladder(@Nonnull DialogueEngine engine) {
        return decode(engine, """
                { "Memories": { "world_beat": {}, "no_then": {} },
                  "Start": {
                    "First": [ { "Node": "world_beat",
                                 "When": [ { "Type": "Remembered", "Memory": "world_beat" } ] } ],
                    "Quests": {
                      "errand":   { "Ready": "ready_beat" },
                      "side_job": { "Offerable": "offer_beat" },
                      "main":     { "Active": "active_beat" } },
                    "Then": [ { "Node": "then_beat",
                                "When": [ { "Type": "NotRemembered", "Memory": "no_then" } ] } ],
                    "Fallback": "fallback_beat" },
                  "Nodes": {
                    "world_beat":    { "Options": [] },
                    "ready_beat":    { "Options": [] },
                    "offer_beat":    { "Options": [] },
                    "active_beat":   { "Options": [] },
                    "then_beat":     { "Options": [] },
                    "fallback_beat": { "Options": [] } } }
                """);
    }
}
