package com.ziggfreed.common.factor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.function.BiFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.codec.ExtraInfo;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.util.RawJsonReader;
import com.ziggfreed.common.factor.FactorFormula.Clamp;
import com.ziggfreed.common.factor.FactorFormula.Term;

/**
 * The value side's ONE load-bearing behaviour, and the exact mirror image of
 * {@code FactorVocabularyTest}'s: <b>a formula degrades gracefully where a gate fails closed</b>.
 *
 * <p>Every way a factor can fail to produce a number contributes 0 here rather than voiding the
 * result, because a term is an ADDEND and the neutral addend is zero. The tempting symmetry (a
 * missing term voids the formula) would let one uninstalled mod's optional {@code +0.25} blank a
 * multiplier, a price, or a reward count everywhere it is used, which is a far bigger failure than
 * the missing bonus.
 */
class FactorFormulaTest {

    private static FactorContext ctx() {
        return FactorContext.builder().build();
    }

    private static FactorFormula decode(String json) throws IOException {
        return FactorFormula.CODEC.decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }

    // ==================== Base + terms + weights ====================

    @Test
    void aFormulaWithOnlyABaseIsThatBase() {
        FactorRegistry registry = new FactorRegistry();

        assertEquals(2.5, FactorFormula.of(2.5, null, null).evaluate(registry, ctx()));
    }

    @Test
    void anUnauthoredBaseReadsAsZero() {
        FactorRegistry registry = new FactorRegistry();
        registry.register("yourmod:one", c -> 1.0);

        FactorFormula formula = FactorFormula.of(null, new Term[]{Term.of("yourmod:one", null, null)}, null);
        assertEquals(1.0, formula.evaluate(registry, ctx()));
        assertEquals(0.0, formula.baseOrZero());
    }

    @Test
    void anUnauthoredWeightIsOne() {
        FactorRegistry registry = new FactorRegistry();
        registry.register("yourmod:four", c -> 4.0);

        assertEquals(4.0, FactorFormula.of(null,
                new Term[]{Term.of("yourmod:four", null, null)}, null).evaluate(registry, ctx()));
        assertEquals(FactorFormula.DEFAULT_WEIGHT, Term.of("f", null, null).weightOrDefault());
    }

    @Test
    void everyTermIsWeightedAndSummedOnTopOfTheBase() {
        FactorRegistry registry = new FactorRegistry();
        registry.register("yourmod:four", c -> 4.0);
        registry.register("yourmod:ten", c -> 10.0);

        FactorFormula formula = FactorFormula.of(1.0, new Term[]{
                Term.of("yourmod:four", null, 0.5),
                Term.of("yourmod:ten", null, 0.1)}, null);

        assertEquals(4.0, formula.evaluate(registry, ctx()), 1e-9, "1 + (4 * 0.5) + (10 * 0.1)");
    }

    @Test
    void aNegativeWeightIsAPenalty() {
        FactorRegistry registry = new FactorRegistry();
        registry.register("yourmod:load", c -> 3.0);

        assertEquals(1.0, FactorFormula.of(4.0,
                new Term[]{Term.of("yourmod:load", null, -1.0)}, null).evaluate(registry, ctx()));
    }

    @Test
    void eachTermIsResolvedWithItsOwnParam() {
        FactorRegistry registry = new FactorRegistry();
        registry.register("yourmod:rep", c -> "guild".equals(c.param()) ? 10.0 : 1.0);

        FactorFormula formula = FactorFormula.of(null, new Term[]{
                Term.of("yourmod:rep", "guild", 1.0),
                Term.of("yourmod:rep", "town", 1.0)}, null);

        assertEquals(11.0, formula.evaluate(registry, ctx()),
                "one factor id read twice with different arguments is the whole point of a per-term Param");
    }

    // ==================== Graceful degradation ====================

    @Test
    void anUnresolvableTermContributesZeroInsteadOfVoidingTheResult() {
        FactorRegistry registry = new FactorRegistry();
        registry.register("yourmod:present", c -> 2.0);

        FactorFormula formula = FactorFormula.of(1.0, new Term[]{
                Term.of("yourmod:present", null, 1.0),
                Term.of("absentmod:bonus", null, 5.0)}, null);

        assertEquals(3.0, formula.evaluate(registry, ctx()),
                "an absent mod costs its own bonus, never the whole value");
    }

