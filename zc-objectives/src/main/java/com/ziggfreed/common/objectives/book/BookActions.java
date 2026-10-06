package com.ziggfreed.common.objectives.book;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.ziggfreed.common.achievement.Achievement;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.util.SafeLog;

/**
 * Every action the book's bindings send, and the shell's answer to each one the active tab hands back. The
 * shell's answer is the truthful one: a reopen on the next state, which a tab improves on by taking the action
 * itself and answering with a partial ({@link BookTab#handle}). Whatever happens on the way (a tab that takes an
 * action and answers nothing, a tab or a seam that throws, an action nobody knows), the event is answered
 * exactly once: a client whose event goes unanswered stays locked.
 *
 * <p>An action's payload: {@code Id} names its row (a verb with none acts on the selected row, so the page's
 * action bar is bound once); {@code View}, {@code Section} with {@code Open}, {@code DropdownValue},
 * {@code ObjectiveId} and {@code Threshold} as each action says. The live search field rides every binding.
 */
public final class BookActions {

    /** Close the book (also an event with no action at all). */
    public static final String CLOSE = "close";

    /** Show another view of the tab ({@code View}). */
    public static final String VIEW = "view";

    /** Open or close a list section ({@code Section}, {@code Open}: {@code "true"} opens). */
    public static final String SECTION = "section";

    /** Select a row ({@code Id}). */
    public static final String SELECT = "select";

    /**
     * Open the book on a row ({@code Id}) in the binding's tab, view and category: another tab opens fresh on it,
     * and Achievements' overview opens Browse, where a selected row shows.
     */
    public static final String OPEN = "open";

    /** The filters: {@code Id} or {@code DropdownValue} carries the new value. */
    public static final String CATEGORY = "category";
    public static final String STATUS = "status";
    public static final String SORT = "sort";
    public static final String TAG = "tag";

    /** Search for the live field's text; clear it; drop every filter and the search ("Clear filters"). */
    public static final String SEARCH = "search";
    public static final String CLEAR_SEARCH = "clear_search";
    public static final String CLEAR_FILTERS = "clear_filters";

    /** A consumer-painted control ({@code Id} carries the consumer's own token); its handler answers. */
    public static final String EXT = "ext";

    /** The quest verbs: Accept or Collect by the quest's live state; each one alone; the rest. */
    public static final String PRIMARY = "primary";
    public static final String ACCEPT = "accept";
    public static final String COLLECT = "collect";
    public static final String HAND_IN = "turn_in";
    public static final String ABANDON = "abandon";
    public static final String TRACK = "toggletrack";

    /** The achievement verbs: pin or unpin, collect its rewards, collect a points milestone ({@code Threshold}). */
    public static final String PIN = "togglepin";
    public static final String CLAIM = "claim";
    public static final String CLAIM_MILESTONE = "claim_milestone";

    /** The pre-redesign book's row expand and subcategory chips: answered, and nothing changes. */
    public static final String LEGACY_EXPAND = "toggle";
    public static final String LEGACY_SUBFILTER = "subfilter";

    /** Every verb, each answered by a reopen on the same state. */
    public static final List<String> VERBS = List.of(PRIMARY, ACCEPT, COLLECT, HAND_IN, ABANDON, TRACK, PIN, CLAIM,
            CLAIM_MILESTONE);

    /** Every action the shell answers. */
    public static final List<String> ALL = List.of(CLOSE, VIEW, SECTION, SELECT, OPEN, CATEGORY, STATUS, SORT, TAG,
            SEARCH, CLEAR_SEARCH, CLEAR_FILTERS, EXT, PRIMARY, ACCEPT, COLLECT, HAND_IN, ABANDON, TRACK, PIN, CLAIM,
            CLAIM_MILESTONE, LEGACY_EXPAND, LEGACY_SUBFILTER);

    private BookActions() {
    }

