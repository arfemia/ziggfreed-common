package com.ziggfreed.common.ui.toast;

import javax.annotation.Nonnull;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Mod-agnostic page base adding an in-page toast overlay for in-menu action feedback. The
 * toast renders as the last top-level sibling of the page content ({@code #ZigToast} /
 * {@code #ZigToastText}) so it draws on top, floating over the open menu where the
 * bottom-right notification feed is hidden. The engine + schema live in {@code ui/toast}
 * and are reused by a future HUD variant.
 *
 * <p>Contract for subclasses:
 * <ol>
 *   <li>Call {@link #renderToastInto(UICommandBuilder)} as the LAST line of {@code build(...)};
 *       it appends the shared {@code Pages/ZigToast.ui} fragment as the last root sibling and
 *       paints the current toast (or idle) on every render. No per-page markup.</li>
 *   <li>Call {@link #showToast(ToastKind, Message)} (or {@link #showToast(ToastSpec)}) in place
 *       of an off-page {@code Notify} when the feedback should float over the still-open page,
 *       then reopen the page (most handlers already do via
 *       {@code openCustomPage(ref, store, this)}) so the toast renders on the next build.</li>
 * </ol>
 *
 * <p>Show and hide both ride a {@code sendUpdate} (the toast toggles via its {@code Anchor},
 * not {@code Visible}), so a toast appears and clears without a page reopen, preserving scroll
 * position. The surface push self-marshals to the world thread, so it is safe to invoke from
 * the auto-dismiss scheduler thread.
 */
public abstract class ToastablePage<T> extends InteractiveCustomUIPage<T> {

    // Per-player toast state lives in ToastStore (keyed by uuid) so a toast set on this instance
    // still renders after the page reopens as a fresh instance.
    //
    // Flipped true by onDismiss, reset false by renderToastInto on every (re)build. Guards the
    // toast surface push: the auto-dismiss fires ~4s after a toast shows, by which point the
    // player may have navigated to another page. updateCustomPage does NOT verify the page is
    // still active, so an unguarded stale push targets whatever page is now open and crashes the
    // client ("selector #ZigToast.Anchor not found"). Skipping the push is safe - the toast store
    // still clears (ToastController calls clearIfCurrent regardless), so the next page opens idle.
    //
    // CRUCIAL: the reset in renderToastInto. The common reopen flow is showToast(...) then
    // openCustomPage(ref, store, this) - and PageManager.openCustomPage calls onDismiss on the
    // OUTGOING page (which is THIS same instance) before rebuilding it. Without the reset, the
    // self-reopen leaves dismissed=true forever, so the scheduled hide is skipped and the toast
    // never auto-dismisses. The reset only fires when this instance rebuilds (is active again);
    // an instance replaced by a DIFFERENT page never rebuilds, so its guard correctly stays set.
    private volatile boolean dismissed = false;

    private final ToastController toast = new ToastController(
            () -> this.playerRef.getUuid(),
            cmd -> { if (!this.dismissed) this.sendUpdate(cmd, new UIEventBuilder(), false); });

    protected ToastablePage(@Nonnull PlayerRef playerRef, @Nonnull CustomPageLifetime lifetime,
                            @Nonnull BuilderCodec<T> eventDataCodec) {
        super(playerRef, lifetime, eventDataCodec);
    }

    /**
     * True once this page instance has been navigated away from / closed / client-dismissed
     * (set by {@link #onDismiss}, cleared on the next {@link #renderToastInto} rebuild). A
     * subclass that pushes an off-thread {@code sendUpdate} (e.g. a live countdown driven by
     * a queue listener) MUST guard it on {@code !isDismissed()}: the engine does not verify
     * the page is still active, so an unguarded stale push targets whatever page replaced it
     * and crashes the client - the same hazard the toast surface guards.
     */
    protected final boolean isDismissed() {
        return dismissed;
    }

    /**
     * Show a toast for an in-menu action result. Sets the current toast and schedules its
     * auto-dismiss; the toast paints on the page's next {@code build()} (the handler should
     * reopen the page after this call).
     */
    protected void showToast(@Nonnull ToastKind kind, @Nonnull Message message) {
        toast.show(ToastSpec.of(kind, message));
    }

    /** Show a fully-specified toast (custom duration / title / icon / lines). */
    protected void showToast(@Nonnull ToastSpec spec) {
        toast.show(spec);
    }

    /**
     * Arm a toast to paint on THIS build (no immediate push), for a one-shot toast shown on a
     * page's first open. Call BEFORE {@link #renderToastInto} in the same {@code build(...)} so
     * that render paints it; only its (later, page-is-live) auto-dismiss rides a push. Guard it
     * behind a one-shot flag so a page reopen does not re-fire it.
     */
    protected void primeToast(@Nonnull ToastSpec spec) {
        toast.prime(spec);
    }

    /**
     * Append the shared toast markup ({@code Pages/ZigToast.ui}) and apply the current toast
     * (or idle) state. Call LAST in {@code build(...)} so the fragment lands as the last root
     * sibling and draws on top of the page content.
     */
    protected void renderToastInto(@Nonnull UICommandBuilder cmd) {
        // This instance is (re)building, so it is the active page again: clear the dismissed guard
        // so a hide-push scheduled before a self-reopen (openCustomPage(this), which fires
        // onDismiss on THIS instance) is not skipped. Without this, toasts never auto-dismiss.
        this.dismissed = false;
        cmd.append("Pages/ZigToast.ui");
        toast.renderInto(cmd);
    }

    /**
     * Marks this page instance dead so the toast's scheduled auto-dismiss does not push a
     * {@code #ZigToast} update to whatever page has since replaced it. {@code PageManager}
     * invokes this on navigate ({@code openCustomPage}), close ({@code setPage}), and client
     * dismiss, all before the next page builds - so the {@code dismissed} read in the surface
     * push is reliably set by the time a stale auto-dismiss fires.
     */
    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        this.dismissed = true;
        super.onDismiss(ref, store);
    }
}
