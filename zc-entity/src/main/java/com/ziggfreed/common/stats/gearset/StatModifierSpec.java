package com.ziggfreed.common.stats.gearset;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.Modifier;
import com.hypixel.hytale.server.core.modules.entitystats.modifier.StaticModifier;
import com.ziggfreed.common.asset.EditorSchema;

/**
 * One stat modifier as an item authors it: the SAME three leaves a native item's
 * {@code StatModifiers} entry carries ({@code Amount}, {@code CalculationType}, {@code Target}),
 * spelled the same way, so a block copied out of an armor piece into a gear-set tier reads as it
 * did on the piece.
 *
 * <pre>{@code
 * "StatModifiers": { "Health": [ { "Amount": 4, "CalculationType": "Additive" } ] }
 * }</pre>
 *
 * <p>This library decodes the shape itself rather than through the engine's own
 * {@code StaticModifier.CODEC}, because a set file keeps the family's authoring rules where the
 * engine codec keeps its own: the engine matches {@code CalculationType} only in its exact spelling
 * (while it matches {@code Target} without regard to case) and requires it (a non-null validator),
 * and its enum schema gives the Asset Editor neither a line per word nor a default. Here both words
 * are CLOSED {@code EditorSchema.oneOfDocumented} dropdowns, each word documented and its default
 * stated, matched without regard to case; an unknown word fails the read naming the file, exactly
 * as the engine's enum codec would, since a modifier that silently read as Additive would pay the
 * wrong number. Unauthored, {@code CalculationType} reads Additive and {@code Target} reads Max (the
 * engine's own {@code Modifier} default); an unauthored {@code Amount} reads 0 and changes nothing,
 * as it does on an item. What goes on the stat map is still the engine's own value:
 * {@link #toModifier()} builds a {@code StaticModifier}.
 */
public final class StatModifierSpec {

    /** Added to the channel. */
    public static final String ADDITIVE = "Additive";

    /** The channel scaled by the amount, after every additive modifier has been added. */
    public static final String MULTIPLICATIVE = "Multiplicative";

    /** The channel's maximum, the bound almost every gear stat moves. */
    public static final String MAX = "Max";

    /** The channel's minimum. */
    public static final String MIN = "Min";

    @Nullable protected Float amount;
    @Nullable protected String calculationType;
    @Nullable protected String target;

    public static final BuilderCodec<StatModifierSpec> CODEC =
            BuilderCodec.builder(StatModifierSpec.class, StatModifierSpec::new)
                    .append(new KeyedCodec<>("Amount", Codec.FLOAT, false),
                            (o, v) -> o.amount = v, o -> o.amount)
                    .documentation("How much. Added to the channel for Additive; for Multiplicative the "
                            + "fraction the channel is scaled by (0.1 is ten percent more), applied after every "
                            + "additive modifier. Unauthored reads 0 and changes nothing, as on an item.")
                    .add()
                    .append(new KeyedCodec<>("CalculationType", Codec.STRING, false),
                            (o, v) -> o.calculationType = word(v, "CalculationType", ADDITIVE, MULTIPLICATIVE),
                            o -> o.calculationType)
                    .metadata(EditorSchema.oneOfDocumented(
                            ADDITIVE, "Added to the channel.",
                            MULTIPLICATIVE, "Scales the channel by the amount, after the additive modifiers; on a "
                                    + "channel whose base is 0 it does nothing."))
                    .metadata(EditorSchema.defaultValue(ADDITIVE))
                    .documentation("Additive or Multiplicative, the item's own two words. Unauthored reads "
                            + "Additive. Any other word fails the read.")
                    .add()
                    .append(new KeyedCodec<>("Target", Codec.STRING, false),
                            (o, v) -> o.target = word(v, "Target", MAX, MIN), o -> o.target)
                    .metadata(EditorSchema.oneOfDocumented(
                            MAX, "The channel's maximum, which is what a gear stat almost always moves.",
                            MIN, "The channel's minimum."))
                    .metadata(EditorSchema.defaultValue(MAX))
                    .documentation("Max or Min, the item's own two words. Unauthored reads Max. Any other word "
                            + "fails the read.")
                    .add()
                    .build();

    public StatModifierSpec() {
    }

    /** Java-side factory; sets the same fields the codec fills. */
    @Nonnull
    public static StatModifierSpec of(float amount, @Nullable String calculationType, @Nullable String target) {
        StatModifierSpec s = new StatModifierSpec();
        s.amount = amount;
        s.calculationType = word(calculationType, "CalculationType", ADDITIVE, MULTIPLICATIVE);
        s.target = word(target, "Target", MAX, MIN);
        return s;
    }

    /** An additive modifier on the channel's maximum, the common case. */
    @Nonnull
    public static StatModifierSpec additive(float amount) {
        return of(amount, ADDITIVE, MAX);
    }

    /** The authored amount, 0 when none was written. */
    public float amount() {
        return amount == null ? 0f : amount;
    }

    /** The calculation word as authored and canonicalised, or null for unauthored (Additive). */
    @Nullable
    public String getCalculationType() {
        return calculationType;
    }

    /** The target word as authored and canonicalised, or null for unauthored (Max). */
    @Nullable
    public String getTarget() {
        return target;
    }

    /** True when this modifier scales rather than adds. */
    public boolean isMultiplicative() {
        return MULTIPLICATIVE.equals(calculationType);
    }

    /** The engine's calculation type: Additive unless Multiplicative was authored. */
    @Nonnull
    public StaticModifier.CalculationType calculationType() {
        return isMultiplicative() ? StaticModifier.CalculationType.MULTIPLICATIVE
                : StaticModifier.CalculationType.ADDITIVE;
    }

    /** The engine's target: Max unless Min was authored. */
    @Nonnull
    public Modifier.ModifierTarget target() {
        return MIN.equals(target) ? Modifier.ModifierTarget.MIN : Modifier.ModifierTarget.MAX;
    }

    /** The native modifier this spec authors, for a keyed put on an entity's stat map. */
    @Nonnull
    public StaticModifier toModifier() {
        return new StaticModifier(target(), calculationType(), amount());
    }

    /**
     * {@code value} as one of {@code allowed}, matched without regard to case and returned in the
     * canonical spelling; null stays null (unauthored). Any other word is refused, and the refusal
     * surfaces as the file failing to read with the leaf named.
     */
    @Nullable
    static String word(@Nullable String value, @Nonnull String leaf, @Nonnull String... allowed) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        for (String candidate : allowed) {
            if (candidate.equalsIgnoreCase(trimmed)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException(leaf + " must be one of " + String.join(" / ", allowed)
                + ", not '" + value + "'");
    }
}
