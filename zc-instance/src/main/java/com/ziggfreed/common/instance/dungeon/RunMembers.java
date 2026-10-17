package com.ziggfreed.common.instance.dungeon;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;

/**
 * Who one dungeon run counts and who is in it now: the one member tracker a walk-in dungeon's run
 * ({@link DungeonRuns}) and a portal dungeon's ({@code portal/PortalDungeonRuns}) keep under the round contract.
 *
 * <p>A player is counted once, by the start that names them (the run's first start, or a late arrival's own), and
 * stays counted however often they leave and come back, so a run's end names as participants every player a start
 * named, in the order they were counted. Who is present changes as players arrive and leave; a clear's winners are
 * the players present at that moment, each of them counted.
 *
 * <p>Not thread-safe: the run list that owns the run guards it, under that list's own lock.
 */
public final class RunMembers {

    /** Every player a start named, in the order they were counted: each is named at the end too. */
    private final Set<UUID> participants = new LinkedHashSet<>();

    /** Every player in the run's place now, in the order they came in. */
    private final Set<UUID> present = new LinkedHashSet<>();

    /**
     * {@code player} is in the run's place now. True when this counts them for the first time, so a start of their
     * own names them; false for a player counted before (named on the way in, or back after leaving).
     */
    public boolean arrive(@Nonnull UUID player) {
        present.add(player);
        return participants.add(player);
    }

    /** Count {@code player} without placing them in the run. True when they were not counted yet. */
    public boolean count(@Nonnull UUID player) {
        return participants.add(player);
    }

    /** Count each of {@code players}, in order, without placing them in the run (those a first start names). */
    public void countAll(@Nonnull Collection<UUID> players) {
        participants.addAll(players);
    }

    /** {@code player} left the run's place; they stay counted. */
    public void leave(@Nonnull UUID player) {
        present.remove(player);
    }

    /** Sweep out of the present every player {@code stillThere} does not hold. */
    public void keepPresent(@Nonnull Collection<UUID> stillThere) {
        present.retainAll(stillThere);
    }

    /** The players present are exactly {@code players} now, in their order (each already counted). */
    public void presentExactly(@Nonnull Collection<UUID> players) {
        present.clear();
        present.addAll(players);
    }

    /** Nobody is present any more: the run's place is free. */
    public void clearPresent() {
        present.clear();
    }

    /** Whether anyone is present. */
    public boolean anyPresent() {
        return !present.isEmpty();
    }

    /** How many players are counted: a start's player count, the run's as it stands. */
    public int participantCount() {
        return participants.size();
    }

    /** Every player counted, in order: the end's participants. */
    @Nonnull
    public List<UUID> participants() {
        return List.copyOf(participants);
    }

    /** Every player present, in the order they came in. */
    @Nonnull
    public List<UUID> present() {
        return List.copyOf(present);
    }

    /** The end's winners: every player present at a clear, nobody at a failure. */
    @Nonnull
    public List<UUID> winners(boolean cleared) {
        return cleared ? present() : List.of();
    }
}
