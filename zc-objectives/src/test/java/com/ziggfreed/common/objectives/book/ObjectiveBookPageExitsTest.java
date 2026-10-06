package com.ziggfreed.common.objectives.book;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.route.Destination;
import com.ziggfreed.common.ui.toast.ToastSpec;

/**
 * Every event the book hears is answered exactly once, whatever happens on the way: a state action reopens
 * on the next state, a verb with nobody to act for still reopens, an unknown or legacy action gets an empty
 * update, a tab that takes an action and forgets to answer is answered for, a tab that throws is answered
 * for, and a consumer that takes the screen is never answered over. A client whose event goes unanswered
 * stays locked, so this is the page's one hard rule.
 */
class ObjectiveBookPageExitsTest {

    /** Every way the context can answer, counted. */
    static final class Recorder implements BookContext.Host {

        int sends;
        int closes;
        final List<BookState> reopens = new ArrayList<>();
        final List<ToastSpec> toasts = new ArrayList<>();
        final List<Destination> destinations = new ArrayList<>();
        @Nullable BookState kept;

        int answers() {
            return sends + closes + reopens.size();
        }

        @Override
        public void send(@Nullable UICommandBuilder cmd, @Nullable UIEventBuilder events) {
            sends++;
        }

        @Override
        public void reopen(@Nonnull BookState next) {
            reopens.add(next);
        }

        @Override
        public void close() {
            closes++;
        }

        @Override
        public void toast(@Nonnull ToastSpec spec) {
            toasts.add(spec);
        }

        @Override
        public boolean openDestination(@Nonnull Destination destination) {
            destinations.add(destination);
            return false;
        }

        @Override
        public void keep(@Nonnull BookState state) {
            kept = state;
        }

        @Nullable
        @Override
        public Subject subject(boolean achievements) {
            return null;
        }
    }

    /** A tab that declines every action, so the shell answers everything. */
    private static BookTab tab(@Nonnull String id, @Nonnull BiPredicate<BookContext, String> handler) {
        return new BookTab() {
            @Nonnull
            @Override
            public String id() {
                return id;
            }

            @Nonnull
            @Override
            public String document() {
                return "Pages/Test.ui";
            }

            @Override
            public void build(@Nonnull BookContext ctx) {
            }

            @Override
            public boolean handle(@Nonnull BookContext ctx, @Nonnull String action,
                    @Nonnull ObjectiveBookEventData data) {
                return handler.test(ctx, action);
            }
        };
    }

    private static final BookTab DECLINES = tab(ObjectiveBookPage.TAB_QUESTS, (ctx, action) -> false);

    @Nonnull
    private static Recorder run(@Nonnull BookState state, @Nonnull BookTab tab, @Nonnull ObjectiveBookEventData data) {
        return run(state, tab, ObjectiveBookDeps.DEFAULTS, data);
    }

    @Nonnull
    private static Recorder run(@Nonnull BookState state, @Nonnull BookTab tab, @Nonnull ObjectiveBookDeps deps,
            @Nonnull ObjectiveBookEventData data) {
        Recorder host = new Recorder();
        BookActions.handle(BookContext.forEvent(host, tab, state, deps, null, null, null, null, 0L), data);
        return host;
    }

    @Nonnull
    private static ObjectiveBookEventData event(@Nullable String action) {
        ObjectiveBookEventData data = new ObjectiveBookEventData();
        data.action = action;
        return data;
    }

    static List<String> everyAction() {
        return BookActions.ALL;
    }

    @ParameterizedTest
    @MethodSource("everyAction")
    void everyActionIsAnsweredExactlyOnce(String action) {
        assertEquals(1, run(BookState.of(null), DECLINES, event(action)).answers(), action);
        ObjectiveBookEventData withId = event(action);
        withId.id = "q1";
        withId.section = "ready";
        withId.view = BookState.VIEW_BROWSE;
        withId.threshold = "100";
        assertEquals(1, run(BookState.of(ObjectiveBookPage.TAB_ACHIEVEMENTS), DECLINES, withId).answers(),
                action + " with its payload");
    }

