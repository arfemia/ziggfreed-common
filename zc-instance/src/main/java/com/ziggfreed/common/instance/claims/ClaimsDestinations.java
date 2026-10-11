package com.ziggfreed.common.instance.claims;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.icon.IconSpec;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.loot.claim.ClaimStore;
import com.ziggfreed.common.ui.menu.MenuEntry;
import com.ziggfreed.common.ui.menu.MenuSlot;
import com.ziggfreed.common.ui.route.Destination;
import com.ziggfreed.common.ui.route.DestinationContext;
import com.ziggfreed.common.ui.route.DestinationType;
import com.ziggfreed.common.ui.route.Destinations;
import com.ziggfreed.common.util.SafeLog;

/**
 * Claims: the library's tab and destination for what a player has earned and not taken yet. {@code "Open":
 * "Claims"} opens the Claims page on the player's own ref, on the rail with this tab selected. The tab shows
 * while the player has a Claim waiting, so it appears with the toast that names it (lead's call); the page opens
 * whenever it is asked for, saying so when nothing waits.
 */
public final class ClaimsDestinations {

    public static final String OWNER = "ziggfreedcommon";
    public static final String TYPE = "Claims";
    public static final Claims CLAIMS = new Claims();

    /** The tab's picture, a base-game item no other tab wears: a dungeon's treasure chest. */
    public static final String ICON = "Furniture_Dungeon_Chest_Epic";

    private ClaimsDestinations() {
    }

    /** Seed the type, at setup, before any asset decodes. */
    public static void register() {
        Destinations.register(OWNER, DestinationType.of(TYPE, Claims.class, Claims.CODEC, ClaimsDestinations::open));
    }

    /** The Claims tab in the shared menu. */
    @Nonnull
    public static MenuEntry entry() {
        return MenuSlot.CLAIMS.entry(ClaimsText.LIBRARY.line("menu.tab"), IconSpec.ofItem(ICON), CLAIMS,
                ClaimsDestinations::waiting);
    }

    /** Whether the player looking has a Claim waiting; false with nobody behind the context. */
    static boolean waiting(@Nonnull DestinationContext viewer) {
        Ref<EntityStore> ref = viewer.playerReference();
        Store<EntityStore> store = viewer.store();
        if (ref == null || store == null || !ref.isValid()) {
            return false;
        }
        PlayerRef playerRef = PlayerAccess.playerRef(store, ref);
        UUID player = playerRef == null ? null : playerRef.getUuid();
        return player != null && ClaimStore.shared().has(player);
    }

    private static boolean open(@Nonnull Claims destination, @Nonnull DestinationContext ctx) {
        return open(ctx.store(), ctx.playerReference(), ctx.player(), null);
    }

    /** Open the Claims page on {@code selectedId} (null for the newest); false when nothing opened. World thread. */
    public static boolean open(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref,
            @Nullable Player player, @Nullable String selectedId) {
        if (store == null || ref == null || player == null) {
            return false;
        }
        PlayerRef playerRef = PlayerAccess.playerRef(store, ref);
        if (playerRef == null) {
            return false;
        }
        try {
            player.getPageManager().openCustomPage(ref, store,
                    new ClaimsPage(playerRef, ClaimsPageDeps.library(), selectedId));
            return true;
        } catch (Throwable t) {
            SafeLog.warn("[claims] the Claims page could not open", t);
            return false;
        }
    }

    /** The Claims page. */
    public static final class Claims extends Destination {

        public static final BuilderCodec<Claims> CODEC = BuilderCodec.builder(Claims.class, Claims::new).build();
    }
}
