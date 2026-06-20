package com.ziggfreed.common.instance.zone;

/**
 * How {@link ControlPointTracker} decides which team (if any) is the "dominant" team on a control point
 * for the current tick - i.e. who is allowed to make capture progress.
 *
 * <p>PURE policy enum, no engine deps; the CONSUMER picks the rule per point and passes it into
 * {@link ControlPointTracker#update}.
 */
public enum ContestRule {

    /**
     * Domination-classic: the dominant team is the ONLY team with {@code > 0} occupants. If two or more
     * teams have occupants, the point is contested (no dominant team).
     */
    ANY_PRESENCE,

    /**
     * The dominant team is the one with a STRICT maximum occupant count. A tie for the lead (no strict
     * max) leaves no dominant team, so the point is contested.
     */
    MAJORITY
}
