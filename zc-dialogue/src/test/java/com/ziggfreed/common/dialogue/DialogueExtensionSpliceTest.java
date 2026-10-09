package com.ziggfreed.common.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.dialogue.schema.DialogueExtension;
import com.ziggfreed.common.dialogue.schema.DialogueExtensionConfig;
import com.ziggfreed.common.dialogue.schema.DialogueNode;
import com.ziggfreed.common.dialogue.schema.DialogueOption;
import com.ziggfreed.common.dialogue.schema.DialogueSelector;
import com.ziggfreed.common.dialogue.schema.NodeSelector;
import com.ziggfreed.common.dialogue.schema.NpcDialogue;

/**
 * Extension lines in real conversations: where they land, in what order, whose {@code Once} they
 * spend, how a child conversation and its parent each get their own, and that a splice repeated or
 * re-run after the extension left is exact.
 */
class DialogueExtensionSpliceTest {

    private static final String GUIDE = """
            { "Start": { "First": [ { "Node": "intro", "Once": true } ], "Fallback": "menu" },
              "Fragments": { "pointers": { "On": { "Tags": ["steady"] }, "Options": [ { "LabelKey": "p" } ] },
                             "footer": [ { "LabelKey": "f", "Close": true } ] },
              "Nodes": { "intro": { "Options": [ { "LabelKey": "i", "Goto": "menu" } ] },
                         "menu":  { "Options": [ { "LabelKey": "a" } ], "Tags": ["steady"],
                                    "IncludeOptions": ["footer"] },
                         "deep":  { "Options": [ { "LabelKey": "d" } ] } } }
            """;

    private static final String SMITH = """
            { "Nodes": { "shop":   { "Options": [ { "LabelKey": "s" } ] },
                         "haggle": { "Options": [ { "LabelKey": "h" } ] } } }
            """;

    private final long[] now = {Instant.parse("2026-10-31T12:00:00Z").toEpochMilli()};

    @BeforeEach
    void resetDialogueTypes() {
        DialogueTestSupport.reset();
    }

    /**
     * Leave no extension installed: the suite shares one JVM, and a test class that never resets
     * would otherwise find these lines spliced into its own conversations' opening screens.
     */
    @AfterEach
    void leaveNoExtensionInstalled() {
        DialogueExtensionConfig.getInstance().mergePackLayer(Map.of());
    }

    @Nonnull
    private DialogueEngine engine() {
        return DialogueEngine.builder().warn(m -> { }).clock(() -> now[0]).build();
    }

    @Nonnull
    private static DialogueExtension trick(@Nonnull String id, @Nullable DialogueSelector dialogues,
                                           @Nullable NodeSelector on) {
        return DialogueExtension.of(id, DialogueTestSupport.optionRows("""
                [ { "LabelKey": "hallows_eve.trick", "OnceId": "treat", "Once": { "Period": "Daily" } } ]
                """), dialogues, on, true);
    }

    @Nonnull
    private static List<String> labels(@Nonnull NpcDialogue d, @Nonnull String node) {
        DialogueNode screen = d.getNode(node);
        assertNotNull(screen, "screen '" + node + "' must exist");
        List<String> out = new ArrayList<>();
        for (DialogueOption option : screen.getOptions()) {
            out.add(option.getLabelKey());
        }
        return out;
    }

    @Nonnull
    private static DialogueOption injected(@Nonnull NpcDialogue d, @Nonnull String node, @Nonnull String extension) {
        for (DialogueOption option : d.getNode(node).getOptions()) {
            if (extension.equals(option.getInjectedBy())) {
                return option;
            }
        }
        throw new AssertionError("no line from '" + extension + "' on '" + node + "'");
    }

    @Test
    void anExtensionLandsOnTheScreensEveryConversationOpensOn() {
        DialogueEngine engine = engine();
        DialogueTestSupport.shareExtensions(trick("hallows_eve_trick", null, null));
        NpcDialogue guide = engine.decode("guide", GUIDE);
        NpcDialogue smith = engine.decode("smith", SMITH);
        assertNotNull(guide);
        assertNotNull(smith);
        assertEquals(List.of("i", "hallows_eve.trick"), labels(guide, "intro"), "a First beat's screen");
        assertEquals(List.of("a", "p", "hallows_eve.trick", "f"), labels(guide, "menu"),
                "the Fallback screen: after its own and placed lines, before the footer it pulls in");
        assertEquals(List.of("d"), labels(guide, "deep"), "a screen reached only by a jump is left alone");
        assertEquals(List.of("s", "hallows_eve.trick"), labels(smith, "shop"), "with no Start, the first screen");
        assertEquals(List.of("h"), labels(smith, "haggle"));
    }

