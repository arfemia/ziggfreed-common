package com.ziggfreed.common.effect.control;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.joml.Vector3d;

import com.hypixel.hytale.builtin.npccombatactionevaluator.evaluator.CombatActionEvaluator;
import com.hypixel.hytale.builtin.npccombatactionevaluator.memory.TargetMemory;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.server.core.asset.type.attitude.Attitude;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.blackboard.Blackboard;
import com.hypixel.hytale.server.npc.blackboard.view.attitude.AttitudeView;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import com.hypixel.hytale.server.npc.role.support.MarkedEntitySupport;
import com.ziggfreed.common.util.SafeLog;

/**
 * Runs the world's open aggro windows once per tick ({@link AggroControl}): it walks the world's creatures once and,
 * for every window that reaches one, re-points a taunted creature at its taunter or wipes a suppressed or dropped
 * source from its target slots, target memory and combat evaluator. It records which creatures each window reached,
 * which is what the attitude provider ({@link AggroAttitudes}) answers from between sweeps.
 *
 * <p>The engine's query-less {@link TickingSystem}, called once per world store per tick; a world with no window
 * open costs one resource read. Every write here is a field of a component the creature already has (its
 * {@code MarkedEntitySupport}, {@code TargetMemory}, {@code CombatActionEvaluator}), never a component added or
 * removed, so the walk is safe inside the store's own iteration.
 */
public final class AggroSweepSystem extends TickingSystem<EntityStore> {

    /** Whether a sweep failure was already named at WARN this process. */
    private static final AtomicBoolean SWEEP_FAILURE_WARNED = new AtomicBoolean();

