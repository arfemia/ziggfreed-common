package com.ziggfreed.common.loot.stamp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.loot.FactorLookup;

/**
 * A running mod adding outcomes to a roll pool through a {@link RollPoolConfig.EntrySource}: where
 * its entries land, that every stamp read of the pool sees them, that the pool's rename and rarity
 * ride along, and that a source going away or breaking costs nothing but its own entries. The
 * owner-keyed rules are the ones a loot table's roll sources follow.
 *
 * <p>Fixture owner, pool and stat ids are this test's own.
 */
class RollPoolEntrySourceTest {

    private static final String OWNER_A = "fixture_owner_a";
    private static final String OWNER_Z = "fixture_owner_z";

    private final List<String> warnings = new ArrayList<>();

    @AfterEach
    void reset() {
        RollPoolConfig config = RollPoolConfig.getInstance();
        config.unregisterEntrySource(OWNER_A);
        config.unregisterEntrySource(OWNER_Z);
        config.warnInto(null);
        config.mergePackLayer(Map.of());
    }

    private static StatRollEntry always(String stat) {
        return StatRollEntry.of(stat, StatRollEntry.Points.of(1.0, 1.0, null), null, true);
    }

    private static void load(RollPoolAsset... pools) {
        Map<String, RollPoolAsset> layer = new LinkedHashMap<>();
        for (RollPoolAsset pool : pools) {
            layer.put(pool.getId(), pool);
        }
        RollPoolConfig.getInstance().mergePackLayer(layer);
    }

