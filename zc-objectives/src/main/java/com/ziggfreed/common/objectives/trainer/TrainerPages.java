package com.ziggfreed.common.objectives.trainer;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.inventory.PlayerAccess;
import com.ziggfreed.common.util.SafeLog;

/**
 * The one way a trainer's page opens: the {@code Trainer} destination ({@link TrainerDestinations}: a conversation's
 * Train answer, a placement's {@code Interact}, a link) and the {@code ZigOpenTrainer} interaction
 * ({@link ZigOpenTrainerInteraction}: an NPC's, a block's or an item's chain) both land here. World thread.
 */
public final class TrainerPages {

    private TrainerPages() {
    }

    /**
     * Open the page of the trainer {@code placementId} for {@code player}, on {@code anchor} (the character at a
     * press-F, else the player). A placement that is no trainer here opens nothing and tells the player so.
     *
     * @return whether the screen was taken
     */
    public static boolean open(@Nullable String placementId, @Nonnull Store<EntityStore> store,
            @Nonnull Ref<EntityStore> anchor, @Nonnull Player player) {
        PlayerRef playerRef = PlayerAccess.playerRef(player);
        if (playerRef == null) {
            SafeLog.fine("[trainer] a trainer was asked for by an entity that is not a player");
            return false;
        }
        if (placementId == null || Trainers.trainer(placementId) == null) {
            playerRef.sendMessage(TrainerText.line("none"));
            return false;
        }
        try {
            player.getPageManager().openCustomPage(anchor, store, new TrainerPage(playerRef, placementId));
            return true;
        } catch (Throwable t) {
            SafeLog.warn("[trainer] the trainer page failed to open", t);
            return false;
        }
    }
}
