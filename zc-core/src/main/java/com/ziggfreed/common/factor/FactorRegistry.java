package com.ziggfreed.common.factor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.registry.RegistryLedger;
import com.ziggfreed.common.util.SafeLog;

/**
 * The open READ vocabulary: a mod registers a namespaced factor id and the number behind it, and
 * authored content gates or scales on that id with no Java. One registry instance is one
 * vocabulary; a consumer builds its own and hands it to whichever engine evaluates against it.
 *
 * <p><b>Instantiable per consumer, never a shared mutable global</b> - the same paradigm the
 * dialogue engine keeps. A consumer's registry is fully populated at setup and only then handed
 * to the engine that reads it, so there is no registration race and one mod's vocabulary never
 * leaks into another's. (A single library engine may still expose ONE process-wide instance
 * behind a static facade where its own content is process-wide; that is the facade's choice, not
 * this class's.)
 *
 * <p><b>Fail-closed, and the null sentinel is the whole mechanism.</b> An unregistered id, a
 * throwing provider, and a provider answering {@link FactorProvider#resolve} with {@code null}
 * all resolve to {@code null} here, and {@link FactorCondition#accepts} fails on null. So a gate
 * written against a mod that is not installed stays shut instead of springing open, including the
 * bounds-less presence-check form. A throwing provider additionally counts against its owner in
 * the ledger and warns once per id.
 *
 * <p>Registration bookkeeping (who owns an id, how often its provider has failed) lives in the
 * shared {@link RegistryLedger}. Registration is idempotent per id with last-write-wins; ids are
 * matched case-insensitively.
 */
public final class FactorRegistry {

    @Nonnull
    private final RegistryLedger<FactorProvider> ledger;

    /** Ids already warned about, so an unregistered factor logs once rather than once per read. */
    @Nonnull
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    /** A registry whose log lines carry the generic prefix. */
    public FactorRegistry() {
        this(null);
    }

    /**
     * A registry whose ledger log lines are prefixed {@code [label]}, so an owner reading an
     * overwrite or fail-closed warning can tell which vocabulary it came from.
     */
    public FactorRegistry(@Nullable String label) {
        this.ledger = new RegistryLedger<>(label);
    }

    /** Register (or replace) the provider for {@code factorId}, unattributed. A blank id is ignored. */
    public void register(@Nullable String factorId, @Nullable FactorProvider provider) {
        register(factorId, RegistryLedger.UNATTRIBUTED, provider);
    }

    /** As {@link #register(String, FactorProvider)}, attributing the claim to {@code owner}. */
    public void register(@Nullable String factorId, @Nullable String owner, @Nullable FactorProvider provider) {
        if (factorId == null || factorId.isBlank() || provider == null) {
            return;
        }
        ledger.put(factorId, owner, provider);
        warned.remove(RegistryLedger.normalize(factorId));
    }

    /** Is {@code factorId} registered? Used by a validator to report a gate that fails closed. */
    public boolean isRegistered(@Nullable String factorId) {
        return ledger.isRegistered(factorId);
    }

    /** Every registered factor id, sorted (diagnostics, an authoring dropdown, a validator hint). */
    @Nonnull
    public List<String> ids() {
        return List.copyOf(ledger.ids());
    }

    /** Every registered id's owner + failure history, keyed by id (an admin registry listing). */
    @Nonnull
    public Map<String, RegistryLedger.RegistrationInfo> info() {
        return ledger.info();
    }

    /**
     * Resolve {@code factorId} for {@code ctx}. {@code null} when the id is blank, nothing is
     * registered for it, the provider answered null, or the provider threw. An UNREGISTERED id and
     * a THROWING provider are warned once per id (the throw also counted against its owner); a
     * provider ANSWERING null is a legitimate "cannot answer" and stays silent, since only the
     * provider knows whether its own null was expected. A non-finite answer is
     * treated as unresolvable too: a NaN cannot be reasoned about, so a gate on it must stay shut
     * rather than pass by accident. Never throws.
     */
    @Nullable
    public Double resolve(@Nullable String factorId, @Nonnull FactorContext ctx) {
        if (factorId == null || factorId.isBlank()) {
            return null;
        }
        String key = RegistryLedger.normalize(factorId);
        FactorProvider provider = ledger.get(key);
        if (provider == null) {
            warnOnce(key, "no provider registered for factor '" + factorId
                    + "' - conditions on it fail closed");
            return null;
        }
        Double value;
        try {
            value = provider.resolve(ctx);
        } catch (Throwable t) {
            ledger.recordFailure(key, t.getMessage());
            warnOnce(key, "factor provider '" + factorId + "' failed: " + t.getMessage());
            return null;
        }
        return value != null && Double.isFinite(value) ? value : null;
    }

    /** Drop every registration and every warn-once latch. */
    public void clear() {
        ledger.clear();
        warned.clear();
    }

    private void warnOnce(@Nonnull String key, @Nonnull String message) {
        if (warned.add(key)) {
            SafeLog.warn("[factor] " + message);
        }
    }
}
