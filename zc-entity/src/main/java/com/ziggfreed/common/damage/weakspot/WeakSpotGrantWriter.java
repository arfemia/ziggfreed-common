package com.ziggfreed.common.damage.weakspot;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.ziggfreed.common.grant.GrantWriter;
import com.ziggfreed.common.grant.Placed;
import com.ziggfreed.common.util.SafeLog;

/**
 * Writes {@link WeakSpotGrant}s: the rules each holder's grants add, kept per entity (by its UUID) and per key
 * ({@code <owner>:...}), and read per hit by {@link WeakSpotSystem}. Nothing is written on the entity itself, so
 * nothing is saved with it: the holder's own rebuild (zc-track's at every world entry) writes the rules again,
 * and a player's go when they leave ({@link #forget}). Transient and single-process by design. World thread.
 */
final class WeakSpotGrantWriter implements GrantWriter {

    static final WeakSpotGrantWriter INSTANCE = new WeakSpotGrantWriter();

    /** One holder's rules: the summed health threshold in percent and the effect ids, lower-cased. */
    record Rules(double healthBelow, @Nonnull Set<String> effects) {

        static final Rules NONE = new Rules(0.0, Set.of());

        boolean isEmpty() {
            return healthBelow <= 0.0 && effects.isEmpty();
        }
    }

    /** Each entity's rules, by key. */
    private static final Map<UUID, Map<String, Rules>> BY_ENTITY = new ConcurrentHashMap<>();

    /** Each entity's rules folded across keys, rebuilt on every write. */
    private static final Map<UUID, Rules> FOLDED = new ConcurrentHashMap<>();

    private WeakSpotGrantWriter() {
    }

    @Override
    public void write(@Nonnull Store<EntityStore> store, @Nonnull Ref<EntityStore> ref, @Nonnull String owner,
            @Nonnull List<Placed> placed) {
        UUID id = uuidOf(store, ref);
        if (id == null) {
            return;
        }
        String prefix = owner + ":";
        Map<String, Rules> held = new LinkedHashMap<>(BY_ENTITY.getOrDefault(id, Map.of()));
        held.keySet().removeIf(key -> key.startsWith(prefix));
        held.putAll(wanted(placed));
        if (held.isEmpty()) {
            BY_ENTITY.remove(id);
            FOLDED.remove(id);
            return;
        }
        BY_ENTITY.put(id, Map.copyOf(held));
        FOLDED.put(id, fold(held.values()));
    }

    /** What {@code placed} wants, per key: thresholds summed (times held, up to 100), effects joined. Pure. */
    @Nonnull
    static Map<String, Rules> wanted(@Nonnull List<Placed> placed) {
        Map<String, double[]> below = new LinkedHashMap<>();
        Map<String, Set<String>> effects = new LinkedHashMap<>();
        for (Placed one : placed) {
            if (!(one.grant() instanceof WeakSpotGrant grant) || one.count() <= 0) {
                continue;
            }
            below.computeIfAbsent(one.key(), k -> new double[1])[0] += grant.targetHealthBelow() * one.count();
            effects.computeIfAbsent(one.key(), k -> new HashSet<>()).addAll(grant.targetEffects());
        }
        Map<String, Rules> out = new LinkedHashMap<>();
        for (Map.Entry<String, double[]> entry : below.entrySet()) {
            Rules rules = new Rules(Math.min(100.0, entry.getValue()[0]), Set.copyOf(effects.get(entry.getKey())));
            if (!rules.isEmpty()) {
                out.put(entry.getKey(), rules);
            }
        }
        return out;
    }

    /** Every key's rules as one: thresholds summed up to 100, effects joined. Pure. */
    @Nonnull
    static Rules fold(@Nonnull Iterable<Rules> all) {
        double below = 0.0;
        Set<String> effects = new HashSet<>();
        for (Rules rules : all) {
            below += rules.healthBelow();
            effects.addAll(rules.effects());
        }
        return new Rules(Math.min(100.0, below), Set.copyOf(effects));
    }

    /** The rules the entity at {@code ref} holds now, or {@link Rules#NONE}. */
    @Nonnull
    static Rules rulesFor(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref) {
        if (FOLDED.isEmpty()) {
            return Rules.NONE;
        }
        UUID id = uuidOf(accessor, ref);
        Rules rules = id == null ? null : FOLDED.get(id);
        return rules == null ? Rules.NONE : rules;
    }

    /** Drop everything held for {@code id} (a player leaving). */
    static void forget(@Nullable UUID id) {
        if (id != null) {
            BY_ENTITY.remove(id);
            FOLDED.remove(id);
        }
    }

    /** {@link #forget} for the player a disconnect names. Never throws. */
    static void onDisconnect(@Nonnull PlayerDisconnectEvent event) {
        try {
            PlayerRef player = event.getPlayerRef();
            forget(player == null ? null : player.getUuid());
        } catch (Throwable t) {
            SafeLog.fine("[weakspot] a leaving player's grant rules could not be dropped: " + t.getMessage());
        }
    }

    @Nullable
    private static UUID uuidOf(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> ref) {
        if (!ref.isValid()) {
            return null;
        }
        UUIDComponent uuid = accessor.getComponent(ref, UUIDComponent.getComponentType());
        return uuid == null ? null : uuid.getUuid();
    }
}
