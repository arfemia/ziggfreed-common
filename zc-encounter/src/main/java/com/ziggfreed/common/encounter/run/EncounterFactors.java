package com.ziggfreed.common.encounter.run;

import java.util.function.BiFunction;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.encounter.wave.WaveRules;
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
 *   <li>{@code ziggfreedcommon:encounter_deaths} - member deaths so far;</li>
 *   <li>{@code ziggfreedcommon:encounter_waves_cleared} - wave clears ({@code zc:cleared}) so far.</li>
 * </ul>
 * Each answers null with no run in the context, so a condition on one fails closed outside a fight.
 *
 * <p>The endless readings, for a fight standing in an endless wave run ({@link EncounterWaves}); asking any of them
 * is what starts the fight asking for its run's rules, and each answers null until they come, and for a fight in no
 * endless run:
 * <ul>
 *   <li>{@code ziggfreedcommon:encounter_endless} - 1 while the run takes waves, 0 once it has ended;</li>
 *   <li>{@code ziggfreedcommon:encounter_wave} - the wave standing now, the next to rise during a breather;</li>
 *   <li>{@code ziggfreedcommon:encounter_wave_band} - the band slot (1 to 4) that wave raises, 0 on a boss wave;</li>
 *   <li>{@code ziggfreedcommon:encounter_wave_size} - the size (1 to 3) it rises at;</li>
 *   <li>{@code ziggfreedcommon:encounter_wave_boss} - 1 on a boss wave, else 0;</li>
 *   <li>{@code ziggfreedcommon:encounter_wave_return} - a boss wave's return (1 for the first), 0 on a mob wave.</li>
 * </ul>
 * The wave readings answer null once the run has ended, as {@code encounter_endless} reads 0.
 */
public final class EncounterFactors {

    /** The owner every registration here is filed under. */
    public static final String OWNER = "ziggfreedcommon";

    public static final String MEMBERS = "ziggfreedcommon:encounter_members";
    public static final String ELAPSED_SECONDS = "ziggfreedcommon:encounter_elapsed_seconds";
    public static final String PHASE_INDEX = "ziggfreedcommon:encounter_phase_index";
    public static final String WAVES = "ziggfreedcommon:encounter_waves";
    public static final String DEATHS = "ziggfreedcommon:encounter_deaths";
    public static final String WAVES_CLEARED = "ziggfreedcommon:encounter_waves_cleared";
    public static final String ENDLESS = "ziggfreedcommon:encounter_endless";
    public static final String WAVE = "ziggfreedcommon:encounter_wave";
    public static final String WAVE_BAND = "ziggfreedcommon:encounter_wave_band";
    public static final String WAVE_SIZE = "ziggfreedcommon:encounter_wave_size";
    public static final String WAVE_BOSS = "ziggfreedcommon:encounter_wave_boss";
    public static final String WAVE_RETURN = "ziggfreedcommon:encounter_wave_return";

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
        registry.register(WAVES_CLEARED, OWNER,
                ctx -> reading(ctx) == null ? null : (double) reading(ctx).run().wavesCleared());
        registry.register(ENDLESS, OWNER, ctx -> {
            ZigEncounterRun run = endlessRun(ctx);
            if (run == null) {
                return null;
            }
            if (run.isEndlessHalted()) {
                return 0.0;
            }
            return run.endless() == null ? null : 1.0;
        });
        registry.register(WAVE, OWNER, ctx -> wave(ctx, wave -> (double) wave.number()));
        registry.register(WAVE_BAND, OWNER, ctx -> wave(ctx, wave -> (double) wave.slot()));
        registry.register(WAVE_SIZE, OWNER, ctx -> wave(ctx, wave -> (double) wave.size()));
        registry.register(WAVE_BOSS, OWNER, ctx -> wave(ctx, wave -> wave.boss() ? 1.0 : 0.0));
        registry.register(WAVE_RETURN, OWNER, ctx -> wave(ctx, wave -> (double) wave.returns()));
        return registry;
    }

    /** The run in {@code ctx}, noted as wanting its endless rules; null with no run. */
    @Nullable
    private static ZigEncounterRun endlessRun(@Nonnull FactorContext ctx) {
        RunReading r = reading(ctx);
        if (r == null) {
            return null;
        }
        r.run().wantEndless();
        return r.run();
    }

    /** {@code read} of the wave standing in the run in {@code ctx}, or null when it runs no endless waves now. */
    @Nullable
    private static Double wave(@Nonnull FactorContext ctx, @Nonnull Function<WaveRules.Wave, Double> read) {
        ZigEncounterRun run = endlessRun(ctx);
        WaveRules.Wave wave = run == null ? null : EncounterWaves.standing(run);
        return wave == null ? null : read.apply(wave);
    }

    @Nullable
    private static RunReading reading(@Nonnull FactorContext ctx) {
        return ctx.payload(RunReading.class);
    }
}
