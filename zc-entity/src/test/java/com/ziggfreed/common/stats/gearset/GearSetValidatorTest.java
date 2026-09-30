package com.ziggfreed.common.stats.gearset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.text.ContentTextAsset;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * One case per code, each driven through the pure core with fakes for the item, stat and effect
 * stores; and the two rules around the codes: a disabled set is skipped, and every finding is filed
 * under the {@code gear_set} domain.
 */
class GearSetValidatorTest {

    private static final Predicate<String> ALL_ITEMS = id -> true;
    private static final Predicate<String> ALL_STATS = id -> true;
    private static final Predicate<String> ALL_EFFECTS = id -> true;
    private static final ToDoubleFunction<String> BASE_TEN = id -> 10.0;

    @Nonnull
    private static ContentTextAsset named(String key) {
        return ContentTextAsset.of(key, null, null);
    }

    @Nonnull
    private static GearSetAsset.Tier tier(Integer pieces, Integer armor, Integer held, Integer utility) {
        return GearSetAsset.Tier.of(pieces, armor, held, utility, null, null,
                Map.of("Health", new StatModifierSpec[] {StatModifierSpec.additive(1f)}));
    }

    @Nonnull
    private static GearSetAsset sound() {
        return GearSetAsset.of("Sound_Set", named("gearset.sound.title"), null, new String[] {"A", "B", "C", "D"},
                tier(2, null, null, null), tier(null, 4, null, null));
    }

    @Nonnull
    private static List<String> codes(@Nonnull List<Finding> findings) {
        List<String> out = new ArrayList<>();
        for (Finding f : findings) {
            out.add(f.code());
        }
        return out;
    }

    @Nonnull
    private static List<Finding> audit(GearSetAsset... sets) {
        return GearSetValidator.audit(List.of(sets), ALL_ITEMS, ALL_STATS, ALL_EFFECTS, BASE_TEN);
    }

    @Test
    void aSoundSetReportsNothing() {
        assertTrue(audit(sound()).isEmpty());
    }

    @Test
    void everyFindingIsFiledUnderTheGearSetDomain() {
        GearSetAsset bare = GearSetAsset.of("Bare", null, null, null);
        for (Finding f : audit(bare)) {
            assertEquals(GearSetValidator.DOMAIN, f.domain());
            assertEquals("Bare", f.sourceId());
        }
    }

    @Test
    void aDisabledSetIsSkipped() {
        GearSetAsset off = GearSetAsset.of("Off", null, false, null);
        assertTrue(audit(off).isEmpty(), "a set switched off is nobody's problem");
    }

    @Test
    void emptyMembersAndNoBonusesAreErrorsAndAnUnnamedSetIsANote() {
        List<Finding> findings = audit(GearSetAsset.of("Bare", null, null, null));

        assertEquals(List.of(GearSetValidator.UNNAMED_SET, GearSetValidator.EMPTY_MEMBERS, GearSetValidator.NO_BONUSES),
                codes(findings));
        assertEquals(Severity.INFO, findings.get(0).severity());
        assertEquals(Severity.ERROR, findings.get(1).severity());
        assertEquals(Severity.ERROR, findings.get(2).severity());
    }

    @Test
    void aTierWithNoConditionIsAnError() {
        GearSetAsset set = GearSetAsset.of("S", named("k"), null, new String[] {"A", "B"}, tier(null, null, null, null));
        assertEquals(List.of(GearSetValidator.TIER_WITHOUT_CONDITION), codes(audit(set)));
    }

    @Test
    void badPieceCountsAreErrorsInEveryShape() {
        GearSetAsset set = GearSetAsset.of("S", named("k"), null, new String[] {"A", "B"},
                tier(0, null, null, null),
                tier(3, null, null, null),
                tier(null, 5, null, null),
                tier(null, null, 2, null),
                tier(null, null, null, -1));
        List<Finding> findings = audit(set);

        assertEquals(5, findings.size(), codes(findings).toString());
        for (Finding f : findings) {
            assertEquals(GearSetValidator.BAD_PIECE_COUNT, f.code());
            assertEquals(Severity.ERROR, f.severity());
        }
    }

    @Test
    void anUnknownMemberIsAWarning() {
        List<Finding> findings = GearSetValidator.audit(List.of(sound()), id -> !"C".equals(id), ALL_STATS,
                ALL_EFFECTS, BASE_TEN);
        assertEquals(List.of(GearSetValidator.UNKNOWN_SET_MEMBER), codes(findings));
        assertEquals(Severity.WARNING, findings.get(0).severity());
        assertTrue(findings.get(0).message().contains("'C'"));
    }

