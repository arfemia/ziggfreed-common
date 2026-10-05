package com.ziggfreed.common.entity.title;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentRegistryProxy;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.event.events.player.PlayerConnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;

/**
 * The per-player record of the TITLES a player has earned and the one they chose to show,
 * persisted by the library so any mod can grant a title and the library's own menus and
 * leaderboards can show it, whichever mods are installed.
 *
 * <p>Ids are lower-cased at write time, so a grant and a lookup never miss each other on case. An
 * id carrying {@code '|'} or {@code ':'} is REFUSED ({@link #usesReservedDelimiter}): the unlocked
 * set persists as one {@code '|'}-joined string with no escaping, the flair record's discipline.
 *
 * <p>A shown title is always an unlocked one: {@link #activeTitle()} answers null when the saved
 * {@code ActiveTitle} names a title the set no longer holds, so a hand-edited or half-written save
 * never shows an unearned title, and revoking the shown title also stops showing it.
 *
 * <p>{@link #register} runs once from the library's setup, BEFORE any world loads (a component type
 * registered later cannot be read off entities saved carrying it), and {@link #install} reads back
 * the record of what every player shows ({@link ActiveTitles}), hangs the connect hook that attaches
 * one to every player and seeds that record, plus the disconnect hook that writes it down: a player
 * who leaves keeps their title on every row. Write it only through zc-objectives' title write path,
 * which announces each change and keeps {@link ActiveTitles} current. A reader PEEKS it
 * ({@code TYPE} is null when registration failed) and treats a missing record as no titles.
 */
public class ZigTitleComponent implements Component<EntityStore> {

    /** The engine registry id; the persisted save key for this component. */
    public static final String REGISTRY_ID = "ZiggfreedCommon:Titles";

    /** The registered type, or null until {@link #register} runs (or when it failed). */
    @Nullable
    public static ComponentType<EntityStore, ZigTitleComponent> TYPE;

    /** Lower-cased ids of every title the player has earned. */
    public Set<String> unlockedTitles;

    /** The lower-cased id of the title the player shows, or null; read through {@link #activeTitle()}. */
    @Nullable
    private volatile String activeTitle;

    public static final BuilderCodec<ZigTitleComponent> CODEC;

    static {
        var builder = BuilderCodec.builder(ZigTitleComponent.class, ZigTitleComponent::new);
        builder.append(new KeyedCodec<>("UnlockedTitles", Codec.STRING),
                (c, v, info) -> c.unlockedTitles = deserializeStringSet(v),
                (c, info) -> serializeStringSet(c.unlockedTitles)).add();
        builder.append(new KeyedCodec<>("ActiveTitle", Codec.STRING),
                (c, v, info) -> c.activeTitle = normalize(v),
                (c, info) -> c.activeTitle == null ? "" : c.activeTitle).add();
        CODEC = builder.build();
    }

    public ZigTitleComponent() {
        this.unlockedTitles = ConcurrentHashMap.newKeySet();
    }

    /** True when {@code titleId} is null or carries a character the save format reserves. */
    public static boolean usesReservedDelimiter(@Nullable String titleId) {
        return titleId == null || titleId.indexOf('|') >= 0 || titleId.indexOf(':') >= 0;
    }

    /** A usable stored id (trimmed, lower-cased), or null for nothing or a reserved character. */
    @Nullable
    static String normalize(@Nullable String titleId) {
        if (titleId == null || titleId.isBlank() || usesReservedDelimiter(titleId)) {
            return null;
        }
        return titleId.trim().toLowerCase(Locale.ROOT);
    }

    static String serializeStringSet(Set<String> set) {
        if (set == null || set.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String s : set) {
            if (usesReservedDelimiter(s)) {
                // A grant is already refused, so this only catches a direct write to the set; the
                // id is dropped LOUDLY rather than corrupting every entry packed after it.
                SafeLog.warn("[title] the title id '" + s + "' was NOT saved: it contains '|' or ':',"
                        + " which the per-player save format reserves. Rename the title.");
                continue;
            }
            if (sb.length() > 0) {
                sb.append('|');
            }
            sb.append(s);
        }
        return sb.toString();
    }

