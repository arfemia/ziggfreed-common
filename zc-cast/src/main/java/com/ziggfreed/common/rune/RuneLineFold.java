package com.ziggfreed.common.rune;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.server.core.asset.type.item.config.CoreItemAbility;
import com.hypixel.hytale.server.core.asset.type.item.config.SupportItemAbility;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.hypixel.hytale.server.core.modules.projectile.component.ImpactModifiers;
import com.ziggfreed.common.util.SafeLog;

/**
 * What a rune line does to a cast of its core: the engine's own fold of a bench line ({@code ImpactModifiers.collect})
 * for a line the engine cannot read, a consumer's own ({@link RuneSockets}), so a cast that is no ability item's still
 * meets its line's supports the way the game's own runes meet theirs.
 *
 * <p><b>The engine's fold, line for line.</b> It starts from the core's {@code Attributes}; each support in socket order
 * that fits the core by the engine's own match ({@code SupportItemAbility.supports}, keyword against keyword, another
 * mod's runes included) applies its {@code AttributeModifiers} in turn (an additive one adds, a multiplicative one
 * multiplies, an attribute the core does not declare starting from 1), adds its {@code OnHitEffects}, and its
 * {@code ConvertElement} replaces any before it, so the rightmost conversion wins. A support that does not fit the core,
 * or an item that is no support rune, is skipped and changes nothing; it is never removed. The answer is the engine's
 * own {@link ImpactModifiers}, or null when it would carry nothing (no attribute, no on-hit effect, no conversion), as
 * the engine answers.
 *
 * <p>What a consumer does with the answer is its own: stamp it on the cast's chain and its hits ({@link RuneHits}), and
 * turn its attributes into its own numbers. Pure over what it is handed: it reads no player, no store and no switch.
 * Inert until a consumer folds a line.
 */
public final class RuneLineFold {

    private RuneLineFold() {
    }

    /**
     * Fold {@code supports} (socket order, an empty socket as null or an empty stack) onto {@code core}, the engine's
     * way: the resolved attributes, on-hit effects and element conversion a cast of the core carries with that line. Null
     * for no core, or when nothing would be carried.
     */
    @Nullable
    public static ImpactModifiers fold(@Nullable CoreItemAbility core, @Nullable List<ItemStack> supports) {
        List<SupportItemAbility> abilities = new ArrayList<>();
        if (supports != null) {
            for (ItemStack stack : supports) {
                abilities.add(supportOf(stack));
            }
        }
        return foldAbilities(core, abilities);
    }

    /**
     * {@link #fold} over the support runes' abilities themselves (socket order; a null is an empty socket or an item
     * that is no support), for a caller that holds the abilities rather than their stacks.
     */
    @Nullable
    public static ImpactModifiers foldAbilities(@Nullable CoreItemAbility core,
            @Nullable List<SupportItemAbility> supports) {
        if (core == null) {
            return null;
        }
        List<Support> line = new ArrayList<>();
        if (supports != null) {
            for (SupportItemAbility support : supports) {
                Support read = support == null ? null : read(core, support);
                if (read != null) {
                    line.add(read);
                }
            }
        }
        Folded folded = foldValues(core.getAttributes(), line);
        if (folded == null) {
            return null;
        }
        return new ImpactModifiers(folded.attributes(), folded.onHitEffectIds(), folded.elementalCauseId());
    }

    /** The support rune ability of one socketed stack, or null when it is empty or no support rune. */
    @Nullable
    private static SupportItemAbility supportOf(@Nullable ItemStack stack) {
        if (ItemStack.isEmpty(stack)) {
            return null;
        }
        try {
            return stack.getItem().getAbility() instanceof SupportItemAbility support ? support : null;
        } catch (Throwable t) {
            SafeLog.fine("[runes] a socketed " + stack.getItemId() + " could not be read as a support rune: "
                    + t.getMessage());
            return null;
        }
    }

    /** One support as the fold reads it, matched against {@code core} by the engine; null when it cannot be read. */
    @Nullable
    private static Support read(@Nonnull CoreItemAbility core, @Nonnull SupportItemAbility support) {
        try {
            Map<String, List<Step>> modifiers = new HashMap<>();
            Map<String, StaticModifier[]> authored = support.getAttributeModifiers();
            if (authored != null) {
                for (Map.Entry<String, StaticModifier[]> entry : authored.entrySet()) {
                    List<Step> steps = new ArrayList<>();
                    if (entry.getValue() != null) {
                        for (StaticModifier modifier : entry.getValue()) {
                            if (modifier != null && modifier.getCalculationType() != null) {
                                steps.add(new Step(modifier.getCalculationType(), modifier.getAmount()));
                            }
                        }
                    }
                    modifiers.put(entry.getKey(), steps);
                }
            }
            return new Support(support.supports(core), modifiers, support.getOnHitEffects(),
                    support.getConvertElement());
        } catch (Throwable t) {
            SafeLog.fine("[runes] a support rune could not be read: " + t.getMessage());
            return null;
        }
    }

    // ==================== The fold itself (pure) ====================

    /** One step of a support's modifier on one attribute: how it combines, and by how much. */
    record Step(@Nonnull StaticModifier.CalculationType type, float amount) {
    }

    /**
     * One support as the fold reads it: whether it fits the core, its attribute modifiers (attribute to its steps, in
     * order), its on-hit effect ids and its element conversion.
     */
    record Support(boolean fits, @Nonnull Map<String, List<Step>> modifiers, @Nullable String[] onHitEffects,
                   @Nullable String convertElement) {
    }

    /** What a line folds to: the resolved attributes (null when none), the on-hit effect ids and the conversion. */
    record Folded(@Nullable Map<String, Float> attributes, @Nullable String[] onHitEffectIds,
                  @Nullable String elementalCauseId) {
    }

    /**
     * The engine's {@code ImpactModifiers.collect} over plain values: {@code coreAttributes} folded with each fitting
     * support in order. Null when nothing would be carried.
     */
    @Nullable
    static Folded foldValues(@Nullable Map<String, Float> coreAttributes, @Nonnull List<Support> supports) {
        Map<String, Float> attributes = coreAttributes != null ? new HashMap<>(coreAttributes) : new HashMap<>();
        List<String> effectIds = null;
        String elementalCauseId = null;
        for (Support support : supports) {
            if (!support.fits()) {
                continue;
            }
            for (Map.Entry<String, List<Step>> entry : support.modifiers().entrySet()) {
                float value = attributes.getOrDefault(entry.getKey(), 1f);
                for (Step step : entry.getValue()) {
                    value = switch (step.type()) {
                        case ADDITIVE -> value + step.amount();
                        case MULTIPLICATIVE -> value * step.amount();
                    };
                }
                attributes.put(entry.getKey(), value);
            }
            String[] onHit = support.onHitEffects();
            if (onHit != null && onHit.length > 0) {
                if (effectIds == null) {
                    effectIds = new ArrayList<>();
                }
                Collections.addAll(effectIds, onHit);
            }
            if (support.convertElement() != null) {
                elementalCauseId = support.convertElement();
            }
        }
        if (attributes.isEmpty() && effectIds == null && elementalCauseId == null) {
            return null;
        }
        return new Folded(attributes.isEmpty() ? null : attributes,
                effectIds != null ? effectIds.toArray(String[]::new) : null, elementalCauseId);
    }
}
