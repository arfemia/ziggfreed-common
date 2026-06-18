package com.ziggfreed.common.ui.toast;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.HytaleServer;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;

/**
 * Transport-agnostic toast engine. Binds a player (a {@link UUID} supplier) and a
 * {@link ToastSurface} (how to push UI commands) to the shared per-player
 * {@link ToastStore}. Reused unchanged by every transport.
 *
 * <p>{@link #show} writes the toast to the store and immediately pushes the show commands
 * through the surface (a page {@code sendUpdate} - no reopen, so scroll is kept), then
 * schedules the hide push. The store key is the player, so a page that <i>does</i> reopen
 * for other reasons re-paints the toast from {@link #renderInto}. A generation token (held
 * by the store) stops a stale scheduled hide from clearing a newer toast.
 */
public final class ToastController {

    private static final long DEFAULT_TTL_MS = 4000L;

    private final Supplier<UUID> playerId;
    private final ToastSurface surface;

    public ToastController(@Nonnull Supplier<UUID> playerId, @Nonnull ToastSurface surface) {
        this.playerId = playerId;
        this.surface = surface;
    }

    /** Show {@code spec} now (push), schedule its auto-dismiss (push). */
    public void show(@Nonnull ToastSpec spec) {
        UUID id = playerId.get();
        if (id == null) {
            return;
        }
        final long gen = ToastStore.show(id, spec);

        UICommandBuilder showCmd = new UICommandBuilder();
        ToastRenderer.apply(showCmd, spec);
        surface.push(showCmd);

        long ttl = spec.durationMs() > 0 ? spec.durationMs() : DEFAULT_TTL_MS;
        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            if (ToastStore.clearIfCurrent(id, gen)) {
                UICommandBuilder idleCmd = new UICommandBuilder();
                ToastRenderer.applyIdle(idleCmd);
                surface.push(idleCmd);
            }
        }, ttl, TimeUnit.MILLISECONDS);
    }

    /**
     * Set {@code spec} as the current toast and schedule its auto-dismiss, WITHOUT an
     * immediate show-push. Use when arming a toast from inside {@code build(...)} (the
     * initial open, before the page is live): a show-push then would target a not-yet-live
     * page and crash the client, so instead the SAME build's {@link #renderInto} paints it,
     * and only the (later, page-is-live) hide rides the surface push.
     */
    public void prime(@Nonnull ToastSpec spec) {
        UUID id = playerId.get();
        if (id == null) {
            return;
        }
        final long gen = ToastStore.show(id, spec);
        long ttl = spec.durationMs() > 0 ? spec.durationMs() : DEFAULT_TTL_MS;
        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            if (ToastStore.clearIfCurrent(id, gen)) {
                UICommandBuilder idleCmd = new UICommandBuilder();
                ToastRenderer.applyIdle(idleCmd);
                surface.push(idleCmd);
            }
        }, ttl, TimeUnit.MILLISECONDS);
    }

    /**
     * Apply the player's current toast (or idle) into a full {@code build()} command stream,
     * so a toast survives a page reopen. Call this in the subclass {@code build(...)}.
     */
    public void renderInto(@Nonnull UICommandBuilder cmd) {
        UUID id = playerId.get();
        ToastSpec spec = id == null ? null : ToastStore.currentFor(id);
        if (spec != null) {
            ToastRenderer.apply(cmd, spec);
        } else {
            ToastRenderer.applyIdle(cmd);
        }
    }
}
