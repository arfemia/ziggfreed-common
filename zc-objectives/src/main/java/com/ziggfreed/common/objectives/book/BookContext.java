package com.ziggfreed.common.objectives.book;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.ZigSearchRow;
import com.ziggfreed.common.ui.route.Destination;
import com.ziggfreed.common.ui.toast.ToastSpec;
import com.ziggfreed.common.util.SafeLog;

/**
 * What a {@link BookTab} works with, for one full build or one event: the builders, the player, the book's
 * state and deps, the header band, the verbs, and the ways an event is answered. One context answers at most
 * once per answer it is asked for ({@link #reopen}, {@link #sendPartial}); the shell answers for a tab that
 * answered nothing, so a client never waits on a click.
 *
 * <p>In a build the builders are the build's own and nothing is answered (the build is the answer). For an
 * event they are fresh, made on first use, and {@link #sendPartial} sends what they hold and starts new ones.
 */
public final class BookContext {

    /** The shell's host for the active tab's document. */
    public static final String TAB_BODY = "#TabBody";

    /** The active tab's document root, the one child the shell appends into {@link #TAB_BODY}. */
    public static final String BODY = TAB_BODY + "[0]";

    /** This library's lang prefix and the book's domain ({@code ziggfreedcommon.progression.lang}). */
    private static final String PREFIX = "ziggfreedcommon.";
    private static final String DOMAIN = "progression.";

    /**
     * How a context reaches the client and the page's memory: the page in a server, a recorder in a test. Each
     * call is one answer except {@link #toast} and {@link #keep}, which ride whichever answer follows.
     */
    interface Host {

        /** A partial update (null builders send an empty one). */
        void send(@Nullable UICommandBuilder cmd, @Nullable UIEventBuilder events);

        /** A fresh book on {@code next}, replacing this one. */
        void reopen(@Nonnull BookState next);

        /** Close the book. */
        void close();

        /** Show a toast: pushed at once on a live page, painted by the next build otherwise. */
        void toast(@Nonnull ToastSpec spec);

        /** Open {@code destination} in the book's place; true when it took the screen. */
        boolean openDestination(@Nonnull Destination destination);

        /** The state the page holds after a partial, which every later event starts from. */
        void keep(@Nonnull BookState state);

        /** The subject the quest engine ({@code false}) or the achievement engine ({@code true}) reads. */
        @Nullable
        Subject subject(boolean achievements);
    }

    @Nonnull private final Host host;
    @Nonnull private final BookTab tab;
    @Nonnull private final ObjectiveBookDeps deps;
    @Nullable private final Store<EntityStore> store;
    @Nullable private final Ref<EntityStore> ref;
    @Nullable private final Player player;
    @Nullable private final PlayerRef viewer;
    private final long nowMs;
    private final boolean building;
    @Nonnull private final BookHeader header;

    @Nonnull private BookState state;
    @Nullable private UICommandBuilder cmd;
    @Nullable private UIEventBuilder events;
    private boolean answered;

    private boolean subjectsRead;
    @Nullable private Subject questSubject;
    @Nullable private Subject achievementSubject;
    @Nullable private BookVerbs verbs;

    private BookContext(@Nonnull Host host, @Nonnull BookTab tab, @Nonnull BookState state,
            @Nonnull ObjectiveBookDeps deps, @Nullable UICommandBuilder cmd, @Nullable UIEventBuilder events,
            @Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref, @Nullable Player player,
            @Nullable PlayerRef viewer, long nowMs, boolean building) {
        this.host = host;
        this.tab = tab;
        this.state = state;
        this.deps = deps;
        this.cmd = cmd;
        this.events = events;
        this.store = store;
        this.ref = ref;
        this.player = player;
        this.viewer = viewer;
        this.nowMs = nowMs;
        this.building = building;
        this.header = new BookHeader(this);
    }

