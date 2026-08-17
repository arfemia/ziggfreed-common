package com.ziggfreed.common.world.placed;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The one WRITER into {@link PlacedBlockLedger}: every block a player puts down is recorded here,
 * once, for everybody who later asks about it.
 *
 * <p>Writing belongs to the library rather than to whichever mod happens to read, because two mods
 * each recording the same placement would double-count the placed ITEM half and neither could tell
 * which of them was authoritative. A consumer READS the ledger; it never records.
 *
 * <p><b>A CANCELLED placement is not recorded either</b>, for the plain reason that no block was
 * put down: something else in the chain refused it, and remembering a placement that never happened
 * would refuse the player credit for breaking whatever is genuinely there.
 *
 * <p><b>A CREATIVE-mode placement is not recorded.</b> An admin walling in an ore vein for survival
 * players to mine is doing the opposite of the exploit this guards against, and the block carries
 * no signal at break time about who put it there, so the decision has to be made now.
 */
public final class PlacedBlockRecorder extends EntityEventSystem<EntityStore, PlaceBlockEvent> {

    /** The id the engine uses for "there is nothing here", which nobody placed. */
    private static final String EMPTY_ITEM_ID = "Empty";

    public PlacedBlockRecorder() {
        super(PlaceBlockEvent.class);
    }

    @Nullable
    @Override
    public Query<EntityStore> getQuery() {
        return PlayerRef.getComponentType();
    }

    @Override
    public void handle(final int index, @Nonnull final ArchetypeChunk<EntityStore> archetypeChunk,
            @Nonnull final Store<EntityStore> store,
            @Nonnull final CommandBuffer<EntityStore> commandBuffer,
            @Nonnull final PlaceBlockEvent event) {
        var placed = event.getItemInHand();
        String itemId = placed == null ? null : placed.getItemId();
        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        Player player = store.getComponent(ref, Player.getComponentType());
        if (!placementCounts(event.isCancelled(), itemId,
                player == null ? null : player.getGameMode())) {
            return;
        }
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        if (playerRef == null) {
            return;
        }
        var position = event.getTargetBlock();
        PlacedBlockLedger ledger = PlacedBlockLedger.getInstance();
        ledger.trackPlacement(playerRef.getUuid(), playerRef.getWorldUuid(),
                position.x, position.y, position.z);
        ledger.trackPlacedItem(playerRef.getUuid(), itemId);
    }

    /**
     * Was a block actually put down here, by a player whose placements count? The ONE reading of
     * the three filters in the class javadoc - a cancelled placement never happened, an empty or
     * blank item is nothing, and a creative-mode placement is exempt - shared with the library's own
     * {@code PLACE_BLOCK} producer, so what is recorded here and what is produced as a moment can
     * never drift apart: a placement one of them counts is a placement the other counts.
     *
     * @param cancelled whether something in the chain refused the placement
     * @param itemId    the placed item's id, or null when nothing was in hand
     * @param gameMode  the placer's game mode, or null when it could not be read
     */
    public static boolean placementCounts(boolean cancelled, @Nullable String itemId,
            @Nullable GameMode gameMode) {
        if (cancelled) {
            return false;
        }
        if (itemId == null || itemId.isBlank() || EMPTY_ITEM_ID.equals(itemId)) {
            return false;
        }
        return gameMode != GameMode.Creative;
    }
}
