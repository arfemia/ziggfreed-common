package com.ziggfreed.common.instance.dungeon.tier;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.counter.Counters;
import com.ziggfreed.common.factor.CounterFactors;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorContributions;
import com.ziggfreed.common.factor.FactorProvider;
import com.ziggfreed.common.instance.dungeon.DungeonAsset;
import com.ziggfreed.common.instance.dungeon.DungeonConfig;
import com.ziggfreed.common.instance.dungeon.DungeonRuns;
import com.ziggfreed.common.party.Party;
import com.ziggfreed.common.party.ZcParty;

/**
 * A dungeon's tiers as the running server reads them ({@link TierLadder} is the pure rule).
 *
 * <p><b>The ladder</b> of a dungeon is every enabled dungeon file that reaches the same lowest rung by following
 * {@code Tier.Opens} down, plus each daily ({@code Seed.Daily}) naming one of those as its {@code Parent} (the lowest
 * tier's daily, which names no {@code Opens}); any other child file, a hard mode with no {@code Tier}, stays a ladder
 * of its own. A dungeon with no tiers and no daily is a ladder of one.
 *
 * <p><b>Who has opened what</b> is read where clears are already recorded: the library's {@code Dungeon_Runs_Completed}
 * line for the dungeon a rung's {@code Opens} names ({@code dungeon:<difficulty>@<id>}), through the
 * {@code ziggfreedcommon:counter} reading, as any {@code Requires} block reads it. No answer (the stats store switched
 * off, a player with no record) opens nothing past the rungs open to everyone. A rung is open to a party only when every
 * member standing in the asker's world, the asker included, has cleared that rung's own {@code Opens}: a member's
 * record is read on their own world's thread, so a member elsewhere is asked when they come to a gate themselves (lead's
 * call). All of it on the asker's world thread.
 */
public final class DungeonTiers {

    /** The library's stat whose per-dungeon lines count a player's clears (zc-stats' shipped file). */
    static final String CLEARS_STAT = "Dungeon_Runs_Completed";

    /** How far down {@code Tier.Opens} is followed before a ladder is taken as looping. */
    private static final int MAX_DEPTH = 32;

    private DungeonTiers() {
    }

    /** The ladder {@code dungeonId} stands on, in {@link TierLadder#ORDER}; empty for a dungeon no loaded file defines. */
    @Nonnull
    public static List<TierLadder.Rung> ladderOf(@Nonnull String dungeonId) {
        Map<String, DungeonAsset> all = DungeonConfig.getInstance().all();
        String named = fold(dungeonId);
        if (!all.containsKey(named)) {
            return List.of();
        }
        String root = rootOf(named, all);
        Map<String, DungeonAsset> members = new LinkedHashMap<>();
        for (Map.Entry<String, DungeonAsset> entry : all.entrySet()) {
            DungeonAsset dungeon = entry.getValue();
            if (dungeon != null && dungeon.isEnabled() && root.equals(rootOf(entry.getKey(), all))) {
                members.put(entry.getKey(), dungeon);
            }
        }
        for (Map.Entry<String, DungeonAsset> entry : all.entrySet()) {
            DungeonAsset dungeon = entry.getValue();
            String parent = dungeon == null || !dungeon.seedDaily() ? null : dungeon.parentId();
            if (parent != null && dungeon.isEnabled() && members.containsKey(fold(parent))) {
                members.putIfAbsent(entry.getKey(), dungeon);
            }
        }
        List<TierLadder.Rung> rungs = new ArrayList<>(members.size());
        for (Map.Entry<String, DungeonAsset> entry : members.entrySet()) {
            rungs.add(rung(entry.getKey(), entry.getValue()));
        }
        rungs.sort(TierLadder.ORDER);
        return List.copyOf(rungs);
    }

    /** {@code dungeon} as a rung, filed under {@code id} (the fold's lower-cased id when it carries none). */
    @Nonnull
    public static TierLadder.Rung rung(@Nonnull String id, @Nonnull DungeonAsset dungeon) {
        String own = dungeon.getId() == null || dungeon.getId().isBlank() ? id : dungeon.getId().trim();
        return new TierLadder.Rung(own, dungeon.tierRank(), dungeon.tierOpens(), dungeon.seedDaily());
    }

