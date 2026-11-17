package com.ziggfreed.common.resource;

import java.util.Locale;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.protocol.ValueType;
import com.hypixel.hytale.server.core.modules.entitystats.asset.EntityStatType;
import com.ziggfreed.common.asset.EditorSchema;

/**
 * A cost drawn from a living entity's stat: which stat ({@code Stat}, any native {@code EntityStatType} id, vanilla's
 * or a pack's), how much ({@code Amount}) and in what unit ({@code ValueType}), in the engine's own words, the ones a
 * {@code StatsCondition} or {@code ChangeStat} step writes. {@link ResourceCosts} checks, drains and gives it back.
 *
 * <pre>{@code
 * { "Stat": "Mana", "Amount": 30 }
 * { "Stat": "Stamina", "Amount": 2.5 }
 * { "Stat": "SignatureEnergy", "Amount": 50, "ValueType": "Percent" }
 * }</pre>
 *
 * <p>{@code Absolute} (unwritten) takes {@code Amount} points of the stat. {@code Percent} takes that share of the
 * stat's span, its {@code Max} less its {@code Min}, as the engine's own {@code Percent} reads it: for a stat whose
 * {@code Min} is 0 (Health, Mana, the held weapon's {@code SignatureEnergy}) that is a share of its {@code Max}, so
 * 50 is half, whatever the stat's size on this entity. {@code Amount} is a number, not a whole one, so a cost a cut
 * leaves fractional (2.82 stamina) is paid as it is.
 *
 * <p>Embed {@link #CODEC} in any asset (every leaf is inherited through a native {@code Parent}, so a child that
 * writes only {@code Amount} keeps its parent's {@code Stat}); a consumer may extend this class to name the same three
 * leaves under its own type ({@code BuilderCodec.builder(Sub.class, Sub::new, ResourceCost.CODEC)}). A new resource
 * is one native stat JSON with its own {@code Regenerating}; nothing here needs wiring for it.
 *
 * <p>Not {@code zc-commerce}'s {@code Cost}, on purpose: that one is the price of a purchase, whole currencies and
 * items taken from a wallet. This one is a draw on a living body, fractional, that game rules may refund. Keep the
 * two apart rather than growing a third.
 */
public class ResourceCost {

    /** The {@code ValueType} word for points of the stat (the default). */
    public static final String ABSOLUTE = "Absolute";

    /** The {@code ValueType} word for a share of the stat's span. */
    public static final String PERCENT = "Percent";

    @Nullable protected String stat;
    @Nullable protected Double amount;
    @Nullable protected String valueType;

    public static final BuilderCodec<ResourceCost> CODEC = BuilderCodec.builder(ResourceCost.class, ResourceCost::new)
            .documentation("A cost drawn from a living entity's stat: which stat, how much, and whether that is "
                    + "points of it or a share of it.")
            .appendInherited(new KeyedCodec<>("Stat", Codec.STRING, false),
                    (o, v) -> o.stat = v, o -> o.stat, (o, p) -> o.stat = p.stat)
            .metadata(EditorSchema.assetRef(EntityStatType.class))
            .documentation("The stat the cost is drawn from, by id (an EntityStatType: Health, Stamina, Mana, "
                    + "SignatureEnergy, or one a pack ships). An entity without it cannot pay. The picker offers "
                    + "the loaded stats; a mod reading this cost may also accept older words of its own as aliases "
                    + "of them (MANA, HEALTH and STAMINA for Mana, Health and Stamina), which the picker does not "
                    + "list but which still load.").add()
            .appendInherited(new KeyedCodec<>("Amount", Codec.DOUBLE, false),
                    (o, v) -> o.amount = v, o -> o.amount, (o, p) -> o.amount = p.amount)
            .metadata(EditorSchema.defaultValue(0.0))
            .documentation("How much is drawn: points of the stat, or with ValueType Percent a share of it (50 is "
                    + "half). A fraction is paid as it is. 0 when unwritten, which costs nothing.").add()
            .appendInherited(new KeyedCodec<>("ValueType", Codec.STRING, false),
                    (o, v) -> o.valueType = v, o -> o.valueType, (o, p) -> o.valueType = p.valueType)
            .metadata(EditorSchema.oneOfDocumented(
                    ABSOLUTE, "Amount is points of the stat",
                    PERCENT, "Amount is a share of the stat's span (its Max less its Min; 100 is all of it)"))
            .metadata(EditorSchema.defaultValue(ABSOLUTE))
            .documentation("The engine's own words, as a StatsCondition or ChangeStat step writes them. Absolute "
                    + "when unwritten.").add()
            .build();

    public ResourceCost() {
    }

    /** Java-side factory (a consumer's folded cost, a test); sets the same fields the codec fills. */
    @Nonnull
    public static ResourceCost of(@Nullable String stat, double amount, @Nullable ValueType valueType) {
        ResourceCost cost = new ResourceCost();
        cost.stat = stat;
        cost.amount = amount;
        cost.valueType = valueType == null ? null : valueType.name();
        return cost;
    }

    /** {@code Stat} as written, or null. */
    @Nullable
    public String getStat() {
        return stat;
    }

    /** {@code Amount} as written, or null. */
    @Nullable
    public Double getAmount() {
        return amount;
    }

    /** {@code ValueType} as written, or null. */
    @Nullable
    public String getValueType() {
        return valueType;
    }

    /** The stat id as written, trimmed, or null when unwritten or blank. */
    @Nullable
    public String stat() {
        return stat == null || stat.isBlank() ? null : stat.trim();
    }

    /** The amount, 0 when unwritten or not a number. */
    public double amount() {
        return amount == null || !Double.isFinite(amount) ? 0.0 : amount;
    }

    /** The unit: {@link ValueType#Percent} when written so (any case), else {@link ValueType#Absolute}. */
    @Nonnull
    public ValueType valueType() {
        return percent() ? ValueType.Percent : ValueType.Absolute;
    }

    /** Is the amount a share of the stat's span ({@code ValueType} {@value #PERCENT}, any case)? */
    public boolean percent() {
        return valueType != null && PERCENT.equalsIgnoreCase(valueType.trim());
    }

    /**
     * Does {@code ValueType} read as one of the engine's two words (or is it unwritten)? False for a word that is
     * neither, which reads as {@value #ABSOLUTE}: what a content audit names.
     */
    public boolean valueTypeKnown() {
        if (valueType == null || valueType.isBlank()) {
            return true;
        }
        String word = valueType.trim().toLowerCase(Locale.ROOT);
        return word.equals(ABSOLUTE.toLowerCase(Locale.ROOT)) || word.equals(PERCENT.toLowerCase(Locale.ROOT));
    }

    /** Does this cost nothing: no stat, or an amount that is not above 0? */
    public boolean isFree() {
        return stat() == null || !(amount() > 0.0);
    }
}
