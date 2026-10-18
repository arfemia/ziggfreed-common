package com.ziggfreed.common.instance.claims;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.loot.claim.Claim;
import com.ziggfreed.common.loot.claim.ClaimAddedEvent;
import com.ziggfreed.common.loot.claim.ClaimStore;
import com.ziggfreed.common.ui.toast.ToastDelivery;
import com.ziggfreed.common.ui.toast.ToastKind;
import com.ziggfreed.common.ui.toast.ToastLine;
import com.ziggfreed.common.ui.toast.ToastSpec;
import com.ziggfreed.common.ui.toast.ToastablePage;
import com.ziggfreed.common.util.SafeLog;

/**
 * Telling a player a Claim waits, where they are looking (M527): a new Claim raises a toast naming it and the tab,
 * drawn into the page they have open or else in the corner, and once a session, at the first ready after login (or
 * after a world hop that comes before any toast), a notice saying how many wait. Neither hands anything over:
 * Claims are only ever collected (M428). A player offline when a Claim arrives hears of it at their next login.
 * Both work on a server running nothing but this library.
 *
 * <p><b>One toast a burst.</b> The Claims written for a player before their toast goes out (a boss's payouts and a
 * dungeon's completion Claim, written in one tick) are one burst, a Claim topped up within it keeping its one row
 * ({@link ClaimStore#awardOrTopUp}), and the toast is raised once, a task later on
 * their world, when the burst is drained: a lone Claim reads as its own title waiting under Claims (an untitled one
 * keeps the plain line), several read as one line with a row each. On a page the rows are the titles under that
 * line; in the corner, which shows a toast's rows without its headline, each row says where its Claim waits. The
 * toast ranks as a payout ({@link ToastSpec#PRIORITY_CLAIM}), so it queues behind or ahead of a receipt as
 * {@code ToastStore} decides rather than replacing it.
 *
 * <p><b>What a page offers itself.</b> A Claim the page the player has just opened offers with its own button (a
 * dungeon's results screen and its completion Claim, {@link #offeredOnPage}) is left out of the burst's toast, and a
 * burst left with nothing shows nothing. The mark lives only in the burst it came in, which the toast drains, so it
 * never outlives that moment.
 */
public final class ClaimNotices {

    /**
     * How long a burst may wait for its toast before a new Claim takes its world task as lost (the world closed with
     * it queued) and raises the toast again, the waiting Claims included.
     */
    static final long STALE_MS = 10_000L;

    /** The players already told this session that Claims wait, so a world hop does not say it again. */
    private static final Set<UUID> TOLD = ConcurrentHashMap.newKeySet();

    /**
     * The Claims written for a player since their toast was raised, oldest first, and the ids (lower-cased) a page
     * they have open offers itself. Replaced whole on every change.
     */
    private record Burst(@Nonnull List<Claim> claims, @Nonnull Set<String> offered, long sinceMs) {

        /** This burst with {@code claim} in it: a top-up of a Claim already here takes that one's row. */
        @Nonnull
        Burst adding(@Nonnull Claim claim, long nowMs) {
            List<Claim> more = new ArrayList<>(claims);
            int at = -1;
            for (int i = 0; i < more.size() && at < 0; i++) {
                if (more.get(i).is(claim.id())) {
                    at = i;
                }
            }
            if (at >= 0) {
                more.set(at, claim);
            } else {
                more.add(claim);
            }
            return new Burst(List.copyOf(more), offered, nowMs);
        }

        @Nonnull
        Burst offering(@Nonnull String id) {
            Set<String> more = new HashSet<>(offered);
            more.add(id);
            return new Burst(claims, Set.copyOf(more), sinceMs);
        }

        /** The Claims no page offers itself, in the order written. */
        @Nonnull
        List<Claim> told() {
            List<Claim> out = new ArrayList<>(claims.size());
            for (Claim claim : claims) {
                if (!offered.contains(idOf(claim.id()))) {
                    out.add(claim);
                }
            }
            return out;
        }
    }

    /** Each player's burst waiting for its toast. */
    private static final ConcurrentHashMap<UUID, Burst> BURSTS = new ConcurrentHashMap<>();

    private ClaimNotices() {
    }

    /** A new Claim: it joins the player's burst, and the burst's first Claim raises its toast, if they are online. */
    public static void onAdded(@Nonnull ClaimAddedEvent event) {
        try {
            Universe universe = Universe.get();
            UUID player = event.player();
            PlayerRef playerRef = universe == null ? null : universe.getPlayer(player);
            if (playerRef == null || playerRef.getReference() == null) {
                return;
            }
            TOLD.add(player);
            if (gather(player, event.claim(), System.currentTimeMillis())) {
                ToastDelivery.deliverWhenSettled(playerRef,
                        () -> drain(player, ToastablePage.isShowing(player)), null);
            }
        } catch (Throwable t) {
            SafeLog.fine("[claims] the new-Claim toast could not be raised: " + t.getMessage());
        }
    }

    /**
     * {@code claimId} is offered by the page {@code player} has just opened, with its own button: the toast of the
     * burst it was written in leaves it out. Kept only in that burst; with none waiting there is nothing to leave it
     * out of, and nothing is kept.
     */
    public static void offeredOnPage(@Nonnull UUID player, @Nonnull String claimId) {
        String id = idOf(claimId);
        BURSTS.computeIfPresent(player, (key, burst) -> burst.offering(id));
    }

