package com.ziggfreed.common.instance.dungeon;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.prefab.PrefabStore;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.spawn.ISpawnProvider;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.instance.world.CopyKey;
import com.ziggfreed.common.instance.world.InstanceWorlds;
import com.ziggfreed.common.util.SafeLog;

/**
 * The one way into a dungeon: the gate, a place from the router, the copy spawned or joined, the area pasted
 * while the player loads, and the teleport. {@code /zigdungeon enter} runs it, and so does any other entry (a
 * block's interaction, a conversation), so every one keeps the same rules.
 *
 * <p><b>The steps</b>, on the entering player's current world thread:
 * <ol>
 *   <li>the dungeon is loaded and open (switched on, in season), its prefab is shipped, and its audit finds no
 *       ERROR ({@link DungeonValidator#entryErrors});</li>
 *   <li>the player is in no area and on the way into none;</li>
 *   <li>the router reserves a place, filed with {@link AreaMembers} as one step;</li>
 *   <li>a new copy is spawned through {@code InstanceWorlds.spawn}, its config step setting the area-aware
 *       spawn provider over the copy's {@link AreaLayout}; or the copy is joined, live or still spawning;</li>
 *   <li>the player goes through the engine's loading teleport with a future that completes once the copy is up
 *       and their area pasted ({@link Areas#prepare}), landing at their area's entry point, with their own
 *       return point where they stood.</li>
 * </ol>
 *
 * <p><b>A copy still spawning takes parties.</b> The router fills only copies it is told are live, so the
 * router asks through {@link #PROBE}, which also counts a copy this class is still spawning: a party arriving
 * during a copy's first spawn waits on that spawn instead of opening another copy.
 */
public final class DungeonEntry {

    private static final String LOG = "[dungeon] ";

    /** What an attempt to enter came to. */
    public enum Result {
        /** The player is on the way into {@link Outcome#area()}. */
        SENT,
        /** No dungeon of that id is loaded. */
        UNKNOWN,
        /** The dungeon is switched off or out of season. */
        CLOSED,
        /** The dungeon names no prefab. */
        NO_PREFAB,
        /** No loaded pack ships the dungeon's prefab. */
        MISSING_PREFAB,
        /** The dungeon's audit finds an ERROR in it ({@link DungeonValidator#entryErrors}); the log names it. */
        AUDIT_ERROR,
        /** The player is already on the way into an area. */
        ON_THE_WAY,
        /** The player is already in an area. */
        INSIDE,
        /** The party's area is full. */
        FULL,
        /** The copy the router chose closed before the player could be sent. */
        COPY_GONE,
        /** The copy could not be opened, or the engine refused the teleport. */
        FAILED
    }

    /** An attempt's answer: what came of it, the dungeon and its prefab id when known, and the area reserved when sent. */
    public record Outcome(@Nonnull Result result, @Nullable DungeonAsset dungeon, @Nullable String prefab,
            @Nullable CopyKey area) {
    }

    /** A copy being spawned now: the spawn's future, and the layout it opens with. */
    private record Opening(@Nonnull CompletableFuture<World> world, @Nonnull AreaLayout layout) {
    }

    /** Every copy being spawned through here, by lower-cased world name. */
    private static final Map<String, Opening> OPENING = new ConcurrentHashMap<>();

    /** The router's questions, answered by the server, with a copy still spawning here counted as live. */
    public static final CopyRouter.WorldProbe PROBE = new OpeningAware();

    private DungeonEntry() {
    }

    /**
     * Send {@code player} into dungeon {@code dungeonId} as party {@code partyKey} ({@link CopyRouter#partyKey}).
     * Run on the player's current world thread; {@code ref} and {@code store} are theirs there.
     */
    @Nonnull
    public static Outcome enter(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull PlayerRef player, @Nonnull World from, @Nonnull String dungeonId, @Nonnull String partyKey) {
        return enter(ref, store, player, from, dungeonId, partyKey, false);
    }

