package com.ziggfreed.common.asset;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Every store whose files carry a top-level {@code Requires} folds its load event through the reporting
 * mod-gate fold ({@code AssetMergeAdapter.gate}) under its own store name, with its {@code missingMod}
 * read, so a file gated on a mod this server lacks never reaches the store, and the drop is logged as one
 * counted line under the name the season boot pair parses. A source scan, because the alternative is
 * standing up the engine's asset registry for one lambda argument, and because a handler that silently
 * lost its fold would let a server without the MMO log MMO lines.
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

    /** Every asset class with a top-level Requires whose store the registrar loads, to its store name. */
    private static final Map<String, String> GATED = new LinkedHashMap<>();

    static {
        GATED.put("LootableAsset", "Lootables");
        GATED.put("BonusRowAsset", "BonusRows");
        GATED.put("NpcPlacementAsset", "NpcPlacements");
        GATED.put("QuestAsset", "Quests");
        GATED.put("AchievementAsset", "Achievements");
        GATED.put("CurrencyAsset", "Currencies");
        GATED.put("StorefrontAsset", "Shops");
        GATED.put("ShopEntryAsset", "ShopEntries");
        GATED.put("BoardAsset", "Boards");
        GATED.put("BountyAsset", "Bounties");
        GATED.put("GearSetAsset", "GearSets");
    }

    @Test
    void everyGatedStoresLoadHandlerFoldsThroughTheReportingGateUnderItsStoreName() throws IOException {
        assertTrue(Files.isRegularFile(REGISTRAR), "missing " + REGISTRAR.toAbsolutePath());
        String source = Files.readString(REGISTRAR, StandardCharsets.UTF_8);

        for (Map.Entry<String, String> gated : GATED.entrySet()) {
            String asset = gated.getKey();
            String handler = handlerOf(source, asset);
            String fold = "AssetMergeAdapter.gate(\"" + gated.getValue() + "\",";
            assertTrue(handler.contains(fold) && handler.contains("missingMod("),
                    () -> asset + "'s load handler must fold through " + fold + " ...) with its missingMod read, or "
                            + "a file gated on an absent mod reaches the store, or its drop line names the wrong "
                            + "store. It reads: " + handler);
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
