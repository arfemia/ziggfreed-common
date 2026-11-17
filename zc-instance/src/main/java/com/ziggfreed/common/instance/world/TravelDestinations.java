package com.ziggfreed.common.instance.world;

import java.util.List;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.builtin.instances.InstancesPlugin;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.PageManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.ui.route.Destination;
import com.ziggfreed.common.ui.route.DestinationContext;
import com.ziggfreed.common.ui.route.DestinationType;
import com.ziggfreed.common.ui.route.Destinations;
import com.ziggfreed.common.ui.toast.ToastDelivery;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastSpec;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.validation.Finding;

/**
 * Two moves a placed character's press-F, a conversation's answer or a block's {@code ZigOpen} (zc-presentation's
 * interaction Type, which the temple hub's two pads run) can make, keeping the return chain the engine's own
 * {@code ExitInstance} would leave pointing at the wrong world:
 * <ul>
 *   <li>{@code "Open": {"Type": "Instance", "Instance": "<template>"}} sends the player to that instance's shared copy,
 *       back where they stood if they came from it ({@link InstanceTravel#toInstance}): the temple hub's doorkeeper
 *       ({@code ZiggfreedCommon_Hub}) and its door back to the temple ({@code Forgotten_Temple_Goblins});</li>
 *   <li>{@code "Open": "Overworld"} leaves every instance for the overworld ({@link InstanceTravel#toOverworld}).</li>
 * </ul>
 *
 * <p><b>The move runs on a world task of its own</b>, after the moment that asked for it: a press-F arrives inside the
 * character's own system, where the store refuses the components a teleport writes. So opening takes over no screen
 * there and then and answers false, as the {@link Destinations} contract asks; a player sent on their way has the page
 * they have open closed as the loading screen takes them, and one refused hears why.
 */
public final class TravelDestinations {

    public static final String OWNER = "ziggfreedcommon";
    public static final String INSTANCE = "Instance";
    public static final String OVERWORLD = "Overworld";

    /** The audit's domain and its code for an {@code Instance} naming a template no loaded pack ships. */
    public static final String DOMAIN = "travel";
    public static final String UNKNOWN_INSTANCE = "UNKNOWN_INSTANCE";

    /** The .lang keys a refused move tells the player. */
    public static final String CLOSED_KEY = "ziggfreedcommon.hub.travel.closed";
    public static final String HERE_KEY = "ziggfreedcommon.hub.travel.here";

    private static final String LOG = "[instance] ";

    private TravelDestinations() {
    }

    /** Seed both types, at setup, before any asset decodes. */
    public static void register() {
        Destinations.register(OWNER, DestinationType.of(INSTANCE, Instance.class, Instance.CODEC,
                TravelDestinations::openInstance).withCheck(TravelDestinations::check));
        Destinations.register(OWNER, DestinationType.of(OVERWORLD, Overworld.class, Overworld.CODEC,
                TravelDestinations::openOverworld));
    }

    private static boolean openInstance(@Nonnull Instance destination, @Nonnull DestinationContext ctx) {
        String template = destination.getInstance();
        String key = destination.getKey();
        return later(ctx, (ref, store) -> template == null ? InstanceTravel.Outcome.NO_SUCH_INSTANCE
                : InstanceTravel.toInstance(ref, store, template, key));
    }

    private static boolean openOverworld(@Nonnull Overworld destination, @Nonnull DestinationContext ctx) {
        return later(ctx, InstanceTravel::toOverworld);
    }

    /** One move, as a world task reads it. */
    @FunctionalInterface
    private interface Move {
        @Nonnull
        InstanceTravel.Outcome run(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store);
    }

    /** Queue {@code move} on the player's world; it takes over no screen now, so false. */
    private static boolean later(@Nonnull DestinationContext ctx, @Nonnull Move move) {
        Ref<EntityStore> ref = ctx.playerReference();
        Store<EntityStore> store = ctx.store();
        World world = store.getExternalData().getWorld();
        world.execute(() -> run(ref, store, move));
        return false;
    }

