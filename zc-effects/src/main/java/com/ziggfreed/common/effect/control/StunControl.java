package com.ziggfreed.common.effect.control;

import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.assetstore.AssetRegistry;
import com.hypixel.hytale.builtin.npccombatactionevaluator.evaluator.CombatActionEvaluator;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.EntityEffect;
import com.hypixel.hytale.server.core.asset.type.entityeffect.config.OverlapBehavior;
import com.hypixel.hytale.server.core.entity.InteractionChain;
import com.hypixel.hytale.server.core.entity.InteractionManager;
import com.hypixel.hytale.server.core.entity.effect.EffectControllerComponent;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatMap;
import com.hypixel.hytale.server.core.modules.entitystats.EntityStatsModule;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.hypixel.hytale.server.core.modules.interaction.InteractionModule;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.RootInteraction;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.role.support.CombatSupport;
import com.ziggfreed.common.effect.NativeEffectUtil;
import com.ziggfreed.common.util.SafeLog;

/**
 * Stuns and staggers any entity, a creature or a player, the one way the family does it (the
 * {@code ZigStun} Type, Kweebec Nightmare's Moonbloom, the MMO's {@code MmoStun}).
 *
 * <p><b>What a stun is.</b> The native effect {@value #STUNNED_EFFECT} (a stagger {@value #STAGGERED_EFFECT}),
 * shipped by this library with the game's own {@code Stun} as its {@code Parent}, for the caller's duration and
 * attributed to the caller's source through the engine's {@code addEffect} owner overload. The effect is the whole
 * record: {@link #isStunned}, {@link #remainingSeconds} and {@link #wake} read and take it off the entity's own
 * effect controller, so nothing else is kept and nothing needs a tick. On top of the effect, the same call:
 * <ul>
 *   <li>cancels every interaction chain the entity is running whose root is not tagged
 *       {@value #UNINTERRUPTIBLE_TAG}, exactly as the native {@code Interrupt} interaction does with that
 *       {@code ExcludedTag} (vanilla's {@code Bomb_Explode_Stun}), so a swing in progress stops;</li>
 *   <li>on a creature, ends the combat action evaluator's current action and clears its primary target, and holds
 *       {@code CombatSupport}'s attack pause for the stun plus a short wake ({@value #STUN_WAKE_SECONDS} s, a
 *       stagger {@value #STAGGER_WAKE_SECONDS} s), which the engine's own attack paths ({@code ActionAttack}, the
 *       evaluator) read before starting another attack. The effect's {@code HorizontalSpeedMultiplier} 0 holds
 *       it in place: the server reads that off a creature's effects every tick.</li>
 * </ul>
 *
 * <p><b>On a player the stun is the player's own game's.</b> The effect's {@code MovementEffects.DisableAll} and
 * {@code AbilityEffects.Disabled} are applied by the client, and the server reads neither, so the server cannot stop
 * a client that ignores them from moving or starting a new attack. What the server does do on a player is cancel the
 * chains they have running, the same as on a creature.
 *
 * <p><b>Stun and stagger.</b> A stun over a stun refreshes it to the new length. A stagger never shortens or
 * replaces a stun (it is dropped), and over a stagger it only lengthens it. A stun over a stagger replaces it.
 *
 * <p>The native diminishing ramp (vanilla's bomb shape: an {@code Immune} target is skipped and a landed stun adds
 * {@code Immunity}, whose {@code MaxValueEffects} put {@code Immune} on at its {@code Max}) is the caller's to ask
 * for, through {@link #isImmune} and {@link #rampImmunity}; {@code ZigStun} does both.
 *
 * <p>World thread only. Every call takes a {@link ComponentAccessor} (a system's or an interaction's
 * {@code CommandBuffer}, or the {@code Store} outside a tick) and degrades to a false or zero answer, never a throw.
 */
public final class StunControl {

    /** The stun effect this library ships ({@code Parent: Stun}). */
    public static final String STUNNED_EFFECT = "Zig_Stunned";

    /** The stagger effect this library ships ({@code Parent: Zig_Stunned}). */
    public static final String STAGGERED_EFFECT = "Zig_Staggered";