    @Test
    void aNonFiniteAnswerAndAThrowingProviderBothContributeZero() {
        FactorRegistry registry = new FactorRegistry();
        registry.register("yourmod:nan", c -> Double.NaN);
        registry.register("yourmod:infinite", c -> Double.POSITIVE_INFINITY);
        registry.register("yourmod:boom", c -> {
            throw new IllegalStateException("provider blew up");
        });
        registry.register("yourmod:quiet", c -> null);

        FactorFormula formula = FactorFormula.of(7.0, new Term[]{
                Term.of("yourmod:nan", null, 1.0),
                Term.of("yourmod:infinite", null, 1.0),
                Term.of("yourmod:boom", null, 1.0),
                Term.of("yourmod:quiet", null, 1.0)}, null);

        assertEquals(7.0, formula.evaluate(registry, ctx()));
    }

    @Test
    void aNonFiniteWeightIsReadAsOneRatherThanPoisoningTheSum() {
        FactorRegistry registry = new FactorRegistry();
        registry.register("yourmod:two", c -> 2.0);

        assertEquals(2.0, FactorFormula.of(null,
                new Term[]{Term.of("yourmod:two", null, Double.NaN)}, null).evaluate(registry, ctx()));
    }

    @Test
    void aTermWithNoFactorIdIsSkippedTheWayABlankConditionIs() {
        FactorRegistry registry = new FactorRegistry();
        registry.register("yourmod:one", c -> 1.0);

        FactorFormula formula = FactorFormula.of(null, new Term[]{
                Term.of(null, null, 99.0),
                Term.of("   ", null, 99.0),
                Term.of("yourmod:one", null, 1.0)}, null);

        assertEquals(1.0, formula.evaluate(registry, ctx()));
        assertTrue(Term.of(null, null, null).isBlank());
        assertFalse(Term.of("f", null, null).isBlank());
    }

    // ==================== Clamp ====================

    @Test
    void clampBoundsAreInclusiveAndIndependentlyOptional() {
        FactorRegistry registry = new FactorRegistry();

        assertEquals(1.0, FactorFormula.of(0.5, null, Clamp.of(1.0, null)).evaluate(registry, ctx()),
                "a floor with no ceiling");
        assertEquals(4.0, FactorFormula.of(9.0, null, Clamp.of(null, 4.0)).evaluate(registry, ctx()),
                "a ceiling with no floor");
        assertEquals(2.0, FactorFormula.of(2.0, null, Clamp.of(1.0, 4.0)).evaluate(registry, ctx()),
                "a value already inside the bounds is untouched");
        assertEquals(4.0, FactorFormula.of(4.0, null, Clamp.of(1.0, 4.0)).evaluate(registry, ctx()),
                "the bounds are inclusive");
    }

    @Test
    void aClampFloorIsWhatHoldsAFormulaUpWhenEveryOptionalTermIsMissing() {
        FactorRegistry registry = new FactorRegistry();

        FactorFormula formula = FactorFormula.of(null,
                new Term[]{Term.of("absentmod:bonus", null, 1.0)}, Clamp.of(1.0, null));

        assertEquals(1.0, formula.evaluate(registry, ctx()));
    }

    @Test
    void aNonFiniteClampBoundIsIgnoredRatherThanApplied() {
        assertEquals(3.0, Clamp.of(Double.NaN, Double.POSITIVE_INFINITY).apply(3.0));
    }

    @Test
    void anInvertedClampIsDetectable() {
        assertTrue(Clamp.of(5.0, 1.0).isInverted());
        assertFalse(Clamp.of(1.0, 5.0).isInverted());
        assertFalse(Clamp.of(5.0, null).isInverted());
    }

    // ==================== The thread-through form ====================