    /**
     * One event as the page hears it: the player looked up (none answers with an empty update), the rail's own
     * click answered by the rail, the context built, then {@link #handle}. A throw before {@link #handle} (the
     * lookup, the rail, the context) is logged and answered with an empty update, so the client never waits on it;
     * {@link #handle} answers everything after. Generic in the player so a test drives it without an engine player.
     *
     * @param answer the page's empty update
     * @param rail   true when the rail took the click (it answered)
     * @param action the event's action, as the page read it after the rail
     */
    static <P> void event(@Nonnull Runnable answer, @Nonnull Supplier<P> player, @Nonnull Predicate<P> rail,
            @Nullable String action, @Nonnull Function<P, BookContext> context, @Nonnull ObjectiveBookEventData data) {
        BookContext ctx;
        try {
            P who = player.get();
            if (who == null) {
                answer.run();
                return;
            }
            if (rail.test(who)) {
                return;
            }
            ctx = context.apply(who);
        } catch (Throwable t) {
            SafeLog.warn("[progression] the book could not start answering an event: " + t.getMessage());
            answer.run();
            return;
        }
        handle(ctx, action, data);
    }

    /**
     * Answer one event, after the rail has had it: a close is the shell's, a consumer's click is the consumer's,
     * the active tab hears everything else first, and the shell answers what the tab hands back.
     */
    static void handle(@Nonnull BookContext ctx, @Nonnull ObjectiveBookEventData data) {
        handle(ctx, data.action, data);
    }

    /** {@link #handle(BookContext, ObjectiveBookEventData)} with the action the page read off the event. */
    static void handle(@Nonnull BookContext ctx, @Nullable String rawAction, @Nonnull ObjectiveBookEventData data) {
        String action = rawAction == null ? "" : rawAction.trim();
        try {
            // The live field is the search truth on every action: it holds what the player typed, submitted or
            // not, so a click never loses it and whatever this event reopens on searches for it.
            ctx.liveSearch(data.searchInput);
            if (action.isEmpty() || CLOSE.equals(action)) {
                ctx.close();
                return;
            }
            if (EXT.equals(action)) {
                ext(ctx, data);
                return;
            }
            if (tabTakes(ctx, action, data)) {
                return;
            }
            route(ctx, action, data);
        } catch (Throwable t) {
            SafeLog.warn("[progression] the book's '" + action + "' failed: " + t.getMessage());
        } finally {
            ctx.answerIfSilent();
        }
    }

    /** The active tab's first refusal; a tab that throws is logged and the shell answers. */
    private static boolean tabTakes(@Nonnull BookContext ctx, @Nonnull String action,
            @Nonnull ObjectiveBookEventData data) {
        try {
            return ctx.tab().handle(ctx, action, data);
        } catch (Throwable t) {
            SafeLog.warn("[progression] the book's " + ctx.tab().id() + " tab failed on '" + action + "': "
                    + t.getMessage());
            return true;
        }
    }