    @Test
    void dialoguesAndOnNarrowWhereTheLineGoes() {
        DialogueEngine engine = engine();
        DialogueTestSupport.shareExtensions(trick("hallows_eve_trick",
                DialogueSelector.of(new String[] {"Smith"}, null),
                NodeSelector.of(new String[] {"haggle"}, null, null)));
        NpcDialogue guide = engine.decode("guide", GUIDE);
        NpcDialogue smith = engine.decode("smith", SMITH);
        assertNotNull(guide);
        assertNotNull(smith);
        assertEquals(List.of("i"), labels(guide, "intro"), "a conversation Ids does not name gets nothing");
        assertEquals(List.of("s"), labels(smith, "shop"), "On replaces the opening screens");
        assertEquals(List.of("h", "hallows_eve.trick"), labels(smith, "haggle"),
                "the named screen of the named conversation, its id matched without regard to case");
    }

    @Test
    void anInjectedLinesDailyOnceIsSpentWithEveryCharacterAtOnce() {
        DialogueEngine engine = engine();
        DialogueTestSupport.shareExtensions(trick("hallows_eve_trick", null, null));
        NpcDialogue guide = engine.decode("guide", GUIDE);
        NpcDialogue smith = engine.decode("smith", SMITH);
        assertNotNull(guide);
        assertNotNull(smith);
        TestDialogueContext atGuide = new TestDialogueContext(guide);
        TestDialogueContext atSmith = new TestDialogueContext(smith, atGuide.state());

        engine.consumeOnce(null, guide, "menu", injected(guide, "menu", "hallows_eve_trick"), atGuide);
        assertEquals(Set.of("once:x:hallows_eve_trick:treat:PD" + LocalDate.of(2026, 10, 31).toEpochDay()),
                atGuide.state().keys, "keyed by the extension, not by the character it was taken at");
        assertFalse(engine.optionAvailable(smith, "shop", injected(smith, "shop", "hallows_eve_trick"), atSmith),
                "spent with the guide, so spent with the smith");
        assertFalse(engine.optionAvailable(guide, "intro", injected(guide, "intro", "hallows_eve_trick"), atGuide),
                "and on every other screen it reached");

        now[0] = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli();
        assertTrue(engine.optionAvailable(smith, "shop", injected(smith, "shop", "hallows_eve_trick"), atSmith),
                "a new day offers it everywhere again");
    }

    /** {@code PerCharacter}: the claim is filed under the character too, so each keeps its own day (M569). */
    @Test
    void aPerCharacterDailyOnceIsSpentOnlyWithTheCharacterItWasTakenAt() {
        DialogueEngine engine = engine();
        DialogueTestSupport.shareExtensions(DialogueExtension.of("hallows_eve_trick", DialogueTestSupport.optionRows("""
                [ { "LabelKey": "hallows_eve.trick", "OnceId": "treat",
                    "Once": { "Period": "Daily", "PerCharacter": true } } ]
                """), null, null, true));
        NpcDialogue guide = engine.decode("guide", GUIDE);
        NpcDialogue smith = engine.decode("smith", SMITH);
        assertNotNull(guide);
        assertNotNull(smith);
        TestDialogueContext atJack = new TestDialogueContext(guide).talkingTo("Old_Jack");
        TestDialogueContext atHub = new TestDialogueContext(guide, atJack.state()).talkingTo("Mmo_Hub");
        TestDialogueContext atSmith = new TestDialogueContext(smith, atJack.state()).talkingTo("Smith");
        long day = LocalDate.of(2026, 10, 31).toEpochDay();

        engine.consumeOnce(null, guide, "menu", injected(guide, "menu", "hallows_eve_trick"), atJack);
        assertEquals(Set.of("once:x:hallows_eve_trick:treat:c:old_jack:PD" + day), atJack.state().keys,
                "keyed by the extension and the character it was taken at");
        assertFalse(engine.optionAvailable(guide, "intro", injected(guide, "intro", "hallows_eve_trick"), atJack),
                "spent with Old Jack, on every screen of his");
        assertTrue(engine.optionAvailable(guide, "menu", injected(guide, "menu", "hallows_eve_trick"), atHub),
                "another character sharing the conversation still offers it");
        assertTrue(engine.optionAvailable(smith, "shop", injected(smith, "shop", "hallows_eve_trick"), atSmith),
                "and so does every other character");

        engine.consumeOnce(null, smith, "shop", injected(smith, "shop", "hallows_eve_trick"), atSmith);
        now[0] = Instant.parse("2026-11-01T00:00:00Z").toEpochMilli();
        assertTrue(engine.optionAvailable(guide, "menu", injected(guide, "menu", "hallows_eve_trick"), atJack),
                "a new day offers it at Old Jack again");
        engine.consumeOnce(null, guide, "menu", injected(guide, "menu", "hallows_eve_trick"), atJack);
        assertEquals(Set.of("once:x:hallows_eve_trick:treat:c:smith:PD" + day,
                "once:x:hallows_eve_trick:treat:c:old_jack:PD" + (day + 1)), atJack.state().keys,
                "a spend clears only that character's earlier day");
    }

