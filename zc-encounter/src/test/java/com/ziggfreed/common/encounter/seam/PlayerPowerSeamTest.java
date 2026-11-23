package com.ziggfreed.common.encounter.seam;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.encounter.power.LibraryPlayerPower;
import com.ziggfreed.common.encounter.power.PlayerPowerAsset;
import com.ziggfreed.common.encounter.power.PlayerPowerConfig;
import com.ziggfreed.common.factor.FactorFormula;

/**
 * The player power seam's contract: with nothing filling it the library's own reading answers (its inputs weighted,
 * summed and held on its scale), a fill REPLACES that reading and never adds to it, a fill that cannot say or throws
 * reads its floor, and the owner file retunes one input's weight leaf by leaf, keeping the factor it reads.
 */
class PlayerPowerSeamTest {

    private static final String SHIPPED = "/Server/ZiggfreedCommon/PlayerPower/Default.json";

    @BeforeEach
    void reset() {
        EncounterSeams.resetForTests();
        PlayerPowerConfig.getInstance().mergePackLayer(Map.of());
        PlayerPowerConfig.getInstance().mergeOwnerLayer(Map.of());
    }

    @AfterEach
    void tearDown() {
        reset();
    }

    @Test
    void withNothingFillingItTheLibrarysOwnReadingAnswers() {
        PlayerPowerConfig.getInstance().mergePackLayer(Map.of(PlayerPowerAsset.SHARED_ID,
                PlayerPowerAsset.of(2.0, 1.0, 80.0, inputs("yourmod:armor", 0.5, "yourmod:weapon", 0.25))));

        assertFalse(EncounterSeams.isPlayerPowerFilled());
        assertSame(LibraryPlayerPower.INSTANCE, EncounterSeams.playerPowerSource(), "the library answers itself");
        assertTrue(EncounterSeams.latchForTests("playerpower").get(), "and said so, once");
        assertEquals(LibraryPlayerPower.OWNER, EncounterSeams.playerPowerOwner());
        assertEquals(1.0, EncounterSeams.playerPowerMin(), 1e-9, "the scale is the record's");
        assertEquals(80.0, EncounterSeams.playerPowerMax(), 1e-9);

        PlayerPowerAsset record = PlayerPowerConfig.getInstance().settings();
        assertEquals(2.0 + 0.5 * 40 + 0.25 * 20, record.read(lookup(40.0, 20.0)), 1e-9,
                "base plus each input's weighted reading");
        assertEquals(2.0 + 0.5 * 40, record.read(lookup(40.0, null)), 1e-9, "an input nobody can read adds 0");
        assertEquals(80.0, record.read(lookup(400.0, 400.0)), 1e-9, "held at the scale's ceiling");
        assertEquals(1.0, PlayerPowerAsset.of(null, 1.0, 80.0, Map.of()).read(lookup(null, null)), 1e-9,
                "and at its floor: a player with nothing reads the floor");
    }

    @Test
    void aFillReplacesTheLibrarysReadingAndNeverAddsToIt() {
        PlayerPowerConfig.getInstance().mergePackLayer(Map.of(PlayerPowerAsset.SHARED_ID,
                PlayerPowerAsset.of(50.0, 1.0, 200.0, Map.of())));
        EncounterSeams.fillPlayerPowerSource(fill(42.0, 1.0, 120.0));

        assertTrue(EncounterSeams.isPlayerPowerFilled());
        assertEquals("yourmod", EncounterSeams.playerPowerOwner());
        assertEquals(42.0, EncounterSeams.playerPower(null, null), 1e-9,
                "the fill's answer stands alone: the library's 50 is not added to it");
        assertEquals(120.0, EncounterSeams.playerPowerMax(), 1e-9, "and its scale is the fill's own");
        assertFalse(EncounterSeams.latchForTests("playerpower").get(),
                "nothing was said about the library's reading, since it never answered");

        EncounterSeams.fillPlayerPowerSource(fill(500.0, 1.0, 120.0));
        assertEquals(120.0, EncounterSeams.playerPower(null, null), 1e-9, "held on the fill's scale");

        EncounterSeams.fillPlayerPowerSource(null);
        assertSame(LibraryPlayerPower.INSTANCE, EncounterSeams.playerPowerSource(), "an emptied seam hands back");
    }