    @Test
    void theNewActionsAreAllRouted() {
        for (String action : List.of(BookActions.VIEW, BookActions.SECTION, BookActions.OPEN,
                BookActions.CLEAR_FILTERS, BookActions.ACCEPT, BookActions.COLLECT)) {
            assertTrue(BookActions.ALL.contains(action), action + " is one of the answered actions");
        }
    }

    @Test
    void anUnknownActionGetsAnEmptyUpdate() {
        Recorder host = run(BookState.of(null), DECLINES, event("no_such_thing"));
        assertEquals(1, host.sends);
        assertEquals(1, host.answers());
    }

    @Test
    void anEmptyActionAndCloseCloseTheBook() {
        assertEquals(1, run(BookState.of(null), DECLINES, event(null)).closes);
        assertEquals(1, run(BookState.of(null), DECLINES, event(BookActions.CLOSE)).closes);
    }

    @Test
    void aStateActionReopensOnTheNextState() {
        BookState start = BookState.of(ObjectiveBookPage.TAB_ACHIEVEMENTS).withSelected("a1");

        ObjectiveBookEventData view = event(BookActions.VIEW);
        view.view = BookState.VIEW_STATISTICS;
        assertEquals(start.withView(BookState.VIEW_STATISTICS), run(start, DECLINES, view).reopens.get(0));

        ObjectiveBookEventData section = event(BookActions.SECTION);
        section.section = "feats";
        section.open = "true";
        assertEquals(start.withSection("feats", true), run(start, DECLINES, section).reopens.get(0));

        ObjectiveBookEventData category = event(BookActions.CATEGORY);
        category.dropdownValue = "combat";
        assertEquals("combat", run(start, DECLINES, category).reopens.get(0).category());

        ObjectiveBookEventData status = event(BookActions.STATUS);
        status.id = "earned";
        assertEquals("earned", run(start, DECLINES, status).reopens.get(0).status());

        ObjectiveBookEventData sort = event(BookActions.SORT);
        sort.dropdownValue = "az";
        assertEquals("az", run(start, DECLINES, sort).reopens.get(0).sort());

        ObjectiveBookEventData tag = event(BookActions.TAG);
        tag.dropdownValue = "daily";
        assertEquals("daily", run(start, DECLINES, tag).reopens.get(0).tag());

        ObjectiveBookEventData search = event(BookActions.SEARCH);
        search.searchInput = "ghoul";
        assertEquals("ghoul", run(start, DECLINES, search).reopens.get(0).search());

        ObjectiveBookEventData select = event(BookActions.SELECT);
        select.id = "a2";
        assertEquals(start.withSelected("a2"), run(start, DECLINES, select).reopens.get(0));

        BookState filtered = start.withFilters("combat", "earned", "az", "ghoul", "daily");
        assertEquals("", run(filtered, DECLINES, event(BookActions.CLEAR_SEARCH)).reopens.get(0).search());
        BookState cleared = run(filtered, DECLINES, event(BookActions.CLEAR_FILTERS)).reopens.get(0);
        assertEquals(start.withFilters(null, null, "az", null, null), cleared,
                "Clear filters drops every filter and the search, and keeps the sort and the selection");
    }

    @Test
    void theLiveSearchFieldRidesEveryReopen() {
        ObjectiveBookEventData view = event(BookActions.VIEW);
        view.view = BookState.VIEW_BROWSE;
        view.searchInput = "typed, not searched";
        BookState next = run(BookState.of(ObjectiveBookPage.TAB_ACHIEVEMENTS), DECLINES, view).reopens.get(0);
        assertEquals("typed, not searched", next.search());
    }

    @Test
    void openLandsOnTheRowInTheViewThatShowsIt() {
        ObjectiveBookEventData open = event(BookActions.OPEN);
        open.tab = ObjectiveBookPage.TAB_ACHIEVEMENTS;
        open.view = BookState.VIEW_OVERVIEW;
        open.id = "ghoul_breaker_2026";
        BookState next = run(BookState.of(ObjectiveBookPage.TAB_ACHIEVEMENTS), DECLINES, open).reopens.get(0);
        assertEquals(BookState.VIEW_BROWSE, next.view());
        assertEquals("ghoul_breaker_2026", next.selectedId());

        ObjectiveBookEventData across = event(BookActions.OPEN);
        across.tab = ObjectiveBookPage.TAB_QUESTS;
        across.id = "the_lantern";
        BookState quest = run(BookState.of(ObjectiveBookPage.TAB_ACHIEVEMENTS).withFilters("combat", null, null,
                null, null), DECLINES, across).reopens.get(0);
        assertEquals(BookState.opening(ObjectiveBookPage.TAB_QUESTS, "the_lantern"), quest,
                "another tab opens fresh, on the row");
    }

