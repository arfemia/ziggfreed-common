package com.ziggfreed.common.proc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatValue;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.effect.NativeEffectUtil;
import com.ziggfreed.common.factor.FactorContext;
import com.ziggfreed.common.stats.StatIndexCache;
import com.ziggfreed.common.stats.condition.HitReads;
import com.ziggfreed.common.stats.condition.Nearby;

/**
 * The runtime of counters ({@link CounterAsset}): each holder's stacks, the native modifiers they give, the effect
 * that shows them, and their decay, read a few times a second by the holder pass. Transient and single-process: kept
 * by the holder's ref and dropped when the holder leaves its store, so a world change or a login starts every counter
 * at 0, and emptied when the holder dies ({@code ProcSystems.Died}); the modifiers and the shown effect are saved with
 * the entity like any other, so a holder's first pass sweeps every one an earlier session left.
 *
 * <p>A {@code PerTarget} counter keeps stacks per foe and gives the holder nothing; its {@code OnFoe} marks (a look by
 * count, a weakness) go on each foe its stacks are about, kept up by the holder pass while they last ({@link FoeMarks}).
 *
 * <p>A counter's modifiers are keyed {@value #MODIFIER_PREFIX}{@code <id>}: under the track grants' owner, because a
 * counter may raise a stat 1.7.x knows (a crit chance), and the 1.7.x rollback sweep clears every {@code zigtrack:}
 * key a player carries (R755); the {@code @} keeps it clear of every grant key, and the stat grant writer leaves
 * such keys alone. World thread.
 */
public final class Counters {

    /** The prefix of every modifier a counter writes, {@code zigtrack:@counter:<id>}. */
    public static final String MODIFIER_PREFIX = "zigtrack:@counter:";

    /**
     * One holder's one counter. Every field but the target's is written on the holder's own world thread alone; the
     * target and its gone mark are also written by {@link #targetGone} from the thread of whichever world the target
     * left, so they are volatile.
     */
    static final class State {
        double stacks;
        /** The stacks and the time of the last add, take or clear, which decay counts from. */
        double baseStacks;
        long baseNanos;
        @Nullable volatile Ref<EntityStore> target;
        volatile boolean targetGone;
        /** The index of the effect shown, or -1 for none. */
        int shown = -1;
        /** A per-foe count's marks on its foe ({@link FoeMarks}). */
        @Nonnull final FoeMarks.Shown onFoe = new FoeMarks.Shown();
    }

    /**
     * One holder's counters, and whether its first pass has swept what an earlier session left. The states are a
     * concurrent map: the holder's world thread adds to it while another world's thread walks it when a target there
     * leaves ({@link #targetGone}). A {@code PerTarget} counter keeps a state per foe ({@code perTarget}, by counter
     * key, then by the foe's ref), concurrent for the same reason.
     */
    private static final class Holder {
        boolean swept;
        @Nonnull final Map<String, State> states = new ConcurrentHashMap<>();
        @Nonnull final Map<String, Map<Ref<EntityStore>, State>> perTarget = new ConcurrentHashMap<>();
    }

    private static final Map<Ref<EntityStore>, Holder> HOLDERS = new ConcurrentHashMap<>();

    private Counters() {
    }

    /** The stacks {@code holder} holds of the counter {@code counterId} (any case); 0 for none. */
    public static double stacks(@Nullable Ref<EntityStore> holder, @Nullable String counterId) {
        return stacks(holder, counterId, null);
    }

    /**
     * The stacks {@code holder} holds of the counter {@code counterId} (any case): of a {@code PerTarget} counter, the
     * stacks about {@code target} (0 with no target); of any other, its one count. 0 for none.
     */
    public static double stacks(@Nullable Ref<EntityStore> holder, @Nullable String counterId,
            @Nullable Ref<EntityStore> target) {
        if (holder == null || counterId == null || counterId.isBlank()) {
            return 0.0;
        }
        Holder h = HOLDERS.get(holder);
        if (h == null) {
            return 0.0;
        }
        String key = counterId.trim().toLowerCase(Locale.ROOT);
        Map<Ref<EntityStore>, State> byTarget = h.perTarget.get(key);
        if (byTarget != null) {
            State state = target == null ? null : byTarget.get(target);
            return state == null ? 0.0 : state.stacks;
        }
        State state = h.states.get(key);
        return state == null ? 0.0 : state.stacks;
    }

