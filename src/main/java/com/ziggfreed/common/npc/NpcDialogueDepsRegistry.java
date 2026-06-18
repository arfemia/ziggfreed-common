package com.ziggfreed.common.npc;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.dialogue.page.DialoguePageDeps;

/**
 * The seam that lets the generic {@link ActionOpenDialogue} NPC action open a
 * {@link DialoguePageDeps}-backed page without coupling {@code ziggfreed-common} to
 * any one consumer's deps. A role asset is decoded long before a consumer's deps
 * exist, so the action stores only the dialogue id + an optional {@code DepsKey} and
 * resolves the deps LAZILY at press-F time from a {@link Supplier} the consumer
 * registered once at setup (mirroring how the MMO / Kweebec expose a static
 * {@code deps()} provider).
 *
 * <p>Keyed so multiple consumers in one server never collide: a consumer that
 * authors {@code "DepsKey": "<key>"} on its role action resolves THAT consumer's
 * deps; the common case (one provider) uses {@link #DEFAULT_KEY}. Keys are
 * normalized (trimmed + lower-cased) so author casing does not matter.
 */
public final class NpcDialogueDepsRegistry {

    /** The key an action with no authored {@code DepsKey} resolves against. */
    public static final String DEFAULT_KEY = "default";

    private static final Map<String, Supplier<DialoguePageDeps>> SUPPLIERS = new ConcurrentHashMap<>();

    private NpcDialogueDepsRegistry() {
    }

    /** Register the default-key deps provider (the common single-consumer case). */
    public static void set(@Nonnull Supplier<DialoguePageDeps> supplier) {
        set(DEFAULT_KEY, supplier);
    }

    /** Register a deps provider under {@code key} (for multi-consumer disambiguation). */
    public static void set(@Nonnull String key, @Nonnull Supplier<DialoguePageDeps> supplier) {
        SUPPLIERS.put(normalize(key), supplier);
    }

    /** The default-key deps provider, or {@code null} if none was registered. */
    @Nullable
    public static Supplier<DialoguePageDeps> get() {
        return get(DEFAULT_KEY);
    }

    /** The deps provider registered under {@code key}, or {@code null} if none. */
    @Nullable
    public static Supplier<DialoguePageDeps> get(@Nullable String key) {
        return SUPPLIERS.get(normalize(key));
    }

    @Nonnull
    private static String normalize(@Nullable String key) {
        if (key == null || key.isBlank()) {
            return DEFAULT_KEY;
        }
        return key.trim().toLowerCase(Locale.ROOT);
    }
}
