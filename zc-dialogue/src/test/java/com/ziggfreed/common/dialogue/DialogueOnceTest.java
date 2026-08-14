package com.ziggfreed.common.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.world.WorldSelector;

/**
 * The {@code Once} knob end to end, purely: the boolean shorthand, entry-level seen-ness spent by
 * COMPLETING a beat (and not by leaving it), option-level seen-ness keyed by label rather than
 * position, and the per-world-family scope. No server needed - the test context's engine handles
 * throw, which is also the "world cannot be read" path.
 */
class DialogueOnceTest {

    /** The decode vocabulary is process-wide; start every test from a clean one. */
    @BeforeEach
    void resetDialogueTypes() {
        DialogueTestSupport.reset();
    }

    private final List<String> warnings = new ArrayList<>();

    private DialogueEngine engine() {
        return DialogueEngine.builder().warn(warnings::add).build();
    }

    // ==================== Authoring shapes ====================

    @Test
    void booleanShorthandNormalizesOnEntriesAndOptions() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("t",
                "{\"Start\":{\"First\":[{\"Node\":\"g\",\"Once\":true}]},\"Nodes\":{\"g\":{\"Options\":["
                        + "{\"LabelKey\":\"a\",\"Once\":true},"
                        + "{\"LabelKey\":\"b\",\"Once\":false},"
                        + "{\"LabelKey\":\"c\"}]}}}");
        assertNotNull(d);

