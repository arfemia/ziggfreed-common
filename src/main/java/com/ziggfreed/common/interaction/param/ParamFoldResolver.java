package com.ziggfreed.common.interaction.param;

import javax.annotation.Nonnull;

/**
 * The consumer-supplied fold: given a fire-time {@link ParamFoldRequest} (caster + declared
 * parameter key + authored base), return the per-caster value for that key. Common knows
 * NOTHING about the consumer's modifier semantics (mastery, gear, ADD_STEP, OVERRIDE/CONDITIONAL
 * shapes, ...) - it only asks.
 *
 * <p>Contract: return a FINITE double. Return {@link ParamFoldRequest#base()} for an unknown
 * key. Never throw (a throw is caught by {@link ParamFold} and degraded to base, but a throwing
 * resolver is a bug in the consumer's fold, not expected behavior).
 *
 * <p>Called on the world thread inside a firing chain: keep it allocation-light and
 * side-effect-free.
 */
@FunctionalInterface
public interface ParamFoldResolver {

    /**
     * Resolve the per-caster value for {@code request}.
     *
     * @param request the fold question; never null when invoked through {@link ParamFold}.
     * @return the resolved value - ideally a finite double, or {@code request.base()} for an
     *         unrecognized key.
     */
    double fold(@Nonnull ParamFoldRequest request);
}
