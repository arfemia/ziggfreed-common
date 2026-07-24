package com.ziggfreed.common.stats;

import java.util.Collection;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.ziggfreed.common.ZiggfreedCommonPlugin;

/**
 * Boot-time channel-presence check: the load-order silent-drop guard (risk R2 in the scope-2
 * design). Native item {@code StatModifiers} resolves ANY registered {@link EntityStatType} id,
 * but an item's {@code afterDecode} resolution runs ONCE at asset decode time and silently drops
 * an unknown channel id with no {@code loadsAfter} edge to force ordering - so a channel that
 * registers AFTER item assets decode (a bug, not by design) leaves any item authoring it
 * permanently missing that stat with no error anywhere else.
 *
 * <p>{@link #audit(Collection)} is the cheap mitigation: at a point AFTER a consumer expects
 * every one of its channels to be registered (first {@code PlayerReadyEvent} is the intended call
 * site - late enough that jar-bundled {@code Server/Entity/Stats/*.json} and any setup()-time
 * dynamic registration have both had their chance), verify each expected id resolves in the
 * {@link EntityStatType} asset map and log ONE {@code SEVERE} line per miss naming the
 * register-before-items explanation. This does NOT detect an item whose authored modifier
 * already silently dropped (that needs re-reading item assets' raw payloads and is out of scope
 * here) - it only proves the CHANNEL itself is missing, which is the far more common failure
 * mode (a typo'd id, a pack that registers its custom skill's channel too late).
 */
public final class StatChannelAudit {

    private StatChannelAudit() {
    }

    /**
     * Verify every id in {@code expectedChannelIds} resolves to a registered {@link
     * EntityStatType}, logging one {@code SEVERE} line per miss. Never throws; a {@code null} or
     * blank id is skipped silently (not a channel to audit).
     */
    public static void audit(@Nonnull Collection<String> expectedChannelIds) {
        for (String id : expectedChannelIds) {
            if (id == null || id.isEmpty()) {
                continue;
            }
            if (StatIndexCache.resolve(id) == StatIndexCache.UNRESOLVED) {
                logMiss(id);
            }
        }
    }

    private static void logMiss(@Nonnull String id) {
        try {
            ZiggfreedCommonPlugin.LOGGER.atSevere().log("StatChannelAudit: expected stat channel '" + id
                    + "' failed to resolve - register it BEFORE any item asset decodes (a jar-bundled "
                    + "Server/Entity/Stats/*.json channel, or setup()-time dynamic registration); a channel "
                    + "that registers AFTER item decode is silently dropped from that item's StatModifiers "
                    + "with no other warning (load-order gap).");
        } catch (Throwable ignored) {
            // log manager absent (unit JVM) - swallow, never throw out of an audit pass.
        }
    }
}
