package com.ziggfreed.common.instance.dungeon;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.WorldConfig;
import com.hypixel.hytale.server.core.universe.world.spawn.ISpawnProvider;
import com.ziggfreed.common.codec.Vec3;
import com.ziggfreed.common.instance.world.CopyKey;

/**
 * A dungeon copy's spawn provider: it answers each player with their own area, so a member who arrives
 * through the copy's spawn point, or respawns after a death, lands in their party's area and never in
 * another party's. Set on every copy as it opens ({@link #install}, from {@code InstanceWorlds.spawn}'s config
 * step), it wraps whatever provider the template brought, which answers anyone who holds no area here (an
 * admin who teleports in, say). It never writes a player's respawn points: those are kept by world name, and
 * copy names repeat.
 *
 * <p>It carries the copy's {@link AreaLayout}, fixed when the copy opened, so the copy's saved config says
 * where its areas are. Its type id is {@link #TYPE}, registered on the engine's spawn provider codec at
 * setup, so a copy's config saves and loads like a native one.
 */
public final class AreaSpawnProvider implements ISpawnProvider {

    /** The type id a copy's saved config names this provider by. */
    public static final String TYPE = "ZigDungeonArea";

    @Nullable private String dungeon;
    @Nullable private Integer areas;
    @Nullable private Integer spacing;
    @Nullable private Vec3 entryOffset;
    @Nullable private Float entryYaw;
    @Nullable private ISpawnProvider fallback;

    /** The layout the fields say, built on first use. */
    @Nullable private volatile AreaLayout layout;

    public static final BuilderCodec<AreaSpawnProvider> CODEC = BuilderCodec.builder(AreaSpawnProvider.class,
                    AreaSpawnProvider::new)
            .documentation("A dungeon copy's spawn provider: each player lands in, and respawns in, the area their "
                    + "party holds in the copy. Set by ziggfreed-common on every dungeon copy it opens; a template "
                    + "never names it.")
            .append(new KeyedCodec<>("Dungeon", Codec.STRING, false),
                    (o, v) -> o.dungeon = v, o -> o.dungeon)
            .documentation("The dungeon the copy belongs to, by its id.").add()
            .append(new KeyedCodec<>("Areas", Codec.INTEGER, false),
                    (o, v) -> o.areas = v, o -> o.areas)
            .documentation("How many areas the copy holds, fixed when it opened. Unauthored means 1.").add()
            .append(new KeyedCodec<>("Spacing", Codec.INTEGER, false),
                    (o, v) -> o.spacing = v, o -> o.spacing)
            .documentation("How far apart the copy's areas sit, in blocks, fixed when it opened. Unauthored means "
                    + "2048.").add()
            .append(new KeyedCodec<>("EntryOffset", Vec3.CODEC, false),
                    (o, v) -> o.entryOffset = v, o -> o.entryOffset)
            .documentation("Where a player lands in their area, in blocks from the spot its prefab is pasted at. "
                    + "Unauthored means that spot itself.").add()
            .append(new KeyedCodec<>("EntryYaw", Codec.FLOAT, false),
                    (o, v) -> o.entryYaw = v, o -> o.entryYaw)
            .documentation("Which way a player faces in their area, in degrees, fixed when the copy opened: 0 faces "
                    + "north (toward negative Z), 180 south. Unauthored means 0.").add()
            .append(new KeyedCodec<>("Fallback", ISpawnProvider.CODEC, false),
                    (o, v) -> o.fallback = v, o -> o.fallback)
            .documentation("The template's own spawn provider, which answers a player who holds no area in the "
                    + "copy. Unauthored, such a player lands in the first area.").add()
            .build();

    public AreaSpawnProvider() {
    }

    AreaSpawnProvider(@Nonnull AreaLayout layout, @Nullable ISpawnProvider fallback) {
        this.dungeon = layout.dungeon();
        this.areas = layout.areas();
        this.spacing = layout.spacing();
        this.entryOffset = Vec3.of(layout.entryX(), layout.entryY(), layout.entryZ());
        this.entryYaw = layout.entryYaw();
        this.fallback = fallback;
        this.layout = layout;
    }

    /**
     * Set a copy's spawn provider to one over {@code layout}, wrapping the provider its template brought. Run in
     * the copy's config step, before the world starts.
     */
    public static void install(@Nonnull WorldConfig config, @Nonnull AreaLayout layout) {
        ISpawnProvider template = config.getSpawnProvider();
        config.setSpawnProvider(new AreaSpawnProvider(layout, template instanceof AreaSpawnProvider ? null : template));
    }

    /** The copy's area layout. */
    @Nonnull
    public AreaLayout layout() {
        AreaLayout known = layout;
        if (known == null) {
            Vec3 entry = entryOffset;
            known = new AreaLayout(dungeon == null ? "" : dungeon, areas == null ? 1 : areas,
                    spacing == null ? DungeonAsset.DEFAULT_SPACING : spacing,
                    entry == null ? 0.0 : entry.effectiveX(), entry == null ? 0.0 : entry.effectiveY(),
                    entry == null ? 0.0 : entry.effectiveZ(), entryYaw == null ? 0f : entryYaw);
            layout = known;
        }
        return known;
    }

    /** The player's own area of this copy when they hold one, else the template's answer, else the first area. */
    @Nonnull
    @Override
    public CompletableFuture<Transform> getSpawnPointAsync(@Nonnull World world, @Nonnull UUID uuid) {
        AreaLayout copy = layout();
        CopyKey area = AreaMembers.shared().areaOf(uuid);
        if (area != null && copy.has(area.area())
                && area.world().equals(world.getName().toLowerCase(Locale.ROOT))) {
            return CompletableFuture.completedFuture(copy.arrival(area.area()));
        }
        ISpawnProvider template = fallback;
        if (template != null) {
            return template.getSpawnPointAsync(world, uuid);
        }
        return CompletableFuture.completedFuture(copy.arrival(0));
    }

    /** Every area's landing spot, in area order. */
    @Override
    // DEPRECATION-KEPT: ISpawnProvider declares this abstract, so every provider must implement it; no successor exists to implement instead
    public Transform[] getSpawnPoints() {
        AreaLayout copy = layout();
        Transform[] points = new Transform[copy.areas()];
        for (int i = 0; i < points.length; i++) {
            points[i] = copy.arrival(i);
        }
        return points;
    }

    /** Is {@code position} within {@code distance} of any area's landing spot? */
    @Override
    public boolean isWithinSpawnDistance(@Nonnull Vector3d position, double distance) {
        AreaLayout copy = layout();
        double limit = distance * distance;
        for (int i = 0; i < copy.areas(); i++) {
            if (position.distanceSquared(copy.arrival(i).getPosition()) < limit) {
                return true;
            }
        }
        return false;
    }
}
