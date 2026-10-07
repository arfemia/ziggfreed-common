package com.ziggfreed.common.reputation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.reputation.asset.ReputationAsset;
import com.ziggfreed.common.reputation.asset.ReputationConfig;
import com.ziggfreed.common.util.SafeLog;

/**
 * Every reputation reading, the ONE zc writer and the effective-rank checks. A reputation is a native
 * ReputationGroup, found by its id without regard to case; it is absent (unknown) while the engine's
 * plugin is off, the owner's switch is off or its companion says {@code Enabled: false}. Effective standing
 * is earned standing plus the companion's {@code Gear.Stat} folded maximum. World thread for every
 * per-player call; every {@code store}/{@code ref} is asked of the engine seam first, so no live player
 * means no answer.
 */
public final class ReputationService {

    /** What a change did. CHANGED and UNCHANGED are successes; the rest are refusals. */
    public enum Status {
        CHANGED, UNCHANGED, UNKNOWN, ZERO, NOT_LIVE, NOT_WRITTEN
    }

    /** A change's status, and what landed when it CHANGED. */
    public record Result(@Nonnull Status status, @Nullable ReputationChange change) {

        public boolean refused() {
            return status != Status.CHANGED && status != Status.UNCHANGED;
        }
    }

    /** What one player stands at with one reputation, read live. */
    public record Standing(@Nonnull ReputationDef reputation, int earned, long gear, boolean entry,
                           @Nullable ReputationLadder.Rank rank) {

        public long effective() {
            return earned + gear;
        }

        /** Met: the engine holds an entry for them, or their standing is not 0. */
        public boolean met() {
            return entry || effective() != 0;
        }
    }

    private final ReputationNative engine;
    private final ReputationFanOut fanOut;
    private final ReputationRankWatch watch = new ReputationRankWatch();

    public ReputationService(@Nonnull ReputationNative engine, @Nonnull ReputationFanOut fanOut) {
        this.engine = engine;
        this.fanOut = fanOut;
    }

    @Nonnull
    public ReputationNative engine() {
        return engine;
    }

    /** Is the module switched on and the engine's reputation plugin running? */
    public boolean isOn() {
        return ReputationConfig.getInstance().isGlobalEnabled() && engine.available();
    }

    /**
     * The server's shared ladder (the ranks every reputation shares), or {@link ReputationLadder#EMPTY} while
     * the module is off. One reputation's own ladder is {@link #ladderOf}.
     */
    @Nonnull
    public ReputationLadder ladder() {
        return isOn() ? ReputationLadder.of(engine.ranks()) : ReputationLadder.EMPTY;
    }

    /**
     * The ladder {@code reputationId} (any case) reads: the shared ranks plus its own tiers above the top
     * ({@link ReputationLadder#of(Collection, Map)}); the shared ladder for an unknown id, and
     * {@link ReputationLadder#EMPTY} while the module is off.
     */
    @Nonnull
    public ReputationLadder ladderOf(@Nullable String reputationId) {
        return isOn() ? ladderFor(known(reputationId)) : ReputationLadder.EMPTY;
    }

    /** {@link #ladderOf} for a reputation already in hand; the shared ladder for null. */
    @Nonnull
    public ReputationLadder ladderFor(@Nullable ReputationDef def) {
        if (!isOn()) {
            return ReputationLadder.EMPTY;
        }
        return ReputationLadder.of(engine.ranks(), def == null ? Map.of() : def.tierFloors());
    }

    /** The switched-on reputation {@code id} names (any case), or null. */
    @Nullable
    public ReputationDef known(@Nullable String id) {
        if (id == null || id.isBlank() || !isOn()) {
            return null;
        }
        String wanted = id.trim();
        for (ReputationNative.Group group : engine.groups()) {
            if (group.id().equalsIgnoreCase(wanted)) {
                return defOf(group);
            }
        }
        return null;
    }

    /** Every switched-on reputation. */
    @Nonnull
    public List<ReputationDef> all() {
        List<ReputationDef> out = new ArrayList<>();
        if (!isOn()) {
            return out;
        }
        for (ReputationNative.Group group : engine.groups()) {
            ReputationDef def = defOf(group);
            if (def != null) {
                out.add(def);
            }
        }
        return out;
    }

    /** What the live player at {@code ref} stands at with {@code id}; null when unknown or nobody is there. */
    @Nullable
    public Standing standing(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref, @Nullable String id) {
        ReputationDef def = known(id);
        if (def == null || !engine.live(store, ref)) {
            return null;
        }
        return standingOf(store, ref, def, ladderFor(def));
    }

    /** Every switched-on reputation the player has met, by Order, then id. */
    @Nonnull
    public List<Standing> met(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref) {
        List<Standing> out = new ArrayList<>();
        if (!engine.live(store, ref)) {
            return out;
        }
        for (ReputationDef def : all()) {
            Standing standing = standingOf(store, ref, def, ladderFor(def));
            if (standing != null && standing.met()) {
                out.add(standing);
            }
        }
        out.sort(Comparator.comparingInt((Standing s) -> s.reputation().order())
                .thenComparing(s -> s.reputation().id().toLowerCase(Locale.ROOT)));
        return out;
    }

