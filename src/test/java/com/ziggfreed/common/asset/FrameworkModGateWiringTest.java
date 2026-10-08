package com.ziggfreed.common.asset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.achievement.asset.AchievementAssetStore;
import com.ziggfreed.common.achievement.asset.AchievementMilestoneConfig;
import com.ziggfreed.common.board.asset.BoardAssetStore;
import com.ziggfreed.common.board.asset.BoardConfig;
import com.ziggfreed.common.currency.asset.CurrencyConfig;
import com.ziggfreed.common.loot.LootableConfig;
import com.ziggfreed.common.loot.trigger.BonusRowConfig;
import com.ziggfreed.common.npc.placement.asset.NpcPlacementConfig;
import com.ziggfreed.common.quest.asset.QuestAssetStore;
import com.ziggfreed.common.reputation.asset.ReputationConfig;
import com.ziggfreed.common.shop.asset.ShopAssetStore;
import com.ziggfreed.common.shop.asset.ShopConfig;
import com.ziggfreed.common.stats.gearset.GearSetConfig;

/**
 * Every store whose files carry a top-level {@code Requires} folds its load event through the reporting
 * mod-gate fold ({@code AssetMergeAdapter.gate}) under its contract label, the {@code MOD_GATE_STORE}
 * constant its store declares, with its {@code missingMod} read, so a file gated on a mod this server lacks
 * never reaches the store, and the drop is logged as one counted line under the name the season boot pair
 * parses. Every owner reader of a gated store passes that store's {@code missingMod} read too, so an owner
 * entry gated on a missing mod takes its id out rather than standing as an ungated override. Source scans,
 * because the alternative is standing up the engine's asset registry for one lambda argument, and because a
 * handler or an owner reader that silently lost its read (a {@code c -> null}) would let a server without
 * the MMO show MMO content.
 *
 * <p>Not listed, because their files carry no top-level {@code Requires} and so cannot be gated:
 * QuestGenerators and ShopEntryGenerators (a family follows its {@code Base}); DialogueFragments,
 * DialogueExtensions and Dialogues (a line gates on its own {@code Conditions}); Instances, RollPools,
 * StatDisplays, ObjectiveKinds, OverheadIndicators, QuestIndicators, RewardKinds, BandedEffects,
 * PrefabPlacements, Leaderboard, Arenas, Party, DialogueOptionTheme, NpcIdentities, Factors,
 * FeedbackMoments, HudRows, HudSpots, HudPanels, HudCards, PlayerSettings, AchievementCategories,
 * QuestCategories, AchievementMilestones, Almanac, ShopPools, EncounterBindings,
 * EncounterParticipation, CalendarEvents, CalendarSpawns, Titles and Reputations. A store that gains a
 * top-level {@code Requires} moves from this paragraph into {@code GATED}, and into {@code OWNER_READERS}
 * or {@code NO_OWNER_FILE}.
 *
 * <p>The third kind of drop line counts reward ROWS: a row's own {@code Requires} keeps it out where its
 * mod is missing, and every store whose files carry rows counts what its fold left out under its contract
 * label ({@code REWARD_ROW_STORES}), AchievementMilestones and Reputations among them although no file of
 * theirs can be gated. A class whose rows are read inline, outside any store's fold, is listed in
 * {@code INLINE_ROWS}: its gated rows are absent too, and never counted.
 */
class FrameworkModGateWiringTest {

    private static final Path REGISTRAR = Path.of("src", "main", "java", "com", "ziggfreed",
            "common", "asset", "FrameworkAssetRegistrar.java");

    /** A gated store: its contract label, the class declaring {@code MOD_GATE_STORE}, and that constant. */
    private record Gated(String label, String declaredBy, String declared) {
    }

    /** Every asset class with a top-level Requires whose store the registrar loads, to its store. */
    private static final Map<String, Gated> GATED = new LinkedHashMap<>();

