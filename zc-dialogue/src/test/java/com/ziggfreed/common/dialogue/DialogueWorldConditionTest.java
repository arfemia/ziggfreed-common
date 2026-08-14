package com.ziggfreed.common.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.universe.world.World;
import com.ziggfreed.common.dialogue.validate.DialogueStructureValidator;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.world.MatchRank;

/**
 * The generic {@code World} dialogue condition: it re-models none of the {@code Where} semantics,
 * so these exercise the decoded condition through the embedded group's PURE matcher (world name +
 * gameplay config). No server needed.
 */
class DialogueWorldConditionTest {

    /** The decode vocabulary is process-wide; start every test from a clean one. */
    @BeforeEach
    void resetDialogueTypes() {
        DialogueTestSupport.reset();
    }

    private static DialogueEngine engine() {
        return DialogueEngine.builder().warn(m -> { }).build();
    }

    private static DialogueCondition.World decodeCondition(String conditionJson) {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("w",
                "{\"Start\":[{\"Node\":\"g\"}],\"Nodes\":{\"g\":{\"Conditions\":[" + conditionJson
                        + "],\"Options\":[]}}}");
        assertNotNull(d);
        DialogueCondition condition = d.getNode("g").getConditions().get(0);
        assertTrue(condition instanceof DialogueCondition.World,
                "Type 'World' must decode to DialogueCondition.World");
        return (DialogueCondition.World) condition;
    }

    // ==================== Decode ====================

    @Test
    void decodesEveryAxis() {
        DialogueCondition.World c = decodeCondition(
                "{\"Type\":\"World\",\"Where\":{\"Match\":[\"*Forgotten_Temple*\"],"
                        + "\"GameplayConfig\":[\"ForgottenTemple\"],\"ExcludeMatch\":[\"*Arena*\"]}}");

        assertNotNull(c.getSelector());
        assertEquals("*Forgotten_Temple*", c.getSelector().getMatch()[0]);
        assertEquals("ForgottenTemple", c.getSelector().getGameplayConfig()[0]);
        assertEquals("*Arena*", c.getSelector().getExcludeMatch()[0]);
    }

    // ==================== Matching ====================

    @Test
    void matchesByNamePattern() {
        DialogueCondition.World c =
                decodeCondition("{\"Type\":\"World\",\"Where\":{\"Match\":[\"*Forgotten_Temple*\"]}}");

        assertNotNull(c.getSelector().match("instance-Forgotten_Temple-abc", null));
        assertNull(c.getSelector().match("default", null));
    }

    @Test
    void matchesByExactWorldName() {
        DialogueCondition.World c =
                decodeCondition("{\"Type\":\"World\",\"Where\":{\"Match\":[\"default\"]}}");

        MatchRank rank = c.getSelector().match("default", null);
        assertNotNull(rank);
        assertEquals(MatchRank.EXACT_NAME_BAND, rank.band(), "a bare word is an exact world name");
        assertNull(c.getSelector().match("instance-Default_Dungeon-abc", null));
    }

    @Test
    void matchesByGameplayConfig() {
        DialogueCondition.World c =
                decodeCondition("{\"Type\":\"World\",\"Where\":{\"GameplayConfig\":[\"ForgottenTemple\"]}}");

        MatchRank rank = c.getSelector().match("instance-Forgotten_Temple-abc", "ForgottenTemple");
        assertNotNull(rank);
        assertEquals(MatchRank.GAMEPLAY_CONFIG_BAND, rank.band(),
                "GameplayConfig is the top band - the uuid-free machine key of an instance world");
        assertNull(c.getSelector().match("instance-Forgotten_Temple-abc", "Overworld"));
    }

    @Test
    void excludeMatchVetoesAnOtherwiseMatchingWorld() {
        DialogueCondition.World c = decodeCondition(
                "{\"Type\":\"World\",\"Where\":{\"Match\":[\"*Temple*\"],\"ExcludeMatch\":[\"*Arena*\"]}}");

        assertNull(c.getSelector().match("instance-Temple_Arena-abc", null),
                "an excluded pattern must veto even a positive pattern hit");
        assertNotNull(c.getSelector().match("instance-Forgotten_Temple-abc", null));
    }

