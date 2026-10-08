package com.ziggfreed.common.commerce.fold;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.assetstore.JsonAsset;
import com.hypixel.hytale.assetstore.codec.AssetBuilderCodec;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.asset.AssetMergeAdapter;
import com.ziggfreed.common.board.asset.BoardAsset;
import com.ziggfreed.common.board.asset.BoardConfig;
import com.ziggfreed.common.currency.asset.CurrencyAsset;
import com.ziggfreed.common.currency.asset.CurrencyConfig;
import com.ziggfreed.common.factor.ModGates;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.shop.asset.ShopConfig;
import com.ziggfreed.common.shop.asset.StorefrontAsset;

/**
 * The commerce owner files (wallets, storefronts, boards) follow the mod gate through the shared reader,
 * driven exactly as each store's load handler drives it: the gated pack fold, then the owner read.
 *
 * <ul>
 *   <li>An owner gate on an id the pack ships WITHOUT one takes that whole id out where the mod is
 *       missing, the pack's own version included, and the id counts as refused (the maintainer's ruling:
 *       the owner's restriction is honored, never silently undone by the pack's ungated file).</li>
 *   <li>An owner retune of a pack file the gate refused goes with that file.</li>
 *   <li>An owner entry of the owner's own, gated on the missing mod, is dropped.</li>
 *   <li>Each store logs one counted owner line under its contract label and names no entry.</li>
 * </ul>
 *
 * With the mod, the owner's gate merges over the pack's file, keeping every leaf the pack wrote, and keeps
 * the gate it adds.
 */
class CommerceOwnerModGateTest {

    private static final String MMO = "Ziggfreed:MMOSkillTree";
    private static final String GATE = "\"Requires\": { \"Factors\": [ { \"Factor\": \"hytale:mod_installed\","
            + " \"Param\": \"" + MMO + "\", \"Min\": 1 } ] }";

    /**
     * One gated commerce store: its contract label, its asset type, its keyed config, its owner file and
     * reader, one leaf the pack writes (as JSON and as the folded asset reads it back) and its gate.
     */
    private record Store<T extends JsonAsset<String>>(String label, Class<T> type, AssetBuilderCodec<String, T> codec,
            AbstractKeyedAssetConfig<T> config, String ownerFile, Runnable reloadOwner, String packLeaf,
            Object packLeafValue, Function<T, Object> leaf, Function<T, GateSpec> requires) {

        /** The store's load handler: the reporting gate fold under its label, then its owner file. */
        void load(PackMap<T> packs) {
            config.mergePackLayer(AssetMergeAdapter.gate(label, packs, a -> GateSpec.missingMod(requires.apply(a))));
            reloadOwner.run();
        }
    }

    /** The engine map with its pack-loading door opened, as the load event hands it over. */
    private static final class PackMap<T extends JsonAsset<String>> extends DefaultAssetMap<String, T> {

        private final Store<T> store;

        PackMap(Store<T> store) {
            this.store = store;
        }

