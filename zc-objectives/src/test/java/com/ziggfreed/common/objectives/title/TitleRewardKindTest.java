package com.ziggfreed.common.objectives.title;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.i18n.LangCatalog;
import com.ziggfreed.common.i18n.PlainText;
import com.ziggfreed.common.loot.reward.RewardChip;
import com.ziggfreed.common.loot.reward.RewardHandler;
import com.ziggfreed.common.loot.reward.RewardKindRegistry;
import com.ziggfreed.common.loot.reward.RewardSpec;
import com.ziggfreed.common.subject.Subject;

/**
 * The {@code Title} reward kind's contract with the shared issuance pass: what it reads, what it
 * refuses, that a grant with nobody to grant to fails LOUD with a replayable line while a spec that
 * could never be granted offers none, and how the reward reads on a chip.
 */
class TitleRewardKindTest {

    private static final Subject OFFLINE = Subject.of(UUID.randomUUID(), "Tester");

    @AfterEach
    void clear() {
        LangCatalog.overrideForTests(null);
    }

    private static RewardHandler kind() {
        RewardKindRegistry kinds = new RewardKindRegistry();
        TitleRewardKind.registerInto(kinds);
        RewardHandler handler = kinds.handler("title");
        assertNotNull(handler, "the kind id matches case-insensitively like every other");
        return handler;
    }

    @Test
    void theTitleIsReadUnderEitherSpelling() {
        assertEquals("Hallows_Eve_Hallowed",
                TitleRewardKind.titleOf(RewardSpec.of("Title", Map.of("Title", " Hallows_Eve_Hallowed "))));
        assertEquals("hallows_eve_hallowed",
                TitleRewardKind.titleOf(RewardSpec.of("Title", Map.of("TitleId", "hallows_eve_hallowed"))));
        assertEquals("", TitleRewardKind.titleOf(RewardSpec.of("Title")));
    }

    @Test
    void noLivePlayerFailsLoudAndOffersTheGrantLine() {
        RewardSpec spec = RewardSpec.of("Title", Map.of("Title", "Hallows_Eve_Hallowed"));

        assertThrows(IllegalStateException.class, () -> kind().grant(spec, OFFLINE));
        assertEquals("zigtitle grant --player=Tester --title=Hallows_Eve_Hallowed",
                kind().retryCommand(spec, OFFLINE, "achievement:hallows_eve_meta"),
                "built from the SPEC, in the named-arg form the engine parser binds");
    }

    @Test
    void aSpecNamingNoTitleIsRefusedAndNotReplayable() {
        RewardSpec spec = RewardSpec.of("Title");

        IllegalStateException refusal = assertThrows(IllegalStateException.class, () -> kind().grant(spec, OFFLINE));
        assertTrue(refusal.getMessage().contains("Title"), "the refusal names the parameter to write");
        assertNull(kind().retryCommand(spec, OFFLINE, "achievement:hallows_eve_meta"),
                "a retry would refuse on every attempt, so it is reported lost instead");
    }

    @Test
    void anIdTheSaveFormatCannotHoldIsRefusedAndNotReplayable() {
        for (String bad : new String[] {"hallowed|eve", "events:hallowed"}) {
            RewardSpec spec = RewardSpec.of("Title", Map.of("Title", bad));
            assertThrows(IllegalStateException.class, () -> kind().grant(spec, OFFLINE), bad);
            assertNull(kind().retryCommand(spec, OFFLINE, "achievement:hallows_eve_meta"), bad);
        }
    }

    @Test
    void theChipNamesTheTitleAndAnswersOnlyForATitleRewardThatNamesOne() {
        LangCatalog.overrideForTests(Map.of(
                "ziggfreedcommon.title.chip", "Title: {0}",
                "hallowseve.title.hallows_eve_hallowed.name", "The Hallowed"));

        RewardChip chip = TitleChipReading.source().chipFor(
                RewardSpec.of("Title", Map.of("Title", "Hallows_Eve_Hallowed")));

        assertNotNull(chip);
        assertNull(chip.iconItemId(), "no picture unless the reward authors one");
        assertEquals("Title: The Hallowed", PlainText.of(chip.label()));
        assertNull(TitleChipReading.source().chipFor(RewardSpec.of("Item", Map.of("Item", "Rock_Crystal"))),
                "another kind's reward is somebody else's to name");
        assertNull(TitleChipReading.source().chipFor(RewardSpec.of("Title")), "a title reward naming none is dropped");
    }
}
