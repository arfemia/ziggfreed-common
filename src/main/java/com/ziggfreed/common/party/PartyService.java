package com.ziggfreed.common.party;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.ziggfreed.common.ZiggfreedCommonPlugin;
import com.ziggfreed.common.feedback.EventTitles;
import com.ziggfreed.common.feedback.Notify;
import com.ziggfreed.common.lobby.DelayScheduler;
import com.ziggfreed.common.lobby.LobbyService;

/**
 * The registry-backed party authority a consumer holds ONE of (the {@code LobbyService}
 * twin). Owns the live {@link Party}s + the global single-party-per-player reservation
 * ({@code partyOf}, mirroring the lobby's {@code queuedTo}) + the one daemon scheduler
 * invite-expiry timers run on. A player is in &le;1 party; a party is invite-built from
 * the searchable online roster (no friends list, ephemeral).
 *
 * <p>Mutations are synchronized on {@code this}; reads + maps are concurrent. ALL
 * player-facing delivery is packet-only - {@code Universe.getPlayer} (store-free) +
 * {@code Notify}/{@code EventTitles}, NEVER a Store/Ref read - and every {@link Message}
 * comes pre-built from the consumer's {@link PartyMessages} (common stays locale-free).
 * The party then queues as a UNIT through the lobby's {@code joinParty}; this service
 * never launches a round itself.
 */
public final class PartyService {

    private final String gameId;
    private final PartyConfig defaultConfig;
    private final PartyMessages messages;
    private final Predicate<UUID> online;
    private final DelayScheduler scheduler;
    private final LongSupplier clock;
    @Nullable private final ScheduledExecutorService ownedExecutor;

    private final Map<String, Party> parties = new ConcurrentHashMap<>();
    /** Global single-party-per-player invariant (mirrors LobbyService.queuedTo). */
    private final Map<UUID, String> partyOf = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<PartyListener> listeners = new CopyOnWriteArrayList<>();

    /** Production: an owned single daemon scheduler + the default online predicate. */
    public PartyService(@Nonnull String gameId, @Nonnull PartyConfig defaultConfig, @Nonnull PartyMessages messages) {
        this(gameId, defaultConfig, messages, LobbyService.universeOnline());
    }

