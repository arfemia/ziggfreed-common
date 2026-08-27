package com.ziggfreed.common.progress;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * How one authored objective reads as a SENTENCE, for the step that wrote no line of its own -
 * the seam every shared fold stamps into its {@link ContentText}, so a step renders as real words
 * on every surface over the shared runtime (a board's detail panel, the objective book, a
 * character's offer page, the tracked panel, a command's listing) instead of as a placeholder.
 *
 * <p><b>One consumer-installable slot over a neutral default.</b> This library ships its own
 * per-kind sentence family ({@link NeutralObjectiveComposer}, keyed by convention on the kind id so
 * a pack-added kind joins by shipping a key), and a consumer whose content deserves richer wording
 * {@linkplain #install installs} its own composer ONCE at startup - the installed one is asked
 * first and the neutral family answers whatever it declines. The same pattern as the shipped
 * feedback-moment defaults: the library reads correctly bare, a consumer replaces the words.
 *
 * <p><b>Every call is guarded.</b> An installed composer that throws costs nothing but its own
 * fancier wording - the neutral family takes over for that line - and a neutral miss (no key
 * shipped for the kind) answers null, which lets {@link ContentText#objective} fall back to the
 * step's own authored key exactly as if nothing had composed. The fold stamps
 * {@link #line(ObjectiveDef, String)} as a SUPPLIER, because a composer is installed during a
 * consumer's startup, possibly after the catalogue folds.
 */
@FunctionalInterface
public interface ObjectiveComposer {

    /**
     * The sentence for one objective, or null when this composer has nothing to say about it.
     *
     * @param objective   the authored step (kind, target, qualifier, zone, amount, match mode)
     * @param authoredKey the step's own localization key when the file wrote one - the composer
     *                    resolves it itself, with whatever arguments its slots need, because a key
     *                    resolved bare paints its numbered slots literally
     */
    @Nullable
    Message compose(@Nonnull ObjectiveDef objective, @Nullable String authoredKey);

    /** Is a consumer's composer behind the seam yet? */
    static boolean isInstalled() {
        return Holder.INSTALLED != null;
    }

    /** Install the consumer's composer. Call once from setup; last install wins. */
    static void install(@Nonnull ObjectiveComposer composer) {
        Holder.INSTALLED = composer;
    }

    /**
     * The line one step reads with: the installed composer's answer when there is one, else the
     * neutral family's, else null. This is what a fold stamps (lazily) per step, and both rungs are
     * guarded so no composer failure can cost more than this one line.
     */
    @Nullable
    static Message line(@Nonnull ObjectiveDef objective, @Nullable String authoredKey) {
        ObjectiveComposer installed = Holder.INSTALLED;
        if (installed != null) {
            try {
                Message composed = installed.compose(objective, authoredKey);
                if (composed != null) {
                    return composed;
                }
            } catch (Throwable costsOnlyThisLine) {
                // The neutral family below still answers for the step.
            }
        }
        try {
            return NeutralObjectiveComposer.INSTANCE.compose(objective, authoredKey);
        } catch (Throwable costsOnlyThisLine) {
            return null;
        }
    }

    /** The one-slot holder, out of line so the interface itself stays a lambda target. */
    final class Holder {

        @Nullable
        static volatile ObjectiveComposer INSTALLED;

        private Holder() {
        }
    }
}
