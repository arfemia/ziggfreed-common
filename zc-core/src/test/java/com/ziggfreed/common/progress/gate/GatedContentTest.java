package com.ziggfreed.common.progress.gate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.i18n.Msg;

/**
 * {@link GatedContent}: sources register by id and are asked live, a throwing source costs only its own entries,
 * and {@link GatedContent#positiveFactors} reads every route that opens a block (its leaves, AllOf and AnyOf) and
 * never a Not group.
 */
class GatedContentTest {

    @AfterEach
    void clear() {
        GatedContent.forget("test.a");
        GatedContent.forget("test.b");
    }

    private static GatedContent.Entry entry(String name) {
        return new GatedContent.Entry(GateSpec.OPEN, " Some_Item ", false, Msg.raw(name), null);
    }

    @Test
    void sourcesAreAskedInOrderAndAReRegistrationReplacesTheOldOne() {
        GatedContent.register("test.a", () -> List.of(entry("one")));
        GatedContent.register("test.b", () -> List.of(entry("two")));
        GatedContent.register("TEST.A", () -> List.of(entry("three")));
        List<GatedContent.Entry> all = GatedContent.all();
        assertEquals(2, all.size(), "the id matches without regard to case, so the second register replaced the first");
        assertEquals("Some_Item", all.get(0).iconItemId(), "the picture's id is trimmed");
        assertTrue(GatedContent.forget("test.b"));
        assertFalse(GatedContent.forget("test.b"), "nothing left to forget");
    }

    @Test
    void aThrowingSourceCostsOnlyItsOwnEntries() {
        GatedContent.register("test.a", () -> {
            throw new IllegalStateException("broken");
        });
        GatedContent.register("test.b", () -> List.of(entry("kept")));
        assertEquals(1, GatedContent.all().size());
    }

    @Test
    void positiveFactorsReadTheLeavesAllOfAndAnyOfButNeverNot() {
        FactorCondition leaf = FactorCondition.of("mod:leaf", "a", 1.0, null);
        FactorCondition all = FactorCondition.of("mod:all", "b", 1.0, null);
        FactorCondition any = FactorCondition.of("mod:any", "c", 1.0, null);
        FactorCondition not = FactorCondition.of("mod:not", "d", 1.0, null);
        FactorCondition blank = FactorCondition.of(" ", "e", 1.0, null);
        GateSpec spec = GateSpec.of(new FactorCondition[] {leaf, blank}, null, null, null,
                new GateClause[] {GateClause.of(new FactorCondition[] {all}, null, null, null)},
                new GateClause[] {GateClause.of(new FactorCondition[] {any}, null, null, null)},
                new GateClause[] {GateClause.of(new FactorCondition[] {not}, null, null, null)});
        assertEquals(List.of(leaf, all, any), GatedContent.positiveFactors(spec));
        assertEquals(List.of(), GatedContent.positiveFactors(null));
    }
}
