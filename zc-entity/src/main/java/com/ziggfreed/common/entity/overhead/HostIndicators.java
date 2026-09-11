package com.ziggfreed.common.entity.overhead;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Everything one HOST is showing over its head: what everyone sees, what particular viewers see
 * instead, until when, and the marker entity standing in for each state. Pure: no engine call, no
 * clock of its own, so the layering and the expiry are assertable without a server.
 *
 * <p><b>One indicator per viewer.</b> A viewer's own entry wins over the shared one for as long as
 * it lives; when it expires or is hidden the shared one is what they see again. Both are keyed by
 * a state id compared without regard to case, and the key a marker is filed under is that same
 * folded spelling, so a look authored {@code Quest_Available} and a consumer writing
 * {@code quest_available} meet on one marker.
 *
 * <p>Thread shape: written on the world thread by the facade and the follow pass, read by the
 * visibility filter from whichever task ticks the viewer, so the maps are concurrent and every
 * read is a single lookup.
 */
final class HostIndicators {

    /** One shown indicator: the state and the instant it runs out. */
    record Entry(@Nonnull String stateKey, long deadlineMs) {

        boolean expired(long nowMs) {
            return nowMs >= deadlineMs;
        }
    }

    @Nullable private volatile Entry everyone;

    private final Map<UUID, Entry> perViewer = new ConcurrentHashMap<>();

    /** The marker entity standing in for each state this host has shown, by folded state key. */
    private final Map<String, Ref<EntityStore>> markers = new ConcurrentHashMap<>();

    /** The folded spelling every state id is compared and filed under. */
    @Nonnull
    static String keyOf(@Nonnull String stateId) {
        return stateId.trim().toLowerCase(Locale.ROOT);
    }

    /** Show {@code stateId} to {@code audience} from {@code nowMs}, for {@code lifetime}. */
    void show(@Nonnull IndicatorAudience audience, @Nonnull String stateId, @Nonnull IndicatorLifetime lifetime,
              long nowMs) {
        Entry entry = new Entry(keyOf(stateId), lifetime.deadlineFrom(nowMs));
        if (entry.expired(nowMs)) {
            return;
        }
        if (audience.shared()) {
            everyone = entry;
            return;
        }
        for (UUID viewer : audience.viewers()) {
            perViewer.put(viewer, entry);
        }
    }

    /** Take down what was shown to {@code audience}, and only that. */
    void hide(@Nonnull IndicatorAudience audience) {
        if (audience.shared()) {
            everyone = null;
            return;
        }
        for (UUID viewer : audience.viewers()) {
            perViewer.remove(viewer);
        }
    }

    /** Take down everything: the shared indicator and every per-viewer one. */
    void clear() {
        everyone = null;
        perViewer.clear();
    }

    /** Forget one viewer's own entry, wherever it came from (a disconnect). */
    void forget(@Nonnull UUID viewer) {
        perViewer.remove(viewer);
    }

    /** The folded state key {@code viewer} sees right now, or null for nothing. */
    @Nullable
    String stateFor(@Nonnull UUID viewer, long nowMs) {
        Entry own = perViewer.get(viewer);
        if (own != null && !own.expired(nowMs)) {
            return own.stateKey();
        }
        Entry shared = everyone;
        return shared != null && !shared.expired(nowMs) ? shared.stateKey() : null;
    }

    /** Drop every entry whose time has run out. */
    void expire(long nowMs) {
        Entry shared = everyone;
        if (shared != null && shared.expired(nowMs)) {
            everyone = null;
        }
        perViewer.entrySet().removeIf(e -> e.getValue().expired(nowMs));
    }

    /** Every state key some live entry names, so a marker for any other state can go. */
    @Nonnull
    Set<String> liveStateKeys(long nowMs) {
        Set<String> out = new LinkedHashSet<>();
        Entry shared = everyone;
        if (shared != null && !shared.expired(nowMs)) {
            out.add(shared.stateKey());
        }
        for (Entry entry : perViewer.values()) {
            if (!entry.expired(nowMs)) {
                out.add(entry.stateKey());
            }
        }
        return out;
    }

    /** True when nothing is shown to anybody, so the host's record can be dropped. */
    boolean isIdle(long nowMs) {
        return liveStateKeys(nowMs).isEmpty();
    }

    @Nonnull
    Map<String, Ref<EntityStore>> markers() {
        return markers;
    }
}
