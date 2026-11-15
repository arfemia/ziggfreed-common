package com.ziggfreed.common.authoring;

import java.util.Locale;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3i;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.protocol.BlockPosition;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.interaction.type.InteractionCtx;
import com.ziggfreed.common.util.EntityIdentifierUtil;
import com.ziggfreed.common.util.SafeLog;
import com.ziggfreed.common.world.SectionBlockCursor;

/**
 * What an authoring tool pointed at when it was used: the block (its cell and its type id), and the entity (its ref,
 * its id, its NPC role, and the item it is when it is an item lying on the ground). Either half may be missing, and
 * both are when the click hit the air ({@link #isEmpty}).
 *
 * <p>It is what the client reported for the click, resolved on the server the way the engine resolves any chain's
 * target ({@code InteractionManager} puts the clicked cell and entity on the chain): the block is read off the world
 * here, never taken from the client. Read it at once: the entity ref is only good for the tick it was read in, and
 * {@link #NOTHING} stands in for a wizard opened with no tool (an admin command, a consumer's page).
 *
 * <p>Each reading a field takes from the world is one of its accessors, so a wizard field names one as
 * {@code WorldPick::blockId}, {@code WorldPick::npcRole}, {@code WorldPick::itemId} or {@code WorldPick::position}.
 */
public record WorldPick(@Nullable Vector3i block, @Nullable String blockId, @Nullable Ref<EntityStore> entity,
        @Nullable UUID entityId, @Nullable String npcRole, @Nullable String droppedItemId) {

    /** A pick of nothing: no tool was used, or it pointed at the air. */
    public static final WorldPick NOTHING = new WorldPick(null, null, null, null, null, null);

    /** True when the tool pointed at nothing the kit can read: no block and no entity. */
    public boolean isEmpty() {
        return blockId == null && entity == null;
    }

    /** True when the pick holds a block. */
    public boolean hasBlock() {
        return blockId != null;
    }

    /** True when the pick holds an entity (a creature, an NPC, an item on the ground, a player). */
    public boolean hasEntity() {
        return entity != null;
    }

    /**
     * The item this pick names: the item lying on the ground it points at, else the item the picked block is; null
     * for neither.
     */
    @Nullable
    public String itemId() {
        if (droppedItemId != null) {
            return droppedItemId;
        }
        if (blockId == null) {
            return null;
        }
        try {
            BlockType type = BlockType.getAssetMap().getAsset(blockId);
            Item item = type == null ? null : type.getItem();
            String id = item == null ? null : item.getId();
            return id == null || id.isBlank() ? null : id;
        } catch (Throwable t) {
            return null;
        }
    }

    /** The picked block's cell as {@code "x y z"}, for a field that keeps a spot; null for no block. */
    @Nullable
    public String position() {
        return block == null ? null : String.format(Locale.ROOT, "%d %d %d", block.x, block.y, block.z);
    }

    /**
     * Read what the chain in {@code ctx} points at. World thread, inside the interaction's tick. Never throws: a
     * reading that fails is missing from the pick.
     */
    @Nonnull
    static WorldPick read(@Nullable InteractionContext ctx) {
        if (ctx == null) {
            return NOTHING;
        }
        Vector3i cell = null;
        String blockId = null;
        try {
            BlockPosition target = ctx.getTargetBlock();
            World world = InteractionCtx.world(ctx);
            if (target != null && world != null) {
                BlockType type = BlockType.getAssetMap().getAsset(SectionBlockCursor.of(world).blockId(
                        target.x, target.y, target.z));
                String id = type == null ? null : type.getId();
                if (id != null && !id.isBlank() && !"Empty".equals(id)) {
                    cell = new Vector3i(target.x, target.y, target.z);
                    blockId = id;
                }
            }
        } catch (Throwable t) {
            SafeLog.fine("[authoring] the block a tool pointed at could not be read: " + t.getMessage());
        }
        Ref<EntityStore> entity = InteractionCtx.target(ctx);
        CommandBuffer<EntityStore> buffer = InteractionCtx.buffer(ctx);
        String role = null;
        String dropped = null;
        if (entity != null && buffer != null) {
            role = EntityIdentifierUtil.roleName(buffer, entity);
            dropped = droppedItem(buffer, entity);
        }
        return new WorldPick(cell, blockId, entity, entity == null ? null : InteractionCtx.entityId(ctx, entity),
                role == null || role.isBlank() ? null : role, dropped);
    }

    /** The item id of the item lying on the ground at {@code entity}, or null when it is no item. */
    @Nullable
    private static String droppedItem(@Nonnull CommandBuffer<EntityStore> buffer, @Nonnull Ref<EntityStore> entity) {
        try {
            ItemComponent item = buffer.getComponent(entity, ItemComponent.getComponentType());
            ItemStack stack = item == null ? null : item.getItemStack();
            String id = stack == null ? null : stack.getItemId();
            return id == null || id.isBlank() ? null : id;
        } catch (Throwable t) {
            return null;
        }
    }
}
