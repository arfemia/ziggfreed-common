package com.ziggfreed.common.loot;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorRegistry;

/**
 * One batch's worth of factor readings, each resolved AT MOST ONCE.
 *
 * <p>A single moment usually evaluates several rolls, and those rolls reference the same factors
 * over and over - a chance and a ladder both reading the player's luck, three rolls all gated on the
 * same tool quality. Without a snapshot each of those is a fresh provider call, and worse, two of
 * them can disagree: a provider reading a live value could answer 4 for the chance and 5 for the
 * ladder in the same instant, and the roll would be evaluated against a state that never existed.
 *
 * <p>So one snapshot covers one batch: build it, evaluate everything against it, discard it. Never
 * hold one across moments - the context it wraps is itself a point-in-time handle (a live store and
 * entity reference are valid only for the call that supplied them).
 */
public final class FactorSnapshot implements FactorLookup {

    @Nonnull private final FactorRegistry registry;
    @Nonnull private final FactorContext ctx;
    @Nonnull private final Map<String, Double> cache = new HashMap<>();

    public FactorSnapshot(@Nonnull FactorRegistry registry, @Nonnull FactorContext ctx) {
        this.registry = registry;
        this.ctx = ctx;
    }

    /** The context every reading is resolved against, re-scoped per term to that term's own param. */
    @Nonnull
    public FactorContext context() {
        return ctx;
    }

    @Override
    @Nullable
    public Double resolve(@Nonnull String factorId, @Nullable String param) {
        if (factorId.isBlank()) {
            return null;
        }
        String key = factorId.toLowerCase(Locale.ROOT) + "#" + (param == null ? "" : param);
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        Double value = registry.resolve(factorId, ctx.withParam(param));
        cache.put(key, value);
        return value;
    }

    /** How many distinct readings this batch has taken, for a diagnostic that wants the number. */
    public int size() {
        return cache.size();
    }
}
