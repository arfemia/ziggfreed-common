package com.ziggfreed.common.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.universe.world.World;
import com.ziggfreed.common.dialogue.validate.DialogueStructureValidator;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.world.MatchRank;
import com.ziggfreed.common.world.WorldNameIndex;

/**
 * The generic {@code World} dialogue condition: it re-models none of the world-selector
 * semantics, so these exercise the decoded condition through the embedded selector's PURE matcher
 * (world name + gameplay config + the already-resolved name index). No server needed.
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

    /** A world carrying the temple selector name, as {@code WorldIdentity} would resolve it. */
    private static WorldNameIndex templeIndex() {
        return WorldNameIndex.of(Map.of("forgotten_temple", MatchRank.gameplayConfig()));
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
    void decodesEverySelectorAxis() {
        DialogueCondition.World c = decodeCondition(
                "{\"Type\":\"World\",\"Where\":{\"Names\":[\"forgotten_temple\"],"
                        + "\"Match\":[\"*Forgotten_Temple*\"],\"GameplayConfig\":[\"ForgottenTemple\"],"
                        + "\"ExcludeNames\":[\"arena\"]}}");

        assertNotNull(c.getSelector());
        assertEquals(1, c.getSelector().getNames().length);
        assertEquals("forgotten_temple", c.getSelector().getNames()[0]);
        assertEquals("*Forgotten_Temple*", c.getSelector().getMatch()[0]);
        assertEquals("ForgottenTemple", c.getSelector().getGameplayConfig()[0]);
        assertEquals("arena", c.getSelector().getExcludeNames()[0]);
    }

    // ==================== Matching ====================

    @Test
    void matchesByName() {
        DialogueCondition.World c =
                decodeCondition("{\"Type\":\"World\",\"Where\":{\"Names\":[\"forgotten_temple\"]}}");

        assertNotNull(c.getSelector().match("instance-Forgotten_Temple-abc", "ForgottenTemple",
                templeIndex()));
        assertNull(c.getSelector().match("default", null, WorldNameIndex.EMPTY),
                "a world that carries no selector names cannot match a Names reference");
    }

    @Test
    void matchesByInlinePattern() {
        DialogueCondition.World c =
                decodeCondition("{\"Type\":\"World\",\"Where\":{\"Match\":[\"*Forgotten_Temple*\"]}}");

        assertNotNull(c.getSelector().match("instance-Forgotten_Temple-abc", null, WorldNameIndex.EMPTY));
        assertNull(c.getSelector().match("default", null, WorldNameIndex.EMPTY));
    }

    @Test
    void matchesByGameplayConfig() {
        DialogueCondition.World c =
                decodeCondition("{\"Type\":\"World\",\"Where\":{\"GameplayConfig\":[\"ForgottenTemple\"]}}");

        MatchRank rank = c.getSelector().match("instance-Forgotten_Temple-abc", "ForgottenTemple",
                WorldNameIndex.EMPTY);
        assertNotNull(rank);
        assertEquals(MatchRank.GAMEPLAY_CONFIG_BAND, rank.band(),
                "GameplayConfig is the top band - the uuid-free machine key of an instance world");
        assertNull(c.getSelector().match("instance-Forgotten_Temple-abc", "Overworld",
                WorldNameIndex.EMPTY));
    }

    @Test
    void excludeNamesVetoesAnOtherwiseMatchingWorld() {
        DialogueCondition.World c = decodeCondition(
                "{\"Type\":\"World\",\"Where\":{\"Match\":[\"*Forgotten_Temple*\"],\"ExcludeNames\":[\"arena\"]}}");

        WorldNameIndex arena = WorldNameIndex.of(Map.of("arena", MatchRank.gameplayConfig()));
        assertNull(c.getSelector().match("instance-Forgotten_Temple-abc", null, arena),
                "an excluded name must veto even a positive pattern hit");
        assertNotNull(c.getSelector().match("instance-Forgotten_Temple-abc", null, WorldNameIndex.EMPTY));
    }

    @Test
    void failsClosedOnANullWorld() {
        DialogueCondition.World c =
                decodeCondition("{\"Type\":\"World\",\"Where\":{\"Names\":[\"forgotten_temple\"]}}");
        // The engine-facing overload: no world means no match, so the gated content stays hidden.
        assertNull(c.getSelector().match((World) null));
    }

    @Test
    void aConditionWithNoPositiveAxisMatchesNothing() {
        DialogueCondition.World c =
                decodeCondition("{\"Type\":\"World\",\"Where\":{\"ExcludeNames\":[\"arena\"]}}");

        assertTrue(c.getSelector().hasNoPositiveAxis());
        assertNull(c.getSelector().match("anything", "Anything", templeIndex()));
    }

    // ==================== Validator findings ====================

    @Test
    void validatorFlagsAnUnknownSelectorNameOnAWorldCondition() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("bad",
                "{\"Start\":[{\"Node\":\"g\"}],"
                        + "\"Nodes\":{\"g\":{\"Conditions\":["
                        + "{\"Type\":\"World\",\"Where\":{\"Names\":[\"frogotten_temple\"]}}],"
                        + "\"Options\":[{\"LabelKey\":\"x\",\"Actions\":[{\"Type\":\"Close\"}]}]}}}");
        assertNotNull(d);

        List<Finding> findings = DialogueStructureValidator
                .validateAll(List.of(d), Set.of("forgotten_temple", "default"));
        List<String> codes = findings.stream().map(Finding::code).toList();

        // The finding comes from the ONE selector-layer check, so a placement naming the same
        // typo reports the identical code rather than a dialogue-flavoured near-duplicate.
        assertTrue(codes.contains("UNKNOWN_SELECTOR_NAME"), codes.toString());
    }

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
                        + "{\"Type\":\"World\",\"Where\":{\"ExcludeNames\":[\"arena\"]}}],"
                        + "\"Options\":[{\"LabelKey\":\"x\",\"Actions\":[{\"Type\":\"Close\"}]}]}}}");
        assertNotNull(d);

        List<String> codes = DialogueStructureValidator.validate(d, Set.of("forgotten_temple"))
                .stream().map(Finding::code).toList();

        assertTrue(codes.contains("WORLD_CONDITION_NO_AXIS"), codes.toString());
    }

    @Test
    void validatorStaysSilentOnKnownNamesAndOnAnUnknownVocabulary() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("good",
                "{\"Start\":[{\"Node\":\"g\",\"Once\":{\"World\":\"forgotten_temple\"}}],"
                        + "\"Nodes\":{\"g\":{\"Conditions\":["
                        + "{\"Type\":\"AnyOf\",\"Any\":[{\"Type\":\"World\","
                        + "\"Where\":{\"Names\":[\"Forgotten_Temple\"]}}]}],"
                        + "\"Options\":[{\"LabelKey\":\"x\",\"Actions\":[{\"Type\":\"Close\"}]}]}}}");
        assertNotNull(d);

        // Known vocabulary: the name resolves (case-insensitively), even nested in a combinator.
        List<String> known = DialogueStructureValidator.validate(d, Set.of("forgotten_temple"))
                .stream().map(Finding::code).toList();
        assertFalse(known.contains("UNKNOWN_SELECTOR_NAME"), known.toString());
        assertFalse(known.contains("WORLD_SCOPE_MATCHES_EVERY_WORLD"), known.toString());

        // Absent / empty vocabulary means "cannot tell" (assets may not have loaded), never a
        // false alarm.
        List<String> unknownPool = DialogueStructureValidator.validate(d, Set.of())
                .stream().map(Finding::code).toList();
        assertFalse(unknownPool.contains("UNKNOWN_SELECTOR_NAME"), unknownPool.toString());
        assertTrue(DialogueStructureValidator.validate(d).isEmpty(),
                "a well-formed world-scoped beat produces no findings at all");
    }
}
