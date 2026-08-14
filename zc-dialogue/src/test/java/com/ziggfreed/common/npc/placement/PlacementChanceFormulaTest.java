package com.ziggfreed.common.npc.placement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.factor.FactorFormula;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * Where a placement's spawn chance comes from when {@code Limits} carries both sources.
 *
 * <p>{@code SpawnChance} and {@code ChanceFormula} are two independent knobs rather than a mode, so
 * both can be present and the engine has to pick one without guessing: the formula wins, because it
 * is the one that can say something the number cannot. Everything downstream of the VALUE is
 * untouched - the keep-or-skip roll is still the deterministic per-position one.
 */
class PlacementChanceFormulaTest {

    @BeforeEach
    @AfterEach
    void reset() {
        PlacementFactorRegistry.clearForTests();
    }

    private static NpcPlacementAsset decodeRoot(String json, String id) throws IOException {
        AssetExtraInfo.Data data = new AssetExtraInfo.Data(NpcPlacementAsset.class, id, null);
        return NpcPlacementAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString(json), null, new AssetExtraInfo<>(data));
    }

    private static NpcPlacementAsset withLimits(String id, NpcPlacementAsset.Limits limits) {
        return NpcPlacementAsset.of(id, true,
                NpcPlacementAsset.Identity.of("some_role"), null,
                NpcPlacementAsset.Anchor.of(NpcPlacementAsset.Anchor.WorldSpawn.of(null, null),
                        null, null, null, null),
                null, limits, null, null);
    }

    // ==================== which source is used ====================

    @Test
    void theFormulaWinsOverTheScalarWhenBothAreAuthored() {
        PlacementFactorRegistry.register("test:density", ctx -> 3.0);

        double chance = PlacementAnchors.resolveChance(withLimits("gated", NpcPlacementAsset.Limits.of(
                0.1,
                FactorFormula.of(0.25, new FactorFormula.Term[] {
                        FactorFormula.Term.of("test:density", null, 0.25) }, null),
                null, null)), null, null);

        assertEquals(1.0, chance, "0.25 base plus 3 x 0.25 - the scalar 0.1 must not be what is rolled against");
    }

    @Test
    void theScalarIsUsedWhenNoFormulaIsAuthored() {
        assertEquals(0.4, PlacementAnchors.resolveChance(
                withLimits("plain", NpcPlacementAsset.Limits.of(0.4, null, null, null)), null, null));
    }

    @Test
    void anEmptyFormulaFallsBackToTheScalarRatherThanReadingAsZero() {
        double chance = PlacementAnchors.resolveChance(withLimits("empty", NpcPlacementAsset.Limits.of(
                0.4, FactorFormula.of(null, null, null), null, null)), null, null);

        assertEquals(0.4, chance,
                "an empty group is an authoring accident; treating it as a constant 0 would silently empty "
                        + "the world of that NPC");
    }

    @Test
    void unauthoredLimitsMeanAlways() {
        assertEquals(1.0, PlacementAnchors.resolveChance(withLimits("bare", null), null, null));
    }

    // ==================== what the formula can and cannot read ====================

    @Test
    void aTermNobodyRegisteredContributesZeroInsteadOfVoidingTheChance() {
        double chance = PlacementAnchors.resolveChance(withLimits("optional", NpcPlacementAsset.Limits.of(
                null,
                FactorFormula.of(0.5, new FactorFormula.Term[] {
                        FactorFormula.Term.of("absentmod:bonus", null, 0.5) }, null),
                null, null)), null, null);

        assertEquals(0.5, chance,
                "the value side degrades: one uninstalled mod's optional term must cost only that term");
    }

    @Test
    void theContextCarriesThePlacementIdAndNoSubject() {
        PlacementFactorRegistry.register("test:echo", ctx -> {
            assertEquals("hub_guide", ctx.payload(String.class),
                    "a provider that needs to know WHICH placement reads it off the payload");
            assertTrue(!ctx.hasLiveSubject(),
                    "nothing stands at the anchor yet, so a subject-dependent factor cannot answer here");
            return 1.0;
        });

        assertEquals(1.0, PlacementAnchors.resolveChance(withLimits("hub_guide", NpcPlacementAsset.Limits.of(
                null,
                FactorFormula.of(null, new FactorFormula.Term[] {
                        FactorFormula.Term.of("test:echo", null, null) }, null),
                null, null)), null, null));
    }

    @Test
    void aThrowingProviderLeavesTheAuthoredNumberStanding() {
        PlacementFactorRegistry.register("test:broken", ctx -> {
            throw new IllegalStateException("provider is broken");
        });

        double chance = PlacementAnchors.resolveChance(withLimits("resilient", NpcPlacementAsset.Limits.of(
                null,
                FactorFormula.of(0.3, new FactorFormula.Term[] {
                        FactorFormula.Term.of("test:broken", null, 1.0) }, null),
                null, null)), null, null);

        assertEquals(0.3, chance);
    }

    // ==================== authoring ====================

    @Test
    void theFormulaDecodesFromItsAuthoredShape() throws Exception {
        NpcPlacementAsset asset = decodeRoot("""
                { "Limits": { "ChanceFormula": { "Base": 0.2,
                                                 "Factors": [ { "Factor": "yourmod:danger", "Weight": 0.1 } ],
                                                 "Clamp": { "Min": 0.0, "Max": 1.0 } } } }
                """, "authored");

        assertTrue(asset.getLimits().hasChanceFormula());
        assertEquals(0.2, asset.getLimits().getChanceFormula().getBase());
        assertEquals("yourmod:danger", asset.getLimits().getChanceFormula().getFactors()[0].getFactor());
        assertEquals(1.0, asset.getLimits().getChanceFormula().getClamp().getMax());
    }

    @Test
    void bothSourcesAuthoredIsReportedSoTheFileSaysWhatItDoes() {
        List<Finding> issues = NpcPlacementValidator.audit(withLimits("both", NpcPlacementAsset.Limits.of(
                0.1,
                FactorFormula.of(0.5, new FactorFormula.Term[] {
                        FactorFormula.Term.of("yourmod:danger", null, null) }, null),
                null, null)));

        assertEquals(Severity.INFO, issues.stream()
                        .filter(i -> "CHANCE_FORMULA_AND_SCALAR".equals(i.code()))
                        .findFirst().orElseThrow().severity(),
                "both authored still works - the formula is read - so it is a remark about clarity, "
                        + "never something to fix");
        assertTrue(issues.stream().anyMatch(i -> "UNREGISTERED_FACTOR".equals(i.code())),
                "a term nobody can answer is a warning here, never an error - it is the value side working");
    }

    @Test
    void aZeroScalarBesideAWorkingFormulaIsNotReportedAsNeverAppearing() {
        List<Finding> issues = NpcPlacementValidator.audit(withLimits("zeroed", NpcPlacementAsset.Limits.of(
                0.0,
                FactorFormula.of(0.5, new FactorFormula.Term[] {
                        FactorFormula.Term.of("yourmod:danger", null, null) }, null),
                null, null)));

        assertTrue(issues.stream().noneMatch(i -> "SPAWN_CHANCE_ZERO".equals(i.code())),
                "the scalar is not what is rolled against, so it cannot be what keeps the NPC away");
    }
}
