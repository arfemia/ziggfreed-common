package com.ziggfreed.common.progress;

import java.util.function.Consumer;
import java.util.function.Function;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.subject.Subject;

/**
 * Reads a {@link ObjectiveKindRegistry#STAT_THRESHOLD} objective's stat channel for one subject, so a
 * lifecycle engine can settle a standing-value objective ITSELF instead of waiting for a producer to
 * fire one.
 *
 * <p><b>Why an engine needs this at all.</b> Every other kind here describes a MOMENT: it happens,
 * somebody fires it, the objective moves. A threshold describes a STATE the subject is already in,
 * and the state that matters is usually reached long before the content asking about it is taken on.
 * Nothing will ever fire again to say so, so an engine that only listens would show an objective as
 * outstanding forever while the answer sat there the whole time.
 *
 * <p><b>Wiring, both halves optional.</b> A probe exists only when its engine was given a
 * {@link FactorRegistry}; without one the kind behaves exactly like every other kind in the
 * vocabulary and is purely consumer-fired. The context function is how a subject becomes the
 * question a factor provider is asked, and an engine that leaves it unset asks with an EMPTY
 * context - enough for a provider that reads only the authored argument, and not enough for the
 * portable stat provider, which needs the entity it is being asked about. So a half-wired seam
 * resolves nothing and writes nothing, which is the same observable behaviour as no seam at all.
 *
 * <p><b>Unresolvable contributes NOTHING, and nothing is never a reset.</b> A blank target, an
 * unregistered factor, a provider that cannot answer and a provider that throws all read as
 * {@code 0}, and {@code 0} applied as a high-water value is a no-op. Stored progress is therefore
 * safe from a channel that has gone missing, a mod that was uninstalled, or a read taken somewhere
 * the subject is not loaded.
 *
 * <p>Threading: the same rule the factor vocabulary has. Call it where the subject is owned
 * (typically the world thread), because the context it builds may carry live handles that are valid
 * only for the duration of the call.
 */
public final class StatThresholdProbe {

    /**
     * The factor id a threshold objective's {@code Target} is read through: the portable
     * {@code hytale:} vocabulary's stat reading, whose authored argument is the stat channel id and
     * whose answer is that channel's effective (folded) value for the subject.
     *
     * <p>A consumer may register the SAME id in its own registry with a resolution of its own (a
     * cached snapshot rather than a live read). That is the registry being per consumer working as
     * intended, and nothing here can tell the difference.
     */
    public static final String STAT_FACTOR = "hytale:stat";

    @Nonnull private final FactorRegistry factors;
    @Nonnull private final Function<Subject, FactorContext> factorContext;
    @Nonnull private final Consumer<String> warn;

    private StatThresholdProbe(@Nonnull FactorRegistry factors,
                               @Nonnull Function<Subject, FactorContext> factorContext,
                               @Nonnull Consumer<String> warn) {
        this.factors = factors;
        this.factorContext = factorContext;
        this.warn = warn;
    }

    /**
     * A probe over {@code factors}, or {@code null} when there is no vocabulary to read - which is
     * an engine's signal to skip every re-check it would otherwise run. A null context function
     * builds an empty context per subject, per the wiring note in the class javadoc.
     */
    @Nullable
    public static StatThresholdProbe of(@Nullable FactorRegistry factors,
                                        @Nullable Function<Subject, FactorContext> factorContext,
                                        @Nonnull Consumer<String> warn) {
        if (factors == null) {
            return null;
        }
        Function<Subject, FactorContext> context =
                factorContext != null ? factorContext : subject -> FactorContext.builder().build();
        return new StatThresholdProbe(factors, context, warn);
    }

    /** Is this objective one an engine re-reads a stat channel for? Matched the way kinds are. */
    public static boolean isStatThreshold(@Nonnull ObjectiveDef objective) {
        return ObjectiveKindRegistry.STAT_THRESHOLD.equalsIgnoreCase(objective.kind().trim());
    }

    /**
     * The value to apply as {@code objective}'s high-water progress right now, or {@code 0} when
     * there is nothing to apply. Never throws.
     *
     * <p>The reading is FLOORED into a whole number, because progress is counted in whole units and
     * a threshold is only genuinely reached at the number itself: a channel sitting fractionally
     * below its target must not round up into a completion.
     */
    public long valueFor(@Nonnull Subject subject, @Nonnull ObjectiveDef objective) {
        String channel = objective.target().trim();
        if (channel.isEmpty()) {
            return 0L;
        }
        FactorContext ctx;
        try {
            ctx = factorContext.apply(subject);
        } catch (Throwable t) {
            warn.accept("could not build the factor context for a threshold objective: " + t.getMessage());
            return 0L;
        }
        if (ctx == null) {
            return 0L;
        }
        Double value = factors.resolve(STAT_FACTOR, ctx.withParam(channel));
        if (value == null || value <= 0d) {
            return 0L;
        }
        return (long) Math.floor(value);
    }
}
