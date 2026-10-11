package com.ziggfreed.common.stats.gearset;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.event.IEvent;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * One tier of one gear set came on or went off for a player: the recompute after an equip change
 * found the tier's minimums newly met, or newly unmet.
 *
 * <p>Synchronous {@code IEvent<Void>} POJO on the shared engine event bus; see {@link GearSetEvents}
 * for the fire contract. It fires ONLY on a real flip: never on the first recompute after login
 * (a hydrate, where the modifiers were already there), never on a recompute that found the same
 * tiers active as last time, and once per tier that changed, deactivations before activations.
 *
 * <p>A listener drawing a notice reads {@link #setName()} and {@link #tierLine()} (the authored keys
 * resolved through the loaded lang catalogue, the id as plain text when the set names none) with
 * {@link #pieces()} of {@link #fullSet()}, and {@link #nextTierLine()} for the tier still to earn; a
 * listener keeping other state reads the ids. Both get the live {@link PlayerRef}, so neither looks
 * the player up again on a thread it may not be on.
 */
public final class ZigGearSetTierChangedEvent implements IEvent<Void> {

    private final UUID playerId;
    private final PlayerRef playerRef;
    private final String setId;
    private final int tierIndex;
    private final boolean active;
    private final int pieces;
    private final int members;
    private final int fullSet;
    @Nullable private final String setTitleKey;
    private final List<String> tierTitleKeys;
    private final List<String> nextTierTitleKeys;

    public ZigGearSetTierChangedEvent(@Nonnull UUID playerId, @Nonnull PlayerRef playerRef, @Nonnull String setId,
            int tierIndex, boolean active, int pieces, int members, int fullSet, @Nullable String setTitleKey,
            @Nonnull List<String> tierTitleKeys, @Nonnull List<String> nextTierTitleKeys) {
        this.playerId = playerId;
        this.playerRef = playerRef;
        this.setId = setId;
        this.tierIndex = tierIndex;
        this.active = active;
        this.pieces = pieces;
        this.members = members;
        this.fullSet = fullSet;
        this.setTitleKey = setTitleKey;
        this.tierTitleKeys = List.copyOf(tierTitleKeys);
        this.nextTierTitleKeys = List.copyOf(nextTierTitleKeys);
    }

    /**
     * The 2.2.0 form, kept so a caller built against it still links. It carries what 2.2.0 knew:
     * {@link #fullSet()} answers {@code members}, the count a 2.2.0 notice showed, the tier's one
     * line key is its whole list, and there is no next tier to name.
     *
     * @deprecated build the event with
     *     {@link #ZigGearSetTierChangedEvent(UUID, PlayerRef, String, int, boolean, int, int, int, String, List, List)},
     *     which carries the full set's count, every line key of the tier and the next tier's
     */
    @Deprecated(since = "2.3.0")
    public ZigGearSetTierChangedEvent(@Nonnull UUID playerId, @Nonnull PlayerRef playerRef, @Nonnull String setId,
            int tierIndex, boolean active, int pieces, int members, @Nullable String setTitleKey,
            @Nullable String tierTitleKey) {
        this(playerId, playerRef, setId, tierIndex, active, pieces, members, members, setTitleKey,
                tierTitleKey == null ? List.of() : List.of(tierTitleKey), List.of());
    }

    @Nonnull
    public UUID playerId() {
        return playerId;
    }

    /** The player's live reference; every fire resolves one before it builds the event. */
    @Nonnull
    public PlayerRef playerRef() {
        return playerRef;
    }

    /** The set id as folded: lower-cased. */
    @Nonnull
    public String setId() {
        return setId;
    }

    /** The tier's position in the set's {@code Bonuses} list. */
    public int tierIndex() {
        return tierIndex;
    }

    /** True when the tier just came on, false when it just went off. */
    public boolean active() {
        return active;
    }

    /**
     * How far the player stands toward the full set now ({@link GearSetDecision#progress}: the pieces
     * that count in the slots the set's tiers ask for), at most {@link #fullSet()}
     * ({@link GearSetDecision#shownPieces}), so a member in the wrong slot or a spare one never counts.
     */
    public int pieces() {
        return pieces;
    }

    /**
     * How many members the set lists in all, alternatives for one slot included. A notice counts
     * against {@link #fullSet()} instead.
     */
    public int members() {
        return members;
    }

    /**
     * How many pieces the full set takes ({@link GearSetDecision#fullCount}): what a notice counts
     * {@link #pieces()} against.
     */
    public int fullSet() {
        return fullSet;
    }

    /** The set's authored title key, or null when the file names none. */
    @Nullable
    public String setTitleKey() {
        return setTitleKey;
    }

    /** The tier's first line key (its own, when it authors one), or null when the tier carries none. */
    @Nullable
    public String tierTitleKey() {
        return tierTitleKeys.isEmpty() ? null : tierTitleKeys.get(0);
    }

    /**
     * Every line key the tier carries ({@link GearSetAsset.Tier#titleKeys()}): its own, then each
     * joined layer's; empty when it carries none.
     */
    @Nonnull
    public List<String> tierTitleKeys() {
        return tierTitleKeys;
    }

    /**
     * The first line key of the tier still to earn ({@link GearSetDecision#nextTier} over what the
     * player has on now), or null when every tier holds or that tier carries no line.
     */
    @Nullable
    public String nextTierTitleKey() {
        return nextTierTitleKeys.isEmpty() ? null : nextTierTitleKeys.get(0);
    }

    /** Every line key of the tier still to earn, as {@link #tierTitleKeys()}; empty when there is none. */
    @Nonnull
    public List<String> nextTierTitleKeys() {
        return nextTierTitleKeys;
    }

    /** The set's name as the reader's client resolves it; the id as plain text when it has no key. */
    @Nonnull
    public Message setName() {
        return GearSetEvents.setName(setTitleKey, setId);
    }

    /**
     * The tier's line as the reader's client resolves it ({@link GearSetEvents#lines}: a joined tier's
     * lines one after another), or null when the tier carries no key.
     */
    @Nullable
    public Message tierLine() {
        return GearSetEvents.lines(tierTitleKeys);
    }

    /** The next tier's line as the reader's client resolves it, or null when there is none to name. */
    @Nullable
    public Message nextTierLine() {
        return GearSetEvents.lines(nextTierTitleKeys);
    }

    /** Whether there is a next tier to name ({@link #nextTierLine()} is not null). */
    public boolean hasNextTier() {
        return !nextTierTitleKeys.isEmpty();
    }
}
