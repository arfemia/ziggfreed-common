package com.ziggfreed.common.feedback.moment;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nonnull;

import com.ziggfreed.common.util.SafeLog;

/**
 * The other places a moment can already be readable, so the corner feed does not repeat them.
 *
 * <p>A notice belongs where the player is looking, and {@link FeedbackEngine} already knows one
 * such place: with a menu open it draws the moment INTO the page rather than behind it. This is the
 * same decision for a surface the engine cannot see. A HUD that already spells out what a moment
 * says - a step counting up on a quest tracker the player pinned it to - answers here, and that
 * moment's feed notice is dropped rather than stacked on top of a panel already showing it. Nothing
 * else about the moment changes: the sound still plays, the banner still goes out, the command
 * still runs, and with a menu open the in-page toast is drawn as before, since the surface behind
 * the menu is not being read.
 *
 * <p><b>Who registers.</b> Whoever OWNS such a surface, from its own setup, because only that layer
 * can see both the moment's vocabulary and the panel drawing it; this library's own tracked-quest
 * HUD is registered from the progression bootstrap. Readers are additive and asked in registration
 * order until one says yes, so two surfaces can each speak for themselves.
 *
 * <p><b>A reader is a cheap, silent question.</b> It is asked on the path of an ordinary progress
 * tick, so it reads state somebody else already keeps rather than computing any; one that throws is
 * logged once at fine and counts as "not on screen", because a notice too many is better than a
 * notice lost.
 */
public final class FeedbackSurfaces {

    /**
     * Can {@code viewer} already read this moment somewhere that is not the corner feed?
     *
     * @param viewer   the player the notice would go to
     * @param momentId what happened, spelled the way it was fired
     * @param args     what the moment carried, so a reader can tell WHICH thing it is about
     */
    @FunctionalInterface
    public interface Reader {

        boolean alreadyReadable(@Nonnull UUID viewer, @Nonnull String momentId,
                @Nonnull Map<String, Object> args);
    }

    private static final List<Reader> READERS = new CopyOnWriteArrayList<>();

    private FeedbackSurfaces() {
    }

    /** Add a surface's own answer. Registered once from the owning layer's setup. */
    public static void register(@Nonnull Reader reader) {
        READERS.add(reader);
    }

    /** Drop every registered reader. For a test that installs its own. */
    public static void clearForTests() {
        READERS.clear();
    }

    /**
     * Does any registered surface already show this moment to this player? False when nothing is
     * registered, which is every server that installs no such surface.
     */
    static boolean alreadyReadable(@Nonnull UUID viewer, @Nonnull String momentId,
            @Nonnull Map<String, Object> args) {
        for (Reader reader : READERS) {
            try {
                if (reader.alreadyReadable(viewer, momentId, args)) {
                    return true;
                }
            } catch (Throwable t) {
                SafeLog.fine("moment surface check failed: " + t.getMessage());
            }
        }
        return false;
    }
}
