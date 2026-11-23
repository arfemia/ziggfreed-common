package com.ziggfreed.common.owner;

import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.util.SafeLog;

/**
 * The library's one answer to "may this player change the server's settings": who sees the owner menu
 * ({@code /zigadmin}), an owner form, and the library's own owner screens (the progression switches, the use gates,
 * the NPC placements, the server's HUD layout) unless a consumer gave one of those its own audience.
 *
 * <p><b>The default is a permission node.</b> With no consumer filling the seam, a player may administer the server
 * when they hold {@link #NODE}, the node the engine derives for {@code /zigadmin} itself, so an operator (who holds
 * every node) and whoever the owner grants that one node are owners, and nobody else is. A consumer that already
 * knows who its admins are (an op check, its own admin node) fills the seam once at setup ({@link #fill}); its
 * answer then stands everywhere the default stood, so no surface is looser than the consumer's rule.
 *
 * <p>Asked on every open AND on every event of an owner screen: a page's build only hides controls, and a client can
 * send any event. Fail-closed: an audience that throws answers no. World thread.
 */
public final class OwnerAudience {

    /** Who may administer the server, asked about one player at a time. */
    @FunctionalInterface
    public interface Audience {

        boolean mayAdminister(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
                @Nonnull Player player);
    }

    /**
     * The node the default audience asks for: the engine's own node for {@code /zigadmin}
     * ({@code <plugin base permission>.command.zigadmin}), so whoever may run the command is an owner.
     */
    public static final String NODE = "ziggfreed.ziggfreedcommon.command.zigadmin";

    /** The library's default: the player holds {@link #NODE} (an operator holds every node). */
    public static final Audience NODE_HOLDERS = (store, ref, player) -> {
        PlayerRef playerRef = PlayerAccess.playerRef(player);
        return playerRef != null && playerRef.hasPermission(NODE);
    };

    private static final AtomicReference<Audience> FILLED = new AtomicReference<>();

    private OwnerAudience() {
    }

    /**
     * Say who administers this server, once from a consumer's setup; null goes back to the library's default
     * ({@link #NODE_HOLDERS}). The last call wins.
     */
    public static void fill(@Nullable Audience audience) {
        FILLED.set(audience);
    }

    /** Has a consumer filled the seam? */
    public static boolean filled() {
        return FILLED.get() != null;
    }

    /**
     * May {@code player} administer the server right now: the consumer's audience when one filled the seam, else
     * {@link #NODE_HOLDERS}. Fail-closed: an audience that throws answers false, with one warning.
     */
    public static boolean mayAdminister(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull Player player) {
        Audience filled = FILLED.get();
        Audience asked = filled != null ? filled : NODE_HOLDERS;
        try {
            return asked.mayAdminister(store, ref, player);
        } catch (Throwable t) {
            SafeLog.warn("[owner] the owner audience failed, so the screen is refused: " + t.getMessage());
            return false;
        }
    }
}
