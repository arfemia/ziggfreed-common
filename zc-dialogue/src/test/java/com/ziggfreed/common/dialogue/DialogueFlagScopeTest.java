package com.ziggfreed.common.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The pure decision core behind per-world-family dialogue state: the storage-key format and the
 * does-this-world-carry-the-scope resolution. Internal plumbing - no author writes one of these
 * keys - but the format is load-bearing for a consumer's prefix-match resets. No server needed.
 */
class DialogueFlagScopeTest {

    /** The decode vocabulary is process-wide; start every test from a clean one. */
    @BeforeEach
    void resetDialogueTypes() {
        DialogueTestSupport.reset();
    }

    // ==================== The key format ====================

    /**
     * THE SOFT-LOCK REGRESSION GUARD. A consumer clears a namespace of dialogue state by a leading
     * PREFIX match (hyMMO's {@code QuestComponent.resetQuest} clears {@code q:<questId>:*} when a
     * quest is reset). If the world scope were PREPENDED, the scoped key would fall outside that
     * prefix, the reset would silently miss it, and the dialogue would stay soft-locked forever.
     * The scope must therefore wrap only the FINAL segment, leaving every leading segment intact.
     */
    @Test
    void questPrefixedKeyKeepsItsQuestPrefixWhenScoped() {
        String key = DialogueFlagScope.scopedKey("q:meet_at_the_temple:greeted", "forgotten_temple");

        assertEquals("q:meet_at_the_temple:w:forgotten_temple:greeted", key);
        // The exact predicate a prefix-match reset uses: it must still hit.
        assertTrue(key.startsWith("q:meet_at_the_temple:"),
                "a world-scoped quest key must stay inside its q:<questId>: prefix or a quest"
                        + " reset silently misses it and the dialogue soft-locks");
    }

    @Test
    void bareKeyScopesToWorldSegment() {
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
    void unscopedKeyResolvesToItselfUnchanged() {
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

    // ==================== The state-key namespaces ====================

    @Test
    void everyStateNamespaceHasItsOwnShape() {
        assertEquals("once:e:mmo_hub_intro:temple_greet",
                DialogueStateKeys.entryOnce("mmo_hub_intro", "temple_greet"));
        assertEquals("once:o:guide:camp_talk:dialogue.guide.opt.bread",
                DialogueStateKeys.optionOnce("guide", "camp_talk", "dialogue.guide.opt.bread"));
        assertEquals("mem:d:guide:helped_refugees",
                DialogueStateKeys.memory("guide", "helped_refugees", false));
        assertEquals("mem:s:helped_refugees",
                DialogueStateKeys.memory("guide", "helped_refugees", true));
    }

    @Test
    void keyPiecesAreCaseFoldedAndCannotInventASegment() {
        assertEquals("once:e:hub:greet", DialogueStateKeys.entryOnce(" Hub ", "GREET"));
        assertEquals("once:o:hub:greet:a.b",
                DialogueStateKeys.optionOnce("hub", "greet", "a:b"),
                "a separator inside a discriminator must not split into an extra segment");
    }

    @Test
    void resetWithQuestPrefixesTheWholeKey() {
        assertEquals("q:guide_trust:mem:s:helped",
                DialogueStateKeys.withQuest("guide_trust",
                        DialogueStateKeys.memory("guide", "helped", true)));
        assertEquals("mem:s:helped",
                DialogueStateKeys.withQuest(null, DialogueStateKeys.memory("guide", "helped", true)));
    }
}