    @Test
    void aMemberSpelledInAnotherCaseIsNotUnknown() {
        List<String> folds = new ArrayList<>();
        Predicate<String> itemKnown = GearSetValidator.itemKnownIgnoringCase(Set.of("A", "B", "C", "D")::contains,
                () -> {
                    folds.add("fold");
                    return List.of("A", "B", "C", "D");
                });
        GearSetAsset lowerCase = GearSetAsset.of("Lower_Set", named("gearset.lower.title"), null,
                new String[] {"a", "b", "C", "Nowhere"}, tier(2, null, null, null));

        List<Finding> findings = GearSetValidator.audit(List.of(lowerCase), itemKnown, ALL_STATS, ALL_EFFECTS, BASE_TEN);

        assertEquals(List.of(GearSetValidator.UNKNOWN_SET_MEMBER), codes(findings),
                "members match without regard to case at runtime, so only the item nobody loads is unknown");
        assertTrue(findings.get(0).message().contains("'Nowhere'"));
        assertEquals(1, folds.size(), "the loaded ids are folded once per audit, and only on an exact miss");
    }

    @Test
    void anUnknownStatIdIsAWarningAndItsArithmeticIsNotChecked() {
        List<Finding> findings = GearSetValidator.audit(List.of(sound()), ALL_ITEMS, id -> false, ALL_EFFECTS,
                id -> 0.0);
        assertEquals(List.of(GearSetValidator.UNKNOWN_STAT_ID, GearSetValidator.UNKNOWN_STAT_ID), codes(findings),
                "one per tier that authors the channel, and no base-zero warning on a channel nobody knows");
    }

    @Test
    void twoTiersAskingTheSameThingIsAWarning() {
        GearSetAsset set = GearSetAsset.of("S", named("k"), null, new String[] {"A", "B", "C", "D"},
                tier(null, 4, null, null), tier(null, 4, null, null), tier(null, 4, 1, null));
        List<Finding> findings = audit(set);
        assertEquals(List.of(GearSetValidator.DUPLICATE_TIER), codes(findings),
                "Armor 4 twice is one warning; Armor 4 plus Held 1 is a different question");
        assertTrue(findings.get(0).message().contains("tier 0"));
    }

    @Test
    void aMultiplicativeModifierOnABaseZeroChannelIsAWarningWithTheSharedCode() {
        GearSetAsset set = GearSetAsset.of("S", named("k"), null, new String[] {"A", "B"},
                GearSetAsset.Tier.of(2, null, null, null, null, null,
                        Map.of("Mana", new StatModifierSpec[] {StatModifierSpec.of(0.5f, "Multiplicative", null)})));
        List<Finding> findings = GearSetValidator.audit(List.of(set), ALL_ITEMS, ALL_STATS, ALL_EFFECTS, id -> 0.0);

        assertEquals(List.of("MULTIPLICATIVE_ON_BASE_ZERO"), codes(findings),
                "the token a consumer's item audit already uses, so one filter covers both");
        assertEquals(Severity.WARNING, findings.get(0).severity());

        assertTrue(GearSetValidator.audit(List.of(set), ALL_ITEMS, ALL_STATS, ALL_EFFECTS, BASE_TEN).isEmpty(),
                "on a channel with a base it is fine");
        assertTrue(GearSetValidator.audit(List.of(set), ALL_ITEMS, ALL_STATS, ALL_EFFECTS, id -> Double.NaN).isEmpty(),
                "a base that cannot be read is not zero");
    }

    @Test
    void anUnknownEffectIsAWarning() {
        GearSetAsset set = GearSetAsset.of("S", named("k"), null, new String[] {"A", "B"},
                GearSetAsset.Tier.of(2, null, null, null, null, "No_Such_Effect", null));
        List<Finding> findings = GearSetValidator.audit(List.of(set), ALL_ITEMS, ALL_STATS, id -> false, BASE_TEN);
        assertEquals(List.of(GearSetValidator.UNKNOWN_SET_EFFECT), codes(findings));
        assertEquals(Severity.WARNING, findings.get(0).severity());
    }

    @Test
    void aSetOfOneMemberIsAWarning() {
        GearSetAsset set = GearSetAsset.of("S", named("k"), null, new String[] {"A", "a"}, tier(1, null, null, null));
        List<Finding> findings = audit(set);
        assertEquals(List.of(GearSetValidator.TOO_FEW_MEMBERS), codes(findings),
                "two spellings of one id are one member, and Pieces 1 is within that count");
        assertEquals(Severity.WARNING, findings.get(0).severity());
    }
}
