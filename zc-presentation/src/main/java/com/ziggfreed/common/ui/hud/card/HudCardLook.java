package com.ziggfreed.common.ui.hud.card;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.util.SafeLog;

/**
 * The colour a HUD card is drawn in, as ONE hex string an author types and the arithmetic every
 * card does with it. A card is a panel wearing a shipped 9-slice frame, and its colour is a tint
 * MULTIPLIED over that frame ({@code .Background.Color}, pushed through {@code ui/UiRetint}):
 * {@code #ffffff} is exactly the shipped look, a darker hex darkens it, a hue tints it, and an
 * EIGHT-digit hex carries an alpha in its last two digits, so {@code #ffffffb8} is the shipped
 * card at about 72 percent opacity, the pure transparency case. Six digits is fully opaque.
 *
 * <p>Two things fall out of the multiply, and the whole model hangs off them. The identity value
 * is {@link #IDENTITY_HEX} ({@code #ffffffff}, or its six-digit twin), and a card resolving to it
 * pushes NOTHING ({@link #cardColor} is null), so the documents' own constants stand and the
 * common case costs no commands. And the alpha is an OPACITY FACTOR ({@link #opacity}) the row
 * dressing inside a bar follows without an author touching it: {@link #dimmed} keeps a shipped
 * colour's hue and multiplies its shipped alpha by that factor, so a card at 72 percent gets a
 * well, a gloss and a shade at 72 percent of their shipped strength, and the card's own hue never
 * bleeds into them (a tinted card with untinted dressing stays readable).
 *
 * <p><b>Where the value is authored.</b> The shared record every card reads is
 * {@code Server/ZiggfreedCommon/HudCards/Default.json} ({@link HudCardAsset}, owner layer
 * {@code mods/ziggfreedcommon/hud-cards.json}); a card overrides it through a leaf of its own, and
 * {@link #resolve} folds the two: the card's own value when it has one, else the shared one, else
 * the shipped look. A malformed value is IGNORED with one warning naming where it was authored and
 * what it said ({@link #authored}), so it falls through to the layer below rather than crashing a
 * paint or drawing a black card. Pure apart from that one guarded line, so every decision here is
 * testable without a client.
 */
public final class HudCardLook {

    /** The identity: the shipped look, fully opaque. A card resolving to it pushes nothing. */
    public static final String IDENTITY_HEX = "#ffffffff";

    /** The shipped look: what every card draws when nothing is authored anywhere. */
    public static final HudCardLook SHIPPED = new HudCardLook(IDENTITY_HEX, 255, 255, 255, 255);

    /** The sources already warned about a malformed value, so a paint every 250ms logs it once. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    @Nonnull private final String hex;
    private final int red;
    private final int green;
    private final int blue;
    private final int alpha;

    private HudCardLook(@Nonnull String hex, int red, int green, int blue, int alpha) {
        this.hex = hex;
        this.red = red;
        this.green = green;
        this.blue = blue;
        this.alpha = alpha;
    }

    // ==================== parsing ====================

    /**
     * Read {@code hex} as a card colour: six or eight hex digits, with or without a leading hash,
     * in either case, surrounding space ignored. Null for a null or blank value (nothing authored)
     * and for anything else (malformed); this never warns, so a caller that wants the warning
     * goes through {@link #authored}.
     */
    @Nullable
    public static HudCardLook parse(@Nullable String hex) {
        if (hex == null) {
            return null;
        }
        String digits = hex.trim().toLowerCase(Locale.ROOT);
        if (digits.startsWith("#")) {
            digits = digits.substring(1);
        }
        if (digits.length() != 6 && digits.length() != 8) {
            return null;
        }
        for (int i = 0; i < digits.length(); i++) {
            if (Character.digit(digits.charAt(i), 16) < 0) {
                return null;
            }
        }
        int red = Integer.parseInt(digits.substring(0, 2), 16);
        int green = Integer.parseInt(digits.substring(2, 4), 16);
        int blue = Integer.parseInt(digits.substring(4, 6), 16);
        int alpha = digits.length() == 8 ? Integer.parseInt(digits.substring(6, 8), 16) : 255;
        return new HudCardLook("#" + digits, red, green, blue, alpha);
    }