    @Test
    void theStaticSumMirrorsTheRegistryFormThroughAnArbitraryLookup() {
        Term[] terms = {Term.of("a", "p", 2.0), Term.of("b", null, null)};
        BiFunction<String, String, Double> lookup = (factor, param) -> switch (factor) {
            case "a" -> "p".equals(param) ? 3.0 : 0.0;
            case "b" -> 1.0;
            default -> null;
        };

        assertEquals(7.0, FactorFormula.sum(terms, lookup), "(3 * 2) + 1");
        assertEquals(0.0, FactorFormula.sum(null, lookup));
        assertEquals(0.0, FactorFormula.sum(new Term[0], lookup));
    }

    @Test
    void aLookupThatAnswersNothingOrThrowsContributesZero() {
        Term[] terms = {Term.of("a", null, 1.0), Term.of("b", null, 1.0)};

        assertEquals(0.0, FactorFormula.sum(terms, (factor, param) -> null));
        assertEquals(0.0, FactorFormula.sum(terms, (factor, param) -> {
            throw new IllegalStateException("lookup blew up");
        }), "a broken lookup must not take the whole value with it either");
    }

    @Test
    void rawSumIsTheTermsAloneWithNoBaseAndNoClamp() {
        FactorRegistry registry = new FactorRegistry();
        registry.register("yourmod:two", c -> 2.0);

        FactorFormula formula = FactorFormula.of(100.0,
                new Term[]{Term.of("yourmod:two", null, 3.0)}, Clamp.of(0.0, 1.0));

        assertEquals(6.0, formula.rawSum(registry, ctx()));
        assertEquals(1.0, formula.evaluate(registry, ctx()), "while the full evaluation still clamps");
    }

    // ==================== The codec ====================

    @Test
    void absentKeysDecodeToNullAndTheFormulaStillEvaluates() throws IOException {
        FactorFormula formula = decode("{ \"Base\": 2.0 }");

        assertEquals(2.0, formula.getBase());
        assertNull(formula.getFactors());
        assertNull(formula.getClamp());
        assertEquals(2.0, formula.evaluate(new FactorRegistry(), ctx()));
    }

    @Test
    void theWholeGroupDecodesFromTheAuthoredShape() throws IOException {
        FactorFormula formula = decode("""
                { "$Comment": "a tip for the server owner",
                  "Base": 1.0,
                  "Factors": [ {"Factor": "yourmod:quality", "Param": "pick", "Weight": 0.25},
                               {"Factor": "yourmod:level"} ],
                  "Clamp": {"Min": 1.0, "Max": 4.0} }
                """);

        Term[] terms = formula.getFactors();
        assertNotNull(terms);
        assertEquals(2, terms.length);
        assertEquals("yourmod:quality", terms[0].getFactor());
        assertEquals("pick", terms[0].getParam());
        assertEquals(0.25, terms[0].getWeight());
        assertNull(terms[1].getWeight(), "an unauthored weight stays null and reads as 1.0");
        assertEquals(1.0, formula.getClamp().getMin());
        assertEquals(4.0, formula.getClamp().getMax());
    }

    @Test
    void aDropdownBearingCodecDecodesIdenticallyToThePlainOne() throws IOException {
        FactorFormula formula = FactorFormula.codec("ziggfreedcommon:factors")
                .decodeJson(RawJsonReader.fromJsonString(
                        "{ \"Factors\": [ {\"Factor\": \"yourmod:one\"} ] }"), new ExtraInfo());

        assertEquals("yourmod:one", formula.getFactors()[0].getFactor(),
                "the editor metadata is authoring sugar; it must never change what decodes");
    }

    @Test
    void everyLeafInheritsThroughNativeParentSoAChildMayOverrideOneGroup() throws IOException {
        FactorFormula parent = decode("""
                { "Base": 1.0,
                  "Factors": [ {"Factor": "yourmod:quality", "Weight": 0.5} ],
                  "Clamp": {"Min": 0.0, "Max": 10.0} }
                """);

        FactorFormula child = FactorFormula.CODEC.decodeAndInheritJson(
                RawJsonReader.fromJsonString("{ \"Clamp\": {\"Max\": 2.0} }"), parent, new ExtraInfo());

        assertEquals(1.0, child.getBase(), "an unmentioned Base carries over");
        assertNotNull(child.getFactors());
        assertEquals("yourmod:quality", child.getFactors()[0].getFactor(), "and so do the terms");
        assertEquals(2.0, child.getClamp().getMax(), "while the authored group wins");
    }

