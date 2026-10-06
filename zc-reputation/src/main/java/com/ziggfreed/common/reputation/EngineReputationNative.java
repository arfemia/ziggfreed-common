package com.ziggfreed.common.reputation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.builtin.adventure.reputation.ReputationPlugin;
import com.hypixel.hytale.builtin.adventure.reputation.assets.ReputationGroup;
import com.hypixel.hytale.builtin.adventure.reputation.assets.ReputationRank;
import com.hypixel.hytale.builtin.tagset.TagSetPlugin;
import com.hypixel.hytale.builtin.tagset.config.NPCGroup;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.ziggfreed.common.stats.StatIndexCache;
import com.ziggfreed.common.util.SafeLog;

import it.unimi.dsi.fastutil.ints.IntSet;

/**
 * The production {@link ReputationNative}: the engine's {@code ReputationPlugin} and its two asset maps,
 * the player's own stat map, and the TagSet lookup the engine itself uses to put an NPC in a group. When
 * the engine's reputation plugin is switched off ({@code ReputationPlugin.get()} is null) every answer is
 * empty or null, so zc fails closed. A membership lookup that throws (the TagSet plugin off, say) answers
 * false and says so once.
 */
public final class EngineReputationNative implements ReputationNative {

    private static final AtomicBoolean WARNED_MEMBERSHIP = new AtomicBoolean();

    @Override
    public boolean available() {
        return ReputationPlugin.get() != null;
    }

    @Nonnull
    @Override
    public List<ReputationLadder.Rank> ranks() {
        List<ReputationLadder.Rank> out = new ArrayList<>();
        if (!available()) {
            return out;
        }
        for (ReputationRank rank : ReputationRank.getAssetMap().getAssetMap().values()) {
            if (rank != null && rank.getId() != null) {
                out.add(new ReputationLadder.Rank(rank.getId(), rank.getMinValue(), rank.getMaxValue()));
            }
        }
        return out;
    }

    @Nonnull
    @Override
    public List<Group> groups() {
        List<Group> out = new ArrayList<>();
        if (!available()) {
            return out;
        }
        for (ReputationGroup group : ReputationGroup.getAssetMap().getAssetMap().values()) {
            if (group == null || group.getId() == null) {
                continue;
            }
            String[] members = group.getNpcGroups();
            out.add(new Group(group.getId(), group.getInitialReputationValue(), members == null ? null : named(members)));
        }
        return out;
    }

    @Override
    public boolean live(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref) {
        return store != null && ref != null && ref.isValid()
                && store.getComponent(ref, Player.getComponentType()) != null;
    }

    @Nullable
    @Override
    public UUID playerId(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref) {
        if (store == null || ref == null) {
            return null;
        }
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        return playerRef == null ? null : playerRef.getUuid();
    }

    @Nullable
    @Override
    public Integer earned(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref, @Nonnull String groupId) {
        ReputationPlugin plugin = ReputationPlugin.get();
        if (plugin == null || store == null || ref == null) {
            return null;
        }
        int value = plugin.getReputationValue(store, ref, groupId);
        return value == ReputationPlugin.NO_REPUTATION_GROUP ? null : value;
    }

    @Override
    public boolean hasEntry(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref, @Nonnull String groupId) {
        if (store == null || ref == null) {
            return false;
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        return player != null && player.getPlayerConfigData().getReputationData().containsKey(groupId);
    }

    @Nullable
    @Override
    public Integer add(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref, @Nonnull String groupId,
            int delta) {
        ReputationPlugin plugin = ReputationPlugin.get();
        if (plugin == null || store == null || ref == null) {
            return null;
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            return null;
        }
        int after = plugin.changeReputation(player, groupId, delta, store);
        return after == ReputationPlugin.NO_REPUTATION_GROUP ? null : after;
    }

    @Override
    public long statMax(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref, @Nullable String statId) {
        if (store == null || ref == null || statId == null || statId.isBlank()) {
            return 0L;
        }
        int index = StatIndexCache.resolve(statId.trim());
        if (index == StatIndexCache.UNRESOLVED) {
            return 0L;
        }
        EntityStatMap stats = store.getComponent(ref, EntityStatsModule.get().getEntityStatMapComponentType());
        EntityStatValue value = stats == null ? null : stats.get(index);
        return value == null ? 0L : Math.round(value.getMax());
    }

    @Override
    public boolean roleInGroup(@Nonnull String roleName, @Nonnull String npcGroupId) {
        try {
            NPCPlugin npcs = NPCPlugin.get();
            if (npcs == null) {
                return false;
            }
            int role = npcs.getIndex(roleName);
            int group = NPCGroup.getAssetMap().getIndex(npcGroupId);
            if (role < 0 || group == AssetMapWithIndexes.NOT_FOUND) {
                return false;
            }
            IntSet members = TagSetPlugin.get(NPCGroup.class).getSet(group);
            return members != null && members.contains(role);
        } catch (Throwable t) {
            if (WARNED_MEMBERSHIP.compareAndSet(false, true)) {
                SafeLog.warn("[reputation] NPC group membership could not be read, so kills move no standing: "
                        + t.getMessage());
            }
            return false;
        }
    }

    @Override
    public boolean npcGroupExists(@Nonnull String npcGroupId) {
        try {
            return NPCGroup.getAssetMap().getIndex(npcGroupId) != AssetMapWithIndexes.NOT_FOUND;
        } catch (Throwable t) {
            return false;
        }
    }

    @Nonnull
    private static List<String> named(@Nonnull String[] ids) {
        List<String> out = new ArrayList<>();
        for (String id : ids) {
            if (id != null) {
                out.add(id);
            }
        }
        return List.copyOf(out);
    }
}