    /**
     * The value a file authored, accepted or ignored: the normalised hex when {@code hex} parses,
     * null when nothing was authored, and null with ONE warning naming {@code source} and the
     * value when it is malformed, so the layer below decides instead. An asset's accessor calls
     * this, so every fold above it only ever sees a valid value or none.
     *
     * @param source where the value was authored, as a server owner would find it (a file path,
     *               or the seam a mod filled), for the one line the warning writes
     */
    @Nullable
    public static String authored(@Nullable String hex, @Nonnull String source) {
        if (hex == null || hex.isBlank()) {
            return null;
        }
        HudCardLook parsed = parse(hex);
        if (parsed != null) {
            return parsed.hex();
        }
        if (WARNED.add(source + "=" + hex)) {
            SafeLog.warn("[hud] " + source + " authors the colour '" + hex + "', which is not a #rrggbb or "
                    + "#rrggbbaa hex, so it is ignored and the layer below decides");
        }
        return null;
    }

    /** The look {@code hex} states, or the shipped look for null; a value that does not parse reads as shipped too. */
    @Nonnull
    public static HudCardLook of(@Nullable String hex) {
        HudCardLook parsed = parse(hex);
        return parsed != null ? parsed : SHIPPED;
    }

    /**
     * The look a card draws in: its {@code own} leaf when it has one, else the {@code shared}
     * record's, else the shipped look. Both values are what {@link #authored} accepted, so a
     * malformed leaf has already fallen out of the fold.
     */
    @Nonnull
    public static HudCardLook resolve(@Nullable String own, @Nullable String shared) {
        return of(own != null ? own : shared);
    }

    // ==================== the value ====================

    /** The normalised hex, lower case with its hash, six or eight digits as authored. */
    @Nonnull
    public String hex() {
        return hex;
    }

    /** The alpha, 0 to 255; 255 for a six-digit value. */
    public int alpha() {
        return alpha;
    }

    /** The alpha as an opacity factor, 0 to 1; exactly 1 for a fully opaque value. */
    public double opacity() {
        return alpha / 255.0;
    }

    /** True for the shipped look: white, fully opaque, whichever spelling stated it. */
    public boolean isIdentity() {
        return red == 255 && green == 255 && blue == 255 && alpha == 255;
    }

    /**
     * What to push on the card's {@code .Background.Color}: the hex, or null for the identity,
     * because a multiply by white at full opacity is the shipped look and costs no command.
     */
    @Nullable
    public String cardColor() {
        return isIdentity() ? null : hex;
    }

    // ==================== the derived dressing ====================

    /**
     * A shipped colour dimmed to follow a card: {@code rgbHex}'s own hue, with {@code shippedAlpha}
     * (its alpha in the document, 0 to 1) MULTIPLIED by {@code opacity}, written as the eight-digit
     * hex the retint pushes. The hue is never touched, so a tinted card's dressing keeps its own
     * colours; only how strongly it shows follows the card. Pure.
     *
     * @param rgbHex       a six-digit {@code #rrggbb}, the document's own colour for the element
     * @param shippedAlpha the alpha the document draws it at, 0 to 1
     * @param opacity      the card's opacity factor, 0 to 1
     */
    @Nonnull
    public static String dimmed(@Nonnull String rgbHex, double shippedAlpha, double opacity) {
        HudCardLook rgb = parse(rgbHex);
        if (rgb == null || rgb.hex().length() != 7) {
            throw new IllegalArgumentException("a shipped dressing colour is a six-digit #rrggbb, not '" + rgbHex + "'");
        }
        double factor = Math.max(0.0, Math.min(1.0, shippedAlpha)) * Math.max(0.0, Math.min(1.0, opacity));
        int alphaByte = (int) Math.round(factor * 255.0);
        return String.format(Locale.ROOT, "%s%02x", rgb.hex(), alphaByte);
    }

    @Override
    public String toString() {
        return "HudCardLook{" + hex + "}";
    }

    /** Forget which malformed values have been warned about; for a test. */
    static void resetWarnings() {
        WARNED.clear();
    }
}
