package com.ziggfreed.common.party;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

/**
 * One pre-formed group of players that queues together. Holds the ordered member set
 * (owner first), the owner, and the pending invites - all in memory, ephemeral. All
 * reads + mutations are synchronized on {@code this}; the package-private mutators are
 * driven by {@link PartyService} (which also owns the global one-party-per-player
 * reservation, the {@code MatchmakingQueue}-style {@code partyOf} invariant).
 *
 * <p>The {@link #snapshot()} is the only thing a UI renders from; usernames are resolved
 * live at render time, never stored here.
 */
public final class Party {

    private final String id;
    private final String gameId;
    private final PartyConfig config;

    /** Owner first, then join order. Guarded by {@code this}. */
    private final LinkedHashSet<UUID> members = new LinkedHashSet<>();
    private final Map<UUID, PartyInvite> pendingInvites = new LinkedHashMap<>();
    private UUID owner;
    /** Owner-settable queue privacy: true = launch this party alone (no stranger backfill). Default public. */
    private boolean privateLobby = false;

    Party(@Nonnull String id, @Nonnull String gameId, @Nonnull UUID owner, @Nonnull PartyConfig config) {
        this.id = id;
        this.gameId = gameId;
        this.owner = owner;
        this.config = config;
        this.members.add(owner);
    }

    @Nonnull public String id() {
        return id;
    }

    @Nonnull public String gameId() {
        return gameId;
    }

    @Nonnull public PartyConfig config() {
        return config;
    }

    public synchronized UUID owner() {
        return owner;
    }

    public synchronized boolean isOwner(@Nonnull UUID uuid) {
        return owner.equals(uuid);
    }

    public synchronized boolean contains(@Nonnull UUID uuid) {
        return members.contains(uuid);
    }

    public synchronized int size() {
        return members.size();
    }

    public synchronized boolean isFull() {
        return members.size() >= config.maxSize();
    }

    /** True if the party queues PRIVATE (launches alone, no stranger backfill). */
    public synchronized boolean isPrivate() {
        return privateLobby;
    }

    /** A defensive copy of the roster in owner-first join order. */
    @Nonnull
    public synchronized List<UUID> orderedMembers() {
        return new ArrayList<>(members);
    }

    /** An immutable point-in-time view for a UI page. */
    @Nonnull
    public synchronized PartySnapshot snapshot() {
        return new PartySnapshot(id, gameId, owner, List.copyOf(members),
                List.copyOf(pendingInvites.values()), config.maxSize(), privateLobby);
    }

    // ==================== package-private mutators (driven by PartyService) ====================

    synchronized boolean addMember(@Nonnull UUID uuid) {
        return members.add(uuid);
    }

    synchronized boolean removeMember(@Nonnull UUID uuid) {
        return members.remove(uuid);
    }

    synchronized boolean isEmpty() {
        return members.isEmpty();
    }

    synchronized void putInvite(@Nonnull PartyInvite invite) {
        pendingInvites.put(invite.invitee(), invite);
    }

    @Nullable
    synchronized PartyInvite getInvite(@Nonnull UUID invitee) {
        return pendingInvites.get(invitee);
    }

    @Nullable
    synchronized PartyInvite removeInvite(@Nonnull UUID invitee) {
        return pendingInvites.remove(invitee);
    }

    synchronized void setOwner(@Nonnull UUID uuid) {
        this.owner = uuid;
    }

    synchronized void setPrivate(boolean priv) {
        this.privateLobby = priv;
    }

    /**
     * Make the next still-present member (after the departed owner) the new owner, and
     * return it; {@code null} if the party is now empty. Keeps the owner at the head of
     * the ordered roster.
     */
    @Nullable
    synchronized UUID promoteNextOwner() {
        UUID next = members.isEmpty() ? null : members.iterator().next();
        if (next != null) {
            owner = next;
        }
        return next;
    }
}
