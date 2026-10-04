package com.ziggfreed.common.almanac;

import java.util.Map;
import java.util.TreeMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.counter.CounterMap;
import com.ziggfreed.common.util.SafeLog;

/**
 * One player's Almanac record: every season tally they hold, keyed by {@link AlmanacKeys}.
 *
 * <p><b>One field, and it only grows.</b> The whole record persists as one {@code Tallies} string,
 * {@code key:value|key:value}, so a new stat or a new season is a new key rather than a new field, and
 * a save written before a stat existed still reads. A pair that is not {@code key:number} is dropped
 * on read and costs only itself.
 *
 * <p>{@link #register} runs once at library setup, before any world loads (a component type
 * registered later cannot be read off entities saved carrying it), and {@link #install} hangs the
 * connect hook that attaches one to every player. Readers PEEK it: {@link #TYPE} is null when
 * registration failed, and a missing record reads as no tallies.
 */
public class AlmanacComponent implements Component<EntityStore> {

    /** The engine registry id, and the save key. */
    public static final String REGISTRY_ID = "ZiggfreedCommon:Almanac";

    public static ComponentType<EntityStore, AlmanacComponent> TYPE;

    /** Every tally this player holds, keyed by {@link AlmanacKeys}. */
    public CounterMap tallies;

    public static final BuilderCodec<AlmanacComponent> CODEC;

    static {
        var builder = BuilderCodec.builder(AlmanacComponent.class, AlmanacComponent::new);
        builder.append(new KeyedCodec<>("Tallies", Codec.STRING),
                (c, v, info) -> c.tallies = deserialize(v),
                (c, info) -> serialize(c.tallies.all())).add();
        CODEC = builder.build();
    }

    public AlmanacComponent() {
        this.tallies = new CounterMap();
    }

    /** {@code key:value|key:value}, keys sorted so a save is stable. */
    @Nonnull
    static String serialize(@Nonnull Map<String, Long> tallies) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Long> entry : new TreeMap<>(tallies).entrySet()) {
            if (out.length() > 0) {
                out.append('|');
            }
            out.append(entry.getKey()).append(':').append(entry.getValue());
        }
        return out.toString();
    }

    /** The bag a saved string holds; a damaged pair is dropped, never the rest. */
    @Nonnull
    static CounterMap deserialize(@Nullable String packed) {
        CounterMap out = new CounterMap();
        if (packed == null || packed.isEmpty()) {
            return out;
        }
        for (String pair : packed.split("\\|")) {
            int colon = pair.lastIndexOf(':');
            if (colon <= 0 || colon == pair.length() - 1) {
                continue;
            }
            try {
                out.set(pair.substring(0, colon), Long.parseLong(pair.substring(colon + 1)));
            } catch (NumberFormatException ignored) {
                // Not key:number: this pair is lost, every other tally survives.
            }
        }
        return out;
    }

    /** Register the type with the entity-store registry, once, at library setup. Never throws. */
    @Nullable
    public static ComponentType<EntityStore, AlmanacComponent> register(
            @Nonnull ComponentRegistryProxy<EntityStore> registry) {
        try {
            TYPE = registry.registerComponent(AlmanacComponent.class, REGISTRY_ID, CODEC);
            return TYPE;
        } catch (Throwable t) {
            SafeLog.warn("[almanac] could not register AlmanacComponent", t);
            return null;
        }
    }

    /** Hang the connect hook that attaches a record to every player. */
    public static void install(@Nonnull PluginBase plugin) {
        plugin.getEventRegistry().register(PlayerConnectEvent.class, AlmanacComponent::onPlayerConnect);
    }

    private static void onPlayerConnect(@Nonnull PlayerConnectEvent event) {
        try {
            if (TYPE == null) {
                return;
            }
            event.getHolder().ensureAndGetComponent(TYPE);
        } catch (Throwable t) {
            SafeLog.warn("[almanac] could not attach the Almanac record", t);
        }
    }

    @Override
    @SuppressWarnings("CloneDeclaresCloneNotSupported")
    public AlmanacComponent clone() {
        AlmanacComponent copy = new AlmanacComponent();
        copy.tallies = this.tallies.copy();
        return copy;
    }
}
