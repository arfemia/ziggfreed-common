package com.ziggfreed.common.ui.kit;

import javax.annotation.Nonnull;

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
import com.ziggfreed.common.util.SafeLog;

/**
 * The kit gallery, an owner's page ({@code /zigkit gallery}, {@link ZigKitCommand}): every piece of the page kit in
 * every state, with sample words, on the band ({@code @ZigDecoratedFrame}), and as its second view the boxed panel
 * a panel or a HUD sits on ({@code @ZigPanelFrame}). It is where the kit's look is checked in game, and changes
 * nothing. What it shows is {@link KitGallery}'s. Every exit of {@link #handleDataEvent} answers the client: Close
 * closes, the two view buttons open the page again on the other view, anything else gets an empty update.
 */
final class KitGalleryPage extends InteractiveCustomUIPage<KitGalleryPage.Press> {

    private static final String LOG = "[kit-gallery] ";

    /** The two views. */
    enum View {
        BAND,
        PANEL
    }

    /** What a press round-trips: which of the page's buttons. */
    static final class Press {

        String action;

        static final BuilderCodec<Press> CODEC = BuilderCodec.builder(Press.class, Press::new)
                .append(new KeyedCodec<>(KitGallery.ACTION, Codec.STRING),
                        (press, value, info) -> press.action = value,
                        (press, info) -> press.action)
                .add()
                .build();
    }

    private final View view;

    KitGalleryPage(@Nonnull PlayerRef playerRef, @Nonnull View view) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, Press.CODEC);
        this.view = view;
    }

    /**
     * Open the gallery on {@code view} for the player at {@code ref}, replacing whatever page is open, on their world
     * thread. False when the entity is no player or the open threw.
     */
    static boolean open(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull PlayerRef playerRef, @Nonnull View view) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return false;
        }
        try {
            player.getPageManager().openCustomPage(ref, store, new KitGalleryPage(playerRef, view));
            return true;
        } catch (Throwable t) {
            SafeLog.warn(LOG + "the kit gallery failed to open", t);
            return false;
        }
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store) {
        if (view == View.PANEL) {
            cmd.append(KitGallery.PANEL_TEMPLATE);
            KitGallery.bindPanel(events);
            return;
        }
        cmd.append(KitGallery.PAGE_TEMPLATE);
        KitGallery.bindBand(events);
        try {
            KitGallery.paint(cmd, events, this.playerRef);
        } catch (Throwable t) {
            SafeLog.warn(LOG + "the kit gallery could not paint everything", t);
        }
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull Press press) {
        String action = press.action == null ? "" : press.action;
        Player player = ref.isValid() ? store.getComponent(ref, Player.getComponentType()) : null;
        if (player == null) {
            this.sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
            return;
        }
        switch (action) {
            case KitGallery.CLOSE -> player.getPageManager().setPage(ref, store, Page.None);
            case KitGallery.PANEL -> reopen(ref, store, View.PANEL);
            case KitGallery.BAND -> reopen(ref, store, View.BAND);
            default -> this.sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
        }
    }

    /** Open the page again on {@code next}; an open that fails is answered with an empty update. */
    private void reopen(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull View next) {
        if (!open(ref, store, this.playerRef, next)) {
            this.sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
        }
    }
}
