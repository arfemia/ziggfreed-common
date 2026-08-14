package com.ziggfreed.common.commerce.fold;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.board.asset.BoardAssetStore;
import com.ziggfreed.common.board.asset.BoardConfig;
import com.ziggfreed.common.board.asset.BoardValidator;
import com.ziggfreed.common.currency.asset.CurrencyConfig;
import com.ziggfreed.common.loot.reward.RewardKinds;
import com.ziggfreed.common.progress.gate.GateKindRegistry;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.shop.asset.ShopAssetStore;
import com.ziggfreed.common.shop.asset.ShopConfig;
import com.ziggfreed.common.shop.asset.ShopPoolConfig;
import com.ziggfreed.common.shop.asset.ShopValidator;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.ValidationReport;

/**
 * One pass over every piece of authored commerce content: the wallets, the storefronts and their
 * shelves and offers, the boards and their contracts.
 *
 * <p>It lives in the JOIN because that is the only layer that can see all three halves at once - the
 * authoring stores that hold the content, and the process-wide vocabularies the checks are answered
 * against. Each validator stays where it is and keeps knowing only its own domain; this class knows
 * which questions can be answered on this server and asks them.
 *
 * <p><b>An unanswerable vocabulary is SKIPPED, never guessed.</b> Every probe below is optional by
 * the validators' own contract: pass null and the checks depending on it do not run, which is right,
 * because reporting every gate as unknown because nothing could enumerate the answer would bury the
 * findings that are real. That is exactly what happens to FACTOR ids: which ones exist is a
 * per-consumer vocabulary assembled in a module commerce has no edge to, so a factor gate is not
 * checked here at all.
 *
 * <p>Total and fail-soft: a store that throws costs its own domain's findings and one line, never
 * the whole audit.
 */
public final class CommerceAudit {

    /** Whether the boot-time pass has run; {@link #runLateAudit} claims it exactly once per boot. */
    private static final AtomicBoolean LATE_AUDIT_RAN = new AtomicBoolean();

    private CommerceAudit() {
    }

    /**
     * Audit every commerce domain and answer the findings together, in the order an author would
     * fix them: what a price is paid in, then what is sold, then what is posted.
     */
    @Nonnull
    public static List<Finding> auditAll() {
        List<Finding> out = new ArrayList<>();
        collect(out, "wallet", CommerceAudit::auditCurrencies);
        collect(out, "shop", CommerceAudit::auditShops);
        collect(out, "board", CommerceAudit::auditBoards);
        return out;
    }

    /** Push findings already in hand at the server log, split by how much each one matters. */
    public static void log(@Nonnull List<Finding> findings) {
        ValidationReport.logAll("[commerce] content", findings, SafeLog::warn, SafeLog::info);
    }

    /** Audit and log in one call, for a caller with nothing else to do with the findings. */
    public static void runAndLog() {
        log(auditAll());
    }

    /**
     * The boot-time pass, gated to once per boot so the wiring root can hang it off a per-player
     * lifecycle event. It runs at FIRST player ready rather than at load for the same reason the
     * placement audit does: several checks ask another store or an open registry whether an id
     * exists, and only by then have every store folded and every mod's {@code setup()} run - asked
     * earlier they report whatever had not loaded yet. {@code /zigcommerce validate} stays the
     * on-demand form and is never gated.
     */
    public static void runLateAudit() {
        if (LATE_AUDIT_RAN.compareAndSet(false, true)) {
            runAndLog();
        }
    }

    // ==================== the three domains ====================

    @Nonnull
    private static List<Finding> auditCurrencies() {
        return CurrencyConfig.getInstance().audit();
    }

    /**
     * The offers as they will actually be sold - generators expanded through the same value sources
     * the catalogue uses - plus whatever the expansion itself reported.
     */
    @Nonnull
    private static List<Finding> auditShops() {
        ShopAssetStore.Resolution resolved =
                ShopAssetStore.getInstance().resolve(CommerceCatalogs.axisValues());
        List<Finding> out = new ArrayList<>(resolved.issues());
        out.addAll(ShopValidator.validate(resolved.entries(), ShopConfig.getInstance().all(),
                ShopPoolConfig.getInstance().all(), CommerceAudit::definesCurrency, rewardKinds(),
                gateKinds(), null));
        return out;
    }

    /**
     * The boards and the contracts as authored. The store's own resolution is asked for its issues:
     * a contract that could not be read is a finding an author needs, and it never reaches the
     * validator to be reported there.
     */
    @Nonnull
    private static List<Finding> auditBoards() {
        BoardAssetStore.Resolution resolved = BoardAssetStore.getInstance().resolve();
        List<Finding> out = new ArrayList<>(resolved.issues());
        out.addAll(BoardValidator.validate(BoardConfig.getInstance().all(),
                BoardAssetStore.getInstance().assets(), CommerceAudit::definesCurrency, rewardKinds(),
                objectiveKinds(), gateKinds(), null));
        return out;
    }

    // ==================== the vocabularies, where this server can answer them ====================

    /** True when some layer defines a wallet under this id. */
    private static boolean definesCurrency(@Nonnull String currencyId) {
        return CommerceCatalogs.currencies().get(currencyId) != null;
    }

    /** Which reward kinds anything pays out, which is one process-wide table. */
    @Nullable
    private static Predicate<String> rewardKinds() {
        try {
            return RewardKinds.shared()::isRegistered;
        } catch (Throwable t) {
            return null;
        }
    }

    /** Which moments any engine on this server ever fires. */
    @Nullable
    private static Predicate<String> objectiveKinds() {
        try {
            return ProgressionRuntime.objectiveKinds()::isRegistered;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The registered custom-gate vocabulary a {@code Requires} block may name. */
    @Nullable
    private static GateKindRegistry gateKinds() {
        try {
            return ProgressionRuntime.gateKinds();
        } catch (Throwable t) {
            return null;
        }
    }

    private interface DomainAudit {
        @Nonnull
        List<Finding> run();
    }

    private static void collect(@Nonnull List<Finding> out, @Nonnull String domain,
            @Nonnull DomainAudit audit) {
        try {
            out.addAll(audit.run());
        } catch (Throwable t) {
            SafeLog.warn("[commerce] the " + domain + " content could not be audited", t);
        }
    }
}