    /** The move, on its own task of the player's world: their handles read afresh, since the ask is a tick old. */
    private static void run(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull Move move) {
        try {
            if (!ref.isValid()) {
                return;
            }
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef == null) {
                return;
            }
            InstanceTravel.Outcome outcome = move.run(ref, store);
            switch (outcome) {
                case SENT -> closePage(ref, store);
                case ALREADY_THERE -> tell(playerRef, HERE_KEY);
                case NO_SUCH_INSTANCE, FAILED -> tell(playerRef, CLOSED_KEY);
                case BUSY -> {
                    // Dead, loading in or already on the move: nothing to say.
                }
            }
        } catch (Throwable t) {
            SafeLog.warn(LOG + "a travel destination could not run its move", t);
        }
    }

    private static void tell(@Nonnull PlayerRef player, @Nonnull String key) {
        Message line = Msg.key(key);
        ToastDelivery.deliverWhenSettled(player, ToastSpec.of(ToastKind.ERROR, line), null);
    }

    /**
     * Close the page the player has open (the conversation that sent them), so they do not arrive under it. A move
     * that lifted the player out of this world at once (the engine's loading teleport) leaves the handle dead, and
     * their page went with the world, so there is nothing to close.
     */
    private static void closePage(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        if (!ref.isValid()) {
            return;
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        PageManager pages = player == null ? null : player.getPageManager();
        if (pages != null && pages.getCustomPage() != null) {
            pages.setPage(ref, store, Page.None);
        }
    }

    /** An {@code Instance} naming no template, or one no loaded pack ships; silent when the engine cannot tell yet. */
    @Nonnull
    private static List<Finding> check(@Nonnull Instance destination, @Nonnull String sourceId) {
        String template = destination.getInstance();
        if (template == null) {
            return List.of(Finding.warning(DOMAIN, UNKNOWN_INSTANCE, "an Instance destination names no Instance, so "
                    + "it leads nowhere", sourceId));
        }
        Boolean known = known(template);
        if (known == null || known) {
            return List.of();
        }
        return List.of(Finding.warning(DOMAIN, UNKNOWN_INSTANCE, "an Instance destination leads to '" + template
                + "', which no loaded pack ships under Server/Instances/, so the way is closed", sourceId));
    }

    /** Does a loaded pack ship {@code template}? Null when the engine cannot tell (its instances are not up yet). */
    @Nullable
    private static Boolean known(@Nonnull String template) {
        if (InstancesPlugin.get() == null) {
            return null;
        }
        try {
            return InstancesPlugin.doesInstanceAssetExist(template);
        } catch (IllegalArgumentException refused) {
            return false;
        } catch (Throwable t) {
            return null;
        }
    }

    /** A way into an instance's shared copy. */
    public static final class Instance extends Destination {

        @Nullable protected String instance;
        @Nullable protected String key;

        public static final BuilderCodec<Instance> CODEC = BuilderCodec.builder(Instance.class, Instance::new)
                .append(new KeyedCodec<>("Instance", Codec.STRING, false), (d, v) -> d.instance = v, d -> d.instance)
                .documentation("The instance to send the player to, by its folder under Server/Instances/ "
                        + "(ZiggfreedCommon_Hub, the temple hub; Forgotten_Temple_Goblins, the Forgotten Temple). A "
                        + "player who came from a copy of it goes back where they stood; anyone else arrives at its "
                        + "spawn, and goes back where they stand now when they leave it.").add()
                .append(new KeyedCodec<>("Key", Codec.STRING, false), (d, v) -> d.key = v, d -> d.key)
                .documentation("The world its one shared copy lives under, for a template that pins none of its "
                        + "own. Unauthored, the template's own InstanceKey, else a fresh copy each visit.").add()
                .build();

        public Instance() {
        }

        /** Java-side construction. */
        @Nonnull
        public static Instance of(@Nullable String instance, @Nullable String key) {
            Instance d = new Instance();
            d.instance = instance;
            d.key = key;
            return d;
        }

        /** The template, trimmed, or null when none is written. */
        @Nullable
        public String getInstance() {
            return instance == null || instance.isBlank() ? null : instance.trim();
        }

        /** The world key, trimmed, or null for the template's own. */
        @Nullable
        public String getKey() {
            return key == null || key.isBlank() ? null : key.trim();
        }
    }

    /** The way out of every instance. */
    public static final class Overworld extends Destination {

        public static final BuilderCodec<Overworld> CODEC = BuilderCodec.builder(Overworld.class, Overworld::new)
                .documentation("Sends the player out of every instance at once, to where they stood when they left "
                        + "the overworld, else its spawn.")
                .build();

        public Overworld() {
        }
    }
}
