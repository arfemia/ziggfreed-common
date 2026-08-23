package com.ziggfreed.common.ui;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.annotation.Nonnull;

/**
 * Deterministic background / text colour pairs for free-string tag chips, so the same tag reads the
 * same colour on every surface and every open. Keywords are matched by substring
 * (case-insensitive); an unmatched tag falls to a stable hash-picked pair, never to a random one.
 *
 * <p>The keyword table is generic adventure vocabulary, not any one mod's; a consumer wanting a
 * bespoke reading for its own tags recolours the chip after asking here.
 */
public final class TagColors {

    private TagColors() {
    }

    /** One chip colouring: the chip's background fill and its label's text colour. */
    public record TagColor(@Nonnull String background, @Nonnull String textColor) {
    }

    private static final LinkedHashMap<String[], TagColor> KEYWORD_MAP = new LinkedHashMap<>();

    private static final TagColor[] FALLBACK_COLORS = {
        new TagColor("#1a2a3d", "#4a9eff"),
        new TagColor("#2a1a3d", "#b07fff"),
        new TagColor("#1a3d1a", "#4aff7f"),
        new TagColor("#3d2a1a", "#ffaa4a"),
        new TagColor("#1a3a2a", "#6aca9a"),
        new TagColor("#3d1a1a", "#ff6b6b"),
    };

    static {
        KEYWORD_MAP.put(new String[]{"combat", "warrior", "fight"}, new TagColor("#3d1a1a", "#ff6b6b"));
        KEYWORD_MAP.put(new String[]{"magic", "arcane", "mage"}, new TagColor("#2a1a3d", "#b07fff"));
        KEYWORD_MAP.put(new String[]{"gather", "harvest", "nature", "fishing"}, new TagColor("#1a3d1a", "#4aff7f"));
        KEYWORD_MAP.put(new String[]{"mining", "excavat"}, new TagColor("#1a2a3d", "#4a9eff"));
        KEYWORD_MAP.put(new String[]{"craft", "build", "smith"}, new TagColor("#3d2a1a", "#ffaa4a"));
        KEYWORD_MAP.put(new String[]{"woodcut", "lumber"}, new TagColor("#1a3a2a", "#6aca9a"));
        KEYWORD_MAP.put(new String[]{"chapter", "story"}, new TagColor("#1a2d3d", "#4a9eff"));
        KEYWORD_MAP.put(new String[]{"epic", "legendary", "boss"}, new TagColor("#3d2e1a", "#ffcc4a"));
        KEYWORD_MAP.put(new String[]{"branch", "choice", "path"}, new TagColor("#2a2a1a", "#cfcf4a"));
        KEYWORD_MAP.put(new String[]{"repeat", "daily", "weekly"}, new TagColor("#2a1a2a", "#cf7fff"));
        KEYWORD_MAP.put(new String[]{"tutorial", "beginner"}, new TagColor("#1a3a3a", "#4acfcf"));
        KEYWORD_MAP.put(new String[]{"advanced"}, new TagColor("#2a1a1a", "#ff9a6a"));
        KEYWORD_MAP.put(new String[]{"chain"}, new TagColor("#1a2a2a", "#6aaaca"));
    }

    /**
     * The colour pair for {@code tag}: the first keyword hit wins, else a deterministic hash pick.
     */
    @Nonnull
    public static TagColor colorOf(@Nonnull String tag) {
        String lower = tag.toLowerCase(Locale.ROOT);
        for (Map.Entry<String[], TagColor> entry : KEYWORD_MAP.entrySet()) {
            for (String keyword : entry.getKey()) {
                if (lower.contains(keyword)) {
                    return entry.getValue();
                }
            }
        }
        int hash = Math.abs(lower.hashCode());
        return FALLBACK_COLORS[hash % FALLBACK_COLORS.length];
    }
}