    @Override
    public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
        AggroWindows windows = AggroWindows.of(store);
        if (windows == null || windows.isEmpty()) {
            return;
        }
        List<AggroWindow> live = windows.live(System.nanoTime());
        if (!live.isEmpty()) {
            try {
                sweep(store, windows, live);
            } catch (Throwable t) {
                // The first failure is named at WARN so a systematic misuse shows at the default level.
                if (SWEEP_FAILURE_WARNED.compareAndSet(false, true)) {
                    SafeLog.warn("[control] the aggro sweep failed (later failures log at FINE): "
                            + t.getMessage(), t);
                } else {
                    SafeLog.fine("[control] the aggro sweep failed this tick: " + t.getMessage());
                }
            }
        }
        windows.closeDrops();
    }

    private static void sweep(@Nonnull Store<EntityStore> store, @Nonnull AggroWindows windows,
            @Nonnull List<AggroWindow> live) {
        ComponentType<EntityStore, NPCEntity> npcType = NPCEntity.getComponentType();
        if (npcType == null) {
            return;
        }
        Parts parts = new Parts(store);
        for (AggroWindow window : live) {
            window.beginSweep(positionOf(store, window.source()));
        }
        store.forEachChunk(npcType, (chunk, buffer) -> {
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                visit(chunk, i, store, windows, live, parts, npcType);
            }
        });
    }

    private static void visit(@Nonnull ArchetypeChunk<EntityStore> chunk, int index, @Nonnull Store<EntityStore> store,
            @Nonnull AggroWindows windows, @Nonnull List<AggroWindow> live, @Nonnull Parts parts,
            @Nonnull ComponentType<EntityStore, NPCEntity> npcType) {
        Ref<EntityStore> creature = chunk.getReferenceTo(index);
        TransformComponent transform = chunk.getComponent(index, TransformComponent.getComponentType());
        Vector3d position = transform == null ? null : transform.getPosition();
        for (AggroWindow window : live) {
            if (!window.reaches(creature, position)) {
                continue;
            }
            try {
                if (window.kind() == AggroWindow.Kind.TAUNT) {
                    NPCEntity npc = chunk.getComponent(index, npcType);
                    if (takesTaunt(store, windows, parts, creature, npc, window.source())) {
                        pointAt(chunk, index, parts, window.source());
                        window.reached(creature);
                    }
                } else {
                    forget(chunk, index, parts, window.source());
                    window.reached(creature);
                }
            } catch (Throwable t) {
                SafeLog.fine("[control] the aggro sweep skipped one creature: " + t.getMessage());
            }
        }
    }

    /**
     * Whether a creature takes a taunt: its attitude toward the taunter, asked of the game's other providers with
     * this library's standing aside, is hostile or neutral. A friendly, revered or ignoring creature is left alone;
     * with no attitude view to ask, every creature takes it.
     */
    private static boolean takesTaunt(@Nonnull Store<EntityStore> store, @Nonnull AggroWindows windows,
            @Nonnull Parts parts, @Nonnull Ref<EntityStore> creature, @Nullable NPCEntity npc,
            @Nonnull Ref<EntityStore> taunter) {
        if (parts.attitudes == null) {
            return true;
        }
        Attitude own;
        windows.muted = true;
        try {
            own = parts.attitudes.getAttitude(creature, npc == null ? -1 : npc.getRoleIndex(), taunter, store);
        } finally {
            windows.muted = false;
        }
        return own == Attitude.HOSTILE || own == Attitude.NEUTRAL;
    }

    /**
     * Point a creature at its taunter: its default target slot, its target memory (remembered as a hostile, the way
     * the engine's own target collector records one, and as its closest).
     */
    private static void pointAt(@Nonnull ArchetypeChunk<EntityStore> chunk, int index, @Nonnull Parts parts,
            @Nonnull Ref<EntityStore> taunter) {
        MarkedEntitySupport marked = parts.marked == null ? null : chunk.getComponent(index, parts.marked);
        if (marked != null) {
            marked.setMarkedEntity(MarkedEntitySupport.DEFAULT_TARGET_SLOT, taunter);
        }
        TargetMemory memory = parts.memory == null ? null : chunk.getComponent(index, parts.memory);
        if (memory != null) {
            if (memory.getKnownHostiles().put(taunter.getIndex(), memory.getRememberFor()) <= 0) {
                memory.getKnownHostilesList().add(taunter);
            }
            memory.setClosestHostile(taunter);
        }
    }

    /** Wipe {@code source} from a creature: every target slot holding it, its target memory, its evaluator. */
    private static void forget(@Nonnull ArchetypeChunk<EntityStore> chunk, int index, @Nonnull Parts parts,
            @Nonnull Ref<EntityStore> source) {
        MarkedEntitySupport marked = parts.marked == null ? null : chunk.getComponent(index, parts.marked);
        if (marked != null) {
            int slots = marked.getMarkedEntitySlotCount();
            for (int slot = 0; slot < slots; slot++) {
                if (AggroWindows.sameEntity(marked.getMarkedEntityRef(slot), source)) {
                    marked.clearMarkedEntity(slot);
                }
            }
        }
        TargetMemory memory = parts.memory == null ? null : chunk.getComponent(index, parts.memory);
        if (memory != null) {
            memory.getKnownHostiles().remove(source.getIndex());
            memory.getKnownHostilesList().removeIf(known -> AggroWindows.sameEntity(known, source));
            if (AggroWindows.sameEntity(memory.getClosestHostile(), source)) {
                memory.setClosestHostile(null);
            }
        }
        CombatActionEvaluator evaluator = parts.evaluator == null ? null : chunk.getComponent(index, parts.evaluator);
        if (evaluator != null && AggroWindows.sameEntity(evaluator.getPrimaryTarget(), source)) {
            evaluator.clearPrimaryTarget();
        }
    }

    @Nullable
    private static Vector3d positionOf(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref) {
        try {
            TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
            Vector3d live = transform == null ? null : transform.getPosition();
            return live == null ? null : new Vector3d(live);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The engine parts one sweep reads, each resolved once and null when its plugin is not running (the combat
     * evaluator's components without NPCCombatActionEvaluator, the attitude view without a blackboard).
     */
    private static final class Parts {

        @Nullable
        final ComponentType<EntityStore, MarkedEntitySupport> marked;
        @Nullable
        final ComponentType<EntityStore, TargetMemory> memory;
        @Nullable
        final ComponentType<EntityStore, CombatActionEvaluator> evaluator;
        @Nullable
        final AttitudeView attitudes;

        Parts(@Nonnull Store<EntityStore> store) {
            this.marked = resolve(MarkedEntitySupport::getComponentType);
            this.memory = resolve(TargetMemory::getComponentType);
            this.evaluator = resolve(CombatActionEvaluator::getComponentType);
            this.attitudes = resolve(() -> store.getResource(Blackboard.getResourceType()).getView(AttitudeView.class, 0));
        }

        @Nullable
        private static <T> T resolve(@Nonnull Supplier<T> lookup) {
            try {
                return lookup.get();
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
