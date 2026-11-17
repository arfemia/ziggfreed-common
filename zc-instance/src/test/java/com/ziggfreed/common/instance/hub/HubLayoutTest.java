package com.ziggfreed.common.instance.hub;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.instance.dungeon.layout.Box;
import com.ziggfreed.common.instance.hub.HubLayout.Claim;
import com.ziggfreed.common.instance.hub.HubLots.Lot;
import com.ziggfreed.common.instance.hub.HubLots.Size;

/**
 * The hub's fixed lot map and the one rule that lays districts on it: the map's own gate (unique ids, no two lots or
 * floors overlapping, every district box inside its floor, the Concourse in the middle), then the rule's clashes,
 * fixed lots, owner moves and the scaffolding on empty lots. Pure, no engine type.
 */
class HubLayoutTest {

    @Test
    void theMapHasUniqueIdsAndNoTwoLotsOrFloorsOverlap() {
        List<Lot> lots = HubLots.all();
        Set<String> ids = new HashSet<>();
        for (Lot lot : lots) {
            assertTrue(ids.add(lot.id().toLowerCase(Locale.ROOT)), "lot ids are unique without regard to case: " + lot.id());
            assertTrue(lot.box().min().x() >= lot.floor().min().x() && lot.box().max().x() <= lot.floor().max().x()
                    && lot.box().min().z() >= lot.floor().min().z() && lot.box().max().z() <= lot.floor().max().z(),
                    lot.id() + "'s district stands on its own floor");
        }
        for (int i = 0; i < lots.size(); i++) {
            for (int j = i + 1; j < lots.size(); j++) {
                Lot a = lots.get(i);
                Lot b = lots.get(j);
                assertFalse(a.box().overlaps(b.box()), a.id() + " and " + b.id() + " never share a block");
                assertFalse(a.floor().overlaps(b.floor()), a.id() + "'s and " + b.id() + "'s floors never overlap");
            }
        }
        Lot concourse = HubLots.find("concourse");
        assertNotNull(concourse, "the Concourse is found without regard to case");
        assertEquals(0, concourse.centreX());
        assertEquals(0, concourse.centreZ(), "players arrive at the template's spawn, the Concourse's centre");
        assertEquals(Size.LARGE, concourse.size());
    }

    @Test
    void theFloorsTileTheWholeHubWithNoGap() {
        Box reach = HubLots.reach();
        long floored = 0;
        for (Lot lot : HubLots.all()) {
            Box floor = lot.floor();
            floored += (long) (floor.max().x() - floor.min().x() + 1) * (floor.max().z() - floor.min().z() + 1);
        }
        long area = (long) (reach.max().x() - reach.min().x() + 1) * (reach.max().z() - reach.min().z() + 1);
        assertEquals(area, floored, "the lots' floors cover the hub's ground once, streets included");
    }

    @Test
    void everySideLotFacesTheConcourse() {
        assertEquals(2, lot("North").turns(), "a North-facing prefab turns twice to face South, toward the middle");
        assertEquals(0, lot("South").turns());
        assertEquals(1, lot("East").turns(), "one turn sends North to West, toward the middle");
        assertEquals(3, lot("West").turns());
        assertEquals(0, lot("Concourse").turns());
    }

    @Test
    void theSpotByTheArrivalStandsOnGroundEveryCopyLaysAndClearOfTheArrival() {
        HubPlan.Spot by = HubPlan.byArrival();
        Lot concourse = lot(HubLots.CONCOURSE);
        int bx = (int) Math.floor(by.x());
        int bz = (int) Math.floor(by.z());
        assertTrue(Math.abs(bx - concourse.centreX()) <= HubPlan.LANDING_REACH
                && Math.abs(bz - concourse.centreZ()) <= HubPlan.LANDING_REACH,
                "it stands on the landing, which every copy lays when nothing floors the Concourse");
        assertFalse(bx == concourse.centreX() && bz == concourse.centreZ(), "players never land inside it");
        assertEquals(HubLots.FLOOR_Y + 1.0, by.y(), 1e-9, "on the floor");
    }

    @Test
    void aDistrictStandsOnTheLotItNames() {
        HubLayout.Result result = HubLayout.assign(HubLots.all(), List.of(claim("Guild_Hall", "north", Size.LARGE)));
        assertEquals("North", result.lotOf("guild_hall").id(), "lot and district ids match without regard to case");
        assertTrue(result.problems().isEmpty());
    }

