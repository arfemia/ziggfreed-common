package com.ziggfreed.common.progress.gate;

import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.registry.RegistryLedger;

/**
 * The open requirement VOCABULARY behind a {@code Requires.Custom} block: which extra
 * requirement kinds exist, and what answers each one. A consumer registers its own at setup and
 * hands the registry to a {@link GateEvaluator}; content then authors those ids with no code.
 *
 * <p><b>Nothing is pre-seeded.</b> The genuinely universal requirements are already leaves of
 * {@link GateClause} (factor bounds, a permission, finished quests); everything else reaches
 * into some mod's own systems. An unregistered kind REFUSES rather than passing, so content gated
 * on a mod that is not installed stays gated, and a content validator reports it at load so
 * an owner sees which mod was expected to provide it.
 *
 * <p>Registration bookkeeping lives in the shared {@link RegistryLedger}: ids are matched
 * case-insensitively, registration is idempotent per id with last-write-wins, and a kind that
 * throws is counted against its owner.
 */
public final class GateKindRegistry {

    @Nonnull
    private final RegistryLedger<GateKind> ledger;

    /** A registry logging under a generic prefix. */
    public GateKindRegistry() {
        this(null);
    }

    /** A registry whose ledger log lines are prefixed {@code [label]}. */
    public GateKindRegistry(@Nullable String label) {
        this.ledger = new RegistryLedger<>(label == null || label.isBlank() ? "gate-kind" : label);
    }

    /** Register (or replace) the kind under {@code kindId}, unattributed. A blank id is ignored. */
    public void register(@Nullable String kindId, @Nullable GateKind kind) {
        register(kindId, null, kind);
    }

    /** As {@link #register(String, GateKind)}, attributing the claim to {@code owner}. */
    public void register(@Nullable String kindId, @Nullable String owner, @Nullable GateKind kind) {
        ledger.put(kindId, owner, kind);
    }

    /** The kind registered under {@code kindId} (case-insensitive), or null when nothing is. */
    @Nullable
    public GateKind kind(@Nullable String kindId) {
        return ledger.get(kindId);
    }

    /** Is {@code kindId} answered by anything? A validator uses this to report a gate nobody owns. */
    public boolean isRegistered(@Nullable String kindId) {
        return ledger.isRegistered(kindId);
    }

    /** Every registered id, sorted (diagnostics, an authoring hint, a validator message). */
    @Nonnull
    public List<String> ids() {
        return List.copyOf(ledger.ids());
    }

    /** Every registered id's owner plus failure history, keyed by id. */
    @Nonnull
    public Map<String, RegistryLedger.RegistrationInfo> info() {
        return ledger.info();
    }

    /** Count a kind's failure against its owner, so a persistently broken gate is visible. */
    public void recordFailure(@Nullable String kindId, @Nullable String message) {
        ledger.recordFailure(kindId, message);
    }

    /** Drop every registration. */
    public void clear() {
        ledger.clear();
    }
}
