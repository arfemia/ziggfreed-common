package com.ziggfreed.common.objectives.trainer;

import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.npc.placement.asset.NpcPlacementAsset;
import com.ziggfreed.common.track.TrackDef;

/**
 * The trainer a page stands at and the player reading it: the placement (its id, its asset and so its trainer
 * block), the character standing there, the tracks it teaches ({@link Trainers#tracksTaught}), and the player's own
 * handles. Built fresh for every build and every press, never kept between them: the handles are live only for that
 * call. World thread.
 *
 * @param placementId the trainer's placement id, lower-cased
 * @param npcId       who stands there ({@code Identity.NpcId}, else its role), or null when the placement names none
 * @param ref         the player's own entity reference, never the character's
 * @param npc         the character's entity the page was opened at (a press F), or null when it was opened from
 *                    elsewhere (an item, a block, a conversation)
 */
public record TrainerAt(@Nonnull String placementId, @Nonnull NpcPlacementAsset placement, @Nullable String npcId,
        @Nonnull List<TrackDef> tracks, @Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
        @Nonnull Player player, @Nonnull UUID playerId, @Nullable Ref<EntityStore> npc) {

    public TrainerAt {
        tracks = List.copyOf(tracks);
    }

    /** The trainer block (the placement was resolved as a trainer, so it carries one; an empty one otherwise). */
    @Nonnull
    public NpcPlacementAsset.Trainer trainer() {
        NpcPlacementAsset.Trainer block = placement.getTrainer();
        return block != null ? block : new NpcPlacementAsset.Trainer();
    }
}
