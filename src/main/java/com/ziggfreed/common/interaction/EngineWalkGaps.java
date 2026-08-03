package com.ziggfreed.common.interaction;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.data.StringTag;
import com.ziggfreed.common.util.SafeLog;

/**
 * The engine's chain-walk BLIND-SLOT table: child-bearing codec slots that
 * {@code InteractionManager.walkChain} never visits. Two mechanisms produce a gap: a Type that
 * adds a child field WITHOUT overriding {@code walk()} inherits {@code SimpleInteraction.walk}'s
 * Next/Failed-only visit (ApplyForce, MovementCondition, RunRootInteraction, RunOnBlockTypes),
 * and a Type whose OWN {@code walk()} override visits only SOME of its slots (Charging's walks
 * its charge-map {@code Next} + {@code Failed} but never {@code Forks}; Chaining's walks
 * {@code Next[]} but never {@code Flags}). Source-verified against shared source
 * {@code 6cdea5ead}, one row per confirmed gap:
 *
 * <ul>
 *   <li>{@code ApplyForceInteraction}: {@code GroundNext}/{@code CollisionNext}
 *       ({@code groundInteraction}/{@code collisionInteraction}, private, Interaction refs);</li>
 *   <li>{@code ChargingInteraction} (and inherited by {@code WieldingInteraction}):
 *       {@code Forks} ({@code forks}, {@code Map<InteractionType,String>} of RootInteraction
 *       refs); {@code WieldingInteraction} additionally {@code BlockedInteractions}
 *       ({@code blockedInteractions});</li>
 *   <li>{@code MovementConditionInteraction}: all 8 direction slots (private, Interaction
 *       refs);</li>
 *   <li>{@code ChainingInteraction}: {@code Flags} ({@code flags},
 *       {@code Map<String,String>});</li>
 *   <li>{@code RunRootInteraction}: {@code RootInteraction} ({@code rootInteraction}) - the
 *       Type's ENTIRE payload, invisible to both walk and the static
 *       {@code needsRemoteSync} scan;</li>
 *   <li>{@code RunOnBlockTypesInteraction}: {@code Interactions} ({@code interactions}).</li>
 * </ul>
 *
 * <p>{@code ChainWalker} consumes this table in a supplemental pass after the engine walk, so a
 * validator or renderer riding the walk sees the FULL authored tree (the motivating bug: native
 * {@code Selector} AOE sweeps hidden inside every slam body's {@code GroundNext} never reached
 * the ability validator). Fields are read reflectively (none of the gap slots has a public
 * getter except {@code WieldingInteraction.getBlockedInteractions}); a reflective failure on an
 * engine update degrades to "slot skipped, one guarded WARN per class", never a throw.
 *
 * <p>A slot value may be a {@code String} id, a {@code String[]}, or a {@code Map} whose VALUES
 * are ids; ids may name either an {@code Interaction} or a {@code RootInteraction} (the caller
 * dual-resolves - e.g. {@code Charging.Forks} values are root refs while
 * {@code ApplyForce.GroundNext} is an interaction ref).
 */
final class EngineWalkGaps {

    /**
     * One unwalked child reference: the target asset id, the slot's JSON-key tag, which store the
     * slot's codec declares ({@code rootRef}: a {@code RootInteraction} ref vs an
     * {@code Interaction} ref - the codecs are unambiguous, so resolution never guesses across
     * stores; {@code Goblin_Duke_Magic_1} exists in BOTH), and whether the ENGINE tolerates a
     * missing id on this slot ({@code tolerant}: the engine resolves it via a
     * {@code get*OrUnknown} stub, so a miss must degrade to skip, not abort - only the
     * {@code Interaction.CHILD_ASSET_CODEC} slots share {@code walkInteraction}'s throwing
     * contract).
     */
    record SlotRef(@Nonnull String id, @Nonnull StringTag tag, boolean rootRef, boolean tolerant) {
    }