    @Test
    void aVerbWithNobodyToActForStillReopensOnTheSameState() {
        BookState start = BookState.of(ObjectiveBookPage.TAB_QUESTS).withSelected("q1");
        for (String verb : BookActions.VERBS) {
            Recorder host = run(start, DECLINES, event(verb));
            assertEquals(List.of(start), host.reopens, verb + " lands back on the same quest");
        }
    }

    @Test
    void aTabThatAnswersIsNeverAnsweredOver() {
        BookTab partial = tab(ObjectiveBookPage.TAB_QUESTS, (ctx, action) -> {
            ctx.keep(ctx.state().withSelected("q2"));
            ctx.sendPartial();
            return true;
        });
        Recorder host = run(BookState.of(null), partial, event(BookActions.SELECT));
        assertEquals(1, host.sends);
        assertEquals(1, host.answers());
        assertEquals("q2", host.kept == null ? null : host.kept.selectedId(), "the page remembers the partial");
    }

    @Test
    void aTabThatTakesAnActionAndForgetsToAnswerIsAnsweredFor() {
        BookTab forgetful = tab(ObjectiveBookPage.TAB_QUESTS, (ctx, action) -> true);
        assertEquals(1, run(BookState.of(null), forgetful, event(BookActions.SELECT)).answers());
    }

    @Test
    void aTabThatThrowsIsAnsweredFor() {
        BookTab throwing = tab(ObjectiveBookPage.TAB_QUESTS, (ctx, action) -> {
            throw new IllegalStateException("boom");
        });
        assertEquals(1, run(BookState.of(null), throwing, event(BookActions.SELECT)).answers());
    }

    @Test
    void theTabNeverHearsCloseOrAConsumersClick() {
        AtomicInteger heard = new AtomicInteger();
        BookTab counting = tab(ObjectiveBookPage.TAB_QUESTS, (ctx, action) -> {
            heard.incrementAndGet();
            return false;
        });
        run(BookState.of(null), counting, event(BookActions.CLOSE));
        run(BookState.of(null), counting, event(BookActions.EXT));
        assertEquals(0, heard.get());
    }

    @Test
    void aConsumerThatTakesTheScreenIsNeverAnsweredOver() {
        ObjectiveBookDeps takes = ObjectiveBookDeps.builder().extHandler((id, store, ref, player) -> true).build();
        ObjectiveBookDeps declines = ObjectiveBookDeps.builder().extHandler((id, store, ref, player) -> false).build();
        ObjectiveBookDeps throwsUp = ObjectiveBookDeps.builder().extHandler((id, store, ref, player) -> {
            throw new IllegalStateException("boom");
        }).build();

        assertEquals(0, run(BookState.of(null), DECLINES, takes, event(BookActions.EXT)).answers(),
                "the consumer's page replaced the book");
        assertEquals(1, run(BookState.of(null), DECLINES, declines, event(BookActions.EXT)).answers());
        assertEquals(1, run(BookState.of(null), DECLINES, throwsUp, event(BookActions.EXT)).answers());
    }

    @Test
    void aSecondPartialSendsOnlyWhatCameAfterTheFirst() {
        BookTab twice = tab(ObjectiveBookPage.TAB_QUESTS, (ctx, action) -> {
            ctx.sendPartial();
            ctx.sendPartial();
            return true;
        });
        assertEquals(2, run(BookState.of(null), twice, event(BookActions.SELECT)).sends,
                "two partials are two updates, and the shell adds none");
    }

    // ==================== the page's way in: player, rail, context, then the action ====================

    /** What one event cost: the page's empty updates plus every answer the context gave. */
    private static final class PageRun {