    public PartyService(@Nonnull String gameId, @Nonnull PartyConfig defaultConfig, @Nonnull PartyMessages messages,
                        @Nonnull Predicate<UUID> online) {
        this.gameId = gameId;
        this.defaultConfig = defaultConfig;
        this.messages = messages;
        this.online = online;
        this.clock = System::currentTimeMillis;
        ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ziggfreed-party");
            t.setDaemon(true);
            return t;
        });
        this.ownedExecutor = exec;
        this.scheduler = (task, delay, unit) -> {
            final ScheduledFuture<?> f = exec.schedule(task, delay, unit);
            return () -> f.cancel(false);
        };
    }

    /** Test seam: a caller-supplied (manual) scheduler + online predicate + clock; no executor is owned. */
    PartyService(@Nonnull String gameId, @Nonnull PartyConfig defaultConfig, @Nonnull PartyMessages messages,
                 @Nonnull Predicate<UUID> online, @Nonnull DelayScheduler scheduler, @Nonnull LongSupplier clock) {
        this.gameId = gameId;
        this.defaultConfig = defaultConfig;
        this.messages = messages;
        this.online = online;
        this.scheduler = scheduler;
        this.clock = clock;
        this.ownedExecutor = null;
    }

    // ==================== queries ====================

    @Nullable
    public Party partyOf(@Nonnull UUID uuid) {
        String id = partyOf.get(uuid);
        return id == null ? null : parties.get(id);
    }

    public boolean isInParty(@Nonnull UUID uuid) {
        return partyOf.containsKey(uuid);
    }

    @Nullable
    public Party byId(@Nonnull String partyId) {
        return parties.get(partyId);
    }

    /**
     * All live (un-expired) invites addressed TO {@code invitee}, across every party -
     * the "Pending invites" the invitee accepts/declines from their own party page.
     */
    @Nonnull
    public List<PartyInvite> pendingInvitesFor(@Nonnull UUID invitee) {
        long nowMs = clock.getAsLong();
        List<PartyInvite> out = new java.util.ArrayList<>();
        for (Party party : parties.values()) {
            PartyInvite inv = party.getInvite(invitee);
            if (inv != null && !inv.isExpired(nowMs)) {
                out.add(inv);
            }
        }
        return out;
    }

    public void addListener(@Nonnull PartyListener listener) {
        listeners.add(listener);
    }

    public void removeListener(@Nonnull PartyListener listener) {
        listeners.remove(listener);
    }

    // ==================== mutations ====================

    /** The player's party, creating a fresh solo one (owned by {@code owner}) if absent. */
    @Nonnull
    public synchronized Party getOrCreate(@Nonnull UUID owner) {
        Party existing = partyOf(owner);
        if (existing != null) {
            return existing;
        }
        String id = UUID.randomUUID().toString();
        Party party = new Party(id, gameId, owner, defaultConfig);
        parties.put(id, party);
        partyOf.put(owner, id);
        return party;
    }

    /** Invite {@code invitee} into {@code inviter}'s party (auto-creating a solo party). */
    @Nonnull
    public synchronized InviteResult invite(@Nonnull UUID inviter, @Nonnull UUID invitee) {
        if (inviter.equals(invitee)) {
            return InviteResult.SELF;
        }
        Party party = getOrCreate(inviter);
        if (party.config().ownerOnlyInvite() && !party.isOwner(inviter)) {
            return InviteResult.NOT_OWNER;
        }
        if (party.isFull()) {
            return InviteResult.PARTY_FULL;
        }
        if (!online.test(invitee)) {
            return InviteResult.TARGET_OFFLINE;
        }
        if (isInParty(invitee)) {
            return InviteResult.TARGET_IN_PARTY;
        }
        long now = clock.getAsLong();
        PartyInvite existing = party.getInvite(invitee);
        if (existing != null && !existing.isExpired(now)) {
            return InviteResult.ALREADY_INVITED;
        }
        long expiry = party.config().inviteTimeoutSeconds() > 0
                ? now + party.config().inviteTimeoutSeconds() * 1000L : 0L;
        party.putInvite(new PartyInvite(party.id(), inviter, invitee, expiry));
        if (party.config().inviteTimeoutSeconds() > 0) {
            scheduler.schedule(() -> expireInvite(party.id(), invitee),
                    party.config().inviteTimeoutSeconds(), TimeUnit.SECONDS);
        }

        String inviterName = name(inviter);
        success(invitee, messages.invited(inviterName, party.size(), party.config().maxSize()));
        banner(invitee, messages.inviteBannerPrimary(inviterName), messages.inviteBannerSecondary());
        info(inviter, messages.inviteSent(name(invitee)));
        fireChange(party);
        return InviteResult.SENT;
    }

    /** Accept a pending invite to {@code partyId}. */
    @Nonnull
    public synchronized AcceptResult accept(@Nonnull UUID invitee, @Nonnull String partyId) {
        Party party = parties.get(partyId);
        if (party == null) {
            return AcceptResult.NO_INVITE;
        }
        PartyInvite invite = party.getInvite(invitee);
        if (invite == null) {
            return AcceptResult.NO_INVITE;
        }
        if (invite.isExpired(clock.getAsLong())) {
            party.removeInvite(invitee);
            return AcceptResult.EXPIRED;
        }
        if (party.isFull()) {
            return AcceptResult.PARTY_FULL;
        }
        if (partyOf.putIfAbsent(invitee, partyId) != null) {
            return AcceptResult.ALREADY_IN_PARTY;
        }
        party.removeInvite(invitee);
        party.addMember(invitee);
        hideBanner(invitee);
        broadcast(party, messages.memberJoined(name(invitee), party.size(), party.config().maxSize()), Tone.SUCCESS);
        fireChange(party);
        return AcceptResult.JOINED;
    }

    /** Decline a pending invite to {@code partyId}. */
    public synchronized boolean decline(@Nonnull UUID invitee, @Nonnull String partyId) {
        Party party = parties.get(partyId);
        if (party == null) {
            return false;
        }
        PartyInvite removed = party.removeInvite(invitee);
        if (removed == null) {
            return false;
        }
        hideBanner(invitee);
        info(removed.inviter(), messages.inviteDeclined(name(invitee)));
        fireChange(party);
        return true;
    }

    /** Owner-only: remove {@code target} from the owner's party. */
    public synchronized boolean kick(@Nonnull UUID owner, @Nonnull UUID target) {
        Party party = partyOf(owner);
        if (party == null || !party.isOwner(owner) || owner.equals(target) || !party.contains(target)) {
            return false;
        }
        party.removeMember(target);
        partyOf.remove(target, party.id());
        info(target, messages.kicked());
        broadcast(party, messages.kickedMember(name(target)), Tone.WARNING);
        fireChange(party);
        return true;
    }

    /** Leave the caller's party; promotes the next owner or disbands an emptied party. */
    public synchronized boolean leave(@Nonnull UUID member) {
        Party party = partyOf(member);
        if (party == null) {
            return false;
        }
        boolean wasOwner = party.isOwner(member);
        party.removeMember(member);
        partyOf.remove(member, party.id());
        if (party.isEmpty()) {
            parties.remove(party.id(), party);
            return true;
        }
        if (wasOwner) {
            UUID next = party.promoteNextOwner();
            if (next != null) {
                broadcast(party, messages.ownerTransferred(name(next)), Tone.DEFAULT);
            }
        }
        broadcast(party, messages.memberLeft(name(member)), Tone.DEFAULT);
        fireChange(party);
        return true;
    }

    /** Owner-only: disband the whole party. */
    public synchronized boolean disband(@Nonnull UUID owner) {
        Party party = partyOf(owner);
        if (party == null || !party.isOwner(owner)) {
            return false;
        }
        List<UUID> members = party.orderedMembers();
        for (UUID u : members) {
            partyOf.remove(u, party.id());
        }
        parties.remove(party.id(), party);
        for (UUID u : members) {
            info(u, messages.disbanded());
        }
        return true;
    }

    /** Owner-only: hand ownership to another member. */
    public synchronized boolean transferOwner(@Nonnull UUID owner, @Nonnull UUID target) {
        Party party = partyOf(owner);
        if (party == null || !party.isOwner(owner) || !party.contains(target)) {
            return false;
        }
        party.setOwner(target);
        broadcast(party, messages.ownerTransferred(name(target)), Tone.DEFAULT);
        fireChange(party);
        return true;
    }

    /** Stop the owned scheduler (no-op for the test constructor's injected one). */
    public void shutdown() {
        if (ownedExecutor != null) {
            ownedExecutor.shutdownNow();
        }
    }

    // ==================== invite expiry ====================

    private synchronized void expireInvite(@Nonnull String partyId, @Nonnull UUID invitee) {
        Party party = parties.get(partyId);
        if (party == null) {
            return;
        }
        PartyInvite invite = party.getInvite(invitee);
        if (invite == null || !invite.isExpired(clock.getAsLong())) {
            return; // already accepted/declined, or renewed
        }
        party.removeInvite(invitee);
        hideBanner(invitee);
        warn(invitee, messages.inviteExpired());
        fireChange(party);
    }

    // ==================== listener fan-out + delivery (packet-only, guarded) ====================

    private enum Tone { DEFAULT, SUCCESS, WARNING, DANGER }

    private void fireChange(@Nonnull Party party) {
        if (listeners.isEmpty()) {
            return;
        }
        PartySnapshot snap = party.snapshot();
        for (PartyListener l : listeners) {
            try {
                l.onChange(snap);
            } catch (Throwable ignored) {
            }
        }
    }

    private void broadcast(@Nonnull Party party, @Nullable Message msg, @Nonnull Tone tone) {
        if (msg == null) {
            return;
        }
        for (UUID u : party.orderedMembers()) {
            deliver(u, msg, tone);
        }
    }

    private void success(@Nonnull UUID uuid, @Nullable Message msg) {
        deliver(uuid, msg, Tone.SUCCESS);
    }

    private void info(@Nonnull UUID uuid, @Nullable Message msg) {
        deliver(uuid, msg, Tone.DEFAULT);
    }

    private void warn(@Nonnull UUID uuid, @Nullable Message msg) {
        deliver(uuid, msg, Tone.WARNING);
    }

    private void deliver(@Nonnull UUID uuid, @Nullable Message msg, @Nonnull Tone tone) {
        if (msg == null) {
            return;
        }
        PlayerRef p = player(uuid);
        if (p == null) {
            return;
        }
        switch (tone) {
            case SUCCESS -> Notify.success(p, msg);
            case WARNING -> Notify.warning(p, msg);
            case DANGER -> Notify.danger(p, msg);
            default -> Notify.def(p, msg);
        }
    }

    private void banner(@Nonnull UUID uuid, @Nullable Message primary, @Nullable Message secondary) {
        if (primary == null || secondary == null) {
            return;
        }
        PlayerRef p = player(uuid);
        if (p != null) {
            EventTitles.show(p, primary, secondary, true);
        }
    }

    private void hideBanner(@Nonnull UUID uuid) {
        PlayerRef p = player(uuid);
        if (p != null) {
            try {
                EventTitles.hide(p, EventTitles.DEFAULT_FADE_OUT);
            } catch (Throwable ignored) {
            }
        }
    }

    @Nullable
    private static PlayerRef player(@Nonnull UUID uuid) {
        try {
            return Universe.get().getPlayer(uuid);
        } catch (Throwable t) {
            return null;
        }
    }

    @Nonnull
    private static String name(@Nonnull UUID uuid) {
        try {
            PlayerRef p = Universe.get().getPlayer(uuid);
            if (p != null && p.getUsername() != null) {
                return p.getUsername();
            }
        } catch (Throwable ignored) {
        }
        return "Player";
    }
}
