package com.ziggfreed.common.npc.placement;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.npc.NpcIdentities;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.ValidationReport;

/**
 * The {@code defaults < pack < owner} fold of every {@link NpcPlacementAsset}, and the pool the
 * reconciler walks when it decides what should be standing in a world.
 *
 * <p>Every layer write clears the per-world sweep debounce ({@link NpcPlacementReconciler}) and
 * the resolved-position cache, so a reload takes effect on the next sweep rather than waiting for
 * a world to be entered fresh. Both invalidations live on the merge methods themselves, so no
 * caller can forget them.
 *
 * <p><b>Two audits, at two moments.</b> A layer fold logs the FILE-LOCAL findings
 * ({@link #logFindings()}), which are answerable from the file alone and therefore true whatever
 * else has loaded. The CROSS-ASSET findings - an id in another store, a registry entry, a loaded
 * model - are only trustworthy once every store has folded and every mod's {@code setup()} has run,
 * so they wait for {@link #runLateAudit()} on the first player ready.
 */
public final class NpcPlacementConfig extends AbstractKeyedAssetConfig<NpcPlacementAsset> {

    private static final NpcPlacementConfig INSTANCE = new NpcPlacementConfig();

    /** Whether the late audit has already had its one run this boot. */
    private final AtomicBoolean lateAudited = new AtomicBoolean();

    /** Who reports this pool's cross-asset findings, when it is not this library. */
    @Nullable
    private volatile String lateAuditOwner;

    private NpcPlacementConfig() {
    }

    @Nonnull
    public static NpcPlacementConfig getInstance() {
        return INSTANCE;
    }

    @Override
    public synchronized void loadDefaults(@Nonnull Map<String, NpcPlacementAsset> jarDefaults) {
        super.loadDefaults(jarDefaults);
        onPoolChanged();
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, NpcPlacementAsset> layer) {
        super.mergePackLayer(layer);
        onPoolChanged();
        logFindings();
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, NpcPlacementAsset> layer) {
        super.mergeOwnerLayer(layer);
        onPoolChanged();
        logFindings();
    }

    /**
     * Drop every derived cache that was computed from the previous pool. Also safe (and correct)
     * to call from a consumer's own config-reload command.
     */
    public void onPoolChanged() {
        NpcPlacementReconciler.clearDebounce();
        NpcPlacementPositionCache.invalidateAll();
        NpcIdentities.invalidate();
    }

    /**
     * Audit every folded placement in full, cross-asset checks included (see
     * {@link NpcPlacementValidator}). Meaningful once the server is up: a consumer's validation
     * command is the natural caller.
     */
    @Nonnull
    public List<Finding> audit() {
        return NpcPlacementValidator.audit(all().values());
    }

    /**
     * Audit every folded placement on its own terms only (the file-local half of
     * {@link NpcPlacementValidator}). Safe at any moment, because nothing it reports depends on
     * anything outside the file.
     */
    @Nonnull
    public List<Finding> auditFileLocal() {
        return NpcPlacementValidator.auditFileLocal(all().values());
    }

    /**
     * Log the file-local findings once per fold: an error as a warning line, anything else at info.
     * The cross-asset half is deliberately absent here - see {@link #runLateAudit()}.
     */
    public void logFindings() {
        ValidationReport.logAll("[placement]", auditFileLocal(), SafeLog::warn, SafeLog::info);
    }

    // ==================== the late, cross-asset audit ====================

    /**
     * Claim the cross-asset audit of this pool for {@code owner}, so this library's own late audit
     * stands down and the same findings are reported once rather than twice.
     *
     * <p>A consumer that folds these placements into its own content report is already going to say
     * everything {@link #runLateAudit()} would, in its own vocabulary and its own command. Claiming
     * is how it says so. Call it from {@code setup()}: the claim is read on the first player ready,
     * which is always later.
     */
    public void claimLateAudit(@Nonnull String owner) {
        String name = owner.trim();
        if (name.isEmpty()) {
            return;
        }
        lateAuditOwner = name;
    }

    /** Who claimed the cross-asset audit, or {@code null} while this library still owns it. */
    @Nullable
    public String lateAuditOwner() {
        return lateAuditOwner;
    }

    /**
     * Run and log the FULL audit, once per boot. Call it on the first player ready: by then every
     * asset store has folded and every mod's {@code setup()} has run, which is what makes an
     * "unknown id" finding an answer rather than a guess.
     *
     * <p>Does nothing on later calls, and nothing at all where a consumer has claimed the audit
     * through {@link #claimLateAudit(String)}.
     */
    public void runLateAudit() {
        runLateAudit(SafeLog::warn, SafeLog::info);
    }

    /**
     * {@link #runLateAudit()} against caller-supplied sinks, which is what
     * {@link ValidationReport#logAll} is shaped for: a config passes its guarded log methods and a
     * test passes a list. An error goes to {@code errorSink}, everything else - including the line
     * naming a claimant - to {@code noteSink}.
     */
    void runLateAudit(@Nullable Consumer<String> errorSink, @Nullable Consumer<String> noteSink) {
        if (!lateAudited.compareAndSet(false, true)) {
            return;
        }
        String owner = lateAuditOwner;
        if (owner != null) {
            if (noteSink != null) {
                noteSink.accept("[placement] cross-asset content findings are reported by " + owner);
            }
            return;
        }
        ValidationReport.logAll("[placement]", audit(), errorSink, noteSink);
    }

    /** Re-arm the late audit and drop any claim, so a test can drive it more than once. */
    void clearLateAuditForTests() {
        lateAudited.set(false);
        lateAuditOwner = null;
    }
}
