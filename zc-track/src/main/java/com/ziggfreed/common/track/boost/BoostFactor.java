package com.ziggfreed.common.track.boost;

import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.track.GainCall;
import com.ziggfreed.common.track.TrackStore;
import com.ziggfreed.common.track.TrackStores;
import com.ziggfreed.common.track.asset.TrackAsset;
import com.ziggfreed.common.track.gain.GainFactor;
import com.ziggfreed.common.track.gain.GainFactors;
import com.ziggfreed.common.track.gain.GainPlans;
import com.ziggfreed.common.track.gain.GainScope;
import com.ziggfreed.common.track.gain.GainStep;

/**
 * The {@value GainFactors#BOOST} factor, registered over the neutral one at setup ({@link BoostBootstrap}): every
 * boost lifting the player's track, summed and capped ({@link BoostStack}), as one step of the gain pipeline, never
 * a second pipeline. It compiles against what runs when the plan is built: the standing boosts, the server's starts
 * and the player's own (through their track store's seam), each naming the track or its category.
 *
 * <p><b>It stays right without a lookup per gain.</b> A start moves the plan's epoch ({@link Boosts#start}), and the
 * step carries the earliest end among the starts it summed: the first gain past it sums again at that moment and
 * moves the player's own epoch, so the next gain runs a plan built without the ended boost. Only a boost that can
 * turn on and off by itself (a {@code Season}, or a {@code Requires} beyond mod presence) is asked at each gain; with
 * none of those, the step is one multiplication. A track with no boost on it compiles to nothing.
 *
 * <p>The sum is the boosts' own: the {@code Bonus} beside it adds to it rather than multiplying it, because the
 * track's {@code Gain.Additive} names both (the gain pipeline runs them as one step); the cap bounds the boosts alone, and with them a prestiged climb's extra on a track whose
 * {@code Prestige.InBoostCap} counts it as one of the player's own boosts (R852).
 */
public final class BoostFactor implements GainFactor {

    public static final BoostFactor INSTANCE = new BoostFactor();

    private BoostFactor() {
    }

    /** One boost the plan sums: the boost, the percent it lifts by, whose it is, and when it ends. */
    private record Candidate(@Nonnull BoostAsset boost, double percent, @Nonnull BoostScope scope, long endsAt) {

        /** Can it turn on or off by itself between two plans (a season, a live Requires)? */
        boolean conditional() {
            return boost.season() != null || boost.liveRequires() != null;
        }
    }

    /** What lifts the track now: the candidates, the earliest end among them, and whether any is conditional. */
    private record Lifts(@Nonnull List<Candidate> candidates, long until, boolean conditional) {

        /**
         * The multiplier the candidates apply for the player at {@code ref}, the conditional ones asked now, with
         * {@code prestige} (a prestiged climb's extra counted inside the cap, 0 for none) as one more of the player's
         * own lifts.
         */
        double multiplier(@Nullable Ref<EntityStore> ref, double prestige) {
            List<BoostStack.Lift> lifts = new ArrayList<>(candidates.size() + 1);
            for (Candidate candidate : candidates) {
                if (!candidate.conditional() || (candidate.boost().isPresent()
                        && Boosts.passes(candidate.boost().liveRequires(), ref))) {
                    lifts.add(new BoostStack.Lift(candidate.percent(), candidate.scope()));
                }
            }
            if (prestige != 0.0) {
                lifts.add(new BoostStack.Lift(prestige, BoostScope.PERSONAL));
            }
            BoostSettingsAsset settings = BoostSettingsConfig.getInstance().settings();
            return BoostStack.multiplier(BoostStack.percent(lifts, settings.cap(), settings.highestOnly()));
        }
    }

