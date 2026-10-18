package com.ziggfreed.common.track.gain;

import javax.annotation.Nonnull;

import com.ziggfreed.common.track.GainCall;
import com.ziggfreed.common.track.TrackGain;

/**
 * The library's gain pipeline, installed in the {@link TrackGain} seam: a gain runs through the factors its track's
 * {@code Gain.Factors} names, in that order, each compiled once per player and track ({@link GainPlans}), so the
 * hot path is a walk over a few steps with nothing to look up. A step that stops the gain (a world paying nothing
 * on the track) ends the walk; a step that throws changes nothing, warned once. A loss (a negative ask) lands as
 * asked: the factors scale what a player earns, never what an admin or a penalty takes. What each factor did is
 * said only when the call asks for a breakdown ({@link GainCall#explaining()}).
 */
public final class GainPipeline implements TrackGain {

    public static final GainPipeline INSTANCE = new GainPipeline();

    private GainPipeline() {
    }

    @Override
    public double apply(@Nonnull GainCall call) {
        double amount = call.asked();
        if (!(amount > 0.0)) {
            return amount;
        }
        GainPlans.Plan plan = GainPlans.planFor(call);
        GainStep[] steps = plan.steps();
        String[] ids = plan.ids();
        boolean explaining = call.explaining();
        for (int i = 0; i < steps.length && amount > 0.0; i++) {
            double in = amount;
            try {
                amount = steps[i].apply(call, in);
            } catch (Throwable t) {
                GainPlans.warnOnce(ids[i], "failed on a gain", t);
                amount = in;
            }
            if (!Double.isFinite(amount)) {
                amount = in;
            }
            if (explaining) {
                call.explain(ids[i], amount / in);
            }
        }
        return Math.max(0.0, amount);
    }
}
