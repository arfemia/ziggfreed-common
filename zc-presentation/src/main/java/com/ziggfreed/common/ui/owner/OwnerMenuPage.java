package com.ziggfreed.common.ui.owner;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.owner.OwnerAudience;
import com.ziggfreed.common.util.SafeLog;

/**
 * The owner menu's page ({@link OwnerMenu}): its sections and tiles as {@link OwnerMenuPaint} draws them, for the
 * owner audience only. Open it through {@link OwnerMenu#open}; a consumer keeping an admin page class of its own (one
 * its screens' Back buttons already open) extends this one, naming its section first, and so opens the same menu.
 *
 * <p>Every event but Close asks the owner audience again before it acts, and every exit answers the client: Close
 * closes, a tile that opened a screen has answered, a tile that acted rebuilds the menu with its status line, and
 * anything else (no player, a refusal, a stale index, a screen that would not open) gets an update.
 */
public class OwnerMenuPage extends InteractiveCustomUIPage<OwnerMenuPage.Press> {

    /** What a press round-trips: which of the page's buttons, and which drawn tile. */
    public static final class Press {

        String action;
        String tile;

        public static final BuilderCodec<Press> CODEC = BuilderCodec.builder(Press.class, Press::new)
                .append(new KeyedCodec<>(OwnerMenuPaint.ACTION, Codec.STRING),
                        (press, value, info) -> press.action = value, (press, info) -> press.action)
                .add()
                .append(new KeyedCodec<>(OwnerMenuPaint.TILE, Codec.STRING),
                        (press, value, info) -> press.tile = value, (press, info) -> press.tile)
                .add()
                .build();
    }

    @Nullable private final String first;
    @Nonnull private List<OwnerTile> drawn = List.of();
    @Nullable private Message status;
    private boolean statusError;

    /**
     * The menu for {@code playerRef}, with {@code first}'s section at the front (null for the usual order).
     */
    public OwnerMenuPage(@Nonnull PlayerRef playerRef, @Nullable String first) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Press.CODEC);
        this.first = first;
    }

    @Override
    public final void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
            @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        cmd.append(OwnerMenuPaint.PAGE_TEMPLATE);
        OwnerMenuPaint.bind(events);
        drawn = List.of();
        try {
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null || !OwnerAudience.mayAdminister(store, ref, player)) {
                OwnerMenuPaint.refused(cmd);
                return;
            }
            OwnerViewer viewer = new OwnerViewer(store, ref, player, this.playerRef);
            drawn = OwnerMenuPaint.paint(cmd, events, OwnerMenu.plan(viewer, first), status, statusError);
        } catch (Throwable t) {
            SafeLog.warn("[owner] the owner menu could not paint everything", t);
        }
    }

    @Override
    public final void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull Press press) {
        Player player = ref.isValid() ? store.getComponent(ref, Player.getComponentType()) : null;
        if (player == null || !route(ref, store, player, press)) {
            answer();
        }
    }

    /** Closes, opens, rebuilds or updates for one press; false when it sent nothing. */
    private boolean route(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Player player,
            @Nonnull Press press) {
        if (OwnerMenuPaint.CLOSE.equals(press.action)) {
            player.getPageManager().setPage(ref, store, Page.None);
            return true;
        }
        if (!OwnerAudience.mayAdminister(store, ref, player)) {
            this.playerRef.sendMessage(OwnerText.line("menu.refused"));
            return false;
        }
        if (!OwnerMenuPaint.PRESS.equals(press.action)) {
            return false;
        }
        OwnerTile tile = tileAt(press.tile);
        OwnerViewer viewer = new OwnerViewer(store, ref, player, this.playerRef);
        if (tile == null || !tile.shownTo(viewer)) {
            return false;
        }
        status = null;
        statusError = false;
        OwnerTile.Press result = tile.press(viewer);
        if (result.opened()) {
            return true;
        }
        if (result.acted()) {
            status = result.status();
            statusError = result.error();
            player.getPageManager().openCustomPage(ref, store, this);
            return true;
        }
        UICommandBuilder cmd = new UICommandBuilder();
        OwnerMenuPaint.status(cmd, OwnerText.line("menu.failed"), true);
        this.sendUpdate(cmd, new UIEventBuilder(), false);
        return true;
    }

    /** The tile drawn at the index a press names, or null for a stale or unreadable one. */
    @Nullable
    private OwnerTile tileAt(@Nullable String index) {
        if (index == null) {
            return null;
        }
        try {
            int i = Integer.parseInt(index.trim());
            return i >= 0 && i < drawn.size() ? drawn.get(i) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** An empty update: the client always hears back. */
    private void answer() {
        this.sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
    }
}
