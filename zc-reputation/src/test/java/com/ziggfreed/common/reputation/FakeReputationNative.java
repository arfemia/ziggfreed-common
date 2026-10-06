package com.ziggfreed.common.reputation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The engine as the service sees it, with no server: one player, a case-EXACT group map (as the engine's
 * own asset map is), the engine's clamp to [lowest MinValue, highest MaxValue - 1], and every write kept
 * in order so a test can say what reached the engine.
 */
public final class FakeReputationNative implements ReputationNative {

    public boolean available = true;
    public boolean live = true;
    public final UUID player = UUID.fromString("00000000-0000-0000-0000-00000000a001");
    public final List<ReputationLadder.Rank> ranks = new ArrayList<>(ReputationFixtures.LADDER);
    public final Map<String, Group> groups = new LinkedHashMap<>();
    public final Map<String, Integer> stored = new HashMap<>();
    public final Map<String, Long> stats = new HashMap<>();
    public final Map<String, Set<String>> members = new HashMap<>();
    public final List<String> writes = new ArrayList<>();

    /** Add a native group with no members, starting at {@code initial}. */
    @Nonnull
    public FakeReputationNative group(@Nonnull String id, int initial) {
        groups.put(id, new Group(id, initial, List.of()));
        return this;
    }

    @Override
    public boolean available() {
        return available;
    }

    @Nonnull
    @Override
    public List<ReputationLadder.Rank> ranks() {
        return available ? List.copyOf(ranks) : List.of();
    }

    @Nonnull
    @Override
    public List<Group> groups() {
        return available ? List.copyOf(groups.values()) : List.of();
    }

    @Override
    public boolean live(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref) {
        return live;
    }

    @Nullable
    @Override
    public UUID playerId(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref) {
        return live ? player : null;
    }

    @Nullable
    @Override
    public Integer earned(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref, @Nonnull String groupId) {
        Group group = groups.get(groupId);
        return !live || group == null ? null : stored.getOrDefault(groupId, group.initial());
    }

    @Override
    public boolean hasEntry(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref, @Nonnull String groupId) {
        return live && stored.containsKey(groupId);
    }

    @Nullable
    @Override
    public Integer add(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref, @Nonnull String groupId,
            int delta) {
        Group group = groups.get(groupId);
        if (!live || group == null) {
            return null;
        }
        writes.add(groupId + " " + delta);
        long next = (long) stored.getOrDefault(groupId, group.initial()) + delta;
        int floor = ranks.stream().mapToInt(ReputationLadder.Rank::min).min().orElse(Integer.MIN_VALUE);
        int ceiling = ranks.stream().mapToInt(ReputationLadder.Rank::max).max().orElse(Integer.MAX_VALUE);
        int after = (int) Math.max(floor, Math.min(ceiling - 1L, next));
        stored.put(groupId, after);
        return after;
    }

    @Override
    public long statMax(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref, @Nullable String statId) {
        return statId == null ? 0L : stats.getOrDefault(statId, 0L);
    }

    @Override
    public boolean roleInGroup(@Nonnull String roleName, @Nonnull String npcGroupId) {
        return members.getOrDefault(npcGroupId, Set.of()).contains(roleName);
    }

    @Override
    public boolean npcGroupExists(@Nonnull String npcGroupId) {
        return members.containsKey(npcGroupId);
    }
}
