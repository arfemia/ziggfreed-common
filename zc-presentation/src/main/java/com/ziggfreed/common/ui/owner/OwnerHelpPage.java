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
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.owner.OwnerAudience;
import com.ziggfreed.common.ui.menu.ZigMenu;
import com.ziggfreed.common.util.SafeLog;

/**
 * A tile's help: every help topic the tile carries ({@link OwnerMenu#topics}), as {@link OwnerHelpPaint} draws them, for
 * the owner audience only. It opens from the menu's Help ({@link OwnerMenuPage}) or from a consumer's own screen
 * ({@link OwnerHelp#open}); Back returns to the menu's Help, Open opens the tile's screen through the tile's own opener,
 * as a press on the menu would.
 *
 * <p>Every event but Close asks the owner audience again before it acts ({@link OwnerPageGuard}, which closes the page on
 * a refusal), the tile is found again and its own rule asked again before Open, and every exit answers the client:
 * Close closes, Back opens the menu, Open opens the screen or says it did not, anything else gets an update.
 */
public final class OwnerHelpPage extends InteractiveCustomUIPage<OwnerHelpPage.Press> {

    /** What a press round-trips: which of the page's buttons. */
    public static final class Press {

        String action;

        public static final BuilderCodec<Press> CODEC = BuilderCodec.builder(Press.class, Press::new)
                .append(new KeyedCodec<>(OwnerHelpPaint.ACTION, Codec.STRING),
                        (press, value, info) -> press.action = value, (press, info) -> press.action)
                .add()
                .build();
    }

    @Nonnull private final String tileId;
    @Nullable private final String first;

    /** The help of the tile {@code tileId}, its Back returning to the menu's Help with {@code first}'s section first. */
    OwnerHelpPage(@Nonnull PlayerRef playerRef, @Nonnull String tileId, @Nullable String first) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Press.CODEC);
        this.tileId = tileId;
        this.first = first;
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store) {
        // The menu theme's paint, the way every framed page of the family takes it (a consumer's theme, else the palette).
        ZigMenu.appendThemed(cmd, OwnerHelpPaint.PAGE_TEMPLATE);
        OwnerHelpPaint.bind(events);
        try {
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null || !OwnerAudience.mayAdminister(store, ref, player)) {
                OwnerHelpPaint.refused(cmd, OwnerText.line("menu.refused"));
                return;
            }
            OwnerTile tile = shownTile(store, ref, player);
            List<OwnerHelp> topics = tile == null ? List.of() : OwnerMenu.topics(tile);
            if (tile == null || topics.isEmpty()) {
                OwnerHelpPaint.refused(cmd, OwnerText.line("help.missing"));
                return;
            }
            OwnerHelpPaint.paint(cmd, tile.title(), tile.line(), topics, tile.opens());
        } catch (Throwable t) {
            SafeLog.warn("[owner] the help of '" + tileId + "' could not paint everything", t);
        }
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull Press press) {
        Player player = ref.isValid() ? store.getComponent(ref, Player.getComponentType()) : null;
        if (player == null || !route(ref, store, player, press)) {
            answer();
        }
    }

    /** Closes, opens or updates for one press; false when it sent nothing. */
    private boolean route(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Player player,
            @Nonnull Press press) {
        String action = press.action == null ? "" : press.action;
        if (OwnerHelpPaint.CLOSE.equals(action)) {
            player.getPageManager().setPage(ref, store, Page.None);
            return true;
        }
        if (!OwnerPageGuard.stillAllowed(store, ref, player, this.playerRef, OwnerAudience::mayAdminister)) {
            return true;
        }
        if (OwnerHelpPaint.BACK.equals(action)) {
            return OwnerMenu.open(store, ref, player, first, true);
        }
        if (!OwnerHelpPaint.OPEN.equals(action)) {
            return false;
        }
        OwnerTile tile = shownTile(store, ref, player);
        if (tile == null || !tile.opens()) {
            return false;
        }
        OwnerTile.Press result = tile.press(new OwnerViewer(store, ref, player, this.playerRef));
        if (result.opened()) {
            return true;
        }
        UICommandBuilder cmd = new UICommandBuilder();
        OwnerHelpPaint.status(cmd, OwnerText.line("menu.failed"), true);
        this.sendUpdate(cmd, new UIEventBuilder(), false);
        return true;
    }

    /** The tile this help is of, as the menu draws it now, or null when it is gone or its own rule hides it. */
    @Nullable
    private OwnerTile shownTile(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull Player player) {
        OwnerTile tile = OwnerMenu.find(tileId, first);
        return tile != null && tile.shownTo(new OwnerViewer(store, ref, player, this.playerRef)) ? tile : null;
    }

    /** An empty update: the client always hears back. */
    private void answer() {
        this.sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
    }
}
