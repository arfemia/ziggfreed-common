package com.ziggfreed.common.loot;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.factor.FactorConditions;
import com.ziggfreed.common.progress.gate.GateQuery;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.progress.gate.StoreGates;

/**
 * Walks a {@code Conditions} array through a {@link FactorLookup} - the lookup-driven twin of
 * {@link FactorConditions}, which walks the same array through a live registry.
 *
 * <p>The BOUND TEST itself is not written here. It is {@link FactorCondition#accepts}, the shared
 * leaf's own method, so an authored {@code Min}/{@code Max} means exactly the same thing in a loot
 * roll as everywhere else that reads the vocabulary. What this class adds is only the walk, and its
 * three rules:
 *
 * <ul>
 *   <li>nothing authored passes vacuously - no conditions is no gate;</li>
 *   <li>a HALF-AUTHORED entry (no factor id at all) is skipped rather than failing. A missing id is
 *       an authoring slip, and hiding working content behind it is far harder to diagnose than the
 *       validator finding that reports it;</li>
 *   <li>everything else fails CLOSED: an unanswerable factor and an out-of-bounds value both shut
 *       the gate, so content gated on a mod that is not installed stays gated.</li>
 * </ul>
 */
public final class FactorGate {

    private FactorGate() {
    }

    /**
     * The factor id of the FIRST entry that did not pass, or null when every entry passed (or there
     * was nothing to evaluate). A caller that wants to NAME what shut the gate uses this; one that
     * only needs the verdict uses {@link #pass}.
     */
    @Nullable
    public static String firstFailure(@Nullable FactorCondition[] conditions, @Nonnull FactorLookup lookup) {
        if (conditions == null || conditions.length == 0) {
            return null;
        }
        for (FactorCondition condition : conditions) {
            if (!passes(condition, lookup)) {
                return condition.getFactor();
            }
        }
        return null;
    }

    /** True when every entry passed - the boolean wrapper over {@link #firstFailure}. */
    public static boolean pass(@Nullable FactorCondition[] conditions, @Nonnull FactorLookup lookup) {
        return firstFailure(conditions, lookup) == null;
    }

    /**
     * Does every table gate a roll or a pool entry carries ({@link Roll#gates()}: the live {@code Requires} of
     * the table it came from, and of a contributing file) pass under {@code lookup}? None carried passes; a
     * lookup that can put no question ({@link FactorLookup#gateQuery()} null) refuses every gate, so a table
     * gated on a player pays nothing where there is no player to ask.
     */
    public static boolean passesGates(@Nullable GateSpec[] gates, @Nonnull FactorLookup lookup) {
        if (gates == null || gates.length == 0) {
            return true;
        }
        GateQuery query;
        try {
            query = lookup.gateQuery();
        } catch (Throwable t) {
            query = null;
        }
        if (query == null) {
            return false;
        }
        for (GateSpec gate : gates) {
            if (!StoreGates.passes(gate, query)) {
                return false;
            }
        }
        return true;
    }

    /** ONE entry: a null or id-less entry passes vacuously, everything else fails closed. */
    public static boolean passes(@Nullable FactorCondition condition, @Nonnull FactorLookup lookup) {
        if (condition == null || condition.isBlank()) {
            return true;
        }
        Double resolved;
        try {
            resolved = lookup.resolve(condition.getFactor(), condition.getParam());
        } catch (Throwable t) {
            resolved = null;
        }
        return condition.accepts(resolved);
    }
}
