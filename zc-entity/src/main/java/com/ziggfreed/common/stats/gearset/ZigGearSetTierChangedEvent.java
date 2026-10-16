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
 * listener keeping other state reads the ids and {@link #tierSize()} (zc-objectives' gear-set
 * producer counts a tier coming on as progress that way). Both get the live {@link PlayerRef}, so neither looks
 * the player up again on a thread it may not be on.
 */
public final class ZigGearSetTierChangedEvent implements IEvent<Void> {

    private final UUID playerId;
    private final PlayerRef playerRef;
    private final String setId;
    private final int tierIndex;
    private final int tierSize;
    private final boolean active;
    private final int pieces;
    private final int members;
    private final int fullSet;
    @Nullable private final String setTitleKey;
    private final List<String> tierTitleKeys;
    private final List<String> nextTierTitleKeys;

    /**
     * The 2.3.0 form, which carries no tier size: {@link #tierSize()} reads 0 on an event built with it.
     *
     * @deprecated use the constructor taking the tier's size after its position.
     */
    @Deprecated(since = "2.4.0")
    public ZigGearSetTierChangedEvent(@Nonnull UUID playerId, @Nonnull PlayerRef playerRef, @Nonnull String setId,
            int tierIndex, boolean active, int pieces, int members, int fullSet, @Nullable String setTitleKey,
            @Nonnull List<String> tierTitleKeys, @Nonnull List<String> nextTierTitleKeys) {
        this(playerId, playerRef, setId, tierIndex, 0, active, pieces, members, fullSet, setTitleKey, tierTitleKeys,
                nextTierTitleKeys);
    }

    public ZigGearSetTierChangedEvent(@Nonnull UUID playerId, @Nonnull PlayerRef playerRef, @Nonnull String setId,
            int tierIndex, int tierSize, boolean active, int pieces, int members, int fullSet,
            @Nullable String setTitleKey, @Nonnull List<String> tierTitleKeys, @Nonnull List<String> nextTierTitleKeys) {
        this.playerId = playerId;
        this.playerRef = playerRef;
        this.setId = setId;
        this.tierIndex = tierIndex;
        this.tierSize = tierSize;
        this.active = active;
        this.pieces = pieces;
        this.members = members;
        this.fullSet = fullSet;
        this.setTitleKey = setTitleKey;
        this.tierTitleKeys = List.copyOf(tierTitleKeys);
        this.nextTierTitleKeys = List.copyOf(nextTierTitleKeys);
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

    /**
     * How many pieces the tier takes, the larger of its {@code Pieces} and its {@code Armor + Held +
     * Utility}: the count its notice shows while it holds ("4/5" for a four-piece tier of a five-piece
     * set). 0 for a tier with no count, or an event built through the deprecated constructor.
     */
    public int tierSize() {
        return tierSize;
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