    /**
     * Add {@code add} stacks (up to the counter's size), take {@code consume} away, then clear them all when
     * {@code clear}, on the moment's holder; an add about another target than a {@code OneTarget} counter's starts it
     * over, and a {@code PerTarget} counter changes only the stacks about the moment's target (none: nothing).
     * Writes the modifiers and the shown effect when the stacks moved.
     */
    static void change(@Nonnull ProcMoment moment, @Nonnull CounterConfig.Def def, double add, double consume,
            boolean clear, @Nonnull FactorContext context) {
        ComponentAccessor<EntityStore> accessor = moment.accessor();
        Ref<EntityStore> holder = moment.holder();
        Holder h = holderOf(accessor, holder);
        if (def.asset().perTarget()) {
            changePerTarget(accessor, h, holder, moment.target(), def, add, consume, clear, context);
            return;
        }
        State state = h.states.computeIfAbsent(def.key(), k -> new State());
        double before = state.stacks;
        if (add > 0.0) {
            Ref<EntityStore> target = moment.target();
            if (def.asset().oneTarget() && target != null) {
                if (state.target == null || state.targetGone || !Nearby.same(state.target, target)) {
                    state.stacks = 0.0;
                }
                state.target = target;
                state.targetGone = false;
            }
            state.stacks = Math.min(sizeOf(def, accessor, holder, context), state.stacks + add);
        }
        if (consume > 0.0) {
            state.stacks = Math.max(0.0, state.stacks - consume);
        }
        if (clear) {
            state.stacks = 0.0;
        }
        state.stacks = Math.max(0.0, state.stacks);
        state.baseStacks = state.stacks;
        state.baseNanos = System.nanoTime();
        if (state.stacks != before) {
            write(accessor, holder, def, state);
        }
    }

    /**
     * The same change on a {@code PerTarget} counter: only the stacks about {@code target}. Such a counter gives the
     * holder no modifiers and shows them nothing (its stacks are about one foe, the holder's stats are not); its
     * {@code OnFoe} marks go on that foe instead ({@link FoeMarks}).
     */
    private static void changePerTarget(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Holder h,
            @Nonnull Ref<EntityStore> holder, @Nullable Ref<EntityStore> target, @Nonnull CounterConfig.Def def,
            double add, double consume, boolean clear, @Nonnull FactorContext context) {
        if (target == null || !target.isValid()) {
            return;
        }
        Map<Ref<EntityStore>, State> byTarget = h.perTarget.computeIfAbsent(def.key(), k -> new ConcurrentHashMap<>());
        State state = byTarget.computeIfAbsent(target, t -> new State());
        if (add > 0.0) {
            state.stacks = Math.min(sizeOf(def, accessor, holder, context), state.stacks + add);
        }
        if (consume > 0.0) {
            state.stacks = Math.max(0.0, state.stacks - consume);
        }
        if (clear) {
            state.stacks = 0.0;
        }
        state.stacks = Math.max(0.0, state.stacks);
        state.baseStacks = state.stacks;
        state.baseNanos = System.nanoTime();
        if (!(state.stacks > 0.0)) {
            byTarget.remove(target);
        }
        markFoe(accessor, holder, target, def, state, context);
    }

    /** Bring a per-foe count's marks on its foe in line with its stacks (none: off). */
    private static void markFoe(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> holder,
            @Nonnull Ref<EntityStore> foe, @Nonnull CounterConfig.Def def, @Nonnull State state,
            @Nonnull FactorContext context) {
        if (def.asset().onFoe() == null) {
            return;
        }
        try {
            if (state.stacks > 0.0) {
                FoeMarks.show(accessor, holder, foe, def, state.stacks, context, state.onFoe);
            } else {
                FoeMarks.hide(accessor, holder, foe, def, state.onFoe);
            }
        } catch (Throwable t) {
            Procs.failed("marking a foe with a counter", t);
        }
    }

    /** Take every mark of a per-foe counter's stacks off their foes (the stacks are gone). */
    private static void unmarkAll(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> holder,
            @Nullable CounterConfig.Def def, @Nullable Map<Ref<EntityStore>, State> byTarget) {
        if (def == null || byTarget == null || def.asset().onFoe() == null) {
            return;
        }
        for (Map.Entry<Ref<EntityStore>, State> one : byTarget.entrySet()) {
            FoeMarks.hide(accessor, holder, one.getKey(), def, one.getValue().onFoe);
        }
    }

