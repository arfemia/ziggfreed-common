package com.ziggfreed.common.world.gather;

import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3i;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.PlaceBlockEvent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.world.BlockRuleSystems;
import com.ziggfreed.common.world.SectionBlockCursor;
import com.ziggfreed.common.world.placed.PlacedBlockLedger;

/**
 * The thrift step: with {@code BuildThrift} in a hundred, plus each {@code BuildThrift_<Family>} whose block family
 * holds the block and {@code BuildThrift_Shaped} on a shaped placement ({@link RecentPlacements}), held at the
 * player's {@code BuildThriftMax}, a block a player places is not used up: the engine's own place event is told not
 * to take it ({@code PlaceBlockEvent.setConsumeItem}). Only a block the {@value #ELIGIBLE_FAMILY} family holds is
 * ever spared, the cheap building blocks. A block this step spared is marked spared in the placed-block ledger once it
 * has landed, so breaking it hands nothing back ({@link ThriftDropSystem}, and a gather shape's own breaks): placing
 * and breaking again never makes an item from nothing. Only this step's own spares are marked; any other placement
 * that keeps its item (creative, a mod's own) drops as the engine has it, and clears a mark a block left behind. A
 * refused placement hands a taken item back, so a spared one the engine then refuses is simply kept.
 *
 * <p>Every player placement is also remembered for the shaped and recent-placement rules, whether or not any thrift
 * applies. Ordered after {@link BlockRuleSystems.RefusedPlacement}, so a refused placement arrives cancelled.
 */
public final class ThriftSystem extends EntityEventSystem<EntityStore, PlaceBlockEvent> {

    /** The base thrift stat. */
    public static final String THRIFT_STAT = "BuildThrift";

    /** Its ceiling stat. */
    public static final String CEILING_STAT = "BuildThriftMax";

    /** A family's thrift stat is this prefix and the family's id. */
    public static final String FAMILY_PREFIX = "BuildThrift_";

    /** Thrift on a shaped placement. */
    public static final String SHAPED_STAT = "BuildThrift_Shaped";

    /** The family that holds every block thrift may spare. */
    public static final String ELIGIBLE_FAMILY = "Thrift";

    /** The ceiling a player reads when the ceiling stat cannot be read. */
    public static final double FALLBACK_CEILING = 75.0;

    private static final Set<Dependency<EntityStore>> DEPENDENCIES =
            Set.of(new SystemDependency<>(Order.AFTER, BlockRuleSystems.RefusedPlacement.class));

    public ThriftSystem() {
        super(PlaceBlockEvent.class);
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return DEPENDENCIES;
    }

    @Override
    public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk, @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull PlaceBlockEvent event) {
        try {
            if (event.isCancelled()) {
                return;
            }
            Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
            PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
            Vector3i pos = event.getTargetBlock();
            if (player == null || pos == null) {
                return;
            }
            boolean shaped = RecentPlacements.place(player.getUuid(), player.getWorldUuid(), pos.x, pos.y, pos.z,
                    System.currentTimeMillis());
            boolean spare = spares(store, ref, event, shaped);
            if (spare) {
                event.setConsumeItem(false);
            }
            noteSpared(store, player.getWorldUuid(), pos.x, pos.y, pos.z, spare);
        } catch (Throwable t) {
            SafeLog.fine("[gather] a placement's thrift could not be read: " + t.getMessage());
        }
    }

    /** Does thrift spare this placement's item: one the engine would take, of the family, on the placer's roll? */
    private static boolean spares(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull PlaceBlockEvent event, boolean shaped) {
        if (!event.isConsumeItem() || GatherReads.gameMode(store, ref) == GameMode.Creative) {
            return false;
        }
        ItemStack item = event.getItemInHand();
        String blockId = item == null || item.isEmpty() ? null : item.getItemId();
        if (blockId == null || !BlockFamilyConfig.getInstance().holds(ELIGIBLE_FAMILY, blockId)) {
            return false;
        }
        return GatherReads.roll(percentFor(GatherReads.stats(store, ref), blockId, shaped));
    }

    /**
     * Keep the ledger's spared mark at the cell true to this placement: for a spare, set once the block has landed,
     * naming the block that landed (a world task, since the engine places after its event; a refused placement leaves
     * the cell as it was and marks nothing); for any other placement, cleared at once, so a mark a block taken by
     * physics left behind never costs the next block placed there its drop.
     */
    private static void noteSpared(@Nonnull Store<EntityStore> store, @Nullable UUID worldId, int x, int y, int z,
            boolean spare) {
        World world = store.getExternalData().getWorld();
        if (world == null || worldId == null) {
            return;
        }
        PlacedBlockLedger ledger = PlacedBlockLedger.getInstance();
        if (!spare) {
            ledger.clearSpared(worldId, x, y, z);
            return;
        }
        int before = SectionBlockCursor.of(world).blockId(x, y, z);
        world.execute(() -> {
            int now = SectionBlockCursor.of(world).blockId(x, y, z);
            BlockType landed = now == before || now == BlockType.EMPTY_ID ? null : BlockType.getAssetMap().getAsset(now);
            if (landed != null) {
                ledger.markSpared(worldId, x, y, z, landed);
            }
        });
    }

    /** The chance in a hundred that placing {@code blockId} spares it, held at the placer's ceiling. */
    public static double percentFor(@Nullable EntityStatMap stats, @Nonnull String blockId, boolean shaped) {
        if (stats == null) {
            return 0.0;
        }
        double sum = GatherReads.max(stats, THRIFT_STAT);
        BlockFamilyConfig families = BlockFamilyConfig.getInstance();
        for (String family : families.familiesOf(blockId)) {
            sum += GatherReads.max(stats, FAMILY_PREFIX + families.displayId(family));
        }
        if (shaped) {
            sum += GatherReads.max(stats, SHAPED_STAT);
        }
        return Math.max(0.0, Math.min(GatherReads.ceiling(stats, CEILING_STAT, FALLBACK_CEILING), sum));
    }
}