    private static RollPoolAsset decode(String json, String id) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(RollPoolAsset.class, id, null);
        return RollPoolAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), null, new AssetExtraInfo<>(data));
    }

    private static List<String> statsOf(StatRollEntry[] entries) {
        List<String> out = new ArrayList<>();
        if (entries != null) {
            for (StatRollEntry entry : entries) {
                out.add(entry.getStat());
            }
        }
        return out;
    }

    private static List<String> statsOf(List<StatRollEntry> entries) {
        return statsOf(entries.toArray(StatRollEntry[]::new));
    }

    /** A source answering {@code stat} for pool {@code poolId} only. */
    private static RollPoolConfig.EntrySource onlyFor(String poolId, String stat) {
        return id -> id.equals(poolId) ? List.of(always(stat)) : List.of();
    }

    @Test
    void sourceEntriesFollowThePoolsOwnInOwnerOrder() {
        load(RollPoolAsset.of("base", new StatRollEntry[] {always("Fixture_Own")}));
        // Registered in the reverse of owner order: entries append in the owners' order, never the
        // order mods happened to set up in.
        RollPoolConfig.getInstance().registerEntrySource(OWNER_Z, onlyFor("base", "Fixture_Zed"));
        RollPoolConfig.getInstance().registerEntrySource(OWNER_A, onlyFor("base", "Fixture_Ay"));

        RollPoolAsset resolved = RollPoolConfig.getInstance().resolve("BASE");
        assertNotNull(resolved);
        assertEquals(List.of("Fixture_Own", "Fixture_Ay", "Fixture_Zed"), statsOf(resolved.getEntries()));
    }

    @Test
    void everyStampReadSeesTheSourceEntriesBeforeASpecsInlineOnes() {
        load(RollPoolAsset.of("base", new StatRollEntry[] {always("Fixture_Own")}));
        RollPoolConfig.getInstance().registerEntrySource(OWNER_A, onlyFor("base", "Fixture_Sourced"));
        StampSpec spec = StampSpec.of("base", new StatRollEntry[] {always("Fixture_Inline")}, null, false, null);

        assertEquals(List.of("Fixture_Own", "Fixture_Sourced", "Fixture_Inline"),
                statsOf(StampCapEngine.candidates(spec)));
        List<String> rolled = new ArrayList<>();
        for (StatRoll roll : StampCapEngine.resolve(spec, StampInspection.empty(), FactorLookup.none(),
                () -> 0.0).entries()) {
            rolled.add(roll.statId());
        }
        assertEquals(List.of("Fixture_Own", "Fixture_Sourced", "Fixture_Inline"), rolled,
                "the stamp itself draws the sourced entry");
    }

    @Test
    void aSourceKeepsThePoolsRenameAndRarityAndLeavesTheAuthoredViewAlone() throws IOException {
        load(decode("{ \"StampName\": \"fixture.stamp.name\", \"Quality\": \"Fixture_Quality\","
                + " \"Entries\": [ { \"Stat\": \"Fixture_Own\", \"Points\": { \"Min\": 1, \"Max\": 1 } } ] }",
                "base"));
        RollPoolConfig.getInstance().registerEntrySource(OWNER_A, onlyFor("base", "Fixture_Sourced"));

        StampIdentity identity = StampIdentity.resolve(null,
                RollPoolConfig.getInstance().poolOf(StampSpec.of("Base", null, null, false, null)));
        assertEquals(new StampIdentity("fixture.stamp.name", "Fixture_Quality"), identity,
                "an extended pool still renames and re-tints what it stamps");
        assertEquals(List.of("Fixture_Own"), statsOf(RollPoolConfig.getInstance().resolveAuthored("base").getEntries()),
                "what a file wrote is still what its author is shown");
        assertEquals(List.of("Fixture_Own"), statsOf(RollPoolConfig.getInstance().all().get("base").getEntries()));
    }

    @Test
    void aSourceNeverConjuresAPoolNoFileShips() {
        RollPoolConfig.getInstance().registerEntrySource(OWNER_A, id -> List.of(always("Fixture_Sourced")));

        assertNull(RollPoolConfig.getInstance().resolve("absent_pool"));
        assertEquals(List.of(), StampCapEngine.candidates(StampSpec.of("absent_pool", null, null, false, null)));
    }

    @Test
    void anUnregisteredSourcesEntriesVanish() {
        load(RollPoolAsset.of("base", new StatRollEntry[] {always("Fixture_Own")}));
        RollPoolConfig.getInstance().registerEntrySource(OWNER_A, onlyFor("base", "Fixture_Sourced"));
        RollPoolConfig.getInstance().unregisterEntrySource(OWNER_A);

        assertEquals(List.of("Fixture_Own"), statsOf(RollPoolConfig.getInstance().resolve("base").getEntries()));
    }

    @Test
    void registeringAnOwnerAgainReplacesItsSource() {
        load(RollPoolAsset.of("base", new StatRollEntry[] {always("Fixture_Own")}));
        RollPoolConfig.getInstance().registerEntrySource(OWNER_A, onlyFor("base", "Fixture_Old"));
        RollPoolConfig.getInstance().registerEntrySource(OWNER_A.toUpperCase(Locale.ROOT),
                onlyFor("base", "Fixture_New"));

        assertEquals(List.of("Fixture_Own", "Fixture_New"),
                statsOf(RollPoolConfig.getInstance().resolve("base").getEntries()),
                "the owner id matches without regard to case, and the second source replaces the first");
    }

    @Test
    void aSourceThatThrowsCostsOnlyItsOwnEntriesAndWarnsOnce() {
        load(RollPoolAsset.of("base", new StatRollEntry[] {always("Fixture_Own")}));
        RollPoolConfig.getInstance().warnInto(warnings::add);
        RollPoolConfig.getInstance().registerEntrySource(OWNER_A, id -> {
            throw new IllegalStateException("fixture failure");
        });
        RollPoolConfig.getInstance().registerEntrySource(OWNER_Z, onlyFor("base", "Fixture_Healthy"));

        assertEquals(List.of("Fixture_Own", "Fixture_Healthy"),
                statsOf(RollPoolConfig.getInstance().resolve("base").getEntries()));
        assertEquals(List.of("Fixture_Own", "Fixture_Healthy"),
                statsOf(Arrays.asList(RollPoolConfig.getInstance().resolve("base").getEntries())));
        assertEquals(1, warnings.size(), "a broken source is reported once, not on every resolve: " + warnings);
    }
}
