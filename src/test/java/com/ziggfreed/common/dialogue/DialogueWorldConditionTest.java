package com.ziggfreed.common.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.universe.world.World;
import com.ziggfreed.common.dialogue.validate.DialogueStructureValidator;
import com.ziggfreed.common.world.MatchRank;
import com.ziggfreed.common.world.WorldNameIndex;

/**
 * The generic {@code World} dialogue condition: it re-models none of the world-selector
 * semantics, so these exercise the decoded condition through the embedded selector's PURE matcher
 * (world name + gameplay config + the already-resolved name index). No server needed.
 */
class DialogueWorldConditionTest {

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
                "{\"Type\":\"World\",\"Names\":[\"forgotten_temple\"],\"Match\":[\"*Forgotten_Temple*\"],"
                        + "\"GameplayConfig\":[\"ForgottenTemple\"],\"ExcludeNames\":[\"arena\"]}");

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
                decodeCondition("{\"Type\":\"World\",\"Names\":[\"forgotten_temple\"]}");

        assertNotNull(c.getSelector().match("instance-Forgotten_Temple-abc", "ForgottenTemple",
                templeIndex()));
        assertNull(c.getSelector().match("default", null, WorldNameIndex.EMPTY),
                "a world that carries no selector names cannot match a Names reference");
    }

    @Test
    void matchesByInlinePattern() {
        DialogueCondition.World c =
                decodeCondition("{\"Type\":\"World\",\"Match\":[\"*Forgotten_Temple*\"]}");

        assertNotNull(c.getSelector().match("instance-Forgotten_Temple-abc", null, WorldNameIndex.EMPTY));
        assertNull(c.getSelector().match("default", null, WorldNameIndex.EMPTY));
    }

    @Test
    void matchesByGameplayConfig() {
        DialogueCondition.World c =
                decodeCondition("{\"Type\":\"World\",\"GameplayConfig\":[\"ForgottenTemple\"]}");

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
                "{\"Type\":\"World\",\"Match\":[\"*Forgotten_Temple*\"],\"ExcludeNames\":[\"arena\"]}");

        WorldNameIndex arena = WorldNameIndex.of(Map.of("arena", MatchRank.gameplayConfig()));
        assertNull(c.getSelector().match("instance-Forgotten_Temple-abc", null, arena),
                "an excluded name must veto even a positive pattern hit");
        assertNotNull(c.getSelector().match("instance-Forgotten_Temple-abc", null, WorldNameIndex.EMPTY));
    }

    @Test
    void failsClosedOnANullWorld() {
        DialogueCondition.World c =
                decodeCondition("{\"Type\":\"World\",\"Names\":[\"forgotten_temple\"]}");
        // The engine-facing overload: no world means no match, so the gated content stays hidden.
        assertNull(c.getSelector().match((World) null));
    }

    @Test
    void aConditionWithNoPositiveAxisMatchesNothing() {
        DialogueCondition.World c =
                decodeCondition("{\"Type\":\"World\",\"ExcludeNames\":[\"arena\"]}");

        assertTrue(c.getSelector().hasNoPositiveAxis());
        assertNull(c.getSelector().match("anything", "Anything", templeIndex()));
    }

    // ==================== Validator findings ====================

    @Test
    void validatorFlagsAnUnknownSelectorNameOnAWorldConditionAndOnAFlagScope() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("bad",
                "{\"Start\":[{\"Node\":\"g\"}],\"Nodes\":{\"g\":{\"Conditions\":["
                        + "{\"Type\":\"World\",\"Names\":[\"frogotten_temple\"]}],"
                        + "\"Options\":[{\"Label\":\"x\",\"Conditions\":[{\"Type\":\"NotFlag\","
                        + "\"Flag\":\"greeted\",\"Scope\":{\"WorldSelector\":\"frogotten_temple\"}}],"
                        + "\"Actions\":[{\"Type\":\"SetFlag\",\"Flag\":\"greeted\","
                        + "\"Scope\":{\"WorldSelector\":\"frogotten_temple\"}}]}]}}}");
        assertNotNull(d);

        List<String> codes = DialogueStructureValidator
                .validateAll(List.of(d), Set.of("forgotten_temple", "primary"))
                .stream().map(DialogueStructureValidator.Issue::code).toList();

        assertTrue(codes.contains("WORLD_CONDITION_UNKNOWN_SELECTOR"), codes.toString());
        assertTrue(codes.contains("FLAG_SCOPE_UNKNOWN_SELECTOR"), codes.toString());
    }

    @Test
    void validatorFlagsAWorldConditionThatCanNeverPassAndABlankScope() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("empty",
                "{\"Start\":[{\"Node\":\"g\"}],\"Nodes\":{\"g\":{\"Conditions\":["
                        + "{\"Type\":\"World\",\"ExcludeNames\":[\"arena\"]}],"
                        + "\"Options\":[{\"Label\":\"x\",\"Actions\":[{\"Type\":\"SetFlag\","
                        + "\"Flag\":\"greeted\",\"Scope\":{}}]}]}}}");
        assertNotNull(d);

        List<String> codes = DialogueStructureValidator.validate(d, Set.of("forgotten_temple"))
                .stream().map(DialogueStructureValidator.Issue::code).toList();

        assertTrue(codes.contains("WORLD_CONDITION_NO_AXIS"), codes.toString());
        assertTrue(codes.contains("FLAG_SCOPE_BLANK"), codes.toString());
    }

    @Test
    void validatorStaysSilentOnKnownNamesAndOnAnUnknownVocabulary() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("good",
                "{\"Start\":[{\"Node\":\"g\"}],\"Nodes\":{\"g\":{\"Conditions\":["
                        + "{\"Type\":\"AnyOf\",\"Any\":[{\"Type\":\"World\","
                        + "\"Names\":[\"Forgotten_Temple\"]}]}],"
                        + "\"Options\":[{\"Label\":\"x\",\"Actions\":[{\"Type\":\"SetFlag\","
                        + "\"Flag\":\"greeted\",\"Scope\":{\"WorldSelector\":\"forgotten_temple\"}}]}]}}}");
        assertNotNull(d);

        // Known vocabulary: the name resolves (case-insensitively), even nested in a combinator.
        List<String> known = DialogueStructureValidator.validate(d, Set.of("forgotten_temple"))
                .stream().map(DialogueStructureValidator.Issue::code).toList();
        assertFalse(known.contains("WORLD_CONDITION_UNKNOWN_SELECTOR"), known.toString());
        assertFalse(known.contains("FLAG_SCOPE_UNKNOWN_SELECTOR"), known.toString());

        // Absent / empty vocabulary means "cannot tell" (assets may not have loaded), never a
        // false alarm.
        List<String> unknownPool = DialogueStructureValidator.validate(d, Set.of())
                .stream().map(DialogueStructureValidator.Issue::code).toList();
        assertFalse(unknownPool.contains("WORLD_CONDITION_UNKNOWN_SELECTOR"), unknownPool.toString());
        assertFalse(unknownPool.contains("FLAG_SCOPE_UNKNOWN_SELECTOR"), unknownPool.toString());
        assertFalse(DialogueStructureValidator.validate(d).stream()
                .anyMatch(i -> i.code().equals("FLAG_SCOPE_UNKNOWN_SELECTOR")));
    }
}
