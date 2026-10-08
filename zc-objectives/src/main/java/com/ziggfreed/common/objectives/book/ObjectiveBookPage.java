package com.ziggfreed.common.objectives.book;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.objectives.book.achievement.AchievementsTab;
import com.ziggfreed.common.objectives.book.quest.QuestJournalTab;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.subject.Subject;
import com.ziggfreed.common.ui.kit.EmptyState;
import com.ziggfreed.common.ui.kit.EmptyStatePainter;
import com.ziggfreed.common.ui.kit.Picture;
import com.ziggfreed.common.ui.menu.MenuFrame;
import com.ziggfreed.common.ui.menu.MenuRail;
import com.ziggfreed.common.ui.menu.MenuSlot;
import com.ziggfreed.common.ui.menu.ZigMenu;
import com.ziggfreed.common.ui.route.Destination;
import com.ziggfreed.common.ui.route.DestinationContext;
import com.ziggfreed.common.ui.route.Destinations;
import com.ziggfreed.common.ui.toast.ToastSpec;
import com.ziggfreed.common.ui.toast.ToastablePage;
import com.ziggfreed.common.util.SafeLog;

/**
 * The Objective Book: the progression book inside the shared menu frame, quests and achievements each a tab on
 * the frame's rail, over THE shared progression runtime. This class is the shell: the frame and the rail, the
 * header band ({@code #BrandingLogo}, {@code #TitleContainer}, {@code #PanelTitle} and
 * {@code #BrandingDescriptionRight} kept for a consumer's white-label branding, the subtitle and three stats), the
 * {@code #TabBody} the active {@link BookTab} appends its document into, the toasts, the self-heal on open, and the
 * answer to every event.
 *
 * <p><b>State.</b> One {@link BookState} per page instance: every binding carries it, every reopen starts from
 * it, and a tab's partial update records what it changed ({@link BookContext#keep}), so a selection or a toggled
 * section survives the next filter click. {@link ObjectiveBookPages#open} opens the book on a row.
 *
 * <p><b>Events.</b> A rail click is the menu's, answered before the book reads its own action; then
 * {@link BookActions} answers everything else, the active tab first. Every exit answers: a client whose event
 * goes unanswered stays locked.
 *
 * <p>What the library cannot know (a consumer's board-managed quests, its milestone ladder, who claimed a
 * server-first, extra page blocks) rides {@link ObjectiveBookDeps}, and every seam's default leaves the book
 * working on a bare server.
 */
public final class ObjectiveBookPage extends ToastablePage<ObjectiveBookEventData> {

    /** The quests tab id, also the default. */
    public static final String TAB_QUESTS = "quests";

    /** The achievements tab id. */
    public static final String TAB_ACHIEVEMENTS = "achievements";

    static final String PAGE_TEMPLATE = "Pages/ZigObjectiveBookPage.ui";

    /** The shell's own empty state, shown across the body when the runtime has no progress for the player. */
    static final String NO_PROGRESS = "#BookEmpty";

    @Nonnull private BookState state;
    @Nonnull private final BookTab tab;

    /** The rail this build painted, which a rail click is routed through. */
    @Nonnull private MenuRail rail = MenuRail.EMPTY;

    /** The deps resolved for THIS open, so build and its events read one consistent set. */
    @Nonnull private ObjectiveBookDeps deps = ObjectiveBookDeps.DEFAULTS;

    /** True while {@link #build} runs: a toast raised then paints with the build instead of a push. */
    private boolean building;

