package com.ziggfreed.common.effect.buff;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.cast.WorldEvictors;
import com.ziggfreed.common.effect.NativeEffectUtil;
import com.ziggfreed.common.util.SafeLog;

/**
 * The timed-buff binding: what consumers report as timed buffs (a boost, a blessing) shows on each
 * player's native buff bar, one tile per buff, its ring carrying the buff's real remaining time.
 *
 * <p>A consumer {@link #register}s a {@link TimedBuffSource} once, at setup, and calls
 * {@link #refresh} for a player (or {@link #refreshAllOnline}) whenever its buffs change. The library
 * refreshes on its own at every player-ready (each login and world entry), after the consumer's own
 * ready work has queued, and after every respawn (the engine clears every effect on death and again on
 * respawn). With no source registered, nothing happens at all.
 *
 * <p>Each refresh, on the player's world thread ({@link BuffPlan}): every live buff takes its effect,
 * the one its source names or a slot of its scope's default pool ({@link BuffScope}, seated by
 * {@code SlotPlan}); a tile that is missing, or whose time strays more than
 * {@link BuffPlan#DRIFT_SECONDS} from its buff's, is applied with the buff's remaining time and
 * {@code OVERWRITE}, so a buff that ticked down offline shows its real time after a relog; and every
 * default pool id and every id the player's {@link BuffSlotsComponent} names that no live buff wants
 * comes off. Nothing is applied while the player is dead; the respawn refresh puts it back.
 */
public final class TimedBuffs {

    private static final CopyOnWriteArrayList<TimedBuffSource> SOURCES = new CopyOnWriteArrayList<>();

    /** The source classes already named for a throw, so a broken source is named once, not per refresh. */
    private static final Set<String> WARNED_SOURCES = ConcurrentHashMap.newKeySet();

    private TimedBuffs() {
    }

    /**
     * Add a source; the same instance twice is added once. Call at setup. Sources are asked in the
     * order they registered, and together decide every tile the binding answers for.
     */
    public static void register(@Nonnull TimedBuffSource source) {
        if (source == null) {
            return;
        }
        SOURCES.addIfAbsent(source);
    }

    /**
     * Re-sync one player's buff tiles on their world thread, after anything queued there before it.
     * Safe from any thread. Nothing happens when no source is registered or the ref is gone.
     */
    public static void refresh(@Nullable Ref<EntityStore> ref) {
        if (SOURCES.isEmpty() || ref == null || !ref.isValid()) {
            return;
        }
        try {
            WorldEvictors.worldOf(ref).execute(() -> refreshNow(ref));
        } catch (Throwable t) {
            // FINE: a stopping world refuses every task, once per player online, and nothing is lost.
            SafeLog.fine("[buff] could not queue a buff refresh: " + t.getMessage());
        }
    }

    /**
     * Re-sync every online player's buff tiles, each on their own world thread: after a change that
     * reaches everyone, such as a server-wide buff starting or ending. Before the universe exists
     * there is nobody to refresh.
     */
    public static void refreshAllOnline() {
        if (SOURCES.isEmpty()) {
            return;
        }
        Universe universe = Universe.get();
        if (universe == null) {
            return;
        }
        try {
            for (PlayerRef player : universe.getPlayers()) {
                refresh(player == null ? null : player.getReference());
            }
        } catch (Throwable t) {
            SafeLog.warn("[buff] could not refresh every online player: " + t.getMessage());
        }
    }

    /** Every player-ready (a login, a world entry): the tiles travel with the player, the time does not. */
    static void onPlayerReady(@Nonnull PlayerReadyEvent event) {
        refresh(event.getPlayerRef());
    }

    /** A player respawned; see {@link TimedBuffRespawnSystem}. */
    static void onRespawned(@Nonnull Ref<EntityStore> ref) {
        refresh(ref);
    }

    /** One refresh, on the player's world thread. */
    private static void refreshNow(@Nonnull Ref<EntityStore> ref) {
        if (!ref.isValid()) {
            return;
        }
        Store<EntityStore> store = ref.getStore();
        try {
            if (store.getComponent(ref, DeathComponent.getComponentType()) != null) {
                return;
            }
            List<TimedBuff> buffs = ask(store, ref);
            if (buffs == null) {
                return;
            }
            BuffSlotsComponent slots = slotsOf(store, ref);
            long now = System.currentTimeMillis();
            BuffPlan.Plan plan = BuffPlan.plan(buffs, slots == null ? Map.of() : slots.all(), now);
            for (String effect : plan.removes()) {
                if (NativeEffectUtil.has(store, ref, effect)) {
                    NativeEffectUtil.remove(store, ref, effect);
                }
            }
            for (Map.Entry<String, Long> wanted : plan.wanted().entrySet()) {
                String effect = wanted.getKey();
                float target = BuffPlan.secondsLeft(wanted.getValue(), now);
                if (BuffPlan.drifted(NativeEffectUtil.remaining(store, ref, effect), target)) {
                    NativeEffectUtil.applyFor(store, ref, effect, target, OverlapBehavior.OVERWRITE);
                }
            }
            if (slots != null) {
                slots.record(plan.record());
            }
        } catch (Throwable t) {
            SafeLog.warn("[buff] buff refresh failed: " + t.getMessage());
        }
    }

    /** Every source's buffs for the player, or null when one threw (this refresh then changes nothing). */
    @Nullable
    private static List<TimedBuff> ask(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        List<TimedBuff> buffs = new ArrayList<>();
        for (TimedBuffSource source : SOURCES) {
            try {
                Collection<TimedBuff> answered = source.buffsOf(store, ref);
                if (answered != null) {
                    buffs.addAll(answered);
                }
            } catch (Throwable t) {
                String name = source.getClass().getName();
                if (WARNED_SOURCES.add(name)) {
                    SafeLog.warn("[buff] the timed-buff source " + name + " failed, so its player's tiles are"
                            + " left as they are: " + t.getMessage());
                }
                return null;
            }
        }
        return buffs;
    }

    /**
     * The player's saved slot record, or null when the type is not registered or the player carries
     * none. Read and replaced in place only: the connect hook attaches it.
     */
    @Nullable
    private static BuffSlotsComponent slotsOf(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        ComponentType<EntityStore, BuffSlotsComponent> type = BuffSlotsComponent.getComponentType();
        return type == null ? null : store.getComponent(ref, type);
    }
}
