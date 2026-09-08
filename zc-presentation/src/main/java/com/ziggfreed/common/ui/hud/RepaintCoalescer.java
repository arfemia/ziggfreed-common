package com.ziggfreed.common.ui.hud;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;

/**
 * Folds a burst of repaint requests into ONE paint, run where the caller says.
 *
 * <p>An event-driven HUD is asked to repaint once per event, and several events land in the same
 * instant on ordinary play: one swing of a pickaxe with several gathering quests pinned is several
 * objective events, one award moving several bars is several value changes. Each would otherwise
 * be a full paint and a packet. The first request queues the paint on the given executor; every
 * request that arrives before it has run is folded into it. When the paint runs it reads the LIVE
 * state, so whatever the burst ended on is what gets drawn.
 *
 * <p>This is not a poller and there is no clock: nothing runs when nothing was asked for. On a
 * server the executor is the player's own {@code World}, whose task queue is drained inside the
 * same tick the requests were made on - so a burst from an ECS system paints once, at the end of
 * that tick, on the world thread. A request arriving while the paint is running queues one more,
 * which is the right answer: it may have seen state the running paint did not.
 */
public final class RepaintCoalescer {

    private final AtomicBoolean queued = new AtomicBoolean();
    private final Runnable paint;

    public RepaintCoalescer(@Nonnull Runnable paint) {
        this.paint = paint;
    }

    /**
     * Ask for a paint on {@code where}. Folded into an already-queued one when there is one; if
     * {@code where} refuses the task (a world that stopped accepting them), the request is dropped
     * and the next one is free to try again.
     */
    public void request(@Nonnull Executor where) {
        if (!queued.compareAndSet(false, true)) {
            return;
        }
        try {
            where.execute(this::flush);
        } catch (Throwable refused) {
            queued.set(false);
        }
    }

    private void flush() {
        queued.set(false);
        paint.run();
    }

    /** Whether a paint is queued and not yet run; for a test reading the fold. */
    public boolean isQueued() {
        return queued.get();
    }
}
