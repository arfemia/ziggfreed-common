package com.ziggfreed.common.commerce.fold;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.board.BoardEngine;
import com.ziggfreed.common.board.BoardQuests;
import com.ziggfreed.common.board.QuestEngineBoardQuests;
import com.ziggfreed.common.cost.CostEngine;
import com.ziggfreed.common.progress.gate.GateEvaluator;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.shop.ShopEngine;
import com.ziggfreed.common.util.SafeLog;

/**
 * The one place a commerce engine is assembled, so every surface that charges, draws or accepts is
 * driving the SAME rules.
 *
 * <p>Each engine is built PER CALL rather than cached, and that is the whole design. Every part an
 * engine is made of can be replaced while the server runs - a consumer installs its own state store
 * or currency engine at its own setup, an asset reload swaps every offer and board object wholesale,
 * a shelf's price is re-read from the file behind it - so an engine held in a field is an engine
 * that quietly keeps answering with yesterday's content. Building one is a builder and a few field
 * copies; the catalogs, the store and the wallets behind it are the long-lived things, and they are
 * all read at the moment they are asked.
 *
 * <p><b>The gate evaluator is the one seam a consumer MUST fill to get factor gates.</b> This module
 * cannot see the portable {@code hytale:} factor standard library (it lives in a module commerce has
 * no edge to, deliberately), so the default evaluator here answers no factor at all and a
 * {@code Requires} block naming one fails CLOSED - which is the library's standing rule for a
 * reading nothing can answer, not a degrade invented here. The wiring root installs the evaluator
 * the progression runtime already uses, and one permission question then has one answer everywhere:
 *
 * <pre>{@code
 * CommerceEngines.installGates(() -> myEvaluator);
 * }</pre>
 */
public final class CommerceEngines {

    private static final AtomicReference<Supplier<GateEvaluator>> GATES = new AtomicReference<>();

    /** The fail-closed evaluator a server that installed none is gated by. Built once. */
    private static final AtomicReference<GateEvaluator> FALLBACK_GATES = new AtomicReference<>();

    private CommerceEngines() {
    }

    // ==================== The gate seam ====================

    /**
     * Install the requirement evaluator every commerce gate is answered by. Call it once at setup
     * with the SAME evaluator the quest engine uses, so a shop lock and a quest lock mean the same
     * thing. Passing null goes back to the fail-closed default.
     *
     * <p>It is a supplier rather than a value because a consumer's factor vocabulary keeps growing
     * after setup: the evaluator is asked for on every gate, so a mod registering a factor late
     * simply widens the next check.
     */
    public static void installGates(@Nullable Supplier<GateEvaluator> gates) {
        GATES.set(gates);
    }

    /**
     * The evaluator in force. Never null: a caller reaching for it before anything installed one -
     * an early command, a test - gets the fail-closed default rather than having to guard.
     */
    @Nonnull
    public static GateEvaluator gates() {
        Supplier<GateEvaluator> supplier = GATES.get();
        if (supplier != null) {
            try {
                GateEvaluator installed = supplier.get();
                if (installed != null) {
                    return installed;
                }
            } catch (Throwable t) {
                SafeLog.warn("[commerce] the installed gate evaluator could not be read, so this check "
                        + "used the fail-closed default: " + t.getMessage());
            }
        }
        return FALLBACK_GATES.updateAndGet(
                current -> current != null ? current : GateEvaluator.builder().build());
    }

    // ==================== The engines ====================

    /**
     * The price authority: check, drain and refund over whichever currency engine is installed right
     * now, with the real inventory behind an item-backed balance.
     */
    @Nonnull
    public static CostEngine costs() {
        return CostEngine.builder(CommerceDefaults.currencyEngine()).build();
    }

    /**
     * The purchase engine over the offers this server has loaded. The catalogue and the state store
     * are both read at call time, so a reload or a consumer's own store lands with nothing to
     * invalidate.
     */
    @Nonnull
    public static ShopEngine shops() {
        return ShopEngine.builder(costs(), gates())
                .catalog(CommerceCatalogs.shops())
                .build();
    }

    /**
     * The board engine over the shared quest runtime. A bounty IS a quest, so the lifecycle it
     * drives is the one every other surface reads, never a copy.
     */
    @Nonnull
    public static BoardEngine boards() {
        return BoardEngine.builder(boardQuests(), gates())
                .costs(costs())
                .build();
    }

    /**
     * The quest lifecycle a board drives, over the shared runtime's engine. Answers the do-nothing
     * seam when no runtime is standing yet, so an early caller reads as "no bounties" rather than
     * throwing - which is what a server still loading genuinely has.
     */
    @Nonnull
    public static BoardQuests boardQuests() {
        try {
            return new QuestEngineBoardQuests(ProgressionRuntime.quests());
        } catch (Throwable t) {
            SafeLog.warn("[commerce] the quest runtime is not available, so boards have no contracts "
                    + "to show: " + t.getMessage());
            return BoardQuests.NONE;
        }
    }
}
