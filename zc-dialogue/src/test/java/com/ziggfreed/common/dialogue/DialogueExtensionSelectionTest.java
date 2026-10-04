package com.ziggfreed.common.dialogue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.dialogue.schema.DialogueExtension;
import com.ziggfreed.common.dialogue.schema.DialogueExtensionConfig;
import com.ziggfreed.common.dialogue.schema.DialogueOption;
import com.ziggfreed.common.dialogue.schema.DialogueSelector;
import com.ziggfreed.common.dialogue.schema.NodeSelector;
import com.ziggfreed.common.dialogue.state.DialogueOnce;

/**
 * Where a dialogue extension's lines land, decided before any conversation is touched: every
 * conversation's opening screens when nothing is written, {@code Dialogues} to choose conversations,
 * {@code On} to choose screens, and the lines handed out as marked copies in extension id order.
 */
class DialogueExtensionSelectionTest {

    @BeforeEach
    void resetDialogueTypes() {
        DialogueTestSupport.reset();
        DialogueEngine.builder().warn(m -> { }).build();
    }

    /**
     * The extension layer is process-wide and an installed extension lands on every conversation's
     * opening screens, so this leaves it EMPTY for the test classes in this JVM that never reset.
     */
    @AfterEach
    void leaveNoExtensionInstalled() {
        DialogueExtensionConfig.getInstance().mergePackLayer(Map.of());
    }

    @Nonnull
    private static DialogueOption[] oneLine(@Nonnull String labelKey) {
        return DialogueTestSupport.optionRows("[ { \"LabelKey\": \"" + labelKey + "\" } ]");
    }

    @Test
    void anExtensionHandsOutMarkedCopiesUnderItsFoldedId() {
        DialogueOption[] rows = DialogueTestSupport.optionRows("""
                [ { "LabelKey": "hallows_eve.trick", "OnceId": "treat", "Once": { "Period": "Daily" } } ]
                """);
        DialogueExtension extension = DialogueExtension.of("Hallows_Eve_Trick_Or_Treat", rows, null, null, true);
        assertEquals("hallows_eve_trick_or_treat", extension.getId());
        DialogueOption line = extension.getOptions().get(0);
        assertEquals("hallows_eve_trick_or_treat", line.getInjectedBy());
        assertTrue(line.isInjected());
        assertEquals("hallows_eve.trick", line.getLabelKey());
        assertEquals(DialogueOnce.Period.DAILY, line.getOnce().getPeriod());
        assertNull(rows[0].getInjectedBy(), "the decoded row is copied, never marked in place");
    }

    @Test
    void unauthoredSelectorsMeanEveryConversationsOpeningScreens() {
        DialogueExtension everywhere = DialogueExtension.of("trick", oneLine("t"), null, null, true);
        assertTrue(everywhere.lands("old_jack", "menu", List.of(), true));
        assertTrue(everywhere.lands("mmo_hub_intro", "greet", List.of(), true));
        assertFalse(everywhere.lands("old_jack", "deep", List.of(), false), "not a screen the conversation opens on");
    }

    @Test
    void dialoguesIdsAndExcludeChooseConversationsWithoutRegardToCase() {
        DialogueOption[] rows = oneLine("t");
        DialogueExtension only = DialogueExtension.of("trick", rows,
                DialogueSelector.of(new String[] {"Old_Jack"}, null), null, true);
        assertTrue(only.lands("old_jack", "menu", List.of(), true));
        assertFalse(only.lands("smith", "menu", List.of(), true));
        DialogueExtension allBut = DialogueExtension.of("trick", rows,
                DialogueSelector.of(null, new String[] {"KWEEBEC_LOBBY"}), null, true);
        assertTrue(allBut.lands("old_jack", "menu", List.of(), true));
        assertFalse(allBut.lands("kweebec_lobby", "menu", List.of(), true), "Exclude wins");
        DialogueExtension blankIds = DialogueExtension.of("trick", rows,
                DialogueSelector.of(new String[] {" "}, null), null, true);
        assertTrue(blankIds.lands("old_jack", "menu", List.of(), true), "a blank id names nothing, so every conversation");
    }

    @Test
    void onChoosesScreensByIdOrTagInsteadOfTheOpeningOnes() {
        DialogueExtension tagged = DialogueExtension.of("trick", oneLine("t"), null,
                NodeSelector.of(null, new String[] {"Greeting"}, null), true);
        assertTrue(tagged.lands("old_jack", "deep", List.of("greeting"), false), "a tagged screen, opening or not");
        assertFalse(tagged.lands("old_jack", "menu", List.of(), true),
                "once On is written, an untagged opening screen is not chosen");
    }

    @Test
    void aDisabledOrEmptyExtensionLandsNowhere() {
        assertFalse(DialogueExtension.of("trick", oneLine("t"), null, null, false)
                .lands("old_jack", "menu", List.of(), true));
        assertFalse(DialogueExtension.of("trick", new DialogueOption[0], null, null, true)
                .lands("old_jack", "menu", List.of(), true));
    }

    @Test
    void theConfigHandsOutLinesInExtensionIdOrder() {
        DialogueTestSupport.shareExtensions(
                DialogueExtension.of("b_second", oneLine("b"), null, null, true),
                DialogueExtension.of("A_First", oneLine("a"), null, null, true));
        List<String> labels = DialogueExtensionConfig.getInstance()
                .linesFor("old_jack", "menu", List.of(), true).stream()
                .map(DialogueOption::getLabelKey).toList();
        assertEquals(List.of("a", "b"), labels, "id order, whatever order the files arrived in");
        assertTrue(DialogueExtensionConfig.getInstance().linesFor("old_jack", "deep", List.of(), false).isEmpty());
        assertFalse(DialogueExtensionConfig.getInstance().isEmpty());
    }
}
