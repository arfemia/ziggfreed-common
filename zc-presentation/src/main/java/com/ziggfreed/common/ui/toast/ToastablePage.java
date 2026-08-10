package com.ziggfreed.common.ui.toast;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPage;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.sound.Sound3D;

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
    // The auto-dismiss surface push is keyed on the player's CURRENTLY-ACTIVE toastable page
    // (ACTIVE below), NOT the instance that scheduled it. A toast outlives the page instance that
    // showed it: the dominant reopen flow is showToast(...) then openCustomPage(ref, store, new
    // SomePage(...)) - a DIFFERENT instance. The scheduled hide (~4s later) must push #ZigToast
    // into whatever instance is open NOW, not the dead one. Routing through ACTIVE does exactly
    // that; binding to the scheduling instance is what left toasts stuck (the old per-instance
    // `dismissed`-guarded surface only auto-dismissed when the page reopened as `this`).
    private static final ConcurrentHashMap<UUID, ToastablePage<?>> ACTIVE = new ConcurrentHashMap<>();

    // Flipped true by onDismiss, reset false by renderToastInto on every (re)build. Read inside
    // the world-thread push recheck (with ACTIVE membership + ref.isValid) so a stale push never
    // targets a page that has since been replaced. Also exposed via isDismissed() for subclasses
    // that drive their own off-thread sendUpdate (e.g. a live countdown).
    private volatile boolean dismissed = false;

    // The surface captures only the player UUID (same player for life), never `this`: each push
    // re-resolves the player's live toastable page from ACTIVE, so a hide scheduled by one page
    // instance correctly fires through whichever instance is open when it lands.
    private final ToastController toast = new ToastController(
            () -> this.playerRef.getUuid(),
            cmd -> pushToActive(this.playerRef.getUuid(), cmd));

    /** Route a toast command to the player's currently-active toastable page (or drop it). */
    private static void pushToActive(@Nullable UUID id, @Nonnull UICommandBuilder cmd) {
        if (id == null) {
            return;
        }
        ToastablePage<?> active = ACTIVE.get(id);
        if (active != null) {
            active.pushToastUpdate(id, cmd);
        }
    }

    /**
     * Float a toast over the player's CURRENTLY-ACTIVE toastable page from a page-less caller
     * (a service, an event system, a dialogue action) - the generic, transport-side counterpart
     * to the instance {@link #showToast(ToastSpec)}. Looks up the live page in the {@code ACTIVE}
     * registry (kept current by {@link #renderToastInto}) and shows the toast on it (which paints
     * via a {@code sendUpdate}, no reopen, scroll preserved, and plays the toast's SFX unless the
     * spec is {@link ToastSpec#silent()}). A no-op when the player has no toastable page open - the
     * toast is simply not shown, and the caller's other channels (e.g. a sound) are unaffected.
     */
    public static void showOnActive(@Nonnull UUID playerId, @Nonnull ToastSpec spec) {
        ToastablePage<?> active = ACTIVE.get(playerId);
        if (active != null) {
            active.showToast(spec);
        }
    }

    /**
     * Apply a toast command into THIS (active) page, rechecking on the world thread - where
     * {@code build}/{@code onDismiss} run - that this page is still the player's live toastable
     * page before the update lands. The engine {@code sendUpdate} only rechecks {@code ref.isValid}
     * inside its world hop; replicating the push here lets the ACTIVE-membership + {@code dismissed}
     * recheck run inside the hop too, so a delayed hide that races a navigation is reliably skipped
     * (an unguarded stale push would target whatever page replaced this one and crash the client -
     * "selector #ZigToast not found").
     */
    private void pushToastUpdate(@Nonnull UUID id, @Nonnull UICommandBuilder cmd) {
        Ref<EntityStore> ref = playerRef.getReference();
        if (ref == null) {
            return;
        }
        Store<EntityStore> store = ref.getStore();
        World world;
        try {
            world = store.getExternalData().getWorld();
        } catch (Throwable t) {
            return;
        }
        if (world == null) {
            return;
        }
        world.execute(() -> {
            if (dismissed || !ref.isValid() || ACTIVE.get(id) != this) {
                return;
            }
            try {
                Player p = store.getComponent(ref, Player.getComponentType());
                if (p == null) {
                    return;
                }
                p.getPageManager().updateCustomPage(new CustomPage(
                        getClass().getName(), false, false, lifetime,
                        cmd.getCommands(), UIEventBuilder.EMPTY_EVENT_BINDING_ARRAY));
            } catch (Throwable ignored) {
            }
        });
    }

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
     * reopen the page after this call). Also plays the toast's SFX (its kind default or
     * {@link ToastSpec#withSound override}, unless {@link ToastSpec#silent()}).
     */
    protected void showToast(@Nonnull ToastKind kind, @Nonnull Message message) {
        showToast(ToastSpec.of(kind, message));
    }

    /** Show a fully-specified toast (custom duration / title / icon / lines / sound). */
    protected void showToast(@Nonnull ToastSpec spec) {
        toast.show(spec);
        playToastSound(spec);
    }

    /**
     * Play the toast's 3D SFX at the player (world-thread; {@code showToast} runs in a page event
     * handler). Cosmetic and fully guarded: a missing asset / invalid ref degrades to silence,
     * never a throw. A {@link ToastSpec#silent()} toast or a kind/override with no id is a no-op.
     */
    private void playToastSound(@Nonnull ToastSpec spec) {
        String soundId = spec.effectiveSoundId();
        if (soundId == null || soundId.isEmpty()) {
            return;
        }
        try {
            Ref<EntityStore> ref = playerRef.getReference();
            if (ref == null) {
                return;
            }
            Sound3D.playAt(soundId, Sound3D.DEFAULT_CATEGORY, ref, ref.getStore(), "TOAST", false);
        } catch (Throwable ignored) {
            // Cosmetic only - never let an audio failure break the toast / page flow.
        }
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
        // This instance is (re)building, so it is the player's active toastable page: register it
        // so a hide scheduled by a PRIOR instance fires through this one, and clear the dismissed
        // guard. (build/onDismiss both run on the world thread, so the ACTIVE write is ordered
        // against the world-thread push recheck.)
        this.dismissed = false;
        UUID id = playerRef.getUuid();
        if (id != null) {
            ACTIVE.put(id, this);
        }
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
        // Deregister iff still the active page (a fresh instance may already have replaced us via
        // its renderToastInto on a self/cross reopen - do not clobber it).
        UUID id = playerRef.getUuid();
        if (id != null) {
            ACTIVE.remove(id, this);
        }
        super.onDismiss(ref, store);
    }
}
