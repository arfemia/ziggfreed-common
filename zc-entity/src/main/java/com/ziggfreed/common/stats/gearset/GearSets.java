package com.ziggfreed.common.stats.gearset;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsModule;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.cast.WorldEvictors;
import com.ziggfreed.common.stats.EquipStatBridge;
import com.ziggfreed.common.stats.EquipStatBridge.StatKey;
import com.ziggfreed.common.stats.EquipStatBridge.UtilityPut;
import com.ziggfreed.common.stats.EquippedSnapshot;
import com.ziggfreed.common.stats.StatIndexCache;
import com.ziggfreed.common.stats.gearset.GearSetDecision.SlotCounts;
import com.ziggfreed.common.stats.gearset.GearSetKeys.TierRef;
import com.ziggfreed.common.util.SafeLog;

/**
 * The gear-set ENGINE: asset-driven set bonuses over the one {@link EquipStatBridge}.
 *
 * <p>It hangs ONE {@link EquipStatBridge.AppliedListener} on the installed bridge
 * ({@link #install}), never a fourth trigger system: the bridge already watches every moment an
 * item can start or stop being worn, and its post-apply seam fires after each. On every fire the
 * engine reads what is on ({@link EquipStatBridge#equippedSnapshot}), decides which tiers of which
 * sets hold ({@link GearSetDecision} over {@link GearSetIndex}), diffs that against the entity's
 * stat map ({@link GearSetPlan}, keys {@code zigset:<setId>:<tierIndex>:<offset>}), puts and
 * removes through {@code putModifier} / {@code removeModifier} (never {@code setStatValue}), puts
 * on and takes off each tier's {@code Effect} through the {@link GearSetEffects} seam, remembers
 * what it wrote ({@link GearSetApplied}) and announces a tier that really flipped
 * ({@link GearSetEvents}).
 *
 * <p><b>The first recompute after login is a hydrate, not a flip.</b> A modifier written by a
 * previous boot may still sit on the entity, so with no row in the applied table the engine sweeps
 * from what is ACTUALLY on the stat map (every {@code zigset:} key present, read once) rather than
 * from memory, takes off every effect id any folded set names (a switched-off set included) that no
 * active tier wants (so an {@code Effect} id must be dedicated to its set), and announces nothing.
 * A world change keeps the player's row, so it announces nothing either.
 *
 * <p><b>The look follows the entity, not the row</b> ({@link #effectChanges}): every recompute puts
 * on each effect an active tier wants that the entity does not have RIGHT NOW, and takes off each
 * effect it put on last time that no active tier wants. The engine clears every effect when a player
 * dies and again when they respawn, while the row still lists the look as applied; asking the entity
 * brings the look back at the first recompute after the respawn, which {@link #onRespawned} runs,
 * with no notice, since no tier flipped. While the player is dead no look is shown at all, the
 * engine's own posture for a corpse.
 *
 * <p>World-thread throughout, try-guarded, isolated by the bridge's own listener dispatch. An
 * unregistered stat channel is named in the log once and skipped, the bridge's own discipline.
 */
public final class GearSets {

    /** One tier coming on or going off for a player. */
    public record TierFlip(@Nonnull TierRef tier, boolean active) {
    }

    /**
     * The one listener, held in a field so a re-run of {@link #install} on the same bridge is a
     * no-op under the bridge's identity dedup rather than a second recompute per equip.
     */
    private static final EquipStatBridge.AppliedListener LISTENER = GearSets::recompute;

    private static final Set<String> WARNED_UNKNOWN_STATS = ConcurrentHashMap.newKeySet();

    private static volatile EquipStatBridge bridge;

    private GearSets() {
    }

    // ==================== wiring ====================

    /** Hang the engine on {@code bridge}; a second call with the same bridge changes nothing. */
    public static void install(@Nonnull EquipStatBridge bridge) {
        GearSets.bridge = bridge;
        bridge.addAppliedListener(LISTENER);
    }

    /** What one recompute does to the look: take these off, put these on. */
    record EffectChanges(@Nonnull List<String> removes, @Nonnull List<String> applies) {
    }

