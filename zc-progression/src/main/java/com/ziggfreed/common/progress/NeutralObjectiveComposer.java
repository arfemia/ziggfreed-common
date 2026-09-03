package com.ziggfreed.common.progress;

import java.util.Locale;
import java.util.function.Function;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.factor.FactorNames;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.LangCatalog;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.i18n.NativeNames;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.util.NumberFormatter;

/**
 * The library's own per-kind sentence family - the words a step reads with on a server where no
 * consumer installed a richer {@link ObjectiveComposer}.
 *
 * <p><b>Convention-keyed on the kind id, never a closed switch</b>: the sentence for kind {@code K}
 * is the shipped line {@code ziggfreedcommon.progress.objective.<k>} (lower-cased), so a pack that
 * registers its own kind joins the family by shipping that one key in its own lang file, with no
 * code anywhere. The ladder per step:
 *
 * <ol>
 *   <li>the step's own authored key, resolved WITH its arguments ({@code {0}} the amount,
 *       {@code {1}} the target's name) - the author's words are never outranked by a generated
 *       sentence;</li>
 *   <li>the step's registered KIND's own authored {@link ObjectiveKind.Presentation#textKey()},
 *       when the registry knows the kind and it named one - resolved the same namespace-agnostic
 *       way as the step's own key, with the same {@code .any} twin for a targetless step (a kind
 *       file, not this class, is the schema authority for its sentence);</li>
 *   <li>{@code objective.<kind>.any} when the step targets nothing in particular;</li>
 *   <li>{@code objective.<kind>} with the amount and the target's name;</li>
 *   <li>{@code objective.default} (or its {@code .any} twin), the last shipped resort;</li>
 *   <li>null - and {@link ContentText#objective} then falls back exactly as if nothing composed.</li>
 * </ol>
 *
 * <p>A qualifier prefixes the sentence and a zone suffixes it, each through its own shipped
 * wrapper line, so "defeat ten of them, in that one place" reads as one translated sentence.
 *
 * <p><b>No target vocabulary of its own.</b> The target's name comes from the engine's shipped
 * name catalogues ({@link NativeNames#targetNameMsg}); a value-threshold step's target is a stat
 * CHANNEL, so it is asked of the factor naming assets first ({@link FactorNames}, the
 * {@code hytale:stat} overlay), which is how a channel named once for gate lines reads the same in
 * a step. Nothing here ever prints a bare asset id at a player - the last fallback is the
 * prettified id.
 */
final class NeutralObjectiveComposer implements ObjectiveComposer {

    /**
     * The instance {@link ObjectiveComposer#line} falls back to, probing the live catalogue and
     * re-reading the shared runtime's objective-kind registry on every call (never a captured
     * snapshot, so a kind registered - or a registry swapped in a test reset - after this constant
     * was built is still seen).
     */
    static final NeutralObjectiveComposer INSTANCE = new NeutralObjectiveComposer(
            LangCatalog::has, kindId -> ProgressionRuntime.objectiveKinds().kind(kindId));

    /** This library's own lang namespace plus the domain file the sentence family lives in. */
    private static final String PREFIX = "ziggfreedcommon.";
    private static final String DOMAIN = "progress.";

    /** The factor id a value-threshold step's channel name is asked of (its naming overlays). */
    private static final String STAT_FACTOR = "hytale:stat";

    private final Predicate<String> keyExists;
    private final Function<String, ObjectiveKind> kindLookup;

    /** Package-visible so a unit test can drive the ladder over a fixture catalogue and vocabulary. */
    NeutralObjectiveComposer(@Nonnull Predicate<String> keyExists,
                            @Nonnull Function<String, ObjectiveKind> kindLookup) {
        this.keyExists = keyExists;
        this.kindLookup = kindLookup;
    }

    @Override
    @Nullable
    public Message compose(@Nonnull ObjectiveDef objective, @Nullable String authoredKey) {
        String amount = NumberFormatter.grouped(objective.amount());
        boolean emptyTarget = objective.target().isEmpty();
        Message target = emptyTarget ? Msg.raw("") : targetName(objective);

        Message sentence = null;
        if (authoredKey != null && !authoredKey.isBlank() && ContentKeys.known(authoredKey.trim())) {
            sentence = ContentKeys.tr(authoredKey.trim(), amount, target);
        }
        if (sentence == null) {
            sentence = fromKindTextKey(objective, emptyTarget, amount, target);
        }
        if (sentence == null) {
            String kindKey = "objective." + objective.kind().toLowerCase(Locale.ROOT);
            if (emptyTarget && exists(kindKey + ".any")) {
                sentence = text(kindKey + ".any", amount);
            } else if (exists(kindKey)) {
                sentence = text(kindKey, amount, target);
            } else if (emptyTarget && exists("objective.default.any")) {
                sentence = text("objective.default.any", amount);
            } else if (!emptyTarget && exists("objective.default")) {
                sentence = text("objective.default", amount, target);
            }
        }
        if (sentence == null) {
            return null;
        }

        String qualifier = objective.qualifier();
        if (qualifier != null && !qualifier.isBlank() && exists("objective.qualifier")) {
            sentence = text("objective.qualifier", Msg.raw(NativeNames.prettify(qualifier)), sentence);
        }
        String zone = objective.zone();
        if (zone != null && exists("objective.zone")) {
            sentence = text("objective.zone", sentence, NativeNames.zoneNameMsg(zone));
        }
        return sentence;
    }

    /**
     * The step's registered KIND's own {@code Presentation.TextKey}, or null when the registry does
     * not know the kind or the kind named none - the rung a kind file (its schema, not this class)
     * owns. Resolved namespace-agnostically through {@link ContentKeys}, exactly like the step's own
     * authored key, with the same {@code .any} twin for a targetless step that the convention rung
     * below carries.
     */
    @Nullable
    private Message fromKindTextKey(@Nonnull ObjectiveDef objective, boolean emptyTarget,
                                    @Nonnull String amount, @Nonnull Message target) {
        ObjectiveKind kind = kindLookup.apply(objective.kind());
        String textKey = kind == null ? null : kind.presentation().textKey();
        if (textKey == null || textKey.isBlank()) {
            return null;
        }
        String trimmed = textKey.trim();
        if (emptyTarget && ContentKeys.known(trimmed + ".any")) {
            return ContentKeys.tr(trimmed + ".any", amount);
        }
        if (ContentKeys.known(trimmed)) {
            return ContentKeys.tr(trimmed, amount, target);
        }
        return null;
    }

    /**
     * What the step's target is called, as a nested client-resolved {@link Message}: the factor
     * naming assets for a value-threshold's channel, then the engine's shipped name catalogues,
     * then the prettified id.
     */
    @Nonnull
    private Message targetName(@Nonnull ObjectiveDef objective) {
        if (ObjectiveKindRegistry.STAT_THRESHOLD.equalsIgnoreCase(objective.kind())) {
            Message channel = FactorNames.name(STAT_FACTOR, objective.target());
            if (channel != null) {
                return channel;
            }
        }
        return NativeNames.targetNameMsg(objective.target(), keyExists);
    }

    private boolean exists(@Nonnull String key) {
        return keyExists.test(PREFIX + DOMAIN + key);
    }

    @Nonnull
    private static Message text(@Nonnull String key, @Nonnull Object... args) {
        return Msg.tr(PREFIX, DOMAIN + key, args);
    }
}
