package com.ziggfreed.common.objectives.admin;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.objectives.questlist.NpcQuestPageDeps;
import com.ziggfreed.common.owner.OwnerAudience;
import com.ziggfreed.common.ui.owner.OwnerMenu;
import com.ziggfreed.common.util.SafeLog;

/**
 * What a consumer may say about {@link ProgressionAdminPage} without owning it. Its
 * {@link AdminAudience} defaults to the library's owner audience (zc-core's {@code OwnerAudience}:
 * a consumer's own admin rule where one filled it, else a holder of {@code /zigadmin}'s node), never
 * to everyone: this is an administrative surface, and the owner menu ({@code /zigadmin}) opens it on
 * a server with no consumer.
 *
 * <ul>
 *   <li>{@link NpcQuestPageDeps.PageTheme} - how the frame is painted; the ONE theme signature
 *       every shared page takes, so a consumer's theme registered once covers this page too.</li>
 *   <li>{@link AdminAudience} - who may OPEN the page. Consulted by
 *       {@link ProgressionAdminPages#open}; the owner audience by default ({@link #OWNERS}), and a
 *       throwing audience denies (fail-closed, the inverse of a system gate, because here the failure
 *       cost of being wrong is an admin surface shown to a player it was never meant for).</li>
 *   <li>{@link BackHandler} - what the Back button opens, for a consumer routing here from its own
 *       admin hub. Return true ONLY when something else took the screen; the default opens the owner
 *       menu ({@link #TO_OWNER_MENU}), and the page closes when that does not open.</li>
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

    /** Nobody may open the page. */
    public static final AdminAudience DENY_ALL = (store, ref, player) -> false;

    /** The library's owner audience (zc-core's {@code OwnerAudience}): the default. */
    public static final AdminAudience OWNERS = OwnerAudience::mayAdminister;

    /** Back takes nothing, so the page closes. */
    public static final BackHandler CLOSE_PAGE = (store, ref, player) -> false;

    /** Back opens the owner menu ({@code /zigadmin}): the default. */
    public static final BackHandler TO_OWNER_MENU = (store, ref, player) -> OwnerMenu.open(store, ref, player, null);

    /** Every seam at its default: a themable page the owner audience opens, whose Back returns to the owner menu. */
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
        @Nonnull private AdminAudience audience = OWNERS;
        @Nonnull private BackHandler back = TO_OWNER_MENU;

        private Builder() {
        }

        @Nonnull
        public Builder theme(@Nullable NpcQuestPageDeps.PageTheme value) {
            this.theme = value != null ? value : NpcQuestPageDeps.PLAIN_THEME;
            return this;
        }

        @Nonnull
        public Builder audience(@Nullable AdminAudience value) {
            this.audience = value != null ? value : OWNERS;
            return this;
        }

        @Nonnull
        public Builder back(@Nullable BackHandler value) {
            this.back = value != null ? value : TO_OWNER_MENU;
            return this;
        }

        @Nonnull
        public ProgressionAdminDeps build() {
            return new ProgressionAdminDeps(this);
        }
    }
}
