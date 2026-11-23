package com.ziggfreed.common.ui.theme;

import javax.annotation.Nullable;

import com.ziggfreed.common.ui.UiRetint;

/**
 * A generic, mod-agnostic menu/UI THEME palette: a set of named colour slots plus
 * an optional bespoke 9-slice texture set, consumed by a retint painter (see
 * {@link UiRetint}). It is pure DATA - no gate, no
 * entitlement, no consumer policy. A consumer reads the slots it cares about and
 * pushes them through the retint primitive, deriving any unset (null) slot from
 * the base colours, so a 3-colour recolor still paints fully.
 *
 * <p>The three base slots ({@code primary} / {@code accent} / {@code background})
 * are the minimal recolor; the named structural / button / text slots, the
 * {@code textureDir} swap set and the {@code bandTexture} are optional and default null / unset.
 */
public final class Palette {
    public String primary;
    public String accent;
    public String background;

    // Named structural slots. Null when unset; a painter derives from the base colours.
    public String frame;
    public String header;
    public String divider;

    // Named button slots. Null when unset.
    public String buttonNeutral;
    public String buttonPositive;
    public String buttonClaim;
    public String buttonDestructive;

    // Named text slots. Null when unset.
    public String textPrimary;
    public String textMuted;

    // Optional bespoke 9-slice texture set. When textureDir is set / non-empty a
    // painter MAY swap the frame/panel selectors to a bespoke texture set instead
    // of a pure colour tint; null/empty leaves the authored textures in place and
    // the theme stays a pure recolor. textureDir is the directory the swap textures
    // live in, in whatever runtime path form the consumer's painter sends (the
    // runtime texture-PATH form for a Java-sent path is an in-game-verify nuance -
    // see UiRetint.swapPatch). The two borders are the 9-slice insets per texture.
    public String textureDir;
    public int frameBorder;
    public int panelBorder;

    // Optional band texture: the art a band frame (Common/ZigFrames.ui's #Band, the Chapter look every page sits
    // on) swaps to, as a path rooted at Common/UI/Custom/ (a Java-sent texture path's root), for example
    // "Common/MyTheme/BackgroundModal.png". Null leaves the game's own band (Common/BackgroundModal.png) and a theme
    // tints it with its frame colour instead. It is its own slot, never derived from textureDir, so an older theme
    // whose texture set has no band file never draws a missing texture across the screen.
    public String bandTexture;

    /**
     * Construct a base 3-colour palette. The 9-slice borders default to common
     * insets (20 outer frame / 4 inner panel) so a texture-bearing theme that omits
     * explicit borders still slices correctly; a consumer overrides them per its own
     * texture set.
     */
    public Palette(@Nullable String primary, @Nullable String accent, @Nullable String background) {
        this.primary = primary;
        this.accent = accent;
        this.background = background;
        this.frameBorder = 20;
        this.panelBorder = 4;
    }

    /** A slot-for-slot copy, so a holder can hand its palette out without handing out the right to change it. */
    public Palette copy() {
        Palette copy = new Palette(primary, accent, background);
        copy.frame = frame;
        copy.header = header;
        copy.divider = divider;
        copy.buttonNeutral = buttonNeutral;
        copy.buttonPositive = buttonPositive;
        copy.buttonClaim = buttonClaim;
        copy.buttonDestructive = buttonDestructive;
        copy.textPrimary = textPrimary;
        copy.textMuted = textMuted;
        copy.textureDir = textureDir;
        copy.frameBorder = frameBorder;
        copy.panelBorder = panelBorder;
        copy.bandTexture = bandTexture;
        return copy;
    }

    /** True only when this theme declares a bespoke texture set to swap in. */
    public boolean hasTextures() {
        return textureDir != null && !textureDir.isEmpty();
    }

    /** True only when this theme names its own band art ({@link #bandTexture}). */
    public boolean hasBandTexture() {
        return bandTexture != null && !bandTexture.isBlank();
    }
}
