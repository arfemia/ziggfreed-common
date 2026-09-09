package com.ziggfreed.common.ui.hud.settings;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * What a consumer may say about {@link HudSettingsPage} without owning it. The page itself is
 * every player's: anyone may open it and change their own HUD. What a consumer decides is who may
 * ALSO see the Server tab, which writes the owner file for everyone, how the frame is painted, and
 * where Back goes.
 *
 * <ul>
 *   <li>{@link PageTheme} - how the frame is painted. Declared here rather than borrowed, because
 *       this module sits BELOW the ones that carry the other page-theme seams and may never import
 *       them; the signature is deliberately identical, so a consumer hands the same lambda to
 *       every one of them.</li>
 *   <li>{@link AdminAudience} - who may see the Server tab. DEFAULT DENY, and a throwing audience
 *       denies too: the library cannot know what "is an admin" means on a given server, and the
 *       failure cost of guessing is an owner file rewritten by a player.</li>
 *   <li>{@link BackHandler} - what the Back button opens, for a consumer routing here from its own
 *       settings menu. Return true ONLY when something else took the screen; the default takes
 *       nothing and the page closes.</li>
 * </ul>
 *
 * <p>Immutable; build one at setup and hand the same instance back on every open.
 */
public final class HudSettingsDeps {

    /**
     * How the page's root template reaches the screen. A consumer with a theme appends it and
     * retints the frame in one call; the default simply appends it.
     */
    @FunctionalInterface
    public interface PageTheme {

        void appendThemed(@Nonnull UICommandBuilder cmd, @Nonnull String template,
                @Nonnull String... frameSelectors);
    }

    /** Append the template and paint nothing: the look a bare server gets. */
    public static final PageTheme PLAIN_THEME = (cmd, template, frameSelectors) -> cmd.append(template);

    /** Who may see the Server tab. Asked per open, about the opening player. */
    @FunctionalInterface
    public interface AdminAudience {

        boolean mayAdminister(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                @Nonnull Player player);
    }

    /**
     * What follows the Back button: a consumer's own settings menu, or nothing.
     *
     * <p>Return true ONLY when something else took the screen; false closes the page.
     */
    @FunctionalInterface
    public interface BackHandler {

        boolean back(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                @Nonnull Player player);
    }

    /** Nobody sees the Server tab: the honest default for a surface nothing has gated yet. */
    public static final AdminAudience DENY_ALL = (store, ref, player) -> false;

    /** Back takes nothing, so the page closes. */
    public static final BackHandler CLOSE_PAGE = (store, ref, player) -> false;

    /** Every seam at its default: a plain, closeable page whose Server tab nobody sees. */
    public static final HudSettingsDeps DEFAULTS = builder().build();

    @Nonnull private final PageTheme theme;
    @Nonnull private final AdminAudience audience;
    @Nonnull private final BackHandler back;

    private HudSettingsDeps(@Nonnull Builder builder) {
        this.theme = builder.theme;
        this.audience = builder.audience;
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
    public AdminAudience audience() {
        return audience;
    }

    @Nonnull
    public BackHandler back() {
        return back;
    }

    /** May {@code player} see the Server tab? Guarded FAIL-CLOSED: an audience that throws denies. */
    public boolean mayAdministerGuarded(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull Player player) {
        try {
            return audience.mayAdminister(store, ref, player);
        } catch (Throwable t) {
            SafeLog.warn("[hud-settings] the audience seam failed, so the Server tab is withheld: "
                    + t.getMessage());
            return false;
        }
    }

    /** Run the Back handler, guarded: a handler that throws takes nothing and the page closes. */
    public boolean backGuarded(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull Player player) {
        try {
            return back.back(store, ref, player);
        } catch (Throwable t) {
            SafeLog.warn("[hud-settings] the back handler failed, so the page closes: " + t.getMessage());
            return false;
        }
    }

    /** Immutable-by-copy assembly; every knob defaults to the library's own answer. */
    public static final class Builder {

        @Nonnull private PageTheme theme = PLAIN_THEME;
        @Nonnull private AdminAudience audience = DENY_ALL;
        @Nonnull private BackHandler back = CLOSE_PAGE;

        private Builder() {
        }

        @Nonnull
        public Builder theme(@Nullable PageTheme value) {
            this.theme = value != null ? value : PLAIN_THEME;
            return this;
        }

        @Nonnull
        public Builder audience(@Nullable AdminAudience value) {
            this.audience = value != null ? value : DENY_ALL;
            return this;
        }

        @Nonnull
        public Builder back(@Nullable BackHandler value) {
            this.back = value != null ? value : CLOSE_PAGE;
            return this;
        }

        @Nonnull
        public HudSettingsDeps build() {
            return new HudSettingsDeps(this);
        }
    }
}
