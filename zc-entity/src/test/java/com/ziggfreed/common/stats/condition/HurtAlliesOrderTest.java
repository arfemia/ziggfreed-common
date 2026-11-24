package com.ziggfreed.common.stats.condition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Who a heal aimed at a hurt ally reaches first ({@link Nearby#hurtFirst}, the pure half of
 * {@link Nearby#hurtAlliesAround}): only an ally below full health counts, the nearest first, or the lowest share of
 * health first with the nearer winning a tie; nobody hurt in reach is nobody.
 */
class HurtAlliesOrderTest {

    private static Nearby.Hurt<String> ally(String who, double distanceSq, Double percent) {
        return new Nearby.Hurt<>(who, distanceSq, percent);
    }

    @Test
    void theNearestHurtAllyComesFirst_andAFullOrUnreadableOneNeverCounts() {
        List<Nearby.Hurt<String>> found = List.of(
                ally("full", 1.0, 100.0),
                ally("far", 64.0, 20.0),
                ally("unreadable", 2.0, null),
                ally("near", 9.0, 90.0));
        assertEquals(List.of("near", "far"), Nearby.hurtFirst(found, false));
    }

    @Test
    void mostHurtFirst_takesTheLowestShareOfHealth_andTheNearerOnATie() {
        List<Nearby.Hurt<String>> found = List.of(
                ally("near_scratched", 1.0, 95.0),
                ally("far_low", 100.0, 30.0),
                ally("near_low", 4.0, 30.0));
        assertEquals(List.of("near_low", "far_low", "near_scratched"), Nearby.hurtFirst(found, true));
    }

    @Test
    void noHurtAllyInReach_isNobody() {
        assertTrue(Nearby.hurtFirst(List.of(ally("full", 1.0, 100.0)), false).isEmpty());
        assertTrue(Nearby.hurtFirst(List.<Nearby.Hurt<String>>of(), true).isEmpty());
    }
}