    /** The game's own immunity effect, which the {@code Immunity} stat puts on at its {@code Max}. */
    public static final String IMMUNE_EFFECT = "Immune";

    /** The game's own stat the diminishing ramp writes. */
    public static final String IMMUNITY_STAT = "Immunity";

    /** The root tag a chain carries to survive a stun, spelled as the game's assets spell it. */
    public static final String UNINTERRUPTIBLE_TAG = "Uninterruptable";

    /** How long a creature stays unable to attack once a stun ends. */
    public static final float STUN_WAKE_SECONDS = 0.25f;

    /** How long a creature stays unable to attack once a stagger ends. */
    public static final float STAGGER_WAKE_SECONDS = 0.1f;

    private static final AtomicBoolean WARNED_NO_EFFECT = new AtomicBoolean();

    private static volatile int uninterruptibleTagIndex = AssetRegistry.TAG_NOT_FOUND;

    private StunControl() {
    }

    /**
     * Stun {@code target} for {@code seconds}, attributed to {@code source} when given.
     *
     * @return true when the stun landed (the effect went on)
     */
    public static boolean stun(@Nonnull ComponentAccessor<EntityStore> accessor, @Nullable Ref<EntityStore> target,
            float seconds, @Nullable Ref<EntityStore> source) {
        return apply(accessor, target, seconds, source, true);
    }

    /**
     * Stun {@code target} for {@code seconds} with the caller's own stun effect {@code effectId} in place of
     * {@value #STUNNED_EFFECT}: the same hold (its chains cancelled, a creature's action ended and its attacks paused
     * for the stun and the wake), under an effect the caller ships, built on the game's {@code Stun} as
     * {@value #STUNNED_EFFECT} is. For a stun that must stay exactly as long as asked, whatever reads the Stunned and
     * Control effect families (a carrier's control resistance cuts every member as it lands): ship the effect in no
     * family and stun with it. {@link #isStunned} and {@link #wake} know only this library's two effects, so the caller
     * reads and ends its own.
     *
     * @return true when the stun landed
     */
    public static boolean stun(@Nonnull ComponentAccessor<EntityStore> accessor, @Nullable Ref<EntityStore> target,
            float seconds, @Nullable Ref<EntityStore> source, @Nonnull String effectId) {
        if (!usable(target) || !(seconds > 0f) || effectId.isBlank()) {
            return false;
        }
        if (!addEffect(accessor, target, effectId.trim(), seconds, source != null && source.isValid() ? source : null)) {
            return false;
        }
        interrupt(accessor, target);
        stopCurrentAction(accessor, target);
        holdAttacks(accessor, target, seconds + STUN_WAKE_SECONDS);
        return true;
    }

    /**
     * Stagger {@code target} for {@code seconds}: the same hold as a stun, with a shorter wake, which never shortens or
     * replaces a stun already on it.
     *
     * @return true when the stagger landed
     */
    public static boolean stagger(@Nonnull ComponentAccessor<EntityStore> accessor, @Nullable Ref<EntityStore> target,
            float seconds, @Nullable Ref<EntityStore> source) {
        return apply(accessor, target, seconds, source, false);
    }

    /**
     * End a stun or stagger early: both effects come off, and a creature may attack again after the short wake.
     *
     * @return true when there was one to end
     */
    public static boolean wake(@Nonnull ComponentAccessor<EntityStore> accessor, @Nullable Ref<EntityStore> target) {
        if (!usable(target)) {
            return false;
        }
        boolean full = NativeEffectUtil.has(accessor, target, STUNNED_EFFECT);
        boolean staggered = NativeEffectUtil.has(accessor, target, STAGGERED_EFFECT);
        if (!full && !staggered) {
            return false;
        }
        NativeEffectUtil.remove(accessor, target, STUNNED_EFFECT);
        NativeEffectUtil.remove(accessor, target, STAGGERED_EFFECT);
        holdAttacks(accessor, target, full ? STUN_WAKE_SECONDS : STAGGER_WAKE_SECONDS);
        return true;
    }