    /**
     * Empty every one of {@code defs} the moment's holder holds stacks of (a moment that clears them), a
     * {@code PerTarget} counter's stacks about every foe. A damage-over-time tick (a foe's burn or poison on the
     * holder) clears only a counter that writes {@code ClearOverTime}.
     */
    static void clearOn(@Nonnull ProcMoment moment, @Nonnull List<CounterConfig.Def> defs) {
        Holder h = HOLDERS.get(moment.holder());
        if (h == null || defs.isEmpty()) {
            return;
        }
        boolean overTime = moment.damage() != null && HitReads.isOverTime(moment.damage());
        for (CounterConfig.Def def : defs) {
            if (overTime && !def.asset().clearOverTime()) {
                continue;
            }
            unmarkAll(moment.accessor(), moment.holder(), def, h.perTarget.remove(def.key()));
            State state = h.states.get(def.key());
            if (state != null && state.stacks > 0.0) {
                empty(moment.accessor(), moment.holder(), def, state);
            }
        }
    }

    /** Empty every counter {@code holder} holds stacks of (its death). */
    static void clearAll(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> holder) {
        Holder h = HOLDERS.get(holder);
        if (h == null) {
            return;
        }
        for (Map.Entry<String, Map<Ref<EntityStore>, State>> entry : h.perTarget.entrySet()) {
            unmarkAll(accessor, holder, CounterConfig.getInstance().live(entry.getKey()), entry.getValue());
        }
        h.perTarget.clear();
        for (Map.Entry<String, State> entry : h.states.entrySet()) {
            State state = entry.getValue();
            if (!(state.stacks > 0.0)) {
                continue;
            }
            CounterConfig.Def def = CounterConfig.getInstance().live(entry.getKey());
            if (def == null) {
                state.stacks = 0.0;
                state.baseStacks = 0.0;
                clearModifiers(accessor, holder, entry.getKey());
            } else {
                empty(accessor, holder, def, state);
            }
        }
    }

    /** Take every stack of one counter away, with its modifiers and its shown effect. */
    private static void empty(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> holder,
            @Nonnull CounterConfig.Def def, @Nonnull State state) {
        state.stacks = 0.0;
        state.baseStacks = 0.0;
        state.baseNanos = System.nanoTime();
        write(accessor, holder, def, state);
    }

    /**
     * The holder pass: on a holder's first, sweep what an earlier session left (every {@value #MODIFIER_PREFIX}
     * modifier and every counter's shown effect worn with no end); then empty a counter whose file is gone or whose
     * {@code Requires} the holder no longer meets (its talent respecced or swapped away), and let the rest decay.
     */
    static void pass(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> holder,
            @Nonnull FactorContext context, long nanos) {
        Holder h = holderOf(accessor, holder);
        if (!h.perTarget.isEmpty()) {
            passPerTarget(accessor, holder, h, context, nanos);
        }
        if (h.states.isEmpty()) {
            return;
        }
        for (Map.Entry<String, State> entry : h.states.entrySet()) {
            State state = entry.getValue();
            if (!(state.stacks > 0.0)) {
                continue;
            }
            CounterConfig.Def def = CounterConfig.getInstance().live(entry.getKey());
            if (def == null) {
                state.stacks = 0.0;
                state.baseStacks = 0.0;
                clearModifiers(accessor, holder, entry.getKey());
                continue;
            }
            if (!Procs.gatePasses(def.asset().liveRequires(), context)) {
                empty(accessor, holder, def, state);
                continue;
            }
            CounterAsset.Decay decay = def.asset().decay();
            if (decay == null) {
                continue;
            }
            double after = decay.after() == null ? 0.0 : Procs.evaluate(decay.after(), context);
            double every = decay.every() == null ? 0.0 : Procs.evaluate(decay.every(), context);
            double next = decayed(state.baseStacks, nanos - state.baseNanos, after, every, decay.lose());
            if (next < state.stacks) {
                state.stacks = next;
                write(accessor, holder, def, state);
            }
        }
    }

