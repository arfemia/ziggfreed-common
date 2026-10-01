package com.ziggfreed.common.subject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;

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

    // ==================== layered facets ====================

    /** Stands in for a pass-scoped collector a pass layers beside the player. */
    private record Tally(@Nonnull String label) {
    }

    @Test
    void anExtraAnswersByTypeAndTheSubjectKeepsItsIdAndName() {
        Session session = new Session("tester");
        Tally tally = new Tally("pass");
        Subject base = subjectWith(session);

        Subject layered = base.withFacets(tally);

        assertSame(tally, layered.handleAs(Tally.class), "an extra answers for its own type");
        assertSame(session, layered.handleAs(Session.class), "the original handle still answers");
        assertEquals(base.id(), layered.id());
        assertEquals(base.name(), layered.name());
        assertNull(layered.handleAs(Avatar.class), "nobody offers an avatar, so nothing is guessed");
    }

    @Test
    void theBaseHandleWinsOverAnExtraOfTheSameType() {
        Session session = new Session("base");

        Subject layered = subjectWith(session).withFacets(new Session("extra"));

        assertSame(session, layered.handleAs(Session.class),
                "an extra can never shadow what the original handle already is");
    }

    @Test
    void theBaseHandlesOwnFacetsAnswerBeforeAnExtra() {
        Avatar carried = new Avatar("carried");

        Subject layered = subjectWith(new RichHandle(new Session("tester"), carried))
                .withFacets(new Avatar("extra"));

        assertSame(carried, layered.handleAs(Avatar.class),
                "the original handle is read by exactly the rules handleAs always applied, facets included");
    }

    @Test
    void aHandleLessSubjectTakesAnExtra() {
        Tally tally = new Tally("pass");

        Subject layered = Subject.of(UUID.randomUUID(), "tester").withFacets(tally);

        assertSame(tally, layered.handleAs(Tally.class));
        assertNull(layered.handleAs(Session.class));
    }

    @Test
    void aNullExtraIsIgnored() {
        Session session = new Session("tester");
        Tally tally = new Tally("pass");
        Subject base = subjectWith(session);

        assertSame(base, base.withFacets((Object) null), "nothing to add leaves the subject as it was");
        Subject layered = base.withFacets(null, tally, null);
        assertSame(tally, layered.handleAs(Tally.class));
        assertSame(session, layered.handleAs(Session.class));
    }

    @Test
    void layeringALayeredSubjectKeepsEverythingInOrder() {
        Session session = new Session("tester");
        Tally older = new Tally("older");
        Avatar avatar = new Avatar("newer");

        Subject twice = subjectWith(session).withFacets(older).withFacets(avatar, new Tally("newer"));

        assertSame(session, twice.handleAs(Session.class), "the original handle survives a second layer");
        assertSame(older, twice.handleAs(Tally.class), "the older extra answers before a newer one");
        assertSame(avatar, twice.handleAs(Avatar.class), "the newer extra is kept");
    }

    @Test
    void aPlayerlessSubjectIsNullRatherThanAGuess() {
        // "Nobody standing anywhere" is a real answer: per-player engine state lives on the
        // player's own entity, so a subject invented around a missing player would read every
        // balance as zero and drop every write while reporting success.
        assertNull(Subject.of((Player) null));
        assertNull(Subject.of((PlayerRef) null, (Player) null));
    }
}