    @Test
    void aFillThatCannotSayOrThrowsReadsItsFloor() {
        EncounterSeams.fillPlayerPowerSource(fill(null, 3.0, 90.0));
        assertEquals(3.0, EncounterSeams.playerPower(null, null), 1e-9);

        EncounterSeams.fillPlayerPowerSource(new PlayerPowerSource() {
            @Nullable
            @Override
            public Double powerOf(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> playerRef) {
                throw new IllegalStateException("broken");
            }

            @Override
            public double minPower() {
                return 4.0;
            }
        });
        assertEquals(4.0, EncounterSeams.playerPower(null, null), 1e-9);
        assertEquals(4.0, EncounterSeams.heldOnScale(Double.NaN, 4.0, 9.0), 1e-9, "not a number reads the floor");
    }

    @Test
    void anOwnerWeightKeepsTheFactorItsInputReads() throws IOException {
        PlayerPowerAsset shipped = decode(shippedJson(), null);
        assertFalse(shipped.inputs().isEmpty(), "the shipped record names its inputs");
        String name = shipped.inputs().keySet().iterator().next();
        FactorFormula.Term before = shipped.inputs().get(name);

        PlayerPowerAsset owner = decode("{\"Inputs\": {\"" + name + "\": {\"Weight\": 0.125}}}", shipped);
        FactorFormula.Term after = owner.inputs().get(name);
        assertEquals(0.125, after.weightOrDefault(), 1e-9, "the owner's weight stands");
        assertEquals(before.getFactor(), after.getFactor(), "the factor it reads is kept");
        assertEquals(before.getParam(), after.getParam());
        assertEquals(shipped.inputs().keySet(), owner.inputs().keySet(), "every other input is kept");
        assertEquals(shipped.maxPower(), owner.maxPower(), 1e-9, "and every other leaf");
    }

    // ==================== helpers ====================

    @Nonnull
    private static Map<String, FactorFormula.Term> inputs(@Nonnull String armor, double armorWeight,
            @Nonnull String weapon, double weaponWeight) {
        Map<String, FactorFormula.Term> inputs = new LinkedHashMap<>();
        inputs.put("Armor", FactorFormula.Term.of(armor, null, armorWeight));
        inputs.put("Weapon", FactorFormula.Term.of(weapon, null, weaponWeight));
        return inputs;
    }

    @Nonnull
    private static BiFunction<String, String, Double> lookup(@Nullable Double armor,
            @Nullable Double weapon) {
        Map<String, Double> readings = new HashMap<>();
        readings.put("yourmod:armor", armor);
        readings.put("yourmod:weapon", weapon);
        return (factor, param) -> readings.get(factor);
    }

    @Nonnull
    private static PlayerPowerSource fill(@Nullable Double answer, double min, double max) {
        return new PlayerPowerSource() {
            @Nullable
            @Override
            public Double powerOf(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> playerRef) {
                return answer;
            }

            @Override
            public double minPower() {
                return min;
            }

            @Override
            public double maxPower() {
                return max;
            }

            @Nonnull
            @Override
            public String owner() {
                return "yourmod";
            }
        };
    }

    @Nonnull
    private static String shippedJson() throws IOException {
        try (InputStream in = PlayerPowerSeamTest.class.getResourceAsStream(SHIPPED)) {
            if (in == null) {
                throw new IOException("missing " + SHIPPED);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Nonnull
    private static PlayerPowerAsset decode(@Nonnull String json, @Nullable PlayerPowerAsset parent) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(PlayerPowerAsset.class, PlayerPowerAsset.SHARED_ID,
                parent == null ? null : PlayerPowerAsset.SHARED_ID);
        return PlayerPowerAsset.CODEC.decodeAndInheritJsonAsset(RawJsonReader.fromJsonString(json), parent,
                new AssetExtraInfo<>(data));
    }
}