    /** The book on {@code state}. */
    public ObjectiveBookPage(@Nonnull PlayerRef playerRef, @Nonnull BookState state) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, ObjectiveBookEventData.CODEC);
        this.state = state;
        this.tab = tabFor(state.tab());
    }

    /** Open the book on the quests tab. */
    public ObjectiveBookPage(@Nonnull PlayerRef playerRef) {
        this(playerRef, BookState.of(TAB_QUESTS));
    }

    /** Open the book on {@code tab} (quests when null or unknown). */
    public ObjectiveBookPage(@Nonnull PlayerRef playerRef, @Nullable String tab) {
        this(playerRef, BookState.of(tab));
    }

    /** The pre-redesign filter form; null filters mean "off". Kept so a consumer built against it still links. */
    public ObjectiveBookPage(@Nonnull PlayerRef playerRef, @Nullable String tab,
                             @Nullable String filterCategory, @Nullable String filterStatus,
                             @Nullable String searchText, @Nullable String filterTag) {
        this(playerRef, BookState.of(tab).withFilters(blankIfNull(filterCategory), blankIfNull(filterStatus), null,
                blankIfNull(searchText), blankIfNull(filterTag)));
    }

    @Nonnull
    private static String blankIfNull(@Nullable String value) {
        return value == null ? "" : value;
    }

    /** The tab {@code id} names; the shell holds one instance per page, so a tab may keep what its last build drew. */
    @Nonnull
    static BookTab tabFor(@Nonnull String id) {
        return TAB_ACHIEVEMENTS.equals(id) ? new AchievementsTab() : new QuestJournalTab();
    }

    /** The state this page shows. */
    @Nonnull
    public BookState state() {
        return state;
    }

    /** An empty update, for an event that changed nothing: the client always hears back. */
    private void answer() {
        this.sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
    }

    // ==================== build ====================

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
                      @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        building = true;
        try {
            paint(ref, cmd, events, store);
        } catch (Throwable t) {
            // A build that throws leaves the client with no page at all; log it and send what was painted.
            SafeLog.warn("[progression] the objective book's build failed", t);
        } finally {
            building = false;
        }
        renderToastInto(cmd);
    }

    private void paint(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd,
                       @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        deps = ObjectiveBookPages.resolvedDeps();
        // The page's markup and the frame's paint; the rail's pane is the menu's.
        ZigMenu.appendThemed(cmd, PAGE_TEMPLATE, MenuFrame.RAIL);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton",
                EventData.of(BookState.KEY_ACTION, BookActions.CLOSE));

        boolean achievements = TAB_ACHIEVEMENTS.equals(state.tab());
        cmd.set("#PanelTitle.TextSpans", text(achievements ? "book.tab.achievements" : "book.tab.quests"));

        Player player = store.getComponent(ref, Player.getComponentType());
        // The shared menu's rail with this tab selected. Painted after the default title, so a consumer's
        // header-row branding wins over it.
        rail = ZigMenu.paint(cmd, events, store, ref, player,
                achievements ? MenuSlot.ACHIEVEMENTS.id() : MenuSlot.QUESTS.id(), true);

        BookContext ctx = BookContext.forBuild(new Host(store, ref, player), tab, state, deps, cmd, events,
                store, ref, player, playerRef, System.currentTimeMillis());
        // The subject comes from the runtime, never built here: with somebody else's store active, a subject
        // carrying the wrong handle reads neutral and drops every write.
        Subject subject = ctx.subject();
        if (subject == null) {
            paintNoProgress(cmd, events, achievements);
            return;
        }
        selfHeal(ctx);
        cmd.append(BookContext.TAB_BODY, tab.document());
        try {
            tab.build(ctx);
        } catch (Throwable t) {
            SafeLog.warn("[progression] the book's " + tab.id() + " tab failed to build", t);
        }
    }

    /** No progress for this player (the runtime has none): the book says so across its body. */
    private void paintNoProgress(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events, boolean achievements) {
        cmd.set(BookContext.TAB_BODY + ".Visible", false);
        cmd.set(NO_PROGRESS + ".Visible", true);
        EmptyStatePainter.paint(cmd, events, NO_PROGRESS, new EmptyState(
                Picture.item(achievements ? ObjectiveBookMenu.ACHIEVEMENTS_ICON : ObjectiveBookMenu.QUESTS_ICON),
                text(achievements ? "book.empty.achievements" : "book.empty.quests"), null, null), null);
    }

    /**
     * Both engines document self-heal as "whenever a surface opens": it settles a standing-value step and
     * re-offers a repeatable whose cooldown has elapsed, so the lists read current rather than one open behind.
     */
    private static void selfHeal(@Nonnull BookContext ctx) {
        try {
            Subject quests = ctx.questSubject();
            if (quests != null) {
                ProgressionRuntime.quests().selfHeal(quests);
            }
            Subject achievements = ctx.achievementSubject();
            if (achievements != null) {
                ProgressionRuntime.achievements().selfHeal(achievements);
            }
        } catch (Throwable t) {
            SafeLog.warn("[progression] objective book self-heal failed", t);
        }
    }

    @Nonnull
    private static Message text(@Nonnull String key) {
        return Msg.tr("ziggfreedcommon.", "progression." + key);
    }

    // ==================== events ====================

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
                                @Nonnull ObjectiveBookEventData data) {
        // No player, a rail click (it carries the row's index and no Action, so it is answered, opened or refused,
        // before the empty action would close the book), then the book's own action; a throw anywhere on the way
        // is logged and answered.
        BookActions.<Player>event(this::answer,
                () -> store.getComponent(ref, Player.getComponentType()),
                player -> rail.handle(data.menu, store, ref, player, this::answer),
                data.action,
                player -> BookContext.forEvent(new Host(store, ref, player), tab, state, deps, store, ref, player,
                        playerRef, System.currentTimeMillis()),
                data);
    }

    /** How a context answers through this page. */
    private final class Host implements BookContext.Host {

        @Nonnull private final Store<EntityStore> store;
        @Nonnull private final Ref<EntityStore> ref;
        @Nullable private final Player player;

        Host(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nullable Player player) {
            this.store = store;
            this.ref = ref;
            this.player = player;
        }

        @Override
        public void send(@Nullable UICommandBuilder cmd, @Nullable UIEventBuilder events) {
            sendUpdate(cmd != null ? cmd : new UICommandBuilder(), events != null ? events : new UIEventBuilder(),
                    false);
        }

        @Override
        public void reopen(@Nonnull BookState next) {
            if (player == null) {
                answer();
                return;
            }
            player.getPageManager().openCustomPage(ref, store, new ObjectiveBookPage(playerRef, next));
        }

        @Override
        public void close() {
            if (player == null) {
                answer();
                return;
            }
            player.getPageManager().setPage(ref, store, Page.None);
        }

        @Override
        public void toast(@Nonnull ToastSpec spec) {
            if (building) {
                primeToast(spec);
            } else {
                showToast(spec);
            }
        }

        @Override
        public boolean openDestination(@Nonnull Destination destination) {
            return player != null && Destinations.open(destination, DestinationContext.of(store, ref, player));
        }

        @Override
        public void keep(@Nonnull BookState next) {
            state = next;
        }

        @Nullable
        @Override
        public Subject subject(boolean achievements) {
            return achievements
                    ? ProgressionRuntime.subjects().achievementSubject(store, ref)
                    : ProgressionRuntime.subjects().questSubject(store, ref);
        }
    }
}
