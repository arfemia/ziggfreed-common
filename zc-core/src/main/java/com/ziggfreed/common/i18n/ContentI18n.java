package com.ziggfreed.common.i18n;

import javax.annotation.Nonnull;

/**
 * What a consumer mod's authored content keys are REALLY called, and which of them exist.
 *
 * <h2>Why an authored key is not the key</h2>
 *
 * <p>The engine's {@code I18nModule} derives a key's namespace from the {@code .lang} FILENAME, so
 * an entry written {@code ui.bounty.board.daily} inside {@code mmoskilltree.lang} registers under
 * the id {@code mmoskilltree.ui.bounty.board.daily}. Content, meanwhile, is authored WITHOUT that
 * namespace: a shelf writes {@code "TitleKey": "shop.general.title"} because that is what the author
 * typed into their own lang file. A library that hands the client the authored key verbatim
 * therefore hands it an id nothing resolves, and the raw key is what a player reads.
 *
 * <p>This seam is where a consumer says, once, which namespace its authored keys belong to and
 * which of them it actually ships. Nothing in this library can know either: the namespace is that
 * mod's lang filename, and the catalogue is whatever it and its packs loaded.
 *
 * <h2>Per-consumer, not per-process</h2>
 *
 * <p>Several mods author into the SAME shared stores, so a single baked-in namespace would be a
 * guess that is wrong for everyone but the first. Fills are REGISTERED with {@link ContentKeys}
 * instead, and a key is attributed to a fill by asking which one {@link #hasKey} answers for. That
 * is the only attribution available, because a folded asset records no owner: every layer is keyed
 * by id alone (see {@code asset.AbstractKeyedAssetConfig}), so by the time a page reads a title key
 * there is nothing left saying which jar or pack wrote it. Existence answers it anyway, since a mod
 * that wrote a key is the mod that shipped its translation.
 *
 * <p>An unfilled server, or a key no fill claims, degrades to the key exactly as authored - which
 * is what a library with no consumer opinion can honestly do, and what every call site did before
 * this seam existed.
 */
public interface ContentI18n {

    /** The {@code .lang} filename stem plus a dot (e.g. {@code "mmoskilltree."}). */
    @Nonnull
    String keyPrefix();

    /**
     * True when this UNPREFIXED key resolves in the default (English) catalogue - a
     * locale-independent probe, since the server never reads a player's locale and the client does
     * the real per-locale resolution. The implementation prepends {@link #keyPrefix()}.
     */
    boolean hasKey(@Nonnull String unprefixedKey);
}