    /**
     * The holder pass over the {@code PerTarget} counters: the stacks about each foe decay as a counter's do, about a
     * foe gone they go, and every foe's go when the counter's file is gone or its {@code Requires} fails; the marks a
     * counter puts on its foes follow (kept up while the stacks last, taken off when they go).
     */
    private static void passPerTarget(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> holder, @Nonnull Holder h, @Nonnull FactorContext context, long nanos) {
        for (Map.Entry<String, Map<Ref<EntityStore>, State>> entry : h.perTarget.entrySet()) {
            CounterConfig.Def def = CounterConfig.getInstance().live(entry.getKey());
            if (def == null || !def.asset().perTarget() || !Procs.gatePasses(def.asset().liveRequires(), context)) {
                unmarkAll(accessor, holder, def, h.perTarget.remove(entry.getKey()));
                continue;
            }
            CounterAsset.Decay decay = def.asset().decay();
            double after = decay == null || decay.after() == null ? 0.0 : Procs.evaluate(decay.after(), context);
            double every = decay == null || decay.every() == null ? 0.0 : Procs.evaluate(decay.every(), context);
            Map<Ref<EntityStore>, State> byTarget = entry.getValue();
            for (Map.Entry<Ref<EntityStore>, State> one : byTarget.entrySet()) {
                State state = one.getValue();
                Ref<EntityStore> foe = one.getKey();
                if (!foe.isValid()) {
                    byTarget.remove(foe);
                    continue;
                }
                if (decay != null) {
                    state.stacks = Math.min(state.stacks,
                            decayed(state.baseStacks, nanos - state.baseNanos, after, every, decay.lose()));
                }
                if (!(state.stacks > 0.0)) {
                    byTarget.remove(foe);
                }
                markFoe(accessor, holder, foe, def, state, context.toBuilder().target(foe).build());
            }
        }
    }

    /**
     * What {@code base} stacks have fallen to {@code sinceNanos} after the counter last changed: all of them until
     * {@code afterSeconds}, then {@code lose} at that moment and again every {@code everySeconds} (all at once when
     * that is 0 or less), never below 0. Pure.
     */
    static double decayed(double base, long sinceNanos, double afterSeconds, double everySeconds, double lose) {
        if (!(base > 0.0)) {
            return 0.0;
        }
        double since = sinceNanos / 1_000_000_000.0;
        double after = Double.isFinite(afterSeconds) ? Math.max(0.0, afterSeconds) : 0.0;
        if (since < after) {
            return base;
        }
        if (!(everySeconds > 0.0) || !Double.isFinite(everySeconds)) {
            return 0.0;
        }
        if (!(lose > 0.0)) {
            return base;
        }
        long falls = 1L + (long) Math.floor((since - after) / everySeconds);
        return Math.max(0.0, base - lose * falls);
    }

    /** Does {@code holder} hold any counter state at all? */
    static boolean holds(@Nonnull Ref<EntityStore> holder) {
        Holder h = HOLDERS.get(holder);
        return h != null && !h.states.isEmpty();
    }

    /** Forget the holder at {@code ref} (it left its store). */
    static void forget(@Nonnull Ref<EntityStore> ref) {
        HOLDERS.remove(ref);
    }

    /**
     * A target left its store: a {@code OneTarget} counter about it starts over at its holder's next add, and a
     * {@code PerTarget} counter's stacks about it go.
     */
    static void targetGone(@Nonnull Ref<EntityStore> ref) {
        for (Holder h : HOLDERS.values()) {
            for (State state : h.states.values()) {
                if (state.target == ref) {
                    state.targetGone = true;
                    state.target = null;
                }
            }
            for (Map<Ref<EntityStore>, State> byTarget : h.perTarget.values()) {
                byTarget.remove(ref);
            }
        }
    }

    /** Forget every holder (a test). */
    static void clear() {
        HOLDERS.clear();
    }

    @Nonnull
    private static Holder holderOf(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> holder) {
        Holder h = HOLDERS.computeIfAbsent(holder, r -> new Holder());
        if (!h.swept) {
            h.swept = true;
            sweep(accessor, holder);
        }
        return h;
    }

