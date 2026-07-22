package com.ziggfreed.common.i18n;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;

/**
 * Native-namespace item display-name resolution, shared by any consumer mod that must show a
 * VANILLA (or another mod's) item name without owning a per-player locale seam of its own - the
 * two-tier {@code server.items.<id>.name} (vanilla/base-game) then {@code items.<id>.name}
 * (mod/pack-shipped {@code items.lang}) probe-then-fallback shape the MMO Skill Tree's own {@code
 * content.objective.TargetNameResolver#itemNameMsg}/{@code i18n.LocalizationConfig#canonicalItemName}
 * pioneered, lifted here (RPG Stations extraction bugfix leg, R1) so a second consumer does not
 * re-derive it minus the existence-check/raw-fallback safety net that first consumer already
 * proved necessary - a bare {@link Msg#key} with no existence probe hands the client an
 * unresolvable translation key for any item that isn't in the FIRST namespace tried.
 *
 * <p>Existence is probed against the {@code en-US} catalog only (this library carries no
 * per-player locale seam - the server never reads/caches/persists one, per the MMO's own
 * display-text convention). The returned {@link Message} still resolves in the VIEWER's own
 * locale client-side; the probe only decides WHICH namespace's key to hand the client.
 */
public final class NativeNames {

    /** The existence-probe locale (the engine's bundled English catalog, {@link I18nModule#DEFAULT_LANGUAGE}). */
    private static final String PROBE_LANGUAGE = "en-US";

    private NativeNames() {
    }

    /**
     * Resolves {@code itemId} to a client-resolved item display {@link Message}: the native
     * {@code server.items.<id>.name} key when it exists (a vanilla/base-game item), else the
     * {@code items.<id>.name} namespace a mod's own/pack-shipped {@code items.lang} loads under,
     * else a prettified raw fallback ({@link #prettify}) so the client is NEVER handed an
     * unresolvable translation key.
     */
    @Nonnull
    public static Message itemNameMsg(@Nonnull String itemId) {
        if (itemId.isBlank()) {
            return Msg.raw("");
        }
        String nativeKey = "server.items." + itemId + ".name";
        if (hasKey(nativeKey)) {
            return Msg.key(nativeKey);
        }
        String modKey = "items." + itemId + ".name";
        if (hasKey(modKey)) {
            return Msg.key(modKey);
        }
        return Msg.raw(prettify(itemId));
    }

    /** True when {@code fullKey} resolves against the {@link #PROBE_LANGUAGE} catalog. Never throws. */
    private static boolean hasKey(@Nonnull String fullKey) {
        try {
            I18nModule i18n = I18nModule.get();
            return i18n != null && i18n.getMessage(PROBE_LANGUAGE, fullKey) != null;
        } catch (Throwable t) {
            // A unit JVM or pre-init window has no module; treat as "no key" so the caller
            // falls through to the raw fallback rather than throwing (mirrors
            // dialogue.i18n.I18nModuleDialogueI18n's identical guard).
            return false;
        }
    }

    /**
     * Turn a Hytale-style {@code Category_Type_Material} id into spaced, title-cased words,
     * preserving any existing intra-word casing (so {@code Trork_Chieftain} -> "Trork
     * Chieftain", {@code zombie} -> "Zombie"). Ported verbatim from the MMO's {@code
     * content.objective.TargetNameResolver#prettify} (generic, license-free logic, not
     * MMO-specific).
     */
    @Nonnull
    public static String prettify(@Nullable String id) {
        if (id == null || id.isEmpty()) {
            return "";
        }
        String[] words = id.replace('_', ' ').trim().split("\\s+");
        StringBuilder sb = new StringBuilder(id.length());
        for (String w : words) {
            if (w.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(w.charAt(0)));
            if (w.length() > 1) {
                sb.append(w.substring(1));
            }
        }
        return sb.length() > 0 ? sb.toString() : id;
    }
}
