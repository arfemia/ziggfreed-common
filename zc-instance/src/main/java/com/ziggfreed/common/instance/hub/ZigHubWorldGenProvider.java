package com.ziggfreed.common.instance.hub;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.worldgen.IWorldGen;
import com.hypixel.hytale.server.core.universe.world.worldgen.provider.IWorldGenProvider;
import com.ziggfreed.common.instance.dungeon.layout.asset.PrefabPoolIndex;
import com.ziggfreed.common.util.SafeLog;

/**
 * The world generator Type {@code ZigHub}, registered into the engine's own {@code IWorldGenProvider.CODEC} at setup,
 * which the hub's template names ({@code "WorldGen": {"Type": "ZigHub"}}). Each copy decodes its own, so this object
 * is the copy's: it lays the copy out once, the first time anything asks ({@link #plan()}, at the latest as the copy's
 * generator is made, before any chunk generates), and its generator paints every chunk of the hub's ground from that
 * plan through the library's one generator route, the generated dungeon's ({@code PrefabPaint}). It carries no fields
 * of its own, so no chunk files ship: the hub is built afresh each time a copy opens.
 */
public final class ZigHubWorldGenProvider implements IWorldGenProvider {

    /** The Type key in {@code IWorldGenProvider.CODEC}. */
    public static final String TYPE = "ZigHub";

    public static final BuilderCodec<ZigHubWorldGenProvider> CODEC = BuilderCodec
            .builder(ZigHubWorldGenProvider.class, ZigHubWorldGenProvider::new)
            .documentation("The temple hub's world: empty, but for the districts in force as each copy opens, each "
                    + "written onto its lot by ziggfreed-common as the ground loads. Named by ziggfreed-common's hub "
                    + "template, ZiggfreedCommon_Hub.")
            .build();

    @Nullable private volatile HubPlan plan;
    @Nullable private volatile PrefabPoolIndex pools;

    public ZigHubWorldGenProvider() {
    }

    @Nonnull
    @Override
    public IWorldGen getGenerator() {
        plan();
        return new ZigHubWorldGen(this);
    }

    /** The hub provider {@code world} runs on, or null when it runs on another generator. */
    @Nullable
    public static ZigHubWorldGenProvider of(@Nullable World world) {
        return world != null && world.getWorldConfig().getWorldGenProvider() instanceof ZigHubWorldGenProvider hub
                ? hub : null;
    }

    /**
     * The copy's layout, made the first time it is asked for from the districts in force then, and kept for the copy's
     * life. A layout that cannot be made is warned about and the copy gets the bare landing, so it still opens.
     */
    @Nonnull
    public HubPlan plan() {
        HubPlan known = plan;
        if (known == null) {
            synchronized (this) {
                known = plan;
                if (known == null) {
                    known = build();
                    plan = known;
                }
            }
        }
        return known;
    }

    /** The copy's prefab pools, read once per copy (packs do not reload under a running copy). */
    @Nonnull
    PrefabPoolIndex pools() {
        PrefabPoolIndex known = pools;
        if (known == null) {
            synchronized (this) {
                known = pools;
                if (known == null) {
                    known = PrefabPoolIndex.live();
                    pools = known;
                }
            }
        }
        return known;
    }

    @Nonnull
    private HubPlan build() {
        try {
            HubPlan made = HubPlan.build(HubDistrictConfig.getInstance(), pools());
            SafeLog.info("[hub] a hub copy is laid out: " + made.summary());
            return made;
        } catch (Throwable t) {
            SafeLog.warn("[hub] a hub copy could not be laid out from its districts; it opens with its landing alone",
                    t);
            return HubPlan.bare();
        }
    }

    @Nonnull
    @Override
    public String toString() {
        return "ZigHubWorldGenProvider{laidOut=" + (plan != null) + "}";
    }
}
