package com.ziggfreed.common.quest.asset;

import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.registry.RegistryLedger;

/**
 * The open VALUE-SOURCE vocabulary a quest generator's axes may name: which lists exist, and who
 * answers each one. A consumer registers its own at setup and hands the registry to
 * {@link QuestAssetStore#resolveAll}; a generator then writes {@code "Source": "yourmod:ores"} with
 * no code of its own.
 *
 * <p><b>Nothing is pre-seeded</b> - there is no list this library could enumerate that would mean
 * anything in somebody else's game. An unregistered source produces NOTHING (so the generator emits
 * no quests) and {@link QuestPoolValidator} reports it, which is far easier to chase than a quest
 * set that is silently short.
 *
 * <p>Registration bookkeeping lives in the shared {@link RegistryLedger}: ids are matched
 * case-insensitively, registration is idempotent per id with last-write-wins, and a source that
 * throws is counted against its owner.
 */
public final class QuestEnumeratorRegistry {

    @Nonnull
    private final RegistryLedger<QuestValueEnumerator> ledger;

    /** A registry logging under a generic prefix. */
    public QuestEnumeratorRegistry() {
        this(null);
    }

    /** A registry whose ledger log lines are prefixed {@code [label]}. */
    public QuestEnumeratorRegistry(@Nullable String label) {
        this.ledger = new RegistryLedger<>(label == null || label.isBlank() ? "quest-enumerator" : label);
    }

    /** Register (or replace) the source under {@code sourceId}, unattributed. A blank id is ignored. */
    public void register(@Nullable String sourceId, @Nullable QuestValueEnumerator enumerator) {
        register(sourceId, null, enumerator);
    }

    /** As {@link #register(String, QuestValueEnumerator)}, attributing the claim to {@code owner}. */
    public void register(@Nullable String sourceId, @Nullable String owner,
            @Nullable QuestValueEnumerator enumerator) {
        ledger.put(sourceId, owner, enumerator);
    }

    /** The source registered under {@code sourceId} (case-insensitive), or null when nothing is. */
    @Nullable
    public QuestValueEnumerator enumerator(@Nullable String sourceId) {
        return ledger.get(sourceId);
    }

    /** Is {@code sourceId} answered by anything? */
    public boolean isRegistered(@Nullable String sourceId) {
        return ledger.isRegistered(sourceId);
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

    /** Count a source's failure against its owner, so a persistently broken one is visible. */
    public void recordFailure(@Nullable String sourceId, @Nullable String message) {
        ledger.recordFailure(sourceId, message);
    }

    /** Drop every registration. */
    public void clear() {
        ledger.clear();
    }
}
