package com.ziggfreed.common.currency.asset;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;
import com.ziggfreed.common.validation.ValidationReport;

/**
 * The {@code defaults < pack < owner} fold of every {@link CurrencyAsset}: which wallets this server
 * has, and what each one is like.
 *
 * <p>It is process-wide because the defining ASSETS are: one folder, one set of files, however many
 * mods spend out of them. A pack ships its wallets, a server owner retunes one through
 * {@code mods/ziggfreedcommon/currencies.json}, and every reader sees the same answer.
 *
 * <p><b>A wallet nothing defines does not exist</b>, and everything priced in it stays unaffordable
 * rather than free. That is the safe direction, and it is why {@link CurrencyValidator} reports an
 * unknown currency id at load: a price nobody can ever pay is silent otherwise.
 */
public final class CurrencyConfig extends AbstractKeyedAssetConfig<CurrencyAsset> {

    private static final CurrencyConfig INSTANCE = new CurrencyConfig();

    private CurrencyConfig() {
    }

    @Nonnull
    public static CurrencyConfig getInstance() {
        return INSTANCE;
    }

    @Override
    public synchronized void loadDefaults(@Nonnull Map<String, CurrencyAsset> jarDefaults) {
        super.loadDefaults(jarDefaults);
        logFindings();
    }

    @Override
    public synchronized void mergePackLayer(@Nonnull Map<String, CurrencyAsset> layer) {
        super.mergePackLayer(layer);
        logFindings();
    }

    @Override
    public synchronized void mergeOwnerLayer(@Nonnull Map<String, CurrencyAsset> layer) {
        super.mergeOwnerLayer(layer);
        logFindings();
    }

    /** Every wallet a player may actually be shown or charged in, in id order. */
    @Nonnull
    public List<CurrencyAsset> enabled() {
        List<CurrencyAsset> out = new ArrayList<>();
        for (String id : ids()) {
            CurrencyAsset currency = resolve(id);
            if (currency != null && currency.isEnabled()) {
                out.add(currency);
            }
        }
        return out;
    }

    /** Is {@code currencyId} a wallet any layer defines and leaves in circulation? */
    public boolean isSpendable(@Nonnull String currencyId) {
        CurrencyAsset currency = resolve(currencyId);
        return currency != null && currency.isEnabled();
    }

    /** Audit every folded wallet. */
    @Nonnull
    public List<Finding> audit() {
        return CurrencyValidator.validateAll(all());
    }

    /** Log this config's findings once per fold: an error as a warning line, anything else at info. */
    public void logFindings() {
        ValidationReport.logAll("[commerce] Currencies", audit(), SafeLog::warn, SafeLog::info);
    }
}
