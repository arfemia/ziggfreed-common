package com.ziggfreed.common.party;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import com.ziggfreed.common.lobby.DelayScheduler;

/**
 * Deterministic unit tests for {@link PartyService}, driven by a manual
 * {@link DelayScheduler} + an injected clock (no wall-clock waiting). Player-facing
 * delivery (Notify/EventTitles/Universe) is a guarded no-op with no server, so these
 * exercise pure registry/invite logic.
 */
class PartyServiceTest {

    private static final class ManualScheduler implements DelayScheduler {
        private final Deque<Task> tasks = new ArrayDeque<>();

        private static final class Task implements Cancellable {
            final Runnable run;
            boolean cancelled;
            Task(Runnable run) { this.run = run; }
            @Override public void cancel() { cancelled = true; }
        }

        @Override public Cancellable schedule(Runnable task, long delay, TimeUnit unit) {
            Task t = new Task(task);
            tasks.add(t);
            return t;
        }

        boolean runNext() {
            while (!tasks.isEmpty()) {
                Task t = tasks.poll();
                if (!t.cancelled) {
                    t.run.run();
                    return true;
                }
            }
            return false;
        }
    }

    private static final PartyMessages NO_MESSAGES = new PartyMessages() { };

    private final long[] now = {1_000L};
    private final ManualScheduler sched = new ManualScheduler();

    private PartyService service(Set<UUID> online) {
        return service(online, new PartyConfig(4, 30, true));
    }

    private PartyService service(Set<UUID> online, PartyConfig config) {
        Predicate<UUID> presence = online::contains;
        LongSupplier clock = () -> now[0];
        return new PartyService("g", config, NO_MESSAGES, presence, sched, clock);
    }

    private static UUID id() {
        return UUID.randomUUID();
    }

    @Test
    void getOrCreateIsStableAndOnePerPlayer() {
        UUID a = id();
        PartyService svc = service(new HashSet<>(Set.of(a)));
        Party p1 = svc.getOrCreate(a);
        Party p2 = svc.getOrCreate(a);
        assertTrue(p1 == p2, "the same player resolves to the same party");
        assertTrue(svc.isInParty(a));
        assertTrue(p1.isOwner(a));
        assertEquals(1, p1.size());
    }

    @Test
    void inviteAndAcceptAddsMember() {
        UUID owner = id();
        UUID guest = id();
        PartyService svc = service(new HashSet<>(Set.of(owner, guest)));

        assertEquals(InviteResult.SENT, svc.invite(owner, guest));
        Party party = svc.partyOf(owner);
        assertNotNull(party);
        assertEquals(AcceptResult.JOINED, svc.accept(guest, party.id()));
        assertTrue(party.contains(guest));
        assertEquals(2, party.size());
        assertTrue(svc.isInParty(guest));
        assertEquals(party, svc.partyOf(guest));
    }

    @Test
    void inviteOfflineTargetRejected() {
        UUID owner = id();
        UUID guest = id();
        PartyService svc = service(new HashSet<>(Set.of(owner))); // guest offline
        assertEquals(InviteResult.TARGET_OFFLINE, svc.invite(owner, guest));
    }

    @Test
    void inviteSelfRejected() {
        UUID owner = id();
        PartyService svc = service(new HashSet<>(Set.of(owner)));
        assertEquals(InviteResult.SELF, svc.invite(owner, owner));
    }

    @Test
    void inviteTargetAlreadyInPartyRejected() {
        UUID owner = id();
        UUID guest = id();
        UUID other = id();
        PartyService svc = service(new HashSet<>(Set.of(owner, guest, other)));
        svc.getOrCreate(other);
        svc.invite(other, guest);
        Party otherParty = svc.partyOf(other);
        assertNotNull(otherParty);
        svc.accept(guest, otherParty.id());
        // guest is now in other's party; owner cannot invite them.
        assertEquals(InviteResult.TARGET_IN_PARTY, svc.invite(owner, guest));
    }

    @Test
    void acceptAfterExpiryFails() {
        UUID owner = id();
        UUID guest = id();
        PartyService svc = service(new HashSet<>(Set.of(owner, guest)));
        svc.invite(owner, guest); // timeout 30s -> expiry at now+30000, expiry task scheduled
        Party party = svc.partyOf(owner);
        assertNotNull(party);

        now[0] = 1_000L + 40_000L; // advance past the expiry
        assertTrue(sched.runNext(), "the expiry task fires");
        assertNull(party.getInvite(guest), "the expired invite was removed");
        assertEquals(AcceptResult.NO_INVITE, svc.accept(guest, party.id()));
        assertFalse(svc.isInParty(guest));
    }