    /** Has the player met any switched-on reputation? */
    public boolean anyMet(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref) {
        return !met(store, ref).isEmpty();
    }

    /**
     * The one zc writer: refuse an unknown reputation or a zero delta, cut a gain at the Cap, write through
     * the engine (its clamp applies), tell the fan-out, then check the effective rank. World thread.
     */
    @Nonnull
    public Result change(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref, @Nullable String id,
            int delta, @Nonnull String source) {
        ReputationDef def = known(id);
        if (def == null) {
            return new Result(Status.UNKNOWN, null);
        }
        if (delta == 0) {
            return new Result(Status.ZERO, null);
        }
        if (!engine.live(store, ref)) {
            return new Result(Status.NOT_LIVE, null);
        }
        UUID player = engine.playerId(store, ref);
        Integer before = engine.earned(store, ref, def.id());
        if (player == null || before == null) {
            return new Result(Status.NOT_LIVE, null);
        }
        int applied = capped(before, delta, def.cap());
        if (applied == 0) {
            return new Result(Status.UNCHANGED, null);
        }
        Integer after = engine.add(store, ref, def.id(), applied);
        if (after == null) {
            return new Result(Status.NOT_WRITTEN, null);
        }
        if (after.intValue() == before.intValue()) {
            return new Result(Status.UNCHANGED, null);
        }
        ReputationLadder ladder = ladderFor(def);
        long gear = engine.statMax(store, ref, def.gearStat());
        ReputationLadder.Rank top = ladder.top();
        int crossings = top == null ? 0 : ReputationLadder.crossings(before, after, top.min(), def.beyondEvery());
        ReputationChange change = new ReputationChange(player, def, before, after, gear,
                ladder.rankFor(before + gear), ladder.rankFor(after + gear),
                ladder.progress(after + gear, def.beyondEvery()), crossings, source);
        try {
            fanOut.changed(store, ref, change);
        } catch (Throwable t) {
            SafeLog.warn("[reputation] telling the change to '" + def.id() + "' failed; the standing stands: "
                    + t.getMessage());
        }
        observe(store, ref, player, def, change.effectiveAfter(), ladder, ReputationRankWatch.Trigger.CHANGE);
        return new Result(Status.CHANGED, change);
    }

    /** Check every switched-on reputation's effective rank for the live player at {@code ref}. World thread. */
    public void checkAll(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref,
            @Nonnull ReputationRankWatch.Trigger trigger) {
        if (!isOn() || !engine.live(store, ref)) {
            return;
        }
        UUID player = engine.playerId(store, ref);
        if (player == null) {
            return;
        }
        for (ReputationDef def : all()) {
            ReputationLadder ladder = ladderFor(def);
            Standing standing = standingOf(store, ref, def, ladder);
            if (standing != null) {
                observe(store, ref, player, def, standing.effective(), ladder, trigger);
            }
        }
    }

    /** Forget what the rank checks remember about {@code player} (they left). */
    public void forget(@Nonnull UUID player) {
        watch.forget(player);
    }

    /** A gain cut so earned standing never passes {@code cap}; a loss and an uncapped gain pass whole. */
    static int capped(int before, int delta, @Nullable Integer cap) {
        if (cap == null || delta <= 0) {
            return delta;
        }
        if (before >= cap) {
            return 0;
        }
        return (int) Math.min(delta, (long) cap - before);
    }

    private void observe(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref, @Nonnull UUID player,
            @Nonnull ReputationDef def, long effective, @Nonnull ReputationLadder ladder,
            @Nonnull ReputationRankWatch.Trigger trigger) {
        try {
            watch.observe(store, ref, player, def, effective, ladder, trigger, fanOut);
        } catch (Throwable t) {
            SafeLog.warn("[reputation] the rank check for '" + def.id() + "' failed: " + t.getMessage());
        }
    }

    @Nullable
    private Standing standingOf(@Nullable Store<EntityStore> store, @Nullable Ref<EntityStore> ref,
            @Nonnull ReputationDef def, @Nonnull ReputationLadder ladder) {
        Integer earned = engine.earned(store, ref, def.id());
        if (earned == null) {
            return null;
        }
        long gear = engine.statMax(store, ref, def.gearStat());
        return new Standing(def, earned, gear, engine.hasEntry(store, ref, def.id()), ladder.rankFor(earned + gear));
    }

    @Nullable
    private static ReputationDef defOf(@Nonnull ReputationNative.Group group) {
        ReputationAsset companion = ReputationConfig.getInstance().resolve(group.id());
        if (companion != null && !companion.isEnabled()) {
            return null;
        }
        return new ReputationDef(group.id(), group.initial(), companion);
    }
}