    /** The counter's size for this holder: its formula, raised by the holder's {@value Procs#COUNTER_SIZE_STAT}. */
    private static double sizeOf(@Nonnull CounterConfig.Def def, @Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> holder, @Nonnull FactorContext context) {
        double size = def.asset().size() == null ? 1.0 : Procs.evaluate(def.asset().size(), context);
        size *= 1.0 + Procs.statPoints(accessor, holder, Procs.COUNTER_SIZE_STAT) / 100.0;
        return Double.isFinite(size) ? Math.max(0.0, size) : 0.0;
    }

    /** Write the counter's modifiers and its shown effect as its stacks now stand. */
    private static void write(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> holder,
            @Nonnull CounterConfig.Def def, @Nonnull State state) {
        EntityStatMap stats = accessor.getComponent(holder, EntityStatMap.getComponentType());
        if (stats != null) {
            String key = MODIFIER_PREFIX + def.key();
            Map<Integer, Double> sums = new HashMap<>();
            for (CounterAsset.PerStack row : def.asset().perStack()) {
                int index = StatIndexCache.resolve(row.stat());
                if (index != StatIndexCache.UNRESOLVED) {
                    sums.merge(index, row.amount() * state.stacks, Double::sum);
                }
            }
            for (Map.Entry<Integer, Double> sum : sums.entrySet()) {
                if (state.stacks > 0.0 && sum.getValue() != 0.0) {
                    stats.putModifier(sum.getKey(), key, new StaticModifier(Modifier.ModifierTarget.MAX,
                            StaticModifier.CalculationType.ADDITIVE, (float) (double) sum.getValue()));
                } else {
                    stats.removeModifier(sum.getKey(), key);
                }
            }
        }
        show(accessor, holder, def, state);
    }

    /** Swap the effect that shows the stacks when the count it shows moved. */
    private static void show(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> holder,
            @Nonnull CounterConfig.Def def, @Nonnull State state) {
        List<String> effects = def.asset().effects();
        int want = state.stacks > 0.0 && !effects.isEmpty()
                ? Math.min((int) Math.ceil(state.stacks - 1e-9), effects.size()) - 1 : -1;
        want = Math.max(want, state.stacks > 0.0 && !effects.isEmpty() ? 0 : -1);
        if (want == state.shown) {
            return;
        }
        if (state.shown >= 0 && state.shown < effects.size()) {
            NativeEffectUtil.remove(accessor, holder, effects.get(state.shown));
        }
        if (want >= 0) {
            NativeEffectUtil.applyInfinite(accessor, holder, effects.get(want));
        }
        state.shown = want;
    }

    /** Take every counter modifier and every counter's shown effect worn with no end off the holder. */
    private static void sweep(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> holder) {
        try {
            EntityStatMap stats = accessor.getComponent(holder, EntityStatMap.getComponentType());
            if (stats != null) {
                for (int i = 0; i < stats.size(); i++) {
                    EntityStatValue value = stats.get(i);
                    Map<String, Modifier> modifiers = value == null ? null : value.getModifiers();
                    if (modifiers == null || modifiers.isEmpty()) {
                        continue;
                    }
                    for (String key : new ArrayList<>(modifiers.keySet())) {
                        if (key != null && key.startsWith(MODIFIER_PREFIX)) {
                            stats.removeModifier(i, key);
                        }
                    }
                }
            }
            for (CounterConfig.Def def : CounterConfig.getInstance().liveAll().values()) {
                for (String effect : def.asset().effects()) {
                    Float remaining = NativeEffectUtil.remaining(accessor, holder, effect);
                    if (remaining != null && remaining.isInfinite()) {
                        NativeEffectUtil.remove(accessor, holder, effect);
                    }
                }
            }
        } catch (Throwable t) {
            Procs.failed("sweeping a holder's counters", t);
        }
    }

    /** Take one counter's modifiers off (its file is gone, so its rows are unknown: every key of its name goes). */
    private static void clearModifiers(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> holder, @Nonnull String counterKey) {
        EntityStatMap stats = accessor.getComponent(holder, EntityStatMap.getComponentType());
        if (stats == null) {
            return;
        }
        String key = MODIFIER_PREFIX + counterKey;
        for (int i = 0; i < stats.size(); i++) {
            EntityStatValue value = stats.get(i);
            Map<String, Modifier> modifiers = value == null ? null : value.getModifiers();
            if (modifiers != null && modifiers.containsKey(key)) {
                stats.removeModifier(i, key);
            }
        }
    }
}
