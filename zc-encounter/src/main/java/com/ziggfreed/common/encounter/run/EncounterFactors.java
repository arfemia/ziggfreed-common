package com.ziggfreed.common.encounter.run;

import java.util.function.BiFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.factor.HytaleFactors;

/**
 * The factor vocabulary an encounter reads: the portable {@code hytale:} library (about a
 * participant, or about the subject), the run's own readings, and whatever any mod contributed
 * process-wide (a companion's region power, say), which every registry consults by id.
 *
 * <p>The run readings, each answered off the run carried in the context's payload:
 * <ul>
 *   <li>{@code ziggfreedcommon:encounter_members} - live members right now;</li>
 *   <li>{@code ziggfreedcommon:encounter_elapsed_seconds} - seconds since the engage;</li>
 *   <li>{@code ziggfreedcommon:encounter_phase_index} - phase beats so far;</li>
 *   <li>{@code ziggfreedcommon:encounter_waves} - wave beats so far;</li>
 *   <li>{@code ziggfreedcommon:encounter_deaths} - member deaths so far.</li>
 * </ul>
 * Each answers null with no run in the context, so a condition on one fails closed outside a fight.
 */
public final class EncounterFactors {

    /** The owner every registration here is filed under. */
    public static final String OWNER = "ziggfreedcommon";

    public static final String MEMBERS = "ziggfreedcommon:encounter_members";
    public static final String ELAPSED_SECONDS = "ziggfreedcommon:encounter_elapsed_seconds";
    public static final String PHASE_INDEX = "ziggfreedcommon:encounter_phase_index";
    public static final String WAVES = "ziggfreedcommon:encounter_waves";
    public static final String DEATHS = "ziggfreedcommon:encounter_deaths";

    /** What a run reading is answered from: the run plus its live member count. */
    public record RunReading(@Nonnull ZigEncounterRun run, int members, long nowMs) {
    }

    private static final FactorRegistry REGISTRY = build();

    private EncounterFactors() {
    }

    @Nonnull
    public static FactorRegistry registry() {
        return REGISTRY;
    }

    /** A context about the subject of a fight, carrying the run's reading for the run factors. */
    @Nonnull
    public static FactorContext contextFor(@Nonnull Store<EntityStore> store, @Nullable Ref<EntityStore> subjectRef,
            @Nonnull RunReading reading) {
        FactorContext.Builder ctx = FactorContext.builder().store(store).payload(reading);
        if (subjectRef != null && subjectRef.isValid()) {
            ctx.subject(subjectRef);
        }
        return ctx.build();
    }

    /** A lookup {@code (factorId, param) -> value} about {@code playerRef} (null for an absent player). */
    @Nonnull
    public static BiFunction<String, String, Double> lookupAbout(@Nullable Store<EntityStore> store,
            @Nullable Ref<EntityStore> playerRef) {
        FactorContext ctx = FactorContext.about(store, playerRef);
        return (factorId, param) -> REGISTRY.resolve(factorId, ctx.withParam(param));
    }

    /** A lookup over {@code ctx} for a live formula evaluation. */
    @Nonnull
    public static BiFunction<String, String, Double> lookupOver(@Nonnull FactorContext ctx) {
        return (factorId, param) -> REGISTRY.resolve(factorId, ctx.withParam(param));
    }

    @Nonnull
    private static FactorRegistry build() {
        FactorRegistry registry = new FactorRegistry("zc-encounter");
        HytaleFactors.registerInto(registry, OWNER);
        registry.register(MEMBERS, OWNER, ctx -> reading(ctx) == null ? null : (double) reading(ctx).members());
        registry.register(ELAPSED_SECONDS, OWNER, ctx -> {
            RunReading r = reading(ctx);
            return r == null ? null : r.run().elapsedMs(r.nowMs()) / 1000.0;
        });
        registry.register(PHASE_INDEX, OWNER, ctx -> reading(ctx) == null ? null : (double) reading(ctx).run().phaseIndex());
        registry.register(WAVES, OWNER, ctx -> reading(ctx) == null ? null : (double) reading(ctx).run().waves());
        registry.register(DEATHS, OWNER, ctx -> reading(ctx) == null ? null : (double) reading(ctx).run().memberDeaths());
        return registry;
    }

    @Nullable
    private static RunReading reading(@Nonnull FactorContext ctx) {
        return ctx.payload(RunReading.class);
    }
}
