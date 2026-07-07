package com.ziggfreed.common.dialogue;

import javax.annotation.Nullable;

/**
 * The resolved, data-driven look for one {@link DialogueOptionStyle} kind: the three button
 * tint states plus the leading glyph token. Authored as a {@code DialogueOptionTheme} asset
 * ({@code Server/ZiggfreedCommon/DialogueOptionTheme/<Kind>.json}) and folded
 * {@code defaults < pack < owner} by {@link DialogueOptionThemeConfig}, so a dialogue's option
 * colours + icons are asset data, not Java literals.
 *
 * <p>Every leaf is nullable so a partial override inherits the rest at paint time: author only
 * {@code Color} and the hover/press derive from it (or fall back to the enum); author only
 * {@code Glyph} and the colours stay the kind's default. A kind with no asset at all resolves to
 * {@code null} and {@link com.ziggfreed.common.dialogue.page.DialoguePage} paints straight from
 * the {@link DialogueOptionStyle} enum - the fail-closed neutral fallback.
 *
 * @param color       the default button tint ({@code #rrggbb}), or null to use the enum default
 * @param hover       the hovered tint, or null to derive from {@code color} / use the enum
 * @param press       the pressed tint, or null to derive from {@code color} / use the enum
 * @param glyphToken  the leading glyph token (accept / turnin / continue / open / farewell), or
 *                    null to use the enum's glyph
 */
public record DialogueOptionTheme(@Nullable String color, @Nullable String hover,
                                  @Nullable String press, @Nullable String glyphToken) {
}