    /** Whether {@code target} is stunned or staggered right now. */
    public static boolean isStunned(@Nonnull ComponentAccessor<EntityStore> accessor, @Nullable Ref<EntityStore> target) {
        return usable(target) && (NativeEffectUtil.has(accessor, target, STUNNED_EFFECT)
                || NativeEffectUtil.has(accessor, target, STAGGERED_EFFECT));
    }

    /** Whether {@code target} carries a full stun (not only a stagger) right now. */
    public static boolean isFullStun(@Nonnull ComponentAccessor<EntityStore> accessor, @Nullable Ref<EntityStore> target) {
        return usable(target) && NativeEffectUtil.has(accessor, target, STUNNED_EFFECT);
    }

    /** Whether {@code target} carries a stagger right now. */
    public static boolean isStaggered(@Nonnull ComponentAccessor<EntityStore> accessor, @Nullable Ref<EntityStore> target) {
        return usable(target) && NativeEffectUtil.has(accessor, target, STAGGERED_EFFECT);
    }

    /** The seconds left on {@code target}'s stun or stagger, 0 when it has neither. */
    public static float remainingSeconds(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nullable Ref<EntityStore> target) {
        if (!usable(target)) {
            return 0f;
        }
        Float stun = NativeEffectUtil.remaining(accessor, target, STUNNED_EFFECT);
        if (stun != null) {
            return Math.max(0f, stun);
        }
        Float stagger = NativeEffectUtil.remaining(accessor, target, STAGGERED_EFFECT);
        return stagger == null ? 0f : Math.max(0f, stagger);
    }

    /**
     * Whether {@code target} wears the game's {@value #IMMUNE_EFFECT} effect, which a stun should pass over (the
     * {@code EffectCondition} vanilla's bomb wraps its stun in). False whenever it cannot tell.
     */
    public static boolean isImmune(@Nonnull ComponentAccessor<EntityStore> accessor, @Nullable Ref<EntityStore> target) {
        return usable(target) && NativeEffectUtil.has(accessor, target, IMMUNE_EFFECT);
    }

    /**
     * Add {@code amount} to {@code target}'s {@value #IMMUNITY_STAT} stat, the native diminishing ramp: the stat's
     * own {@code MaxValueEffects} put {@value #IMMUNE_EFFECT} on once it fills. A target with no stat map, or a
     * server without the stat, is left as it is.
     *
     * @return true when the stat moved
     */
    public static boolean rampImmunity(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nullable Ref<EntityStore> target, float amount) {
        if (!usable(target) || amount == 0f) {
            return false;
        }
        try {
            int index = EntityStatType.getAssetMap().getIndex(IMMUNITY_STAT);
            if (index < 0) {
                return false;
            }
            EntityStatMap stats = accessor.getComponent(target, EntityStatsModule.get().getEntityStatMapComponentType());
            if (stats == null) {
                return false;
            }
            stats.addStatValue(index, amount);
            return true;
        } catch (Throwable t) {
            SafeLog.fine("[control] the immunity ramp failed: " + t.getMessage());
            return false;
        }
    }

    private static boolean apply(@Nonnull ComponentAccessor<EntityStore> accessor, @Nullable Ref<EntityStore> target,
            float seconds, @Nullable Ref<EntityStore> source, boolean full) {
        if (!usable(target) || !(seconds > 0f)) {
            return false;
        }
        if (!full) {
            if (NativeEffectUtil.has(accessor, target, STUNNED_EFFECT)) {
                return false;
            }
            Float staggerLeft = NativeEffectUtil.remaining(accessor, target, STAGGERED_EFFECT);
            if (staggerLeft != null && staggerLeft >= seconds) {
                return false;
            }
        }
        if (!addEffect(accessor, target, full ? STUNNED_EFFECT : STAGGERED_EFFECT, seconds,
                source != null && source.isValid() ? source : null)) {
            return false;
        }
        if (full && NativeEffectUtil.has(accessor, target, STAGGERED_EFFECT)) {
            NativeEffectUtil.remove(accessor, target, STAGGERED_EFFECT);
        }
        interrupt(accessor, target);
        stopCurrentAction(accessor, target);
        holdAttacks(accessor, target, seconds + (full ? STUN_WAKE_SECONDS : STAGGER_WAKE_SECONDS));
        return true;
    }

