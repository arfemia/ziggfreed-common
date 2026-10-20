package com.ziggfreed.common.world.gather;

import java.util.Locale;
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
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.DamageBlockEvent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.world.BlockRuleSystems;
import com.ziggfreed.common.world.SectionBlockCursor;
import com.ziggfreed.common.world.placed.PlacedBlockLedger;

/**
 * The gather step: a player's hit on a block deals {@code 1 + P/100} times the engine's damage, P being the sum of
 * their {@code GatherSpeed}, each {@code GatherSpeed_<Family>} whose block family holds the block, {@code
 * GatherSpeed_Placed} on a placed block, {@code GatherSpeed_Tall_Tree} on a log of a tall tree, the {@code
 * GatherRhythm} chain's steps and each stat a mod counts for the block ({@link GatherSpeedSources}), held at their
 * {@code GatherSpeedMax} (and never below {@value #MIN_PERCENT}). The engine's damage is its tool spec's power, so a
 * hit the tool cannot make (a tier it lacks, R625) is 0 and stays 0: speed never breaks a block early. A gather shape
 * that breaks its first block at once ({@link GatherShapes#instantFor}) raises the hit to the block's whole health.
 *
 * <p>The client predicts no block damage (the server sends the block's health back), so the faster break needs
 * nothing client-side. Ordered after {@link BlockRuleSystems.AttributedDamage}, so a refused hit arrives cancelled
 * and is left alone.
 */
public final class GatherSpeedSystem extends EntityEventSystem<EntityStore, DamageBlockEvent> {

    /** The base speed stat every block reads, and the one a holder condition's stat pairs with. */
    public static final String SPEED_STAT = "GatherSpeed";

    /** The ceiling stat. */
    public static final String CEILING_STAT = "GatherSpeedMax";

    /** A family's speed stat is this prefix and the family's id. */
    public static final String FAMILY_PREFIX = "GatherSpeed_";

    /** Speed on a block a player placed. */
    public static final String PLACED_STAT = "GatherSpeed_Placed";

    /** Speed on a log of a tall tree. */
    public static final String TALL_TREE_STAT = "GatherSpeed_Tall_Tree";

    /** Speed for each step of the break chain. */
    public static final String RHYTHM_STAT = "GatherRhythm";

    /** The family a tall tree's logs are in. */
    public static final String LOG_FAMILY = "Log";

    private static final String LOG_FAMILY_KEY = LOG_FAMILY.toLowerCase(Locale.ROOT);

    /** A tree is tall when its column of one log reaches this many blocks. */
    public static final int TALL_TREE_LOGS = 10;

    /** The ceiling a player reads when the ceiling stat cannot be read. */
    public static final double FALLBACK_CEILING = 200.0;

    /** The slowest a hit can become: a tenth of its damage. */
    public static final double MIN_PERCENT = -90.0;

    private static final Set<Dependency<EntityStore>> DEPENDENCIES =
            Set.of(new SystemDependency<>(Order.AFTER, BlockRuleSystems.AttributedDamage.class));

    public GatherSpeedSystem() {
        super(DamageBlockEvent.class);
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
            @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull DamageBlockEvent event) {
        try {
            if (event.isCancelled() || !(event.getDamage() > 0.0f)) {
                return;
            }
            BlockType blockType = event.getBlockType();
            String blockId = blockType == null ? null : blockType.getId();
            if (blockId == null || blockId.isBlank()) {
                return;
            }
            Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
            Vector3i pos = event.getTargetBlock();
            if (GatherShapes.instantFor(store, ref, blockId, pos)) {
                event.setDamage(Math.max(event.getDamage(), event.getCurrentDamage() + 0.01f));
                return;
            }
            double percent = percentFor(store, ref, blockId, pos);
            if (percent != 0.0) {
                event.setDamage((float) (event.getDamage() * (1.0 + percent / 100.0)));
            }
        } catch (Throwable t) {
            SafeLog.fine("[gather] a hit's speed could not be read: " + t.getMessage());
        }
    }

    /**
     * The percent a hit by {@code ref} on {@code blockId} at {@code pos} is sped up by, held at the breaker's ceiling.
     * World-thread only. 0 when the breaker carries no stat map.
     */
    public static double percentFor(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull String blockId, @Nullable Vector3i pos) {
        EntityStatMap stats = GatherReads.stats(store, ref);
        if (stats == null) {
            return 0.0;
        }
        double sum = GatherReads.max(stats, SPEED_STAT);
        BlockFamilyConfig families = BlockFamilyConfig.getInstance();
        Set<String> held = families.familiesOf(blockId);
        for (String family : held) {
            sum += GatherReads.max(stats, FAMILY_PREFIX + families.displayId(family));
        }
        for (String stat : GatherSpeedSources.statsFor(blockId)) {
            sum += GatherReads.max(stats, stat);
        }
        if (pos != null) {
            double placed = GatherReads.max(stats, PLACED_STAT);
            if (placed != 0.0 && placed(store, ref, pos)) {
                sum += placed;
            }
            double tall = GatherReads.max(stats, TALL_TREE_STAT);
            if (tall != 0.0 && held.contains(LOG_FAMILY_KEY)
                    && columnOf(store, blockId, pos) >= TALL_TREE_LOGS) {
                sum += tall;
            }
        }
        double rhythm = GatherReads.max(stats, RHYTHM_STAT);
        if (rhythm != 0.0) {
            sum += rhythm * GatherChains.steps(playerId(store, ref), System.currentTimeMillis());
        }
        return held(sum, GatherReads.ceiling(stats, CEILING_STAT, FALLBACK_CEILING));
    }

    /** {@code sum} held between {@value #MIN_PERCENT} and {@code ceiling}. Pure. */
    static double held(double sum, double ceiling) {
        return Math.max(MIN_PERCENT, Math.min(ceiling, sum));
    }

    /** Whether the block at {@code pos} in the breaker's world was placed by a player. */
    private static boolean placed(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref,
            @Nonnull Vector3i pos) {
        PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
        UUID worldId = player == null ? null : player.getWorldUuid();
        return worldId != null
                && PlacedBlockLedger.getInstance().isPlaced(player.getUuid(), worldId, pos.x, pos.y, pos.z);
    }

    /**
     * How tall the column of {@code blockId} through {@code pos} stands: the block itself and the run of the same
     * block straight above and below it, at most 64.
     */
    static int columnOf(@Nonnull Store<EntityStore> store, @Nonnull String blockId, @Nonnull Vector3i pos) {
        World world = store.getExternalData().getWorld();
        if (world == null) {
            return 1;
        }
        int wanted = BlockType.getAssetMap().getIndex(blockId);
        SectionBlockCursor cursor = SectionBlockCursor.of(world);
        int count = 1;
        for (int dy = 1; dy <= 32 && cursor.blockId(pos.x, pos.y + dy, pos.z) == wanted; dy++) {
            count++;
        }
        for (int dy = 1; dy <= 32 && cursor.blockId(pos.x, pos.y - dy, pos.z) == wanted; dy++) {
            count++;
        }
        return count;
    }

    @Nullable
    static UUID playerId(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
        return player == null ? null : player.getUuid();
    }
}