    static {
        GATED.put("LootableAsset", new Gated("Lootables", "LootableConfig", LootableConfig.MOD_GATE_STORE));
        GATED.put("BonusRowAsset", new Gated("BonusRows", "BonusRowConfig", BonusRowConfig.MOD_GATE_STORE));
        GATED.put("NpcPlacementAsset",
                new Gated("NpcPlacements", "NpcPlacementConfig", NpcPlacementConfig.MOD_GATE_STORE));
        GATED.put("QuestAsset", new Gated("Quests", "QuestAssetStore", QuestAssetStore.MOD_GATE_STORE));
        GATED.put("AchievementAsset",
                new Gated("Achievements", "AchievementAssetStore", AchievementAssetStore.MOD_GATE_STORE));
        GATED.put("CurrencyAsset", new Gated("Currencies", "CurrencyConfig", CurrencyConfig.MOD_GATE_STORE));
        GATED.put("StorefrontAsset", new Gated("Shops", "ShopConfig", ShopConfig.MOD_GATE_STORE));
        GATED.put("ShopEntryAsset", new Gated("ShopEntries", "ShopAssetStore", ShopAssetStore.MOD_GATE_STORE));
        GATED.put("BoardAsset", new Gated("Boards", "BoardConfig", BoardConfig.MOD_GATE_STORE));
        GATED.put("BountyAsset", new Gated("Bounties", "BoardAssetStore", BoardAssetStore.MOD_GATE_STORE));
        GATED.put("GearSetAsset", new Gated("GearSets", "GearSetConfig", GearSetConfig.MOD_GATE_STORE));
    }

    /**
     * A gated store's owner reader: the call its load handler makes (null where the store's own fold reads
     * the owner file), the reader's source, the method in it that reads the file, and the store's
     * {@code missingMod} read that method must pass.
     */
    private record OwnerReader(String asset, String wiredBy, Path source, String method, String read) {
    }

    private static final List<OwnerReader> OWNER_READERS = List.of(
            new OwnerReader("BonusRowAsset", "BonusRowOwnerLayers.reload();",
                    module("zc-loot", "loot", "trigger", "BonusRowOwnerLayers.java"),
                    "public static void reload()", "PresenceRequiresCodec.missingMod("),
            new OwnerReader("CurrencyAsset", "CommerceOwnerLayers.reloadCurrencies();",
                    module("zc-commerce", "commerce", "fold", "CommerceOwnerLayers.java"),
                    "public static void reloadCurrencies()", "GateSpec.missingMod("),
            new OwnerReader("StorefrontAsset", "CommerceOwnerLayers.reloadShops();",
                    module("zc-commerce", "commerce", "fold", "CommerceOwnerLayers.java"),
                    "public static void reloadShops()", "GateSpec.missingMod("),
            new OwnerReader("BoardAsset", "CommerceOwnerLayers.reloadBoards();",
                    module("zc-commerce", "commerce", "fold", "CommerceOwnerLayers.java"),
                    "public static void reloadBoards()", "GateSpec.missingMod("),
            new OwnerReader("GearSetAsset", "GearSetOwnerLayers.reload();",
                    module("zc-entity", "stats", "gearset", "GearSetOwnerLayers.java"),
                    "public static void reload()", "PresenceRequiresCodec.missingMod("),
            new OwnerReader("NpcPlacementAsset", "NpcPlacementOverrides.getInstance().applyOwnerLayer();",
                    module("zc-dialogue", "npc", "placement", "asset", "NpcPlacementOverrides.java"),
                    "public void applyOwnerLayer()", "NpcPlacementAsset.Requires.missingMod("),
            new OwnerReader("QuestAsset", null,
                    module("zc-progression", "quest", "asset", "QuestOwnerLayers.java"),
                    "private static QuestAsset decode(", "GateSpec.missingMod("));

    /** The gated stores that keep no owner file, so there is no owner reader to wire. */
    private static final Set<String> NO_OWNER_FILE =
            Set.of("LootableAsset", "AchievementAsset", "ShopEntryAsset", "BountyAsset");

    private static final Path QUEST_STORE = module("zc-progression", "quest", "asset", "QuestAssetStore.java");

    /**
     * A store whose files carry reward rows: the asset class holding them, its contract label, the class
     * declaring {@code MOD_GATE_STORE} and that constant, and the method in which its fold counts the rows
     * a row's own gate left out.
     */
    private record RewardRows(String asset, String label, String declaredBy, String declared, Path source,
            String method) {
    }

