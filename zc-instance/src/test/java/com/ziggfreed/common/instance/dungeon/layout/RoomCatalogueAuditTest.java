package com.ziggfreed.common.instance.dungeon.layout;

import static com.ziggfreed.common.instance.dungeon.layout.SyntheticRooms.box;
import static com.ziggfreed.common.instance.dungeon.layout.SyntheticRooms.door;
import static com.ziggfreed.common.instance.dungeon.layout.SyntheticRooms.room;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.instance.dungeon.layout.LayoutPlan.Beat;
import com.ziggfreed.common.instance.dungeon.layout.LayoutPlan.Count;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.Severity;

/**
 * The catalogue audit flags each way a kit can keep a plan from making layouts (an empty pool, a door facing
 * nowhere, a box that does not hold its doors, a door kind no cap closes, a cap with more than one door, a need no
 * room fills, a plan role no room fills, a plan entry outside its bounds) at its tier, and a clean kit not at all.
 */
class RoomCatalogueAuditTest {

    @Test
    void aCleanKitHasNothingToReport() {
        List<Finding> findings = RoomCatalogueAudit.audit(SyntheticRooms.catalogue(),
                List.of(SyntheticRooms.plan()));
        List<Finding> problems = new ArrayList<>();
        for (Finding finding : findings) {
            if (!finding.sourceId().equals("ghost_hall")) {
                problems.add(finding);
            }
        }
        assertTrue(problems.isEmpty(), () -> "the synthetic kit is clean but for its ghost pool: " + problems);
    }

    @Test
    void eachFaultIsFlaggedAtItsTier() {
        List<RoomSpec> rooms = new ArrayList<>(clean());
        rooms.add(room("ghost", SyntheticRooms.GHOST_POOL, box(-2, 0, -2, 2, 3, 2), List.of("Combat"), null, 1, 0, 1,
                List.of(), door(0, 0, -2, Facing.NORTH, "Small")));
        rooms.add(room("blind", "Audit.Blind", box(-2, 0, -2, 2, 3, 2), List.of("Combat"), null, 1, 0, 1,
                List.of(), door(0, 0, -2, null, "Small")));
        rooms.add(room("leaky", "Audit.Leaky", box(-2, 0, -2, 2, 3, 2), List.of("Combat"), null, 1, 0, 1,
                List.of(), door(10, 0, 0, Facing.EAST, "Small")));
        rooms.add(room("inward", "Audit.Inward", box(-2, 0, -2, 2, 3, 2), List.of("Combat"), null, 1, 0, 1,
                List.of(), door(0, 0, 0, Facing.NORTH, "Small")));
        rooms.add(room("odd", "Audit.Odd", box(-2, 0, -2, 2, 3, 2), List.of("Combat"), null, 1, 0, 1,
                List.of(), door(0, 0, -2, Facing.NORTH, "Odd")));
        rooms.add(room("wide_cap", "Audit.Wide_Cap", box(-2, 0, -2, 2, 3, 2), List.of("Cap"), null, 1, 0, 1,
                List.of(), door(0, 0, -2, Facing.NORTH, "Small"), door(0, 0, 2, Facing.SOUTH, "Small")));
        rooms.add(room("riddle", "Audit.Riddle", box(-2, 0, -2, 2, 3, 2), List.of("Puzzle"), null, 1, 0, 1,
                List.of("Answer"), door(0, 0, -2, Facing.NORTH, "Small")));
        LayoutPlan plan = new LayoutPlan("Audit_Plan", List.of(SyntheticRooms.TAG), List.of(),
                List.of(new Beat("Entry", Count.ONE, -1), new Beat("Dragon", Count.ONE, -1)), null, List.of(),
                null, null, 0);
        LayoutPlan outside = new LayoutPlan("Outside_Plan", List.of(SyntheticRooms.TAG), List.of(),
                List.of(new Beat("Entry", Count.ONE, -1)), null, List.of(), new Cell(64, 32, 64),
                new Cell(32, 32, 10), 0);

        List<Finding> findings = RoomCatalogueAudit.audit(RoomCatalogue.of(rooms, SyntheticRooms.POOLS),
                List.of(plan, outside));

        assertFlagged(findings, RoomCatalogueAudit.EMPTY_POOL, "ghost", Severity.WARNING);
        assertFlagged(findings, RoomCatalogueAudit.DOOR_FACING, "blind", Severity.ERROR);
        assertFlagged(findings, RoomCatalogueAudit.BOX_DOORS, "leaky", Severity.ERROR);
        assertFlagged(findings, RoomCatalogueAudit.BOX_DOORS, "inward", Severity.ERROR);
        assertFlagged(findings, RoomCatalogueAudit.CAP_KIND, "odd", Severity.WARNING);
        assertFlagged(findings, RoomCatalogueAudit.CAP_DOORS, "wide_cap", Severity.WARNING);
        assertFlagged(findings, RoomCatalogueAudit.UNANSWERED_NEED, "riddle", Severity.WARNING);
        assertFlagged(findings, RoomCatalogueAudit.PLAN_ROLE, "audit_plan", Severity.WARNING);
        assertFlagged(findings, RoomCatalogueAudit.ENTRY_BOUNDS, "outside_plan", Severity.ERROR);
        assertEquals(9, findings.size(), () -> "each fault once, nothing else: " + findings);
    }

    /** One entry room and its cap: nothing to report. */
    @Nonnull
    private static List<RoomSpec> clean() {
        return List.of(
                room("entry", "Audit.Entry", box(-2, 0, -2, 2, 3, 2), List.of("Entry"), null, 1, 0, 1, List.of(),
                        door(0, 0, -2, Facing.NORTH, "Small")),
                room("cap", "Audit.Cap", box(-1, 0, 0, 1, 3, 0), List.of("Cap"), null, 1, 0, 1, List.of(),
                        door(0, 0, 0, Facing.NORTH, "Small")));
    }

    private static void assertFlagged(@Nonnull List<Finding> findings, @Nonnull String code, @Nonnull String source,
            @Nonnull Severity severity) {
        for (Finding finding : findings) {
            if (finding.code().equals(code) && finding.sourceId().equals(source)) {
                assertEquals(severity, finding.severity(), () -> code + " on " + source + " is a " + severity);
                assertEquals(RoomCatalogueAudit.DOMAIN, finding.domain());
                return;
            }
        }
        throw new AssertionError(code + " was not flagged on " + source + ": " + findings);
    }
}
