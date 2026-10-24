package com.ziggfreed.common.instance.dungeon.layout;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;

import com.ziggfreed.common.instance.dungeon.layout.LayoutPlan.Beat;
import com.ziggfreed.common.instance.dungeon.layout.LayoutPlan.Branch;
import com.ziggfreed.common.validation.Finding;

/**
 * What can keep a room catalogue and its plans from making layouts, found before a run needs one. Pure: it reads
 * the catalogue (each pool already counted through its {@link PoolIndex}) and the plans, and returns findings.
 *
 * <ul>
 *   <li>{@code EMPTY_POOL} (WARNING): a room whose prefab or pool names nothing a loaded pack ships; it is never
 *       placed. A pack that ships it may simply not be loaded.</li>
 *   <li>{@code DOOR_FACING} (ERROR): a door with no known {@code Facing}; it never joins or takes a cap.</li>
 *   <li>{@code BOX_DOORS} (ERROR): a door whose cell lies outside the room's {@code Box}, or whose next cell out lies
 *       inside it; a room joined there would overlap this one, so the door never joins.</li>
 *   <li>{@code CAP_KIND} (WARNING): a door kind no usable cap closes. A room is placed by joining through one of its
 *       doors, and every other door must have room for a cap, so a room with one such door is placed only by
 *       joining through that door, never as the entry, and a room with two or more is never placed.</li>
 *   <li>{@code CAP_DOORS} (WARNING): a room with role {@code Cap} without exactly one door with a facing; it never
 *       serves as a cap.</li>
 *   <li>{@code UNANSWERED_NEED} (WARNING): a room {@code Needs} a role no usable room carries; it is never placed.</li>
 *   <li>{@code PLAN_ROLE} (WARNING): a plan names a role (a beat, its connectors, a branch or its gate) no usable room
 *       carrying the plan's tags fills.</li>
 *   <li>{@code ENTRY_BOUNDS} (ERROR): a plan's {@code Bounds.Entry} lies outside its {@code Bounds}; it makes no
 *       layout.</li>
 * </ul>
 *
 * <p>A reference another pack may fill is a WARNING, per the library's rule; a file contradicting itself is an
 * ERROR.
 */
public final class RoomCatalogueAudit {

    /** The domain every finding is filed under. */
    public static final String DOMAIN = "dungeon";

    public static final String EMPTY_POOL = "EMPTY_POOL";
    public static final String DOOR_FACING = "DOOR_FACING";
    public static final String BOX_DOORS = "BOX_DOORS";
    public static final String CAP_KIND = "CAP_KIND";
    public static final String CAP_DOORS = "CAP_DOORS";
    public static final String UNANSWERED_NEED = "UNANSWERED_NEED";
    public static final String PLAN_ROLE = "PLAN_ROLE";
    public static final String ENTRY_BOUNDS = "ENTRY_BOUNDS";

    private RoomCatalogueAudit() {
    }

    /** Every finding for {@code catalogue} and {@code plans}, rooms first in id order, then plans as given. */
    @Nonnull
    public static List<Finding> audit(@Nonnull RoomCatalogue catalogue, @Nonnull Collection<LayoutPlan> plans) {
        Set<String> capKinds = new HashSet<>();
        Set<String> roles = new HashSet<>();
        for (RoomSpec room : catalogue.rooms()) {
            if (!catalogue.usable(room)) {
                continue;
            }
            roles.addAll(room.roles());
            if (room.isCap()) {
                for (DoorSpec door : room.doors()) {
                    if (door.usable()) {
                        capKinds.add(door.kind());
                    }
                }
            }
        }
        List<Finding> out = new ArrayList<>();
        for (RoomSpec room : catalogue.rooms()) {
            auditRoom(catalogue, room, capKinds, roles, out);
        }
        for (LayoutPlan plan : plans) {
            if (plan != null) {
                auditPlan(catalogue, plan, out);
            }
        }
        return out;
    }