    /** Fill how a tier's {@code Effect} goes on, comes off and is looked for; see {@link GearSetEffects}. */
    public static void effects(@Nullable GearSetEffects.Apply apply, @Nullable GearSetEffects.Remove remove,
            @Nullable GearSetEffects.Has has) {
        GearSetEffects.fill(apply, remove, has);
    }

    /** The bridge the engine hangs on, or null before {@link #install}. */
    @Nullable
    public static EquipStatBridge bridge() {
        return bridge;
    }

    /**
     * The sets changed under everybody (a load, a re-import, an owner reload): drop the derived
     * index and run every online player through the bridge again, each on their own world thread.
     */
    public static void onContentChanged() {
        GearSetConfig.getInstance().dropIndex();
        recomputeAllOnline();
    }

    /**
     * Run every online player through the bridge again, each on their own world thread. Before the
     * universe exists (the boot-time content load fires this too) there is nobody to run, and that
     * is not worth a line in the log.
     */
    public static void recomputeAllOnline() {
        EquipStatBridge installed = bridge;
        Universe universe = Universe.get();
        if (installed == null || universe == null) {
            return;
        }
        try {
            for (PlayerRef player : universe.getPlayers()) {
                Ref<EntityStore> ref = player == null ? null : player.getReference();
                if (ref == null || !ref.isValid()) {
                    continue;
                }
                WorldEvictors.worldOf(ref).execute(() -> recomputeThrough(installed, ref));
            }
        } catch (Throwable t) {
            SafeLog.warn("[gearset] could not recompute every online player: " + t.getMessage());
        }
    }

    /**
     * The late player-ready hydrate: the bridge's own full recompute, which fires this engine's
     * listener, on the player's world thread. Inventory components are hydrated strictly before
     * this event, so this is the safe hydrate authority, the bridge's own contract.
     */
    public static void onPlayerReady(@Nonnull PlayerReadyEvent event) {
        EquipStatBridge installed = bridge;
        if (installed == null) {
            return;
        }
        try {
            Player player = event.getPlayer();
            Ref<EntityStore> ref = event.getPlayerRef();
            World world = player == null ? null : player.getWorld();
            if (world == null || ref == null) {
                return;
            }
            world.execute(() -> recomputeThrough(installed, ref));
        } catch (Throwable t) {
            SafeLog.warn("[gearset] player-ready recompute failed: " + t.getMessage());
        }
    }

    /**
     * The player respawned (their {@code DeathComponent} came off, see
     * {@link GearSetLifecycleSystems.Respawned}): run them through the bridge again on their world
     * thread, AFTER the engine's own respawn systems have cleared their effects, so the recompute
     * finds the look missing and puts it back. Deferred rather than run inline because a respawn
     * reaction runs inside the store's component-removal dispatch, beside the clear itself.
     */
    public static void onRespawned(@Nonnull Ref<EntityStore> ref) {
        EquipStatBridge installed = bridge;
        if (installed == null) {
            return;
        }
        try {
            WorldEvictors.worldOf(ref).execute(() -> recomputeThrough(installed, ref));
        } catch (Throwable t) {
            SafeLog.warn("[gearset] respawn recompute failed: " + t.getMessage());
        }
    }

    /**
     * Forget the departing player's last write, the first of the row's two evictions: the row is
     * keyed by player alone and stays true across a world change (see {@link GearSetApplied}), so no
     * world unload touches it, and {@code WorldEvictors} serves this engine only as
     * {@code worldOf}, the thread a recompute runs on. The engine fires this event BEFORE the entity
     * leaves its store, so a recompute already queued on the world thread can write the row back;
     * {@link #onEntityRemoved} is the second eviction, which closes that gap.
     */
    public static void onPlayerDisconnect(@Nonnull PlayerDisconnectEvent event) {
        try {
            PlayerRef playerRef = event.getPlayerRef();
            UUID uuid = playerRef == null ? null : playerRef.getUuid();
            if (uuid != null) {
                GearSetApplied.forget(uuid);
            }
        } catch (Throwable t) {
            SafeLog.warn("[gearset] player-disconnect eviction failed: " + t.getMessage());
        }
    }

