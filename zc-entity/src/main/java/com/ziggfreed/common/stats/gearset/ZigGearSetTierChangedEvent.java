package com.ziggfreed.common.stats.gearset;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.event.IEvent;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.i18n.ContentKeys;
import com.ziggfreed.common.i18n.Msg;

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
 * {@link #pieces()} of {@link #members()}; a listener keeping other state reads the ids. Both get
 * the live {@link PlayerRef}, so neither looks the player up again on a thread it may not be on.
 */
public final class ZigGearSetTierChangedEvent implements IEvent<Void> {

    private final UUID playerId;
    private final PlayerRef playerRef;
    private final String setId;
    private final int tierIndex;
    private final boolean active;
    private final int pieces;
    private final int members;
    @Nullable private final String setTitleKey;
    @Nullable private final String tierTitleKey;

    public ZigGearSetTierChangedEvent(@Nonnull UUID playerId, @Nonnull PlayerRef playerRef, @Nonnull String setId,
            int tierIndex, boolean active, int pieces, int members, @Nullable String setTitleKey,
            @Nullable String tierTitleKey) {
        this.playerId = playerId;
        this.playerRef = playerRef;
        this.setId = setId;
        this.tierIndex = tierIndex;
        this.active = active;
        this.pieces = pieces;
        this.members = members;
        this.setTitleKey = setTitleKey;
        this.tierTitleKey = tierTitleKey;
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

    /** How many distinct members the player has on now, anywhere. */
    public int pieces() {
        return pieces;
    }

    /** How many members the set has in all. */
    public int members() {
        return members;
    }

    /** The set's authored title key, or null when the file names none. */
    @Nullable
    public String setTitleKey() {
        return setTitleKey;
    }

    /** The tier's authored line key, or null when the tier carries none. */
    @Nullable
    public String tierTitleKey() {
        return tierTitleKey;
    }

    /** The set's name as the reader's client resolves it; the id as plain text when it has no key. */
    @Nonnull
    public Message setName() {
        return setTitleKey == null ? Msg.raw(setId) : ContentKeys.tr(setTitleKey);
    }

    /** The tier's one line as the reader's client resolves it, or null when the tier carries no key. */
    @Nullable
    public Message tierLine() {
        return tierTitleKey == null ? null : ContentKeys.tr(tierTitleKey);
    }
}
