package com.ziggfreed.common.npc.placement.admin;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.ziggfreed.common.util.SafeLog;

/**
 * What a consumer may say about {@link NpcPlacementAdminPage} without owning it - with ONE seam
 * whose default deliberately breaks the every-default-leaves-a-working-page rule the other page
 * deps keep: the {@link AdminAudience} defaults to DENY. This is an administrative surface that
 * WRITES (it switches placements off and stands new ones up), so a bare server where nobody
 * registered an audience gets a page nobody can open, and a consumer grants access from its own
 * gated admin surface rather than the library guessing what "is an admin" means here.
 *
 * <ul>
 *   <li>{@link PageTheme} - how the frame is painted. Declared here rather than borrowed, because
 *       this module sits BELOW the ones that carry the other page-theme seams and may never import
 *       them; the signature is deliberately identical, so a consumer hands the same lambda to all
 *       three.</li>
 *   <li>{@link AdminAudience} - who may OPEN the page. Consulted by
 *       {@link NpcPlacementAdminPages#open}; DEFAULT DENY, and a throwing audience denies too
 *       (fail-closed, because the failure cost of being wrong is an admin surface, and a writing
 *       one, shown to a player it was never meant for).</li>
 *   <li>{@link BackHandler} - what the Back button opens, for a consumer routing here from its own
 *       admin hub. Return true ONLY when something else took the screen; the default takes nothing
 *       and the page closes.</li>
 * </ul>
 *
 * <p>Immutable; build one at setup and hand the same instance back on every open.
 */
public final class NpcPlacementAdminDeps {

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

    /** Who may open the admin page. Asked per open attempt, about the opening player. */
    @FunctionalInterface
    public interface AdminAudience {

        boolean mayOpen(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                @Nonnull Player player);
    }

    /**
     * What follows the Back button: a consumer's own admin hub, or nothing.
     *
     * <p>Return true ONLY when something else took the screen; false closes the page.
     */
    @FunctionalInterface
    public interface BackHandler {

        boolean back(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                @Nonnull Player player);
    }

    /** Nobody may open the page: the honest default for an admin surface nothing has gated yet. */
    public static final AdminAudience DENY_ALL = (store, ref, player) -> false;

    /** Back takes nothing, so the page closes. */
    public static final BackHandler CLOSE_PAGE = (store, ref, player) -> false;

    /** Every seam at its default: a themable, closeable page NOBODY can open. */
    public static final NpcPlacementAdminDeps DEFAULTS = builder().build();

    @Nonnull private final PageTheme theme;
    @Nonnull private final AdminAudience audience;
    @Nonnull private final BackHandler back;

    private NpcPlacementAdminDeps(@Nonnull Builder builder) {
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

    /** May {@code player} open the page? Guarded FAIL-CLOSED: an audience that throws denies. */
    public boolean mayOpenGuarded(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull Player player) {
        try {
            return audience.mayOpen(store, ref, player);
        } catch (Throwable t) {
            SafeLog.warn("[placement-admin] the audience seam failed, so the open is refused: "
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
            SafeLog.warn("[placement-admin] the back handler failed, so the page closes: "
                    + t.getMessage());
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
        public NpcPlacementAdminDeps build() {
            return new NpcPlacementAdminDeps(this);
        }
    }
}
