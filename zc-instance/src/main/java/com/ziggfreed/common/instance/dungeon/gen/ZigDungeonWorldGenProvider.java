package com.ziggfreed.common.instance.dungeon.gen;

import javax.annotation.Nonnull;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.universe.world.worldgen.IWorldGen;
import com.hypixel.hytale.server.core.universe.world.worldgen.provider.IWorldGenProvider;

/**
 * SPIKE (PD2): the world generator Type {@code ZigDungeon}, registered into the engine's own
 * {@code IWorldGenProvider.CODEC} at setup, so a copy's config can name it ({@code "WorldGen": {"Type":
 * "ZigDungeon"}}) or a spawn's config step can set it. It carries no fields yet; the build card's version reads
 * the registered layouts by run region.
 */
public final class ZigDungeonWorldGenProvider implements IWorldGenProvider {

    /** The Type key in {@code IWorldGenProvider.CODEC}. */
    public static final String TYPE = "ZigDungeon";

    public static final BuilderCodec<ZigDungeonWorldGenProvider> CODEC = BuilderCodec
            .builder(ZigDungeonWorldGenProvider.class, ZigDungeonWorldGenProvider::new)
            .build();

    @Nonnull
    @Override
    public IWorldGen getGenerator() {
        return new ZigDungeonWorldGen();
    }

    @Nonnull
    @Override
    public String toString() {
        return "ZigDungeonWorldGenProvider{}";
    }
}
