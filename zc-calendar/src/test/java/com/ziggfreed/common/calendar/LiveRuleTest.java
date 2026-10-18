package com.ziggfreed.common.calendar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

/** A composite event's three lists as a truth table, and the loop that refuses one. */
class LiveRuleTest {

    private static LiveRule rule(String[] anyOf, String[] allOf, String[] noneOf) {
        return LiveRule.of(anyOf, allOf, noneOf);
    }

    private static Predicate<String> live(String... ids) {
        Set<String> on = Set.of(ids);
        return on::contains;
    }

    @Test
    void anyOfHoldsWhileAnyMemberIsLive() {
        LiveRule any = rule(new String[]{"A", "B"}, null, null);
        assertFalse(any.holds(live()));
        assertTrue(any.holds(live("a")));
        assertTrue(any.holds(live("b")));
        assertTrue(any.holds(live("a", "b")));
    }

    @Test
    void allOfHoldsOnlyWhileEveryMemberIsLive() {
        LiveRule all = rule(null, new String[]{"A", "B"}, null);
        assertFalse(all.holds(live()));
        assertFalse(all.holds(live("a")));
        assertFalse(all.holds(live("b")));
        assertTrue(all.holds(live("a", "b")));
    }

    @Test
    void noneOfHoldsOnlyWhileNoMemberIsLive() {
        LiveRule none = rule(null, null, new String[]{"A", "B"});
        assertTrue(none.holds(live()), "NoneOf alone holds while nothing it names is live");
        assertFalse(none.holds(live("a")));
        assertFalse(none.holds(live("b")));
    }

    @Test
    void theThreeListsHoldTogether() {
        // Live while a is, and b is, and c is not: the shape of a full moon outside a season (AllOf, NoneOf) with a
        // third list beside it.
        LiveRule mixed = rule(new String[]{"a"}, new String[]{"b"}, new String[]{"c"});
        Map<Set<String>, Boolean> table = Map.of(
                Set.of(), false,
                Set.of("a"), false,
                Set.of("b"), false,
                Set.of("c"), false,
                Set.of("a", "b"), true,
                Set.of("a", "c"), false,
                Set.of("b", "c"), false,
                Set.of("a", "b", "c"), false);
        table.forEach((on, expected) -> assertEquals(expected, mixed.holds(on::contains), "live: " + on));
    }

    @Test
    void aLiveNamingNothingNeverHoldsAndAuthoredIdsReadTrimmedAndLowerCased() {
        LiveRule empty = rule(new String[]{" ", ""}, null, new String[0]);
        assertTrue(empty.isEmpty());
        assertFalse(empty.holds(id -> true));
        LiveRule named = rule(new String[]{" Full_Moon ", "full_moon"}, new String[]{"Hallows_Eve"}, null);
        assertEquals(List.of("full_moon", "hallows_eve"), named.members(), "each once, matched without regard to case");
    }

    @Test
    void aLoopIsFoundWhateverIsLiveAndRefusesEveryEventLeadingIntoIt() {
        Map<String, LiveRule> rules = Map.of(
                "a", rule(new String[]{"b"}, null, null),
                "b", rule(null, null, new String[]{"a"}),
                "c", rule(new String[]{"d", "a"}, null, null),
                "e", rule(new String[]{"d"}, null, null),
                "self", rule(null, new String[]{"self"}, null));
        Function<String, LiveRule> lookup = rules::get;
        assertEquals(List.of("a", "b", "a"), LiveRule.loopFrom("a", lookup));
        assertEquals(List.of("b", "a", "b"), LiveRule.loopFrom("B", lookup), "found from any event on it, any case");
        assertEquals(List.of("a", "b", "a"), LiveRule.loopFrom("c", lookup),
                "an event leading into a loop is refused too, though d comes first and could answer alone");
        assertEquals(List.of("self", "self"), LiveRule.loopFrom("self", lookup), "an event can never be live by itself");
        assertNull(LiveRule.loopFrom("e", lookup), "d is no composite, so the path ends there");
        assertTrue(LiveRule.refused("a", lookup));
        assertFalse(LiveRule.refused("e", lookup));
    }

    @Test
    void aMemberNamedTwiceIsNoLoop() {
        Map<String, LiveRule> rules = Map.of(
                "top", rule(new String[]{"mid", "leaf"}, new String[]{"mid"}, null),
                "mid", rule(new String[]{"leaf"}, null, null));
        assertNull(LiveRule.loopFrom("top", rules::get), "two paths to one event are a diamond, never a loop");
    }
}
