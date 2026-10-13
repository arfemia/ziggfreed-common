package com.ziggfreed.common.ui.toast;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
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
    private final Predicate<UUID> showing;

    /** A controller whose surface is always there to show on, so a queued notice always gets its turn. */
    public ToastController(@Nonnull Supplier<UUID> playerId, @Nonnull ToastSurface surface) {
        this(playerId, surface, id -> true);
    }

    /**
     * A controller over a surface that can go away: {@code showing} answers, when a toast's time is up,
     * whether the player still has the surface up, and when not, the notices queued behind the toast are
     * dropped rather than shown into nothing.
     */
    public ToastController(@Nonnull Supplier<UUID> playerId, @Nonnull ToastSurface surface,
                           @Nonnull Predicate<UUID> showing) {
        this.playerId = playerId;
        this.surface = surface;
        this.showing = showing;
    }

    /** Show {@code spec} now (push), schedule its auto-dismiss (push). {@link #offer} answering nothing. */
    public void show(@Nonnull ToastSpec spec) {
        offer(spec);
    }

    /**
     * {@link #show}, answering whether it showed now: false when there is no player, or when a
     * higher-ranked toast still on screen kept it off ({@link ToastStore#HELD_BACK}), so it waits its turn
     * behind that one instead.
     */
    public boolean offer(@Nonnull ToastSpec spec) {
        UUID id = playerId.get();
        if (id == null) {
            return false;
        }
        final long gen = ToastStore.show(id, spec);
        if (gen == ToastStore.HELD_BACK) {
            return false;
        }

        UICommandBuilder showCmd = new UICommandBuilder();
        ToastRenderer.apply(showCmd, spec);
        surface.push(showCmd);
        scheduleHide(id, spec, gen);
        return true;
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
        if (gen != ToastStore.HELD_BACK) {
            scheduleHide(id, spec, gen);
        }
    }

    /**
     * When {@code spec}'s time is up, end it and put up the best notice queued behind it, with its own hide
     * in turn, or go idle when none waits ({@link ToastStore#clearIfCurrent}). A newer toast that replaced
     * this one owns the slot, so this timer then does nothing. A notice put up from the queue plays no
     * sound: a moment's toast played its own as it arrived, and a click's answer that had to wait has lost
     * its moment.
     */
    private void scheduleHide(@Nonnull UUID id, @Nonnull ToastSpec spec, long gen) {
        long ttl = spec.durationMs() > 0 ? spec.durationMs() : DEFAULT_TTL_MS;
        HytaleServer.SCHEDULED_EXECUTOR.schedule(() -> {
            ToastStore.Next next = ToastStore.clearIfCurrent(id, gen, showing.test(id));
            if (!next.cleared()) {
                return;
            }
            UICommandBuilder cmd = new UICommandBuilder();
            ToastSpec queued = next.spec();
            if (queued == null) {
                ToastRenderer.applyIdle(cmd);
                surface.push(cmd);
                return;
            }
            ToastRenderer.apply(cmd, queued);
            surface.push(cmd);
            scheduleHide(id, queued, next.gen());
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
