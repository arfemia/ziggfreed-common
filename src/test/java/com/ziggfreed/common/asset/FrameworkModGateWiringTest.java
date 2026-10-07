package com.ziggfreed.common.asset;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Every store whose files carry a top-level {@code Requires} folds its load event through a mod-gate
 * keep filter, so a file gated on a mod this server lacks never reaches the store. A source scan,
 * because the alternative is standing up the engine's asset registry for one lambda argument, and
 * because a handler that silently lost its filter would let a server without the MMO log MMO lines.
 *
 * <p>Not listed, because their files carry no top-level {@code Requires} and so cannot be gated:
 * QuestGenerators and ShopEntryGenerators (a family follows its {@code Base}); DialogueFragments,
 * DialogueExtensions and Dialogues (a line gates on its own {@code Conditions}); Instances, RollPools,
 * StatDisplays, ObjectiveKinds, OverheadIndicators, QuestIndicators, RewardKinds, BandedEffects,
 * PrefabPlacements, Leaderboard, Arenas, Party, DialogueOptionTheme, NpcIdentities, Factors,
 * FeedbackMoments, HudRows, HudSpots, HudPanels, HudCards, PlayerSettings, AchievementCategories,
 * QuestCategories, AchievementMilestones, Almanac, ShopPools, EncounterBindings,
 * EncounterParticipation, CalendarEvents, CalendarSpawns, Titles and Reputations. A store that gains a
 * top-level {@code Requires} moves from this paragraph into {@code GATED}.
 */
class FrameworkModGateWiringTest {

    private static final Path REGISTRAR = Path.of("src", "main", "java", "com", "ziggfreed",
            "common", "asset", "FrameworkAssetRegistrar.java");

    /** Every asset class with a top-level Requires whose store the registrar loads. */
    private static final List<String> GATED = List.of(
            "LootableAsset", "BonusRowAsset", "NpcPlacementAsset", "QuestAsset", "AchievementAsset",
            "CurrencyAsset", "StorefrontAsset", "ShopEntryAsset", "BoardAsset", "BountyAsset", "GearSetAsset");

    @Test
    void everyGatedStoresLoadHandlerPassesItsModGateFilter() throws IOException {
        assertTrue(Files.isRegularFile(REGISTRAR), "missing " + REGISTRAR.toAbsolutePath());
        String source = Files.readString(REGISTRAR, StandardCharsets.UTF_8);

        for (String asset : GATED) {
            String handler = handlerOf(source, asset);
            assertTrue(handler.contains("passesModGate("),
                    () -> asset + "'s load handler must fold through AssetMergeAdapter.layer(map, keep) with its "
                            + "passesModGate filter, or a file gated on an absent mod reaches the store. It reads: "
                            + handler);
        }
    }

    /** The text from the class's LoadedAssetsEvent registration to the next store's registration. */
    private static String handlerOf(String source, String asset) {
        String marker = "register(LoadedAssetsEvent.class, " + asset + ".class,";
        int start = source.indexOf(marker);
        assertTrue(start >= 0, "no LoadedAssetsEvent handler for " + asset);
        int end = source.indexOf("AssetStoreRegistrar.registerStore(", start);
        return end < 0 ? source.substring(start) : source.substring(start, end);
    }
}
