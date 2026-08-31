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
 *
 * <p><b>Nor is a placement by anyone the policy exempts.</b> Same case, for a builder who is in
 * survival rather than creative: {@link PlacedBlockLedger.Policy#guardsPlacementsBy} lets the
 * consumer name them (typically by permission). An exempt placement is simply never remembered, so
 * whoever breaks it later earns from it as they would from any other block. It still counts as a
 * placement for everything else, including whatever the placer earns for making it.
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
        PlacedBlockLedger ledger = PlacedBlockLedger.getInstance();
        if (!ledger.policy().guardsPlacementsBy(playerRef)) {
            // Somebody building FOR other players to work: what they put down is meant to pay out,
            // so it is left unrecorded and reads as an ordinary block from here on. They still earn
            // for placing it - this decides only whether the block is remembered.
            return;
        }
        var position = event.getTargetBlock();
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
