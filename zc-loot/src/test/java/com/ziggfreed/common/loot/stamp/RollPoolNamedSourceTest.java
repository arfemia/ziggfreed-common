package com.ziggfreed.common.loot.stamp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.loot.FactorLookup;

/**
 * The seam a pool file names for its per-roll entries ({@code EntrySource}, a
 * {@link RollPoolConfig.NamedSource}): a stamp draws the named source's entries beside the pool's own,
 * handed the moment its lookup reads about, and a pool whose source nobody registers rolls its own
 * entries alone, so a server without the source's mod still stamps.
 *
 * <p>Fixture pool, source and stat ids are this test's own; the moment's payload stands in for the
 * item a real stamp carries.
 */
class RollPoolNamedSourceTest {

    private static final String SOURCE = "Fixture_Named_Source";

    @AfterEach
    void reset() {
        RollPoolConfig.getInstance().unregisterNamedSource(SOURCE);
        RollPoolConfig.getInstance().mergePackLayer(Map.of());
    }

    private static RollPoolAsset decode(String json, String id) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(RollPoolAsset.class, id, null);
        return RollPoolAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), null, new AssetExtraInfo<>(data));
    }

    private static void load(RollPoolAsset pool) {
        Map<String, RollPoolAsset> layer = new LinkedHashMap<>();
        layer.put(pool.getId(), pool);
        RollPoolConfig.getInstance().mergePackLayer(layer);
    }

    /** A pool authoring one always-landing entry and naming {@code source}. */
    private static RollPoolAsset poolNaming(String source) throws IOException {
        return decode("{ \"EntrySource\": \"" + source + "\", \"Entries\": [ { \"Stat\": \"Fixture_Own\","
                + " \"Points\": { \"Min\": 1, \"Max\": 1 }, \"Always\": true } ] }", "named");
    }

    /** A lookup answering no reading, about {@code moment}. */
    private static FactorLookup about(FactorContext moment) {
        return new FactorLookup() {
            @Nullable
            @Override
            public Double resolve(@Nonnull String factorId, @Nullable String param) {
                return null;
            }

            @Nonnull
            @Override
            public FactorContext context() {
                return moment;
            }
        };
    }

    private static List<String> rolledStats(FactorLookup lookup) {
        StampSpec spec = StampSpec.of("named", null, null, false, null);
        List<String> out = new ArrayList<>();
        for (StatRoll roll : StampCapEngine.resolve(spec, StampInspection.empty(), lookup, () -> 0.0).entries()) {
            out.add(roll.statId());
        }
        return out;
    }

    @Test
    void aNamedSourceAddsEntriesFromTheRollsMoment() throws IOException {
        load(poolNaming(SOURCE));
        RollPoolConfig.getInstance().registerNamedSource(SOURCE.toUpperCase(Locale.ROOT), (poolId, roll) -> {
            String kind = roll.payload(String.class);
            return List.of(StatRollEntry.of(kind == null ? "Fixture_No_Moment" : "Fixture_For_" + kind,
                    StatRollEntry.Points.of(2.0, 2.0, null), null, true));
        });

        assertEquals(List.of("Fixture_Own", "Fixture_For_Sword"),
                rolledStats(about(FactorContext.builder().payload("Sword").build())),
                "the source is found by the file's name without regard to case and reads the stamp's moment");
        assertEquals(List.of("Fixture_Own", "Fixture_No_Moment"), rolledStats(FactorLookup.none()),
                "a lookup about no moment hands the source an empty context, never null");
    }

    @Test
    void aPoolWhoseSourceNobodyRegistersRollsItsOwnEntriesAlone() throws IOException {
        load(poolNaming("Fixture_Unregistered"));
        RollPoolConfig.getInstance().registerNamedSource(SOURCE,
                (poolId, roll) -> List.of(StatRollEntry.of("Fixture_Wrong_Source", null, null, true)));

        assertEquals(List.of("Fixture_Own"), rolledStats(FactorLookup.none()),
                "another name's source is never asked, and a missing one costs nothing but its entries");
    }
}
