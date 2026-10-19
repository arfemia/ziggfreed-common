package com.ziggfreed.common.stats.gearset;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
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
import com.ziggfreed.common.LibraryOwner;
import com.ziggfreed.common.asset.PresenceRequiresCodec;
import com.ziggfreed.common.cast.WorldEvictors;
import com.ziggfreed.common.factor.FactorCondition;
import com.ziggfreed.common.factor.FactorConditions;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.factor.FactorRegistry;
import com.ziggfreed.common.factor.HytaleFactors;
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
 * what it wrote ({@link GearSetApplied}), saves the looks it answers for on the player
 * ({@link GearSetLooksComponent}) and announces a tier that really flipped, or a set whose piece
 * count rose with no flip ({@link GearSetEvents}).
 *
 * <p><b>The first recompute in a store is a hydrate, not a flip.</b> The row is forgotten whenever
 * the player's entity leaves its store ({@link #onEntityRemoved}), a disconnect and a world change
 * alike, since both remove it with {@code UNLOAD}. A modifier written by a previous boot may still
 * sit on the entity, so with no row the engine sweeps from what is ACTUALLY on the stat map (every
 * {@code zigset:} key present, read once) rather than from memory, takes off every look it answers
 * for ({@link #hydrateAnswersFor}: every effect id any folded set names, a switched-off set's
 * included, plus every id the player's {@link GearSetLooksComponent} recorded, a deleted set's
 * included) that no active tier wants (so an {@code Effect} id must be dedicated to its set), and
 * announces nothing. After a world change that is right: the stat map and the effects travel with
 * the player, no tier flipped, strays are swept from the live stat map, and the look is reconciled
 * by asking the entity.
 *
 * <p><b>The look follows the entity, not the row</b> ({@link #effectChanges}): every recompute puts
 * on each effect an active tier wants that the entity does not have RIGHT NOW, and takes off every
 * effect it answers for (put on last time, or whose remove has not yet gone through) that no active
 * tier wants. The engine clears every effect when a player dies and again when they respawn, while
 * the row still lists the look as applied; asking the entity brings the look back at the first
 * recompute after the respawn, which {@link #onRespawned} runs, with no notice, since no tier
 * flipped. While the player is dead no look is shown at all, the engine's own posture for a corpse.
 *
 * <p><b>Each worn set shows one buff-bar tile</b> while any of its tiers is active
 * ({@link GearSetBadges}): the {@code Badge} of its highest active tier naming one, else a slot of
 * the default pool, which keeps the set on its slot from recompute to recompute. A tile is an effect
 * the engine answers for exactly as it does a look: dedicated to the set, swept at a hydrate (the
 * default pool's ids included), none while dead, back after a respawn, saved in the look record.
 *
 * <p><b>A set's {@code Requires} is asked of the wearer at each recompute</b>: everything but its mod
 * presence (which its fold decided), through the {@code hytale:} readings plus every contributed and
 * asset-defined factor. A wearer it refuses has none of its tiers. Nothing re-asks it between recomputes,
 * so a set gated on a permission or a finished quest switches at the wearer's next equipment change,
 * login or respawn.
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

    /** The counts of a set the player no longer has any piece of: it is no candidate, so it was never counted. */
    private static final SlotCounts NONE_ON = new SlotCounts(0, 0, 0, 0);

    private static volatile EquipStatBridge bridge;

    /**
     * The vocabulary a set's {@code Requires} reads, built on first use: the portable {@code hytale:} readings
     * about the wearer, plus every id another module contributes and every asset-defined one, which every
     * registry resolves. So a set gates on what any library table can, a permission or a finished quest
     * included, on a server running this library alone.
     */
    private static final class WearerFactors {

        static final FactorRegistry REGISTRY = build();

        private WearerFactors() {
        }

        @Nonnull
        private static FactorRegistry build() {
            FactorRegistry registry = new FactorRegistry("gear-sets");
            HytaleFactors.registerInto(registry, LibraryOwner.NAME);
            return registry;
        }
    }

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
     * is not worth a line in the log. A player whose world is gone or stopping is skipped alone, so
     * every player after them still runs.
     */
    public static void recomputeAllOnline() {
        EquipStatBridge installed = bridge;
        Universe universe = Universe.get();
        if (installed == null || universe == null) {
            return;
        }
        try {
            for (PlayerRef player : universe.getPlayers()) {
                recomputeOnWorldThread(installed, player);
            }
        } catch (Throwable t) {
            SafeLog.warn("[gearset] could not recompute every online player: " + t.getMessage());
        }
    }

    /** Queue one online player's recompute on their world thread; a dead or stopping world costs only them. */
    private static void recomputeOnWorldThread(@Nonnull EquipStatBridge installed, @Nullable PlayerRef player) {
        try {
            Ref<EntityStore> ref = player == null ? null : player.getReference();
            if (ref == null || !ref.isValid()) {
                return;
            }
            World world = WorldEvictors.worldOf(ref);
            if (world == null || !world.isAlive()) {
                return;
            }
            world.execute(() -> recomputeThrough(installed, ref));
        } catch (Throwable t) {
            SafeLog.warn("[gearset] could not recompute an online player: " + t.getMessage());
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
     * The player's entity left its store (see {@link GearSetLifecycleSystems.Left}): forget their
     * row, whatever the {@code reason}. This is the row's ONE eviction. A disconnect removes the
     * player with {@code UNLOAD} ({@code PlayerRef.removeFromStore}, after the disconnect event has
     * fired) and a world change removes them with the same reason, so no reason is spared. It runs on
     * the world thread as the entity goes, after any recompute queued before it, and nothing can
     * recompute an entity that is no longer in a store, so no row outlives the session or the world.
     * Forgetting is always safe: the next recompute is then a hydrate, which reads what is really on
     * the entity and announces nothing. Nothing else evicts the table; {@code WorldEvictors} serves
     * this engine only as {@code worldOf}, the thread a recompute runs on.
     *
     * @param reason the engine's reason, passed through so a test drives the real one; every reason
     *               forgets
     */
    static void onEntityRemoved(@Nullable UUID playerId, @Nonnull RemoveReason reason) {
        if (playerId != null) {
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
            GearSetLooksComponent looks = looksOf(store, ref);

            // Decide.
            List<GearSetPlan.Desired> desired = new ArrayList<>();
            Set<String> desiredEffects = new LinkedHashSet<>();
            Set<TierRef> active = new LinkedHashSet<>();
            Map<String, SlotCounts> countsBySet = new LinkedHashMap<>();
            Map<String, Integer> progressBySet = new LinkedHashMap<>();
            Map<String, Integer> fullBySet = new LinkedHashMap<>();
            List<String> pooled = new ArrayList<>();
            FactorContext wearer = null;
            for (GearSetAsset set : index.candidates(snapshot)) {
                // A set's own Requires, every condition but its mod presence (decided at the fold): a wearer
                // it refuses has none of its tiers, as if no piece were on. Asked at each recompute, so a
                // change in what it reads shows at the wearer's next one.
                FactorCondition[] gate = PresenceRequiresCodec.liveFactors(set.getRequires());
                if (gate.length > 0) {
                    if (wearer == null) {
                        wearer = FactorContext.about(store, ref);
                    }
                    if (!FactorConditions.pass(gate, WearerFactors.REGISTRY, wearer)) {
                        continue;
                    }
                }
                SlotCounts counts = GearSetDecision.count(set, snapshot);
                countsBySet.put(GearSetKeys.setId(set.getId()), counts);
                progressBySet.put(GearSetKeys.setId(set.getId()), GearSetDecision.progress(set, counts));
                fullBySet.put(GearSetKeys.setId(set.getId()), GearSetDecision.fullCount(set));
                List<GearSetAsset.Tier> tiers = set.tiers();
                List<Integer> activeTiers = GearSetDecision.activeTiers(set, counts);
                for (int tierIndex : activeTiers) {
                    GearSetAsset.Tier tier = tiers.get(tierIndex);
                    TierRef tierRef = new TierRef(set.getId(), tierIndex);
                    active.add(tierRef);
                    desired.add(new GearSetPlan.Desired(tierRef,
                            GearSetPlan.resolve(tier, StatIndexCache::resolve, StatIndexCache::warnUnknownOnce)));
                    String effect = tier.effectId();
                    if (effect != null) {
                        desiredEffects.add(effect);
                    }
                }
                // The set's one tile: its own badge, or a default slot seated below.
                if (!activeTiers.isEmpty()) {
                    String badge = GearSetBadges.badgeOf(tiers, activeTiers);
                    if (badge != null) {
                        desiredEffects.add(badge);
                    } else {
                        pooled.add(GearSetKeys.setId(set.getId()));
                    }
                }
            }
            Map<String, String> seats = GearSetBadges.seat(pooled, hydrate ? Map.of() : previous.badgeSeats());
            desiredEffects.addAll(seats.values());

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

            // Effects: see effectChanges. A hydrate cannot trust memory, so it answers for every
            // effect id any folded set names (looks and badges), the default tiles, and every id the
            // player's saved record holds, which is why a set's effect ids must be dedicated to the set.
            Set<String> shown = shownEffects(desiredEffects, dead);
            Set<String> previousEffects = hydrate
                    ? hydrateAnswersFor(GearSetBadges.withDefaultTiles(index.allEffectIds()),
                            looks == null ? Set.of() : looks.effects())
                    : previous.effects();
            EffectChanges changes = effectChanges(previousEffects, shown,
                    effect -> GearSetEffects.has(store, ref, effect));
            List<String> unremoved = new ArrayList<>();
            for (String effect : changes.removes()) {
                if (!GearSetEffects.remove(store, ref, effect)) {
                    unremoved.add(effect);
                }
            }
            for (String effect : changes.applies()) {
                GearSetEffects.apply(store, ref, effect);
            }

            // What the engine answers for from here on: what it asked for, plus every look whose
            // remove did not go through, so the next recompute (and the next login) tries again.
            Set<String> answered = answeredAfter(shown, unremoved, GearSetValidator::effectKnown);
            Map<String, Integer> pieces = piecesOf(progressBySet);
            GearSetApplied.put(playerId, new GearSetApplied.Applied(GearSetPlan.tiersOf(desired),
                    answered, active, pieces, seats));
            if (looks != null) {
                looks.record(answered);
            }

            // The joined set (its layers' tiers in), the one the tiers were decided over.
            List<TierFlip> flips = announcements(previous, active);
            for (TierFlip flip : flips) {
                GearSetAsset set = index.set(flip.tier().setId());
                if (set == null) {
                    continue;
                }
                SlotCounts counts = countsBySet.get(flip.tier().setId());
                GearSetEvents.fireTierChanged(playerId, player, set, flip.tier().tierIndex(), flip.active(),
                        counts == null ? NONE_ON : counts);
            }
            for (String setId : pieceRises(previous, pieces, flips, fullBySet)) {
                GearSetAsset set = index.set(setId);
                SlotCounts counts = countsBySet.get(setId);
                if (set == null || counts == null) {
                    continue;
                }
                GearSetEvents.firePiecesChanged(playerId, player, set, counts);
            }
        } catch (Throwable t) {
            SafeLog.warn("[gearset] recompute failed: " + t.getMessage());
        }
    }

    /**
     * The player's saved look record, or null when the type is not registered or the player carries
     * none. Read and updated in place only: the connect hook attaches it, never a recompute, which
     * runs inside system ticks where adding a component would change the archetype.
     */
    @Nullable
    private static GearSetLooksComponent looksOf(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        ComponentType<EntityStore, GearSetLooksComponent> type = GearSetLooksComponent.getComponentType();
        return type == null ? null : store.getComponent(ref, type);
    }

    /**
     * The looks a hydrate answers for: every effect id any folded set names ({@code folded}, a
     * disabled set's included), then every id the player's saved record holds that the fold does not
     * ({@code recorded}, a look of a set deleted since included), distinct and in that order. Pure.
     */
    @Nonnull
    static Set<String> hydrateAnswersFor(@Nonnull Collection<String> folded, @Nonnull Collection<String> recorded) {
        Set<String> out = new LinkedHashSet<>(folded);
        out.addAll(recorded);
        return out;
    }

    /**
     * The looks the engine answers for after one recompute, which the row and the player's saved
     * record both hold: every look in {@code shown}, then every look in {@code unremoved} (its
     * remove this recompute returned false) that still {@code resolves} to a loaded effect, distinct
     * and in that order. A look whose remove failed stays until a remove goes through, so it is
     * tried again at the next recompute and at the next login. A look whose effect asset no longer
     * exists is dropped: the engine's effect controller skips such an effect as it loads the player
     * and never saves it again, so nothing is left to take off, and no remove of it could ever
     * succeed. Pure.
     */
    @Nonnull
    static Set<String> answeredAfter(@Nonnull Collection<String> shown, @Nonnull Collection<String> unremoved,
            @Nonnull Predicate<String> resolves) {
        Set<String> out = new LinkedHashSet<>(shown);
        for (String effect : unremoved) {
            if (resolves.test(effect)) {
                out.add(effect);
            }
        }
        return out;
    }

    /** The looks the entity should carry now: every desired one, or none while it is dead. Pure. */
    @Nonnull
    static Set<String> shownEffects(@Nonnull Set<String> desired, boolean dead) {
        return dead ? Set.of() : desired;
    }

    /**
     * The effect rule, pure. Off: every effect in {@code previous}, every effect the engine answers
     * for (put on last time, or whose remove has not yet gone through; on a hydrate, every effect id
     * any folded set names plus every id the player's saved record holds), that {@code shown} does
     * not hold. On: every effect in {@code shown} the entity does not have right now ({@code has}),
     * whatever the row says, so a look something else cleared comes back at the next recompute and
     * one already on is never put on twice. Each list keeps the order given.
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
     * What one recompute announces: nothing on the first recompute since the player's entity joined
     * its store (no row, a hydrate, not a flip: after a login and after a world change alike),
     * otherwise every tier that really flipped since the player's last write in that store. Pure.
     */
    @Nonnull
    static List<TierFlip> announcements(@Nullable GearSetApplied.Applied previous, @Nonnull Collection<TierRef> active) {
        return previous == null ? List.of() : flips(previous.activeTiers(), active);
    }

    /**
     * How far each set stands toward its full set ({@link GearSetDecision#progress}), by folded set id, a set
     * at none left out: the count a notice shows and a rise is measured in. Pure.
     */
    @Nonnull
    static Map<String, Integer> piecesOf(@Nonnull Map<String, Integer> progressBySet) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : progressBySet.entrySet()) {
            if (entry.getValue() > 0) {
                out.put(entry.getKey(), entry.getValue());
            }
        }
        return out;
    }

    /**
     * The sets whose quiet piece line one recompute announces: every set in {@code now} whose count
     * rose since the player's last write in this store from below its full count ({@code full}, by
     * folded set id; a set missing there, or authoring no count, has nothing to count against) and
     * none of whose tiers is in {@code flips} (a flip's own notice already carries the count), in
     * the order {@code now} gives. Nothing on a hydrate (no row), so logging in or changing worlds
     * with pieces on says nothing; nothing for a count that stayed or fell; nothing once the count
     * already stood at the full set, since the notice shows it capped there and would only repeat
     * itself. Pure.
     */
    @Nonnull
    static List<String> pieceRises(@Nullable GearSetApplied.Applied previous, @Nonnull Map<String, Integer> now,
            @Nonnull Collection<TierFlip> flips, @Nonnull Map<String, Integer> full) {
        if (previous == null) {
            return List.of();
        }
        Set<String> flipped = new HashSet<>();
        for (TierFlip flip : flips) {
            flipped.add(flip.tier().setId());
        }
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : now.entrySet()) {
            int before = previous.pieces().getOrDefault(entry.getKey(), 0);
            int fullCount = full.getOrDefault(entry.getKey(), 0);
            if (entry.getValue() > before && before < fullCount && !flipped.contains(entry.getKey())) {
                out.add(entry.getKey());
            }
        }
        return out;
    }

    /**
     * The set tiers holding on {@code playerId} right now, as the last recompute decided them, in the order it
     * found them; empty for a player with no recompute yet in their current store. A page showing what a player's
     * worn gear adds reads this (M557: a server running only this library shows the active set and its bonus).
     */
    @Nonnull
    public static List<TierRef> activeTiers(@Nullable UUID playerId) {
        GearSetApplied.Applied applied = playerId == null ? null : GearSetApplied.get(playerId);
        return applied == null ? List.of() : List.copyOf(applied.activeTiers());
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
}
