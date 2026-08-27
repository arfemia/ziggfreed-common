package com.ziggfreed.common.factor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.registry.RegistryLedger;
import com.ziggfreed.common.util.SafeLog;

/**
 * The generic FEATURE-FLAG factor: a mod declares which of its own features are switched on, and
 * authored content anywhere gates on them through the ordinary factor vocabulary with no Java and
 * no dependency on the declaring mod.
 *
 * <p>Registering a namespace's first feature contributes the factor id {@code <namespace>:feature}
 * process-wide (through {@link FactorContributions}, so every {@link FactorRegistry} resolves it),
 * read as:
 * <pre>{@code
 * "Conditions": [ {"Factor": "yourmod:feature", "Param": "trading", "Min": 1} ]
 * }</pre>
 *
 * <p><b>A consumer contributes only its own feature IDS and their live on/off state</b> - one
 * {@link #register} call per feature at its own setup, each with a supplier read fresh on every
 * evaluation so a runtime toggle is visible on the next gate check. An alias is simply the same
 * supplier registered under a second id.
 *
 * <p><b>The reading is a definite number wherever a feature can be asked about at all:</b>
 * <ul>
 *   <li>a registered feature answers {@code 1} when its supplier says on, else {@code 0};</li>
 *   <li>a feature id the namespace never registered answers a definite {@code 0} - a feature nobody
 *       declared is genuinely off, and a real number is what keeps the bounds-less presence form
 *       usable as "the declaring mod is installed";</li>
 *   <li>a missing {@code Param} names no feature at all, so it answers {@code null} and whatever
 *       asked stays shut;</li>
 *   <li>an UNDECLARED NAMESPACE is simply an uncontributed factor id: the standing fail-closed rule
 *       already shuts every gate on it, with nothing to special-case here.</li>
 * </ul>
 *
 * <p>A supplier that throws reads as {@code 0} (off) with one warn per feature: the feature clearly
 * cannot be used right now, and staying a definite number keeps the presence idiom intact.
 */
public final class FeatureFlags {

    /** The id suffix every declared namespace's factor carries: {@code <namespace>:feature}. */
    public static final String FACTOR_SUFFIX = ":feature";

    /** One namespace's declared features, keyed by normalized feature id. */
    private static final Map<String, Map<String, BooleanSupplier>> FEATURES = new ConcurrentHashMap<>();

    /** Features already warned about a throwing supplier, keyed {@code namespace:feature}. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private FeatureFlags() {
    }

    /**
     * Declare one feature of {@code namespace} (the declaring mod's own id prefix, colon-free) and
     * the live supplier behind it, attributed to {@code owner} in the factor ledger. The first
     * feature of a namespace contributes {@code <namespace>:feature} process-wide; later calls just
     * add to the table it reads. Re-registering an id replaces its supplier, which is how a reload
     * refreshes a captured config handle.
     */
    public static void register(@Nullable String namespace, @Nullable String featureId,
            @Nullable String owner, @Nullable BooleanSupplier enabled) {
        String ns = normalizeNamespace(namespace);
        if (ns == null || featureId == null || featureId.isBlank() || enabled == null) {
            return;
        }
        Map<String, BooleanSupplier> table = FEATURES.computeIfAbsent(ns, key -> {
            FactorContributions.register(key + FACTOR_SUFFIX, owner,
                    ctx -> read(key, ctx.param()));
            return new ConcurrentHashMap<>();
        });
        table.put(RegistryLedger.normalize(featureId), enabled);
    }

    /** Has {@code namespace} declared {@code featureId} (whatever its current state)? */
    public static boolean isKnown(@Nullable String namespace, @Nullable String featureId) {
        String ns = normalizeNamespace(namespace);
        if (ns == null || featureId == null || featureId.isBlank()) {
            return false;
        }
        Map<String, BooleanSupplier> table = FEATURES.get(ns);
        return table != null && table.containsKey(RegistryLedger.normalize(featureId));
    }

    /** Every feature id {@code namespace} has declared, sorted; empty for an undeclared namespace. */
    @Nonnull
    public static List<String> ids(@Nullable String namespace) {
        String ns = normalizeNamespace(namespace);
        Map<String, BooleanSupplier> table = ns == null ? null : FEATURES.get(ns);
        return table == null ? List.of() : List.copyOf(new TreeSet<>(table.keySet()));
    }

    /**
     * The factor reading for one namespace: {@code 1}/{@code 0} for a declared feature, a definite
     * {@code 0} for an undeclared one, {@code null} for a blank param. Public so a declaring mod's
     * own availability checks can share the exact reading its gates get.
     */
    @Nullable
    public static Double read(@Nullable String namespace, @Nullable String param) {
        String ns = normalizeNamespace(namespace);
        if (ns == null || param == null || param.isBlank()) {
            return null;
        }
        Map<String, BooleanSupplier> table = FEATURES.get(ns);
        if (table == null) {
            return null;
        }
        String feature = RegistryLedger.normalize(param);
        BooleanSupplier enabled = table.get(feature);
        if (enabled == null) {
            return 0.0;
        }
        try {
            return enabled.getAsBoolean() ? 1.0 : 0.0;
        } catch (Throwable t) {
            if (WARNED.add(ns + ":" + feature)) {
                SafeLog.warn("[factor] feature '" + feature + "' of '" + ns
                        + "' could not be read, so it gates as off: " + t.getMessage());
            }
            return 0.0;
        }
    }

    /** Forget every declared feature - the empty state a test starts from. Contributions remain. */
    public static void reset() {
        FEATURES.clear();
        WARNED.clear();
    }

    /**
     * The namespace, trimmed and lower-cased, or null when unusable. A colon is refused because the
     * factor token grammar reads everything before the first {@code :} as the namespace, so a
     * namespace carrying one would mint an id no condition could address back.
     */
    @Nullable
    private static String normalizeNamespace(@Nullable String namespace) {
        if (namespace == null || namespace.isBlank() || namespace.indexOf(':') >= 0) {
            return null;
        }
        return RegistryLedger.normalize(namespace);
    }
}