    /** The context of one full build, painting into the build's own builders. */
    @Nonnull
    static BookContext forBuild(@Nonnull Host host, @Nonnull BookTab tab, @Nonnull BookState state,
            @Nonnull ObjectiveBookDeps deps, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref, @Nullable Player player,
            @Nullable PlayerRef viewer, long nowMs) {
        return new BookContext(host, tab, state, deps, cmd, events, store, ref, player, viewer, nowMs, true);
    }

    /** The context of one event, with fresh builders made on first use. */
    @Nonnull
    static BookContext forEvent(@Nonnull Host host, @Nonnull BookTab tab, @Nonnull BookState state,
            @Nonnull ObjectiveBookDeps deps, @Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref,
            @Nullable Player player, @Nullable PlayerRef viewer, long nowMs) {
        return new BookContext(host, tab, state, deps, null, null, store, ref, player, viewer, nowMs, false);
    }

    // ==================== what a tab reads ====================

    /** The commands this build or this event's next update carries. */
    @Nonnull
    public UICommandBuilder cmd() {
        if (cmd == null) {
            cmd = new UICommandBuilder();
        }
        return cmd;
    }

    /** The bindings this build or this event's next update carries. Bind only what the same update appends. */
    @Nonnull
    public UIEventBuilder events() {
        if (events == null) {
            events = new UIEventBuilder();
        }
        return events;
    }

    /** The store the player's entity lives in (null only in a test). */
    @Nullable
    public Store<EntityStore> store() {
        return store;
    }

    /** The player's own entity (null only in a test). */
    @Nullable
    public Ref<EntityStore> ref() {
        return ref;
    }

    /** The player (null when the page was built for an entity that is not one, or in a test). */
    @Nullable
    public Player player() {
        return player;
    }

    /** The viewing player's reference (null only in a test). */
    @Nullable
    public PlayerRef viewer() {
        return viewer;
    }

    /** The book's state for this build or event, the live search field already applied. */
    @Nonnull
    public BookState state() {
        return state;
    }

    @Nonnull
    public ObjectiveBookDeps deps() {
        return deps;
    }

    /** One clock reading for the whole build or event. */
    public long nowMs() {
        return nowMs;
    }

    /** The shell's header band: the subtitle and the three stats. */
    @Nonnull
    public BookHeader header() {
        return header;
    }

    /** The tab this context paints or answers for. */
    @Nonnull
    public BookTab tab() {
        return tab;
    }

    /** True in a full build, false for an event. */
    public boolean building() {
        return building;
    }

    /** The active tab's document root ({@link #BODY}); address its children as {@code body() + " #Id"}. */
    @Nonnull
    public String body() {
        return BODY;
    }

    /** {@code selector} inside the active tab's document: {@code at("#List")} is {@code "#TabBody[0] #List"}. */
    @Nonnull
    public String at(@Nonnull String selector) {
        return BODY + " " + selector;
    }

    /**
     * {@code action} carrying the whole state and the tab's live search text, so a click never loses a filter
     * or what was typed. Every binding a tab makes starts here.
     */
    @Nonnull
    public EventData binding(@Nonnull String action) {
        EventData data = state.event(action);
        String row = tab.searchRow();
        return row == null || row.isBlank() ? data : ZigSearchRow.carry(data, BookState.KEY_SEARCH_INPUT, at(row));
    }

    /** {@link #binding} for a row: {@code action} on {@code id}. */
    @Nonnull
    public EventData binding(@Nonnull String action, @Nonnull String id) {
        return binding(action).append(BookState.KEY_ID, id);
    }

    /** A section head's click: it asks for the section the other way round from how it shows now. */
    @Nonnull
    public EventData sectionBinding(@Nonnull String sectionId, boolean openNow) {
        return binding(BookActions.SECTION).append(BookState.KEY_SECTION, sectionId)
                .append(BookState.KEY_OPEN, Boolean.toString(!openNow));
    }

