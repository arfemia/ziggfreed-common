package com.ziggfreed.common.instance.dungeon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.schema.SchemaContext;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.encounter.wave.WaveRules;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * A dungeon's endless wave run ({@code Run.Endless}): its group reads, writes back and merges its bands by name under
 * {@code Parent}, and the dungeon audit names the three ways it cannot run (no band to raise, a boss every wave, a
 * ramp with no cap) as ERRORs, which refuse entry, while a sound one is clean and no longer reported as a dungeon
 * nothing clears. Untagged: nothing here builds an engine item.
 */
class DungeonEndlessTest {

    /** A view distance any default spacing clears, so no spacing finding joins the ones under test. */
    private static final int MIN_SPACING = 64;

    private static final String GRAVEYARD = """
            {
              "Area": { "Prefab": "MyPack/Graveyard.prefab.json" },
              "Run": { "Endless": {
                "BossEvery": 5,
                "Bands": { "Bones": { "Slot": 1 }, "Rotten": { "Slot": 2 } },
                "Sizes": { "Max": 3 },
                "Ramp": { "HealthPerWave": 0.08, "DamagePerWave": 0.04, "Max": 4.0 },
                "Chest": { "PerWave": "MyPack_Wave", "PerBossWave": "MyPack_Boss_Wave" } } }
            }
            """;

    @Test
    void theGroupReadsWritesBackAndMergesItsBandsUnderParent() throws IOException {
        DungeonAsset graveyard = decode("MyPack_Graveyard", GRAVEYARD, null);
        assertEndless(graveyard);
        assertEndless(decode("MyPack_Graveyard", DungeonAsset.CODEC.encode(graveyard, new ExtraInfo()).toJson(),
                null));

        DungeonAsset child = decode("MyPack_Graveyard_Void", """
                { "Run": { "Endless": { "Bands": { "Void": { "Slot": 3 }, "Rotten": { "Slot": 0 } } } } }
                """, graveyard);
        DungeonEndless endless = child.endless();
        assertNotNull(endless, "an unrestated group is inherited");
        assertEquals(List.of(1, 3), endless.bandSlots(), "bands merge by name, and a Slot of 0 takes one out");
        assertEquals(Integer.valueOf(5), endless.bossEvery(), "an unrestated leaf is inherited");
        assertNotNull(DungeonAsset.Run.CODEC.toSchema(new SchemaContext()).getProperties().get("Endless"),
                "the schema the Asset Editor loads declares the group");

        DungeonAsset plain = decode("MyPack_Crypt", "{}", null);
        assertFalse(plain.isEndless(), "a dungeon with no Run.Endless runs as before");
        assertNull(plain.endless());
    }

    @Test
    void aSoundEndlessRunIsCleanAndNotReportedAsUnclearable() throws IOException {
        Set<String> codes = codes(decode("MyPack_Graveyard", GRAVEYARD, null));
        assertTrue(codes.isEmpty(), "found " + codes);
    }

    @Test
    void anEndlessRunWithNoBandIsAnError() throws IOException {
        DungeonAsset empty = decode("MyPack_Graveyard", """
                { "Area": { "Prefab": "MyPack/Graveyard.prefab.json" },
                  "Run": { "Endless": { "Bands": { "Bones": { "Slot": 9 } } } } }
                """, null);
        assertEquals(Severity.ERROR, severityOf(empty, DungeonValidator.ENDLESS_NO_WAVE_POOL));
        assertNull(empty.endless().rules(), "no rules: nothing could rise");
    }

    @Test
    void aBossEveryUnderTwoIsAnError() throws IOException {
        DungeonAsset every = decode("MyPack_Graveyard", """
                { "Area": { "Prefab": "MyPack/Graveyard.prefab.json" },
                  "Run": { "Endless": { "BossEvery": 1, "Bands": { "Bones": { "Slot": 1 } } } } }
                """, null);
        assertEquals(Severity.ERROR, severityOf(every, DungeonValidator.ENDLESS_BOSS_EVERY_TOO_LOW));
    }

    @Test
    void aRampWithAStepAndNoCapIsAnError() throws IOException {
        DungeonAsset uncapped = decode("MyPack_Graveyard", """
                { "Area": { "Prefab": "MyPack/Graveyard.prefab.json" },
                  "Run": { "Endless": { "Bands": { "Bones": { "Slot": 1 } },
                                        "Ramp": { "HealthPerWave": 0.1 } } } }
                """, null);
        assertEquals(Severity.ERROR, severityOf(uncapped, DungeonValidator.ENDLESS_RAMP_UNCAPPED));

        DungeonAsset flat = decode("MyPack_Graveyard", """
                { "Area": { "Prefab": "MyPack/Graveyard.prefab.json" },
                  "Run": { "Endless": { "Bands": { "Bones": { "Slot": 1 } }, "Ramp": { "HealthPerWave": 0 } } } }
                """, null);
        assertFalse(codes(flat).contains(DungeonValidator.ENDLESS_RAMP_UNCAPPED), "a ramp with no step needs no cap");
    }

    @Test
    void anEndlessRunsUnknownTablesAreWarnings() throws IOException {
        List<Finding> findings = DungeonValidator.audit(List.of(decode("MyPack_Graveyard", GRAVEYARD, null)),
                template -> true, template -> null, prefab -> true, script -> true, table -> false, MIN_SPACING,
                key -> true);
        assertTrue(findings.stream().anyMatch(f -> f.code().equals(DungeonValidator.UNKNOWN_DUNGEON_LOOTABLE)
                && f.severity() == Severity.WARNING), "a table no file defines is named, as a warning");
    }

    private static void assertEndless(@Nonnull DungeonAsset dungeon) {
        assertTrue(dungeon.isEndless());
        DungeonEndless endless = dungeon.endless();
        assertEquals(Integer.valueOf(5), endless.bossEvery());
        assertEquals(4, endless.bandWaves(), "unauthored, the waves between two boss waves");
        assertEquals(List.of(1, 2), endless.bandSlots());
        assertEquals(3, endless.sizeMax());
        assertEquals(4.0, endless.ramp().cap(), 1e-9);
        assertEquals(0.08, endless.ramp().healthPerReturn(), 1e-9, "a per-return step falls back to the per-wave one");
        assertEquals("MyPack_Wave", endless.perWaveLootable());
        assertEquals("MyPack_Boss_Wave", endless.perBossWaveLootable());
        assertEquals(DungeonEndless.DEFAULT_CHEST, endless.chestFile());
        WaveRules rules = endless.rules();
        assertNotNull(rules);
        assertTrue(rules.at(5).boss());
    }

    @Nonnull
    private static Set<String> codes(@Nonnull DungeonAsset dungeon) {
        return audit(dungeon).stream().map(Finding::code).collect(Collectors.toSet());
    }

    @Nullable
    private static Severity severityOf(@Nonnull DungeonAsset dungeon, @Nonnull String code) {
        return audit(dungeon).stream().filter(f -> f.code().equals(code)).map(Finding::severity).findFirst()
                .orElse(null);
    }

    @Nonnull
    private static List<Finding> audit(@Nonnull DungeonAsset dungeon) {
        return DungeonValidator.audit(List.of(dungeon), template -> true, template -> null, prefab -> true,
                script -> true, table -> true, MIN_SPACING, key -> true);
    }

    @Nonnull
    private static DungeonAsset decode(@Nonnull String id, @Nonnull String json, @Nullable DungeonAsset parent)
            throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(DungeonAsset.class, id,
                parent == null ? null : parent.getId());
        return DungeonAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), parent,
                new AssetExtraInfo<>(data));
    }
}
