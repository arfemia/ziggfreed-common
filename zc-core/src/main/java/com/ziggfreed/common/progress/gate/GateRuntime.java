package com.ziggfreed.common.progress.gate;

import java.util.function.Function;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * THE requirement evaluator this server answers a {@code Requires} block with, for every module down to
 * zc-core, so a loot table, a bonus row, a gear set or a reward row reads a full block the way a quest does.
 *
 * <p><b>It works with nothing wired (M513).</b> Its defaults are a vocabulary over every contributed and
 * file-defined factor ({@code FactorContributions}, the Factors files), a context over the subject's live
 * player, and an empty set of requirement kinds; a {@code Quests} leaf refuses until a completion probe is
 * wired. zc-progression's {@code ProgressionGates} fills all four seams at the library's setup with the shared
 * progression runtime's parts, which carry everything the defaults do and more, and answers this same
 * instance: one evaluator per server, never a second.
 *
 * <p>Every seam is read live at each question, so a part installed after the evaluator was first asked for
 * feeds the same instance. A seam that answers null reads as its default.
 */
public final class GateRuntime {

    /** The default vocabulary: no registration of its own, so every id falls through to the shared layers. */
    private static final FactorRegistry SHARED = new FactorRegistry("gate");

    /** The default requirement kinds: none, until a mod registers one or a richer vocabulary is installed. */
    private static final GateKindRegistry KINDS = new GateKindRegistry("gate-kind");

    @Nullable private static volatile Supplier<FactorRegistry> factors;
    @Nullable private static volatile Function<Subject, FactorContext> context;
    @Nullable private static volatile Supplier<GateKindRegistry> kinds;
    @Nullable private static volatile GateEvaluator evaluator;

    private GateRuntime() {
    }

    /** The one evaluator, built on first ask over the live seams. */
    @Nonnull
    public static GateEvaluator evaluator() {
        GateEvaluator local = evaluator;
        if (local == null) {
            synchronized (GateRuntime.class) {
                local = evaluator;
                if (local == null) {
                    local = GateEvaluator.builder()
                            .factorsLive(GateRuntime::vocabulary)
                            .factorContext(GateRuntime::contextOf)
                            .gateKindsLive(GateRuntime::kinds)
                            .warn(msg -> SafeLog.warn("[gate] " + msg))
                            .build();
                    evaluator = local;
                }
            }
        }
        return local;
    }

    /**
     * Fill the seams: the vocabulary, the subject's context and the requirement kinds the evaluator reads (each
     * null keeps its default), and the completion probe a {@code Quests} leaf asks (null keeps the one set).
     * At setup; filling again replaces.
     */
    public static synchronized void install(@Nullable Supplier<FactorRegistry> vocabulary,
            @Nullable Function<Subject, FactorContext> subjectContext, @Nullable Supplier<GateKindRegistry> gateKinds,
            @Nullable GateEvaluator.CompletionProbe completion) {
        factors = vocabulary;
        context = subjectContext;
        kinds = gateKinds;
        if (completion != null) {
            evaluator().completedQuests(completion);
        }
    }

    /** The vocabulary the evaluator reads right now: the installed one, else every shared factor. */
    @Nonnull
    public static FactorRegistry vocabulary() {
        Supplier<FactorRegistry> installed = factors;
        FactorRegistry answer = installed == null ? null : installed.get();
        return answer != null ? answer : SHARED;
    }

    /** The requirement kinds the evaluator reads right now: the installed ones, else the library's own table. */
    @Nonnull
    public static GateKindRegistry kinds() {
        Supplier<GateKindRegistry> installed = kinds;
        GateKindRegistry answer = installed == null ? null : installed.get();
        return answer != null ? answer : KINDS;
    }

    /** The context a subject's readings take: the installed seam's, else its live player's. */
    @Nonnull
    public static FactorContext contextOf(@Nonnull Subject subject) {
        Function<Subject, FactorContext> installed = context;
        FactorContext answer = installed == null ? null : installed.apply(subject);
        return answer != null ? answer : playerContext(subject);
    }

    /** The default context: the store and reference of the subject's live player, empty when it has none. */
    @Nonnull
    public static FactorContext playerContext(@Nonnull Subject subject) {
        try {
            Player player = subject.handleAs(Player.class);
            Ref<EntityStore> ref = player == null ? null : player.getReference();
            return FactorContext.about(ref == null ? null : ref.getStore(), ref);
        } catch (Throwable t) {
            return FactorContext.builder().build();
        }
    }

    /** Back to the defaults with no evaluator built. Tests only. */
    public static synchronized void resetForTests() {
        factors = null;
        context = null;
        kinds = null;
        evaluator = null;
        KINDS.clear();
    }
}
