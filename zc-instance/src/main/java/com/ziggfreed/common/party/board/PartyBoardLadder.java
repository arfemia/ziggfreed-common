package com.ziggfreed.common.party.board;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.instance.dungeon.DungeonAsset;
import com.ziggfreed.common.instance.dungeon.DungeonConfig;
import com.ziggfreed.common.instance.dungeon.tier.DungeonTiers;
import com.ziggfreed.common.instance.dungeon.tier.TierLadder;

/**
 * A board's dungeon as one player reads it: the ladder of its depths and daily ({@link DungeonTiers#ladderOf}) and what
 * that player alone has opened (their own clears, as the gate reads them), so the post form offers only what they have
 * opened and a notice for a depth they have not is greyed with what opens it. Read on the player's world thread.
 */
final class PartyBoardLadder {

    /** The ladder of a board kept for no dungeon: nothing to pick but anything. */
    static final PartyBoardLadder NONE = new PartyBoardLadder(List.of(), opens -> false);

    private final List<TierLadder.Rung> ladder;
    private final Predicate<String> cleared;

    private PartyBoardLadder(@Nonnull List<TierLadder.Rung> ladder, @Nonnull Predicate<String> cleared) {
        this.ladder = ladder;
        this.cleared = cleared;
    }

    /** {@code dungeon}'s ladder as the player at {@code ref} has opened it; {@link #NONE} for no dungeon. */
    @Nonnull
    static PartyBoardLadder read(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull UUID player, @Nullable String dungeon) {
        if (dungeon == null || dungeon.isBlank()) {
            return NONE;
        }
        List<TierLadder.Rung> rungs = DungeonTiers.ladderOf(dungeon);
        // The party reading lists the player first: their own clears alone decide what they may post for or ask to.
        List<Predicate<String>> members = DungeonTiers.party(ref, store, player);
        return new PartyBoardLadder(rungs, members.isEmpty() ? opens -> false : members.get(0));
    }

    /**
     * The dungeon whose clear opens {@code aim} when it is a rung of this ladder the player has not opened, else null
     * (anything, a rung open to them, or a dungeon off this ladder).
     */
    @Nullable
    String lockedBehind(@Nullable String aim) {
        TierLadder.Rung rung = rungOf(aim);
        return rung == null || TierLadder.open(rung, cleared) ? null : rung.opens();
    }

    /** Is {@code id} a rung of this ladder? */
    boolean holds(@Nullable String id) {
        return rungOf(id) != null;
    }

    /** The rungs the player may post for: open to them and open now, the depths in order, then the dailies. */
    @Nonnull
    List<DungeonAsset> postable() {
        List<DungeonAsset> depths = new ArrayList<>();
        List<DungeonAsset> dailies = new ArrayList<>();
        for (TierLadder.Rung rung : ladder) {
            if (!TierLadder.open(rung, cleared)) {
                continue;
            }
            DungeonAsset dungeon = DungeonConfig.getInstance().resolve(rung.id());
            if (dungeon != null && dungeon.isOpen()) {
                (rung.daily() ? dailies : depths).add(dungeon);
            }
        }
        depths.addAll(dailies);
        return depths;
    }

    @Nullable
    private TierLadder.Rung rungOf(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        for (TierLadder.Rung rung : ladder) {
            if (rung.id().equalsIgnoreCase(id.trim())) {
                return rung;
            }
        }
        return null;
    }
}