    @Test
    void leaveAsOwnerPromotesNext() {
        UUID owner = id();
        UUID guest = id();
        PartyService svc = service(new HashSet<>(Set.of(owner, guest)));
        svc.invite(owner, guest);
        Party party = svc.partyOf(owner);
        svc.accept(guest, party.id());

        assertTrue(svc.leave(owner));
        assertFalse(svc.isInParty(owner));
        assertTrue(party.isOwner(guest), "the remaining member is promoted to owner");
        assertEquals(1, party.size());
    }

    @Test
    void leaveSoloDisbands() {
        UUID owner = id();
        PartyService svc = service(new HashSet<>(Set.of(owner)));
        Party party = svc.getOrCreate(owner);
        assertTrue(svc.leave(owner));
        assertFalse(svc.isInParty(owner));
        assertNull(svc.byId(party.id()), "an emptied party is removed from the registry");
    }

    @Test
    void kickByNonOwnerFailsOwnerSucceeds() {
        UUID owner = id();
        UUID guest = id();
        PartyService svc = service(new HashSet<>(Set.of(owner, guest)));
        svc.invite(owner, guest);
        Party party = svc.partyOf(owner);
        svc.accept(guest, party.id());

        assertFalse(svc.kick(guest, owner), "a non-owner cannot kick");
        assertTrue(svc.kick(owner, guest), "the owner can kick");
        assertFalse(svc.isInParty(guest));
        assertEquals(1, party.size());
    }

    @Test
    void transferOwnerHandsOwnership() {
        UUID owner = id();
        UUID guest = id();
        PartyService svc = service(new HashSet<>(Set.of(owner, guest)));
        svc.invite(owner, guest);
        Party party = svc.partyOf(owner);
        svc.accept(guest, party.id());

        assertTrue(svc.transferOwner(owner, guest));
        assertTrue(party.isOwner(guest));
        assertFalse(svc.transferOwner(owner, guest), "the old owner can no longer transfer");
    }

    @Test
    void defaultPartyIsPublic() {
        UUID owner = id();
        PartyService svc = service(new HashSet<>(Set.of(owner)));
        Party party = svc.getOrCreate(owner);
        assertFalse(party.isPrivate(), "a new party defaults to public");
        assertFalse(party.snapshot().privateLobby());
    }

    @Test
    void ownerTogglesPrivacyMemberCannot() {
        UUID owner = id();
        UUID guest = id();
        PartyService svc = service(new HashSet<>(Set.of(owner, guest)));
        svc.invite(owner, guest);
        Party party = svc.partyOf(owner);
        svc.accept(guest, party.id());

        assertFalse(svc.setPrivate(guest, true), "a non-owner cannot change privacy");
        assertFalse(party.isPrivate());

        assertTrue(svc.setPrivate(owner, true), "the owner can set the party private");
        assertTrue(party.isPrivate());
        assertTrue(party.snapshot().privateLobby(), "the snapshot carries the private scope for the queue key");

        assertTrue(svc.setPrivate(owner, false), "the owner can set it public again");
        assertFalse(party.isPrivate());
    }

    @Test
    void setPrivateWithNoPartyIsNoOp() {
        UUID owner = id();
        PartyService svc = service(new HashSet<>(Set.of(owner)));
        assertFalse(svc.setPrivate(owner, true), "no party -> no-op, not a crash");
    }

    @Test
    void disbandRemovesEveryone() {
        UUID owner = id();
        UUID guest = id();
        PartyService svc = service(new HashSet<>(Set.of(owner, guest)));
        svc.invite(owner, guest);
        Party party = svc.partyOf(owner);
        svc.accept(guest, party.id());

        assertFalse(svc.disband(guest), "a non-owner cannot disband");
        assertTrue(svc.disband(owner));
        assertFalse(svc.isInParty(owner));
        assertFalse(svc.isInParty(guest));
        assertNull(svc.byId(party.id()));
    }

    @Test
    void listenerObservesMemberJoin() {
        UUID owner = id();
        UUID guest = id();
        PartyService svc = service(new HashSet<>(Set.of(owner, guest)));
        PartySnapshot[] last = {null};
        svc.addListener(s -> last[0] = s);

        svc.invite(owner, guest);
        Party party = svc.partyOf(owner);
        svc.accept(guest, party.id());

        assertNotNull(last[0]);
        assertEquals(party.id(), last[0].id());
        assertTrue(last[0].members().contains(guest));
    }
}
