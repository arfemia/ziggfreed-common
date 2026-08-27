package com.ziggfreed.common.factor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.hypixel.hytale.assetstore.AssetExtraInfo;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.factor.FactorFormula.Clamp;
import com.ziggfreed.common.factor.FactorFormula.Term;

/**
 * Asset-defined factors end to end: a file under {@code Server/ZiggfreedCommon/Factors/} adds an id
 * to the vocabulary that nothing downstream can tell apart from a mod-registered one.
 *
 * <p>The two failure modes that MUST stay fail-closed are pinned here as hard as the gate rules
 * are, because both are silent otherwise: a definition that reaches itself (which would otherwise
 * recurse until the world thread dies) and one that has been reloaded away.
 */
class DerivedFactorTest {

    private static FactorContext ctx() {
        return FactorContext.builder().build();
    }

    /** Fold {@code definitions} into the process-wide config the registries consult. */
    private static void define(Map<String, FactorFormula> definitions) {
        Map<String, DerivedFactorAsset> files = new HashMap<>();
        for (Map.Entry<String, FactorFormula> e : definitions.entrySet()) {
            files.put(e.getKey(), DerivedFactorAsset.of(e.getKey(), e.getValue(), null, null, null, null));
        }
        DerivedFactorConfig.getInstance().mergePackLayer(files);
    }

    private static FactorFormula formula(String factor, Double weight) {
        return FactorFormula.of(null, new Term[]{Term.of(factor, null, weight)}, null);
    }

    @AfterEach
    void clearTheProcessWideConfig() {
        DerivedFactorConfig.getInstance().mergePackLayer(Map.of());
    }

    // ==================== Resolution ====================

    @Test
    void aDerivedIdResolvesLikeARegisteredOne() {
        FactorRegistry registry = new FactorRegistry();
        registry.register("yourmod:quality", c -> 4.0);
        define(Map.of("yourmod:gear_score", FactorFormula.of(1.0,
                new Term[]{Term.of("yourmod:quality", null, 0.5)}, null)));

        assertEquals(3.0, registry.resolve("yourmod:gear_score", ctx()));
        assertTrue(FactorCondition.of("yourmod:gear_score", null, 3.0, null).accepts(
                registry.resolve("yourmod:gear_score", ctx())),
                "a gate cannot tell a derived factor from a registered one, which is the point");
    }

    @Test
    void theDefinitionIsAdoptedIntoTheLedgerAttributedToTheAsset() {
        FactorRegistry registry = new FactorRegistry();
        registry.register("yourmod:quality", c -> 2.0);
        define(Map.of("yourmod:derived", formula("yourmod:quality", 1.0)));

        assertFalse(registry.isRegistered("yourmod:derived"), "nothing is claimed before it is asked for");
        assertEquals(2.0, registry.resolve("yourmod:derived", ctx()));

        assertTrue(registry.isRegistered("yourmod:derived"));
        assertEquals("asset:yourmod:derived", registry.info().get("yourmod:derived").owner(),
                "an admin listing must point at the file a server owner would go and edit");
        assertTrue(registry.ids().contains("yourmod:derived"));
    }

    @Test
    void aRegisteredProviderAlwaysWinsOverADefinitionOfTheSameId() {
        FactorRegistry registry = new FactorRegistry();
        registry.register("yourmod:same", c -> 99.0);
        define(Map.of("yourmod:same", FactorFormula.of(1.0, null, null)));

        assertEquals(99.0, registry.resolve("yourmod:same", ctx()),
                "the derived source is consulted only on a MISS, so it can never shadow Java");
    }

    @Test
    void theDefiningContextIsTheCallersOwnSoPerConsumerResolutionStaysCorrect() {
        FactorRegistry registry = new FactorRegistry();
        registry.register("yourmod:payload", c -> "hub".equals(c.payload()) ? 10.0 : 1.0);
        define(Map.of("yourmod:derived", formula("yourmod:payload", 1.0)));

        assertEquals(10.0, registry.resolve("yourmod:derived",
                FactorContext.builder().payload("hub").build()));
        assertEquals(1.0, registry.resolve("yourmod:derived",
                FactorContext.builder().payload("elsewhere").build()));
    }