    /**
     * The player's entity left its store (see {@link GearSetLifecycleSystems.Left}). Anything but an
     * {@code UNLOAD} ends the entity for good, so its row goes; an {@code UNLOAD} is a world change
     * (the holder moves to the next world's store) and keeps it. This runs on the world thread as the
     * entity goes, after any recompute queued before the disconnect, and nothing can recompute an
     * entity that is no longer in a store, so no row outlives the session. Forgetting is always safe:
     * the next recompute is then a hydrate, which reads what is really on the entity.
     */
    static void onEntityRemoved(@Nullable UUID playerId, @Nonnull RemoveReason reason) {
        if (playerId != null && reason != RemoveReason.UNLOAD) {
            GearSetApplied.forget(playerId);
        }
    }

    private static void recomputeThrough(@Nonnull EquipStatBridge installed, @Nonnull Ref<EntityStore> ref) {
        if (ref.isValid()) {
            installed.recomputeAll(ref.getStore(), ref);
        }
    }

    // ==================== the recompute ====================

    /** The listener body: what the bridge just applied for, decided, diffed, written and announced. */
    public static void recompute(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        try {
            EntityStatMap statMap = store.getComponent(ref, EntityStatsModule.get().getEntityStatMapComponentType());
            PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
            UUID playerId = player == null ? null : player.getUuid();
            if (statMap == null || playerId == null) {
                return;
            }
            GearSetIndex index = GearSetConfig.getInstance().index();
            EquippedSnapshot snapshot = EquipStatBridge.equippedSnapshot(store, ref);
            GearSetApplied.Applied previous = GearSetApplied.get(playerId);
            boolean hydrate = previous == null;
            boolean dead = store.getComponent(ref, DeathComponent.getComponentType()) != null;

            // Decide.
            List<GearSetPlan.Desired> desired = new ArrayList<>();
            Set<String> desiredEffects = new LinkedHashSet<>();
            Set<TierRef> active = new LinkedHashSet<>();
            Map<String, SlotCounts> countsBySet = new LinkedHashMap<>();
            for (GearSetAsset set : index.candidates(snapshot)) {
                SlotCounts counts = GearSetDecision.count(set, snapshot);
                countsBySet.put(GearSetKeys.setId(set.getId()), counts);
                List<GearSetAsset.Tier> tiers = set.tiers();
                for (int tierIndex : GearSetDecision.activeTiers(set, counts)) {
                    GearSetAsset.Tier tier = tiers.get(tierIndex);
                    TierRef tierRef = new TierRef(set.getId(), tierIndex);
                    active.add(tierRef);
                    desired.add(new GearSetPlan.Desired(tierRef,
                            GearSetPlan.resolve(tier, StatIndexCache::resolve, GearSets::warnUnknownStatOnce)));
                    String effect = tier.effectId();
                    if (effect != null) {
                        desiredEffects.add(effect);
                    }
                }
            }

            // Stats: diff against the map, sweep what was written last time or stranded earlier.
            Collection<TierRef> lastWritten = hydrate ? List.of() : previous.writtenTiers();
            Collection<StatKey> strays = hydrate ? presentKeys(statMap) : List.of();
            GearSetPlan.Plan plan = GearSetPlan.plan(desired, lastWritten, strays, statMap.size(),
                    (index0, key) -> existingStatic(statMap, index0, key));
            for (UtilityPut put : plan.puts()) {
                statMap.putModifier(put.statIndex, put.key, put.modifier);
            }
            for (StatKey remove : plan.removes()) {
                statMap.removeModifier(remove.statIndex, remove.key);
            }

            // Effects: see effectChanges. A hydrate cannot know what it put on before, so it answers
            // for every effect id any folded set names, which is why a set's effect id must be
            // dedicated to the set.
            Set<String> shown = shownEffects(desiredEffects, dead);
            Set<String> previousEffects = hydrate ? index.allEffectIds() : previous.effects();
            EffectChanges changes = effectChanges(previousEffects, shown,
                    effect -> GearSetEffects.has(store, ref, effect));
            for (String effect : changes.removes()) {
                GearSetEffects.remove(store, ref, effect);
            }
            for (String effect : changes.applies()) {
                GearSetEffects.apply(store, ref, effect);
            }

            GearSetApplied.put(playerId, new GearSetApplied.Applied(GearSetPlan.tiersOf(desired),
                    shown, active));

            for (TierFlip flip : announcements(previous, active)) {
                GearSetAsset set = GearSetConfig.getInstance().resolve(flip.tier().setId());
                if (set == null) {
                    continue;
                }
                SlotCounts counts = countsBySet.get(flip.tier().setId());
                GearSetEvents.fireTierChanged(playerId, player, set, flip.tier().tierIndex(), flip.active(),
                        counts == null ? 0 : counts.pieces());
            }
        } catch (Throwable t) {
            SafeLog.warn("[gearset] recompute failed: " + t.getMessage());
        }
    }

