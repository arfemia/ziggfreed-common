package com.ziggfreed.common.feedback.moment;

import javax.annotation.Nonnull;

import com.ziggfreed.common.subject.Subject;

/**
 * What a subject says about the PERSONAL notifications it wants to see.
 *
 * <p>An authored moment is static data, and some of what decides whether a player should be
 * bothered is not: a consumer that lets a player turn its own notifications down holds that answer
 * on the player, per player, at the instant the moment happens. This is how such a consumer says so
 * without the moment engine learning anything about it - the subject's own handle answers for this
 * type ({@link Subject.HandleFacets}), the engine asks before it draws a toast, and a handle that
 * says nothing is simply a player who wants what was authored.
 *
 * <p><b>Only the personal toast is gated.</b> A server-wide banner belongs to everyone watching, a
 * sound is the moment being audible, and a command is server business; a player turning their own
 * notifications down is a statement about their own screen and nothing else. That split is
 * deliberate and matches what consumers already do by hand.
 */
@FunctionalInterface
public interface FeedbackAudience {

    /**
     * Does this subject want the personal notification for {@code momentId}? Answer true when in
     * doubt: a moment nobody has an opinion about is one the author asked for.
     */
    boolean wantsNotification(@Nonnull String momentId);
}