    @Test
    void aDerivedFactorMayBeBuiltOnAnotherDerivedFactor() {
        FactorRegistry registry = new FactorRegistry();
        registry.register("yourmod:base", c -> 2.0);
        define(Map.of(
                "yourmod:mid", formula("yourmod:base", 2.0),
                "yourmod:top", formula("yourmod:mid", 3.0)));

        assertEquals(12.0, registry.resolve("yourmod:top", ctx()), "2 * 2 * 3");
    }

    @Test
    void aDefinitionThatIsReloadedAwayGoesBackToFailingClosed() {
        FactorRegistry registry = new FactorRegistry();
        registry.register("yourmod:quality", c -> 2.0);
        define(Map.of("yourmod:derived", formula("yourmod:quality", 1.0)));
        assertEquals(2.0, registry.resolve("yourmod:derived", ctx()));

        define(Map.of());

        assertNull(registry.resolve("yourmod:derived", ctx()),
                "the adopted provider re-reads the config, so a dropped file is not cached open");
    }

    @Test
    void anEmptyDefinitionIsNoDefinitionAtAll() {
        FactorRegistry registry = new FactorRegistry();
        define(Map.of("yourmod:empty", new FactorFormula()));

        assertNull(registry.resolve("yourmod:empty", ctx()),
                "an empty file is an authoring accident; letting it answer 0 would open every "
                        + "bounds-less gate written against the id");
        assertFalse(FactorCondition.of("yourmod:empty", null, null, null)
                .accepts(registry.resolve("yourmod:empty", ctx())));
    }

    @Test
    void aRegistryWithTheHookClearedSeesNoDefinitionsAtAll() {
        FactorRegistry registry = new FactorRegistry();
        registry.derivedSource(null);
        define(Map.of("yourmod:derived", FactorFormula.of(1.0, null, null)));

        assertNull(registry.resolve("yourmod:derived", ctx()));
        assertNotNull(new FactorRegistry().derivedSource(),
                "while a fresh registry starts wired to the shared config");
    }

    @Test
    void aDerivedFactorAnswersEvenWhenEveryInputIsMissing() {
        FactorRegistry registry = new FactorRegistry();
        define(Map.of("yourmod:derived", FactorFormula.of(1.0,
                new Term[]{Term.of("absentmod:bonus", null, 5.0)}, null)));

        assertEquals(1.0, registry.resolve("yourmod:derived", ctx()),
                "the value side degrades to Base, so a bounds-less gate on a derived id is a "
                        + "presence check on the DEFINITION rather than on its inputs");
    }

    // ==================== Cycles and depth ====================

    @Test
    void aDefinitionThatReachesItselfFailsClosedInsteadOfRecursing() {
        FactorRegistry registry = new FactorRegistry();
        define(Map.of(
                "yourmod:a", formula("yourmod:b", 1.0),
                "yourmod:b", formula("yourmod:a", 1.0)));

        assertNull(registry.resolve("yourmod:a", ctx()),
                "a cycle must fail closed all the way out, and above all must not stack-overflow the "
                        + "world thread - the degrade-to-zero rule is for a missing input, never a loop");
        assertTrue(registry.info().get("yourmod:a").failures() >= 1,
                "the cycle is counted against the definition that closed the loop");
        assertNotNull(registry.info().get("yourmod:a").lastFailure());
        assertTrue(registry.info().get("yourmod:a").lastFailure().contains("cycle"));
    }

    @Test
    void aDirectSelfReferenceFailsClosedToo() {
        FactorRegistry registry = new FactorRegistry();
        define(Map.of("yourmod:self", formula("yourmod:self", 1.0)));

        assertNull(registry.resolve("yourmod:self", ctx()));
    }

    @Test
    void aCycleLeavesNoResidueSoTheNextResolutionIsUnaffected() {
        FactorRegistry registry = new FactorRegistry();
        registry.register("yourmod:plain", c -> 5.0);
        define(Map.of(
                "yourmod:a", formula("yourmod:b", 1.0),
                "yourmod:b", formula("yourmod:a", 1.0),
                "yourmod:ok", formula("yourmod:plain", 1.0)));

        assertNull(registry.resolve("yourmod:a", ctx()));
        assertEquals(5.0, registry.resolve("yourmod:ok", ctx()),
                "the in-flight path must unwind fully, or a pooled world thread would poison later reads");
    }