    /**
     * Send {@code player} into dungeon {@code dungeonId} alone: a party of one, given an empty area even where the
     * dungeon's areas are not party-locked (where later parties may still be put in with them). The admin's way in.
     */
    @Nonnull
    public static Outcome enterAlone(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull PlayerRef player, @Nonnull World from, @Nonnull String dungeonId) {
        return enter(ref, store, player, from, dungeonId, CopyRouter.partyKey(null, player.getUuid()), true);
    }

    @Nonnull
    private static Outcome enter(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull PlayerRef player, @Nonnull World from, @Nonnull String dungeonId, @Nonnull String partyKey,
            boolean emptyArea) {
        String id = dungeonId.trim().toLowerCase(Locale.ROOT);
        DungeonAsset dungeon = id.isEmpty() ? null : DungeonConfig.getInstance().resolve(id);
        if (dungeon == null) {
            return new Outcome(Result.UNKNOWN, null, null, null);
        }
        if (!dungeon.isOpen()) {
            return new Outcome(Result.CLOSED, dungeon, null, null);
        }
        String prefabId = dungeon.prefabPath();
        if (prefabId == null) {
            return new Outcome(Result.NO_PREFAB, dungeon, null, null);
        }
        Path prefab = findPrefab(prefabId);
        if (prefab == null) {
            return new Outcome(Result.MISSING_PREFAB, dungeon, prefabId, null);
        }
        if (!DungeonValidator.entryErrors(id, dungeon).isEmpty()) {
            return new Outcome(Result.AUDIT_ERROR, dungeon, prefabId, null);
        }
        UUID uuid = player.getUuid();
        AreaMembers.Standing standing = AreaMembers.shared().standing(uuid);
        if (standing == AreaMembers.Standing.ON_THE_WAY) {
            return new Outcome(Result.ON_THE_WAY, dungeon, prefabId, null);
        }
        if (standing == AreaMembers.Standing.PRESENT) {
            return new Outcome(Result.INSIDE, dungeon, prefabId, null);
        }

        long now = System.currentTimeMillis();
        CompletableFuture<World> ready = new CompletableFuture<>();
        CopyRouter.Rules rules = dungeon.routerRules(id);
        if (emptyArea && !rules.partyLocked()) {
            rules = new CopyRouter.Rules(rules.dungeon(), rules.areasPerCopy(), rules.maxPlayers(), true,
                    rules.emptyCloseMs());
        }
        CopyRouter.Placement placement = AreaMembers.shared().reserve(partyKey, uuid, rules, now, PROBE, ready);
        if (placement == null) {
            return new Outcome(Result.ON_THE_WAY, dungeon, prefabId, null);
        }
        CopyKey area = placement.area();
        if (area == null) {
            return new Outcome(Result.FULL, dungeon, prefabId, null);
        }

        // The place is reserved and filed: from here, anything that throws fails it at once, rather than leaving
        // the player "on the way" until the ticker's limit.
        Transform here;
        AreaLayout layout;
        try {
            here = standingAt(ref, store, player);
            CompletableFuture<World> copy;
            if (placement.opensCopy()) {
                layout = AreaLayout.of(id, dungeon);
                copy = open(dungeon, area.world(), from, here, layout);
            } else {
                Opening opening = OPENING.get(area.world());
                World live = opening == null ? LiveWorlds.world(area.world()) : null;
                if (opening == null && (live == null || !live.isAlive())) {
                    ready.completeExceptionally(new IllegalStateException("the copy " + area.world() + " is gone"));
                    return new Outcome(Result.COPY_GONE, dungeon, prefabId, area);
                }
                copy = opening != null ? opening.world() : CompletableFuture.completedFuture(live);
                layout = opening != null ? opening.layout() : layoutOf(live, id, dungeon);
            }

            AreaLayout areas = layout;
            copy.thenCompose(world -> Areas.shared().prepare(world, area, areas, prefabId, prefab)
                            .thenApply(ignored -> world))
                    .whenComplete((world, error) -> {
                        if (error != null) {
                            ready.completeExceptionally(error);
                        } else {
                            ready.complete(world);
                        }
                    });
        } catch (Throwable t) {
            SafeLog.warn(LOG + uuid + " could not be sent into " + area.world() + " area " + area.area(), t);
            ready.completeExceptionally(t);
            return new Outcome(Result.FAILED, dungeon, prefabId, area);
        }
        if (ready.isCompletedExceptionally()) {
            // The copy was refused outright (its name taken, its template unreadable): the engine's loading
            // teleport would lift the player out of their world only to put them back. The ticker gives the
            // place up.
            return new Outcome(Result.FAILED, dungeon, prefabId, area);
        }
        try {
            InstanceWorlds.teleportIn(ref, store, ready, here, layout.arrival(area.area()));
        } catch (Throwable t) {
            SafeLog.warn(LOG + player.getUuid() + " could not be sent into " + area.world() + " area " + area.area(), t);
            ready.completeExceptionally(t);
            return new Outcome(Result.FAILED, dungeon, prefabId, area);
        }
        return new Outcome(Result.SENT, dungeon, prefabId, area);
    }

