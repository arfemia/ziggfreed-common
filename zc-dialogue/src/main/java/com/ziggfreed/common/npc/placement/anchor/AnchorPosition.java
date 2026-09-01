package com.ziggfreed.common.npc.placement.anchor;

import java.util.Locale;

import javax.annotation.Nonnull;

/**
 * One resolved place an NPC could stand: which anchor group produced it, which instance of that
 * group it is, and the position plus facing.
 *
 * <p><b>{@code anchorKey()} is the identity of a placement INSTANCE</b>, not of the placement. A
 * placement authoring both a world-spawn anchor and a structure anchor produces several of these,
 * and each one gets its own ledger row and its own NPC. That is what lets one file say "a guide at
 * spawn and one at every trade post" without inventing a second placement id.
 *
 * @param kind       which anchor group produced this position
 * @param instanceId distinguishes several positions from the SAME group (a structure's stable
 *                   prefab-instance id, a zone name, an index for a provider that returned
 *                   several). Must be stable across restarts, or the ledger row for a standing
 *                   NPC stops matching and a second one is placed beside it.
 * @param x          world X
 * @param y          world Y
 * @param z          world Z
 * @param yaw        facing in degrees
 */
public record AnchorPosition(@Nonnull AnchorKind kind, @Nonnull String instanceId,
                             double x, double y, double z, float yaw) {

    /** Which anchor group a position came from. Declaration order IS the collapse order. */
    public enum AnchorKind {
        WORLD_SPAWN("worldspawn"),
        COORDS("coords"),
        STRUCTURE("structure"),
        ZONE("zone"),
        CUSTOM("custom");

        private final String code;

        AnchorKind(@Nonnull String code) {
            this.code = code;
        }

        /** The stable code used inside an anchor key; never rename one (it is persisted). */
        @Nonnull
        public String code() {
            return code;
        }
    }

    /** A position from a group that only ever yields one, so the instance id is fixed. */
    @Nonnull
    public static AnchorPosition single(@Nonnull AnchorKind kind, double x, double y, double z, float yaw) {
        return new AnchorPosition(kind, "0", x, y, z, yaw);
    }

    /**
     * The persisted instance identity: {@code "<kind>:<instanceId>"}. This is the third component
     * of a ledger row key and the {@code AnchorKey} a placed NPC carries, so its format is a
     * stored format: changing it orphans every existing row.
     */
    @Nonnull
    public String anchorKey() {
        return kind.code() + ":" + instanceId.trim().toLowerCase(Locale.ROOT);
    }
}
