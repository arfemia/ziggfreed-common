package com.ziggfreed.common.commerce.fold;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.currency.CurrencyCatalog;
import com.ziggfreed.common.currency.CurrencyDef;
import com.ziggfreed.common.currency.asset.CurrencyAsset;
import com.ziggfreed.common.currency.asset.CurrencyConfig;

/**
 * Which wallets exist, read off the authored {@code defaults < pack < owner} fold.
 *
 * <p><b>Live rather than snapshotted.</b> Every lookup asks {@link CurrencyConfig} again, so a
 * reload, a pack arriving late or an owner file being re-read all land on the next question with
 * nothing to invalidate. A wallet switched off is simply not here, which is what makes everything
 * priced in it unaffordable rather than free.
 *
 * <p>The fold itself is memoised per wallet against the ASSET INSTANCE it came from: a balance read
 * runs on a render path, and rebuilding a definition per read would copy its whole knob bag each
 * time. Identity is the check rather than equality, because every layer merge replaces the asset
 * objects wholesale - so a re-import invalidates itself and there is no cache to clear.
 */
public final class AssetCurrencyCatalog implements CurrencyCatalog {

    private static final AssetCurrencyCatalog INSTANCE = new AssetCurrencyCatalog();

    /** The one catalog over the authored wallets. */
    @Nonnull
    public static AssetCurrencyCatalog getInstance() {
        return INSTANCE;
    }

    /** One folded wallet, remembered against the asset it was folded from. */
    private record Memo(@Nonnull CurrencyAsset source, @Nonnull CurrencyDef def) {
    }

    private final Map<String, Memo> memo = new ConcurrentHashMap<>();

    private AssetCurrencyCatalog() {
    }

    @Override
    @Nullable
    public CurrencyDef get(@Nonnull String currencyId) {
        CurrencyAsset asset = CurrencyConfig.getInstance().resolve(currencyId);
        if (asset == null || !asset.isEnabled()) {
            return null;
        }
        return folded(asset);
    }

    @Override
    @Nonnull
    public Collection<CurrencyDef> all() {
        List<CurrencyAsset> enabled = CurrencyConfig.getInstance().enabled();
        List<CurrencyDef> out = new ArrayList<>(enabled.size());
        for (CurrencyAsset asset : enabled) {
            out.add(folded(asset));
        }
        return out;
    }

    /** The definition for {@code asset}, folded once and reused until the asset itself is replaced. */
    @Nonnull
    private CurrencyDef folded(@Nonnull CurrencyAsset asset) {
        String id = asset.getId() == null ? "" : asset.getId();
        Memo current = memo.get(id);
        if (current != null && current.source() == asset) {
            return current.def();
        }
        CurrencyDef def = CommerceFold.currencyDef(asset);
        memo.put(id, new Memo(asset, def));
        return def;
    }
}