        assertNotNull(d.getStart().first().get(0).getOnce(), "\"Once\": true on an entry is the empty group");
        assertNull(d.getStart().first().get(0).getOnce().getWhere());
        assertNotNull(d.getNode("g").getOptions().get(0).getOnce());
        assertNull(d.getNode("g").getOptions().get(1).getOnce(), "\"Once\": false authors no Once");
        assertNull(d.getNode("g").getOptions().get(2).getOnce());
    }

    @Test
    void groupFormCarriesTheWorld() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("t",
                "{\"Start\":{\"First\":[{\"Node\":\"g\",\"Once\":{\"Where\":{\"Match\":[\"forgotten_temple\"]}}}]},"
                        + "\"Nodes\":{\"g\":{\"Options\":[{\"LabelKey\":\"a\",\"OnceId\":\"hail\","
                        + "\"Once\":{\"Where\":{\"Match\":[\"forgotten_temple\"]}}}]}}}");
        assertNotNull(d);

        assertEquals("forgotten_temple", d.getStart().first().get(0).getOnce().getWhere().getMatch()[0]);
        DialogueOption option = d.getNode("g").getOptions().get(0);
        assertEquals("forgotten_temple", option.getOnce().getWhere().getMatch()[0]);
        assertEquals("hail", option.getOnceId());
    }

    @Test
    void sugarNormalizationIsIdempotent() {
        DialogueEngine engine = engine();
        // A body already through the pass (the group form) decodes identically the second time.
        NpcDialogue d = engine.decode("t",
                "{\"Start\":{\"First\":[{\"Node\":\"g\",\"Once\":{}}]},\"Nodes\":{\"g\":{\"Options\":["
                        + "{\"LabelKey\":\"a\",\"Once\":{},\"Close\":true}]}}}");
        assertNotNull(d);
        assertNotNull(d.getStart().first().get(0).getOnce());
        DialogueOption option = d.getNode("g").getOptions().get(0);
        assertNotNull(option.getOnce(), "a sugar expander's strip pass must not eat the option's Once");
        assertTrue(option.closesDialogue(), "the Close sugar still expanded beside it");
    }

    // ==================== Entry-level Once ====================

    /** A first-visit greeting that falls through to a steady-state node once it is spent. */
    private static NpcDialogue firstVisitTree(@Nonnull DialogueEngine engine) {
        return engine.decode("temple_talk",
                "{\"Start\":{\"First\":[{\"Node\":\"greet\",\"Once\":{}},{\"Node\":\"steady\"}]},"
                        + "\"Nodes\":{\"greet\":{\"Options\":[{\"LabelKey\":\"hail\","
                        + "\"Actions\":[{\"Type\":\"Goto\",\"Node\":\"steady\"}]}]},"
                        + "\"steady\":{\"Options\":[]}}}");
    }

    @Test
    void entryOnceIsSpentByCompletingTheBeat() {
        DialogueEngine engine = engine();
        NpcDialogue d = firstVisitTree(engine);
        assertNotNull(d);
        TestDialogueContext ctx = new TestDialogueContext(d);

        DialogueEngine.EntryResolution first = engine.resolveEntry(d, ctx);
        assertEquals("greet", first.nodeId());
        assertEquals("once:e:temple_talk:greet", first.onceKey());

        // The player chooses the greeting's only option: the beat is complete.
        DialogueOption hail = d.getNode("greet").getOptions().get(0);
        engine.consumeOnce(first.onceKey(), d, "greet", hail, ctx);
        assertTrue(ctx.state().has("once:e:temple_talk:greet"));

        assertEquals("steady", engine.resolveEntry(d, ctx).nodeId(),
                "a spent first-visit entry must stop matching, falling through to the next");
    }

    @Test
    void theImplicitFarewellAlsoCompletesTheBeat() {
        DialogueEngine engine = engine();
        NpcDialogue d = firstVisitTree(engine);
        assertNotNull(d);
        TestDialogueContext ctx = new TestDialogueContext(d);

        DialogueEngine.EntryResolution first = engine.resolveEntry(d, ctx);
        // Null option: the page's implicit Farewell row, which is still a deliberate choice.
        engine.consumeOnce(first.onceKey(), d, "greet", null, ctx);

        assertEquals("steady", engine.resolveEntry(d, ctx).nodeId());
    }

    @Test
    void leavingMidBeatShowsItAgain() {
        DialogueEngine engine = engine();
        NpcDialogue d = firstVisitTree(engine);
        assertNotNull(d);
        TestDialogueContext ctx = new TestDialogueContext(d);

        // Escape / the close button: resolved, rendered, nothing consumed.
        assertEquals("greet", engine.resolveEntry(d, ctx).nodeId());
        assertEquals("greet", engine.resolveEntry(d, ctx).nodeId(),
                "an interrupted first-visit beat must show again on the next conversation");
        assertTrue(ctx.state().keys.isEmpty());
    }

    @Test
    void anEntryWithoutOnceKeepsMatching() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("plain",
                "{\"Start\":{\"First\":[{\"Node\":\"g\"}]},\"Nodes\":{\"g\":{\"Options\":[]}}}");
        assertNotNull(d);
        TestDialogueContext ctx = new TestDialogueContext(d);

        DialogueEngine.EntryResolution resolution = engine.resolveEntry(d, ctx);
        assertEquals("g", resolution.nodeId());
        assertNull(resolution.onceKey());
        engine.consumeOnce(resolution.onceKey(), d, "g", null, ctx);
        assertTrue(ctx.state().keys.isEmpty(), "nothing to spend means nothing written");
        assertEquals("g", engine.resolveEntry(d, ctx).nodeId());
    }

    // ==================== Option-level Once ====================

    @Test
    void optionOnceIsKeyedByLabelKeySoReorderingCannotResurrectIt() {
        DialogueEngine engine = engine();
        NpcDialogue authored = engine.decode("guide",
                "{\"Start\":{\"First\":[{\"Node\":\"camp\"}]},\"Nodes\":{\"camp\":{\"Options\":["
                        + "{\"LabelKey\":\"opt.bread\",\"Once\":{}},"
                        + "{\"LabelKey\":\"opt.lore\"}]}}}");
        assertNotNull(authored);
        TestDialogueContext ctx = new TestDialogueContext(authored);

        DialogueOption bread = authored.getNode("camp").getOptions().get(0);
        assertTrue(engine.optionAvailable(authored, "camp", bread, ctx));
        engine.consumeOnce(null, authored, "camp", bread, ctx);
        assertEquals(Set.of("once:o:guide:camp:opt.bread"), ctx.state().keys);
        assertFalse(engine.optionAvailable(authored, "camp", bread, ctx));

        // The same content re-authored with the options in the other order: still spent.
        NpcDialogue reordered = engine.decode("guide",
                "{\"Start\":{\"First\":[{\"Node\":\"camp\"}]},\"Nodes\":{\"camp\":{\"Options\":["
                        + "{\"LabelKey\":\"opt.lore\"},"
                        + "{\"LabelKey\":\"opt.bread\",\"Once\":{}}]}}}");
        assertNotNull(reordered);
        TestDialogueContext moved = new TestDialogueContext(reordered, ctx.state());
        DialogueOption movedBread = reordered.getNode("camp").getOptions().get(1);
        assertFalse(engine.optionAvailable(reordered, "camp", movedBread, moved),
                "an option's Once must key on its label, never on its index");
        assertTrue(engine.optionAvailable(reordered, "camp",
                reordered.getNode("camp").getOptions().get(0), moved));
    }

    @Test
    void onceIdSeparatesTwoOptionsSharingALabel() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("guide",
                "{\"Start\":{\"First\":[{\"Node\":\"camp\"}]},\"Nodes\":{\"camp\":{\"Options\":["
                        + "{\"LabelKey\":\"opt.gift\",\"OnceId\":\"bread\",\"Once\":{}},"
                        + "{\"LabelKey\":\"opt.gift\",\"OnceId\":\"stew\",\"Once\":{}}]}}}");
        assertNotNull(d);
        TestDialogueContext ctx = new TestDialogueContext(d);

        DialogueOption bread = d.getNode("camp").getOptions().get(0);
        DialogueOption stew = d.getNode("camp").getOptions().get(1);
        engine.consumeOnce(null, d, "camp", bread, ctx);

        assertEquals(Set.of("once:o:guide:camp:bread"), ctx.state().keys);
        assertFalse(engine.optionAvailable(d, "camp", bread, ctx));
        assertTrue(engine.optionAvailable(d, "camp", stew, ctx),
                "an OnceId gives each duplicate-label option its own identity");
    }

    @Test
    void anOptionWithNoIdentityStaysRepeatableAndSaysSo() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("guide",
                "{\"Start\":{\"First\":[{\"Node\":\"camp\"}]},\"Nodes\":{\"camp\":{\"Options\":[{\"Once\":{}}]}}}");
        assertNotNull(d);
        TestDialogueContext ctx = new TestDialogueContext(d);

        DialogueOption nameless = d.getNode("camp").getOptions().get(0);
        engine.consumeOnce(null, d, "camp", nameless, ctx);

        assertTrue(ctx.state().keys.isEmpty());
        assertTrue(engine.optionAvailable(d, "camp", nameless, ctx));
        assertTrue(warnings.stream().anyMatch(w -> w.contains("OnceId")),
                "the author has to be told the guard is inert: " + warnings);
        int warned = warnings.size();
        engine.optionAvailable(d, "camp", nameless, ctx);
        assertEquals(warned, warnings.size(), "the warning must not repeat on every render");
    }

    // ==================== The per-world scope ====================

    @Test
    void aWorldScopedOnceResolvesInThatWorldAndNowhereElse() {
        DialogueOnce once = DialogueOnce.ofWhere(WorldSelector.of(new String[] {"forgotten_temple"}, null, null));

        assertEquals("once:e:hub:w:forgotten_temple:greet",
                once.resolveKey("once:e:hub:greet", "Forgotten_Temple"));
        assertNull(once.resolveKey("once:e:hub:greet", "default"),
                "in another world the key does not exist: reads are unset, writes are dropped");
    }

    @Test
    void anUnscopedOnceIsRememberedPerCharacter() {
        assertEquals("once:e:hub:greet",
                DialogueOnce.GLOBAL.resolveKey("once:e:hub:greet", "default"));
    }

    @Test
    void offPatternAScopedEntryOnceIsInertRatherThanSpent() {
        DialogueEngine engine = engine();
        // The test context cannot read a world at all, which is the same shape as standing in a
        // world the pattern does not match.
        NpcDialogue d = engine.decode("temple_talk",
                "{\"Start\":{\"First\":[{\"Node\":\"greet\",\"Once\":{\"Where\":{\"Match\":[\"forgotten_temple\"]}}},"
                        + "{\"Node\":\"steady\"}]},\"Nodes\":{\"greet\":{\"Options\":[]},"
                        + "\"steady\":{\"Options\":[]}}}");
        assertNotNull(d);
        TestDialogueContext ctx = new TestDialogueContext(d);

        DialogueEngine.EntryResolution resolution = engine.resolveEntry(d, ctx);
        assertEquals("greet", resolution.nodeId());
        assertNull(resolution.onceKey(), "there is no key to spend in a world the pattern does not match");

        engine.consumeOnce(resolution.onceKey(), d, "greet", null, ctx);
        assertTrue(ctx.state().keys.isEmpty(), "the write is a deliberate no-op, never a throw");
        assertEquals("greet", engine.resolveEntry(d, ctx).nodeId());
    }
}
