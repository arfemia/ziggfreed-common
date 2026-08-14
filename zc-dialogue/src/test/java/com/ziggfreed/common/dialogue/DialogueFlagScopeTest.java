package com.ziggfreed.common.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.world.WorldSelector;

/**
 * The pure decision core behind per-world dialogue state: the storage-key format and the
 * does-this-pattern-match-this-world resolution. Internal plumbing - no author writes one of these
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
    void theStateKeySegmentIsNormalized() {
        assertEquals("w:forgotten_temple:greeted",
                DialogueFlagScope.scopedKey("greeted", "  Forgotten_Temple "));
    }

    // ==================== Resolution against the current world ====================

    @Test
    void unscopedKeyResolvesToItselfUnchanged() {
        assertEquals("greeted", DialogueFlagScope.resolve(null, "greeted", "forgotten_temple"));
        assertEquals("q:x:greeted",
                DialogueFlagScope.resolve(null, "q:x:greeted", "forgotten_temple"));
    }

    @Test
    void anExactWorldNameResolvesToTheScopedKey() {
        assertEquals("w:forgotten_temple:greeted",
                DialogueFlagScope.resolve(matching("forgotten_temple"), "greeted", "Forgotten_Temple"));
    }

    /**
     * THE STORED-STATE COMPATIBILITY PIN. The scope grammar moved to the shared {@code Where} group,
     * but a name pattern must still file its state under exactly the key it always did, or every
     * player's "already greeted here" comes back on the next login.
     */
    @Test
    void aPatternKeysStateByItsCoreSoARebuiltInstanceRemembers() {
        DialogueFlagScope scope = matching("*KweebecNightmare*");

        assertEquals("w:kweebecnightmare:greeted", DialogueFlagScope.resolve(scope, "greeted",
                "instance-KweebecNightmare_Barn-9f3a"));
        assertEquals("w:kweebecnightmare:greeted", DialogueFlagScope.resolve(scope, "greeted",
                "instance-KweebecNightmare_Barn-0011"),
                "a fresh instantiation carries a different uuid and must land on the same key");
    }

    @Test
    void aNarrowerPatternScopesTheSameStatePerArena() {
        assertEquals("w:kweebecnightmare_barn:greeted",
                DialogueFlagScope.resolve(matching("*KweebecNightmare_Barn*"),
                        "greeted", "instance-KweebecNightmare_Barn-9f3a"));
    }

    /**
     * The reason the config axis exists: an instance world's NAME carries a fresh uuid every time it
     * is built, while the config key it runs does not - so state filed under the config is the same
     * state next visit, with no pattern to get right.
     */
    @Test
    void aGameplayConfigKeysStateByTheConfigId() {
        DialogueFlagScope scope = DialogueFlagScope.ofWhere(
                WorldSelector.of(null, new String[] {"ForgottenTemple"}, null));

        assertEquals("w:forgottentemple:greeted", DialogueFlagScope.resolve(scope, "greeted",
                "instance-Forgotten_Temple-9f3a", "ForgottenTemple"));
        assertEquals("w:forgottentemple:greeted", DialogueFlagScope.resolve(scope, "greeted",
                "instance-Forgotten_Temple-0011", "ForgottenTemple"),
                "the config outlives the instance, so the key does too");
        assertNull(DialogueFlagScope.resolve(scope, "greeted", "default", "Default"),
                "a world running another config is not this scope's world");
    }

    @Test
    void theMostSpecificAxisDecidesTheKey() {
        DialogueFlagScope both = DialogueFlagScope.ofWhere(
                WorldSelector.of(new String[] {"*Temple*"}, new String[] {"ForgottenTemple"}, null));
        assertEquals("w:forgottentemple:greeted",
                DialogueFlagScope.resolve(both, "greeted", "instance-Temple-1", "ForgottenTemple"),
                "a config hit is the top of the shared ladder, so it decides the key");

        DialogueFlagScope patterns = DialogueFlagScope.ofWhere(
                WorldSelector.of(new String[] {"*Temple*", "*Forgotten_Temple*"}, null, null));
        assertEquals("w:forgotten_temple:greeted",
                DialogueFlagScope.resolve(patterns, "greeted", "instance-Forgotten_Temple-1", null),
                "with two patterns matching, the longer core wins whatever order they were written in");
    }

    @Test
    void unmatchedWorldResolvesToNullSoWritesNoOpAndReadsAreUnset() {
        DialogueFlagScope scope = matching("forgotten_temple");
        assertNull(DialogueFlagScope.resolve(scope, "greeted", "default"),
                "a scope that misses this world must resolve to no key at all");
        assertNull(DialogueFlagScope.resolve(scope, "greeted", null),
                "an unreadable world carries no scope either");
    }

    @Test
    void blankScopeIsTreatedAsGlobal() {
        assertEquals("greeted",
                DialogueFlagScope.resolve(DialogueFlagScope.ofWhere(null), "greeted", "default"));
        assertEquals("greeted", DialogueFlagScope.resolve(
                DialogueFlagScope.ofWhere(WorldSelector.of(null, null, null)), "greeted", "default"));
        assertTrue(DialogueFlagScope.ofWhere(WorldSelector.of(null, null, null)).isBlank());
    }

    @Test
    void aBareWildcardIsTheSameAsNoScopeAtAll() {
        DialogueFlagScope everywhere = matching("*");

        assertTrue(everywhere.isBlank(), "scoping to every world narrows nothing");
        assertEquals("greeted", DialogueFlagScope.resolve(everywhere, "greeted", "default"),
                "so it must never put an empty core into the stored key");
    }

    @Test
    void anExcludeOnlyScopeExistsNowhere() {
        DialogueFlagScope nowhere = DialogueFlagScope.ofWhere(
                WorldSelector.of(null, null, new String[] {"*Arena*"}));

        assertFalse(nowhere.isBlank(), "it says something, and what it says is 'no world'");
        assertNull(DialogueFlagScope.resolve(nowhere, "greeted", "default"),
                "an exclusion filters the positive axes rather than standing in for them");
    }

    /** A scope narrowing to one world, in the shared grammar. */
    @Nonnull
    private static DialogueFlagScope matching(@Nonnull String pattern) {
        return DialogueFlagScope.ofWhere(WorldSelector.of(new String[] {pattern}, null, null));
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
