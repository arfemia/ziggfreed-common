package com.ziggfreed.common.subject;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

/**
 * What a handle can be read back as: the one contract every engine, store, gate and reward handler
 * in this library reaches a player through.
 *
 * <p>The types here are the test's own, because the point is the DISPATCH rather than any particular
 * player representation - which is the whole reason the handle is opaque in the first place.
 */
class SubjectTest {

    /** Stands in for whatever a consumer attaches as its handle. */
    private record Session(@Nonnull String who) {
    }

    /** Stands in for the ONE type some reader insists on - a reward handler's player, say. */
    private record Avatar(@Nonnull String who) {
    }

    /** A rich handle: it is itself, and it can also produce the avatar it carries. */
    private record RichHandle(@Nonnull Session session, @Nonnull Avatar avatar)
            implements Subject.HandleFacets {

        @Override
        @Nullable
        public Object facet(@Nonnull Class<?> type) {
            return type.isAssignableFrom(Avatar.class) ? avatar : null;
        }
    }

    @Nonnull
    private static Subject subjectWith(@Nullable Object handle) {
        return new Subject(UUID.randomUUID(), "tester", handle);
    }

    @Test
    void aPlainHandleComesBackTypedAndNothingElseDoes() {
        Session session = new Session("tester");
        Subject subject = subjectWith(session);

        assertSame(session, subject.handleAs(Session.class));
        assertNull(subject.handleAs(Avatar.class),
                "a handle that offers nothing must never be guessed at");
    }

    @Test
    void aHandleLessSubjectAnswersNothing() {
        assertNull(Subject.of(UUID.randomUUID(), "tester").handleAs(Session.class));
    }

    @Test
    void aRichHandleAnswersForWhatItCarries() {
        Avatar avatar = new Avatar("tester");

        Subject subject = subjectWith(new RichHandle(new Session("tester"), avatar));

        assertSame(avatar, subject.handleAs(Avatar.class),
                "a reader asking only for the player representation must find the one the handle holds");
    }

    @Test
    void theDirectCastWinsOverTheFacet() {
        RichHandle handle = new RichHandle(new Session("tester"), new Avatar("tester"));

        assertSame(handle, subjectWith(handle).handleAs(RichHandle.class),
                "a handle can never shadow itself with something it merely offers");
    }

    @Test
    void anAnswerOfTheWrongTypeIsDiscardedRatherThanTrusted() {
        Subject subject = subjectWith((Subject.HandleFacets) type -> "not what anybody asked for");

        assertNull(subject.handleAs(Avatar.class));
    }

    @Test
    void aFacetWithNothingToOfferIsSimplyNoAnswer() {
        Subject subject = subjectWith((Subject.HandleFacets) type -> null);

        assertNull(subject.handleAs(Avatar.class));
    }
}
