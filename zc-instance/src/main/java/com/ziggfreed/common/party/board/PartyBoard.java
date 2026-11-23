package com.ziggfreed.common.party.board;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.ziggfreed.common.feedback.Notify;
import com.ziggfreed.common.lobby.LobbyService;
import com.ziggfreed.common.party.AcceptResult;
import com.ziggfreed.common.party.Party;
import com.ziggfreed.common.party.PartyService;
import com.ziggfreed.common.party.PartySnapshot;
import com.ziggfreed.common.party.ZcParty;
import com.ziggfreed.common.party.page.PartyInvitePage;
import com.ziggfreed.common.ui.name.PlayerDisplayNames;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.util.ZigScheduler;

/**
 * The party board: notices a player puts up to find a party, and the asks to join them (WoW's meeting stone, never a
 * queue that throws strangers together). One board serves every board block on the server, over the library's own
 * party ({@link ZcParty}); a board kept for one dungeon only shows that dungeon's notices ({@link PartyNotice#onBoard}).
 * Nobody is ever moved: a yes puts the asker in the poster's party ({@link PartyService#admit}), and both walk to the
 * dungeon's gate, which says where their party is.
 *
 * <p><b>A notice</b> is one per player, put up by someone in no party or leading their own, with room left. It comes
 * down when the party reaches the size it asked for or is full, when its poster takes it down, leaves the party or
 * logs off, or after {@code Notices.Minutes} ({@link PartyBoardAsset}); none is kept over a restart.
 *
 * <p><b>An ask</b> is one player asking one notice; the poster answers it on their party page ({@link PartyInvitePage}
 * shows it as a row with Accept and Decline). An ask goes with its notice, and with the asker once they join a party
 * or log off.
 *
 * <p><b>Threads.</b> Mutations are {@code synchronized} on the board and never call a party mutation while they hold
 * it, so the party service's lock and the board's are never taken the other way round: a yes leaves the board's lock
 * before it calls {@link PartyService#admit}, and the party listener, which runs under the service's lock, hands its
 * check to the library's scheduler. Delivery is packet-only ({@link Notify}).
 */
public final class PartyBoard {

    static final String LOG = "[party] ";

    /** How often the board takes down a notice whose time is up or whose poster has gone. */
    static final long SWEEP_SECONDS = 20L;

    @Nullable private static volatile PartyBoard shared;

    private final PartyService service;
    private final LongSupplier clock;
    private final Predicate<UUID> online;

    /** Notices by poster. */
    private final Map<UUID, PartyNotice> notices = new ConcurrentHashMap<>();

    /** Asks by poster: askers in the order they asked. Guarded by {@code this}. */
    private final Map<UUID, LinkedHashMap<UUID, Long>> asks = new LinkedHashMap<>();

    /**
     * When each poster last had a notice announced: one announcement per poster per {@code Notices.Minutes}, so taking
     * a notice down and putting it up again never repeats the line to everyone online or the owner's commands. Kept
     * over a log off (a relog announces no sooner), forgotten by the sweep once that long has passed.
     */
    private final Map<UUID, Long> announcedAt = new ConcurrentHashMap<>();

    PartyBoard(@Nonnull PartyService service, @Nonnull LongSupplier clock, @Nonnull Predicate<UUID> online) {
        this.service = service;
        this.clock = clock;
        this.online = online;
    }

    /** The library's board, over its own party, built on the first call and listening to that party from then on. */
    @Nonnull
    public static PartyBoard shared() {
        PartyBoard built = shared;
        if (built == null) {
            synchronized (PartyBoard.class) {
                built = shared;
                if (built == null) {
                    built = new PartyBoard(ZcParty.service(), System::currentTimeMillis, LobbyService.universeOnline());
                    PartyBoard board = built;
                    built.service.addListener(snap -> board.partyChanged(snap));
                    shared = built;
                }
            }
        }
        return built;
    }

    /** The board when one was built, else null: a disconnect or a sweep never builds one. */
    @Nullable
    static PartyBoard ifBuilt() {
        return shared;
    }

