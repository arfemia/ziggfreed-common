package com.ziggfreed.common.quest.asset;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.progress.gate.GateEvaluator;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestGates;
import com.ziggfreed.common.quest.QuestLifecycle;
import com.ziggfreed.common.subject.Subject;

/**
 * The bridge that makes a quest's authored {@code Requires} block the engine's gate: hand one to
 * {@code QuestEngine.Builder#gates} and every accept and every visibility check consults what the
 * content actually says.
 *
 * <pre>{@code
 * AssetQuestGates gates = AssetQuestGates.of(evaluator);
 * QuestEngine engine = QuestEngine.builder().gates(gates).build();
 * gates.pool(pool);          // after each content load
 * gates.useEngine(engine);   // once, so 'Quests' prerequisites can be answered
 * }</pre>
 *
 * <p><b>The pool and the engine are set AFTER construction on purpose.</b> The engine needs its
 * gates at build time while the gates need the engine to answer a finished-quest prerequisite, and
 * content reloads long after both exist. Both handles are held atomically, so a reload swaps the
 * pool without a gap where a quest would be gated on nothing.
 *
 * <p>Everything a consumer supplies beyond this - the factor vocabulary, the context a factor is
 * read against, its own requirement kinds - lives on the {@link GateEvaluator}. A quest with no
 * requirements never touches any of it.
 */
public final class AssetQuestGates implements QuestGates {

    private final GateEvaluator evaluator;
    private final AtomicReference<QuestPool> pool = new AtomicReference<>(QuestPool.EMPTY);
    private final AtomicReference<QuestEngine> engine = new AtomicReference<>();

    private AssetQuestGates(@Nonnull GateEvaluator evaluator) {
        this.evaluator = evaluator;
    }

    /** Gates that answer through {@code evaluator}. */
    @Nonnull
    public static AssetQuestGates of(@Nonnull GateEvaluator evaluator) {
        return new AssetQuestGates(evaluator);
    }

    /** Point the gates at the current content. Call it after every load; null resets to empty. */
    public void pool(@Nullable QuestPool pool) {
        this.pool.set(pool == null ? QuestPool.EMPTY : pool);
    }

    /** The pool these gates read. */
    @Nonnull
    public QuestPool pool() {
        return pool.get();
    }

    /**
     * Let the gates answer {@code Requires.Quests} out of the engine's own records: a prerequisite
     * counts as met once that quest has been finished, claimed or not. Without this, and without a
     * completion probe wired on the evaluator, a prerequisite refuses.
     *
     * <p>It reads the STORED status rather than the effective one, so a repeatable prerequisite
     * still counts as done while it sits on cooldown - "have you ever finished this" is the
     * question a prerequisite asks.
     */
    public void useEngine(@Nullable QuestEngine engine) {
        this.engine.set(engine);
        evaluator.completedQuests(completionProbe());
    }

    /** The evaluator behind these gates, for a consumer that also wants to explain a refusal. */
    @Nonnull
    public GateEvaluator evaluator() {
        return evaluator;
    }

    /** The reason token for what shut this quest's gate, or null when it is open to the player. */
    @Nullable
    public String firstFailure(@Nonnull Subject subject, @Nonnull Quest quest) {
        QuestDefinition definition = pool.get().definition(quest.id());
        if (definition == null) {
            return null;
        }
        return evaluator.firstFailure(subject, definition.requires());
    }

    @Override
    public boolean accepts(@Nonnull Subject subject, @Nonnull Quest quest,
            @Nonnull List<String> reasons) {
        String failure = firstFailure(subject, quest);
        if (failure == null) {
            return true;
        }
        reasons.add(failure);
        return false;
    }

    @Override
    public boolean prerequisitesMet(@Nonnull Subject subject, @Nonnull Quest quest) {
        return firstFailure(subject, quest) == null;
    }

    /**
     * The completion probe {@link #useEngine} installs. It is kept here rather than on the
     * evaluator so a consumer that never sets an engine still gets the fail-closed default.
     *
     * <p>What counts as finished is {@link QuestLifecycle#isFinished}, the ONE rule - the same one
     * the {@code ziggfreedcommon:quest_completed} factor reading answers with - so a prerequisite
     * written as a {@code Quests} leaf and the same requirement written as a factor condition can
     * never disagree about one player and one quest.
     */
    @Nonnull
    GateEvaluator.CompletionProbe completionProbe() {
        return (subject, questId) -> {
            QuestEngine current = engine.get();
            return current != null
                    && QuestLifecycle.isFinished(current.store().status(subject, questId));
        };
    }
}