    @Test
    void aChainDeeperThanTheCapFailsClosedRatherThanWalkingForever() {
        FactorRegistry registry = new FactorRegistry();
        registry.register("yourmod:leaf", c -> 1.0);

        int depth = FactorRegistry.MAX_DERIVED_DEPTH + 4;
        Map<String, FactorFormula> definitions = new HashMap<>();
        for (int i = 0; i < depth; i++) {
            String next = i + 1 < depth ? "yourmod:d" + (i + 1) : "yourmod:leaf";
            definitions.put("yourmod:d" + i, formula(next, 1.0));
        }
        define(definitions);

        assertNull(registry.resolve("yourmod:d0", ctx()));
        assertEquals(1.0, registry.resolve("yourmod:d" + (depth - 1), ctx()),
                "a shallow definition in the same graph still resolves");
    }

    @Test
    void aThrowingDerivedSourceIsTreatedAsNoDefinition() {
        FactorRegistry registry = new FactorRegistry();
        registry.derivedSource(id -> {
            throw new IllegalStateException("source blew up");
        });

        assertNull(registry.resolve("yourmod:anything", ctx()));
    }

    // ==================== The asset ====================

    @Test
    void theAssetDecodesItsFormulaAndTheConfigServesItByFileName() throws IOException {
        DerivedFactorAsset asset = DerivedFactorAsset.CODEC.decodeJsonAsset(
                RawJsonReader.fromJsonString("""
                        { "$Comment": "how heavy the player's kit is",
                          "Formula": { "Base": 1.0,
                                       "Factors": [ {"Factor": "yourmod:quality", "Weight": 0.5} ],
                                       "Clamp": {"Min": 1.0, "Max": 5.0} } }
                        """),
                new AssetExtraInfo<>(new AssetExtraInfo.Data(DerivedFactorAsset.class,
                        "yourmod_gear_score", null)));

        FactorFormula formula = asset.getFormula();
        assertNotNull(formula);
        assertEquals(1.0, formula.getBase());
        assertEquals("yourmod:quality", formula.getFactors()[0].getFactor());

        define(Map.of("yourmod_gear_score", formula));
        FactorRegistry registry = new FactorRegistry();
        registry.register("yourmod:quality", c -> 4.0);

        assertEquals(3.0, registry.resolve("YourMod_Gear_Score", ctx()),
                "ids fold and match case-insensitively, exactly as registered ones do");
    }

    @Test
    void anAssetInheritsItsParentsFormulaLeafByLeaf() throws IOException {
        DerivedFactorAsset parent = DerivedFactorAsset.CODEC.decodeJsonAsset(
                RawJsonReader.fromJsonString("""
                        { "Formula": { "Base": 1.0,
                                       "Factors": [ {"Factor": "yourmod:quality", "Weight": 0.5} ] } }
                        """),
                new AssetExtraInfo<>(new AssetExtraInfo.Data(DerivedFactorAsset.class, "base", null)));

        DerivedFactorAsset child = DerivedFactorAsset.CODEC.decodeAndInheritJsonAsset(
                RawJsonReader.fromJsonString("{ \"Formula\": { \"Clamp\": {\"Max\": 2.0} } }"),
                parent,
                new AssetExtraInfo<>(new AssetExtraInfo.Data(DerivedFactorAsset.class, "child", "base")));

        assertEquals(1.0, child.getFormula().getBase());
        assertEquals("yourmod:quality", child.getFormula().getFactors()[0].getFactor());
        assertEquals(2.0, child.getFormula().getClamp().getMax());
    }

    @Test
    void theConfigTreatsAnEmptyFormulaAsUndefined() {
        define(Map.of(
                "yourmod:empty", new FactorFormula(),
                "yourmod:real", FactorFormula.of(1.0, null, null)));

        assertNull(DerivedFactorConfig.getInstance().formulaFor("yourmod:empty"));
        assertNotNull(DerivedFactorConfig.getInstance().formulaFor("yourmod:real"));
        assertNull(DerivedFactorConfig.getInstance().formulaFor("yourmod:never_authored"));
    }

    @Test
    void aClampedDefinitionIsHeldInsideItsBounds() {
        FactorRegistry registry = new FactorRegistry();
        registry.register("yourmod:quality", c -> 100.0);
        define(Map.of("yourmod:capped", FactorFormula.of(1.0,
                new Term[]{Term.of("yourmod:quality", null, 1.0)}, Clamp.of(1.0, 5.0))));

        assertEquals(5.0, registry.resolve("yourmod:capped", ctx()));
    }
}