    private static void route(@Nonnull BookContext ctx, @Nonnull String action, @Nonnull ObjectiveBookEventData data) {
        BookState state = ctx.state();
        String target = firstNonBlank(data.id, state.selectedId(), null);
        switch (action) {
            case VIEW -> ctx.reopen(state.withView(firstNonBlank(data.view, data.id, null)));
            case SECTION -> {
                String section = firstNonBlank(data.section, data.id, null);
                if (section != null) {
                    ctx.reopen(state.withSection(section, !"false".equalsIgnoreCase(data.open)));
                }
            }
            case SELECT -> {
                if (data.id != null && !data.id.isBlank()) {
                    ctx.reopen(state.withSelected(data.id));
                }
            }
            case OPEN -> ctx.reopen(opened(state, data));
            case CATEGORY -> ctx.reopen(state.withFilters(firstNonBlank(data.id, data.dropdownValue, BookState.ALL),
                    null, null, null, null));
            case STATUS -> ctx.reopen(state.withFilters(null, firstNonBlank(data.id, data.dropdownValue,
                    BookState.ALL), null, null, null));
            case SORT -> ctx.reopen(state.withFilters(null, null, firstNonBlank(data.dropdownValue, data.id,
                    BookState.SORT_DEFAULT), null, null));
            case TAG -> ctx.reopen(state.withFilters(null, null, null, null, firstNonBlank(data.dropdownValue,
                    data.id, BookState.ALL)));
            case SEARCH -> ctx.reopen(state.withSearch(data.searchInput));
            case CLEAR_SEARCH -> ctx.reopen(state.withSearch(""));
            case CLEAR_FILTERS -> ctx.reopen(state.clearFilters());
            case PRIMARY -> verbThenReopen(ctx, () -> ctx.verbs().primary(target));
            case ACCEPT -> verbThenReopen(ctx, () -> withQuest(ctx, target, quest -> ctx.verbs().accept(quest)));
            case COLLECT -> verbThenReopen(ctx, () -> withQuest(ctx, target, quest -> ctx.verbs().collect(quest)));
            case HAND_IN -> verbThenReopen(ctx, () -> withQuest(ctx, target,
                    quest -> ctx.verbs().handIn(quest, data.objectiveId) > 0));
            case ABANDON -> verbThenReopen(ctx, () -> withQuest(ctx, target, quest -> ctx.verbs().abandon(quest)));
            case TRACK -> verbThenReopen(ctx, () -> withQuest(ctx, target, quest -> ctx.verbs().toggleTrack(quest)));
            case PIN -> verbThenReopen(ctx, () -> target != null && ctx.verbs().togglePin(target));
            case CLAIM -> verbThenReopen(ctx, () -> {
                Achievement achievement = ctx.achievementSubject() == null ? null : BookVerbs.achievement(target);
                return achievement != null && ctx.verbs().claim(achievement);
            });
            case CLAIM_MILESTONE -> verbThenReopen(ctx, () -> ctx.verbs().claimMilestone(threshold(data.threshold))
                    == ObjectiveBookDeps.MilestoneClaimOutcome.SUCCESS);
            case LEGACY_SUBFILTER -> ctx.reopen(state);
            default -> {
                // An unknown action, or the old row expand: the empty update below answers it.
            }
        }
    }

    /**
     * A verb, then a reopen on the same state: the selection rides it, so the row the player acted on stays
     * selected in whatever section it moved to, and a toast the verb raised survives into the new book.
     */
    private static void verbThenReopen(@Nonnull BookContext ctx, @Nonnull Verb verb) {
        try {
            verb.run();
        } finally {
            ctx.reopen(ctx.state());
        }
    }

    @FunctionalInterface
    private interface Verb {
        boolean run();
    }

    @FunctionalInterface
    private interface QuestVerb {
        boolean run(@Nonnull Quest quest);
    }

    /** The quest verb on {@code id}, once there is somebody to act for and such a quest. */
    private static boolean withQuest(@Nonnull BookContext ctx, @Nullable String id, @Nonnull QuestVerb verb) {
        Quest quest = ctx.questSubject() == null ? null : BookVerbs.quest(id);
        return quest != null && verb.run(quest);
    }

    /** Where {@link #OPEN} lands: the binding's tab (fresh when it is another one), view and category, on the row. */
    @Nonnull
    private static BookState opened(@Nonnull BookState state, @Nonnull ObjectiveBookEventData data) {
        BookState next = state;
        if (data.tab != null && !data.tab.isBlank() && !BookState.of(data.tab).tab().equals(state.tab())) {
            next = BookState.of(data.tab);
        } else {
            if (data.view != null && !data.view.isBlank()) {
                next = next.withView(data.view);
            }
            if (data.category != null && !data.category.isBlank()) {
                next = next.withFilters(data.category, null, null, null, null);
            }
        }
        if (data.id != null && !data.id.isBlank()) {
            next = next.withSelected(data.id).showingSelection();
        }
        return next;
    }

    /** A consumer's control: its handler answers when it takes the screen; otherwise the book answers. */
    private static void ext(@Nonnull BookContext ctx, @Nonnull ObjectiveBookEventData data) {
        boolean took = false;
        try {
            took = ctx.deps().extHandler().handle(data.id == null ? "" : data.id, ctx.store(), ctx.ref(), ctx.player());
        } catch (Throwable t) {
            SafeLog.warn("[progression] the book's ext handler failed: " + t.getMessage());
        }
        if (took) {
            ctx.handOff();
        }
    }

    private static int threshold(@Nullable String raw) {
        try {
            return raw == null ? -1 : Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    @Nullable
    private static String firstNonBlank(@Nullable String a, @Nullable String b, @Nullable String fallback) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        if (b != null && !b.isBlank()) {
            return b.trim();
        }
        return fallback;
    }
}
