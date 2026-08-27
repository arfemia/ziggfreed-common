package com.ziggfreed.common.subject;

import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.ziggfreed.common.inventory.PlayerAccess;

/**
 * Who a payout is for when the call site holds nothing but a reference to them - a shop purchase,
 * a dialogue gift, an interaction grant, an admin command settling a quest on somebody else.
 *
 * <p>It resolves the live entity from that reference ON DEMAND rather than being handed one, so a
 * caller that only ever had a {@link PlayerRef} keeps the shape it already had and an item reward
 * still reaches a backpack. That is the whole reason it lives beside {@link Subject} rather than
 * in a payout package: it is an identity, not a payout.
 *
 * @param ref the player this payout is for, or null when nobody is here to receive it
 */
public record PlayerRefSubjectHandle(@Nullable PlayerRef ref) implements Subject.HandleFacets {

    /** The id a payout with no online player is filed under, since nothing keys state by it. */
    private static final UUID ANONYMOUS = new UUID(0L, 0L);

    /**
     * The subject a payout for {@code ref} is granted to. {@code username} names them for a log
     * line, a command placeholder and the retry queue, so it is passed in rather than read back off
     * a reference that may not be there.
     */
    @Nonnull
    public static Subject subjectFor(@Nullable PlayerRef ref, @Nonnull String username) {
        UUID id = ref != null ? ref.getUuid() : null;
        return new Subject(id != null ? id : ANONYMOUS, username, new PlayerRefSubjectHandle(ref));
    }

    /**
     * What this handle can answer for besides itself: the live player and the reference to them.
     *
     * <p>The library's ready-made reward handlers ask the SUBJECT for a player, not for any
     * particular handle type, so without this a purchase or a gift would look player-less to every
     * one of them and an item reward would refuse to pay out on a subject that plainly has one.
     */
    @Override
    @Nullable
    public Object facet(@Nonnull Class<?> type) {
        if (type == PlayerRef.class) {
            return ref;
        }
        if (type == Player.class) {
            return PlayerAccess.player(ref);
        }
        return null;
    }
}
