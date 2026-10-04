package com.ziggfreed.common.objectives.title.page;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * What a consumer may say about the title picker without owning it: how the frame is painted and
 * where Back goes. The page itself is every player's. Immutable; build one at setup.
 */
public final class TitlePickerDeps {

    /** How the page's template reaches the screen; the signature every page-theme seam in the family shares. */
    @FunctionalInterface
    public interface PageTheme {

        void appendThemed(@Nonnull UICommandBuilder cmd, @Nonnull String template,
                @Nonnull String... frameSelectors);
    }

    /** Append the template and paint nothing: the look a bare server gets. */
    public static final PageTheme PLAIN_THEME = (cmd, template, frameSelectors) -> cmd.append(template);

    /** What follows Back. Return true ONLY when something else took the screen; false closes the page. */
    @FunctionalInterface
    public interface BackHandler {

        boolean back(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull Player player);
    }

    /** Back takes nothing, so the page closes. */
    public static final BackHandler CLOSE_PAGE = (store, ref, player) -> false;

    /** Every seam at its default. */
    public static final TitlePickerDeps DEFAULTS = builder().build();

    @Nonnull private final PageTheme theme;
    @Nonnull private final BackHandler back;

    private TitlePickerDeps(@Nonnull Builder builder) {
        this.theme = builder.theme;
        this.back = builder.back;
    }

    @Nonnull
    public static Builder builder() {
        return new Builder();
    }

    @Nonnull
    public PageTheme theme() {
        return theme;
    }

    @Nonnull
    public BackHandler back() {
        return back;
    }

    /** Run the Back handler, guarded: one that throws takes nothing and the page closes. */
    public boolean backGuarded(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull Player player) {
        try {
            return back.back(store, ref, player);
        } catch (Throwable t) {
            SafeLog.warn("[title] the picker's back handler failed, so the page closes: " + t.getMessage());
            return false;
        }
    }

    /** Every knob defaults to the library's own answer; null restores it. */
    public static final class Builder {

        @Nonnull private PageTheme theme = PLAIN_THEME;
        @Nonnull private BackHandler back = CLOSE_PAGE;

        private Builder() {
        }

        @Nonnull
        public Builder theme(@Nullable PageTheme value) {
            this.theme = value != null ? value : PLAIN_THEME;
            return this;
        }

        @Nonnull
        public Builder back(@Nullable BackHandler value) {
            this.back = value != null ? value : CLOSE_PAGE;
            return this;
        }

        @Nonnull
        public TitlePickerDeps build() {
            return new TitlePickerDeps(this);
        }
    }
}