    private static final List<RewardRows> REWARD_ROW_STORES = List.of(
            new RewardRows("QuestAsset", "Quests", "QuestAssetStore", QuestAssetStore.MOD_GATE_STORE,
                    QUEST_STORE, "public Resolution resolve("),
            new RewardRows("AchievementAsset", "Achievements", "AchievementAssetStore",
                    AchievementAssetStore.MOD_GATE_STORE,
                    module("zc-progression", "achievement", "asset", "AchievementAssetStore.java"),
                    "public Resolution resolve()"),
            new RewardRows("AchievementMilestoneAsset", "AchievementMilestones", "AchievementMilestoneConfig",
                    AchievementMilestoneConfig.MOD_GATE_STORE,
                    module("zc-progression", "achievement", "asset", "AchievementMilestoneConfig.java"),
                    "public synchronized void mergePackLayer("),
            new RewardRows("BountyAsset", "Bounties", "BoardAssetStore", BoardAssetStore.MOD_GATE_STORE,
                    module("zc-commerce", "board", "asset", "BoardAssetStore.java"), "public Resolution resolve()"),
            new RewardRows("ShopEntryAsset", "ShopEntries", "ShopAssetStore", ShopAssetStore.MOD_GATE_STORE,
                    module("zc-commerce", "shop", "asset", "ShopAssetStore.java"), "public Resolution resolve("),
            new RewardRows("ReputationAsset", "Reputations", "ReputationConfig", ReputationConfig.MOD_GATE_STORE,
                    module("zc-reputation", "reputation", "asset", "ReputationOwnerLayers.java"),
                    "public static void reload()"));

    /**
     * The classes whose reward rows are read inline (a dialogue action's, an interaction's) or that are the
     * shared group itself: a gated row there is absent, and no store fold counts it.
     */
    private static final Set<String> INLINE_ROWS =
            Set.of("ContentRewardsAsset", "GrantDialogueAction", "ZigGrantRewardInteraction");

    /** A field holding reward rows, as a codec-backed class declares one. */
    private static final Pattern ROW_FIELD = Pattern.compile(
            "^\\s*(@Nullable\\s+)?((protected|private|public)\\s+)?(RewardEntryAsset\\[\\]|ContentRewardsAsset)\\s+\\w+;",
            Pattern.MULTILINE);

    private static Path module(String module, String... packageAndFile) {
        Path path = Path.of(module, "src", "main", "java", "com", "ziggfreed", "common");
        for (String part : packageAndFile) {
            path = path.resolve(part);
        }
        return path;
    }