    @Test
    void twoExtensionsSharingALabelKeepTheirOwnOnce() {
        DialogueEngine engine = engine();
        DialogueTestSupport.shareExtensions(trick("b_trick", null, null), trick("a_trick", null, null));
        NpcDialogue smith = engine.decode("smith", SMITH);
        assertNotNull(smith);
        List<String> origins = new ArrayList<>();
        for (DialogueOption option : smith.getNode("shop").getOptions()) {
            if (option.isInjected()) {
                origins.add(option.getInjectedBy());
            }
        }
        assertEquals(List.of("a_trick", "b_trick"), origins, "extension id order");
        TestDialogueContext ctx = new TestDialogueContext(smith);
        engine.consumeOnce(null, smith, "shop", injected(smith, "shop", "a_trick"), ctx);
        assertFalse(engine.optionAvailable(smith, "shop", injected(smith, "shop", "a_trick"), ctx));
        assertTrue(engine.optionAvailable(smith, "shop", injected(smith, "shop", "b_trick"), ctx),
                "the same label in another extension is another line");
    }

    @Test
    void aChildGetsLinesByItsOwnIdAndTheParentKeepsItsOwnScreens() throws Exception {
        DialogueEngine engine = engine();
        DialogueTestSupport.shareExtensions(trick("hallows_eve_trick",
                DialogueSelector.of(new String[] {"kid"}, null), null));
        NpcDialogue base = engine.decode("base", """
                { "Start": { "Fallback": "g" }, "Nodes": { "g": { "Options": [ { "LabelKey": "a" } ] } } }
                """);
        assertNotNull(base);
        NpcDialogue kid = DialogueTestSupport.decodeWithParent(engine, "kid", """
                { "Nodes": { "h": { "Options": [ { "LabelKey": "b" } ] } } }
                """, base);
        assertNotNull(kid);
        assertEquals(List.of("a", "hallows_eve.trick"), labels(kid, "g"), "the child's inherited opening screen");
        assertEquals(List.of("a"), labels(base, "g"), "the parent is not a conversation the line names");
    }

    @Test
    void aLineLeavesWhenItsExtensionDoesAndASpliceNeverStacks() {
        DialogueEngine engine = engine();
        DialogueTestSupport.shareExtensions(trick("hallows_eve_trick", null, null));
        NpcDialogue guide = engine.decode("guide", GUIDE);
        assertNotNull(guide);
        guide.spliceFragments();
        guide.spliceFragments();
        assertEquals(List.of("a", "p", "hallows_eve.trick", "f"), labels(guide, "menu"), "splicing again never stacks a line");

        DialogueTestSupport.shareExtensions();
        guide.spliceFragments();
        assertEquals(List.of("a", "p", "f"), labels(guide, "menu"), "the line goes with its extension");
        assertEquals(List.of("i"), labels(guide, "intro"), "including from a screen that pulls nothing in");
    }

    @Test
    void anInjectedLineWithNoIdentityStaysRepeatableAndNamesItsExtension() {
        List<String> warnings = new ArrayList<>();
        DialogueEngine engine = DialogueEngine.builder().warn(warnings::add).clock(() -> now[0]).build();
        DialogueTestSupport.shareExtensions(DialogueExtension.of("trick",
                DialogueTestSupport.optionRows("[ { \"Once\": true } ]"), null, null, true));
        NpcDialogue smith = engine.decode("smith", SMITH);
        assertNotNull(smith);
        TestDialogueContext ctx = new TestDialogueContext(smith);
        DialogueOption nameless = injected(smith, "shop", "trick");

        engine.consumeOnce(null, smith, "shop", nameless, ctx);
        assertTrue(ctx.state().keys.isEmpty(), "nothing to key it by, so nothing is written");
        assertTrue(engine.optionAvailable(smith, "shop", nameless, ctx));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("extension 'trick'") && w.contains("OnceId")),
                warnings.toString());
    }
}