    /** The looks the entity should carry now: every desired one, or none while it is dead. Pure. */
    @Nonnull
    static Set<String> shownEffects(@Nonnull Set<String> desired, boolean dead) {
        return dead ? Set.of() : desired;
    }

    /**
     * The effect rule, pure. Off: every effect in {@code previous} (what the engine put on last
     * time; on a hydrate, every effect id any folded set names) that {@code shown} does not hold.
     * On: every effect in {@code shown} the entity does not have right now ({@code has}), whatever
     * the row says, so a look something else cleared comes back at the next recompute and one
     * already on is never put on twice. Each list keeps the order given.
     */
    @Nonnull
    static EffectChanges effectChanges(@Nonnull Collection<String> previous, @Nonnull Collection<String> shown,
            @Nonnull Predicate<String> has) {
        List<String> removes = new ArrayList<>();
        for (String effect : previous) {
            if (!shown.contains(effect)) {
                removes.add(effect);
            }
        }
        List<String> applies = new ArrayList<>();
        for (String effect : shown) {
            if (!has.test(effect)) {
                applies.add(effect);
            }
        }
        return new EffectChanges(removes, applies);
    }

    /**
     * What one recompute announces: nothing on the first recompute since login (no row, a hydrate,
     * not a flip), otherwise every tier that really flipped since the player's last write. The row
     * is the player's own whatever world they are in, so a recompute after a world change that finds
     * the same tiers active announces nothing. Pure.
     */
    @Nonnull
    static List<TierFlip> announcements(@Nullable GearSetApplied.Applied previous, @Nonnull Collection<TierRef> active) {
        return previous == null ? List.of() : flips(previous.activeTiers(), active);
    }

    /**
     * Which tiers changed between two recomputes: every tier in {@code before} and not in
     * {@code now} went off, every tier in {@code now} and not in {@code before} came on;
     * deactivations first, each group in the order given. Pure.
     */
    @Nonnull
    public static List<TierFlip> flips(@Nonnull Collection<TierRef> before, @Nonnull Collection<TierRef> now) {
        List<TierFlip> out = new ArrayList<>();
        for (TierRef tier : before) {
            if (!now.contains(tier)) {
                out.add(new TierFlip(tier, false));
            }
        }
        for (TierRef tier : now) {
            if (!before.contains(tier)) {
                out.add(new TierFlip(tier, true));
            }
        }
        return out;
    }

    /** Every key of this engine's actually on the stat map, on any index: the once-per-login read. */
    @Nonnull
    static List<StatKey> presentKeys(@Nonnull EntityStatMap statMap) {
        List<StatKey> out = new ArrayList<>();
        int size = statMap.size();
        for (int i = 0; i < size; i++) {
            EntityStatValue value = statMap.get(i);
            Map<String, Modifier> modifiers = value == null ? null : value.getModifiers();
            if (modifiers == null) {
                continue;
            }
            for (String key : modifiers.keySet()) {
                if (GearSetKeys.isOurs(key)) {
                    out.add(new StatKey(i, key));
                }
            }
        }
        return out;
    }

    @Nullable
    private static StaticModifier existingStatic(@Nonnull EntityStatMap statMap, int index, @Nonnull String key) {
        Modifier existing = statMap.getModifier(index, key);
        return existing instanceof StaticModifier sm ? sm : null;
    }

    private static void warnUnknownStatOnce(@Nonnull String statId) {
        if (WARNED_UNKNOWN_STATS.add(statId)) {
            SafeLog.warn("[gearset] unknown stat channel '" + statId + "' in a gear set's StatModifiers, skipped "
                    + "(not registered yet, or misspelled)");
        }
    }
}