    /**
     * Add {@code claim} to {@code player}'s burst; true when it starts one, or finds one whose toast never went out
     * within {@link #STALE_MS}, so the caller raises the toast.
     */
    static boolean gather(@Nonnull UUID player, @Nonnull Claim claim, long nowMs) {
        boolean[] raise = {false};
        BURSTS.compute(player, (key, burst) -> {
            if (burst == null) {
                raise[0] = true;
                return new Burst(List.of(claim), Set.of(), nowMs);
            }
            raise[0] = nowMs - burst.sinceMs() > STALE_MS;
            return burst.adding(claim, raise[0] ? nowMs : burst.sinceMs());
        });
        return raise[0];
    }

    /**
     * The burst's toast, as it goes out, on the player's world thread: the burst is taken whole, so a Claim written
     * after this starts a burst of its own. Null when nothing is waiting or every Claim is offered on a page.
     */
    @Nullable
    static ToastSpec drain(@Nonnull UUID player, boolean onPage) {
        Burst burst = BURSTS.remove(player);
        return burst == null ? null : addedToast(burst.told(), onPage, ClaimsText.LIBRARY);
    }

    /** A player ready in a world: the notice, once a session, while any Claim waits. */
    public static void onPlayerReady(@Nonnull PlayerReadyEvent event) {
        try {
            Player player = event.getPlayer();
            Ref<EntityStore> ref = event.getPlayerRef();
            World world = player == null ? null : player.getWorld();
            if (world == null || ref == null) {
                return;
            }
            world.execute(() -> tellWaiting(ref));
        } catch (Throwable t) {
            SafeLog.fine("[claims] the Claims notice could not be queued: " + t.getMessage());
        }
    }

    /** A player leaving: their next session is told again, and a burst still waiting goes with them. */
    public static void onPlayerDisconnect(@Nonnull PlayerDisconnectEvent event) {
        PlayerRef playerRef = event.getPlayerRef();
        UUID player = playerRef == null ? null : playerRef.getUuid();
        if (player != null) {
            TOLD.remove(player);
            BURSTS.remove(player);
        }
    }

    /** On the player's world thread: say how many Claims wait, unless none do or this session already heard. */
    private static void tellWaiting(@Nonnull Ref<EntityStore> ref) {
        try {
            if (!ref.isValid()) {
                return;
            }
            PlayerRef playerRef = PlayerAccess.playerRef(ref.getStore(), ref);
            UUID player = playerRef == null ? null : playerRef.getUuid();
            int waiting = player == null ? 0 : ClaimStore.shared().count(player);
            if (waiting <= 0 || !TOLD.add(player)) {
                return;
            }
            ToastDelivery.deliver(playerRef, readyNotice(ClaimsText.LIBRARY, waiting), null);
        } catch (Throwable t) {
            SafeLog.fine("[claims] the Claims notice could not be shown: " + t.getMessage());
        }
    }

    /**
     * What a burst of new Claims says, or null for none: one Claim is its own line, several are one line with a row
     * each, the Claim's title on a page ({@code onPage}) and its own line in the corner, which drops the headline.
     */
    @Nullable
    static ToastSpec addedToast(@Nonnull List<Claim> claims, boolean onPage, @Nonnull ClaimsText text) {
        if (claims.isEmpty()) {
            return null;
        }
        Message headline;
        List<ToastLine> rows = new ArrayList<>();
        if (claims.size() == 1) {
            headline = waiting(claims.get(0), text);
        } else {
            // Every Claim in a chest: the first one's chest says where they wait, in its own words.
            Message inChest = claims.stream().allMatch(SpoilsChests::inChest)
                    ? SpoilsChests.notice(claims.get(0), null) : null;
            headline = inChest != null ? inChest : text.line("notice.added.many");
            for (Claim claim : claims) {
                rows.add(onPage ? ToastLine.text(ClaimsView.title(claim, text))
                        : ToastLine.item(ClaimsDestinations.ICON, 1, waiting(claim, text)));
            }
        }
        return ToastSpec.of(ToastKind.REWARD, headline)
                .withIcon(ClaimsDestinations.ICON)
                .withLines(rows)
                .withPriority(ToastSpec.PRIORITY_CLAIM);
    }

    /**
     * One Claim waiting: its own title under Claims in the menu, or the plain line when it names none; a Claim filed
     * under a standing reward chest ({@link SpoilsChests#inChest}) says it waits in that chest too, in the chest's own
     * words (a boss's: the chest where the boss fell).
     */
    @Nonnull
    private static Message waiting(@Nonnull Claim claim, @Nonnull ClaimsText text) {
        Message title = ClaimsView.ownTitle(claim);
        Message inChest = SpoilsChests.notice(claim, title);
        if (inChest != null) {
            return inChest;
        }
        return title == null ? text.line("notice.added") : text.line("notice.added.named", title);
    }

    /** What the ready notice says: how many wait under Claims in the menu. */
    @Nonnull
    static ToastSpec readyNotice(@Nonnull ClaimsText text, int waiting) {
        return ToastSpec.of(ToastKind.INFO, text.line("notice.ready", waiting)).withIcon(ClaimsDestinations.ICON);
    }

    /** A Claim id as a mark keeps it: Claim ids match without regard to case. */
    @Nonnull
    private static String idOf(@Nonnull String claimId) {
        return claimId.trim().toLowerCase(Locale.ROOT);
    }
}