    private static void auditRoom(@Nonnull RoomCatalogue catalogue, @Nonnull RoomSpec room,
            @Nonnull Set<String> capKinds, @Nonnull Set<String> roles, @Nonnull List<Finding> out) {
        String id = room.id();
        if (catalogue.variants(id) == 0) {
            out.add(Finding.warning(DOMAIN, EMPTY_POOL, "Prefab '" + room.prefab() + "' names no prefab a loaded "
                    + "pack ships under Server/Prefabs/, so the room is never placed.", id));
        }
        Set<String> uncapped = new LinkedHashSet<>();
        int uncappedDoors = 0;
        for (int i = 0; i < room.doors().size(); i++) {
            DoorSpec door = room.doors().get(i);
            if (door.facing() == null) {
                out.add(Finding.error(DOMAIN, DOOR_FACING, "Door " + i + " names no Facing (North, East, South or "
                        + "West), so it never joins or takes a cap.", id));
            }
            boolean inside = room.box().contains(door.at());
            boolean opensOut = door.facing() == null || !room.box().contains(door.at().step(door.facing()));
            if (!inside || !opensOut) {
                out.add(Finding.error(DOMAIN, BOX_DOORS, "Door " + i + " at " + cell(door.at()) + " does not sit on "
                        + "the inside edge of the Box " + cell(room.box().min()) + " to " + cell(room.box().max())
                        + ", so a room joined there would overlap this one and the door never joins.", id));
            }
            if (door.usable() && !room.isCap() && !capKinds.contains(door.kind())) {
                uncapped.add(door.kind());
                uncappedDoors++;
            }
        }
        String effect = uncappedDoors == 1
                ? "the room can be placed only by joining through that one door, never as the entry"
                : "the room is never placed: it joins through one door and every other door needs room for a cap, "
                        + "but " + uncappedDoors + " of its doors have no cap";
        for (String kind : uncapped) {
            out.add(Finding.warning(DOMAIN, CAP_KIND, "No usable Cap room closes a '" + kind + "' door, so " + effect
                    + ".", id));
        }
        if (room.hasRole(RoomSpec.CAP_ROLE) && room.usableDoorCount() != 1) {
            out.add(Finding.warning(DOMAIN, CAP_DOORS, "A Cap room needs exactly one door with a Facing; this one has "
                    + room.usableDoorCount() + ", so it never closes a door.", id));
        }
        for (String need : room.needs()) {
            if (!roles.contains(need)) {
                out.add(Finding.warning(DOMAIN, UNANSWERED_NEED, "Needs role '" + need + "', which no usable room "
                        + "carries, so the room is never placed.", id));
            }
        }
    }

    private static void auditPlan(@Nonnull RoomCatalogue catalogue, @Nonnull LayoutPlan plan,
            @Nonnull List<Finding> out) {
        if (!plan.entryInBounds()) {
            out.add(Finding.error(DOMAIN, ENTRY_BOUNDS, "Bounds.Entry " + cell(plan.entryCell()) + " lies outside the "
                    + "Bounds " + cell(Cell.ZERO) + " to " + cell(Box.ofSize(plan.bounds()).max())
                    + ", so the plan makes no layout.", plan.id()));
        }
        Set<String> roles = new HashSet<>();
        for (RoomSpec room : catalogue.rooms()) {
            if (catalogue.usable(room) && room.carriesAll(plan.roomTags())) {
                roles.addAll(room.roles());
            }
        }
        Set<String> named = new LinkedHashSet<>();
        for (Beat beat : plan.path()) {
            named.add(beat.role());
        }
        if (plan.links() != null) {
            named.add(plan.links().role());
        }
        for (Branch branch : plan.branches()) {
            named.add(branch.role());
            if (branch.gate() != null) {
                named.add(branch.gate());
            }
        }
        for (String role : named) {
            if (!role.isEmpty() && !roles.contains(role)) {
                out.add(Finding.warning(DOMAIN, PLAN_ROLE, "Names role '" + role + "', which no usable room carrying "
                        + "the plan's tags " + plan.roomTags() + " fills.", plan.id()));
            }
        }
    }

    @Nonnull
    private static String cell(@Nonnull Cell cell) {
        return "(" + cell.x() + ", " + cell.y() + ", " + cell.z() + ")";
    }
}
