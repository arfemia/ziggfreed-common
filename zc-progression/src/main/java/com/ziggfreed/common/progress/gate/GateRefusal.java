package com.ziggfreed.common.progress.gate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * ONE unmet requirement, structured: which kind of thing failed and everything the evaluator knew
 * about it - the factor id, the authored {@code Param} and both bounds for a factor condition, the
 * prerequisite quest id, the registered kind id for a {@code Custom} entry, and the flat
 * payload-less kinds (a missing permission, an {@code AnyOf} with no open route, a {@code Not}
 * that shut the gate by passing).
 *
 * <p>{@link GateEvaluator#allRefusals} produces these, and the string tokens the older
 * {@code allFailures} API answers with are DERIVED from them through {@link #token()} - so a
 * surface that wants the rich line (the factor's own name plus the bound it asks for) reads the
 * records, while every token-parsing caller keeps seeing exactly the spelling it always did. The
 * BOUNDS deliberately live only here and never in the token: a token is an opaque id a consumer
 * matches on, not a channel to smuggle numbers through.
 */
public record GateRefusal(@Nonnull Kind kind, @Nullable String factorId, @Nullable String param,
                          @Nullable Double min, @Nullable Double max, @Nullable String questId,
                          @Nullable String customKindId) {

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
        NOT
    }

    /** The one {@code any_of} refusal - payload-less, so one instance serves every block. */
    public static final GateRefusal ANY_OF = new GateRefusal(Kind.ANY_OF, null, null, null, null, null, null);

    /** The one {@code not} refusal. */
    public static final GateRefusal NOT = new GateRefusal(Kind.NOT, null, null, null, null, null, null);

    /** The one {@code permission} refusal. */
    public static final GateRefusal PERMISSION = new GateRefusal(Kind.PERMISSION, null, null, null, null, null, null);

    /** An unmet factor bound, with everything the condition authored. */
    @Nonnull
    public static GateRefusal factor(@Nullable String factorId, @Nullable String param,
            @Nullable Double min, @Nullable Double max) {
        return new GateRefusal(Kind.FACTOR, factorId == null ? "" : factorId,
                param == null || param.isBlank() ? null : param, min, max, null, null);
    }

    /** An unfinished prerequisite quest. */
    @Nonnull
    public static GateRefusal quest(@Nonnull String questId) {
        return new GateRefusal(Kind.QUEST, null, null, null, null, questId, null);
    }

    /** A refusing (or unregistered) {@code Custom} requirement kind. */
    @Nonnull
    public static GateRefusal custom(@Nonnull String kindId) {
        return new GateRefusal(Kind.CUSTOM, null, null, null, null, null, kindId);
    }

    /**
     * The opaque reason token this refusal reads as - byte-identical to what the evaluator's token
     * API has always answered: {@code factor:<id>} (with {@code @<param>} appended when one was
     * authored), {@code permission}, {@code quest:<id>}, {@code gate:<kind>}, {@code any_of},
     * {@code not}.
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
        };
    }

    /**
     * The record a GATE token reads back as, or {@code null} for a token this vocabulary does not
     * own (an engine's own flat lifecycle token, or anything unrecognised). A {@code factor:} token
     * splits at the FIRST {@code @}, so a {@code Param} carrying its own survives; the bounds are
     * gone by construction - they were never in the token - so a caller that has the records should
     * pass them instead of round-tripping through here.
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
        return null;
    }
}