        PackMap<T> load(String id, String json) throws IOException {
            T asset = store.codec().decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), null,
                    new AssetExtraInfo<>(new AssetExtraInfo.Data(store.type(), id, null)));
            putAll("Ziggfreed:SeasonsOfOrbis", store.codec(), Map.of(id, asset),
                    Map.of(id, Path.of("SeasonsOfOrbis", id + ".json")), Map.of(id, Set.of()));
            return this;
        }
    }

    private static final List<Store<?>> STORES = List.of(
            new Store<>("Currencies", CurrencyAsset.class, CurrencyAsset.CODEC, CurrencyConfig.getInstance(),
                    CommerceOwnerLayers.CURRENCIES_FILE, CommerceOwnerLayers::reloadCurrencies, "\"Cap\": 500",
                    500L, CurrencyAsset::cap, CurrencyAsset::getRequires),
            new Store<>("Shops", StorefrontAsset.class, StorefrontAsset.CODEC, ShopConfig.getInstance(),
                    CommerceOwnerLayers.SHOPS_FILE, CommerceOwnerLayers::reloadShops, "\"Order\": 7",
                    7, StorefrontAsset::order, StorefrontAsset::getRequires),
            new Store<>("Boards", BoardAsset.class, BoardAsset.CODEC, BoardConfig.getInstance(),
                    CommerceOwnerLayers.BOARDS_FILE, CommerceOwnerLayers::reloadBoards, "\"Order\": 7",
                    7, BoardAsset::order, BoardAsset::getRequires));

    @TempDir
    Path dir;

    private final List<String> lines = new ArrayList<>();

    @BeforeEach
    void ownerFilesHere() throws IOException {
        CommerceOwnerLayers.setDirectory(dir);
        ModGates.reportIntoForTests(lines::add);
        for (Store<?> store : STORES) {
            Files.writeString(dir.resolve(store.ownerFile()), "{"
                    + " \"Harvest_Coin\": { " + GATE + " },"
                    + " \"Mmo_Coin\": { },"
                    + " \"Owner_Mmo_Coin\": { " + GATE + " },"
                    + " \"Owner_Coin\": { } }", StandardCharsets.UTF_8);
        }
    }

    @AfterEach
    void clearEverything() {
        CommerceOwnerLayers.setDirectory(CommerceOwnerLayers.DEFAULT_DIRECTORY);
        for (Store<?> store : STORES) {
            store.config().mergePackLayer(Map.of());
            store.config().mergeOwnerLayer(Map.of());
        }
        ModGates.useProbeForTests(null);
        ModGates.reportIntoForTests(null);
    }

    private static void mmoInstalled(boolean installed) {
        ModGates.useProbeForTests(param -> param != null && MMO.equals(param.trim()) ? (installed ? 1.0 : 0.0) : 1.0);
    }

    /** An ungated pack file carrying one leaf, and a pack file gated on the MMO. */
    private static <T extends JsonAsset<String>> PackMap<T> packsOf(Store<T> store) throws IOException {
        return new PackMap<>(store)
                .load("Harvest_Coin", "{ " + store.packLeaf() + " }")
                .load("Mmo_Coin", "{ " + GATE + ", " + store.packLeaf() + " }");
    }

    private static String line(String store, int count, String what) {
        return "[zc] mod gate: " + store + " dropped " + count + " " + what + " gated on a missing mod (" + MMO + ")";
    }

    @Test
    void withoutTheMmoEachOwnerReaderTakesTheOwnerGatedIdOutAndDropsWhatFollowsTheGate() throws IOException {
        mmoInstalled(false);
        List<String> expected = new ArrayList<>();

        for (Store<?> store : STORES) {
            withoutTheMmo(store);
            expected.add(line(store.label(), 1, "pack file(s)"));
            expected.add(line(store.label(), 3, "owner override(s)"));
        }

        assertEquals(expected, lines, "one counted pack line and one counted owner line per store, under its label");
        for (String line : lines) {
            assertFalse(line.toLowerCase(Locale.ROOT).contains("_coin"), "a drop line names no entry: " + line);
        }
    }

    private static <T extends JsonAsset<String>> void withoutTheMmo(Store<T> store) throws IOException {
        store.load(packsOf(store));
        AbstractKeyedAssetConfig<T> config = store.config();
        String label = store.label() + ": ";

        assertNull(config.resolve("harvest_coin"), label + "the owner's gate takes the pack's ungated file out");
        assertFalse(config.ids().contains("harvest_coin"), label + "and out of every listing");
        assertEquals(MMO, config.modGateRefused().get("harvest_coin"), label + "it counts as refused");
        assertNull(config.resolve("mmo_coin"), label + "a retune of a refused pack file goes with it");
        assertNull(config.resolve("owner_mmo_coin"), label + "an owner entry gated on the missing mod is dropped");
        assertNotNull(config.resolve("owner_coin"), label + "an ungated owner entry stays");
    }

    @Test
    void withTheMmoEachOwnerGateMergesOverItsPackFileAndKeepsTheGate() throws IOException {
        mmoInstalled(true);

        for (Store<?> store : STORES) {
            withTheMmo(store);
        }

        assertTrue(lines.isEmpty(), "nothing dropped says nothing: " + lines);
    }

    private static <T extends JsonAsset<String>> void withTheMmo(Store<T> store) throws IOException {
        store.load(packsOf(store));
        AbstractKeyedAssetConfig<T> config = store.config();
        String label = store.label() + ": ";

        T merged = config.resolve("harvest_coin");
        assertNotNull(merged, label + "the owner's entry is in force");
        assertEquals(store.packLeafValue(), store.leaf().apply(merged), label + "it keeps the leaf the pack wrote");
        assertNotNull(store.requires().apply(merged), label + "and the gate the owner added");
        assertNotNull(config.resolve("mmo_coin"), label + "the gated pack file loads, and its retune with it");
        assertNotNull(config.resolve("owner_mmo_coin"));
        assertTrue(config.modGateRefused().isEmpty(), label + "nothing is refused");
    }
}
