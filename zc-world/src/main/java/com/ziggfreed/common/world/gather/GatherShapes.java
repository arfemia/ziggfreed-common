package com.ziggfreed.common.world.gather;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;
import org.joml.Vector3i;

import com.hypixel.hytale.builtin.adventure.farming.FarmingUtil;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.blocktype.component.BlockPhysics;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.InteractionManager;
import com.hypixel.hytale.server.core.entity.ItemUtils;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.block.BlockModule;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.interaction.BlockHarvestUtils;
import com.hypixel.hytale.server.core.modules.interaction.BlockInteractionUtils;
import com.hypixel.hytale.server.core.modules.interaction.InteractionModule;
import com.hypixel.hytale.server.core.modules.interaction.components.PlacedByInteractionComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.SetBlockSettings;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorFormula;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.factor.HytaleFactors;
import com.ziggfreed.common.progress.gate.GateQuery;
import com.ziggfreed.common.progress.gate.GateSpec;
import com.ziggfreed.common.progress.gate.StoreGates;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.world.BreakPermission;
import com.ziggfreed.common.world.ChunkSections;
import com.ziggfreed.common.world.SectionBlockCursor;
import com.ziggfreed.common.world.placed.PlacedBlockLedger;

/**
 * What the gather shapes ({@link GatherShapeAsset}) do: which shapes a player's hit, break or harvest starts, and the
 * further blocks each takes. A shape is asked in two halves: at the moment (the engine's break or use event, the block
 * still there), whether its {@code Origin} and {@code Requires} hold for the player, the block and the held item; then,
 * once the world has run the moment (a world task), whether the block really went (an uncancelled event does not prove
 * the engine broke or harvested it), and if so its further blocks. A further block breaks only where the held tool
 * could break it by hand ({@link ToolTier}) and the world lets the player break it ({@link BreakPermission}), through
 * the engine's own break call with the player and the drop a hand break picks, inside {@link ShapedBreaks#run} so
 * listeners can tell it apart; the held tool pays {@code Wear} for each block that broke and would wear it by hand
 * (never a soft block's), and the shape stops before the tool would break. A block whose placement did not use up its
 * item drops nothing ({@link PlacedBlockLedger#isSpared}). Harvest shapes harvest each further ripe farming block
 * through the engine's own harvest, once the engine's use event for it goes uncancelled. World-thread only.
 */
public final class GatherShapes {

    /** The vocabulary a shape's numbers and gates read first: the engine readings (held tool, stats). */
    private static final FactorRegistry FACTORS = factors();

    private GatherShapes() {
    }

    private static FactorRegistry factors() {
        FactorRegistry registry = new FactorRegistry("gather shapes");
        HytaleFactors.registerInto(registry, "ziggfreedcommon");
        return registry;
    }

    // ==================== the moment ====================

