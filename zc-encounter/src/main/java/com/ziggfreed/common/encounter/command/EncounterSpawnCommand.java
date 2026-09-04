package com.ziggfreed.common.encounter.command;

import java.util.concurrent.CompletableFuture;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.arguments.system.OptionalArg;
import com.hypixel.hytale.server.core.command.system.arguments.system.RequiredArg;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractAsyncCommand;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.encounter.event.Encounters;
import com.ziggfreed.common.encounter.run.EncounterSpawner;
import com.ziggfreed.common.encounter.run.SpawnOptions;
import com.ziggfreed.common.util.SafeLog;
import org.joml.Vector3d;

/**
 * Stand a script up: at the sender when they are a player, at the named world's spawn point from
 * the console, or wherever {@code --x= --y= --z=} say. The chunk under the spot is brought up
 * ticking first, so a console spawn into a chunk nobody is standing in takes.
 */
final class EncounterSpawnCommand extends AbstractAsyncCommand {

    private static final String DEFAULT_WORLD = "default";

    private final RequiredArg<String> assetArg;
    private final OptionalArg<String> worldArg;
    private final OptionalArg<Double> xArg;
    private final OptionalArg<Double> yArg;
    private final OptionalArg<Double> zArg;

    EncounterSpawnCommand() {
        super(EncounterCommandLine.SPAWN, EncounterAdminMessages.desc(EncounterCommandLine.SPAWN));
        this.assetArg = withRequiredArg(EncounterCommandLine.ARG_ASSET, EncounterAdminMessages.desc("arg.asset"),
                ArgTypes.STRING);
        this.worldArg = withOptionalArg(EncounterCommandLine.ARG_WORLD, EncounterAdminMessages.desc("arg.world"),
                ArgTypes.STRING);
        this.xArg = withOptionalArg(EncounterCommandLine.ARG_X, EncounterAdminMessages.desc("arg.x"), ArgTypes.DOUBLE);
        this.yArg = withOptionalArg(EncounterCommandLine.ARG_Y, EncounterAdminMessages.desc("arg.y"), ArgTypes.DOUBLE);
        this.zArg = withOptionalArg(EncounterCommandLine.ARG_Z, EncounterAdminMessages.desc("arg.z"), ArgTypes.DOUBLE);
    }

    @Override
    @Nonnull
    protected CompletableFuture<Void> executeAsync(@Nonnull CommandContext ctx) {
        String asset = assetArg.get(ctx);
        Ref<EntityStore> sender = ctx.isPlayer() ? ctx.senderAsPlayerRef() : null;
        World world = resolveWorld(ctx, sender);
        if (world == null) {
            return CompletableFuture.completedFuture(null);
        }
        Double x = xArg.provided(ctx) ? xArg.get(ctx) : null;
        Double y = yArg.provided(ctx) ? yArg.get(ctx) : null;
        Double z = zArg.provided(ctx) ? zArg.get(ctx) : null;
        // Answered only once the spawn itself has been reported, which happens after the chunk has
        // come up; the world-thread hop below returns well before that.
        CompletableFuture<Void> done = new CompletableFuture<>();
        runAsync(ctx, () -> spawn(ctx, world, asset, sender, x, y, z, done), world);
        return done;
    }

    @Nullable
    private World resolveWorld(@Nonnull CommandContext ctx, @Nullable Ref<EntityStore> sender) {
        if (worldArg.provided(ctx)) {
            String name = worldArg.get(ctx);
            World world = Universe.get().getWorld(name);
            if (world == null) {
                EncounterAdminMessages.refused(ctx, "spawn.world.unknown", name == null ? "" : name);
            }
            return world;
        }
        if (sender != null && sender.isValid()) {
            return sender.getStore().getExternalData().getWorld();
        }
        World world = Universe.get().getWorld(DEFAULT_WORLD);
        if (world == null) {
            EncounterAdminMessages.refused(ctx, "spawn.world.needed");
        }
        return world;
    }

    /** On the world thread: place, bring the chunk up, spawn, and say how it went. */
    private static void spawn(@Nonnull CommandContext ctx, @Nonnull World world, @Nonnull String asset,
            @Nullable Ref<EntityStore> sender, @Nullable Double x, @Nullable Double y, @Nullable Double z,
            @Nonnull CompletableFuture<Void> done) {
        try {
            Store<EntityStore> store = world.getEntityStore().getStore();
            TransformComponent at = placement(store, world, sender, x, y, z);
            if (at == null) {
                EncounterAdminMessages.refused(ctx, "spawn.position.needed");
                done.complete(null);
                return;
            }
            EncounterSpawner.spawnWhenLoaded(world, asset, at, SpawnOptions.defaults())
                    .whenComplete((outcome, error) -> {
                        try {
                            report(ctx, world, asset, at, outcome);
                        } finally {
                            done.complete(null);
                        }
                    });
        } catch (Throwable t) {
            SafeLog.warn(Encounters.LOG_PREFIX + " /zigencounter spawn '" + asset + "' failed", t);
            EncounterAdminMessages.refused(ctx, "spawn.failed", asset);
            done.complete(null);
        }
    }

    private static void report(@Nonnull CommandContext ctx, @Nonnull World world, @Nonnull String asset,
            @Nonnull TransformComponent at, @Nullable EncounterSpawner.Outcome outcome) {
        if (outcome != null && outcome.spawned()) {
            Vector3d p = at.getPosition();
            EncounterAdminMessages.done(ctx, "spawn.done", asset, world.getName(), Math.round(p.x), Math.round(p.y),
                    Math.round(p.z));
            return;
        }
        EncounterSpawner.Refusal refusal = outcome == null || outcome.refusal() == null
                ? EncounterSpawner.Refusal.ENGINE_FAILED : outcome.refusal();
        switch (refusal) {
            case UNKNOWN_ASSET -> EncounterAdminMessages.refused(ctx, "spawn.unknown", asset);
            case NOT_SPAWNABLE -> EncounterAdminMessages.refused(ctx, "spawn.notspawnable", asset);
            case DISABLED -> EncounterAdminMessages.refused(ctx, "spawn.disabled", asset);
            case ENGINE_FAILED -> EncounterAdminMessages.refused(ctx, "spawn.failed", asset);
        }
    }

    /** Explicit coordinates, else the sender's own transform, else the world's first spawn point. */
    @Nullable
    private static TransformComponent placement(@Nonnull Store<EntityStore> store, @Nonnull World world,
            @Nullable Ref<EntityStore> sender, @Nullable Double x, @Nullable Double y, @Nullable Double z) {
        if (x != null && y != null && z != null) {
            return new TransformComponent(new Vector3d(x, y, z), Rotation3f.IDENTITY);
        }
        if (sender != null && sender.isValid() && sender.getStore() == store) {
            TransformComponent own = store.getComponent(sender, TransformComponent.getComponentType());
            if (own != null) {
                return own.clone();
            }
        }
        try {
            Transform[] points = world.getWorldConfig().getSpawnProvider().getSpawnPoints();
            if (points != null && points.length > 0 && points[0] != null) {
                Transform spawn = points[0];
                return new TransformComponent(spawn.getPosition(), spawn.getRotation());
            }
        } catch (Throwable ignored) {
            // No spawn provider: fall through to the refusal.
        }
        return null;
    }
}
