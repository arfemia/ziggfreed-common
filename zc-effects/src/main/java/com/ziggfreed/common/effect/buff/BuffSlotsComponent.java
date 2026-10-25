package com.ziggfreed.common.effect.buff;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.map.MapCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * The timed-buff binding's slot record on a player, saved WITH the player: per scope, each live
 * buff's key and the effect it shows on.
 *
 * <p>Two jobs. A buff keeps its slot from refresh to refresh and across a relog, so its tile never
 * jumps to another slot and starts over ({@code SlotPlan} reads the record). And the effects saved on
 * the player outlive the session, so a refresh after login takes off every effect the record names
 * that no live buff wants any more, an effect a source named for itself included, even when the
 * buff ended while the player was away.
 *
 * <p>ECS state beside the effect controller it describes: a world change moves both, a save writes
 * both. Only ever replaced in place, at each refresh. {@link #register} runs once at the library's
 * setup, unconditionally and before any world loads (a component type registered later cannot be
 * read off entities saved carrying it), and {@link #install} hangs the connect hook that attaches one
 * to every player's holder before the entity joins a store, where adding a component inside a refresh
 * would change the archetype. The gear-set look record's shape ({@code GearSetLooksComponent}).
 */
public final class BuffSlotsComponent implements Component<EntityStore> {

    /** The engine registry id; the persisted save key for this component. */
    public static final String REGISTRY_ID = "ZiggfreedCommon:BuffSlots";

    /** The save's {@code Version} leaf: a save from before it reads as version 0, unchanged (zc-core's {@code codec/} router). */
    public static final int VERSION = 1;

    /** One scope's record: buff key to effect id. */
    private static final Codec<Map<String, String>> SLOTS = new MapCodec<>(Codec.STRING, LinkedHashMap::new, false);

    /** The registered type, or null until {@link #register} runs (or when it failed). */
    @Nullable
    private static volatile ComponentType<EntityStore, BuffSlotsComponent> type;

    @Nonnull
    public static final BuilderCodec<BuffSlotsComponent> CODEC = BuilderCodec
            .builder(BuffSlotsComponent.class, BuffSlotsComponent::new)
            .versioned()
            .codecVersion(VERSION)
            .append(new KeyedCodec<>("Personal", SLOTS),
                    (c, v) -> c.personal = copyOf(v),
                    c -> c.personal).add()
            .append(new KeyedCodec<>("Server", SLOTS),
                    (c, v) -> c.server = copyOf(v),
                    c -> c.server).add()
            .build();

    private Map<String, String> personal = new LinkedHashMap<>();

    private Map<String, String> server = new LinkedHashMap<>();

    public BuffSlotsComponent() {
    }

    /**
     * Register the component type. Called once at library setup, BEFORE any world loads. Never
     * throws: a failure logs and leaves the type unset, and the binding then keeps no record.
     */
    @Nullable
    public static ComponentType<EntityStore, BuffSlotsComponent> register(
            @Nonnull ComponentRegistryProxy<EntityStore> registry) {
        try {
            type = registry.registerComponent(BuffSlotsComponent.class, REGISTRY_ID, CODEC);
            return type;
        } catch (Throwable t) {
            SafeLog.warn("[buff] could not register BuffSlotsComponent", t);
            return null;
        }
    }

    /** Hang the connect hook that attaches one of these to every player's holder. */
    public static void install(@Nonnull PluginBase plugin) {
        plugin.getEventRegistry().register(PlayerConnectEvent.class, BuffSlotsComponent::onPlayerConnect);
    }

    /** The registered type, or null when registration has not run or failed. */
    @Nullable
    public static ComponentType<EntityStore, BuffSlotsComponent> getComponentType() {
        return type;
    }

    private static void onPlayerConnect(@Nonnull PlayerConnectEvent event) {
        try {
            ComponentType<EntityStore, BuffSlotsComponent> registered = type;
            if (registered == null) {
                return;
            }
            event.getHolder().ensureAndGetComponent(registered);
        } catch (Throwable t) {
            SafeLog.warn("[buff] could not attach the buff slot record", t);
        }
    }

    /** One scope's record, buff key to effect id, in the order it was written; never null. */
    @Nonnull
    public Map<String, String> slots(@Nonnull BuffScope scope) {
        return Collections.unmodifiableMap(scope == BuffScope.SERVER ? server : personal);
    }

    /** Every scope's record, as {@code BuffPlan} reads it. */
    @Nonnull
    public Map<BuffScope, Map<String, String>> all() {
        Map<BuffScope, Map<String, String>> out = new EnumMap<>(BuffScope.class);
        for (BuffScope scope : BuffScope.values()) {
            out.put(scope, slots(scope));
        }
        return out;
    }

    /**
     * Replace the record with what this refresh planned; a scope it does not name records nothing.
     *
     * @param planned per scope, each live buff's key and effect id
     */
    public void record(@Nonnull Map<BuffScope, Map<String, String>> planned) {
        personal = copyOf(planned.get(BuffScope.PERSONAL));
        server = copyOf(planned.get(BuffScope.SERVER));
    }

    /** A writable copy with every blank key or id dropped, order kept. */
    @Nonnull
    private static Map<String, String> copyOf(@Nullable Map<String, String> slots) {
        Map<String, String> out = new LinkedHashMap<>();
        if (slots == null) {
            return out;
        }
        for (Map.Entry<String, String> entry : slots.entrySet()) {
            String key = entry.getKey();
            String effect = entry.getValue();
            if (key != null && !key.isBlank() && effect != null && !effect.isBlank()) {
                out.put(key, effect);
            }
        }
        return out;
    }

    @Override
    public BuffSlotsComponent clone() {
        BuffSlotsComponent c = new BuffSlotsComponent();
        c.personal = new LinkedHashMap<>(personal);
        c.server = new LinkedHashMap<>(server);
        return c;
    }
}
