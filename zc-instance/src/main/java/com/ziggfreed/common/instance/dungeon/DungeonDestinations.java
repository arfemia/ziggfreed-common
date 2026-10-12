package com.ziggfreed.common.instance.dungeon;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.PageManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.ui.route.Destination;
import com.ziggfreed.common.ui.route.DestinationContext;
import com.ziggfreed.common.ui.route.DestinationType;
import com.ziggfreed.common.ui.route.Destinations;
import com.ziggfreed.common.util.SafeLog;

/**
 * The {@code Dungeon} destination: {@code "Open": {"Type": "Dungeon", "Dungeon": "<id>"}} sends the player into
 * that dungeon with their party, through the same entry every way in takes ({@link DungeonEntry#enterWithParty}),
 * so a placed character's press-F ({@code "Interact": {"Open": ...}}, which cannot run an interaction chain) or a
 * conversation's open action can be a dungeon's gate. A refusal tells the player why ({@link DungeonRefusals}).
 *
 * <p><b>The entry runs on a world task of its own</b>, after the moment that asked for it: a press-F arrives
 * inside the character's own system, where the store refuses the components the teleport writes. So opening takes
 * over no screen there and then and answers false, as the {@link Destinations} contract asks, and a conversation
 * carries on until the entry answers: a player sent in has any page they have open closed as the loading screen
 * takes them, and a refused one hears why where they are looking.
 */
public final class DungeonDestinations {

    public static final String OWNER = "ziggfreedcommon";
    public static final String TYPE = "Dungeon";

    private static final String LOG = "[dungeon] ";

    private DungeonDestinations() {
    }

    /** Seed the type, at setup, before any asset decodes. */
    public static void register() {
        Destinations.register(OWNER, DestinationType.of(TYPE, Dungeon.class, Dungeon.CODEC, DungeonDestinations::open));
    }

    /** Queue the entry on the player's world; it takes over no screen now, so false. */
    private static boolean open(@Nonnull Dungeon destination, @Nonnull DestinationContext ctx) {
        String id = destination.getDungeon();
        Ref<EntityStore> ref = ctx.playerReference();
        Store<EntityStore> store = ctx.store();
        World world = store.getExternalData().getWorld();
        world.execute(() -> enter(ref, store, world, id == null ? "" : id));
        return false;
    }

    /** The entry, on its own task of the player's world: their handles read afresh, since the ask is a tick old. */
    private static void enter(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull World world,
            @Nonnull String dungeonId) {
        try {
            if (!ref.isValid()) {
                return;
            }
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef == null) {
                return;
            }
            DungeonEntry.Outcome outcome = DungeonEntry.enterWithParty(ref, store, playerRef, world, dungeonId);
            if (outcome.result() == DungeonEntry.Result.SENT) {
                closePage(ref, store);
                return;
            }
            DungeonRefusals.tell(playerRef, dungeonId, outcome);
        } catch (Throwable t) {
            SafeLog.warn(LOG + "the Dungeon destination could not run its entry into '" + dungeonId + "'", t);
        }
    }

    /** Close the page the player has open (the conversation that sent them), so they do not arrive under it. */
    private static void closePage(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        Player player = store.getComponent(ref, Player.getComponentType());
        PageManager pages = player == null ? null : player.getPageManager();
        if (pages != null && pages.getCustomPage() != null) {
            pages.setPage(ref, store, Page.None);
        }
    }

    /** A dungeon's gate. */
    public static final class Dungeon extends Destination {

        @Nullable protected String dungeon;

        public static final BuilderCodec<Dungeon> CODEC = BuilderCodec.builder(Dungeon.class, Dungeon::new)
                .append(new KeyedCodec<>("Dungeon", Codec.STRING, false),
                        (d, v) -> d.dungeon = v, d -> d.dungeon)
                .documentation("The dungeon to send the player into, by its id (the file name under "
                        + "Server/ZiggfreedCommon/Dungeons/). The player goes in with their party, or alone when "
                        + "they have none; a dungeon that is closed, out of season or full tells them why.").add()
                .build();

        public Dungeon() {
        }

        /** Java-side construction. */
        @Nonnull
        public static Dungeon of(@Nullable String dungeonId) {
            Dungeon d = new Dungeon();
            d.dungeon = dungeonId;
            return d;
        }

        /** The dungeon id, trimmed, or null when none is written. */
        @Nullable
        public String getDungeon() {
            return dungeon == null || dungeon.isBlank() ? null : dungeon.trim();
        }
    }
}
