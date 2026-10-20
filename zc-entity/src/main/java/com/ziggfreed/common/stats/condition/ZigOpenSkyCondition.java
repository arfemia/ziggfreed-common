package com.ziggfreed.common.stats.condition;

import java.time.Instant;

import javax.annotation.Nonnull;

import org.joml.Vector3d;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.server.core.modules.entity.condition.Condition;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.heightmap.HeightmapColumn;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * An entity condition: is the entity under open sky, with no block above its head in its column (the column's top
 * block, off the chunk's own heightmap, at or below its feet plus its height)? {@code Inverse} makes it "under cover",
 * which underground, in a cave or a mine, always is. A column whose chunk is not loaded counts as open sky. Registered
 * into the engine's entity condition map as {@value #TYPE_NAME} (R700).
 *
 * <pre>{@code
 * { "Id": "ZigOpenSky" }                    // under open sky
 * { "Id": "ZigOpenSky", "Inverse": true }   // underground, or under a roof
 * }</pre>
 */
public final class ZigOpenSkyCondition extends Condition {

    /** The authored {@code "Id"} name. */
    public static final String TYPE_NAME = "ZigOpenSky";

    /** How far above the feet, in blocks, a block counts as over the entity's head. */
    static final int HEAD_ROOM = 2;

    private static final class Holder {
        static final BuilderCodec<ZigOpenSkyCondition> CODEC = BuilderCodec.builder(
                        ZigOpenSkyCondition.class, ZigOpenSkyCondition::new, Condition.BASE_CODEC)
                .documentation("Holds while no block is above the entity's head in its column. With Inverse, while "
                        + "one is: underground, in a cave, under a roof.")
                .build();
    }

    /** The codec, built on first call. Only registration and a codec test invoke this. */
    @Nonnull
    public static BuilderCodec<ZigOpenSkyCondition> codec() {
        return Holder.CODEC;
    }

    /** Is a column whose top block is at {@code top} open over feet at {@code feetY}? Pure. */
    static boolean openOver(int top, double feetY) {
        return top == HeightmapColumn.NO_HEIGHT || top < Math.floor(feetY) + HEAD_ROOM;
    }

    @Override
    public boolean eval0(@Nonnull ComponentAccessor<EntityStore> componentAccessor, @Nonnull Ref<EntityStore> ref,
            @Nonnull Instant currentTime) {
        try {
            Vector3d at = Nearby.positionOf(componentAccessor, ref);
            if (at == null) {
                return true;
            }
            World world = componentAccessor.getExternalData().getWorld();
            ChunkStore chunks = world.getChunkStore();
            Ref<ChunkStore> chunk = chunks.getChunkReference(ChunkUtil.indexChunkFromBlock(at.x, at.z));
            if (chunk == null || !chunk.isValid()) {
                return true;
            }
            HeightmapColumn column = chunks.getStore().getComponent(chunk, HeightmapColumn.getComponentType());
            if (column == null) {
                return true;
            }
            return openOver(column.getHeight((int) Math.floor(at.x), (int) Math.floor(at.z)), at.y);
        } catch (Throwable t) {
            return true;
        }
    }
}
