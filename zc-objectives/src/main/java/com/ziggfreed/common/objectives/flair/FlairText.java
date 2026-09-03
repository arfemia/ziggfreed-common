package com.ziggfreed.common.objectives.flair;

import java.util.Map;

import javax.annotation.Nonnull;

import com.hypixel.hytale.server.core.Message;
import com.ziggfreed.common.feedback.moment.FeedbackEngine;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.i18n.NativeNames;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.util.SafeLog;

/**
 * What a flair is CALLED, and how a player is told they got one - the two player-facing readings
 * every flair surface shares, so the claim panel's chip, the book's reward preview and the toast
 * cannot disagree about a flair's name.
 *
 * <p><b>The name ladder.</b> The library has no flair catalogue: what a flair looks like and where
 * it shows is the vocabulary of whichever mod renders it, and that mod (or a content pack) ships
 * the flair's name as {@code flair.<id>.name} in its own lang file. So a name is resolved
 * namespace-agnostically through {@link ContentKeys} - any loaded lang file that carries the key
 * names it - and a flair nothing names reads as its id spelled out ({@code sawmill_gold} as
 * "Sawmill Gold"), which is a traceable fallback rather than a raw key at a player.
 *
 * <p><b>The notice</b> is an authored feedback moment ({@link #UNLOCKED_MOMENT}), so it draws
 * through the same toast engine every quest and achievement notice draws through: into the page the
 * player has open, or the corner feed when none is, with whatever sound and broadcast the moment
 * file says. The library ships a neutral default file; a pack or an owner overrides it by name.
 */
public final class FlairText {

    /** The key family the shipped {@code ziggfreedcommon.flair.lang} resolves under. */
    public static final String PREFIX = "ziggfreedcommon.flair.";

    /** The feedback moment fired for a NEW unlock; the shipped default toasts the flair's name. */
    public static final String UNLOCKED_MOMENT = "Flair_Unlocked";

    /** The moment value carrying the flair's localized name, which the shipped toast line reads. */
    static final String NAME_ARG = "name";

    /** The moment value carrying the flair's id, for a command or a variant that wants it. */
    static final String FLAIR_ARG = "flair";

    private FlairText() {
    }

    /** The authored name key for a flair, WITHOUT any namespace: {@code flair.<id>.name}. */
    @Nonnull
    public static String nameKey(@Nonnull String flairId) {
        return "flair." + flairId + ".name";
    }

    /** True when some loaded lang file names this flair; the one presence probe the library has. */
    public static boolean isNamed(@Nonnull String flairId) {
        return ContentKeys.known(nameKey(flairId));
    }

    /**
     * What the flair is called, as a nested client-resolved {@link Message}: the authored name
     * under whichever namespace ships it, else the id spelled out as words.
     */
    @Nonnull
    public static Message nameOf(@Nonnull String flairId) {
        String key = nameKey(flairId);
        return ContentKeys.known(key) ? ContentKeys.tr(key) : Msg.raw(NativeNames.prettify(flairId));
    }

    /**
     * Tell {@code who} the flair is theirs now, through the authored moment. Guarded whole: a
     * notice that cannot be drawn must never undo the unlock that earned it.
     */
    static void announceUnlocked(@Nonnull Subject who, @Nonnull String flairId) {
        try {
            if (!FeedbackEngine.answers(UNLOCKED_MOMENT)) {
                return;
            }
            FeedbackEngine.fire(UNLOCKED_MOMENT, who,
                    Map.of(FLAIR_ARG, flairId, NAME_ARG, nameOf(flairId)));
        } catch (Throwable t) {
            SafeLog.fine("[flair] the unlock notice for '" + flairId + "' could not be drawn: "
                    + t.getMessage());
        }
    }
}
