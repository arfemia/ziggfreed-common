package com.ziggfreed.common.dialogue;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.asset.AbstractKeyedAssetConfig;

/**
 * The {@code defaults < pack < owner} fold authority for dialogue-option styling, keyed by a
 * {@link DialogueOptionStyle#key()} ({@code accept} / {@code turnin} / {@code continue} /
 * {@code neutral} / {@code farewell}) - the data-driven home of the option tint + glyph the
 * {@link DialogueOptionStyle} enum previously hard-coded. ziggfreed-common ships the neutral
 * defaults as {@code Server/ZiggfreedCommon/DialogueOptionTheme/*.json} (its own asset pack), and
 * a consumer pack or a server owner overrides any kind by dropping the same-id file; the enum
 * stays only as the fail-closed fallback when no layer authored a kind.
 *
 * <p>The fold mechanics live in {@link AbstractKeyedAssetConfig}; this singleton adds only the
 * {@link DialogueOptionTheme} type binding + {@link #getInstance()}. The map holds the RESOLVED
 * {@link DialogueOptionTheme} (the merge mapper runs {@code (id, a) -> a.toTheme()}).
 */
public final class DialogueOptionThemeConfig extends AbstractKeyedAssetConfig<DialogueOptionTheme> {

    private static final DialogueOptionThemeConfig INSTANCE = new DialogueOptionThemeConfig();

    @Nonnull
    public static DialogueOptionThemeConfig getInstance() {
        return INSTANCE;
    }

    private DialogueOptionThemeConfig() {
    }

    /** The folded theme for a style kind (by {@link DialogueOptionStyle#key()}), or null if no layer authored it. */
    @Nullable
    public DialogueOptionTheme forStyle(@Nonnull DialogueOptionStyle style) {
        return resolve(style.key());
    }
}