    private record Slot(@Nonnull String fieldName, @Nonnull StringTag tag, boolean rootRef, boolean tolerant) {
    }

    private static final Map<String, List<Slot>> GAPS = Map.of(
            "ApplyForceInteraction", List.of(
                    new Slot("groundInteraction", StringTag.of("GroundNext"), false, false),
                    new Slot("collisionInteraction", StringTag.of("CollisionNext"), false, false)),
            "ChargingInteraction", List.of(
                    new Slot("forks", StringTag.of("Forks"), true, true)),
            "WieldingInteraction", List.of(
                    new Slot("forks", StringTag.of("Forks"), true, true),
                    new Slot("blockedInteractions", StringTag.of("BlockedInteractions"), true, true)),
            "MovementConditionInteraction", List.of(
                    new Slot("forward", StringTag.of("Forward"), false, false),
                    new Slot("back", StringTag.of("Back"), false, false),
                    new Slot("left", StringTag.of("Left"), false, false),
                    new Slot("right", StringTag.of("Right"), false, false),
                    new Slot("forwardLeft", StringTag.of("ForwardLeft"), false, false),
                    new Slot("forwardRight", StringTag.of("ForwardRight"), false, false),
                    new Slot("backLeft", StringTag.of("BackLeft"), false, false),
                    new Slot("backRight", StringTag.of("BackRight"), false, false)),
            "ChainingInteraction", List.of(
                    new Slot("flags", StringTag.of("Flags"), false, true)),
            "RunRootInteraction", List.of(
                    new Slot("rootInteraction", StringTag.of("RootInteraction"), true, true)),
            "RunOnBlockTypesInteraction", List.of(
                    new Slot("interactions", StringTag.of("Interactions"), true, true)));

    /** Classes already warned about (a renamed field on an engine update), to warn once each. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private EngineWalkGaps() {
    }

    /**
     * Every child reference {@code node} declares in an engine-walk-invisible slot, resolved from
     * the live field values. Empty for a Type with no known gap, and empty (plus one guarded WARN
     * per class, ever) when reflection fails.
     */
    @Nonnull
    static List<SlotRef> missedRefsOf(@Nonnull Interaction node) {
        List<Slot> slots = GAPS.get(node.getClass().getSimpleName());
        if (slots == null) {
            return List.of();
        }
        List<SlotRef> refs = new ArrayList<>();
        for (Slot slot : slots) {
            try {
                Object value = readField(node, slot.fieldName());
                appendIds(value, slot, refs);
            } catch (Throwable t) {
                if (WARNED.add(node.getClass().getSimpleName() + "#" + slot.fieldName())) {
                    SafeLog.warn("[interaction] engine-walk-gap slot unreadable: "
                            + node.getClass().getSimpleName() + "#" + slot.fieldName()
                            + " (engine update renamed it?) - subtree not walked", t);
                }
            }
        }
        return refs;
    }

    @Nullable
    private static Object readField(@Nonnull Interaction node, @Nonnull String fieldName)
            throws ReflectiveOperationException {
        for (Class<?> c = node.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(fieldName);
                f.setAccessible(true);
                return f.get(node);
            } catch (NoSuchFieldException e) {
                // keep climbing (WieldingInteraction reads Charging's `forks`)
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    private static void appendIds(@Nullable Object value, @Nonnull Slot slot, @Nonnull List<SlotRef> out) {
        switch (value) {
            case null -> { /* slot unauthored */ }
            case String s -> {
                if (!s.isBlank()) {
                    out.add(new SlotRef(s, slot.tag(), slot.rootRef(), slot.tolerant()));
                }
            }
            case String[] arr -> {
                for (String s : arr) {
                    appendIds(s, slot, out);
                }
            }
            case Map<?, ?> map -> {
                for (Object v : map.values()) {
                    if (v instanceof String s) {
                        appendIds(s, slot, out);
                    }
                }
            }
            default -> { /* not an id-bearing shape we know; leave unwalked */ }
        }
    }
}
