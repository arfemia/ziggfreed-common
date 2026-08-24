package com.ziggfreed.common.objectives.admin;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.objectives.questlist.NpcQuestPageDeps;
import com.ziggfreed.common.util.SafeLog;

/**
 * What a consumer may say about {@link ProgressionAdminPage} without owning it - with ONE seam
 * whose default deliberately breaks the every-default-leaves-a-working-page rule the other page
 * deps keep: the {@link AdminAudience} defaults to DENY. This is an administrative surface, so a
 * bare server where nobody registered an audience gets a page nobody can open, and a consumer
 * grants access from its own gated admin surface (a permission check, an op check) rather than the
 * library guessing one.
 *
 * <ul>
 *   <li>{@link NpcQuestPageDeps.PageTheme} - how the frame is painted; the ONE theme signature
 *       every shared page takes, so a consumer's theme registered once covers this page too.</li>
 *   <li>{@link AdminAudience} - who may OPEN the page. Consulted by
 *       {@link ProgressionAdminPages#open}; DEFAULT DENY, and a throwing audience denies too
 *       (fail-closed, the inverse of a system gate, because here the failure cost of being wrong
 *       is an admin surface shown to a player it was never meant for).</li>
 *   <li>{@link BackHandler} - what the Back button opens, for a consumer routing here from its own
 *       admin hub. Return true ONLY when something else took the screen; the default takes nothing
 *       and the page closes.</li>
 * </ul>
 *
 * <p>Immutable; build one at setup and hand the same instance back on every open.
 */
public final class ProgressionAdminDeps {

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

    /** Every seam at its default: a themable, closeable page NOBODY can open until a consumer fills the audience. */
    public static final ProgressionAdminDeps DEFAULTS = builder().build();

    @Nonnull private final NpcQuestPageDeps.PageTheme theme;
    @Nonnull private final AdminAudience audience;
    @Nonnull private final BackHandler back;

    private ProgressionAdminDeps(@Nonnull Builder builder) {
        this.theme = builder.theme;
        this.audience = builder.audience;
        this.back = builder.back;
    }

    @Nonnull
    public static Builder builder() {
        return new Builder();
    }

    @Nonnull
    public NpcQuestPageDeps.PageTheme theme() {
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
            SafeLog.warn("[progression-admin] the audience seam failed, so the open is refused: "
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
            SafeLog.warn("[progression-admin] the back handler failed, so the page closes: "
                    + t.getMessage());
            return false;
        }
    }

    /** Immutable-by-copy assembly; every knob defaults to the library's own answer. */
    public static final class Builder {

        @Nonnull private NpcQuestPageDeps.PageTheme theme = NpcQuestPageDeps.PLAIN_THEME;
        @Nonnull private AdminAudience audience = DENY_ALL;
        @Nonnull private BackHandler back = CLOSE_PAGE;

        private Builder() {
        }

        @Nonnull
        public Builder theme(@Nullable NpcQuestPageDeps.PageTheme value) {
            this.theme = value != null ? value : NpcQuestPageDeps.PLAIN_THEME;
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
        public ProgressionAdminDeps build() {
            return new ProgressionAdminDeps(this);
        }
    }
}
