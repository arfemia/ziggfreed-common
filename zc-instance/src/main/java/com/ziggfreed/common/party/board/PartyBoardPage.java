package com.ziggfreed.common.party.board;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;
import com.ziggfreed.common.instance.dungeon.CopyRouter;
import com.ziggfreed.common.instance.dungeon.DungeonAsset;
import com.ziggfreed.common.instance.dungeon.DungeonConfig;
import com.ziggfreed.common.instance.dungeon.DungeonRefusals;
import com.ziggfreed.common.instance.hub.ZigHubWorldGenProvider;
import com.ziggfreed.common.party.Party;
import com.ziggfreed.common.party.PartyService;
import com.ziggfreed.common.ui.kit.ActionLook;
import com.ziggfreed.common.ui.kit.ActionSlot;
import com.ziggfreed.common.ui.kit.DetailAction;
import com.ziggfreed.common.ui.kit.Hero;
import com.ziggfreed.common.ui.kit.HeroPainter;
import com.ziggfreed.common.ui.kit.LedgerBindings;
import com.ziggfreed.common.ui.kit.LedgerModel;
import com.ziggfreed.common.ui.kit.LedgerPainter;
import com.ziggfreed.common.ui.kit.LedgerRow;
import com.ziggfreed.common.ui.kit.LedgerSection;
import com.ziggfreed.common.ui.kit.Mark;
import com.ziggfreed.common.ui.kit.MetaGlyph;
import com.ziggfreed.common.ui.kit.Picture;
import com.ziggfreed.common.ui.kit.RowAccent;
import com.ziggfreed.common.ui.kit.RowSize;
import com.ziggfreed.common.ui.kit.SegmentPainter;
import com.ziggfreed.common.ui.kit.Tone;
import com.ziggfreed.common.ui.name.PlayerDisplayNames;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastablePage;
import com.ziggfreed.common.util.SafeLog;

/**
 * The party board ({@link PartyBoard}) on the band frame, in two views of one document
 * ({@code Pages/ZigPartyBoardPage.ui}):
 * <ul>
 *   <li><b>The list</b>: the frame's hero (Party Board, what it is for), then the board's notices on the kit's ledger
 *       ({@link LedgerPainter} owns every row's ids): the viewer's own under "Your notice", the rest newest first,
 *       each its poster and what for with how many more, its notes and where its poster is, how long it has been up,
 *       and its one button, Ask to join (greyed as Asked with its tooltip once asked, or while the viewer is in
 *       another party; none on a notice of the viewer's own party); a notice for a depth the viewer has not opened
 *       is greyed, says what opens it, and carries Not open instead of a button. Under them, Put up a notice, or Take my notice down with one up.</li>
 *   <li><b>Putting up a notice</b>: what for (the board's depths and daily the viewer has opened, and anything), how
 *       many more (as many as their party has room for, four at most), up to two set notes, all choice tiles
 *       ({@link SegmentPainter}); the game's own check box, unticked to start, which announces the notice; how it will
 *       read; when it comes down; Put it up and Back.</li>
 * </ul>
 * Every word is {@code ziggfreedcommon.party.lang}'s {@code board.*}; a player is named through the display-name
 * seam on a Label's {@code .TextSpans}. Every exit of {@link #handleDataEvent} answers the client.
 */
public final class PartyBoardPage extends ToastablePage<PartyBoardEventData> {

    // The ids this page names in its own document live in PartyBoardIds, which PartyBoardPageIdsTest holds to it.
    private static final String PAGE_TEMPLATE = PartyBoardIds.PAGE_TEMPLATE;
    private static final String LIST_VIEW = PartyBoardIds.LIST_VIEW;
    private static final String LIST = PartyBoardIds.LIST;
    private static final String EMPTY = PartyBoardIds.EMPTY;
    private static final String POST_BUTTON = PartyBoardIds.POST_BUTTON;
    private static final String TAKE_DOWN_BUTTON = PartyBoardIds.TAKE_DOWN_BUTTON;
    private static final String POST_VIEW = PartyBoardIds.POST_VIEW;
    private static final String AIM_TILES = PartyBoardIds.AIM_TILES;
    private static final String WANT_TILES = PartyBoardIds.WANT_TILES;
    private static final String NOTE_HEAD = PartyBoardIds.NOTE_HEAD;
    private static final String NOTE_TILES = PartyBoardIds.NOTE_TILES;
    private static final String ANNOUNCE_BOX = PartyBoardIds.ANNOUNCE_BOX;
    private static final String PREVIEW = PartyBoardIds.PREVIEW;
    private static final String ENDS = PartyBoardIds.ENDS;
    private static final String PUT_UP_BUTTON = PartyBoardIds.PUT_UP_BUTTON;
    private static final String BACK_BUTTON = PartyBoardIds.BACK_BUTTON;

