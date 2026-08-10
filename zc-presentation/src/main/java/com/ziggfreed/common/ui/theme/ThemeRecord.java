package com.ziggfreed.common.ui.theme;

import javax.annotation.Nonnull;

/**
 * One loaded theme retained in a theme registry: enough to drive a theme picker /
 * command (id + display-name localization key + premium tag + sort order) and the
 * runtime recolor (its {@link Palette}). Generic + mod-agnostic.
 *
 * <p>A consumer folds these records over the generic {@code defaults < pack < owner}
 * backbone ({@link com.ziggfreed.common.asset.AbstractKeyedAssetConfig}) and applies
 * its OWN gate / policy on top - {@code premium} is a neutral tag the consumer
 * interprets (e.g. "requires an entitlement to actually paint"); this type carries
 * no concept of whether a premium theme is unlocked.
 */
public final class ThemeRecord {
    public final String id;
    public final String displayNameKey;
    public final Palette palette;
    public final boolean premium;
    public final int order;

    public ThemeRecord(@Nonnull String id, @Nonnull String displayNameKey,
            @Nonnull Palette palette, boolean premium, int order) {
        this.id = id;
        this.displayNameKey = displayNameKey;
        this.palette = palette;
        this.premium = premium;
        this.order = order;
    }
}
