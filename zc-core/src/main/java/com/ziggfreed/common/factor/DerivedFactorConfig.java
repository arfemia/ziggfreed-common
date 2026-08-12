package com.ziggfreed.common.factor;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.ValidationReport;

/**
 * The {@code defaults < pack < owner} fold of every {@link DerivedFactorAsset}, and the
 * {@link DerivedFactorSource} a {@link FactorRegistry} consults when nothing is registered for an
 * id.
 *
 * <p>Wire it once per registry at setup ({@code registry.derivedSource(DerivedFactorConfig
 * .getInstance())}) and asset-defined factors resolve everywhere that registry is read. It is
 * process-wide because the defining ASSETS are: one store, one fold, one vocabulary of derived ids,
 * however many per-consumer registries read it.
 *
 * <p><b>No cache to invalidate on a reload.</b> A registry that adopts a derived id keeps a provider
 * that re-reads this config on every call rather than the folded formula, so a re-import takes
 * effect on the next resolve and a definition that disappears goes straight back to failing closed.
 * That is why the merge methods here only re-audit, where {@code WorldSelectorConfig} must also
 * invalidate.
 *
 * <p>An EMPTY definition (no {@code Base} and no usable term) is treated as NO definition:
 * {@link #formulaFor} answers null, so the id fails closed exactly as an unregistered one does. An
 * empty file is an authoring accident, and letting it answer 0 would open every bounds-less gate
 * written against that id.
 */
public final class DerivedFactorConfig extends AbstractKeyedAssetConfig<FactorFormula>
        implements DerivedFactorSource {

    private static final DerivedFactorConfig INSTANCE = new DerivedFactorConfig();

    private DerivedFactorConfig() {
    }

    @Nonnull
    public static DerivedFactorConfig getInstance() {
        return INSTANCE;
    }

    @Override
    @Nullable
    public FactorFormula formulaFor(@Nonnull String factorId) {
        FactorFormula formula = resolve(factorId);
        return formula == null || formula.isEmpty() ? null : formula;
    }

    @Override
    public synchronized void loadDefaults(@Nonnull Map<String, FactorFormula> jarDefaults) {
        super.loadDefaults(jarDefaults);
        logFindings();
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, FactorFormula> layer) {
        super.mergePackLayer(layer);
        logFindings();
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, FactorFormula> layer) {
        super.mergeOwnerLayer(layer);
        logFindings();
    }

    /**
     * Audit every folded definition. Findings are neutral values a consumer can surface in its own
     * validation command; {@link #logFindings()} is the always-on baseline.
     */
    @Nonnull
    public List<Finding> audit() {
        return audit(null);
    }

    /**
     * As {@link #audit()}, with {@code registeredElsewhere} answering "does some registry already
     * provide this factor id?" - pass a consumer's own registry check (for instance the placement
     * vocabulary's) so a term naming a genuinely unknown factor is reported rather than assumed.
     */
    @Nonnull
    public List<Finding> audit(@Nullable Predicate<String> registeredElsewhere) {
        return DerivedFactorValidator.validateAll(all(), registeredElsewhere);
    }

    /** Log this config's findings once per fold: an error as a warning line, anything else at info. */
    public void logFindings() {
        ValidationReport.logAll("[factor] DerivedFactor", audit(), SafeLog::warn, SafeLog::info);
    }
}