    /** Does any {@code Instant} shape break the block {@code blockId} at {@code pos} in one hit for {@code ref}? */
    public static boolean instantFor(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull String blockId, @Nullable Vector3i pos) {
        List<GatherShapeAsset> shapes = GatherShapeConfig.getInstance().instantShapes();
        if (shapes.isEmpty() || pos == null) {
            return false;
        }
        Moment moment = Moment.of(store, ref, blockId, pos, held(store, ref));
        for (GatherShapeAsset shape : shapes) {
            if (startsOn(shape, moment, false)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The {@code Break} shapes the player's break of {@code blockId} at {@code pos} with {@code held} starts, asked
     * while the block is still there. Empty, never null.
     */
    @Nonnull
    static List<GatherShapeAsset> breakShapesFor(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull String blockId, @Nonnull Vector3i pos, @Nullable ItemStack held) {
        return startingOf(GatherShapeConfig.getInstance().breakShapes(), Moment.of(store, ref, blockId, pos, held),
                true);
    }

    /** The {@code Harvest} shapes the player's harvest of {@code blockId} at {@code pos} starts. Empty, never null. */
    @Nonnull
    static List<GatherShapeAsset> harvestShapesFor(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull String blockId, @Nonnull Vector3i pos) {
        return startingOf(GatherShapeConfig.getInstance().harvestShapes(),
                Moment.of(store, ref, blockId, pos, held(store, ref)), false);
    }

    @Nonnull
    private static List<GatherShapeAsset> startingOf(@Nonnull List<GatherShapeAsset> shapes, @Nonnull Moment moment,
            boolean breaking) {
        if (shapes.isEmpty()) {
            return List.of();
        }
        List<GatherShapeAsset> out = new ArrayList<>(2);
        for (GatherShapeAsset shape : shapes) {
            if (startsOn(shape, moment, breaking)) {
                out.add(shape);
            }
        }
        return out;
    }

    /** One moment's facts, read once: who, where, what block, held how. */
    private record Moment(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull String blockId,
            @Nonnull Vector3i pos, @Nullable ItemStack held, @Nullable World world, @Nullable UUID playerId,
            @Nullable UUID worldId, @Nonnull FactorContext context) {

        static Moment of(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull String blockId,
                @Nonnull Vector3i pos, @Nullable ItemStack held) {
            PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
            World world = store.getExternalData().getWorld();
            FactorContext context = FactorContext.builder().store(store).subject(ref).world(world).held(held)
                    .blockId(blockId).build();
            return new Moment(store, ref, blockId, pos, held, world, player == null ? null : player.getUuid(),
                    player == null ? null : player.getWorldUuid(), context);
        }
    }

    /**
     * Does {@code shape} start on {@code moment}? Its {@code Origin} first (cheap reads), then its {@code Requires}.
     * {@code breaking} says the block is about to go, so a placed-block mark is spent rather than only read.
     */
    private static boolean startsOn(@Nonnull GatherShapeAsset shape, @Nonnull Moment moment, boolean breaking) {
        GatherShapeAsset.Origin origin = shape.origin();
        BlockFamilyConfig families = BlockFamilyConfig.getInstance();
        if (origin.families().length > 0 && !families.holdsAny(origin.families(), moment.blockId())) {
            return false;
        }
        if (origin.crouching() != null
                && origin.crouching() != GatherReads.crouching(moment.store(), moment.ref())) {
            return false;
        }
        if (origin.placed() != null && origin.placed() != placedAt(moment, breaking)) {
            return false;
        }
        if (origin.placedWithin() != null) {
            // The cheap read first: most blocks were never placed by this player at all.
            long placedAt = RecentPlacements.placedAt(moment.playerId(), moment.worldId(), moment.pos().x,
                    moment.pos().y, moment.pos().z);
            if (placedAt == Long.MIN_VALUE) {
                return false;
            }
            double seconds = evaluate(origin.placedWithin(), moment.context());
            if (!(seconds > 0.0) || System.currentTimeMillis() - placedAt > (long) (seconds * 1000.0)) {
                return false;
            }
        }
        if (origin.lowest() && moment.world() != null) {
            SectionBlockCursor cursor = SectionBlockCursor.of(moment.world());
            int self = BlockType.getAssetMap().getIndex(moment.blockId());
            if (cursor.blockId(moment.pos().x, moment.pos().y - 1, moment.pos().z) == self) {
                return false;
            }
        }
        if (origin.own() && !ownedBy(moment.world(), moment.playerId(), moment.worldId(), moment.pos().x,
                moment.pos().y, moment.pos().z)) {
            return false;
        }
        return passes(shape.liveRequires(), moment.context());
    }

    private static boolean placedAt(@Nonnull Moment moment, boolean breaking) {
        if (moment.playerId() == null || moment.worldId() == null) {
            return false;
        }
        Vector3i pos = moment.pos();
        PlacedBlockLedger ledger = PlacedBlockLedger.getInstance();
        return breaking ? ledger.consumePlacement(moment.playerId(), moment.worldId(), pos.x, pos.y, pos.z)
                : ledger.isPlaced(moment.playerId(), moment.worldId(), pos.x, pos.y, pos.z);
    }

    // ==================== the further blocks ====================

    /**
     * Run {@code shapes} for a break of {@code blockId} at {@code origin}, once the world has run it: nothing when
     * the block is still there (the engine refused the break). World-thread only, outside any system's tick.
     */
    static void runBreaks(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull String blockId,
            @Nonnull Vector3i origin, @Nullable String heldId, @Nonnull List<GatherShapeAsset> shapes) {
        World world = store.getExternalData().getWorld();
        if (world == null || !ref.isValid()) {
            return;
        }
        SectionBlockCursor cursor = SectionBlockCursor.of(world);
        int originIndex = BlockType.getAssetMap().getIndex(blockId);
        if (cursor.blockId(origin.x, origin.y, origin.z) == originIndex) {
            return;
        }
        ItemStack held = held(store, ref);
        String nowHeld = held == null || held.isEmpty() ? null : held.getItemId();
        if (heldId == null ? nowHeld != null : !heldId.equals(nowHeld)) {
            return;
        }
        for (GatherShapeAsset shape : shapes) {
            try {
                Moment moment = Moment.of(store, ref, blockId, origin, held);
                List<int[]> cells = cellsOf(shape, moment, originIndex, cursor);
                if (!cells.isEmpty()) {
                    breakAll(moment, shape, cells);
                }
            } catch (Throwable t) {
                SafeLog.warn("[gather] the gather shape '" + shape.getId() + "' could not run: " + t.getMessage());
            }
        }
    }

    /**
     * Run {@code shapes} for a harvest of {@code blockId} at {@code origin}, once the world has run it: nothing when
     * the block is unchanged (no harvest happened). Each further block is harvested only where the world lets the
     * player break it ({@link BreakPermission}) and the engine's own use event, fired for it with the player as the
     * engine's use of a block fires it ({@code UseBlockEvent.Pre}, {@code use} the interaction the first harvest
     * came by), goes uncancelled: that event is where a claim or protection mod refuses a harvest, and the engine's
     * harvest call fires none of its own. A refused block stops the shape, so a refusal is heard once. World-thread
     * only, outside any system's tick.
     */
    static void runHarvests(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull String blockId, @Nonnull Vector3i origin, @Nonnull InteractionType use,
            @Nonnull List<GatherShapeAsset> shapes) {
        World world = store.getExternalData().getWorld();
        if (world == null || !ref.isValid()) {
            return;
        }
        SectionBlockCursor cursor = SectionBlockCursor.of(world);
        int originIndex = BlockType.getAssetMap().getIndex(blockId);
        if (cursor.blockId(origin.x, origin.y, origin.z) == originIndex) {
            return;
        }
        Store<ChunkStore> chunks = world.getChunkStore().getStore();
        GameMode mode = GatherReads.gameMode(store, ref);
        InteractionManager manager = store.getComponent(ref,
                InteractionModule.get().getInteractionManagerComponent());
        for (GatherShapeAsset shape : shapes) {
            try {
                Moment moment = Moment.of(store, ref, blockId, origin, held(store, ref));
                for (int[] cell : cellsOf(shape, moment, originIndex, cursor)) {
                    if (!ref.isValid()) {
                        return;
                    }
                    int id = cursor.blockId(cell[0], cell[1], cell[2]);
                    BlockType type = id == BlockType.EMPTY_ID ? null : BlockType.getAssetMap().getAsset(id);
                    Vector3i pos = new Vector3i(cell[0], cell[1], cell[2]);
                    if (type == null) {
                        continue;
                    }
                    if (!BreakPermission.allowsBreaking(world, mode, type, pos.x, pos.y, pos.z)
                            || !useAllowed(store, ref, manager, use, pos, type)) {
                        // Refused: the shape stops here, so a claim mod's refusal is heard once, not once a cell.
                        break;
                    }
                    FarmingUtil.harvest(chunks, store, ref, pos);
                }
            } catch (Throwable t) {
                SafeLog.warn("[gather] the gather shape '" + shape.getId() + "' could not run: " + t.getMessage());
            }
        }
    }

    /**
     * Does the engine's use event for {@code type} at {@code pos}, fired with the player as the engine's use of a block
     * fires it, go uncancelled? A player whose interactions cannot be read is refused: the event could not be asked.
     */
    private static boolean useAllowed(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nullable InteractionManager manager, @Nonnull InteractionType use, @Nonnull Vector3i pos,
            @Nonnull BlockType type) {
        if (manager == null) {
            return false;
        }
        try {
            InteractionType asked = use == InteractionType.Equipped ? InteractionType.Use : use;
            InteractionContext context = InteractionContext.forInteraction(manager, ref, asked, store);
            UseBlockEvent.Pre event = new UseBlockEvent.Pre(asked, context, pos, type);
            store.invoke(ref, event);
            return !event.isCancelled();
        } catch (Throwable t) {
            SafeLog.fine("[gather] a harvest's use event could not be asked: " + t.getMessage());
            return false;
        }
    }

    /** The further cells {@code shape} takes around {@code moment}'s block, in break order. */
    @Nonnull
    private static List<int[]> cellsOf(@Nonnull GatherShapeAsset shape, @Nonnull Moment moment, int originIndex,
            @Nonnull SectionBlockCursor cursor) {
        GatherShapeAsset.Shape walk = shape.shape();
        if (walk == null || walk.walk() == null) {
            return List.of();
        }
        int max = ShapeWalks.capped(evaluate(shape.count(), moment.context()));
        if (max <= 0) {
            return List.of();
        }
        Vector3i o = moment.pos();
        ShapeWalks.Cells takes = cellTest(walk, moment, originIndex, cursor);
        switch (walk.walk()) {
            case CONNECTED:
                return ShapeWalks.connected(o.x, o.y, o.z, max, takes);
            case TREE: {
                List<int[]> logs = ShapeWalks.connected(o.x, o.y, o.z, max, takes);
                GatherShapeAsset.Canopy canopy = walk.canopy();
                if (canopy == null || canopy.families().length == 0
                        || !passes(canopy.liveRequires(), moment.context())) {
                    return logs;
                }
                String[] leafFamilies = canopy.families();
                List<int[]> all = new ArrayList<>(logs);
                all.addAll(ShapeWalks.canopy(o.x, o.y, o.z, logs, ShapeWalks.MAX_COUNT * 2,
                        (x, y, z) -> inFamilies(cursor.blockId(x, y, z), leafFamilies)));
                return all;
            }
            case OFFSETS: {
                int[] facing = facingOf(moment);
                GatherShapeAsset.Offset[] offsets = walk.offsets();
                int[][] frame = new int[offsets.length][];
                for (int i = 0; i < offsets.length; i++) {
                    frame[i] = new int[] {offsets[i].forward(), offsets[i].up(), offsets[i].right()};
                }
                return ShapeWalks.offsets(o.x, o.y, o.z, facing[0], facing[1], frame, max, takes);
            }
            case AREA: {
                int radius = (int) Math.floor(evaluate(walk.radius(), moment.context()));
                int minY = o.y - walk.below();
                if (walk.aboveFeet()) {
                    minY = Math.max(minY, feetY(moment));
                }
                return ShapeWalks.area(o.x, o.y, o.z, radius, minY, o.y + walk.above(), max, takes);
            }
            default:
                return List.of();
        }
    }

    /** Which cells a walk takes: not air, of its families (else the first block's own block), placed as it asks. */
    @Nonnull
    private static ShapeWalks.Cells cellTest(@Nonnull GatherShapeAsset.Shape walk, @Nonnull Moment moment,
            int originIndex, @Nonnull SectionBlockCursor cursor) {
        String[] families = walk.families();
        Boolean placed = walk.walk() == GatherShapeAsset.Walk.TREE && walk.placed() == null ? Boolean.FALSE
                : walk.placed();
        boolean own = walk.own();
        PlacedBlockLedger ledger = PlacedBlockLedger.getInstance();
        return (x, y, z) -> {
            int id = cursor.blockId(x, y, z);
            if (id == BlockType.EMPTY_ID) {
                return false;
            }
            if (families.length == 0 ? id != originIndex : !inFamilies(id, families)) {
                return false;
            }
            if (placed != null && moment.playerId() != null && moment.worldId() != null
                    && placed != ledger.isPlaced(moment.playerId(), moment.worldId(), x, y, z)) {
                return false;
            }
            return !own || ownedBy(moment.world(), moment.playerId(), moment.worldId(), x, y, z);
        };
    }

    /**
     * Break each of {@code cells} for the moment's player as their own swing would, through the engine's own break
     * with the drop a hand break picks ({@link ToolTier#dropFor}), stopping before the held tool would break, then
     * charge the tool {@code Wear} for each block that broke and wears a tool by hand (a soft block, such as a leaf,
     * costs nothing, as a hand hit on it does). A block whose placement did not use up its item drops nothing
     * ({@link PlacedBlockLedger#isSpared}); a {@code Pickup} shape hands every drop to the player as a pickup.
     */
    private static void breakAll(@Nonnull Moment moment, @Nonnull GatherShapeAsset shape, @Nonnull List<int[]> cells) {
        World world = moment.world();
        if (world == null) {
            return;
        }
        Store<EntityStore> store = moment.store();
        Ref<EntityStore> ref = moment.ref();
        ItemStack held = moment.held();
        GameMode mode = GatherReads.gameMode(store, ref);
        boolean wears = held != null && !held.isEmpty() && !held.isUnbreakable() && mode != GameMode.Creative;
        double wear = shape.wear() == null ? 1.0 : Math.max(0.0, evaluate(shape.wear(), moment.context()));
        boolean costs = wears && wear > 0.0;
        int budget = costs ? (int) Math.max(0.0, Math.floor((held.getDurability() - 1.0) / wear)) : Integer.MAX_VALUE;
        boolean rows = shape.writesRows() && passes(shape.liveRows(), moment.context());
        boolean pickup = shape.pickup() && BlockInteractionUtils.isNaturalAction(ref, store);
        ChunkStore chunks = world.getChunkStore();
        Store<ChunkStore> chunkStore = chunks.getStore();
        SectionBlockCursor cursor = SectionBlockCursor.of(world);
        PlacedBlockLedger ledger = PlacedBlockLedger.getInstance();
        UUID worldId = moment.worldId();
        int[] worn = {0};
        ShapedBreaks.run(rows, () -> {
            for (int[] cell : cells) {
                if (!ref.isValid()) {
                    break;
                }
                int x = cell[0];
                int y = cell[1];
                int z = cell[2];
                int id = cursor.blockId(x, y, z);
                BlockType type = id == BlockType.EMPTY_ID ? null : BlockType.getAssetMap().getAsset(id);
                boolean paid = costs && ToolTier.wears(type);
                if (paid && worn[0] >= budget) {
                    // The tool would break on the next block it pays for: the shape stops here.
                    break;
                }
                Ref<ChunkStore> section = type == null ? null : ChunkSections.at(chunks, x, y, z);
                ToolTier.Drop drop = section == null ? null
                        : ToolTier.dropFor(held, type, deco(chunkStore, section, x, y, z));
                if (drop == null) {
                    // The held tool could not break this block by hand: the shape leaves it standing.
                    continue;
                }
                if (!BreakPermission.allowsBreaking(world, mode, type, x, y, z)) {
                    // Refused: the shape stops here, so a refusal is heard once, not once a cell.
                    break;
                }
                boolean spared = worldId != null && ledger.isSpared(worldId, x, y, z, type);
                int quantity = spared || pickup ? 0 : drop.quantity();
                Vector3i pos = new Vector3i(x, y, z);
                BlockHarvestUtils.performBlockBreak(pos, type, held, quantity, drop.itemId(), drop.dropListId(),
                        SetBlockSettings.NONE, ref, section, store, chunkStore);
                if (cursor.blockId(x, y, z) == id) {
                    // The engine's break event was refused (a claim or protection mod): the shape stops here too.
                    break;
                }
                if (spared) {
                    ledger.clearSpared(worldId, x, y, z);
                } else if (pickup) {
                    pickUp(store, ref, type, drop, pos);
                }
                if (paid) {
                    worn[0]++;
                }
            }
        });
        if (costs && worn[0] > 0) {
            chargeWear(store, ref, held.getItemId(), wear * worn[0]);
        }
    }

    /** Whether the engine marks the block at the cell as a placed decoration (its own default-drop rule reads it). */
    private static boolean deco(@Nonnull Store<ChunkStore> chunkStore, @Nonnull Ref<ChunkStore> section, int x, int y,
            int z) {
        BlockPhysics physics = chunkStore.getComponent(section, BlockPhysics.getComponentType());
        return physics != null && physics.isDeco(x, y, z);
    }

    /** Hand a broken block's {@code drop} to the player as the engine's own pickup does; a full bag drops the rest. */
    private static void pickUp(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull BlockType type, @Nonnull ToolTier.Drop drop, @Nonnull Vector3i pos) {
        Vector3d center = new Vector3d(pos.x + 0.5, pos.y + 0.5, pos.z + 0.5);
        for (ItemStack stack : BlockHarvestUtils.getDrops(type, drop.quantity(), drop.itemId(), drop.dropListId())) {
            if (stack != null && !stack.isEmpty()) {
                ItemUtils.interactivelyPickupItem(ref, stack, center, store);
            }
        }
    }

    /** Take {@code amount} durability from the tool in the active hotbar slot, when it is still {@code itemId}. */
    private static void chargeWear(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull String itemId, double amount) {
        InventoryComponent.Hotbar hotbar = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        ItemStack now = hotbar == null ? null : hotbar.getActiveItem();
        if (now == null || now.isEmpty() || !itemId.equals(now.getItemId())) {
            return;
        }
        ItemUtils.updateItemStackDurability(ref, now, hotbar.getInventory(), hotbar.getActiveSlot(), -amount, store);
    }

    // ==================== reads ====================

    private static boolean inFamilies(int blockIndex, @Nonnull String[] families) {
        if (blockIndex == BlockType.EMPTY_ID) {
            return false;
        }
        BlockType type = BlockType.getAssetMap().getAsset(blockIndex);
        return type != null && BlockFamilyConfig.getInstance().holdsAny(families, type.getId());
    }

    /**
     * Whether the block at the cell was placed by {@code playerId}: the placed-block ledger's placer, or the engine's own
     * record on a block that keeps one (a farming block).
     */
    private static boolean ownedBy(@Nullable World world, @Nullable UUID playerId, @Nullable UUID worldId, int x,
            int y, int z) {
        if (world == null || playerId == null) {
            return false;
        }
        if (worldId != null && PlacedBlockLedger.getInstance().isPlacedBy(playerId, worldId, x, y, z)) {
            return true;
        }
        try {
            Ref<ChunkStore> entity = BlockModule.getBlockEntity(world, x, y, z);
            if (entity == null || !entity.isValid()) {
                return false;
            }
            PlacedByInteractionComponent placed = world.getChunkStore().getStore().getComponent(entity,
                    PlacedByInteractionComponent.getComponentType());
            return placed != null && playerId.equals(placed.getWhoPlacedUuid());
        } catch (Throwable t) {
            return false;
        }
    }

    @Nullable
    static ItemStack held(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        InventoryComponent.Hotbar hotbar = store.getComponent(ref, InventoryComponent.Hotbar.getComponentType());
        return hotbar == null ? null : hotbar.getActiveItem();
    }

    @Nonnull
    private static int[] facingOf(@Nonnull Moment moment) {
        TransformComponent transform = moment.store().getComponent(moment.ref(), TransformComponent.getComponentType());
        Vector3d at = transform == null ? null : transform.getPosition();
        if (at == null) {
            return new int[] {0, -1};
        }
        return ShapeWalks.facing(at.x, at.z, moment.pos().x + 0.5, moment.pos().z + 0.5);
    }

    private static int feetY(@Nonnull Moment moment) {
        TransformComponent transform = moment.store().getComponent(moment.ref(), TransformComponent.getComponentType());
        Vector3d at = transform == null ? null : transform.getPosition();
        return at == null ? moment.pos().y : (int) Math.floor(at.y + 0.01);
    }

    private static double evaluate(@Nullable FactorFormula formula, @Nonnull FactorContext context) {
        if (formula == null) {
            return 0.0;
        }
        try {
            double value = formula.evaluate(FACTORS, context);
            return Double.isFinite(value) ? value : 0.0;
        } catch (Throwable t) {
            return 0.0;
        }
    }

    private static boolean passes(@Nullable GateSpec live, @Nonnull FactorContext context) {
        return live == null || StoreGates.passes(live, GateQuery.builder().context(context).local(FACTORS).build());
    }
}