    /**
     * The clears of each member of {@code player}'s party standing in this world, the player first (at {@code ref} in
     * {@code store}): one answer per member, asked of a dungeon id, as {@link TierLadder} reads them. A member in
     * another world is not asked (lead's call). On that world's thread.
     */
    @Nonnull
    public static List<Predicate<String>> party(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull UUID player) {
        List<Predicate<String>> members = new ArrayList<>();
        members.add(opens -> cleared(store, ref, opens));
        Party party = ZcParty.service().partyOf(player);
        Universe universe = Universe.get();
        if (party != null && universe != null) {
            for (UUID member : party.orderedMembers()) {
                if (member.equals(player)) {
                    continue;
                }
                PlayerRef other = universe.getPlayer(member);
                Ref<EntityStore> otherRef = other == null ? null : other.getReference();
                if (otherRef != null && otherRef.isValid() && otherRef.getStore() == store) {
                    members.add(opens -> cleared(store, otherRef, opens));
                }
            }
        }
        return members;
    }

    /**
     * The dungeon the party of {@code player} must clear before it may go into {@code dungeon}: its own
     * {@code Tier.Opens}, when some member standing in this world has not cleared it; null when it names none or every
     * one of them has. On the player's world thread.
     */
    @Nullable
    public static String lockedBehind(@Nonnull DungeonAsset dungeon, @Nonnull Ref<EntityStore> ref,
            @Nonnull Store<EntityStore> store, @Nonnull UUID player) {
        String opens = dungeon.tierOpens();
        if (opens == null) {
            return null;
        }
        for (Predicate<String> member : party(ref, store, player)) {
            if (!member.test(opens)) {
                return opens;
            }
        }
        return null;
    }

    /**
     * Has the player at {@code ref} cleared {@code dungeonId} at least once: its {@code Dungeon_Runs_Completed} line
     * ({@code dungeon:<difficulty>@<id>}, the round contract's), read as {@code ziggfreedcommon:counter} reads it.
     * False when nothing can answer.
     */
    static boolean cleared(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull String dungeonId) {
        FactorProvider counter = FactorContributions.provider(CounterFactors.COUNTER);
        if (counter == null) {
            return false;
        }
        Double count = counter.resolve(FactorContext.about(store, ref).withParam(clearsKey(dungeonId)));
        return count != null && count >= 1.0;
    }

    /** The counter key of {@code dungeonId}'s clears: {@code Dungeon_Runs_Completed/dungeon:<difficulty>@<id>}. */
    @Nonnull
    static String clearsKey(@Nonnull String dungeonId) {
        DungeonAsset dungeon = DungeonConfig.getInstance().resolve(dungeonId.trim());
        String id = dungeon == null || dungeon.getId() == null ? dungeonId.trim() : dungeon.getId().trim();
        String difficulty = dungeon == null ? "" : dungeon.difficulty();
        return Counters.key(CLEARS_STAT, DungeonRuns.MOD_ID + ":" + difficulty + "@" + id);
    }

    /** The lowest rung {@code id} reaches by following {@code Tier.Opens} down, folded; itself when it names none. */
    @Nonnull
    private static String rootOf(@Nonnull String id, @Nonnull Map<String, DungeonAsset> all) {
        String at = fold(id);
        Set<String> seen = new HashSet<>();
        for (int depth = 0; depth < MAX_DEPTH && seen.add(at); depth++) {
            DungeonAsset dungeon = all.get(at);
            String opens = dungeon == null ? null : dungeon.tierOpens();
            if (opens == null || !all.containsKey(fold(opens))) {
                return at;
            }
            at = fold(opens);
        }
        return at;
    }

    @Nonnull
    private static String fold(@Nonnull String id) {
        return id.trim().toLowerCase(Locale.ROOT);
    }
}
