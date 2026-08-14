package com.ziggfreed.common.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.ziggfreed.common.dialogue.validate.DialogueStructureValidator;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * The findings for the ways an authored conversation is quietly wrong: a shorthand that never runs,
 * two actions where only one can win, a shared option group that was never declared, a quest state
 * that is not a state, and a line this game cannot answer or act on.
 *
 * <p>Every one of them renders as a perfectly normal page and then misbehaves, which is exactly why
 * they are worth reporting at startup.
 */
class DialogueAuthoringAuditTest {

    @BeforeEach
    void resetDialogueTypes() {
        DialogueTestSupport.reset();
    }

    @Nonnull
    private static DialogueEngine engine() {
        return DialogueEngine.builder().warn(m -> { }).build();
    }

    @Nonnull
    private static List<String> codes(@Nonnull List<Finding> findings) {
        return DialogueTestSupport.codes(findings);
    }

    @Nonnull
    private static Finding finding(@Nonnull List<Finding> findings, @Nonnull String code) {
        Finding found = findings.stream().filter(f -> f.code().equals(code)).findFirst().orElse(null);
        assertNotNull(found, "expected a " + code + " finding in " + codes(findings));
        return found;
    }

    @Test
    void everyFindingIsStampedWithTheDialogueDomain() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("guide",
                "{\"Start\":{\"First\":[{\"Node\":\"missing\"}]},\"Nodes\":{\"g\":{\"Options\":[]}}}");
        assertNotNull(d);

        List<Finding> findings = DialogueStructureValidator.validate(d);
        assertFalse(findings.isEmpty());
        assertTrue(findings.stream().allMatch(f -> DialogueStructureValidator.DOMAIN.equals(f.domain())),
                findings.toString());
        assertEquals(Severity.ERROR, finding(findings, "START_MISSING_NODE").severity());
    }

    @Test
    void bareShorthandBesideADoArrayIsReported() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("guide",
                "{\"Start\":{\"First\":[{\"Node\":\"g\"}]},\"Nodes\":{\"g\":{\"Options\":["
                        + "{\"LabelKey\":\"a\",\"Goto\":\"g\",\"Do\":[{\"Close\":true}]}]}}}");
        assertNotNull(d);

        assertEquals(Severity.WARNING,
                finding(DialogueStructureValidator.validate(d), "SUGAR_SHADOWED_BY_DO").severity());
        assertEquals(1, d.getNode("g").getOptions().get(0).getActions().size(),
                "and the shadowed shorthand really does not run");
    }

    @Test
    void twoJumpsOnOneOptionAreReported() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("guide",
                "{\"Start\":{\"First\":[{\"Node\":\"g\"}]},\"Nodes\":{\"g\":{\"Options\":["
                        + "{\"LabelKey\":\"a\",\"Actions\":[{\"Type\":\"Goto\",\"Node\":\"g\"}],"
                        + "\"Goto\":\"g\"}]}}}");
        assertNotNull(d);

        assertEquals(Severity.WARNING,
                finding(DialogueStructureValidator.validate(d), "DUPLICATE_ACTION").severity());
    }

    @Test
    void aSharedOptionGroupThatWasNeverDeclaredIsAnError() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("guide",
                "{\"Start\":{\"First\":[{\"Node\":\"g\"}]},\"Nodes\":{\"g\":{\"Options\":[],"
                        + "\"IncludeOptions\":[\"footer\"]}}}");
        assertNotNull(d);

        assertEquals(Severity.ERROR,
                finding(DialogueStructureValidator.validate(d), "UNKNOWN_FRAGMENT").severity());
    }

    @Test
    void aQuestStateThatIsNotAStateIsAnError() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("guide",
                "{\"Start\":{\"First\":[{\"Node\":\"g\",\"When\":[{\"Type\":\"QuestState\","
                        + "\"Quest\":\"intro\",\"State\":\"DONE\"}]}]},"
                        + "\"Nodes\":{\"g\":{\"Options\":[]}}}");
        assertNotNull(d);

        assertEquals(Severity.ERROR,
                finding(DialogueStructureValidator.validate(d), "QUEST_STATE_UNKNOWN").severity());
    }

    @Test
    void aQuestLineWithNoQuestIdIsAnError() {
        DialogueEngine engine = engine();
        NpcDialogue d = engine.decode("guide",
                "{\"Start\":{\"First\":[{\"Node\":\"g\"}]},\"Nodes\":{\"g\":{\"Options\":["
                        + "{\"LabelKey\":\"a\",\"Conditions\":[{\"Type\":\"ReadyToTurnIn\"}],"
                        + "\"Actions\":[{\"Type\":\"TurnInQuest\"}]}]}}}");
        assertNotNull(d);

        List<String> codes = codes(DialogueStructureValidator.validate(d));
        assertTrue(codes.contains("QUEST_CONDITION_NO_ID"), codes.toString());
        assertTrue(codes.contains("QUEST_ACTION_NO_ID"), codes.toString());
    }

    /**
     * The shared schema can read every mod's vocabulary, so a file may carry a line THIS game cannot
     * answer or act on. It renders and then does nothing, which is worth saying out loud.
     */
    @Test
    void aTypeThisGameCannotServeIsReportedWhenAnEngineIsGiven() {
        DialogueEngine full = DialogueEngine.builder().warn(m -> { })
                .condition(DialogueConditionType.of("Elsewhere", Elsewhere.class, Elsewhere.CODEC,
                        (c, ctx) -> true))
                .action(DialogueActionType.of("DoElsewhere", DoElsewhere.class, DoElsewhere.CODEC,
                        (a, ctx, out) -> { }))
                .build();
        NpcDialogue d = full.decode("guide",
                "{\"Start\":{\"First\":[{\"Node\":\"g\"}]},\"Nodes\":{\"g\":{\"Options\":["
                        + "{\"LabelKey\":\"a\",\"Conditions\":[{\"Type\":\"Elsewhere\"}],"
                        + "\"Actions\":[{\"Type\":\"DoElsewhere\"}]}]}}}");
        assertNotNull(d);

        // The engine that owns those types is happy with the file.
        assertTrue(codes(DialogueStructureValidator.validate(d, null, full)).isEmpty()
                        || !codes(DialogueStructureValidator.validate(d, null, full))
                                .contains("UNKNOWN_CONDITION_TYPE"),
                "the mod that registered them can serve them");

        // A second game, sharing the store but not the vocabulary, is told what it cannot serve.
        DialogueEngine other = DialogueEngine.builder().warn(m -> { }).build();
        List<String> codes = codes(DialogueStructureValidator.validate(d, null, other));
        assertTrue(codes.contains("UNKNOWN_CONDITION_TYPE"), codes.toString());
        assertTrue(codes.contains("UNKNOWN_ACTION_TYPE"), codes.toString());

        // With no engine to compare against, the check is skipped rather than guessed at.
        assertFalse(codes(DialogueStructureValidator.validate(d)).contains("UNKNOWN_ACTION_TYPE"));
    }

    public static final class Elsewhere extends DialogueCondition {
        public static final BuilderCodec<Elsewhere> CODEC =
                BuilderCodec.builder(Elsewhere.class, Elsewhere::new).build();
    }

    public static final class DoElsewhere extends DialogueAction {
        public static final BuilderCodec<DoElsewhere> CODEC =
                BuilderCodec.builder(DoElsewhere.class, DoElsewhere::new).build();
    }
}
