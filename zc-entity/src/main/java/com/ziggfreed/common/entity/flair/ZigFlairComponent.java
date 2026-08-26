package com.ziggfreed.common.entity.flair;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
import com.ziggfreed.common.util.SafeLog;

/**
 * The per-player set of unlocked cosmetic FLAIR ids, persisted by the library so any mod on the
 * server can grant one and any mod can ask - a station's flair overlay above all, but nothing here
 * knows what a flair looks like or where it shows.
 *
 * <p><b>Why the library holds it.</b> A flair is granted by whoever wants to (a command reward, a
 * quest, an admin command - anything that can run a console command) and READ by whoever renders it,
 * and those two are routinely different mods. Holding the set in the shared library means a server
 * running only the renderer still remembers what a player unlocked, exactly as
 * {@code ZigProgressComponent} holds quest and achievement state for whoever is running.
 *
 * <p>Ids are lower-cased at write time, so a grant and a lookup can never miss each other on case.
 *
 * <p>{@link #register} is called once from the library plugin's setup, and {@link #install} hangs
 * the connect hook that attaches one to every player - a component type registered after a world
 * has loaded cannot be read off entities saved carrying it, and a component never attached would
 * drop every write. Consumers PEEK it ({@code TYPE} may be null when registration failed) and treat
 * a missing component as an empty set.
 */
public class ZigFlairComponent implements Component<EntityStore> {

    /** The engine registry id; the persisted save key for this component. */
    public static final String REGISTRY_ID = "ZiggfreedCommon:Flairs";

    public static ComponentType<EntityStore, ZigFlairComponent> TYPE;

    /** Lowercase flair ids the player has unlocked (any surface, any grantor). */
    public Set<String> unlockedFlairs;

    public static final BuilderCodec<ZigFlairComponent> CODEC;

    private static String serializeStringSet(Set<String> set) {
        if (set == null || set.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String s : set) {
            if (sb.length() > 0) {
                sb.append("|");
            }
            sb.append(s);
        }
        return sb.toString();
    }

    private static Set<String> deserializeStringSet(String str) {
        Set<String> set = ConcurrentHashMap.newKeySet();
        if (str == null || str.isEmpty()) {
            return set;
        }
        for (String s : str.split("\\|")) {
            if (!s.isEmpty()) {
                set.add(s);
            }
        }
        return set;
    }

    static {
        var builder = BuilderCodec.builder(ZigFlairComponent.class, ZigFlairComponent::new);
        builder.append(new KeyedCodec<>("UnlockedFlairs", Codec.STRING),
                (c, v, info) -> c.unlockedFlairs = deserializeStringSet(v),
                (c, info) -> serializeStringSet(c.unlockedFlairs)).add();
        CODEC = builder.build();
    }

    public ZigFlairComponent() {
        this.unlockedFlairs = ConcurrentHashMap.newKeySet();
    }

    /**
     * Register the component type with the entity-store registry. Called once at library setup,
     * BEFORE any world loads. Never throws: a failure logs and leaves {@link #TYPE} unset, and
     * every consumer guards on that.
     */
    @Nullable
    public static ComponentType<EntityStore, ZigFlairComponent> register(
            @Nonnull ComponentRegistryProxy<EntityStore> registry) {
        try {
            TYPE = registry.registerComponent(ZigFlairComponent.class, REGISTRY_ID, CODEC);
            return TYPE;
        } catch (Throwable t) {
            SafeLog.warn("[flair] could not register ZigFlairComponent", t);
            return null;
        }
    }

    /** Hang the connect hook that attaches one of these to every player. */
    public static void install(@Nonnull PluginBase plugin) {
        plugin.getEventRegistry().register(PlayerConnectEvent.class, ZigFlairComponent::onPlayerConnect);
    }

    private static void onPlayerConnect(@Nonnull PlayerConnectEvent event) {
        try {
            if (TYPE == null) {
                return;
            }
            event.getHolder().ensureAndGetComponent(TYPE);
        } catch (Throwable t) {
            SafeLog.warn("[flair] could not ensure the flair component", t);
        }
    }

    /** True when {@code flairId} (any case) is in the unlocked set. */
    public boolean hasFlair(@Nullable String flairId) {
        return flairId != null && unlockedFlairs.contains(flairId.toLowerCase(Locale.ROOT));
    }

    /** Unlock {@code flairId} (lowercased at write time). Returns true when newly added. */
    public boolean unlock(@Nullable String flairId) {
        if (flairId == null || flairId.isBlank()) {
            return false;
        }
        return unlockedFlairs.add(flairId.toLowerCase(Locale.ROOT));
    }

    /** Revoke {@code flairId} (lowercased for lookup). Returns true when it was present. */
    public boolean revoke(@Nullable String flairId) {
        if (flairId == null) {
            return false;
        }
        return unlockedFlairs.remove(flairId.toLowerCase(Locale.ROOT));
    }

    @Override
    @SuppressWarnings("CloneDeclaresCloneNotSupported")
    public ZigFlairComponent clone() {
        ZigFlairComponent c = new ZigFlairComponent();
        c.unlockedFlairs = ConcurrentHashMap.newKeySet();
        c.unlockedFlairs.addAll(this.unlockedFlairs);
        return c;
    }
}
