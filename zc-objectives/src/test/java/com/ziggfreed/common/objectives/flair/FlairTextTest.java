package com.ziggfreed.common.objectives.flair;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.i18n.LangCatalog;
import com.ziggfreed.common.loot.reward.RewardChip;
import com.ziggfreed.common.loot.reward.RewardSpec;

/**
 * What a flair is called, on every surface at once: the chip a reward panel paints and the name a
 * toast carries both come off {@link FlairText#nameOf}, so this pins the ladder itself - an authored
 * {@code flair.<id>.name} under whatever namespace ships it, else the id spelled out - and that the
 * contributed chip reading follows it.
 */
class FlairTextTest {

    @AfterEach
    void clearCatalogue() {
        LangCatalog.overrideForTests(null);
    }

    @Test
    void aFlairNothingNamesReadsAsItsIdSpelledOut() {
        Message name = FlairText.nameOf("sawmill_gold");

        assertEquals("Sawmill Gold", name.getRawText(), "a traceable fallback, never a raw key");
        assertNull(name.getMessageId());
        assertFalse(FlairText.isNamed("sawmill_gold"));
    }

    @Test
    void aFlairSomeLoadedLangFileNamesReadsThroughThatKey() {
        LangCatalog.overrideForTests(Map.of("rpgstations.flair.sawmill_gold.name", "Gilded Saw"));

        Message name = FlairText.nameOf("sawmill_gold");

        assertEquals("rpgstations.flair.sawmill_gold.name", name.getMessageId(),
                "resolved under the namespace that ships it, whichever mod that is");
        assertNull(name.getRawText());
        assertTrue(FlairText.isNamed("sawmill_gold"));
    }

    @Test
    void theChipReadsTheSameNameTheToastDoes() {
        RewardChip chip = chipFor(RewardSpec.of("Flair", Map.of("FlairId", "sawmill_gold")));

        assertNotNull(chip);
        assertNull(chip.iconItemId(), "no picture unless the reward authors one");
        assertEquals("Sawmill Gold", chip.label().getRawText());

        LangCatalog.overrideForTests(Map.of("rpgstations.flair.sawmill_gold.name", "Gilded Saw"));
        assertEquals("rpgstations.flair.sawmill_gold.name",
                chipFor(RewardSpec.of("Flair", Map.of("Flair", "sawmill_gold"))).label().getMessageId());
    }

    @Test
    void theChipReadingAnswersOnlyForAFlairThatNamesOne() {
        assertNull(chipFor(RewardSpec.of("Item", Map.of("Item", "Rock_Crystal"))),
                "another kind's reward is somebody else's to name");
        assertNull(chipFor(RewardSpec.of("Flair")), "a flair reward naming no flair is dropped");
    }

    private static RewardChip chipFor(RewardSpec spec) {
        return FlairChipReading.source().chipFor(spec);
    }
}