    static Set<String> deserializeStringSet(String str) {
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

    /**
     * Register the component type. Called once at library setup, BEFORE any world loads. Never
     * throws: a failure logs and leaves {@link #TYPE} unset, and every reader guards on that.
     */
    @Nullable
    public static ComponentType<EntityStore, ZigTitleComponent> register(
            @Nonnull ComponentRegistryProxy<EntityStore> registry) {
        try {
            TYPE = registry.registerComponent(ZigTitleComponent.class, REGISTRY_ID, CODEC);
            return TYPE;
        } catch (Throwable t) {
            SafeLog.warn("[title] could not register ZigTitleComponent", t);
            return null;
        }
    }

    /**
     * Read back the record of shown titles (written a moment after each change, on the server's
     * scheduler), then hang the connect hook (attach and seed the record) and the disconnect hook
     * (write it down).
     */
    public static void install(@Nonnull PluginBase plugin) {
        ActiveTitles.persistTo(ActiveTitles.DEFAULT_FILE, flush -> HytaleServer.SCHEDULED_EXECUTOR.schedule(
                flush, ActiveTitles.FLUSH_DELAY_SECONDS, TimeUnit.SECONDS));
        plugin.getEventRegistry().register(PlayerConnectEvent.class, ZigTitleComponent::onPlayerConnect);
        plugin.getEventRegistry().register(PlayerDisconnectEvent.class, ZigTitleComponent::onPlayerDisconnect);
    }

    private static void onPlayerConnect(@Nonnull PlayerConnectEvent event) {
        try {
            if (TYPE == null) {
                return;
            }
            // The holder is not in a world yet, so reading the saved record here is thread-safe.
            ZigTitleComponent record = event.getHolder().ensureAndGetComponent(TYPE);
            PlayerRef playerRef = event.getPlayerRef();
            seed(playerRef == null ? null : playerRef.getUuid(), record);
        } catch (Throwable t) {
            SafeLog.warn("[title] could not ensure the title record", t);
        }
    }

    private static void onPlayerDisconnect(@Nonnull PlayerDisconnectEvent event) {
        try {
            left();
        } catch (Throwable t) {
            SafeLog.warn("[title] could not write down a leaving player's shown title: " + t.getMessage());
        }
    }

    /**
     * A player left. They keep their entry in {@link ActiveTitles}, so a row naming them offline shows
     * the title it would show online and says nothing about presence; the record is written down now,
     * so a restart keeps it. A server stopping disconnects every player first, so this also writes it
     * at shutdown.
     */
    static void left() {
        ActiveTitles.flush();
    }

    /** Put what {@code record} shows into the record for {@code playerId}; nothing to key, nothing done. */
    static void seed(@Nullable UUID playerId, @Nullable ZigTitleComponent record) {
        if (playerId == null) {
            return;
        }
        ActiveTitles.put(playerId, record == null ? null : record.activeTitle());
    }

    /** True when {@code titleId} (any case) has been earned. */
    public boolean hasTitle(@Nullable String titleId) {
        String id = normalize(titleId);
        return id != null && unlockedTitles.contains(id);
    }

    /** Earn {@code titleId}. True when newly added; a blank or reserved id is refused with one warning. */
    public boolean unlock(@Nullable String titleId) {
        if (titleId == null || titleId.isBlank()) {
            return false;
        }
        if (usesReservedDelimiter(titleId)) {
            SafeLog.warn("[title] the title id '" + titleId + "' is REFUSED: it contains '|' or ':',"
                    + " which the per-player save format reserves. Rename the title.");
            return false;
        }
        return unlockedTitles.add(titleId.trim().toLowerCase(Locale.ROOT));
    }

    /** Take {@code titleId} away, and stop showing it if it was shown. True when it was there. */
    public boolean revoke(@Nullable String titleId) {
        String id = normalize(titleId);
        if (id == null || !unlockedTitles.remove(id)) {
            return false;
        }
        if (id.equals(activeTitle)) {
            activeTitle = null;
        }
        return true;
    }

    /** The title the player shows, lower-cased, or null for none; never one they have not earned. */
    @Nullable
    public String activeTitle() {
        String shown = activeTitle;
        return shown != null && unlockedTitles.contains(shown) ? shown : null;
    }

    /** Show {@code titleId}. True when that changed what is shown; false when unearned or already shown. */
    public boolean activate(@Nullable String titleId) {
        String id = normalize(titleId);
        if (id == null || !unlockedTitles.contains(id) || id.equals(activeTitle())) {
            return false;
        }
        activeTitle = id;
        return true;
    }

    /** Show no title. True when one was shown. */
    public boolean deactivate() {
        boolean wasShowing = activeTitle() != null;
        activeTitle = null;
        return wasShowing;
    }

    @Override
    @SuppressWarnings("CloneDeclaresCloneNotSupported")
    public ZigTitleComponent clone() {
        ZigTitleComponent c = new ZigTitleComponent();
        c.unlockedTitles.addAll(this.unlockedTitles);
        c.activeTitle = this.activeTitle;
        return c;
    }
}