    @Nonnull
    @Override
    public GainStep compile(@Nonnull GainScope scope) {
        Lifts lifts = collect(scope, System.currentTimeMillis());
        double prestige = prestigePercent(scope);
        if (lifts.candidates().isEmpty() && prestige == 0.0) {
            return GainStep.NONE;
        }
        if (lifts.conditional()) {
            return (call, amount) -> amount * live(scope, lifts, call, prestige);
        }
        double fixed = lifts.multiplier(null, 0.0);
        double prestiged = prestige == 0.0 ? fixed : lifts.multiplier(null, prestige);
        if (fixed == 1.0 && prestiged == 1.0 && lifts.until() == Long.MAX_VALUE) {
            return GainStep.NONE;
        }
        return (call, amount) -> System.currentTimeMillis() < lifts.until()
                ? amount * (call.before().prestige() > 0 ? prestiged : fixed)
                : amount * live(scope, lifts, call, prestige);
    }

    /**
     * The prestige extra {@code scope}'s track counts inside the boosts' cap ({@code Prestige.InBoostCap}, R852): its
     * {@code Prestige.GainPercent} on a track that prestiges and says so, else 0 (the {@code Prestige} factor pays it
     * on its own, or there is none). Paid only once the climb has prestiged, read off the gain's own record.
     */
    private static double prestigePercent(@Nonnull GainScope scope) {
        TrackAsset track = scope.def().asset();
        return track.prestiges() && track.prestigeInBoostCap() ? track.prestigeGainPercent() : 0.0;
    }

    /**
     * The multiplier now: the compiled lifts while none of them has ended, else summed again at this moment, the
     * player's plan moved so the next gain builds without what ended; {@code prestige} counted when the gain's
     * climb has prestiged.
     */
    private static double live(@Nonnull GainScope scope, @Nonnull Lifts compiled, @Nonnull GainCall call,
            double prestige) {
        double own = call.before().prestige() > 0 ? prestige : 0.0;
        long now = System.currentTimeMillis();
        if (now < compiled.until()) {
            return compiled.multiplier(call.ref(), own);
        }
        GainPlans.invalidate(call.playerId());
        return collect(scope, now).multiplier(call.ref(), own);
    }

    /** Every boost lifting {@code scope}'s track at {@code nowMs}. */
    @Nonnull
    private static Lifts collect(@Nonnull GainScope scope, long nowMs) {
        String trackId = scope.trackId();
        String categoryId = scope.categoryId();
        List<Candidate> candidates = new ArrayList<>();
        // A standing boost lifts every player its Requires lets in, so it counts as the server's (the bucket
        // HighestOnly keeps), whatever its Scope says.
        for (BoostAsset boost : BoostConfig.getInstance().standing()) {
            if (boost.lifts(trackId, categoryId)) {
                candidates.add(new Candidate(boost, boost.percent(), BoostScope.SERVER, Long.MAX_VALUE));
            }
        }
        for (BoostRun run : ServerBoosts.shared().live(nowMs)) {
            addRun(candidates, run, BoostScope.SERVER, trackId, categoryId);
        }
        TrackStore store = scope.playerId() == null ? null : TrackStores.forKey(scope.key());
        HeldBoosts held = store == null ? null : store.boosts(scope.key().subject());
        if (held != null) {
            for (BoostRun run : held.runs()) {
                if (run.liveAt(nowMs)) {
                    addRun(candidates, run, BoostScope.PERSONAL, trackId, categoryId);
                }
            }
        }
        long until = Long.MAX_VALUE;
        boolean conditional = false;
        for (Candidate candidate : candidates) {
            until = Math.min(until, candidate.endsAt());
            conditional |= candidate.conditional();
        }
        return new Lifts(List.copyOf(candidates), until, conditional);
    }

    private static void addRun(@Nonnull List<Candidate> into, @Nonnull BoostRun run, @Nonnull BoostScope scope,
            @Nonnull String trackId, @Nullable String categoryId) {
        BoostAsset boost = BoostConfig.getInstance().live(run.boost());
        if (boost != null && !boost.isStanding() && boost.lifts(trackId, categoryId)) {
            into.add(new Candidate(boost, run.percent(), scope, run.endsAt()));
        }
    }
}
