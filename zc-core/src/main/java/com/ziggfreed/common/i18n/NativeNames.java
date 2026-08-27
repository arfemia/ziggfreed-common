package com.ziggfreed.common.i18n;

import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;

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
 * <p>Existence is probed against the English catalog only, through the shared {@link LangCatalog}
 * (this library carries no per-player locale seam - the server never reads/caches/persists one, per
 * the MMO's own display-text convention). The returned {@link Message} still resolves in the
 * VIEWER's own locale client-side; the probe only decides WHICH namespace's key to hand the client.
 */
public final class NativeNames {

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
        if (LangCatalog.has(nativeKey)) {
            return Msg.key(nativeKey);
        }
        String modKey = "items." + itemId + ".name";
        if (LangCatalog.has(modKey)) {
            return Msg.key(modKey);
        }
        return Msg.raw(prettify(itemId));
    }

    /**
     * The display name for an id whose CATEGORY is unknown - an objective's target, a loot
     * subject - probed across the namespaces the engine ships names in: the two item families
     * first, then the two character/creature families, then the prettified raw fallback. One
     * ladder, so every surface that has to name "whatever this id is" reads the same answer.
     */
    @Nonnull
    public static Message targetNameMsg(@Nonnull String id) {
        return targetNameMsg(id, LangCatalog::has);
    }

    /**
     * {@link #targetNameMsg(String)} over an explicit key-existence probe - the decision core a
     * unit test drives, and what a caller composing its own ladder threads its probe through.
     */
    @Nonnull
    public static Message targetNameMsg(@Nonnull String id, @Nonnull Predicate<String> keyExists) {
        if (id.isBlank()) {
            return Msg.raw("");
        }
        for (String key : new String[] {
                "server.items." + id + ".name", "items." + id + ".name",
                "server.npcRoles." + id + ".name", "npcs." + id + ".name"}) {
            if (keyExists.test(key)) {
                return Msg.key(key);
            }
        }
        return Msg.raw(prettify(id.replace(':', ' ')));
    }

    /**
     * Resolves {@code entityId} to a client-resolved character / creature display {@link Message}:
     * the native {@code server.npcRoles.<id>.name} key when it exists, else the
     * {@code npcs.<id>.name} namespace a mod's own {@code npcs.lang} loads under, else the
     * prettified raw fallback - the same probe-then-fallback shape as {@link #itemNameMsg}, over
     * the two namespaces the engine registers role names in.
     */
    @Nonnull
    public static Message entityNameMsg(@Nonnull String entityId) {
        if (entityId.isBlank()) {
            return Msg.raw("");
        }
        String nativeKey = "server.npcRoles." + entityId + ".name";
        if (LangCatalog.has(nativeKey)) {
            return Msg.key(nativeKey);
        }
        String modKey = "npcs." + entityId + ".name";
        if (LangCatalog.has(modKey)) {
            return Msg.key(modKey);
        }
        return Msg.raw(prettify(entityId));
    }

    /**
     * Resolves {@code zoneId} to a client-resolved place display {@link Message}: the native
     * {@code server.map.zone.<id>} key when it exists, else {@code server.map.region.<id>}, else
     * the prettified raw fallback. The two map namespaces are the engine's own zone and region
     * catalogues, so a vanilla place reads with its shipped name in the viewer's own language.
     */
    @Nonnull
    public static Message zoneNameMsg(@Nonnull String zoneId) {
        if (zoneId.isBlank()) {
            return Msg.raw("");
        }
        String zoneKey = "server.map.zone." + zoneId;
        if (LangCatalog.has(zoneKey)) {
            return Msg.key(zoneKey);
        }
        String regionKey = "server.map.region." + zoneId;
        if (LangCatalog.has(regionKey)) {
            return Msg.key(regionKey);
        }
        return Msg.raw(prettify(zoneId));
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
