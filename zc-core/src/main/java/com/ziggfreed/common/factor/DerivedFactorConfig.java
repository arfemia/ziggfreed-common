package com.ziggfreed.common.factor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.registry.RegistryLedger;
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
 * <p><b>Only a file that DEFINES a value registers one.</b> A naming overlay (a file with a
 * {@code Factor} target and no {@code Formula}) contributes words through {@link FactorNames} and
 * nothing to {@link #formulaFor}, so it can never shadow the real provider of the factor it names;
 * a file carrying both halves at once registers no value either, until the validator's finding is
 * resolved one way or the other.
 *
 * <p><b>No cache to invalidate on a reload.</b> A registry that adopts a derived id keeps a provider
 * that re-reads this config on every call rather than the folded formula, so a re-import takes
 * effect on the next resolve and a definition that disappears goes straight back to failing closed.
 * That is why the merge methods here only re-audit, with no cache to drop.
 *
 * <p>An EMPTY definition (no {@code Base} and no usable term) is treated as NO definition:
 * {@link #formulaFor} answers null, so the id fails closed exactly as an unregistered one does. An
 * empty file is an authoring accident, and letting it answer 0 would open every bounds-less gate
 * written against that id.
 */
public final class DerivedFactorConfig extends AbstractKeyedAssetConfig<DerivedFactorAsset>
        implements DerivedFactorSource {

    /** The label this audit's lines carry, at every fold and in zc's boot audit. */
    public static final String LOG_LABEL = "[factor] DerivedFactor";

    /** The store's contract label, which its mod-gate drop line carries ({@code [zc] mod gate: Factors ...}). */
    public static final String MOD_GATE_STORE = "Factors";

    private static final DerivedFactorConfig INSTANCE = new DerivedFactorConfig();

    private DerivedFactorConfig() {
        super(MOD_GATE_STORE);
    }

    @Nonnull
    public static DerivedFactorConfig getInstance() {
        return INSTANCE;
    }

    @Override
    @Nullable
    public FactorFormula formulaFor(@Nonnull String factorId) {
        DerivedFactorAsset asset = resolve(factorId);
        return asset != null && asset.definesValue() ? asset.getFormula() : null;
    }

    /**
     * Every factor id the folded files DEFINE a value for, sorted - the ids that belong in a factor
     * vocabulary listing. Deliberately narrower than {@link #ids()}, which lists FILE ids and so
     * would leak arbitrarily-named naming overlays into a pick list as if they were factors.
     */
    @Nonnull
    public List<String> definedIds() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, DerivedFactorAsset> entry : all().entrySet()) {
            if (entry.getValue() != null && entry.getValue().definesValue()) {
                out.add(entry.getKey());
            }
        }
        out.sort(null);
        return List.copyOf(out);
    }

    @Override
    public synchronized void loadDefaults(@Nonnull Map<String, DerivedFactorAsset> jarDefaults) {
        super.loadDefaults(jarDefaults);
        logFindings();
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, DerivedFactorAsset> layer) {
        super.mergePackLayer(layer);
        logFindings();
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, DerivedFactorAsset> layer) {
        super.mergeOwnerLayer(layer);
        logFindings();
    }

    /**
     * What {@link #audit()} asks before calling a formula term's factor unknown: the installed answer of
     * "does a factor vocabulary on this server provide this id?". The production default is the
     * contributions a mod made ({@link FactorContributions#isContributed}); the library installs the full
     * answer at its own setup (zc-objectives' {@code KnownFactors}: the progression runtime's vocabulary,
     * the placement, loot and encounter vocabularies, and the contributions), which every plugin's setup has
     * run before the first fold logs.
     */
    @Nonnull
    private volatile Predicate<String> knownFactors = FactorContributions::isContributed;

    /**
     * Install what {@link #audit()} asks of a formula term's factor id (see the field); null puts the
     * contributions-only default back. A consumer passing its own registry to {@link #audit(Predicate)}
     * still decides for that call alone.
     */
    public void knownFactors(@Nullable Predicate<String> known) {
        this.knownFactors = known == null ? FactorContributions::isContributed : known;
    }

    /**
     * Audit every folded file against the installed factor vocabularies ({@link #knownFactors(Predicate)}), so a
     * term naming a factor some mod registered in code is not reported. Findings are neutral values a consumer
     * can surface in its own validation command; {@link #logFindings()} is the always-on baseline, and zc's
     * boot audit reads this too.
     */
    @Nonnull
    public List<Finding> audit() {
        return audit(knownFactors);
    }

    /**
     * As {@link #audit()}, with {@code registeredElsewhere} answering "does some registry already
     * provide this factor id?" - pass a consumer's own registry check (for instance the placement
     * vocabulary's) so a term naming a genuinely unknown factor is reported rather than assumed. A term
     * naming a factor the mod gate left out ({@link #refusedByModGate}) is never reported.
     */
    @Nonnull
    public List<Finding> audit(@Nullable Predicate<String> registeredElsewhere) {
        Predicate<String> known = id -> refusedByModGate(id)
                || (registeredElsewhere != null && registeredElsewhere.test(id));
        return DerivedFactorValidator.validateAssets(all(), known);
    }

    /**
     * Did the mod gate leave the factor {@code factorId} out of this server on purpose (its {@code Factors}
     * file gates on a mod this server does not run, or on one it does with {@code Max: 0})? Such a factor is
     * absent by design: a condition on it fails closed and a term naming it adds 0, exactly as for an id
     * nobody defines, but a validator reports nothing about it and a resolve warns nothing. Matched trimmed
     * and lower-cased, as factor ids are.
     */
    public boolean refusedByModGate(@Nullable String factorId) {
        if (factorId == null || factorId.isBlank()) {
            return false;
        }
        String key = RegistryLedger.normalize(factorId);
        return modGateRefused().containsKey(key) && resolve(key) == null;
    }

    /** Log this config's findings once per fold: an error as a warning line, anything else at info. */
    public void logFindings() {
        ValidationReport.logAll(LOG_LABEL, audit(), SafeLog::warn, SafeLog::info);
    }
}
