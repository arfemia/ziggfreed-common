package com.ziggfreed.common.grant;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * The grant kinds a server knows, and the one way a holder puts its grants on an entity.
 *
 * <p><b>A kind registers once, at setup</b> ({@link #register}): its codec into the native {@link Grant#CODEC} union
 * through the plugin's own codec registry (so it leaves with its plugin, and a file naming it decodes from the first
 * asset load), and the one {@link GrantWriter} that writes it. A failed registration throws at setup, never later as
 * an unknown {@code Kind} inside a pack. The library registers {@code Stat}; a module adds its own the same way.
 *
 * <p><b>{@link #apply}</b> hands every registered writer its kind's share of a holder's grants, an empty share
 * included, so a kind the holder no longer has any of is cleared too. Applying the same set twice changes nothing.
 */
public final class Grants {

    /** Each kind's writer, by its id lower-cased. */
    private static final Map<String, GrantWriter> WRITERS = new ConcurrentHashMap<>();

    /** Each kind's id as registered, by its id lower-cased. */
    private static final Map<String, String> SPELLED = new ConcurrentHashMap<>();

    /** The kinds already warned for having no writer, so each is said once. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private Grants() {
    }

    /**
     * Register the grant kind {@code kind}: {@code codec} into {@link Grant#CODEC} through {@code plugin}'s codec
     * registry, and {@code writer} as the one that writes it. Once, at setup; throws when the engine refuses.
     */
    public static <T extends Grant> void register(@Nonnull PluginBase plugin, @Nonnull String kind,
            @Nonnull Class<T> type, @Nonnull BuilderCodec<T> codec, @Nonnull GrantWriter writer) {
        plugin.getCodecRegistry(Grant.CODEC).register(kind, type, codec);
        writer(kind, writer);
    }

    /**
     * Name {@code writer} as the one that writes {@code kind}, without touching the codec (a test, or a kind whose
     * codec its owner registered itself). A second writer for one kind replaces the first.
     */
    public static void writer(@Nonnull String kind, @Nonnull GrantWriter writer) {
        String key = kind.trim().toLowerCase(Locale.ROOT);
        WRITERS.put(key, writer);
        SPELLED.put(key, kind.trim());
        WARNED.remove(key);
    }

    /** Is a writer registered for {@code kind} (any case)? */
    public static boolean isRegistered(@Nullable String kind) {
        return kind != null && WRITERS.containsKey(kind.trim().toLowerCase(Locale.ROOT));
    }

    /** Every registered kind, as registered. */
    @Nonnull
    public static List<String> kinds() {
        return List.copyOf(SPELLED.values());
    }

    /**
     * Make the entity at {@code ref} carry exactly {@code placed} for {@code owner}: every registered writer gets
     * its kind's share (an empty one clears what it wrote for the owner before). A grant of a kind with no writer
     * is said once and left out. World thread.
     */
    public static void apply(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull String owner,
            @Nonnull List<Placed> placed) {
        Map<String, List<Placed>> byKind = new LinkedHashMap<>();
        for (String kind : WRITERS.keySet()) {
            byKind.put(kind, new ArrayList<>());
        }
        for (Placed one : placed) {
            String kind = one.grant().kind().trim().toLowerCase(Locale.ROOT);
            List<Placed> share = byKind.get(kind);
            if (share == null) {
                if (WARNED.add(kind)) {
                    SafeLog.warn("[grant] no writer is registered for the grant kind '" + one.grant().kind()
                            + "', so those grants are not written");
                }
                continue;
            }
            share.add(one);
        }
        for (Map.Entry<String, List<Placed>> share : byKind.entrySet()) {
            GrantWriter writer = WRITERS.get(share.getKey());
            if (writer == null) {
                continue;
            }
            try {
                writer.write(store, ref, owner, share.getValue());
            } catch (Throwable t) {
                SafeLog.warn("[grant] the '" + SPELLED.getOrDefault(share.getKey(), share.getKey())
                        + "' grants could not be written: " + t.getMessage());
            }
        }
    }
}