    /** The subject the active tab's engine reads, or null when the runtime has none for this player. */
    @Nullable
    public Subject subject() {
        return ObjectiveBookPage.TAB_ACHIEVEMENTS.equals(state.tab()) ? achievementSubject() : questSubject();
    }

    /** The subject the quest engine reads. */
    @Nullable
    public Subject questSubject() {
        readSubjects();
        return questSubject;
    }

    /** The subject the achievement engine reads. */
    @Nullable
    public Subject achievementSubject() {
        readSubjects();
        return achievementSubject;
    }

    /** The book's verbs (accept, collect, hand in, abandon, track, pin, claim), acting for this player. */
    @Nonnull
    public BookVerbs verbs() {
        if (verbs == null) {
            verbs = new BookVerbs(this);
        }
        return verbs;
    }

    /** A key of the book's own lang file ({@code ziggfreedcommon.progression.lang}), without its prefix. */
    @Nonnull
    public Message text(@Nonnull String key, @Nonnull Object... args) {
        return Msg.tr(PREFIX, DOMAIN + key, args);
    }

    // ==================== answers ====================

    /**
     * Answer by opening a fresh book on {@code next}: the truthful answer to anything a partial cannot show
     * (a filter, a view, a row moving between sections). A toast shown before it survives into the new book.
     */
    public void reopen(@Nonnull BookState next) {
        if (refusedInBuild("reopen")) {
            return;
        }
        answered = true;
        host.reopen(next);
    }

    /**
     * Answer with what {@link #cmd()} and {@link #events()} hold, keeping the scroll, then start fresh builders, so
     * a second partial sends only what came after the first.
     */
    public void sendPartial() {
        if (refusedInBuild("partial update")) {
            return;
        }
        answered = true;
        host.send(cmd, events);
        cmd = null;
        events = null;
    }

    /**
     * Record the state a partial update left on screen (a new selection, a toggled section), so the next event
     * and the next reopen start from it. Not an answer.
     */
    public void keep(@Nonnull BookState next) {
        state = next;
        host.keep(next);
    }

    /** Show a toast; it rides whatever answer follows and survives a reopen. Not an answer. */
    public void toast(@Nonnull ToastSpec spec) {
        host.toast(spec);
    }

    /**
     * Open {@code destination} in the book's place (another screen, the Almanac on a season). True when it
     * took the screen, which answers the event; false leaves the event to be answered.
     */
    public boolean openDestination(@Nonnull Destination destination) {
        if (refusedInBuild("destination")) {
            return false;
        }
        boolean took = false;
        try {
            took = host.openDestination(destination);
        } catch (Throwable t) {
            SafeLog.warn("[progression] the book could not open a destination: " + t.getMessage());
        }
        if (took) {
            answered = true;
        }
        return took;
    }

    // ==================== the shell's own ====================

    /** Apply the live search field an event carried (null: the event carried none). */
    void liveSearch(@Nullable String text) {
        if (text != null) {
            state = state.withSearch(text);
        }
    }

    /** Close the book (the answer to a close). */
    void close() {
        answered = true;
        host.close();
    }

    /** Another page took the screen (a consumer's click): nothing is sent to the book after it. */
    void handOff() {
        answered = true;
    }

    boolean answered() {
        return answered;
    }

    /** The last word on every event: whatever was left unanswered is answered with what the builders hold. */
    void answerIfSilent() {
        if (!building && !answered) {
            answered = true;
            host.send(cmd, events);
        }
    }

    private boolean refusedInBuild(@Nonnull String what) {
        if (building) {
            SafeLog.warn("[progression] a book tab asked for a " + what + " during a build; the build is the answer");
            return true;
        }
        return false;
    }

    private void readSubjects() {
        if (subjectsRead) {
            return;
        }
        subjectsRead = true;
        try {
            questSubject = host.subject(false);
            achievementSubject = host.subject(true);
        } catch (Throwable t) {
            SafeLog.warn("[progression] the book could not read the player's progress: " + t.getMessage());
        }
    }
}
