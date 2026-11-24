package com.ziggfreed.common.progress;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.progress.gate.GateKindRegistry;
import com.ziggfreed.common.progress.gate.GateQuery;
import com.ziggfreed.common.progress.gate.GateRuntime;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.progress.gate.GateValidator;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.validation.Finding;

/**
 * A step's own condition, its {@code While} block ({@link ObjectiveDef#countsWhile()}): the ONE place every
 * surface crediting an authored objective asks whether a credit may land, so a quest step, an achievement
 * criterion and a native objective's task read one block the one way.
 *
 * <pre>{@code
 * "defeat_foes": { "Kind": "KILL_ENTITY", "Amount": 30,
 *                  "While": { "Factors": [ { "Factor": "yourmod:rank", "Param": "guild", "Min": 1 } ] } }
 * }</pre>
 *
 * <p>The block is the shared {@code Requires} shape, answered by the server's one evaluator
 * ({@link GateRuntime#evaluator()}) through the block's compiled form, so a reading whose Factors file declares
 * {@code Player} or {@code Server} volatility keeps its verdict between moments and a moment costs one look-up.
 * A step that writes no {@code While} asks nothing: {@link #holds} reads one field and answers yes.
 *
 * <p>A refusal is not a loss: the moment still reaches every other step, every other piece of content and every
 * listener exactly as before; only this step's credit is skipped, and the next moment that comes while the block
 * holds counts as usual.
 */
public final class ObjectiveWhile {

    private ObjectiveWhile() {
    }

    /**
     * May a credit land on {@code step} for {@code subject} right now? True when the step writes no condition
     * (and nothing is asked); otherwise the block's answer for the subject, and false with nobody to ask. Call it
     * where the subject is owned (the world thread), as any factor reading asks.
     */
    public static boolean holds(@Nullable Subject subject, @Nonnull ObjectiveDef step) {
        GateSpec condition = step.countsWhile();
        if (condition == null) {
            return true;
        }
        return subject != null && GateRuntime.evaluator().check(condition, GateQuery.of(subject)).passed();
    }

    /**
     * What the shared gate audit ({@link GateValidator}) says of {@code step}'s condition, each finding under its
     * own code with its message prefixed by {@code where} (such as {@code "step 'defeat_foes'"}), so a condition
     * that can never hold or names a factor nothing answers is reported where it was written. Empty for a step
     * writing none. Unknown factors are asked of the server's vocabulary ({@link GateRuntime#vocabulary()}) and
     * the Factors files.
     *
     * @param noun         what a message calls the gated thing, the step itself ({@code "step"}, {@code "criterion"}),
     *                     since a condition that fails closed leaves the step uncountable, never its content locked
     * @param knownContent answers "is this a quest in the same pool?" for a {@code Quests} leaf, or null to skip
     */
    @Nonnull
    public static List<Finding> findings(@Nonnull ObjectiveDef step, @Nonnull String where, @Nonnull String domain,
            @Nonnull String sourceId, @Nonnull String noun, @Nullable GateKindRegistry gateKinds,
            @Nullable Predicate<String> knownContent) {
        GateSpec condition = step.countsWhile();
        if (condition == null) {
            return List.of();
        }
        List<Finding> out = new ArrayList<>();
        for (Finding finding : GateValidator.validate(condition, domain, sourceId, noun, gateKinds,
                GateRuntime.vocabulary()::isRegistered, knownContent)) {
            out.add(new Finding(finding.severity(), finding.code(), where + " While: " + finding.message(),
                    finding.sourceId(), finding.domain()));
        }
        return out;
    }
}