    /** The path of a prefab a loaded pack ships under {@code Server/Prefabs/}, or null. */
    @Nullable
    static Path findPrefab(@Nonnull String prefabId) {
        try {
            return PrefabStore.get().findAssetPrefabPath(prefabId);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Spawn copy {@code worldName} of {@code dungeon}, its spawn provider over {@code layout}. */
    @Nonnull
    private static CompletableFuture<World> open(@Nonnull DungeonAsset dungeon, @Nonnull String worldName,
            @Nonnull World from, @Nonnull Transform returnPoint, @Nonnull AreaLayout layout) {
        CompletableFuture<World> spawn = InstanceWorlds.spawn(dungeon.templateId(), worldName, from, returnPoint,
                dungeon.emptyCloseSeconds(), config -> AreaSpawnProvider.install(config, layout));
        Opening opening = new Opening(spawn, layout);
        OPENING.put(worldName, opening);
        spawn.whenComplete((world, error) -> OPENING.remove(worldName, opening));
        return spawn;
    }

    /** A live copy's layout, from the spawn provider it opened with, else the dungeon's as it stands now. */
    @Nonnull
    private static AreaLayout layoutOf(@Nonnull World copy, @Nonnull String id, @Nonnull DungeonAsset dungeon) {
        ISpawnProvider provider = copy.getWorldConfig().getSpawnProvider();
        return provider instanceof AreaSpawnProvider areas ? areas.layout() : AreaLayout.of(id, dungeon);
    }

    /** Where the player stands now: their return point, and a new copy's. */
    @Nonnull
    private static Transform standingAt(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store,
            @Nonnull PlayerRef player) {
        TransformComponent at = store.getComponent(ref, TransformComponent.getComponentType());
        return at != null ? new Transform(at.getTransform()) : new Transform(player.getTransform());
    }

    /** {@link LiveWorlds}, with a copy still spawning here counted as live and held. */
    private static final class OpeningAware implements CopyRouter.WorldProbe {

        @Override
        public boolean isLive(@Nonnull String world) {
            return opening(world) || LiveWorlds.INSTANCE.isLive(world);
        }

        @Override
        public boolean isRegistered(@Nonnull String world) {
            return opening(world) || LiveWorlds.INSTANCE.isRegistered(world);
        }

        @Override
        public double tickAverageMs(@Nonnull String world) {
            return LiveWorlds.INSTANCE.tickAverageMs(world);
        }

        private static boolean opening(@Nonnull String world) {
            return OPENING.containsKey(world.trim().toLowerCase(Locale.ROOT));
        }
    }
}