    /** The close button the band frame declares. */
    private static final String CLOSE_BUTTON = "#CloseButton";

    /** The value the box's state rides under on every post-form binding ({@link PartyBoardEventData}). */
    private static final String ANNOUNCE_KEY = "@Announce";

    /** What for: anything. */
    static final String ANY = "*";

    private static final String YOURS = "yours";
    private static final String NOTICES = "notices";

    private static final String CLOSE = "close";
    private static final String POST = "post";
    private static final String BACK = "back";
    private static final String AIM = "aim";
    private static final String WANTS = "wants";
    private static final String NOTE = "note";
    private static final String PUT_UP = "putup";
    private static final String TAKE_DOWN = "takedown";
    private static final String ASK = "ask";

    /** Only a notice's button is bound; a row, a head and a show-more row are only read. */
    private static final LedgerBindings ROWS = new LedgerBindings() {
        @Override
        public EventData row(LedgerSection s, LedgerRow r) {
            return null;
        }

        @Override
        public EventData section(LedgerSection s) {
            return null;
        }

        @Override
        public EventData showMore(LedgerSection s) {
            return null;
        }

        @Override
        public EventData action(LedgerSection s, LedgerRow r) {
            return EventData.of("Action", ASK).append("Notice", r.id());
        }
    };

    @Nullable private final String dungeon;
    @Nullable private final String place;

    private boolean posting;
    @Nonnull private String aim = ANY;
    private int wants = 1;
    private final List<String> notes = new ArrayList<>();
    /** The announce box, unticked to start (R1168). */
    private boolean announce;

