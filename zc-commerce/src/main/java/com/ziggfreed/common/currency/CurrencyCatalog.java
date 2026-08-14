package com.ziggfreed.common.currency;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Which currencies exist. The seam between the currency ENGINE and wherever the definitions were
 * authored, so the engine never learns about an asset store, a config layer, or a file.
 *
 * <p>Ids resolve case-insensitively: an id is a filename in one file and a reference in another, and
 * an author who wrote one of them in a different case has made no mistake worth punishing.
 *
 * <p>An id nobody defines resolves to null, and every engine operation on it is a no-op that says
 * so once. That is the standing rule for an unknown id in this library: content naming a currency
 * whose pack is not installed stays inert rather than inventing a balance.
 */
@FunctionalInterface
public interface CurrencyCatalog {

    /** A catalog holding nothing. Every currency is unknown, so every operation is inert. */
    CurrencyCatalog EMPTY = new CurrencyCatalog() {
        @Override
        @Nullable
        public CurrencyDef get(@Nonnull String currencyId) {
            return null;
        }

        @Override
        @Nonnull
        public Collection<CurrencyDef> all() {
            return List.of();
        }
    };

    /** The definition for {@code currencyId}, matched case-insensitively, or null. */
    @Nullable
    CurrencyDef get(@Nonnull String currencyId);

    /**
     * Every defined currency, for the passes that fan over all of them (death loss, decay) and for
     * an admin listing. Empty by default, so a catalog that only answers lookups still compiles.
     */
    @Nonnull
    default Collection<CurrencyDef> all() {
        return List.of();
    }

    /** True when {@code currencyId} is defined here. */
    default boolean has(@Nonnull String currencyId) {
        return get(currencyId) != null;
    }

    /**
     * A fixed catalog over {@code definitions}, keyed case-insensitively. What a test drives the
     * engine with, and what a consumer folding a resolved asset store hands over.
     */
    @Nonnull
    static CurrencyCatalog of(@Nonnull Collection<CurrencyDef> definitions) {
        Map<String, CurrencyDef> byId = new LinkedHashMap<>();
        for (CurrencyDef def : definitions) {
            if (def != null) {
                byId.put(CurrencyDef.normalizeId(def.id()), def);
            }
        }
        Map<String, CurrencyDef> frozen = Collections.unmodifiableMap(byId);
        return new CurrencyCatalog() {
            @Override
            @Nullable
            public CurrencyDef get(@Nonnull String currencyId) {
                return frozen.get(CurrencyDef.normalizeId(currencyId));
            }

            @Override
            @Nonnull
            public Collection<CurrencyDef> all() {
                return frozen.values();
            }
        };
    }
}
