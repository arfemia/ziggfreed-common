package com.ziggfreed.common.progress.gate;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * ONE unmet requirement, structured: which kind of thing failed and everything the evaluator knew
 * about it - the factor id, the authored {@code Param}, both bounds and the READING it resolved
 * for a factor condition, the prerequisite quest id, the registered kind id for a {@code Custom}
 * entry, a missing permission, and the COMPOSITE kinds (an {@code AnyOf} with no open route, a
 * {@code Not} that shut the gate by passing), each carrying what its group asks for as
 * {@code children}.
 *
 * <p>{@link GateEvaluator#allRefusals} produces these, and the string tokens the older
 * {@code allFailures} API answers with are DERIVED from them through {@link #token()} - so a
 * surface that wants the rich line (the factor's own name plus the bound it asks for, an either-or
 * route list) reads the records, while every token-parsing caller keeps seeing exactly the
 * spelling it always did. The BOUNDS, the VALUE and the CHILDREN deliberately live only here and
 * never in the token: a token is an opaque id a consumer matches on, not a channel to smuggle
 * payload through.
 *
 * <p>{@code value} is the number the evaluation itself resolved when it decided the refusal -
 * what the player currently HAS - so a "(currently N)" readout costs no second lookup and can
 * never disagree with the decision. Null when the factor could not be answered at all, and on
 * every non-factor kind.
 *
 * <p>{@code children} is what a COMPOSITE group asks for, described from the authored
 * requirements rather than from an evaluation: an {@code ANY_OF} carries one child per route (a
 * route asking several things at once is one nested {@code ALL_OF} child), a {@code NOT} carries
 * the asks the subject currently satisfies. Empty on every leaf kind, and empty on a record lifted
 * from a token - the children were never in the token, exactly like the bounds.
 */
public record GateRefusal(@Nonnull Kind kind, @Nullable String factorId, @Nullable String param,
                          @Nullable Double min, @Nullable Double max, @Nullable Double value,
                          @Nullable String questId, @Nullable String customKindId,
                          @Nullable List<GateRefusal> children) {

    public GateRefusal {
        children = children == null || children.isEmpty() ? List.of() : List.copyOf(children);
    }

    /** What kind of requirement refused. */
    public enum Kind {

        /** A factor condition whose bounds were not met (or could not be evaluated). */
        FACTOR,

        /** A permission the subject does not hold. */
        PERMISSION,

        /** A prerequisite quest that is not finished. */
        QUEST,

        /** A registered requirement kind that refused (or is not registered at all). */
        CUSTOM,

        /** An {@code AnyOf} block where no route passed. */
        ANY_OF,

        /** A {@code Not} group that passed, which is what shuts the gate. */
        NOT,

        /**
         * One multi-ask route inside a composite's {@code children} - never reported at top level
         * by the walks, so it only ever renders as part of its parent's line.
         */
        ALL_OF
    }

    /** The childless {@code any_of} refusal - what a token lifts back into. */
    public static final GateRefusal ANY_OF =
            new GateRefusal(Kind.ANY_OF, null, null, null, null, null, null, null, null);

    /** The childless {@code not} refusal. */
    public static final GateRefusal NOT =
            new GateRefusal(Kind.NOT, null, null, null, null, null, null, null, null);

    /** The childless {@code all_of} refusal. */
    public static final GateRefusal ALL_OF =
            new GateRefusal(Kind.ALL_OF, null, null, null, null, null, null, null, null);

    /** The one {@code permission} refusal. */
    public static final GateRefusal PERMISSION =
            new GateRefusal(Kind.PERMISSION, null, null, null, null, null, null, null, null);

    /** An unmet factor bound, with everything the condition authored and no resolved reading. */
    @Nonnull
    public static GateRefusal factor(@Nullable String factorId, @Nullable String param,
            @Nullable Double min, @Nullable Double max) {
        return factor(factorId, param, min, max, null);
    }

    /**
     * An unmet factor bound, with everything the condition authored plus the reading the
     * evaluation resolved for it (null when the factor could not be answered).
     */
    @Nonnull
    public static GateRefusal factor(@Nullable String factorId, @Nullable String param,
            @Nullable Double min, @Nullable Double max, @Nullable Double value) {
        return new GateRefusal(Kind.FACTOR, factorId == null ? "" : factorId,
                param == null || param.isBlank() ? null : param, min, max, value, null, null, null);
    }

    /** An unfinished prerequisite quest. */
    @Nonnull
    public static GateRefusal quest(@Nonnull String questId) {
        return new GateRefusal(Kind.QUEST, null, null, null, null, null, questId, null, null);
    }

    /** A refusing (or unregistered) {@code Custom} requirement kind. */
    @Nonnull
    public static GateRefusal custom(@Nonnull String kindId) {
        return new GateRefusal(Kind.CUSTOM, null, null, null, null, null, null, kindId, null);
    }

    /**
     * An {@code AnyOf} block with no open route, carrying what each route asks for: one child per
     * route, a multi-ask route folded into one {@link Kind#ALL_OF} child so "either of these two
     * bundles" never flattens into four interchangeable alternatives.
     */
    @Nonnull
    public static GateRefusal anyOf(@Nonnull List<GateRefusal> routes) {
        return new GateRefusal(Kind.ANY_OF, null, null, null, null, null, null, null, routes);
    }

    /**
     * A {@code Not} group that passed, carrying what the group asks for - the met requirements
     * standing in the way.
     */
    @Nonnull
    public static GateRefusal not(@Nonnull List<GateRefusal> asks) {
        return new GateRefusal(Kind.NOT, null, null, null, null, null, null, null, asks);
    }

    /** One multi-ask route, as it nests inside a composite's children. */
    @Nonnull
    public static GateRefusal allOf(@Nonnull List<GateRefusal> asks) {
        return new GateRefusal(Kind.ALL_OF, null, null, null, null, null, null, null, asks);
    }

    /**
     * The opaque reason token this refusal reads as - byte-identical to what the evaluator's token
     * API has always answered: {@code factor:<id>} (with {@code @<param>} appended when one was
     * authored), {@code permission}, {@code quest:<id>}, {@code gate:<kind>}, {@code any_of},
     * {@code not}, {@code all_of}.
     */
    @Nonnull
    public String token() {
        return switch (kind) {
            case FACTOR -> param == null
                    ? GateEvaluator.REASON_FACTOR + (factorId == null ? "" : factorId)
                    : GateEvaluator.REASON_FACTOR + (factorId == null ? "" : factorId) + "@" + param;
            case PERMISSION -> GateEvaluator.REASON_PERMISSION;
            case QUEST -> GateEvaluator.REASON_QUEST + (questId == null ? "" : questId);
            case CUSTOM -> GateEvaluator.REASON_CUSTOM + (customKindId == null ? "" : customKindId);
            case ANY_OF -> GateEvaluator.REASON_ANY_OF;
            case NOT -> GateEvaluator.REASON_NOT;
            case ALL_OF -> GateEvaluator.REASON_ALL_OF;
        };
    }

    /**
     * The record a GATE token reads back as, or {@code null} for a token this vocabulary does not
     * own (an engine's own flat lifecycle token, or anything unrecognised). A {@code factor:} token
     * splits at the FIRST {@code @}, so a {@code Param} carrying its own survives; the bounds, the
     * resolved value and a composite's children are gone by construction - they were never in the
     * token - so a caller that has the records should pass them instead of round-tripping through
     * here.
     */
    @Nullable
    public static GateRefusal fromToken(@Nullable String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String t = token.trim();
        if (t.startsWith(GateEvaluator.REASON_FACTOR)) {
            String rest = t.substring(GateEvaluator.REASON_FACTOR.length());
            int at = rest.indexOf('@');
            return at < 0
                    ? factor(rest, null, null, null)
                    : factor(rest.substring(0, at), rest.substring(at + 1), null, null);
        }
        if (t.startsWith(GateEvaluator.REASON_QUEST)) {
            return quest(t.substring(GateEvaluator.REASON_QUEST.length()));
        }
        if (t.startsWith(GateEvaluator.REASON_CUSTOM)) {
            return custom(t.substring(GateEvaluator.REASON_CUSTOM.length()));
        }
        if (GateEvaluator.REASON_PERMISSION.equals(t)) {
            return PERMISSION;
        }
        if (GateEvaluator.REASON_ANY_OF.equals(t)) {
            return ANY_OF;
        }
        if (GateEvaluator.REASON_NOT.equals(t)) {
            return NOT;
        }
        if (GateEvaluator.REASON_ALL_OF.equals(t)) {
            return ALL_OF;
        }
        return null;
    }
}