    private static String read(Path file) throws IOException {
        assertTrue(Files.isRegularFile(file), "missing " + file.toAbsolutePath());
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    @Test
    void everyGatedStoresLoadHandlerFoldsThroughTheReportingGateUnderItsStoreLabel() throws IOException {
        String source = read(REGISTRAR);

        for (Map.Entry<String, Gated> gated : GATED.entrySet()) {
            String asset = gated.getKey();
            Gated store = gated.getValue();
            assertEquals(store.label(), store.declared(),
                    store.declaredBy() + ".MOD_GATE_STORE must be the contract label the season boot pair parses");
            String handler = handlerOf(source, asset);
            String fold = "AssetMergeAdapter.gate(" + store.declaredBy() + ".MOD_GATE_STORE,";
            assertTrue(handler.replaceAll("\\s+", "").contains(fold) && handler.contains("missingMod("),
                    () -> asset + "'s load handler must fold through " + fold + " ...) with its missingMod read, or "
                            + "a file gated on an absent mod reaches the store, or its drop line names the wrong "
                            + "store. It reads: " + handler);
        }
    }

    /** An owner drop line names the store's contract label even before any gated fold has run. */
    @Test
    void everyKeyedGatedConfigNamesItsContractLabelForItsOwnerLine() {
        assertEquals("Lootables", LootableConfig.getInstance().modGateStore());
        assertEquals("BonusRows", BonusRowConfig.getInstance().modGateStore());
        assertEquals("NpcPlacements", NpcPlacementConfig.getInstance().modGateStore());
        assertEquals("Currencies", CurrencyConfig.getInstance().modGateStore());
        assertEquals("Shops", ShopConfig.getInstance().modGateStore());
        assertEquals("Boards", BoardConfig.getInstance().modGateStore());
        assertEquals("GearSets", GearSetConfig.getInstance().modGateStore());
    }

    @Test
    void everyOwnerReaderOfAGatedStorePassesThatStoresMissingModRead() throws IOException {
        Set<String> covered = new LinkedHashSet<>(NO_OWNER_FILE);
        String registrar = read(REGISTRAR);

        for (OwnerReader reader : OWNER_READERS) {
            assertTrue(GATED.containsKey(reader.asset()), reader.asset() + " is not a gated store");
            covered.add(reader.asset());
            if (reader.wiredBy() == null) {
                assertTrue(read(QUEST_STORE).contains("QuestOwnerLayers.read("),
                        "the quest fold must read the owner folder through QuestOwnerLayers");
            } else {
                assertTrue(handlerOf(registrar, reader.asset()).contains(reader.wiredBy()),
                        reader.asset() + "'s load handler must read its owner file through " + reader.wiredBy());
            }
            String body = methodBody(read(reader.source()), reader.method(), reader.source());
            assertTrue(body.contains(reader.read()),
                    () -> reader.source().getFileName() + " " + reader.method() + " must pass " + reader.read()
                            + "...), or an owner entry gated on a missing mod stands as an ungated override. It reads: "
                            + body);
            assertFalse(body.contains("-> null"),
                    () -> reader.source().getFileName() + " " + reader.method() + " reads no gate at all: " + body);
        }
        assertEquals(GATED.keySet(), covered,
                "every gated store has an owner reader listed here, or is listed as keeping no owner file");
    }

    @Test
    void everyStoreCarryingRewardRowsCountsWhatTheRowGateLeftOutUnderItsStoreLabel() throws IOException {
        for (RewardRows store : REWARD_ROW_STORES) {
            assertEquals(store.label(), store.declared(),
                    store.declaredBy() + ".MOD_GATE_STORE must be the contract label the season boot pair parses");
            boolean declaredHere = store.source().getFileName().toString().equals(store.declaredBy() + ".java");
            String call = "ModGates.reportRewardRows(" + (declaredHere ? "" : store.declaredBy() + ".")
                    + "MOD_GATE_STORE,";
            String body = methodBody(read(store.source()), store.method(), store.source());
            assertTrue(body.replaceAll("\\s+", "").contains(call),
                    () -> store.source().getFileName() + " " + store.method() + " must count its rows through "
                            + call + " ...), or a row gated on an absent mod drops uncounted, or under the wrong "
                            + "store. It reads: " + body);
        }
        assertEquals("AchievementMilestones", AchievementMilestoneConfig.getInstance().modGateStore());
        assertEquals("Reputations", ReputationConfig.getInstance().modGateStore());
    }

    /**
     * Every class holding reward rows is a store listed in {@code REWARD_ROW_STORES} or a reader listed in
     * {@code INLINE_ROWS}, so a new store carrying rows cannot drop them without its line.
     */
    @Test
    void everyClassHoldingRewardRowsIsAListedStoreOrAnInlineReader() throws IOException {
        Set<String> holders = new TreeSet<>();
        try (Stream<Path> modules = Files.list(Path.of("."))) {
            for (Path module : modules.filter(p -> p.getFileName().toString().startsWith("zc-")).toList()) {
                Path sources = module.resolve(Path.of("src", "main", "java"));
                if (!Files.isDirectory(sources)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(sources)) {
                    for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                        if (ROW_FIELD.matcher(Files.readString(file, StandardCharsets.UTF_8)).find()) {
                            String name = file.getFileName().toString();
                            holders.add(name.substring(0, name.length() - ".java".length()));
                        }
                    }
                }
            }
        }
        Set<String> listed = new TreeSet<>(INLINE_ROWS);
        for (RewardRows store : REWARD_ROW_STORES) {
            listed.add(store.asset());
        }
        assertEquals(listed, holders, "a class holding reward rows joins REWARD_ROW_STORES (its fold counts the "
                + "rows its gate left out) or INLINE_ROWS");
    }

    /** The text from the class's LoadedAssetsEvent registration to the next store's registration. */
    private static String handlerOf(String source, String asset) {
        String marker = "register(LoadedAssetsEvent.class, " + asset + ".class,";
        int start = source.indexOf(marker);
        assertTrue(start >= 0, "no LoadedAssetsEvent handler for " + asset);
        int end = source.indexOf("AssetStoreRegistrar.registerStore(", start);
        return end < 0 ? source.substring(start) : source.substring(start, end);
    }

    /** The braces-matched body of the method whose declaration starts with {@code signature}. */
    private static String methodBody(String source, String signature, Path file) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "no " + signature + " in " + file);
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return source.substring(open, i + 1);
            }
        }
        return source.substring(open);
    }
}
