package com.ziggfreed.common.reputation;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * Every question zc asks the engine's reputation system, in one seam, so the service runs and is tested
 * without a server. Production is {@link EngineReputationNative}. Group ids passed in are the engine's own
 * spelling (the engine's maps are keyed exactly); the service finds that spelling first. World thread for
 * every per-player call.
 */
public interface ReputationNative {

    /** One native ReputationGroup as loaded; {@code npcGroups} is null when its file wrote no NPCGroups key. */
    record Group(@Nonnull String id, int initial, @Nullable List<String> npcGroups) {
    }

    /** Is the engine's reputation plugin running? Off, every other answer is empty or null. */
    boolean available();

    /** Every loaded ReputationRank, in any order. */
    @Nonnull
    List<ReputationLadder.Rank> ranks();

    /** Every loaded ReputationGroup. */
    @Nonnull
    List<Group> groups();

    /** Is there a live player at {@code ref}? */
    boolean live(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref);

    /** The live player's uuid, or null. */
    @Nullable
    UUID playerId(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref);

    /** The player's standing with {@code groupId} as the engine reads it (its starting value when untouched); null when it cannot read one. */
    @Nullable
    Integer earned(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref, @Nonnull String groupId);

    /** Has the player an entry of their own for {@code groupId}, i.e. has it ever been moved for them? */
    boolean hasEntry(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref, @Nonnull String groupId);

    /** Add {@code delta} through the engine (its clamp applies); the standing after, or null when nothing was written. */
    @Nullable
    Integer add(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref, @Nonnull String groupId, int delta);

    /** The folded maximum of {@code statId} on the player: what equipped gear adds. 0 for null, unknown or unread. */
    long statMax(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref, @Nullable String statId);

    /** Is the NPC role {@code roleName} a member of the native NPCGroup {@code npcGroupId}? */
    boolean roleInGroup(@Nonnull String roleName, @Nonnull String npcGroupId);

    /** Is {@code npcGroupId} a loaded native NPCGroup? */
    boolean npcGroupExists(@Nonnull String npcGroupId);
}