    @Test
    void failsClosedOnANullWorld() {
        DialogueCondition.World c =
                decodeCondition("{\"Type\":\"World\",\"Where\":{\"Match\":[\"*Forgotten_Temple*\"]}}");
        // The engine-facing overload: no world means no match, so the gated content stays hidden.
        assertNull(c.getSelector().match((World) null));
    }

    @Test
    void aConditionWithNoPositiveAxisMatchesNothing() {
        DialogueCondition.World c =
                decodeCondition("{\"Type\":\"World\",\"Where\":{\"ExcludeMatch\":[\"*Arena*\"]}}");

        assertTrue(c.getSelector().hasNoPositiveAxis());
        assertNull(c.getSelector().match("anything", "Anything"));
    }

    // ==================== Validator findings ====================

    @Test
    void validatorFlagsAOnceScopedToEveryWorld() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("everywhere",
                "{\"Start\":[{\"Node\":\"g\",\"Once\":{\"World\":\"*\"}}],"
                        + "\"Nodes\":{\"g\":{\"Options\":[{\"LabelKey\":\"x\","
                        + "\"Actions\":[{\"Type\":\"Close\"}]}]}}}");
        assertNotNull(d);

        List<String> codes = DialogueStructureValidator.validate(d)
                .stream().map(Finding::code).toList();

        assertTrue(codes.contains("WORLD_SCOPE_MATCHES_EVERY_WORLD"), codes.toString());
    }

    @Test
    void validatorFlagsAWorldConditionThatCanNeverPass() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("empty",
                "{\"Start\":[{\"Node\":\"g\"}],\"Nodes\":{\"g\":{\"Conditions\":["
                        + "{\"Type\":\"World\",\"Where\":{\"ExcludeMatch\":[\"*Arena*\"]}}],"
                        + "\"Options\":[{\"LabelKey\":\"x\",\"Actions\":[{\"Type\":\"Close\"}]}]}}}");
        assertNotNull(d);

        List<String> codes = DialogueStructureValidator.validate(d)
                .stream().map(Finding::code).toList();

        assertTrue(codes.contains("WORLD_CONDITION_NO_AXIS"), codes.toString());
    }

    @Test
    void validatorFlagsABlankPatternInsideAWorldCondition() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("blank",
                "{\"Start\":[{\"Node\":\"g\"}],\"Nodes\":{\"g\":{\"Conditions\":["
                        + "{\"Type\":\"World\",\"Where\":{\"Match\":[\"default\",\"\"]}}],"
                        + "\"Options\":[{\"LabelKey\":\"x\",\"Actions\":[{\"Type\":\"Close\"}]}]}}}");
        assertNotNull(d);

        List<String> codes = DialogueStructureValidator.validate(d)
                .stream().map(Finding::code).toList();

        // The finding comes from the ONE Where audit, so a placement carrying the same mistake
        // reports the identical code rather than a dialogue-flavoured near-duplicate.
        assertTrue(codes.contains("BLANK_ENTRY"), codes.toString());
    }

    @Test
    void validatorStaysSilentOnAWellFormedWorldScopedBeat() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("good",
                "{\"Start\":[{\"Node\":\"g\",\"Once\":{\"World\":\"*Forgotten_Temple*\"}}],"
                        + "\"Nodes\":{\"g\":{\"Conditions\":["
                        + "{\"Type\":\"AnyOf\",\"Any\":[{\"Type\":\"World\","
                        + "\"Where\":{\"GameplayConfig\":[\"ForgottenTemple\"]}}]}],"
                        + "\"Options\":[{\"LabelKey\":\"x\",\"Actions\":[{\"Type\":\"Close\"}]}]}}}");
        assertNotNull(d);

        List<String> codes = DialogueStructureValidator.validate(d)
                .stream().map(Finding::code).toList();

        assertFalse(codes.contains("WORLD_SCOPE_MATCHES_EVERY_WORLD"), codes.toString());
        assertTrue(codes.isEmpty(), "a well-formed world-scoped beat produces no findings at all");
    }
}
