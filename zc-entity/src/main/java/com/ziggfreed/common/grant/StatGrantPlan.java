package com.ziggfreed.common.grant;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.annotation.Nonnull;

/**
 * The pure core of {@link StatGrantWriter}: what to put and what to remove so that an entity's modifiers under one
 * owner become exactly the wanted ones. An amount already there is left alone, a wanted amount of 0 is the same as
 * not wanted, and anything there that is not wanted goes. Planning against what a plan left behind plans nothing,
 * which is what makes a rebuild idempotent.
 */
public final class StatGrantPlan {

    /** One modifier's place: the stat's index on the entity's stat map, and its key. */
    public record Target(int stat, @Nonnull String key) {

        public Target {
            Objects.requireNonNull(key, "key");
        }
    }

    /** The writes a plan asks for: amounts to put, and places to clear. */
    public record Plan(@Nonnull Map<Target, Float> puts, @Nonnull Set<Target> removes) {

        public Plan {
            puts = Map.copyOf(puts);
            removes = Set.copyOf(removes);
        }

        /** Does the plan change nothing? */
        public boolean isEmpty() {
            return puts.isEmpty() && removes.isEmpty();
        }
    }

    private StatGrantPlan() {
    }

    /**
     * The plan turning {@code existing} (each owned modifier there now, by place, its flat amount; NaN for one of
     * another shape, which is always replaced) into {@code wanted}.
     */
    @Nonnull
    public static Plan plan(@Nonnull Map<Target, Float> existing, @Nonnull Map<Target, Float> wanted) {
        Map<Target, Float> puts = new LinkedHashMap<>();
        Set<Target> removes = new HashSet<>();
        for (Map.Entry<Target, Float> want : wanted.entrySet()) {
            float amount = want.getValue() == null ? 0f : want.getValue();
            if (amount == 0f || !Float.isFinite(amount)) {
                continue;
            }
            Float now = existing.get(want.getKey());
            if (now == null || Float.compare(now, amount) != 0) {
                puts.put(want.getKey(), amount);
            }
        }
        for (Target there : existing.keySet()) {
            Float want = wanted.get(there);
            if (want == null || want == 0f || !Float.isFinite(want)) {
                removes.add(there);
            }
        }
        return new Plan(puts, removes);
    }

    /** {@code existing} after {@code plan}: what the entity holds once the plan is written (pure, for a test). */
    @Nonnull
    public static Map<Target, Float> applied(@Nonnull Map<Target, Float> existing, @Nonnull Plan plan) {
        Map<Target, Float> out = new LinkedHashMap<>(existing);
        for (Target gone : plan.removes()) {
            out.remove(gone);
        }
        out.putAll(plan.puts());
        return out;
    }
}
