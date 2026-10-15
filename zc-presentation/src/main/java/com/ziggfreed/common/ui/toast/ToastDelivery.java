package com.ziggfreed.common.ui.toast;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;

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
 * <p>The corner never repeats the game's own pickup notices: a row marked {@link ToastLine#pickupNoticed()} (an
 * item that landed as a pickup, whose notice already sits in that feed) is left out there, and a toast whose
 * every row was so marked shows nothing in the corner and plays no sound, the pickup's own having played. A
 * page still draws every row. This is the one place that rule lives, for every producer.
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
        deliverWhenSettled(playerRef, () -> spec, overflow);
    }

    /**
     * {@link #deliverWhenSettled(PlayerRef, ToastSpec, IntFunction)} with the toast decided as the delivery runs, so
     * a producer gathering what one moment raised (several notices written in one tick) builds one toast of them
     * then; {@code spec} answering null shows nothing. It is asked once: on the player's world, or now when there
     * is no world to hop to or the world refuses the task. A world that closes with the task still queued never
     * asks it, so a producer whose state the task drains keeps a way to retire that state itself.
     */
    public static void deliverWhenSettled(@Nonnull PlayerRef playerRef, @Nonnull Supplier<ToastSpec> spec,
            @Nullable IntFunction<Message> overflow) {
        Runnable task = () -> {
            ToastSpec built;
            try {
                built = spec.get();
            } catch (Throwable t) {
                SafeLog.fine("[toast] a toast could not be built: " + t.getMessage());
                return;
            }
            if (built != null) {
                deliver(playerRef, built, overflow);
            }
        };
        World world = worldOf(playerRef);
        if (world == null) {
            task.run();
            return;
        }
        try {
            world.execute(task);
        } catch (Throwable t) {
            task.run();
        }
    }

    /**
     * The decision, with no server behind it: the whole toast to {@code page} while one is showing (the page
     * plays its sound as it draws it), else each of {@link #feedRows} to {@code corner} and the toast's sound,
     * when it has one, to {@code sound} once; with no feed row left (every row pickup-noticed), nothing at all.
     */
    static void route(boolean pageShowing, @Nonnull ToastSpec spec, @Nullable IntFunction<Message> overflow,
            @Nonnull Consumer<ToastSpec> page, @Nonnull Consumer<ToastLine> corner, @Nonnull Consumer<String> sound) {
        if (pageShowing) {
            page.accept(spec);
            return;
        }
        List<ToastLine> rows = feedRows(spec, overflow);
        if (rows.isEmpty()) {
            return;
        }
        for (ToastLine row : rows) {
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
     * A {@link ToastLine#pickupNoticed() pickup-noticed} row is left out, since the game's own notice for it is
     * already in the feed, and a toast whose every row was is empty here: it shows nothing, not its headline.
     *
     * <p>A sectioned toast ({@link RewardToastLines}) reads flat here, as it always did: a notice is a title with
     * no rows under it, so a section's heading is dropped, an indented row is a plain notice, and the overflow
     * count counts reward rows only, never a heading.
     */
    @Nonnull
    static List<ToastLine> feedRows(@Nonnull ToastSpec spec, @Nullable IntFunction<Message> overflow) {
        List<ToastLine> rows = new ArrayList<>(spec.lines().size());
        boolean noticed = false;
        for (ToastLine row : spec.lines()) {
            if (row.pickupNoticed()) {
                noticed = true;
            } else if (!row.heading()) {
                rows.add(row.indented() ? row.withIndented(false) : row);
            }
        }
        if (rows.isEmpty()) {
            return noticed ? List.of() : List.of(new ToastLine(spec.iconItemId(), 1, spec.message()));
        }
        if (rows.size() <= FEED_ROWS) {
            return List.copyOf(rows);
        }
        if (overflow == null) {
            return List.copyOf(rows.subList(0, FEED_ROWS));
        }
        List<ToastLine> out = new ArrayList<>(rows.subList(0, FEED_ROWS - 1));
        out.add(ToastLine.text(overflow.apply(rows.size() - (FEED_ROWS - 1))));
        return out;
    }

    /**
     * The player's world, read on the caller's thread (a world thread, or a scheduler thread for a delayed toast
     * such as a dungeon's goal line): only the reference and the world handle are read here, and the Store itself
     * is touched only inside the world task.
     */
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
