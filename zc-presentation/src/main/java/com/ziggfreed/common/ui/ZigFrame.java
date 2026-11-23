package com.ziggfreed.common.ui;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

/**
 * The band frames' contract in Java: the ids {@code Common/ZigFrames.ui}'s {@code @ZigDecoratedFrame} and
 * {@code @ZigMenuFrame} declare, and the one way a theme paints the band. A document cannot read a Java constant, so
 * the document spells the same names and numbers and {@code ZigFrameDocumentTest} holds the two together.
 *
 * <p><b>The band.</b> Both frames are the game's Chapter look, the game's own server modal
 * ({@code Common/IntroModal.ui}): {@link #BLUR} (a {@code SceneBlur}) and {@link #SCRIM} cover the whole screen, then
 * the band, the game's {@code Common/BackgroundModal.png} with its runic head and darker foot kept whole
 * ({@code VerticalBorder} {@link #BAND_HEAD}, {@code HorizontalBorder} 4). All of it is drawn by children reaching
 * past the frame's own box, since the client clips no child to its parent (vanilla's
 * {@code InGame/GamePageNavigation.ui} stretches its bar 74 past both sides the same way), so a page's own
 * {@code Anchor} on the frame keeps its meaning: its box is the content column, and the band reaches
 * {@link #BAND_HEAD} above and below it. The band is three pieces: {@link #BAND}, {@link #BAND_WIDTH} wide and centred
 * on the box, so on a screen that wide the texture draws at its own size, its head's centre tab and its foot's
 * knotwork unstretched; and {@link #BAND_LEFT} and {@link #BAND_RIGHT}, {@link #BAND_REACH} wide on either side of
 * it, which carry the band's plain body on to the edges of a wider screen (their own centres, where the texture's
 * ornament falls, sit far off any screen). Every piece carries a {@code Background}, so a theme tints all three.
 *
 * <p><b>The body.</b> {@link #CONTENT} is the page's column, its {@code Background} clear (a tint or a colour pushed
 * there never lands on an unset one), and it holds {@link #HERO} first, hidden until a painter fills it: an emblem,
 * the gold title under the game's text gradient, a copper tagline, the game's rule and a line under it.
 * {@link #DECOR_TOP} and {@link #DECOR_BOTTOM} are declared but hidden, since the band has its own head and foot.
 * {@link #CLOSE} sits in the band's head over the box's top right.
 *
 * <p>A panel or a HUD takes {@code @ZigPanelFrame} instead: the boxed frame, with no blur, scrim or band, so it never
 * dims the world during play.
 */
public final class ZigFrame {

    /** The blur over the world, under the whole screen. */
    public static final String BLUR = "#Blur";

    /** The scrim over the blurred world ({@code @ZigBandScrim}). */
    public static final String SCRIM = "#Scrim";

    /** The band's middle piece, centred on the box: the retint target. */
    public static final String BAND = "#Band";

    /** The band's plain body carried on past {@link #BAND}'s left edge. */
    public static final String BAND_LEFT = "#BandLeft";

    /** The band's plain body carried on past {@link #BAND}'s right edge. */
    public static final String BAND_RIGHT = "#BandRight";

    /** Every piece of the band, each carrying the band's {@code Background}: what a theme paints. */
    public static final List<String> BAND_PIECES = List.of(BAND, BAND_LEFT, BAND_RIGHT);

    /** The page's column, its {@code Background} clear. */
    public static final String CONTENT = "#Content";

    /** The hero at the top of the column, hidden until a painter fills it. */
    public static final String HERO = "#Hero";

    /** The hero's emblem row (hidden without an emblem) and the picture in it, an {@code AssetImage}. */
    public static final String HERO_EMBLEM = "#HeroEmblem";
    public static final String HERO_EMBLEM_ART = "#HeroEmblemArt";

    /** The hero's title, the display title under the game's gold text gradient. */
    public static final String HERO_HEADLINE = "#HeroHeadline";

    /** The copper line under the title, hidden until filled. */
    public static final String HERO_TAGLINE = "#HeroTagline";

    /** The game's rule under the title and tagline. */
    public static final String HERO_RULE = "#HeroRule";

    /** The line under the rule, hidden until filled. */
    public static final String HERO_SUB = "#HeroSub";

    /** The boxed frame's ornaments: declared on the band frames, hidden there. */
    public static final String DECOR_TOP = "#DecorTop";
    public static final String DECOR_BOTTOM = "#DecorBottom";

    /** The close button, shown by the frame's {@code @CloseButton}. */
    public static final String CLOSE = "#CloseButton";

    /** The band's texture, as the frame document spells it beside it. */
    public static final String BAND_TEXTURE = "../Common/BackgroundModal.png";

    /** The band's head above the box and its foot below it, each kept unstretched: its {@code VerticalBorder}. */
    public static final int BAND_HEAD = 78;

    /** The band's middle piece, centred on the box: the texture's own width, the screen's at 1920 wide. */
    public static final int BAND_WIDTH = 1920;

    /** How far each side piece carries the band past the middle one. */
    public static final int BAND_REACH = 6000;

    /** How far the blur and the scrim reach past the box on every side. */
    public static final int SCREEN_REACH = 8000;

    private ZigFrame() {
    }

    /**
     * Tint every band piece by {@code hex}, a {@code #rrggbb} or {@code #rrggbbaa} multiplied over the band's texture
     * ({@link UiRetint#retintColor}); a no-op for a null or non-hex value, so the band keeps its authored look.
     */
    public static void tintBand(@Nonnull UICommandBuilder cmd, @Nullable String hex) {
        for (String piece : BAND_PIECES) {
            UiRetint.retintColor(cmd, piece, hex);
        }
    }

    /**
     * Swap every band piece to {@code texturePath} (rooted at {@code Common/UI/Custom/}, a Java-sent path's root),
     * whole patches with the band's head kept ({@link #BAND_HEAD} on every side, the one border a runtime patch
     * carries), under an optional {@code #rrggbb} tint. Only for a theme that names its own band art
     * ({@code Palette.bandTexture}); a blank path is skipped, so the band keeps the game's own.
     */
    public static void swapBand(@Nonnull UICommandBuilder cmd, @Nullable String texturePath, @Nullable String tintHex) {
        if (texturePath == null || texturePath.isBlank()) {
            return;
        }
        for (String piece : BAND_PIECES) {
            UiRetint.swapPatch(cmd, piece, texturePath.trim(), BAND_HEAD, tintHex);
        }
    }
}
