package com.ziggfreed.common.interaction;

import javax.annotation.Nonnull;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.entity.InteractionChain;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.InteractionManager;
import com.hypixel.hytale.server.core.modules.interaction.InteractionModule;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.ZiggfreedCommonPlugin;

/**
 * Fires a named engine {@code RootInteraction} chain on an entity - {@code initChain} + {@code
 * queueExecuteChain}, the proven server-side chain-trigger mechanism (see the
 * {@code hytale-interaction-trigger} ledger entry: {@code RootInteraction.getAssetMap()} is one
 * GLOBAL, unscoped store, so any vanilla/pack/plugin-registered id can be fired by ANY caller with
 * zero ownership check). Lifted config-free out of a consumer mod's {@code NativeChainFire}
 * (the mod-specific original stays as a thin call site, mechanism unified here) so every consumer
 * that wants to compose native Hytale interaction content by REFERENCE (station presentations,
 * dialogue actions, moment vocabularies, ...) shares one resolve/fire routine instead of
 * re-deriving it.
 *
 * <p><b>A missing id is a hard no-op, never the engine's silent stub.</b> The engine's own {@code
 * RootInteraction.getRootInteractionIdOrUnknown} resolves an unknown id to an empty 0-operation
 * placeholder and merely logs a WARNING - a typo would silently fire a chain that does nothing,
 * with no signal at the call site. This util instead resolves via a direct {@code
 * RootInteraction.getAssetMap().getAsset(id)} lookup and treats {@code null} as a hard failure: a
 * guarded warn plus {@code false}, never falling through to {@code initChain} with an unresolved
 * root (fail-closed, one warn per failed fire, matching the repo's id-ref-only content-composition
 * principle).
 *
 * <p><b>{@code forceRemoteSync=false} does NOT keep a chain server-only.</b> {@code
 * InteractionManager.initChain} ORs the caller's {@code forceRemoteSync} argument with the root's
 * own {@code rootInteraction.needsRemoteSync()} ({@code InteractionChain}'s {@code requiresClient}
 * field), so ANY chain containing a client-package op still syncs to the owning client even though
 * this util always passes {@code false} here. A real player's client then needs to actually
 * execute the same root (desync risk if the id needs client ops the caller's client doesn't
 * expect); an NPC/entity-less caller auto-runs {@code simulationTick} server-side, no real client
 * needed.
 *
 * <p><b>World-thread only</b> (reads/mutates the entity's {@code InteractionManager} component);
 * the caller guarantees the thread. Every engine touch is try-guarded, so a missing component, an
 * unresolved id, or an engine throw all degrade to {@code false} rather than propagate.
 */
public final class NativeChainFire {

    private NativeChainFire() {
    }

    /**
     * Resolve {@code interactionId} in the {@code RootInteraction} asset store and queue it for
     * execution on {@code casterRef} as {@code interactionType}. Returns {@code true} on a
     * successful queue, {@code false} on any failure (the entity has no {@code InteractionManager},
     * the id does not resolve, or the engine call throws) - every failure path is already logged
     * (guarded FINE for a missing component, guarded WARN for an unresolved id or an engine throw),
     * so a caller only needs the boolean to decide its own result.
     */
    public static boolean fire(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> casterRef,
                               @Nonnull String interactionId, @Nonnull InteractionType interactionType) {
        try {
            InteractionManager manager = store.getComponent(casterRef,
                    InteractionModule.get().getInteractionManagerComponent());
            if (manager == null) {
                fine("entity has no InteractionManager - skipping " + interactionId);
                return false;
            }
            RootInteraction root = RootInteraction.getAssetMap().getAsset(interactionId);
            if (root == null) {
                warn("RootInteraction '" + interactionId + "' not found in asset map");
                return false;
            }
            InteractionContext context = InteractionContext.forInteraction(
                    manager, casterRef, interactionType, store);
            InteractionChain chain = manager.initChain(interactionType, context, root, false);
            manager.queueExecuteChain(chain);
            return true;
        } catch (Throwable t) {
            warn("trigger failed for '" + interactionId + "': " + t.getMessage());
            return false;
        }
    }

    private static void warn(@Nonnull String message) {
        try {
            ZiggfreedCommonPlugin.LOGGER.atWarning().log("[ziggfreed-common][interaction] " + message);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM: the flogger LOGGER can throw; swallow it.
        }
    }

    private static void fine(@Nonnull String message) {
        try {
            ZiggfreedCommonPlugin.LOGGER.atFine().log("[ziggfreed-common][interaction] " + message);
        } catch (Throwable ignored) {
            // log-manager-less unit JVM: the flogger LOGGER can throw; swallow it.
        }
    }
}
