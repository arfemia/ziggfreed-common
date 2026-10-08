package com.ziggfreed.common.ui.menu;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.ziggfreed.common.ui.route.Destination;
import com.ziggfreed.common.ui.theme.Palette;

/**
 * What a consumer says about the shared menu, in one call ({@link ZigMenu#consumer}): its section of
 * tabs above the library's four, where {@code /ziggui} lands, how the rail's branding is painted, the ONE
 * {@link Palette} the frame and the rail read every colour and texture from, and, for a consumer that
 * paints the frame by its own policy, how a page's template reaches the screen. Every leaf defaults to the
 * library's own answer, so {@link #EMPTY} is a working menu of the four slots in the default palette.
 */
public final class MenuDeps {

    /**
     * How a page's template reaches the screen when a consumer paints the frame itself (append, then
     * retint the frame and the named panels). The same signature as every other page-theme seam in the
     * library, so one lambda serves them all. Unset (null), the library appends the template and paints
     * the frame from {@link #palette()} ({@code MenuFrameRetint}).
     */
    @FunctionalInterface
    public interface PageTheme {

        void appendThemed(@Nonnull UICommandBuilder cmd, @Nonnull String template,
                @Nonnull String... frameSelectors);
    }

    /**
     * Paints branding into the rail's hosts ({@code #BrandingContainerLeft}, {@code #BrandingServerName},
     * {@code #BrandingDescription}; the name and description wrap). {@code titleRow} is true when the page
     * also declares the header row's four hosts: {@code #TitleContainer}, {@code #BrandingLogo} (an
     * {@code AssetImage} in the logo's 260:97 shape, hidden with a blank fallback, for the painter to give an
     * {@code .AssetPath} and show), {@code #PanelTitle} (the page writes its own title there first, so a
     * server name written over it wins) and {@code #BrandingDescriptionRight} (hidden). A painter writes those
     * only then, since a selector the page lacks disconnects the player.
     */
    @FunctionalInterface
    public interface BrandingPainter {

        void paint(@Nonnull UICommandBuilder cmd, boolean titleRow);
    }

    public static final BrandingPainter NO_BRANDING = (cmd, titleRow) -> {
    };

    /** The library's own menu: the four slots, no section, no landing, the default palette. */
    public static final MenuDeps EMPTY = builder().build();

    @Nullable private final PageTheme theme;
    @Nonnull private final Palette palette;
    @Nullable private final MenuSection section;
    @Nullable private final Destination landing;
    @Nonnull private final BrandingPainter branding;

    private MenuDeps(@Nonnull Builder builder) {
        this.theme = builder.theme;
        this.palette = builder.palette.copy();
        this.section = builder.section;
        this.landing = builder.landing;
        this.branding = builder.branding;
    }

    @Nonnull
    public static Builder builder() {
        return new Builder();
    }

    /** A consumer's own frame paint, or null for the library's paint from {@link #palette()}. */
    @Nullable
    public PageTheme theme() {
        return theme;
    }

    /**
     * The one palette the rail (and, with no {@link #theme()}, the frame) reads every colour and texture from,
     * as a fresh copy: {@link Palette}'s slots are public fields, so handing out the held instance would let
     * one caller recolour the menu for everyone.
     */
    @Nonnull
    public Palette palette() {
        return palette.copy();
    }

    @Nullable
    public MenuSection section() {
        return section;
    }

    @Nullable
    public Destination landing() {
        return landing;
    }

    @Nonnull
    public BrandingPainter branding() {
        return branding;
    }

    /** Immutable-by-copy assembly; a null leaf falls back to the library's answer. */
    public static final class Builder {

        @Nullable private PageTheme theme;
        @Nonnull private Palette palette = MenuPalette.defaults();
        @Nullable private MenuSection section;
        @Nullable private Destination landing;
        @Nonnull private BrandingPainter branding = NO_BRANDING;

        private Builder() {
        }

        @Nonnull
        public Builder theme(@Nullable PageTheme value) {
            this.theme = value;
            return this;
        }

        @Nonnull
        public Builder palette(@Nullable Palette value) {
            this.palette = value != null ? value : MenuPalette.defaults();
            return this;
        }

        @Nonnull
        public Builder section(@Nullable MenuSection value) {
            this.section = value;
            return this;
        }

        @Nonnull
        public Builder landing(@Nullable Destination value) {
            this.landing = value;
            return this;
        }

        @Nonnull
        public Builder branding(@Nullable BrandingPainter value) {
            this.branding = value != null ? value : NO_BRANDING;
            return this;
        }

        @Nonnull
        public MenuDeps build() {
            return new MenuDeps(this);
        }
    }
}
