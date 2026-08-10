package com.ziggfreed.common.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The pure decision core of scoped dialogue flags: the storage-key format and the
 * does-this-world-carry-the-scope resolution. No server needed.
 */
class DialogueFlagScopeTest {

    private static DialogueEngine engine() {
        return DialogueEngine.builder().warn(m -> { }).build();
    }

    // ==================== The key format ====================

    /**
     * THE SOFT-LOCK REGRESSION GUARD. A consumer clears a namespace of dialogue flags by a leading
     * PREFIX match (hyMMO's {@code QuestComponent.resetQuest} clears {@code q:<questId>:*} when a
     * quest is reset). If the world scope were PREPENDED, the scoped flag would fall outside that
     * prefix, the reset would silently miss it, and the dialogue would stay soft-locked forever.
     * The scope must therefore wrap only the FINAL segment, leaving every leading segment intact.
     */
    @Test
    void questPrefixedFlagKeepsItsQuestPrefixWhenScoped() {
        String key = DialogueFlagScope.scopedKey("q:meet_at_the_temple:greeted", "forgotten_temple");

        assertEquals("q:meet_at_the_temple:w:forgotten_temple:greeted", key);
        // The exact predicate a prefix-match reset uses: it must still hit.
        assertTrue(key.startsWith("q:meet_at_the_temple:"),
                "a world-scoped quest flag must stay inside its q:<questId>: prefix or a quest"
                        + " reset silently misses it and the dialogue soft-locks");
    }

    @Test
    void bareFlagScopesToWorldSegment() {
        assertEquals("w:forgotten_temple:greeted",
                DialogueFlagScope.scopedKey("greeted", "forgotten_temple"));
    }

    @Test
    void deeperPrefixesAreAllPreserved() {
        assertEquals("a:b:c:w:temple:flag",
                DialogueFlagScope.scopedKey("a:b:c:flag", "temple"));
    }

    @Test
    void selectorNameIsNormalized() {
        assertEquals("w:forgotten_temple:greeted",
                DialogueFlagScope.scopedKey("greeted", "  Forgotten_Temple "));
    }

    // ==================== Resolution against the current world ====================

    @Test
    void unscopedFlagResolvesToItselfUnchanged() {
        // The no-regression case: an absent Scope must behave exactly as before this feature.
        assertEquals("greeted", DialogueFlagScope.resolve(null, "greeted", Set.of("forgotten_temple")));
        assertEquals("q:x:greeted",
                DialogueFlagScope.resolve(null, "q:x:greeted", Set.of("forgotten_temple")));
    }

    @Test
    void matchingWorldResolvesToTheScopedKey() {
        DialogueFlagScope scope = DialogueFlagScope.ofWorldSelector("forgotten_temple");
        assertEquals("w:forgotten_temple:greeted",
                DialogueFlagScope.resolve(scope, "greeted", Set.of("forgotten_temple", "instance")));
    }

    @Test
    void unmatchedWorldResolvesToNullSoWritesNoOpAndReadsAreUnset() {
        DialogueFlagScope scope = DialogueFlagScope.ofWorldSelector("forgotten_temple");
        assertNull(DialogueFlagScope.resolve(scope, "greeted", Set.of("primary")),
                "a scope the world does not carry must resolve to no key at all");
        assertNull(DialogueFlagScope.resolve(scope, "greeted", Set.of()),
                "a world with no selector names carries no scope either");
    }

    @Test
    void blankScopeIsTreatedAsGlobal() {
        assertEquals("greeted",
                DialogueFlagScope.resolve(DialogueFlagScope.ofWorldSelector(null), "greeted", Set.of()));
        assertEquals("greeted",
                DialogueFlagScope.resolve(DialogueFlagScope.ofWorldSelector("  "), "greeted", Set.of()));
        assertTrue(DialogueFlagScope.ofWorldSelector(" ").isBlank());
    }

    // ==================== Round-trip decode ====================

    @Test
    void decodesScopeOnSetFlagAndOnBothFlagConditions() {
        DialogueEngine engine = engine();
        String json = "{\"Start\":[{\"Node\":\"g\"}],\"Nodes\":{\"g\":{\"Options\":[{\"Label\":\"x\","
                + "\"Conditions\":[{\"Type\":\"NotFlag\",\"Flag\":\"greeted\","
                + "\"Scope\":{\"WorldSelector\":\"forgotten_temple\"}},"
                + "{\"Type\":\"Flag\",\"Flag\":\"met\",\"Scope\":{\"WorldSelector\":\"primary\"}}],"
                + "\"Actions\":[{\"Type\":\"SetFlag\",\"Flag\":\"greeted\","
                + "\"Scope\":{\"WorldSelector\":\"forgotten_temple\"}}]}]}}}";

        NpcDialogue d = engine.decode("scoped", json);
        assertNotNull(d);
        DialogueOption opt = d.getNode("g").getOptions().get(0);

        DialogueCondition.NotFlag notFlag = (DialogueCondition.NotFlag) opt.getConditions().get(0);
        assertNotNull(notFlag.getScope());
        assertEquals("forgotten_temple", notFlag.getScope().getWorldSelector());

        DialogueCondition.Flag flag = (DialogueCondition.Flag) opt.getConditions().get(1);
        assertNotNull(flag.getScope());
        assertEquals("primary", flag.getScope().getWorldSelector());

        DialogueAction.SetFlag set = (DialogueAction.SetFlag) opt.getActions().get(0);
        assertNotNull(set.getScope());
        assertEquals("forgotten_temple", set.getScope().getWorldSelector());
    }

    @Test
    void anUnsetScopeDecodesToNullOnEveryCarrier() {
        DialogueEngine engine = engine();
        String json = "{\"Start\":[{\"Node\":\"g\"}],\"Nodes\":{\"g\":{\"Options\":[{\"Label\":\"x\","
                + "\"Conditions\":[{\"Type\":\"NotFlag\",\"Flag\":\"greeted\"},"
                + "{\"Type\":\"Flag\",\"Flag\":\"met\"}],"
                + "\"Actions\":[{\"Type\":\"SetFlag\",\"Flag\":\"greeted\"}]}]}}}";

        NpcDialogue d = engine.decode("plain", json);
        assertNotNull(d);
        DialogueOption opt = d.getNode("g").getOptions().get(0);

        assertNull(((DialogueCondition.NotFlag) opt.getConditions().get(0)).getScope());
        assertNull(((DialogueCondition.Flag) opt.getConditions().get(1)).getScope());
        assertNull(((DialogueAction.SetFlag) opt.getActions().get(0)).getScope());
    }
}
