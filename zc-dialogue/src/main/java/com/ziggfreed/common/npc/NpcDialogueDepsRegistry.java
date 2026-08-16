package com.ziggfreed.common.npc;

import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.dialogue.page.DialoguePageDeps;
import com.ziggfreed.common.registry.RegistryLedger;

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
 *
 * <p><b>Every registration names its owner, and the default key is a real collision.</b> Two mods
 * both registering the un-keyed default is the ordinary case on a server running both, and the
 * later one takes it: a role or a placement without a {@code DepsKey} then opens with that mod's
 * deps. The ENGINE and every mod's payload are shared, so what is left riding on the deps is
 * narrower than it was - but it is not nothing, and it is worth naming: which resolver looks the
 * conversation up, which header name and hint the page paints, and which
 * {@link com.ziggfreed.common.i18n.ContentI18n} namespace every authored key on the screen is
 * resolved through. That last one is the one with teeth: a conversation opened against another mod's
 * i18n has its keys prefixed with that mod's {@code .lang} filename, so text that resolves fine on
 * its own server renders as raw keys here. The ledger therefore prints one line naming both owners
 * rather than leaving it to be discovered in game, and a consumer that must be reachable regardless
 * registers a named key as well and authors it on its own roles.
 */
public final class NpcDialogueDepsRegistry {

    /** The key an action with no authored {@code DepsKey} resolves against. */
    public static final String DEFAULT_KEY = "default";

    private static final RegistryLedger<Supplier<DialoguePageDeps>> LEDGER =
            new RegistryLedger<>("dialogue-deps");

    private NpcDialogueDepsRegistry() {
    }

    /**
     * Register the default-key deps provider, attributed to {@code owner} (the registering mod's
     * name). The common single-consumer case; see the class documentation for what happens when two
     * mods do this on one server.
     *
     * <p>Named apart from {@link #set} on purpose: the two carry the same argument types, and a call
     * meaning "register under the key {@code owner}" that quietly landed on the default key instead
     * would be exactly the collision this registry exists to make visible.
     */
    public static void setDefault(@Nonnull String owner, @Nonnull Supplier<DialoguePageDeps> supplier) {
        set(DEFAULT_KEY, owner, supplier);
    }

    /**
     * Register a deps provider under {@code key} (for multi-consumer disambiguation), attributed to
     * {@code owner}. Re-registering the SAME supplier instance is silent; a different one for a key
     * somebody already registered warns once, naming both owners.
     */
    public static void set(@Nonnull String key, @Nonnull String owner,
            @Nonnull Supplier<DialoguePageDeps> supplier) {
        LEDGER.put(normalize(key), owner, supplier);
    }

    /** The default-key deps provider, or {@code null} if none was registered. */
    @Nullable
    public static Supplier<DialoguePageDeps> get() {
        return get(DEFAULT_KEY);
    }

    /** The deps provider registered under {@code key}, or {@code null} if none. */
    @Nullable
    public static Supplier<DialoguePageDeps> get(@Nullable String key) {
        return LEDGER.get(normalize(key));
    }

    /** Which mod registered each key, and how often its provider has failed (an admin listing). */
    @Nonnull
    public static Map<String, RegistryLedger.RegistrationInfo> info() {
        return LEDGER.info();
    }

    /** Drop every registration. Tests only; a live server registers once at setup. */
    public static void resetForTests() {
        LEDGER.clear();
    }

    @Nonnull
    private static String normalize(@Nullable String key) {
        if (key == null || key.isBlank()) {
            return DEFAULT_KEY;
        }
        return key.trim().toLowerCase(Locale.ROOT);
    }
}
