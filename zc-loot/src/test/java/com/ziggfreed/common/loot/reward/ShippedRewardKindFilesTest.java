package com.ziggfreed.common.loot.reward;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.i18n.LangCatalog;

/**
 * The three presentation-only kind files this module SHIPS - {@code Lootable}, {@code Droplist},
 * {@code Effect} - pinned end to end: each decodes command-less (the built-in Java handler keeps
 * the payout), and with its shipped lang key in the catalogue a reward of that kind reads as the
 * generic localized line under its stand-in icon, with no consumer Java at all.
 *
 * <p>The three kinds roll or apply their payload only when they pay out, so none can promise a
 * specific item ahead of time; what they must never do is paint the raw kind word. These files are
 * why every consumer's chip surfaces get a readable line for them out of the box.
 */
class ShippedRewardKindFilesTest {

    @AfterEach
    void clearFixtures() {
        RewardKindConfig.getInstance().loadDefaults(Map.of());
        LangCatalog.overrideForTests(null);
    }

    private static RewardKindAsset shipped(String id) throws IOException {
        String path = "/Server/ZiggfreedCommon/RewardKinds/" + id + ".json";
        try (InputStream in = ShippedRewardKindFilesTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "missing shipped kind file: " + path);
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return RewardKindAssetCodecTest.decodeRoot(json, id);
        }
    }

    @Test
    void eachShippedFileIsPresentationOnlyWithItsOwnKeyAndIcon() throws Exception {
        record Expected(String id, String key, String icon) {
        }
        for (Expected e : new Expected[] {
                new Expected("Lootable", "ziggfreedcommon.loot.reward.lootable",
                        "Furniture_Ancient_Chest_Small"),
                new Expected("Droplist", "ziggfreedcommon.loot.reward.droplist",
                        "Furniture_Ancient_Crate"),
                new Expected("Effect", "ziggfreedcommon.loot.reward.effect", "Potion_Health")}) {
            RewardKindAsset kind = shipped(e.id());
            assertTrue(kind.isBlank(),
                    e.id() + " must name no Command: the built-in handler keeps the payout");
            assertNotNull(kind.getPresentation());
            assertEquals(e.key(), kind.getPresentation().getNameKey());
            assertNotNull(kind.getPresentation().getIcon());
            assertEquals(e.icon(), kind.getPresentation().getIcon().getDefaultItem());
        }
    }

    @Test
    void aLootableRewardReadsAsTheGenericLineUnderTheChestIcon() throws Exception {
        RewardKindAsset kind = shipped("Lootable");
        RewardKindConfig.getInstance().loadDefaults(Map.of(kind.getId(), kind));
        LangCatalog.overrideForTests(
                Map.of("ziggfreedcommon.loot.reward.lootable", "Loot Bundle"));

        RewardChip chip = RewardChips.chipFor(
                RewardSpec.of("Lootable", Map.of("Lootable", "forest_finds")));
        assertNotNull(chip, "the shipped kind file names every lootable reward with no Java");
        assertEquals("Furniture_Ancient_Chest_Small", chip.iconItemId());
        assertEquals("ziggfreedcommon.loot.reward.lootable",
                chip.label().getFormattedMessage().messageId,
                "the line is the shipped localization key, never the raw kind word");
        assertNull(chip.label().getFormattedMessage().rawText);
    }
}
