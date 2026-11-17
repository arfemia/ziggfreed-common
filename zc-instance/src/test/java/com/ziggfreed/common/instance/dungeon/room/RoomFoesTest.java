package com.ziggfreed.common.instance.dungeon.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * A room's foes ({@link RoomFoes}, R1054): a bigger party meets more foes, never a role that already grows with the
 * party, picked from the run's seed so a daily's rooms hold the same foes for every party, a bigger party's being the
 * smaller one's plus its extras, about one in four of them ranged. Untagged: nothing here builds an engine item.
 */
class RoomFoesTest {

    private static final RoomFoes.Roster CRYPT = new RoomFoes.Roster(List.of("Kit=Cursed_Crypt"),
            List.of("Skeleton_Burnt_Soldier", "Zombie"), List.of("Skeleton_Burnt_Archer"), List.of("Wraith"), Set.of());

    private static final RoomFoes.Roster MINES = new RoomFoes.Roster(List.of("Kit=Shale"),
            List.of("Dungeon_Goblin_Scrapper", "Goblin_Miner"), List.of(), List.of(), Set.of("Dungeon_Goblin_Scrapper"));

    private static final RoomFoes.Rule SEALED = new RoomFoes.Rule("Combat", true, 4, 1.0, 0.0);
    private static final RoomFoes.Rule PACK = new RoomFoes.Rule("Junction", false, 2, 0.5, 0.0);

    @Test
    void aBiggerPartyMeetsMoreFoesRoundedDownAndNeverPastFive() {
        assertEquals(4, RoomFoes.count(SEALED, 1));
        assertEquals(5, RoomFoes.count(SEALED, 2));
        assertEquals(8, RoomFoes.count(SEALED, 5));
        assertEquals(8, RoomFoes.count(SEALED, 9), "a party is sized as five at most");
        assertEquals(4, RoomFoes.count(SEALED, 0), "no party reads as one player");
        assertEquals(2, RoomFoes.count(PACK, 2), "half a foe per player rounds down");
        assertEquals(3, RoomFoes.count(PACK, 3));
        assertEquals(4, RoomFoes.count(PACK, 5));
    }

    @Test
    void theSameSeedAndRoomPickTheSameFoesAndABiggerPartyAddsToThem() {
        List<RoomFoes.Pick> solo = RoomFoes.picks(CRYPT, SEALED, 1, 42L, 3);
        assertEquals(solo, RoomFoes.picks(CRYPT, SEALED, 1, 42L, 3), "a daily's room holds the same foes");
        List<RoomFoes.Pick> five = RoomFoes.picks(CRYPT, SEALED, 5, 42L, 3);
        assertEquals(8, five.size());
        assertEquals(solo, five.subList(0, solo.size()), "the party's extras come after the solo foes");
        assertEquals(1, solo.stream().filter(p -> CRYPT.ranged().contains(p.role())).count(),
                "one foe in four is ranged");
        assertTrue(five.subList(4, 8).stream().allMatch(RoomFoes.Pick::extra));
    }

    @Test
    void anExtraIsNeverARoleThatGrowsWithThePartyByItself() {
        for (long seed = 0; seed < 200; seed++) {
            List<RoomFoes.Pick> picks = RoomFoes.picks(MINES, SEALED, 4, seed, 1);
            for (RoomFoes.Pick pick : picks) {
                if (pick.extra()) {
                    assertEquals("Goblin_Miner", pick.role(), "seed " + seed);
                }
            }
        }
        RoomFoes.Roster onlyScaled = new RoomFoes.Roster(List.of(), List.of("Dungeon_Goblin_Scrapper"), List.of(),
                List.of(), Set.of("dungeon_goblin_scrapper"));
        assertEquals(4, RoomFoes.picks(onlyScaled, SEALED, 5, 7L, 0).size(),
                "a roster of only party-scaled roles adds no extras");
    }

    @Test
    void anEliteJoinsAtItsChanceWhateverThePartysSize() {
        RoomFoes.Rule always = new RoomFoes.Rule("Combat", true, 4, 1.0, 1.0);
        List<RoomFoes.Pick> solo = RoomFoes.picks(CRYPT, always, 1, 9L, 2);
        assertEquals(5, solo.size());
        assertTrue(solo.get(4).elite());
        assertEquals("Wraith", solo.get(4).role());
        RoomFoes.Rule half = new RoomFoes.Rule("Combat", true, 4, 1.0, 0.5);
        for (long seed = 0; seed < 50; seed++) {
            boolean soloElite = RoomFoes.picks(CRYPT, half, 1, seed, 5).stream().anyMatch(RoomFoes.Pick::elite);
            boolean fiveElite = RoomFoes.picks(CRYPT, half, 5, seed, 5).stream().anyMatch(RoomFoes.Pick::elite);
            assertEquals(soloElite, fiveElite, "seed " + seed + ": the elite's roll never moves with the party");
        }
    }

    @Test
    void aRoomTakesTheFirstRuleForItsRoleAndTheFirstRosterForItsTags() {
        RoomFoes.Plan plan = new RoomFoes.Plan(List.of(CRYPT, MINES), List.of(SEALED, PACK), "Rock_Crystal_Void_Block");
        assertFalse(plan.empty());
        assertEquals(SEALED, plan.ruleFor(List.of("combat")));
        assertNull(plan.ruleFor(List.of("puzzle")));
        assertEquals(MINES, plan.rosterFor(List.of("kit", "shale", "kit=shale")));
        assertNull(plan.rosterFor(List.of("kit=stone")));
        RoomFoes.Roster any = new RoomFoes.Roster(List.of(), List.of("Zombie"), List.of(), List.of(), Set.of());
        assertNotNull(new RoomFoes.Plan(List.of(any), List.of(SEALED), null).rosterFor(List.of("kit=stone")),
                "a roster with no AnyOf serves every room");
        assertTrue(RoomFoes.Plan.NONE.empty());
    }
}
