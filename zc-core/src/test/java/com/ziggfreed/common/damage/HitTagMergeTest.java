package com.ziggfreed.common.damage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The hit-tag merge and conflict rules, over plain maps: no engine class is touched, since
 * {@code Interaction}'s static init throws in this JVM.
 */
class HitTagMergeTest {

    private static final Set<String> HIT = Set.of("hit");

    /** A raw tag map the way the engine expands one: the authored key, each value and each Key=Value pair. */
    private static Map<String, String[]> raw(String key, String... values) {
        Map<String, String[]> out = new LinkedHashMap<>();
        out.put(key, values);
        for (String value : values) {
            out.putIfAbsent(value, new String[0]);
            out.putIfAbsent(key + "=" + value, new String[0]);
        }
        return out;
    }

    /** Value-equal on purpose, like the engine's DamageCalculator. */
    private record Calculator(int damage) {
    }

    @Test
    void theIndexedKeyMatchesIgnoringCaseAndTheExpandedFormsNeverDo() {
        Map<String, List<String>> tags = HitTagMerge.merge(List.of(raw("HIT", "Moonbloom")), HIT);

        assertEquals(Map.of("hit", List.of("Moonbloom")), tags);
    }

    @Test
    void anInlineNodeReadsItsOwnTagsFirstThenEachContainerAbove() {
        List<Map<String, String[]>> chain = Arrays.asList(
                raw("Hit", "Own"), null, raw("Hit", "File", "own"), raw("Hit", "Item"));

        assertEquals(List.of("Own", "File", "Item"), HitTagMerge.merge(chain, HIT).get("hit"));
    }

    @Test
    void anUnindexedKeyIsLeftOut() {
        List<Map<String, String[]>> chain = List.of(raw("MmoAbility", "Tempest"));

        assertTrue(HitTagMerge.merge(chain, HIT).isEmpty());
        assertEquals(Map.of("mmoability", List.of("Tempest")),
                HitTagMerge.merge(chain, Set.of("hit", "mmoability")));
    }

    @Test
    void aCalculatorIsKeyedByIdentityNeverByValue() {
        HitTagMerge.Table<Calculator> table = new HitTagMerge.Table<>();
        Calculator first = new Calculator(5);
        Calculator second = new Calculator(5);

        table.put(first, "First", Map.of("hit", List.of("A")), 1);
        table.put(second, "Second", Map.of("hit", List.of("B")), 1);

        assertEquals(2, table.tagged().size());
        assertEquals(List.of("A"), table.tagged().get(first).get("hit"));
        assertEquals(List.of("B"), table.tagged().get(second).get("hit"));
        assertTrue(table.warnings().isEmpty());
    }

    @Test
    void aSharedCalculatorWhoseNodesDisagreeIsIndexedWithNoTagsAndWarnedOnce() {
        HitTagMerge.Table<Calculator> table = new HitTagMerge.Table<>();
        Calculator shared = new Calculator(5);

        table.put(shared, "Parent", Map.of(), 1);
        table.put(shared, "Child", Map.of("hit", List.of("A")), 1);
        table.put(shared, "Grandchild", Map.of("hit", List.of("B")), 1);

        assertTrue(table.tagged().isEmpty());
        assertEquals(1, table.warnings().size());
    }

    @Test
    void aSharedCalculatorWhoseNodesAgreeKeepsItsTags() {
        HitTagMerge.Table<Calculator> table = new HitTagMerge.Table<>();
        Calculator shared = new Calculator(5);

        table.put(shared, "Parent", Map.of("hit", List.of("A", "B")), 1);
        table.put(shared, "Child", Map.of("hit", List.of("b", "a")), 1);

        assertEquals(List.of("A", "B"), table.tagged().get(shared).get("hit"));
        assertTrue(table.warnings().isEmpty());
    }

    @Test
    void aTaggedNodeNamingSeveralTypesWarnsAndStaysTagged() {
        HitTagMerge.Table<Calculator> table = new HitTagMerge.Table<>();
        Calculator tagged = new Calculator(1);
        Calculator untagged = new Calculator(2);

        table.put(tagged, "Tagged", Map.of("hit", List.of("A")), 2);
        table.put(untagged, "Untagged", Map.of(), 3);

        assertEquals(1, table.tagged().size());
        assertEquals(1, table.warnings().size());
    }

    @Test
    void declaredValuesKeepTheFirstSpellingAndMatchIgnoringCase() {
        Map<String, Set<String>> declared = new HashMap<>();
        HitTagMerge.declare(declared, Map.of("hit", List.of("Moonbloom")));
        HitTagMerge.declare(declared, Map.of("hit", List.of("MOONBLOOM", "Ember")));

        Set<String> hit = HitTagMerge.freeze(declared).get("hit");

        assertEquals(List.of("Ember", "Moonbloom"), List.copyOf(hit));
        assertTrue(hit.contains("moonbloom"));
    }
}
