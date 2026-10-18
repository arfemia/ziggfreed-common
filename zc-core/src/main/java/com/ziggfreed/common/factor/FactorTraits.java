package com.ziggfreed.common.factor;

import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.registry.RegistryLedger;

/**
 * What a factor's {@code Factors} files declare about its readings, beside its name: the editor dataset its
 * {@code Param} picks from, the number it reads when what it asks about is absent, and how often a reading can
 * change ({@link Volatility}). Read off the folded files on every ask, so a reload lands on the next one; a
 * caller that keeps the answer (a compiled gate) rechecks against {@link FactorRegistry#generation()}, which a
 * Factors fold bumps.
 *
 * <p>Every file naming the factor counts: a definition (its file name is the id) and every overlay targeting
 * it, whatever its {@code Param}. Where two disagree, the first file in id order that authors a leaf wins it,
 * except {@code Volatility}, which takes the most volatile word any of them writes, so two packs can never
 * talk a gate into keeping a stale answer. A factor no file names reads {@link #NONE}: free-text Param, nothing
 * definite when absent, {@link Volatility#MOMENT}.
 *
 * @param paramDataSet the editor dataset listing valid Params, or null
 * @param absentReads  the definite number read when the asked thing is absent, or null for "reads nothing"
 * @param volatility   how often a reading can change
 */
public record FactorTraits(@Nullable String paramDataSet, @Nullable Double absentReads,
        @Nonnull Volatility volatility) {

    /** What a factor no file describes reads as. */
    public static final FactorTraits NONE = new FactorTraits(null, null, Volatility.MOMENT);

    /** The traits the folded {@code Factors} files declare for {@code factorId}. */
    @Nonnull
    public static FactorTraits of(@Nullable String factorId) {
        if (factorId == null || factorId.isBlank()) {
            return NONE;
        }
        return of(factorId, DerivedFactorConfig.getInstance().all());
    }

    /** The decision core over an explicit file set, what a test drives. */
    @Nonnull
    public static FactorTraits of(@Nonnull String factorId, @Nonnull Map<String, DerivedFactorAsset> files) {
        String target = RegistryLedger.normalize(factorId);
        Map<String, DerivedFactorAsset> naming = new TreeMap<>();
        for (Map.Entry<String, DerivedFactorAsset> entry : files.entrySet()) {
            DerivedFactorAsset asset = entry.getValue();
            if (entry.getKey() != null && asset != null && asset.carriesTraits()
                    && target.equals(asset.namedFactorId())) {
                naming.put(RegistryLedger.normalize(entry.getKey()), asset);
            }
        }
        if (naming.isEmpty()) {
            return NONE;
        }
        String dataSet = null;
        Double absent = null;
        Volatility volatility = null;
        for (DerivedFactorAsset asset : naming.values()) {
            if (dataSet == null) {
                dataSet = asset.getParamDataSet();
            }
            if (absent == null && asset.getAbsentReads() != null && Double.isFinite(asset.getAbsentReads())) {
                absent = asset.getAbsentReads();
            }
            String word = asset.getVolatility();
            if (word != null && !word.isBlank()) {
                Volatility declared = Volatility.of(word);
                volatility = volatility == null ? declared : volatility.and(declared);
            }
        }
        return new FactorTraits(dataSet, absent, volatility == null ? Volatility.MOMENT : volatility);
    }
}