    private static boolean addEffect(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> target,
            @Nonnull String effectId, float seconds, @Nullable Ref<EntityStore> source) {
        try {
            int index = EntityEffect.getAssetMap().getIndex(effectId);
            EntityEffect effect = index == Integer.MIN_VALUE ? null : EntityEffect.getAssetMap().getAsset(index);
            if (effect == null) {
                if (WARNED_NO_EFFECT.compareAndSet(false, true)) {
                    SafeLog.warn("[control] the stun effect '" + effectId + "' is not loaded, so no stun lands");
                }
                return false;
            }
            EffectControllerComponent effects = accessor.getComponent(target, EffectControllerComponent.getComponentType());
            if (effects == null) {
                return false;
            }
            return effects.addEffect(target, effect, seconds, OverlapBehavior.OVERWRITE, accessor, source);
        } catch (Throwable t) {
            SafeLog.fine("[control] the stun effect '" + effectId + "' could not go on: " + t.getMessage());
            return false;
        }
    }

    /**
     * Cancel every chain {@code target} is running whose root is not tagged {@value #UNINTERRUPTIBLE_TAG}: the
     * native {@code Interrupt} interaction's own walk with that {@code ExcludedTag}.
     */
    private static void interrupt(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> target) {
        try {
            InteractionManager manager = accessor.getComponent(target,
                    InteractionModule.get().getInteractionManagerComponent());
            if (manager == null) {
                return;
            }
            int keep = uninterruptibleTag();
            for (InteractionChain chain : manager.getChains().values()) {
                if (chain != null && !carriesTag(chain, keep)) {
                    manager.cancelChains(chain);
                }
            }
        } catch (Throwable t) {
            SafeLog.fine("[control] could not interrupt a stunned entity's chains: " + t.getMessage());
        }
    }

    /** Whether the chain's root carries the tag; a root with no asset data carries none. */
    private static boolean carriesTag(@Nonnull InteractionChain chain, int tag) {
        RootInteraction root = chain.getInitialRootInteraction();
        if (root == null || root.getData() == null) {
            return false;
        }
        return root.getData().getExpandedTagIndexes().contains(tag);
    }

    /** End a creature's current combat action and forget its primary target, so it picks again once it wakes. */
    private static void stopCurrentAction(@Nonnull ComponentAccessor<EntityStore> accessor,
            @Nonnull Ref<EntityStore> target) {
        try {
            CombatActionEvaluator evaluator = accessor.getComponent(target, CombatActionEvaluator.getComponentType());
            if (evaluator != null) {
                evaluator.completeCurrentAction(true, true);
                evaluator.clearPrimaryTarget();
            }
        } catch (Throwable t) {
            SafeLog.fine("[control] could not stop a stunned creature's action: " + t.getMessage());
        }
    }

    /** Hold a creature's attack pause for {@code seconds}; a player has no {@code CombatSupport} and is passed over. */
    private static void holdAttacks(@Nonnull ComponentAccessor<EntityStore> accessor, @Nonnull Ref<EntityStore> target,
            float seconds) {
        try {
            CombatSupport combat = accessor.getComponent(target, CombatSupport.getComponentType());
            if (combat != null) {
                combat.setExecutingAttack(null, false, Math.max(0f, seconds));
            }
        } catch (Throwable t) {
            SafeLog.fine("[control] could not hold a stunned creature's attacks: " + t.getMessage());
        }
    }

    private static int uninterruptibleTag() {
        int index = uninterruptibleTagIndex;
        if (index == AssetRegistry.TAG_NOT_FOUND) {
            index = AssetRegistry.getOrCreateTagIndex(UNINTERRUPTIBLE_TAG);
            uninterruptibleTagIndex = index;
        }
        return index;
    }

    private static boolean usable(@Nullable Ref<EntityStore> ref) {
        return ref != null && ref.isValid();
    }
}
