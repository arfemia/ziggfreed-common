package com.ziggfreed.common.factor;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * Where a {@link FactorRegistry} looks for a factor NOBODY registered a provider for: a definition
 * written as a {@link FactorFormula} over other factors, rather than Java.
 *
 * <p>This is the seam that makes a factor authorable. A mod registers the primitive readings it
 * owns; a pack author combines them into a new named number without touching Java, and every
 * consumer of the vocabulary - a placement gate, a dialogue condition, another formula - addresses
 * that name exactly like a registered one.
 *
 * <p>Consulted only on a provider MISS, so a real provider always wins and a derived definition can
 * never shadow one. {@code null} means "not a derived factor here", which leaves the registry's
 * usual fail-closed answer intact. Called on the world thread inside a resolve, so an implementation
 * must be a cheap map read (never a load, never blocking) and must not itself resolve factors.
 *
 * <p>{@link DerivedFactorConfig} is the implementation this library ships, backed by the
 * {@code Server/ZiggfreedCommon/Factors/} asset store.
 */
@FunctionalInterface
public interface DerivedFactorSource {

    /**
     * The formula defining {@code factorId}, or {@code null} when this source does not define it.
     * {@code factorId} arrives already normalized the way {@link FactorRegistry} matches ids
     * (trimmed, lower-cased).
     */
    @Nullable
    FactorFormula formulaFor(@Nonnull String factorId);
}