    @Test
    void aClashKeepsTheFirstByIdAndNamesTheOther() {
        HubLayout.Result result = HubLayout.assign(HubLots.all(), List.of(
                claim("Zz_Market", "East", Size.LARGE), claim("Aa_Hall", "East", Size.LARGE)));
        assertEquals("East", result.lotOf("Aa_Hall").id(), "the first by id keeps the lot");
        assertNull(result.lotOf("Zz_Market"), "the other stands nowhere");
        HubLayout.Problem problem = result.problems().get(0);
        assertEquals(HubLayout.LOT_CLASH, problem.code());
        assertEquals("Zz_Market", problem.districtId());
        assertEquals("Aa_Hall", problem.holder());
    }

    @Test
    void anOwnersMoveWinsTheLotItMovesTo() {
        HubLayout.Result result = HubLayout.assign(HubLots.all(), List.of(
                claim("Aa_Hall", "East", Size.LARGE),
                new Claim("Zz_Market", "East", Size.LARGE, false, true)));
        assertEquals("East", result.lotOf("Zz_Market").id(), "the owner's file chose it");
        assertEquals(HubLayout.LOT_CLASH, result.problems().get(0).code());
        assertEquals("Aa_Hall", result.problems().get(0).districtId());
    }

    @Test
    void anOwnersMoveToAFreeLotMovesNoOtherDistrict() {
        List<Claim> before = List.of(claim("Hall", "North", Size.LARGE), claim("Market", "East", Size.LARGE));
        List<Claim> after = List.of(new Claim("Hall", "West", Size.LARGE, false, true),
                claim("Market", "East", Size.LARGE));
        assertEquals("North", HubLayout.assign(HubLots.all(), before).lotOf("Hall").id());
        HubLayout.Result moved = HubLayout.assign(HubLots.all(), after);
        assertEquals("West", moved.lotOf("Hall").id());
        assertEquals("East", moved.lotOf("Market").id(), "the other district stays where it was");
        assertTrue(moved.problems().isEmpty());
    }

    @Test
    void anUnknownLotOrOneTooSmallLeavesTheDistrictOff() {
        HubLayout.Result result = HubLayout.assign(HubLots.all(), List.of(
                claim("Lost", "Nowhere", Size.SMALL), claim("Big", "North_East_1", Size.LARGE),
                new Claim("Adrift", null, Size.SMALL, false, false)));
        assertEquals(List.of(HubLayout.NO_LOT, HubLayout.LOT_TOO_SMALL, HubLayout.LOT_UNKNOWN),
                result.problems().stream().map(HubLayout.Problem::code).toList(), "in claim order, by id");
        assertNull(result.lotOf("Big"));
    }

    @Test
    void theScaffoldingFillsOnlyTheEmptyLotsOfItsSize() {
        HubLayout.Result result = HubLayout.assign(HubLots.all(), List.of(
                claim("Concourse_District", HubLots.CONCOURSE, Size.LARGE),
                claim("Shop", "North_East_2", Size.SMALL),
                new Claim("Scaffold_Large", null, Size.LARGE, true, false),
                new Claim("Scaffold_Small", null, Size.SMALL, true, false)));
        assertEquals(HubLots.all().size(), result.placed().size(), "every lot holds something");
        for (HubLayout.Placed placed : result.placed()) {
            String id = placed.lot().id();
            if (id.equals(HubLots.CONCOURSE)) {
                assertEquals("Concourse_District", placed.districtId());
                assertFalse(placed.placeholder());
            } else if (id.equals("North_East_2")) {
                assertEquals("Shop", placed.districtId());
            } else {
                assertTrue(placed.placeholder(), id + " is empty, so the scaffolding stands there");
                assertEquals(placed.lot().size() == Size.LARGE ? "Scaffold_Large" : "Scaffold_Small",
                        placed.districtId(), "the scaffolding of the lot's own size");
            }
        }
        assertNull(result.lotOf("Scaffold_Large"), "a placeholder is never a district's own lot");
    }

    @Test
    void theSameClaimsAlwaysGiveTheSameMap() {
        List<Claim> claims = List.of(claim("B", "East", null), claim("A", "East", null),
                new Claim("Fill", null, null, true, false));
        assertEquals(HubLayout.assign(HubLots.all(), claims), HubLayout.assign(HubLots.all(), List.of(
                claims.get(2), claims.get(0), claims.get(1))), "claim order in the store never changes the map");
    }

    @Nonnull
    private static Claim claim(@Nonnull String id, @Nullable String lot, @Nullable Size size) {
        return new Claim(id, lot, size, false, false);
    }

    @Nonnull
    private static Lot lot(@Nonnull String id) {
        Lot lot = HubLots.find(id);
        assertNotNull(lot, id);
        return lot;
    }
}
