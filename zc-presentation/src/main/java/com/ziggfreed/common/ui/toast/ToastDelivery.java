package com.ziggfreed.common.ui.toast;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntFunction;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.feedback.Notify;
import com.ziggfreed.common.util.SafeLog;

/**
 * Shows a toast raised OUTSIDE a page's own handler (a conversation line's payout, a used item's) where the
 * player is looking: drawn INTO the toastable page they have open ({@link ToastablePage#showOnActive}), since the
 * corner feed sits hidden behind an open page, and otherwise in the corner feed ({@link Notify}), one notice per
 * row, since a feed notice is a title with no rows. It is the rule a feedback moment's toast follows, as one
 * helper for a producer that composes its own {@link ToastSpec}.
 *
 * <p>The toast's sound ({@link ToastSpec#effectiveSoundId}) plays once wherever it is shown: the page plays it
 * as it draws the toast, and the corner plays it once for the whole toast, however many notices its rows
 * become ({@link ToastSounds#play}).
 *
 * <p>{@link #deliverWhenSettled} waits for what raised the toast to settle first: a conversation line that closes
 * its page must not draw into a page that is about to go, and one that reopens the page or opens another draws
 * into that one.
 */
public final class ToastDelivery {

    /**
     * The most corner notices one toast becomes. The feed shows seven lines and drains oldest first, so one
     * toast never fills it alone.
     */
    public static final int FEED_ROWS = 5;

    private ToastDelivery() {
    }

    /**
     * Show {@code spec} to the player where they are looking now; {@code overflow} words the last corner notice
     * when the rows run past {@link #FEED_ROWS} (null cuts the rest). World thread; never throws.
     */
    public static void deliver(@Nonnull PlayerRef playerRef, @Nonnull ToastSpec spec,
            @Nullable IntFunction<Message> overflow) {
        try {
            UUID viewer = playerRef.getUuid();
            route(viewer != null && ToastablePage.isShowing(viewer), spec, overflow,
                    page -> ToastablePage.showOnActive(viewer, page),
                    row -> Notify.withIcon(playerRef, row.text(), null, row.iconItemId(), spec.kind().feedStyle()),
                    soundId -> ToastSounds.play(playerRef, soundId));
        } catch (Throwable t) {
            SafeLog.fine("[toast] a toast could not be delivered: " + t.getMessage());
        }
    }

    /**
     * {@link #deliver}, on a later task of the player's world, once the click or the use that raised the toast
     * has settled. With no world to hop to, now.
     */
    public static void deliverWhenSettled(@Nonnull PlayerRef playerRef, @Nonnull ToastSpec spec,
            @Nullable IntFunction<Message> overflow) {
        World world = worldOf(playerRef);
        if (world == null) {
            deliver(playerRef, spec, overflow);
            return;
        }
        try {
            world.execute(() -> deliver(playerRef, spec, overflow));
        } catch (Throwable t) {
            deliver(playerRef, spec, overflow);
        }
    }

    /**
     * The decision, with no server behind it: the whole toast to {@code page} while one is showing (the page
     * plays its sound as it draws it), else each of {@link #feedRows} to {@code corner} and the toast's sound,
     * when it has one, to {@code sound} once.
     */
    static void route(boolean pageShowing, @Nonnull ToastSpec spec, @Nullable IntFunction<Message> overflow,
            @Nonnull Consumer<ToastSpec> page, @Nonnull Consumer<ToastLine> corner, @Nonnull Consumer<String> sound) {
        if (pageShowing) {
            page.accept(spec);
            return;
        }
        for (ToastLine row : feedRows(spec, overflow)) {
            corner.accept(row);
        }
        String soundId = spec.effectiveSoundId();
        if (soundId != null && !soundId.isEmpty()) {
            sound.accept(soundId);
        }
    }

    /**
     * What the corner feed shows of {@code spec}: one notice per row, in order, at most {@link #FEED_ROWS}, the
     * last spent on {@code overflow} when more did not fit; a toast with no rows is its headline and picture.
     */
    @Nonnull
    static List<ToastLine> feedRows(@Nonnull ToastSpec spec, @Nullable IntFunction<Message> overflow) {
        List<ToastLine> rows = spec.lines();
        if (rows.isEmpty()) {
            return List.of(new ToastLine(spec.iconItemId(), 1, spec.message()));
        }
        if (rows.size() <= FEED_ROWS) {
            return rows;
        }
        if (overflow == null) {
            return List.copyOf(rows.subList(0, FEED_ROWS));
        }
        List<ToastLine> out = new ArrayList<>(rows.subList(0, FEED_ROWS - 1));
        out.add(ToastLine.text(overflow.apply(rows.size() - (FEED_ROWS - 1))));
        return out;
    }

    /** The player's world, read on the caller's thread: both callers pay on the world thread, and the Store itself is touched only inside the world task. */
    @Nullable
    private static World worldOf(@Nonnull PlayerRef playerRef) {
        try {
            Ref<EntityStore> ref = playerRef.getReference();
            return ref == null || !ref.isValid() ? null : ref.getStore().getExternalData().getWorld();
        } catch (Throwable t) {
            return null;
        }
    }
}
