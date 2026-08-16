package com.ziggfreed.common.i18n;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

/**
 * The one place an AUTHORED content key becomes the id the client can actually resolve.
 *
 * <p>Every consumer that owns content registers a {@link ContentI18n} here once, and every library
 * surface that paints authored text asks this class rather than prefixing anything itself. That is
 * the whole point: a title on a board, a shelf heading, a category label and a currency name are the
 * same problem, and solving it per page is how three of them end up right and the fourth ships as a
 * raw key.
 *
 * <h2>How a key finds its owner</h2>
 *
 * <p>The fills are asked in registration order and the first one that {@link ContentI18n#hasKey}
 * answers for lends its namespace. A mod that shipped a key is the mod that shipped its translation,
 * so existence IS the attribution - and it is the only one available, since a folded asset carries
 * no record of which jar or pack wrote it.
 *
 * <p>Two consequences worth knowing before authoring:
 *
 * <ul>
 *   <li>A key nothing claims is handed over EXACTLY as authored. So an author who already wrote the
 *       full registered id, or who points at another namespace entirely (a native
 *       {@code server.items.*} name), keeps working untouched - the probe simply fails and the key
 *       passes through. It is also why an unfilled server behaves precisely as one did before this
 *       seam existed, rather than throwing or blanking a name.</li>
 *   <li>A key TWO consumers both ship resolves to the one registered first. Nothing here can do
 *       better, and content ids are already expected to be owner-prefixed when two mods share a
 *       store.</li>
 * </ul>
 */
public final class ContentKeys {

    private static final List<ContentI18n> FILLS = new CopyOnWriteArrayList<>();

    private ContentKeys() {
    }

    // ==================== the registry ====================

    /**
     * Register a consumer's namespace + catalogue. Idempotent per namespace: registering the same
     * prefix twice (a reload, a second setup pass) replaces nothing and adds nothing, so a fill
     * cannot silently pile up behind itself.
     */
    public static void install(@Nonnull ContentI18n fill) {
        String prefix = fill.keyPrefix();
        for (ContentI18n existing : FILLS) {
            if (existing.keyPrefix().equals(prefix)) {
                return;
            }
        }
        FILLS.add(fill);
    }

    /** Every registered fill, in the order they are asked. */
    @Nonnull
    public static List<ContentI18n> installed() {
        return List.copyOf(FILLS);
    }

    /** Forget every fill, which is the unfilled state a test starts from. */
    public static void reset() {
        FILLS.clear();
    }

    // ==================== resolution ====================

    /**
     * The registered id for an authored key: the namespace of whichever fill claims it, else the key
     * exactly as it was written.
     */
    @Nonnull
    public static String resolved(@Nonnull String authoredKey) {
        String key = authoredKey.trim();
        if (key.isEmpty()) {
            return authoredKey;
        }
        ContentI18n owner = ownerOf(key);
        return owner == null ? key : owner.keyPrefix() + key;
    }

    /**
     * An authored key as a client-resolved {@link Message}, its {@code {0},{1},...} slots bound from
     * {@code args} on {@link Msg}'s rules (a localized arg stays a nested {@link Message}).
     */
    @Nonnull
    public static Message tr(@Nonnull String authoredKey, @Nonnull Object... args) {
        return Msg.key(resolved(authoredKey), args);
    }

    /** True when any registered fill ships this authored key. */
    public static boolean known(@Nonnull String authoredKey) {
        return ownerOf(authoredKey.trim()) != null;
    }

    /**
     * Which unprefixed key a surface should emit when content offers an explicit one and a naming
     * convention offers another: the explicit key when this consumer ships it, else the convention
     * key when it does, else null for "neither exists, fall back to whatever raw text you have".
     *
     * <p>Takes the fill rather than reading the registry, because the caller that already holds a
     * consumer's own seam (a page built around one mod's conversation) must not have its choice
     * decided by a second mod's catalogue.
     */
    @Nullable
    public static String pick(@Nonnull ContentI18n i18n, @Nullable String explicitKey,
            @Nullable String conventionKey) {
        if (explicitKey != null && !explicitKey.isEmpty() && i18n.hasKey(explicitKey)) {
            return explicitKey;
        }
        if (conventionKey != null && !conventionKey.isEmpty() && i18n.hasKey(conventionKey)) {
            return conventionKey;
        }
        return null;
    }

    @Nullable
    private static ContentI18n ownerOf(@Nonnull String key) {
        if (key.isEmpty()) {
            return null;
        }
        for (ContentI18n fill : FILLS) {
            try {
                if (fill.hasKey(key)) {
                    return fill;
                }
            } catch (Throwable ignored) {
                // A consumer's catalogue failing costs its claim on this key, never the whole line.
            }
        }
        return null;
    }
}