    @Test
    void everyCodecInTheGroupStaticInitializes() {
        // The module-local half of the PascalCase guard: these three nest inside a consumer's asset
        // rather than backing a store of their own, so a lower-case key would otherwise surface at
        // that consumer's decode. The aggregate AssetCodecInitTest covers the asset that embeds them.
        assertNotNull(FactorFormula.CODEC);
        assertNotNull(Term.CODEC);
        assertNotNull(Clamp.CODEC);
        assertNotNull(FactorFormula.codec("ziggfreedcommon:factors"));
        assertNotNull(Clamp.codec("ziggfreedcommon:factors"));
    }

    @Test
    void anEmptyFormulaSaysNothingButAnAuthoredZeroBaseIsAConstant() throws IOException {
        assertTrue(decode("{ }").isEmpty());
        assertTrue(decode("{ \"Factors\": [ {\"Param\": \"orphan\"} ] }").isEmpty(),
                "a term with no factor id cannot make a formula say anything");
        assertFalse(decode("{ \"Base\": 0.0 }").isEmpty(),
                "an authored zero is a deliberate constant, not an empty file");
    }

    // ==================== the bare-number spelling ====================

    /**
     * A flat value is the ordinary case for a chance, a weight or a multiplier, so a leaf built
     * with {@link FactorFormula#numberOrGroup} takes the number on its own and means exactly what
     * the long form means.
     */
    @Test
    void aBareNumberDecodesAsTheBase() throws IOException {
        FactorFormula plain = numberOrGroup("0.35");
        assertNotNull(plain);
        assertEquals(0.35, plain.baseOrZero());
        assertNull(plain.getFactors());
        assertNull(plain.getClamp());
        assertEquals(numberOrGroup("{\"Base\": 0.35}").baseOrZero(), plain.baseOrZero());
    }

    /** A negative value is a number too, so the arm may not be recognised by a leading digit alone. */
    @Test
    void aNegativeBareNumberDecodes() throws IOException {
        FactorFormula plain = numberOrGroup("-2");
        assertNotNull(plain);
        assertEquals(-2.0, plain.baseOrZero());
    }

    /** The group form still decodes whole through the same leaf: one model under both spellings. */
    @Test
    void theGroupFormStillDecodesThroughTheSameLeaf() throws IOException {
        FactorFormula group = numberOrGroup(
                "{\"Base\": 1.0, \"Factors\": [{\"Factor\": \"f\", \"Weight\": 2.0}]}");
        assertNotNull(group);
        assertEquals(1.0, group.baseOrZero());
        assertNotNull(group.getFactors());
        assertEquals(1, group.getFactors().length);
    }

    /**
     * The rule that keeps {@link FactorFormula#numberOrGroup} out of an inheritable leaf, pinned
     * where it can be checked rather than only written down.
     *
     * <p>Per-leaf {@code Parent} merging dispatches on the child codec's CONCRETE type: a leaf whose
     * codec is not a {@link BuilderCodec} is decoded on its own and replaces the parent's whole
     * group. So the union arm is deliberately not a builder codec (it could not export its number
     * arm if it were), and the group form deliberately is. A change that blurs the two would take
     * inheritance out of every formula leaf silently, as a wrong value rather than a failure, which
     * is exactly what this asserts against.
     */
    @Test
    void onlyTheGroupFormIsInheritable() {
        assertInstanceOf(BuilderCodec.class, FactorFormula.codec(null),
                "the group form carries per-leaf Parent merging, so it must stay a BuilderCodec");
        assertFalse(FactorFormula.numberOrGroup(null) instanceof BuilderCodec,
                "the union arm cannot be a BuilderCodec and must never sit on an appendInherited leaf");
    }

    @Nullable
    private static FactorFormula numberOrGroup(@Nonnull String json) throws IOException {
        return FactorFormula.numberOrGroup(null)
                .decodeJson(RawJsonReader.fromJsonString(json), new ExtraInfo());
    }
}
