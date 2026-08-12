package com.ziggfreed.common.factor;

import java.util.ArrayDeque;
import java.util.Deque;
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
 * <p><b>An id nobody registered may still be ASSET-DEFINED.</b> Wire a
 * {@link DerivedFactorSource} with {@link #derivedSource(DerivedFactorSource)} and a provider miss
 * falls through to a {@link FactorFormula} authored over this same registry's other factors - see
 * {@link #resolve} for the adoption and cycle rules. Without one, a miss is exactly what it always
 * was.
 *
 * <p>Registration bookkeeping (who owns an id, how often its provider has failed) lives in the
 * shared {@link RegistryLedger}. Registration is idempotent per id with last-write-wins; ids are
 * matched case-insensitively.
 */
public final class FactorRegistry {

    /**
     * How deep one resolution may follow derived definitions before giving up. A derived factor
     * built on another is normal; sixteen levels of it is a runaway definition, not authoring, and
     * the cap is what keeps a pathological (but acyclic) graph from eating the world thread.
     */
    public static final int MAX_DERIVED_DEPTH = 16;

    /**
     * One thread's in-flight derived resolution: the ids being evaluated (innermost last) and
     * whether the walk hit something structurally broken below.
     *
     * <p>The {@code broken} flag is what keeps a cycle from being SWALLOWED. A formula treats an
     * unresolvable term as a 0 contribution, which is right for a missing optional input and
     * exactly wrong for a cycle: the enclosing definition would quietly publish a number computed
     * from a loop it never noticed. So a cycle (or an exceeded depth cap) poisons the whole walk,
     * and every enclosing derived frame answers null instead of a value. A broken definition graph
     * is not a missing bonus.
     */
    private static final class DerivedWalk {
        private final Deque<String> path = new ArrayDeque<>();
        private boolean broken;
    }

    /**
     * The derived resolution in flight on THIS thread. A resolve pushes before evaluating a formula
     * and pops in a {@code finally}, so an id meeting itself on the path is a cycle by construction.
     *
     * <p>Static rather than per-registry on purpose: a derived formula resolves through whichever
     * registry asked, and two registries sharing one definition source could otherwise bounce a
     * cycle between them unseen. Removed once the outermost frame unwinds, so a pooled world thread
     * carries nothing - not a path entry, not the broken flag - between resolutions.
     */
    private static final ThreadLocal<DerivedWalk> DERIVED_WALK = ThreadLocal.withInitial(DerivedWalk::new);

    @Nonnull
    private final RegistryLedger<FactorProvider> ledger;

    /** Ids already warned about, so an unregistered factor logs once rather than once per read. */
    @Nonnull
    private final Set<String> warned = ConcurrentHashMap.newKeySet();

    /**
     * The provider instance adopted for each derived id, so re-adoption is identity-idempotent and
     * the ledger never logs a spurious overwrite for a factor that only ever had one definition.
     */
    @Nonnull
    private final Map<String, FactorProvider> adoptedDerived = new ConcurrentHashMap<>();

    @Nullable
    private volatile DerivedFactorSource derivedSource;

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
        // Asset-defined factors are a library-wide vocabulary layer, not one consumer's: the
        // Factors store is process-wide, so every registry sees those ids unless its owner clears
        // the hook. Defaulting it here (rather than at each construction site) is also the only
        // way a registry a CONSUMER builds and hands to an engine - a dialogue engine's, say -
        // picks them up without every consumer remembering to wire it.
        this.derivedSource = DerivedFactorConfig.getInstance();
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

    /**
     * Replace (or clear with {@code null}) the source consulted when nothing is registered for an
     * id. Every registry starts wired to the shared {@link DerivedFactorConfig}, so this is for a
     * consumer that wants a vocabulary of its own or none at all. Call once at setup, before the
     * engine reading this registry runs.
     */
    public void derivedSource(@Nullable DerivedFactorSource source) {
        this.derivedSource = source;
    }

    /** The wired derived source, or {@code null} when this registry has none. */
    @Nullable
    public DerivedFactorSource derivedSource() {
        return derivedSource;
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
     * registered or asset-defined for it, the provider answered null, or the provider threw. An
     * UNANSWERABLE id and a THROWING provider are warned once per id (the throw also counted
     * against its owner); a provider ANSWERING null is a legitimate "cannot answer" and stays
     * silent, since only the provider knows whether its own null was expected. A non-finite answer
     * is treated as unresolvable too: a NaN cannot be reasoned about, so a gate on it must stay
     * shut rather than pass by accident. Never throws.
     *
     * <p><b>A provider miss falls through to the wired {@link DerivedFactorSource}</b>, if there is
     * one. When it defines {@code factorId}, the formula is evaluated against THIS registry and
     * THIS context, so the derived value is resolved for the same subject/world/payload the caller
     * asked about, and the definition is then ADOPTED into the ledger under owner
     * {@code asset:<id>}: subsequent reads take the ordinary provider path, an admin registry
     * listing shows the factor with the asset that defines it, and an evaluation failure is
     * countable like any other. The adopted provider re-reads the definition every call, so an
     * asset reload that drops the file goes straight back to failing closed.
     *
     * <p><b>A derived factor always ANSWERS once its definition exists</b>, because an unresolvable
     * term contributes 0 rather than voiding the sum ({@link FactorFormula}). A bounds-less
     * {@link FactorCondition} on a derived id is therefore a presence check on the DEFINITION, not
     * on its inputs - author a {@code Min} when the inputs are what matter.
     *
     * <p>A definition that reaches itself, directly or through others, is a CYCLE: it fails closed
     * (null), the failure is counted against the asset, and the path is warned once. So is a chain
     * deeper than {@link #MAX_DERIVED_DEPTH}.
     */
    @Nullable
    public Double resolve(@Nullable String factorId, @Nonnull FactorContext ctx) {
        if (factorId == null || factorId.isBlank()) {
            return null;
        }
        String key = RegistryLedger.normalize(factorId);
        FactorProvider provider = ledger.get(key);
        if (provider == null) {
            provider = adoptDerived(key, factorId);
        }
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

    /** Drop every registration, every adopted definition, and every warn-once latch. */
    public void clear() {
        ledger.clear();
        adoptedDerived.clear();
        warned.clear();
    }

    // ==================== Derived factors ====================

    /**
     * The provider for an asset-defined {@code key}, registering it on first use, or {@code null}
     * when no derived source defines it. Attribution is the ASSET ({@code asset:<id>}) because that
     * file, not any Java caller, is what a server owner would go and edit.
     */
    @Nullable
    private FactorProvider adoptDerived(@Nonnull String key, @Nonnull String authoredId) {
        DerivedFactorSource source = derivedSource;
        if (source == null || formulaFor(source, key) == null) {
            return null;
        }
        FactorProvider adopted = adoptedDerived.computeIfAbsent(key,
                k -> ctx -> evaluateDerived(k, authoredId, ctx));
        ledger.put(key, "asset:" + key, adopted);
        warned.remove(key);
        return adopted;
    }

    /**
     * Evaluate the derived definition for {@code key} under the cycle + depth guards. Returns
     * {@code null} (fail closed) when the definition has gone, when the id is already in flight on
     * this thread, or when the chain is too deep - never a partial number, because a number the
     * caller cannot distinguish from a real one is the worst answer here.
     */
    @Nullable
    private Double evaluateDerived(@Nonnull String key, @Nonnull String authoredId, @Nonnull FactorContext ctx) {
        DerivedFactorSource source = derivedSource;
        FactorFormula formula = source == null ? null : formulaFor(source, key);
        if (formula == null) {
            // The defining asset was reloaded away: back to the ordinary fail-closed answer.
            return null;
        }
        DerivedWalk walk = DERIVED_WALK.get();
        Deque<String> path = walk.path;
        if (path.contains(key)) {
            String cycle = String.join(" -> ", path) + " -> " + key;
            walk.broken = true;
            ledger.recordFailure(key, "derived factor cycle: " + cycle);
            warnOnce("cycle:" + key, "derived factor '" + authoredId
                    + "' is defined in terms of itself (" + cycle + ") - it fails closed until the "
                    + "Factors assets are untangled");
            return null;
        }
        if (path.size() >= MAX_DERIVED_DEPTH) {
            walk.broken = true;
            ledger.recordFailure(key, "derived factor chain deeper than " + MAX_DERIVED_DEPTH);
            warnOnce("depth:" + key, "derived factor '" + authoredId + "' sits deeper than "
                    + MAX_DERIVED_DEPTH + " definitions (" + String.join(" -> ", path)
                    + ") - it fails closed");
            return null;
        }
        path.addLast(key);
        try {
            double value = formula.evaluate(this, ctx);
            // A term that could not resolve contributed 0 and that is fine; a CYCLE below did not,
            // so this frame's number was computed from a loop and must not be published.
            return walk.broken ? null : value;
        } finally {
            path.removeLast();
            if (path.isEmpty()) {
                DERIVED_WALK.remove();
            }
        }
    }

    /** The source's answer for {@code key}, treating a throwing source as "not defined here". */
    @Nullable
    private FactorFormula formulaFor(@Nonnull DerivedFactorSource source, @Nonnull String key) {
        try {
            return source.formulaFor(key);
        } catch (Throwable t) {
            warnOnce("source:" + key, "the derived-factor source failed for '" + key + "': " + t.getMessage());
            return null;
        }
    }

    private void warnOnce(@Nonnull String key, @Nonnull String message) {
        if (warned.add(key)) {
            SafeLog.warn("[factor] " + message);
        }
    }
}