    /** Start the sweep on the library's scheduler; at setup. A board that was never built sweeps nothing. */
    public static void startSweep() {
        try {
            ZigScheduler.scheduleWithFixedDelay(() -> {
                PartyBoard board = shared;
                if (board != null) {
                    try {
                        board.sweep();
                    } catch (Throwable t) {
                        SafeLog.warn(LOG + "the party board's sweep failed; it runs again shortly", t);
                    }
                }
            }, SWEEP_SECONDS, SWEEP_SECONDS, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            SafeLog.warn(LOG + "the party board's sweep could not be scheduled; a notice comes down only as players "
                    + "read the board", e);
        }
    }

    /** A player left the server: their notice comes down and their asks go. Builds no board. */
    public static void onPlayerDisconnect(@Nonnull PlayerDisconnectEvent event) {
        PlayerRef left = event.getPlayerRef();
        UUID player = left == null ? null : left.getUuid();
        PartyBoard board = shared;
        if (board != null && player != null) {
            board.leftServer(player);
        }
    }

    /**
     * The asks to {@code poster}'s notice when the board is built over {@code service} (a party page over another
     * service shows none); empty when no board was built. For the party page; builds no board.
     */
    @Nonnull
    public static List<UUID> asksFor(@Nonnull PartyService service, @Nonnull UUID poster) {
        PartyBoard board = shared;
        return board == null || board.service != service ? List.of() : board.asksTo(poster);
    }

    /** {@link #admit} on the board built over {@code service}, or null when there is none. For the party page. */
    @Nullable
    public static AcceptResult admitFor(@Nonnull PartyService service, @Nonnull UUID poster, @Nonnull UUID asker) {
        PartyBoard board = shared;
        return board == null || board.service != service ? null : board.admit(poster, asker);
    }

    /** {@link #refuse} on the board built over {@code service}; false when there is none. For the party page. */
    public static boolean refuseFor(@Nonnull PartyService service, @Nonnull UUID poster, @Nonnull UUID asker) {
        PartyBoard board = shared;
        return board != null && board.service == service && board.refuse(poster, asker);
    }

    /** The party service this board puts players into. */
    @Nonnull
    public PartyService service() {
        return service;
    }

    // ==================== reads ====================

    /**
     * The notices up on the board kept for {@code boardDungeon} (null: every notice), newest first, leaving out any
     * whose time is up or whose poster is offline.
     */
    @Nonnull
    public List<PartyNotice> notices(@Nullable String boardDungeon) {
        long now = clock.getAsLong();
        long life = lifeMillis();
        List<PartyNotice> out = new ArrayList<>();
        for (PartyNotice notice : notices.values()) {
            if (notice.onBoard(boardDungeon) && now - notice.postedAt() < life && online.test(notice.poster())) {
                out.add(notice);
            }
        }
        out.sort(Comparator.comparingLong(PartyNotice::postedAt).reversed());
        return out;
    }

    /** {@code poster}'s notice while it is up, else null. */
    @Nullable
    public PartyNotice noticeOf(@Nonnull UUID poster) {
        PartyNotice notice = notices.get(poster);
        return notice != null && clock.getAsLong() - notice.postedAt() < lifeMillis() ? notice : null;
    }

    /** How many more {@code notice} wants now, by its poster's party as it stands. */
    public int remaining(@Nonnull PartyNotice notice) {
        Party party = service.partyOf(notice.poster());
        return notice.remaining(party == null ? 1 : party.size());
    }

    /** Has {@code asker} asked to join {@code poster}'s notice, and not been answered? */
    public synchronized boolean hasAsked(@Nonnull UUID asker, @Nonnull UUID poster) {
        Map<UUID, Long> to = asks.get(poster);
        return to != null && to.containsKey(asker);
    }

    /** The players asking to join {@code poster}'s notice, in the order they asked; none once it is down. */
    @Nonnull
    public synchronized List<UUID> asksTo(@Nonnull UUID poster) {
        Map<UUID, Long> to = asks.get(poster);
        if (to == null || noticeOf(poster) == null) {
            return List.of();
        }
        return List.copyOf(to.keySet());
    }

    /** The most more a poster may ask for now: room left in their party, at most {@value PartyNotice#MAX_WANTS}. */
    public int maxWants(@Nonnull UUID poster) {
        Party party = service.partyOf(poster);
        int size = party == null ? 1 : party.size();
        int max = party == null ? service.defaultConfig().maxSize() : party.config().maxSize();
        return Math.max(0, Math.min(PartyNotice.MAX_WANTS, max - size));
    }

    /** How long a notice stays up, in minutes, as the owner's settings say. */
    public static int minutes() {
        return PartyBoardConfig.getInstance().settings().minutes();
    }

    // ==================== putting up and taking down ====================

    /** What became of a notice put up. */
    public enum Posted {
        /** It is up (and announced, when its poster asked). */
        UP,
        /**
         * It is up, but its poster asked for an announcement and had one less than {@code Notices.Minutes} ago, so it
         * was not announced.
         */
        UP_NOT_ANNOUNCED,
        /** The poster has one up already. */
        ALREADY_UP,
        /** The poster is in a party they do not lead. */
        IN_OTHER_PARTY,
        /** The poster's party has no room left. */
        PARTY_FULL
    }

    /**
     * Put up {@code poster}'s notice at a board ({@code dungeon} it is kept for and the {@code place} it stands, each
     * null for none), for {@code aim} (a depth's or a daily's dungeon file, null for anything), wanting {@code wants}
     * more (held to what their party has room for) with up to two {@code notes} (note ids; ones not offered are left
     * out). When {@code announce} (the poster's ticked box), the announcement runs once it is up
     * ({@link PartyBoardAnnounce}), at most once per poster per {@code Notices.Minutes}
     * ({@link Posted#UP_NOT_ANNOUNCED} otherwise).
     */
    @Nonnull
    public Posted post(@Nonnull UUID poster, @Nullable String dungeon, @Nullable String place, @Nullable String aim,
            int wants, @Nonnull List<String> notes, boolean announce) {
        PartyNotice notice;
        boolean announceNow = false;
        synchronized (this) {
            if (noticeOf(poster) != null) {
                return Posted.ALREADY_UP;
            }
            Party party = service.partyOf(poster);
            if (party != null && !party.isOwner(poster)) {
                return Posted.IN_OTHER_PARTY;
            }
            int room = maxWants(poster);
            if (room <= 0) {
                return Posted.PARTY_FULL;
            }
            int asked = Math.max(1, Math.min(room, wants));
            int size = party == null ? 1 : party.size();
            List<String> kept = new ArrayList<>();
            for (String id : notes) {
                PartyNoteAsset note = PartyNoteConfig.getInstance().offered(id);
                if (note != null && !kept.contains(note.getId()) && kept.size() < PartyNotice.MAX_NOTES) {
                    kept.add(note.getId());
                }
            }
            long now = clock.getAsLong();
            notice = new PartyNotice(poster, dungeon, place, aim, asked, size + asked, kept, now,
                    party == null ? null : party.id());
            notices.put(poster, notice);
            asks.remove(poster);
            if (announce) {
                Long last = announcedAt.get(poster);
                announceNow = last == null || now - last >= lifeMillis();
                if (announceNow) {
                    announcedAt.put(poster, now);
                }
            }
        }
        if (announceNow) {
            PartyBoardAnnounce.announce(notice);
        }
        return announce && !announceNow ? Posted.UP_NOT_ANNOUNCED : Posted.UP;
    }

    /** Why a notice came down. */
    public enum Down {
        /** Its poster took it down. */
        TAKEN,
        /** Its party reached the size it asked for, or filled. */
        FULL,
        /** Its time was up. */
        EXPIRED,
        /** Its poster left their party, or now follows someone else's lead. */
        LEFT,
        /** Its poster logged off. */
        OFFLINE
    }

    /**
     * Take {@code poster}'s notice down; false when none is up. Its poster hears why when it came down on its own
     * (full, or its time up); taking it down themselves, a page says so. Its asks go with it.
     */
    public boolean takeDown(@Nonnull UUID poster, @Nonnull Down why) {
        PartyNotice gone;
        synchronized (this) {
            gone = notices.remove(poster);
            asks.remove(poster);
        }
        if (gone == null) {
            return false;
        }
        switch (why) {
            case FULL -> tell(poster, PartyBoardText.line("msg.down.full"), true);
            case EXPIRED -> tell(poster, PartyBoardText.line("msg.down.expired", minutes()), false);
            default -> {
            }
        }
        PartyInvitePage.repaintOpen(poster);
        return true;
    }

    // ==================== asking and answering ====================

    /** What became of an ask. */
    public enum Asked {
        /** The poster has it. */
        SENT,
        /** The notice is down. */
        GONE,
        /** The asker has not opened what the notice is for. */
        NOT_OPEN,
        /** The asker is in a party already. */
        IN_PARTY,
        /** The poster's party is full. */
        FULL,
        /** The asker asked already, or is its poster. */
        ALREADY
    }

    /**
     * {@code asker} asks to join {@code poster}'s notice; {@code opened} says whether they have opened what it is for
     * (the page reads it on the asker's world thread). The poster hears it (on their party page too), and so does the
     * asker.
     */
    @Nonnull
    public Asked ask(@Nonnull UUID asker, @Nonnull UUID poster, boolean opened) {
        synchronized (this) {
            PartyNotice notice = noticeOf(poster);
            if (notice == null) {
                return Asked.GONE;
            }
            if (asker.equals(poster) || hasAsked(asker, poster)) {
                return Asked.ALREADY;
            }
            if (!opened) {
                return Asked.NOT_OPEN;
            }
            if (service.isInParty(asker)) {
                return Asked.IN_PARTY;
            }
            Party party = service.partyOf(poster);
            if (party != null && (party.isFull() || notice.remaining(party.size()) <= 0)) {
                return Asked.FULL;
            }
            asks.computeIfAbsent(poster, p -> new LinkedHashMap<>()).put(asker, clock.getAsLong());
        }
        tell(poster, PartyBoardText.line("msg.asks", PlayerDisplayNames.plainName(asker, null)), true);
        PartyInvitePage.repaintOpen(poster);
        return Asked.SENT;
    }

    /**
     * {@code poster} says yes to {@code asker}: the ask goes, and the asker joins the poster's party (made now when
     * the poster is still alone) through {@link PartyService#admit}, called outside the board's lock. The asker hears
     * it; the party hears its usual joined line. {@link AcceptResult#NO_INVITE} when there is no such ask or the
     * notice is down.
     */
    @Nonnull
    public AcceptResult admit(@Nonnull UUID poster, @Nonnull UUID asker) {
        synchronized (this) {
            Map<UUID, Long> to = asks.get(poster);
            if (to == null || to.remove(asker) == null || noticeOf(poster) == null) {
                return AcceptResult.NO_INVITE;
            }
        }
        AcceptResult result = service.admit(poster, asker);
        if (result == AcceptResult.JOINED) {
            tell(asker, PartyBoardText.line("msg.accepted", PlayerDisplayNames.plainName(poster, null)), true);
        }
        return result;
    }

    /** {@code poster} says no to {@code asker}: the ask goes and the asker hears it. False when there was none. */
    public boolean refuse(@Nonnull UUID poster, @Nonnull UUID asker) {
        synchronized (this) {
            Map<UUID, Long> to = asks.get(poster);
            if (to == null || to.remove(asker) == null) {
                return false;
            }
        }
        tell(asker, PartyBoardText.line("msg.declined", PlayerDisplayNames.plainName(poster, null)), false);
        return true;
    }

    // ==================== keeping it true ====================

    /**
     * A party changed (the listener, under the service's lock): check its members' notices and asks on the library's
     * scheduler, off that lock; inline only where there is no scheduler (a unit test).
     */
    void partyChanged(@Nonnull PartySnapshot snap) {
        List<UUID> members = List.copyOf(snap.members());
        String partyId = snap.id();
        Runnable check = () -> recheck(members, partyId);
        try {
            ZigScheduler.execute(check);
        } catch (RejectedExecutionException e) {
            check.run();
        }
    }

    /** Recheck every notice whose poster is among {@code members} or was filed under {@code partyId}, and the asks. */
    void recheck(@Nonnull List<UUID> members, @Nonnull String partyId) {
        List<UUID> posters = new ArrayList<>();
        for (PartyNotice notice : notices.values()) {
            if (members.contains(notice.poster()) || partyId.equals(notice.partyId())) {
                posters.add(notice.poster());
            }
        }
        for (UUID poster : posters) {
            recheckNotice(poster);
        }
        // An asker who joined a party since is no longer asking: their row leaves the poster's open party page.
        List<UUID> answered = new ArrayList<>();
        synchronized (this) {
            for (Map.Entry<UUID, LinkedHashMap<UUID, Long>> to : asks.entrySet()) {
                if (to.getValue().keySet().removeIf(service::isInParty)) {
                    answered.add(to.getKey());
                }
            }
        }
        for (UUID poster : answered) {
            PartyInvitePage.repaintOpen(poster);
        }
    }

    /** One notice against its poster's party: down when they left it or follow another's lead, or once it filled. */
    private void recheckNotice(@Nonnull UUID poster) {
        Down why = null;
        synchronized (this) {
            PartyNotice notice = notices.get(poster);
            if (notice == null) {
                return;
            }
            Party party = service.partyOf(poster);
            if (notice.partyId() != null && (party == null || !party.id().equals(notice.partyId()))) {
                why = Down.LEFT;
            } else if (party != null && !party.isOwner(poster)) {
                why = Down.LEFT;
            } else if (party != null) {
                if (notice.partyId() == null) {
                    notices.put(poster, notice.withParty(party.id()));
                }
                if (party.isFull() || notice.remaining(party.size()) <= 0) {
                    why = Down.FULL;
                }
            }
        }
        if (why != null) {
            takeDown(poster, why);
        }
    }

    /**
     * Take down every notice whose time is up (its poster hears it) or whose poster is offline, and forget an
     * announcement older than a notice's life.
     */
    void sweep() {
        long now = clock.getAsLong();
        long life = lifeMillis();
        for (PartyNotice notice : List.copyOf(notices.values())) {
            if (!online.test(notice.poster())) {
                leftServer(notice.poster());
            } else if (now - notice.postedAt() >= life) {
                takeDown(notice.poster(), Down.EXPIRED);
            }
        }
        announcedAt.values().removeIf(at -> now - at >= life);
    }

    /** {@code player} logged off: their notice comes down, and their asks and the asks to them go. */
    void leftServer(@Nonnull UUID player) {
        takeDown(player, Down.OFFLINE);
        List<UUID> answered = new ArrayList<>();
        synchronized (this) {
            for (Map.Entry<UUID, LinkedHashMap<UUID, Long>> to : asks.entrySet()) {
                if (to.getValue().remove(player) != null) {
                    answered.add(to.getKey());
                }
            }
        }
        for (UUID poster : answered) {
            PartyInvitePage.repaintOpen(poster);
        }
    }

    private long lifeMillis() {
        return minutes() * 60_000L;
    }

    /** A line to {@code player} in the corner feed, when they are online. */
    private static void tell(@Nonnull UUID player, @Nonnull Message line, boolean good) {
        try {
            PlayerRef ref = Universe.get().getPlayer(player);
            if (ref == null) {
                return;
            }
            if (good) {
                Notify.success(ref, line);
            } else {
                Notify.def(ref, line);
            }
        } catch (Throwable ignored) {
            // A player between worlds misses a line; the board itself is already true.
        }
    }
}