        final AtomicInteger emptyUpdates = new AtomicInteger();
        final AtomicInteger railAsked = new AtomicInteger();
        final AtomicInteger contextsBuilt = new AtomicInteger();
        final Recorder host = new Recorder();

        int answers() {
            return emptyUpdates.get() + host.answers();
        }
    }

    /**
     * Drive {@link BookActions#event} exactly as {@code ObjectiveBookPage.handleDataEvent} does, with a stand-in
     * player ({@code null} for none), a rail that takes the click or not or throws, and a context build that works
     * or throws.
     */
    @Nonnull
    private static PageRun page(@Nullable Object player, @Nullable Boolean railTakes, boolean contextThrows,
            boolean lookupThrows, @Nullable String action) {
        PageRun run = new PageRun();
        BookActions.<Object>event(run.emptyUpdates::incrementAndGet,
                () -> {
                    if (lookupThrows) {
                        throw new IllegalStateException("store gone");
                    }
                    return player;
                },
                who -> {
                    run.railAsked.incrementAndGet();
                    if (railTakes == null) {
                        throw new IllegalStateException("rail boom");
                    }
                    return railTakes;
                },
                action,
                who -> {
                    run.contextsBuilt.incrementAndGet();
                    if (contextThrows) {
                        throw new IllegalStateException("context boom");
                    }
                    return BookContext.forEvent(run.host, DECLINES, BookState.of(null), ObjectiveBookDeps.DEFAULTS,
                            null, null, null, null, 0L);
                },
                event(action));
        return run;
    }

    @Test
    void anEventWithNoPlayerIsAnsweredAndNeverReachesTheRail() {
        PageRun run = page(null, false, false, false, BookActions.SELECT);
        assertEquals(1, run.answers());
        assertEquals(1, run.emptyUpdates.get());
        assertEquals(0, run.railAsked.get(), "no player, no rail");
    }

    @Test
    void aRailClickIsTheRailsAndTheBookNeverReadsItsAction() {
        PageRun run = page(new Object(), true, false, false, null);
        assertEquals(1, run.railAsked.get());
        assertEquals(0, run.contextsBuilt.get(), "the book never reads an action the rail took");
        assertEquals(0, run.answers(), "the rail answered its own click (opened or refused)");
    }

    @Test
    void aThrowingRailIsLoggedAndAnswered() {
        PageRun run = page(new Object(), null, false, false, BookActions.SELECT);
        assertEquals(1, run.answers());
        assertEquals(1, run.emptyUpdates.get());
        assertEquals(0, run.contextsBuilt.get());
    }

    @Test
    void aThrowingContextBuildIsLoggedAndAnswered() {
        PageRun run = page(new Object(), false, true, false, BookActions.SELECT);
        assertEquals(1, run.contextsBuilt.get());
        assertEquals(1, run.answers());
        assertEquals(1, run.emptyUpdates.get());
    }

    @Test
    void aThrowingPlayerLookupIsLoggedAndAnswered() {
        PageRun run = page(new Object(), false, false, true, BookActions.SELECT);
        assertEquals(1, run.answers());
        assertEquals(0, run.railAsked.get());
    }

    @Test
    void anEventPastTheRailIsTheActionsAndIsAnsweredOnce() {
        PageRun close = page(new Object(), false, false, false, null);
        assertEquals(1, close.host.closes, "an empty action past the rail closes the book");
        assertEquals(1, close.answers());

        PageRun unknown = page(new Object(), false, false, false, "no_such_thing");
        assertEquals(1, unknown.answers());
        assertEquals(0, unknown.emptyUpdates.get(), "the context answered, not the page's fallback");
    }

    @Test
    void thePageGoesInThroughTheTestedDoorAndNoActionIsListedTwice() throws IOException {
        String page = Files.readString(Path.of("src", "main", "java", "com", "ziggfreed", "common", "objectives",
                "book", "ObjectiveBookPage.java"), StandardCharsets.UTF_8);
        int handler = page.indexOf("public void handleDataEvent(");
        assertTrue(handler > 0 && page.indexOf("BookActions.<Player>event(", handler) > handler,
                "handleDataEvent answers through BookActions.event, the path these tests drive");
        assertTrue(Set.copyOf(BookActions.ALL).size() == BookActions.ALL.size(), "no action is listed twice");
    }
}