    public PartyBoardPage(@Nonnull PlayerRef playerRef, @Nullable String dungeon, @Nullable String place) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, PartyBoardEventData.CODEC);
        this.dungeon = dungeon == null || dungeon.isBlank() ? null : dungeon.trim();
        this.place = place == null || place.isBlank() ? null : place.trim();
    }

    @Override
    public void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull Store<EntityStore> store) {
        cmd.append(PAGE_TEMPLATE);
        events.addEventBinding(CustomUIEventBindingType.Activating, CLOSE_BUTTON, EventData.of("Action", CLOSE));
        try {
            PartyBoardLadder ladder = PartyBoardLadder.read(ref, store, playerRef.getUuid(), dungeon);
            if (posting) {
                paintPost(cmd, events, ladder);
            } else {
                paintList(cmd, events, ladder);
            }
        } catch (Throwable t) {
            SafeLog.warn(PartyBoard.LOG + "the party board could not paint everything", t);
        }
        renderToastInto(cmd);
    }

    // ==================== the list ====================

    private void paintList(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull PartyBoardLadder ladder) {
        HeroPainter.paint(cmd, new Hero(null, PartyBoardText.line("title"), null, PartyBoardText.line("lead")));
        cmd.set(LIST_VIEW + ".Visible", true);
        cmd.set(POST_VIEW + ".Visible", false);

        UUID viewer = playerRef.getUuid();
        PartyBoard board = PartyBoard.shared();
        PartyNotice mine = board.noticeOf(viewer);
        List<LedgerRow> others = new ArrayList<>();
        for (PartyNotice notice : board.notices(dungeon)) {
            if (!notice.poster().equals(viewer)) {
                others.add(row(board, notice, viewer, ladder));
            }
        }
        List<LedgerSection> sections = new ArrayList<>();
        if (mine != null) {
            sections.add(new LedgerSection(YOURS, PartyBoardText.line("section.yours"),
                    List.of(row(board, mine, viewer, ladder)), true));
        }
        if (!others.isEmpty()) {
            Message name = PartyBoardText.dungeonName(dungeon);
            sections.add(new LedgerSection(NOTICES, name == null ? PartyBoardText.line("section.all")
                    : PartyBoardText.line("section.dungeon", name), others, true));
        }
        LedgerPainter.paint(cmd, events, LIST, LedgerModel.of(sections), Set.of(), null, ROWS, RowSize.STANDARD,
                playerRef);

        boolean empty = sections.isEmpty();
        cmd.set(EMPTY + ".Visible", empty);
        if (empty) {
            cmd.set(EMPTY + ".TextSpans", PartyBoardText.line("empty"));
        }
        cmd.set(POST_BUTTON + ".Visible", mine == null);
        cmd.set(TAKE_DOWN_BUTTON + ".Visible", mine != null);
        events.addEventBinding(CustomUIEventBindingType.Activating, POST_BUTTON, EventData.of("Action", POST));
        events.addEventBinding(CustomUIEventBindingType.Activating, TAKE_DOWN_BUTTON,
                EventData.of("Action", TAKE_DOWN));
    }

    /** One notice as the viewer reads it. */
    @Nonnull
    private LedgerRow row(@Nonnull PartyBoard board, @Nonnull PartyNotice notice, @Nonnull UUID viewer,
            @Nonnull PartyBoardLadder ladder) {
        Message name = PlayerDisplayNames.displayName(notice.poster(), null);
        boolean own = notice.poster().equals(viewer);
        String opener = own ? null : ladder.lockedBehind(notice.aim());
        Message title = PartyBoardText.title(name, notice.aim(), Math.max(1, board.remaining(notice)));
        Message notesLine = PartyBoardText.notes(notice.notes());
        // Two lines at most: the notes, then what opens it when the viewer cannot join, else where its poster is.
        Message second = opener != null ? PartyBoardText.line("locked", tierWordOf(opener))
                : where(notice.poster(), name, ladder);
        Message meta = notesLine == null ? second : PartyBoardText.line("notice.meta", notesLine, second);
        Message state = own ? PartyBoardText.line("state.yours")
                : opener != null ? PartyBoardText.line("state.notopen") : null;
        Tone tone = own ? Tone.ACTIVE : opener != null ? Tone.BLOCKED : Tone.NEUTRAL;
        LedgerRow row = new LedgerRow(notice.poster().toString(), title, meta, Picture.NONE, tone, state,
                ago(notice.postedAt()), null, Mark.NONE, opener != null, MetaGlyph.NONE, RowAccent.NONE);
        if (own || opener != null) {
            return row;
        }
        PartyService service = board.service();
        Party viewersParty = service.partyOf(viewer);
        if (viewersParty != null && viewersParty.contains(notice.poster())) {
            return row; // the viewer is in this notice's party already: nothing to ask
        }
        DetailAction action;
        if (board.hasAsked(viewer, notice.poster())) {
            action = new DetailAction(ActionSlot.SECONDARY, PartyBoardText.line("btn.asked"), ActionLook.NORMAL, ASK,
                    null, false, PartyBoardText.line("tip.asked"));
        } else if (service.isInParty(viewer)) {
            action = new DetailAction(ActionSlot.SECONDARY, PartyBoardText.line("btn.ask"), ActionLook.NORMAL, ASK,
                    null, false, PartyBoardText.line("tip.inparty"));
        } else {
            action = new DetailAction(ActionSlot.SECONDARY, PartyBoardText.line("btn.ask"), ActionLook.NORMAL, ASK,
                    null, true, null);
        }
        return row.withAction(action);
    }

    /** Where a notice's poster is: in the hub, in a dungeon with their party, or out in the world. */
    @Nonnull
    private static Message where(@Nonnull UUID poster, @Nonnull Message name, @Nonnull PartyBoardLadder ladder) {
        World world = null;
        try {
            Universe universe = Universe.get();
            PlayerRef ref = universe == null ? null : universe.getPlayer(poster);
            UUID worldId = ref == null ? null : ref.getWorldUuid();
            world = worldId == null ? null : universe.getWorld(worldId);
        } catch (Throwable ignored) {
            // Where they are is a courtesy line: a poster between worlds reads as out in the world.
        }
        if (ZigHubWorldGenProvider.of(world) != null) {
            return PartyBoardText.line("where.hub", name);
        }
        String inside = world == null ? null : CopyRouter.dungeonOf(world.getName());
        DungeonAsset dungeon = inside == null ? null : DungeonConfig.getInstance().resolve(inside);
        if (dungeon != null) {
            Message depth = ladder.holds(dungeon.getId()) ? PartyBoardText.tierWord(dungeon)
                    : DungeonRefusals.tierName(dungeon);
            return PartyBoardText.line("where.dungeon", name, depth);
        }
        return PartyBoardText.line("where.world", name);
    }

    /** How long ago a notice went up, in whole minutes; under one, just now. */
    @Nonnull
    private static Message ago(long postedAt) {
        long minutes = Math.max(0L, (System.currentTimeMillis() - postedAt) / 60_000L);
        return minutes < 1 ? PartyBoardText.line("ago.now") : PartyBoardText.line("ago", minutes);
    }

    /** What players call the dungeon {@code id} on a board, its id when no loaded file answers. */
    @Nonnull
    private static Message tierWordOf(@Nonnull String id) {
        DungeonAsset dungeon = DungeonConfig.getInstance().resolve(id);
        return dungeon == null ? Msg.raw(id) : PartyBoardText.tierWord(dungeon);
    }

    // ==================== putting up a notice ====================

    private void paintPost(@Nonnull UICommandBuilder cmd, @Nonnull UIEventBuilder events,
            @Nonnull PartyBoardLadder ladder) {
        HeroPainter.paint(cmd, new Hero(null, PartyBoardText.line("post.title"), null,
                PartyBoardText.line("post.lead")));
        cmd.set(LIST_VIEW + ".Visible", false);
        cmd.set(POST_VIEW + ".Visible", true);

        // What for: the depths and daily the viewer has opened, then anything.
        List<String> keys = new ArrayList<>();
        List<Message> labels = new ArrayList<>();
        for (DungeonAsset dungeon : ladder.postable()) {
            keys.add(dungeon.getId());
            labels.add(dungeon.seedDaily() ? PartyBoardText.line("post.aim.daily") : PartyBoardText.tierWord(dungeon));
        }
        keys.add(ANY);
        labels.add(PartyBoardText.line("post.aim.any"));
        if (!keys.contains(aim)) {
            aim = keys.get(0);
        }
        cmd.clear(AIM_TILES);
        for (int i = 0; i < keys.size(); i++) {
            SegmentPainter.appendTwoLine(cmd, events, AIM_TILES, labels.get(i), keys.get(i).equals(aim), false, false,
                    carry(EventData.of("Action", AIM).append("Value", keys.get(i))));
        }

        // How many more: what the viewer's party has room for, four at most.
        int most = Math.max(1, PartyBoard.shared().maxWants(playerRef.getUuid()));
        wants = Math.max(1, Math.min(most, wants));
        cmd.clear(WANT_TILES);
        for (int n = 1; n <= most; n++) {
            SegmentPainter.append(cmd, events, WANT_TILES, Msg.num(n), n == wants, false, false,
                    carry(EventData.of("Action", WANTS).append("Value", Integer.toString(n))));
        }

        // Notes, up to two.
        List<PartyNoteAsset> offered = PartyNoteConfig.getInstance().offered();
        notes.removeIf(id -> PartyNoteConfig.getInstance().offered(id) == null);
        cmd.set(NOTE_HEAD + ".Visible", !offered.isEmpty());
        cmd.clear(NOTE_TILES);
        for (PartyNoteAsset note : offered) {
            SegmentPainter.appendTwoLine(cmd, events, NOTE_TILES, ContentKeys.tr(note.textKey()), picked(note.getId()),
                    false, false, carry(EventData.of("Action", NOTE).append("Value", note.getId())));
        }

        cmd.set(ANNOUNCE_BOX + ".Value", announce);

        Message title = PartyBoardText.title(PlayerDisplayNames.displayName(playerRef.getUuid(), null),
                ANY.equals(aim) ? null : aim, wants);
        Message notesLine = PartyBoardText.notes(notes);
        cmd.set(PREVIEW + ".TextSpans", notesLine == null ? PartyBoardText.line("post.preview.bare", title)
                : PartyBoardText.line("post.preview.line", title, notesLine));
        cmd.set(ENDS + ".TextSpans", PartyBoardText.line("post.ends", PartyBoard.minutes()));

        events.addEventBinding(CustomUIEventBindingType.Activating, PUT_UP_BUTTON,
                carry(EventData.of("Action", PUT_UP)));
        events.addEventBinding(CustomUIEventBindingType.Activating, BACK_BUTTON, carry(EventData.of("Action", BACK)));
    }

    /** {@code data} with the announce box's live state on it, so a repaint keeps what the viewer ticked. */
    @Nonnull
    private static EventData carry(@Nonnull EventData data) {
        return data.append(ANNOUNCE_KEY, ANNOUNCE_BOX + ".Value");
    }

    private boolean picked(@Nonnull String noteId) {
        for (String id : notes) {
            if (id.equalsIgnoreCase(noteId)) {
                return true;
            }
        }
        return false;
    }

    /** Pick a note, or unpick it; a third pick lets the first one go. */
    private void toggleNote(@Nullable String noteId) {
        PartyNoteAsset note = PartyNoteConfig.getInstance().offered(noteId);
        if (note == null) {
            return;
        }
        if (notes.removeIf(id -> id.equalsIgnoreCase(note.getId()))) {
            return;
        }
        notes.add(note.getId());
        while (notes.size() > PartyNotice.MAX_NOTES) {
            notes.remove(0);
        }
    }

    // ==================== events ====================

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull PartyBoardEventData data) {
        Player player = ref.isValid() ? store.getComponent(ref, Player.getComponentType()) : null;
        if (player == null) {
            this.sendUpdate(new UICommandBuilder(), new UIEventBuilder(), false);
            return;
        }
        if (data.announce != null) {
            this.announce = data.announce;
        }
        String action = data.action == null ? "" : data.action;
        try {
            switch (action) {
                case POST -> this.posting = true;
                case BACK -> this.posting = false;
                case AIM -> this.aim = data.value == null || data.value.isBlank() ? ANY : data.value.trim();
                case WANTS -> this.wants = parseWants(data.value, this.wants);
                case NOTE -> toggleNote(data.value);
                case PUT_UP -> putUp(ref, store);
                case TAKE_DOWN -> {
                    if (PartyBoard.shared().takeDown(playerRef.getUuid(), PartyBoard.Down.TAKEN)) {
                        showToast(ToastKind.SUCCESS, PartyBoardText.line("msg.down.taken"));
                    }
                }
                case ASK -> ask(ref, store, data.notice);
                default -> {
                    player.getPageManager().setPage(ref, store, Page.None);
                    return;
                }
            }
        } catch (Throwable t) {
            SafeLog.warn(PartyBoard.LOG + "the party board could not answer '" + action + "' for "
                    + playerRef.getUuid(), t);
        }
        player.getPageManager().openCustomPage(ref, store, this);
    }

    /** Put the viewer's notice up from the form, and back to the list when it went up. */
    private void putUp(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store) {
        PartyBoardLadder ladder = PartyBoardLadder.read(ref, store, playerRef.getUuid(), dungeon);
        String target = ANY.equals(aim) ? null : aim;
        if (target != null && !postable(ladder, target)) {
            // A depth the form no longer offers (its clear undone, the file switched off): the form repaints its choices.
            this.aim = ANY;
            return;
        }
        PartyBoard.Posted posted = PartyBoard.shared().post(playerRef.getUuid(), dungeon, place, target, wants,
                List.copyOf(notes), announce);
        switch (posted) {
            case UP -> {
                this.posting = false;
                showToast(ToastKind.SUCCESS, PartyBoardText.line("msg.up"));
            }
            case UP_NOT_ANNOUNCED -> {
                this.posting = false;
                showToast(ToastKind.SUCCESS, PartyBoardText.line("msg.up.quiet", PartyBoard.minutes()));
            }
            case ALREADY_UP -> {
                this.posting = false;
                showToast(ToastKind.WARNING, PartyBoardText.line("refuse.already"));
            }
            case IN_OTHER_PARTY -> showToast(ToastKind.WARNING, PartyBoardText.line("refuse.otherparty"));
            case PARTY_FULL -> showToast(ToastKind.WARNING, PartyBoardText.line("refuse.ownfull"));
        }
    }

    /** Is {@code id} a depth or daily the viewer may post for? */
    private static boolean postable(@Nonnull PartyBoardLadder ladder, @Nonnull String id) {
        for (DungeonAsset dungeon : ladder.postable()) {
            if (dungeon.getId() != null && dungeon.getId().equalsIgnoreCase(id)) {
                return true;
            }
        }
        return false;
    }

    /** Ask to join the notice its button is on; the outcome reads as a toast over the board. */
    private void ask(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nullable String noticeId) {
        UUID poster = parseUuid(noticeId);
        if (poster == null) {
            return;
        }
        PartyBoard board = PartyBoard.shared();
        PartyNotice notice = board.noticeOf(poster);
        String opener = notice == null ? null
                : PartyBoardLadder.read(ref, store, playerRef.getUuid(), dungeon).lockedBehind(notice.aim());
        switch (board.ask(playerRef.getUuid(), poster, opener == null)) {
            case SENT -> showToast(ToastKind.SUCCESS,
                    PartyBoardText.line("msg.asked", PlayerDisplayNames.plainName(poster, null)));
            case GONE -> showToast(ToastKind.WARNING, PartyBoardText.line("refuse.gone"));
            case NOT_OPEN -> showToast(ToastKind.WARNING,
                    PartyBoardText.line("refuse.locked", opener == null ? PartyBoardText.line("aim.any")
                            : tierWordOf(opener)));
            case IN_PARTY -> showToast(ToastKind.WARNING, PartyBoardText.line("refuse.inparty"));
            case FULL -> showToast(ToastKind.WARNING, PartyBoardText.line("refuse.full"));
            case ALREADY -> {
            }
        }
    }

    private static int parseWants(@Nullable String value, int fallback) {
        try {
            return value == null ? fallback
                    : Math.max(1, Math.min(PartyNotice.MAX_WANTS, Integer.parseInt(value.trim())));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Nullable
    private static UUID parseUuid(@Nullable String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(s.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
